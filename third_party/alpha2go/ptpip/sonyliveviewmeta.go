package ptpip

import "fmt"

// SonyLiveViewInfo is the decoded overlay metadata of a live-view frame.
type SonyLiveViewInfo struct {
	Version  uint16
	Level    *SonyLevel  // nil before version 0x68
	Focus    []SonyFrame // AF area frames
	Faces    []SonyFrame // face/eye frames, from version 0x65
	Tracking []SonyFrame // from version 0x65
}

// SonyLevel is the camera's electronic level; the axes are degrees in
// -180..180, valid when the camera reports it as on.
type SonyLevel struct {
	State   uint32
	X, Y, Z int32
}

// SonyFrame is one overlay rectangle. X, Y are its centre and W, H its size,
// all in units of XMax by YMax.
type SonyFrame struct {
	Type, State uint16
	X, Y, W, H  uint32
	XMax, YMax  uint32
}

// sonyMetaBlockHdr and sonyMetaEntry are the sizes of a frame block's header and entries.
const (
	sonyMetaBlockHdr = 16
	sonyMetaEntry    = 24
)

// ParseSonyLiveViewMeta decodes SonyLiveViewFrame.Meta (little endian).
func ParseSonyLiveViewMeta(b []byte) (SonyLiveViewInfo, error) {
	r := &reader{b: b}
	info := SonyLiveViewInfo{Version: r.u16()}
	if r.err == nil && info.Version > 0x27 {
		if info.Version > 0x67 {
			r.off = 0x10
			l := SonyLevel{State: r.u32()}
			r.u32()
			l.X, l.Y, l.Z = int32(r.u32()), int32(r.u32()), int32(r.u32())
			r.off = 0x28 // the level is padded to 0x18 bytes
			info.Level = &l
		} else {
			r.off = 0x28
		}
		r.sonyFrameBlock() // unknown content
		info.Focus = r.sonyFrameBlock()
		if info.Version > 0x64 {
			info.Faces = r.sonyFrameBlock()
			info.Tracking = r.sonyFrameBlock()
		}
	}
	if r.err != nil {
		return SonyLiveViewInfo{}, fmt.Errorf("ptpip: parse Sony live-view meta: %w", r.err)
	}
	return info, nil
}

func (r *reader) sonyFrameBlock() []SonyFrame {
	xmax, ymax := r.u32(), r.u32()
	n := int(r.u16())
	if !r.need(sonyMetaBlockHdr - 10 + n*sonyMetaEntry) {
		return nil
	}
	r.off += sonyMetaBlockHdr - 10
	var out []SonyFrame
	for range n {
		f := SonyFrame{XMax: xmax, YMax: ymax, Type: r.u16(), State: r.u16()}
		r.off += 4
		f.X, f.Y, f.H, f.W = r.u32(), r.u32(), r.u32(), r.u32()
		out = append(out, f)
	}
	return out
}
