package ptpip

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
)

// SonyContent is one entry of the 0x923C contents list. A
// content groups the files of one shot (e.g. HIF + ARW).
type SonyContent struct {
	Type, ID, DirNumber, FileNumber, GroupType, GroupID uint32
	Representative                                      bool
	CreatedUTC, ModifiedUTC                             time.Time // real UTC
	CreatedLocal, ModifiedLocal                         time.Time // camera wall-clock time stored as UTC: not an instant
	Rating                                              int32
	Protected, Dummy                                    bool
	ShotMarks                                           []byte
	Files                                               []SonyContentFile
}

// SonyContentFile is one file of a content.
type SonyContentFile struct {
	ID            uint16
	Path          string // as sent, e.g. "A:/DCIM/100MSDCF/DSC00001.JPG"
	Format        uint32
	Size          uint64
	UMID          [32]byte
	Width, Height uint32 // 0 if the camera sent no image parameters
	HasVideo      bool
	HasAudio      bool
}

const (
	sonyListHeader   = 0x90
	sonyListPageSize = 100
	sonyVideoParam   = 0x4C
	sonyAudioParam   = 0x10
)

func msTime(ms uint64) time.Time { return time.UnixMilli(int64(ms)).UTC() }

// parseSonyContentsInfoList decodes a 0x923C reply (layout in docs/protocol.md).
func parseSonyContentsInfoList(b []byte) ([]SonyContent, error) {
	if len(b) < sonyListHeader+4 {
		return nil, fmt.Errorf("ptpip: contents list too short (%d bytes)", len(b))
	}
	r := &reader{b: b, off: sonyListHeader}
	n := int(r.u32())
	var out []SonyContent
	for i := 0; i < n && r.err == nil; i++ {
		c := SonyContent{
			Type: r.u32(), ID: r.u32(), DirNumber: r.u32(), FileNumber: r.u32(), GroupType: r.u32(), GroupID: r.u32(),
			Representative: r.u32() != 0,
			CreatedUTC:     msTime(r.u64()), ModifiedUTC: msTime(r.u64()), CreatedLocal: msTime(r.u64()), ModifiedLocal: msTime(r.u64()),
			Rating: int32(r.u32()), Protected: r.u32() != 0, Dummy: r.u32() != 0,
		}
		if marks := int(r.u32()); r.need(marks) {
			c.ShotMarks = append([]byte(nil), r.b[r.off:r.off+marks]...)
			r.off += marks
		}
		files := int(r.u32())
		for j := 0; j < files && r.err == nil; j++ {
			f := SonyContentFile{ID: uint16(r.u32())} // u16 in a 4-byte slot
			if l := int(r.u32()); r.need(l) {
				f.Path = strings.TrimRight(string(r.b[r.off:r.off+l]), "\x00")
				r.off += l
			}
			f.Format, f.Size = r.u32(), r.u64()
			if r.need(len(f.UMID)) {
				copy(f.UMID[:], r.b[r.off:])
				r.off += len(f.UMID)
			}
			if r.u32() != 0 {
				f.Width, f.Height = r.u32(), r.u32()
			}
			if r.u32() != 0 && r.need(sonyVideoParam) {
				f.HasVideo = true
				r.off += sonyVideoParam
			}
			if r.u32() != 0 && r.need(sonyAudioParam) {
				f.HasAudio = true
				r.off += sonyAudioParam
			}
			c.Files = append(c.Files, f)
		}
		out = append(out, c)
	}
	if r.err != nil {
		return nil, fmt.Errorf("ptpip: parse contents list: %w", r.err)
	}
	return out, nil
}

// EncodeSonyContentsInfoList serialises a contents list (fake responders, tests).
// Video and audio parameters are written as zeros.
func EncodeSonyContentsInfoList(cs []SonyContent) []byte {
	le := binary.LittleEndian
	b := le.AppendUint32(nil, 100)
	b = append(b, make([]byte, sonyListHeader-4)...)
	b = le.AppendUint32(b, uint32(len(cs)))
	flag := func(v bool) uint32 {
		if v {
			return 1
		}
		return 0
	}
	for _, c := range cs {
		for _, v := range []uint32{c.Type, c.ID, c.DirNumber, c.FileNumber, c.GroupType, c.GroupID, flag(c.Representative)} {
			b = le.AppendUint32(b, v)
		}
		for _, t := range []time.Time{c.CreatedUTC, c.ModifiedUTC, c.CreatedLocal, c.ModifiedLocal} {
			b = le.AppendUint64(b, uint64(t.UnixMilli()))
		}
		for _, v := range []uint32{uint32(c.Rating), flag(c.Protected), flag(c.Dummy), uint32(len(c.ShotMarks))} {
			b = le.AppendUint32(b, v)
		}
		b = append(b, c.ShotMarks...)
		b = le.AppendUint32(b, uint32(len(c.Files)))
		for _, f := range c.Files {
			b = le.AppendUint32(b, uint32(f.ID))
			b = le.AppendUint32(b, uint32(len(f.Path)+1))
			b = append(append(b, f.Path...), 0)
			b = le.AppendUint32(b, f.Format)
			b = le.AppendUint64(b, f.Size)
			b = append(b, f.UMID[:]...)
			if f.Width != 0 || f.Height != 0 {
				b = le.AppendUint32(b, 1)
				b = le.AppendUint32(b, f.Width)
				b = le.AppendUint32(b, f.Height)
			} else {
				b = le.AppendUint32(b, 0)
			}
			for _, p := range []struct {
				on bool
				n  int
			}{{f.HasVideo, sonyVideoParam}, {f.HasAudio, sonyAudioParam}} {
				b = le.AppendUint32(b, flag(p.on))
				if p.on {
					b = append(b, make([]byte, p.n)...)
				}
			}
		}
	}
	return b
}

// Errors of the contents operations (0x923B-0x923E). They wrap the PTP
// error, so IsCode still works.
var (
	// ErrSlotEmpty: no card in the slot, or the camera has only one slot
	// (InvalidParameter, StoreNotAvailable).
	ErrSlotEmpty = errors.New("no memory card in this slot")
	// ErrCardUnavailable: the camera refuses card access right now
	// (OperationNotSupported, DeviceBusy). The session itself stays usable;
	// retrying at once gets the same answer.
	ErrCardUnavailable = errors.New("the camera does not allow card access right now")
)

// cardErr classifies the answer of a contents operation.
func cardErr(err error) error {
	if IsCode(err, RespOperationNotSupported) || IsCode(err, RespDeviceBusy) {
		return fmt.Errorf("%w: %w", ErrCardUnavailable, err)
	}
	return err
}

// listErr is cardErr for the date list, where InvalidParameter means the slot
// (on a contents page or a download it can mean a stale cursor or ID instead,
// and must not look like an empty card).
func listErr(err error) error {
	if IsCode(err, RespInvalidParameter) || IsCode(err, RespStoreNotAvailable) {
		return fmt.Errorf("%w: %w", ErrSlotEmpty, err)
	}
	return cardErr(err)
}

// SonyGetCapturedDateList returns the capture dates of a slot (0x923B). Like
// the Created/Modified*Local times they are camera wall-clock time stored as UTC.
func (c *Client) SonyGetCapturedDateList(ctx context.Context, slot uint32) ([]time.Time, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetCapturedDateList, []uint32{slot}, nil, &buf); err != nil {
		return nil, listErr(err)
	}
	r := &reader{b: buf.Bytes()}
	n := int(r.u32())
	if !r.need(8 * n) {
		return nil, fmt.Errorf("ptpip: parse captured date list: %w", r.err)
	}
	out := make([]time.Time, n)
	for i := range out {
		out[i] = msTime(r.u64())
	}
	return out, nil
}

// SonyGetContentsInfoList fetches one page (up to 100 contents) with
// CreatedUTC after the given ms timestamp (0 = from the start).
func (c *Client) SonyGetContentsInfoList(ctx context.Context, slot uint32, after uint64) ([]SonyContent, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpSonyGetContentsInfoList, []uint32{uint32(after), uint32(after >> 32), sonyListPageSize, slot, 0}, nil, &buf); err != nil {
		return nil, cardErr(err)
	}
	return parseSonyContentsInfoList(buf.Bytes())
}

// SonyListAllContents pages through the whole slot, deduplicated by content ID.
//
// An empty or missing slot answers the date list (0x923B) with
// InvalidParameter but leaves a contents list request (0x923C) unanswered
// until the I/O timeout, which ends the session. So the date list goes first;
// it also answers how many contents there are, and an empty slot needs no
// contents request at all. Its errors are ErrSlotEmpty or ErrCardUnavailable
// where they apply.
func (c *Client) SonyListAllContents(ctx context.Context, slot uint32) ([]SonyContent, error) {
	dates, err := c.SonyGetCapturedDateList(ctx, slot)
	if err != nil {
		return nil, err
	}
	if len(dates) == 0 {
		return nil, nil
	}
	const maxPages = 10000
	var all []SonyContent
	seen := map[uint32]bool{}
	var after uint64
	for page := 0; page < maxPages; page++ {
		cs, err := c.SonyGetContentsInfoList(ctx, slot, after)
		if err != nil {
			return nil, err
		}
		for _, ct := range cs {
			if !seen[ct.ID] {
				seen[ct.ID] = true
				all = append(all, ct)
			}
		}
		if len(cs) < sonyListPageSize {
			return all, nil
		}
		// The next page starts after the last content's creation time.
		next := uint64(cs[len(cs)-1].CreatedUTC.UnixMilli())
		if next <= after {
			return all, fmt.Errorf("ptpip: contents list cursor stuck at %d after %d contents; listing incomplete", after, len(all))
		}
		after = next
	}
	return all, fmt.Errorf("ptpip: contents list has more than %d pages", maxPages)
}

// SonyGetContentsData downloads up to size bytes of a content file at a 64-bit
// offset (0x923D) and returns the bytes written. The response parameters are a
// timestamp, not a length. last marks the final chunk of the file.
func (c *Client) SonyGetContentsData(ctx context.Context, slot, contentID uint32, fileID uint16, offset uint64, size uint32, last bool, w io.Writer) (uint32, error) {
	if last {
		size |= 1 << 31
	}
	cw := &countingWriter{w: w}
	_, err := c.Transaction(ctx, OpSonyGetContentsData, []uint32{contentID, slot<<24 | uint32(fileID), uint32(offset), uint32(offset >> 32), size}, nil, cw)
	return uint32(cw.n), cardErr(err)
}

// 0x923E data types.
const (
	SonyThumbnail  uint32 = 1 // a6700: 160x120 JPEG for RAW, 320x212 HEIF for HEIF
	SonyScreennail uint32 = 2 // a6700: 1616x1080 HEIF
)

// SonyGetContentsCompressed writes a content file's thumbnail or screennail
// (0x923E) to w and returns its size.
func (c *Client) SonyGetContentsCompressed(ctx context.Context, slot, contentID uint32, fileID uint16, typ uint32, w io.Writer) (int64, error) {
	cw := &countingWriter{w: w}
	_, err := c.Transaction(ctx, OpSonyGetContentsCompressedData, []uint32{contentID, slot<<24 | uint32(fileID), typ}, nil, cw)
	return cw.n, cardErr(err)
}

// SonyDownloadContentFile transfers a whole file in 4 MiB chunks.
func (c *Client) SonyDownloadContentFile(ctx context.Context, slot, contentID uint32, fileID uint16, size uint64, w io.Writer, progress func(int64)) (int64, error) {
	const chunk = 4 << 20
	var done uint64
	for done < size {
		// Never ask past the end, flag the final chunk.
		want := uint32(min(uint64(chunk), size-done))
		n, err := c.SonyGetContentsData(ctx, slot, contentID, fileID, done, want, done+uint64(want) >= size, w)
		done += uint64(n)
		if err != nil {
			return int64(done), err
		}
		if n == 0 {
			return int64(done), errors.New("ptpip: camera returned no data")
		}
		if n > want {
			return int64(done), fmt.Errorf("ptpip: camera sent %d bytes for a %d byte request", n, want)
		}
		if progress != nil {
			progress(int64(done))
		}
	}
	return int64(done), nil
}
