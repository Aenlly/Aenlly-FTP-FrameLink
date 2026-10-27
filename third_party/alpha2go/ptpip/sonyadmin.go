package ptpip

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"time"
)

// SonyPropOSDMode is the on-screen-display mode (u8, 0 or 1). The OSD image
// is only available while it is 1.
const SonyPropOSDMode = 0xD207

// SonySetOSDMode switches the OSD mode (prop 0xD207).
func (c *Client) SonySetOSDMode(ctx context.Context, on bool) error {
	v := int64(0)
	if on {
		v = 1
	}
	return c.SonySetProp(ctx, SonyPropOSDMode, 2, SonyValue{Int: v})
}

// SonyGetOSDImage fetches the camera's OSD picture (0x9238; PNG, plus
// metadata). It needs the OSD mode on: with it off the a6700 sends megabytes
// of garbage and drops the connection, so the operation is never sent then.
func (c *Client) SonyGetOSDImage(ctx context.Context) (SonyLiveViewFrame, error) {
	p, err := c.SonyGetProp(ctx, SonyPropOSDMode)
	if err != nil {
		return SonyLiveViewFrame{}, err
	}
	if p.Current.Int != 1 {
		return SonyLiveViewFrame{}, errors.New("ptpip: OSD mode is off; enable it with SonySetOSDMode first")
	}
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetOSDImage, nil, nil, &buf); err != nil {
		return SonyLiveViewFrame{}, err
	}
	return readSonyLiveViewFrame(&buf)
}

const sonyTimeLayout = "20060102T150405.0"

// SonyTimeZoneAt builds the camera time zone for a host time, in the form
// the camera reports it (see SonyTimeZone.Time).
func SonyTimeZoneAt(t time.Time) SonyTimeZone {
	_, secs := t.Zone()
	dst := t.IsDST()
	if dst {
		secs -= 3600
	}
	sign := '+'
	if secs < 0 {
		sign, secs = '-', -secs
	}
	return SonyTimeZone{DateTime: t.Format(sonyTimeLayout), Area: fmt.Sprintf("%c%02d%02d", sign, secs/3600, secs%3600/60), DST: dst}
}

func encodeSonyTimeZone(tz SonyTimeZone) ([]byte, error) {
	if _, err := time.Parse(sonyTimeLayout, tz.DateTime); err != nil || len(tz.DateTime) != 17 {
		return nil, fmt.Errorf("ptpip: bad time zone date-time %q (want YYYYMMDDThhmmss.s)", tz.DateTime)
	}
	if _, err := time.Parse("-0700", tz.Area); err != nil || len(tz.Area) != 5 {
		return nil, fmt.Errorf("ptpip: bad time zone area %q (want +hhmm or -hhmm)", tz.Area)
	}
	b := binary.LittleEndian.AppendUint32(nil, 100) // version
	b = append(b, 1)
	b = append(append(b, tz.DateTime...), 0)
	b = append(b, 1)
	b = append(append(b, tz.Area...), 0)
	b = append(b, 1, 0)
	if tz.DST {
		b[len(b)-1] = 1
	}
	return b, nil
}

// SonySetTimeZone sets the camera clock, UTC offset and DST flag (0x9249).
func (c *Client) SonySetTimeZone(ctx context.Context, tz SonyTimeZone) error {
	b, err := encodeSonyTimeZone(tz)
	if err != nil {
		return err
	}
	_, err = c.Transaction(ctx, OpSonySetTimeZone, nil, b, nil)
	return err
}

// SonyDeleteContent deletes a content (all its files) from a card slot
// (0x9250). This cannot be undone.
func (c *Client) SonyDeleteContent(ctx context.Context, slot, contentID uint32) error {
	_, err := c.Transaction(ctx, OpSonyDeleteContent, []uint32{contentID, slot}, nil, nil)
	return err
}

func encodeSonyLUTUpload(name string, data []byte) []byte {
	nameLen := uint32(len(name) + 1)
	b := binary.LittleEndian.AppendUint32(nil, 100)
	b = binary.LittleEndian.AppendUint32(b, 0x18)
	b = binary.LittleEndian.AppendUint32(b, nameLen)
	b = binary.LittleEndian.AppendUint32(b, 0x18+nameLen)
	b = binary.LittleEndian.AppendUint32(b, uint32(len(data)))
	b = binary.LittleEndian.AppendUint32(b, 0)
	b = append(append(b, name...), 0)
	return append(b, data...)
}

func encodeSonyLUTSelect(number uint16) []byte {
	return binary.LittleEndian.AppendUint16(binary.LittleEndian.AppendUint32(nil, 100), number)
}

// SonyImportLUT uploads a LUT file into user slot number (1..16) in two
// steps (0x921A, 0x921B). The camera reports the result asynchronously as
// event 0xC214 (result, 0x20000): 1 OK, 2 failed, 3 bad file name, 4 busy.
// The a6700 is busy unless its mode dial is on movie.
func (c *Client) SonyImportLUT(ctx context.Context, number uint16, name string, data []byte) error {
	if number < 1 || number > 16 {
		return fmt.Errorf("ptpip: LUT number %d out of range 1..16", number)
	}
	if name == "" || len(name) > 255 || strings.ContainsAny(name, `/\`) {
		return fmt.Errorf("ptpip: bad LUT file name %q", name)
	}
	for i := 0; i < len(name); i++ {
		if name[i] < 0x20 || name[i] > 0x7E {
			return fmt.Errorf("ptpip: LUT file name %q is not ASCII", name)
		}
	}
	if len(data) == 0 {
		return errors.New("ptpip: empty LUT file")
	}
	if _, err := c.Transaction(ctx, OpSonyUploadData, []uint32{0x20001}, encodeSonyLUTUpload(name, data), nil); err != nil {
		return err
	}
	_, err := c.Transaction(ctx, OpSonyControlUploadData, []uint32{0x20000}, encodeSonyLUTSelect(number), nil)
	return err
}

// sonyRestorePace is the pause between the phases of the settings upload;
// the camera expects them. A variable for tests.
var sonyRestorePace = time.Second

// SonyRestoreSettings uploads a settings backup (see SonyDownloadSettings)
// to the camera. The camera checks the model itself and reports the result
// asynchronously as event 0xC209 (result): 1 OK, 2 failed.
func (c *Client) SonyRestoreSettings(ctx context.Context, data []byte) error {
	if len(data) < 64 || !bytes.HasPrefix(data, []byte("SONY1")) {
		return errors.New("ptpip: not a Sony settings backup")
	}
	_, err := c.transaction(ctx, OpSendObject, []uint32{sonySettingsHandle}, data, nil, sonyRestorePace)
	return err
}
