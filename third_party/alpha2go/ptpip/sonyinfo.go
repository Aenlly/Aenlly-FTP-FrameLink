package ptpip

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"strings"
	"time"
)

// SonyGetProp reads one extended device property (0x9251).
func (c *Client) SonyGetProp(ctx context.Context, code uint16) (SonyProp, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetExtDeviceProp, []uint32{uint32(code)}, nil, &buf); err != nil {
		return SonyProp{}, err
	}
	r := &reader{b: buf.Bytes()}
	p := r.sonyProp()
	if r.err != nil {
		return SonyProp{}, fmt.Errorf("ptpip: parse Sony prop: %w", r.err)
	}
	return p, nil
}

// SonyTimeZone is the 0x9248 reply: camera wall-clock time, UTC offset and DST flag.
type SonyTimeZone struct {
	DateTime string // "YYYYMMDDThhmmss.s"
	Area     string // "+hhmm"
	DST      bool
}

// Time parses DateTime as wall time. Area is the standard offset; DST adds an
// hour (a6700 in CEST: Area "+0100", DST on, wall time = UTC+2).
func (tz SonyTimeZone) Time() (time.Time, error) {
	off, err := time.Parse("-0700", tz.Area)
	if err != nil {
		return time.Time{}, fmt.Errorf("ptpip: bad time zone area %q: %w", tz.Area, err)
	}
	_, secs := off.Zone()
	if tz.DST {
		secs += 3600
	}
	return time.ParseInLocation("20060102T150405.0", tz.DateTime, time.FixedZone("", secs))
}

func (r *reader) cstr() string {
	i := bytes.IndexByte(r.b[min(r.off, len(r.b)):], 0)
	if i < 0 {
		r.need(len(r.b) - r.off + 1)
		return ""
	}
	s := string(r.b[r.off : r.off+i])
	r.off += i + 1
	return s
}

func parseSonyTimeZone(b []byte) (SonyTimeZone, error) {
	r := &reader{b: b}
	r.u32() // version
	tz := SonyTimeZone{DateTime: r.cstr(), Area: r.cstr(), DST: r.u8() == 1}
	if r.err != nil {
		return SonyTimeZone{}, fmt.Errorf("ptpip: parse time zone: %w", r.err)
	}
	return tz, nil
}

// SonyGetTimeZone reads the camera clock (0x9248).
func (c *Client) SonyGetTimeZone(ctx context.Context) (SonyTimeZone, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetTimeZone, nil, nil, &buf); err != nil {
		return SonyTimeZone{}, err
	}
	return parseSonyTimeZone(buf.Bytes())
}

// SonyOperationResult names a property/control code and the operation (0x9205
// or 0x9207) that changes it.
type SonyOperationResult struct{ Code, Op uint16 }

func parseSonyOperationResults(b []byte) ([]SonyOperationResult, error) {
	r := &reader{b: b}
	n := int(r.u32())
	if !r.need(4 * n) {
		return nil, fmt.Errorf("ptpip: parse operation results: %w", r.err)
	}
	out := make([]SonyOperationResult, n)
	for i := range out {
		out[i] = SonyOperationResult{r.u16(), r.u16()}
	}
	return out, nil
}

// SonyGetOperationResults lists the codes for which the camera reports operation results (0x922F).
func (c *Client) SonyGetOperationResults(ctx context.Context) ([]SonyOperationResult, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetOperationResults, nil, nil, &buf); err != nil {
		return nil, err
	}
	return parseSonyOperationResults(buf.Bytes())
}

func parseSonyFTPServerNames(b []byte) ([]string, error) {
	r := &reader{b: b}
	r.u32() // header size
	r.u32() // payload size
	var out []string
	for i, n := 0, int(r.u8()); i < n && r.err == nil; i++ {
		out = append(out, r.str())
	}
	if r.err != nil {
		return nil, fmt.Errorf("ptpip: parse FTP server names: %w", r.err)
	}
	return out, nil
}

// SonyGetFTPServerNames lists the FTP server setting names (0x920E).
func (c *Client) SonyGetFTPServerNames(ctx context.Context) ([]string, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetFTPServerNames, nil, nil, &buf); err != nil {
		return nil, err
	}
	return parseSonyFTPServerNames(buf.Bytes())
}

// SonyStringItem is one value and its display text.
type SonyStringItem struct {
	Value int64
	Text  string
}

// SonyStringList is the display texts for the values of one property/setting type.
type SonyStringList struct {
	Type     uint32
	DataType uint16 // PTP datatype of the item values
	Items    []SonyStringItem
}

func parseSonyDisplayStrings(b []byte) ([]SonyStringList, error) {
	r := &reader{b: b}
	r.u32() // header size
	r.u32() // payload size
	var out []SonyStringList
	for i, n := 0, int(r.u32()); i < n && r.err == nil; i++ {
		l := SonyStringList{Type: r.u32(), DataType: r.u16()}
		if sonyScalarSize(l.DataType) == 0 && r.err == nil {
			r.err = fmt.Errorf("unsupported datatype 0x%04X", l.DataType)
		}
		for j, m := 0, int(r.u16()); j < m && r.err == nil; j++ {
			it := SonyStringItem{Value: r.sonyScalar(l.DataType)}
			if tl := int(r.u16()); r.need(tl) {
				it.Text = strings.TrimRight(string(r.b[r.off:r.off+tl]), "\x00")
				r.off += tl
			}
			l.Items = append(l.Items, it)
		}
		out = append(out, l)
	}
	if r.err == nil && r.off != len(b) {
		r.err = fmt.Errorf("%d trailing bytes", len(b)-r.off)
	}
	if r.err != nil {
		return nil, fmt.Errorf("ptpip: parse display strings: %w", r.err)
	}
	return out, nil
}

// SonyGetDisplayStrings reads the display text lists (0x9215); typ 0 = all lists.
func (c *Client) SonyGetDisplayStrings(ctx context.Context, typ uint32) ([]SonyStringList, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetDisplayStrings, []uint32{typ}, nil, &buf); err != nil {
		return nil, err
	}
	return parseSonyDisplayStrings(buf.Bytes())
}

// SonyGetDeviceDescription returns the camera's XML device description (0x923A).
func (c *Client) SonyGetDeviceDescription(ctx context.Context) ([]byte, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetDeviceDescription, []uint32{2}, nil, &buf); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

const sonySettingsHandle = 0xFFFFC004

// SonyDownloadSettings saves the camera settings backup. Without the
// GetObjectInfo first the a6700 answers GetObject with InvalidObjectHandle.
func (c *Client) SonyDownloadSettings(ctx context.Context, w io.Writer) (int64, error) {
	if _, err := c.GetObjectInfo(ctx, sonySettingsHandle); err != nil {
		return 0, err
	}
	return c.GetObject(ctx, sonySettingsHandle, w, nil)
}
