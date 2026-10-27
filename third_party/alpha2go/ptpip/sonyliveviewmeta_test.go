package ptpip_test

import (
	"encoding/binary"
	"encoding/hex"
	"reflect"
	"strings"
	"testing"

	"github.com/knallli/alpha2go/ptpip"
)

// a6700 sample: version 0x68, level on, four empty frame blocks.
const a6700Meta = `68 00 02 00 00 00 00 00 01 00 00 00 00 00 00 00
02 00 00 00 00 00 00 00 fc ff ff ff fb ff ff ff
ff 7f 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00 00 00 0a 00 00 80 07 00
00 00 00 00 00 00 00 00 00 00 0a 00 00 80 07 00
00 00 00 00 00 00 00 00 00 00 0a 00 00 80 07 00
00 00 00 00 00 00 00 00 00 00 0a 00 00 80 07 00
00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00`

func TestParseSonyLiveViewMetaA6700(t *testing.T) {
	b, err := hex.DecodeString(strings.Join(strings.Fields(a6700Meta), ""))
	if err != nil || len(b) != 136 {
		t.Fatalf("sample: %d bytes, %v", len(b), err)
	}
	got, err := ptpip.ParseSonyLiveViewMeta(b)
	if err != nil {
		t.Fatal(err)
	}
	want := ptpip.SonyLiveViewInfo{Version: 0x68, Level: &ptpip.SonyLevel{State: 2, X: -4, Y: -5, Z: 32767}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v (level %+v), want %+v", got, got.Level, want)
	}
}

func metaBlock(xmax, ymax uint32, entries ...[6]uint32) []byte {
	le := binary.LittleEndian
	b := le.AppendUint32(nil, xmax)
	b = le.AppendUint32(b, ymax)
	b = le.AppendUint16(b, uint16(len(entries)))
	b = append(b, make([]byte, 6)...)
	for _, e := range entries { // type, state, x, y, h, w
		b = le.AppendUint16(b, uint16(e[0]))
		b = le.AppendUint16(b, uint16(e[1]))
		b = append(b, 0, 0, 0, 0)
		for _, v := range e[2:] {
			b = le.AppendUint32(b, v)
		}
	}
	return b
}

func synthMeta(version uint16) []byte {
	b := binary.LittleEndian.AppendUint16(nil, version)
	b = append(b, make([]byte, 0x28-2)...)
	b = append(b, metaBlock(1, 1)...) // skipped
	b = append(b, metaBlock(100, 50, [6]uint32{1, 2, 10, 20, 4, 6})...)
	b = append(b, metaBlock(100, 50, [6]uint32{3, 4, 30, 40, 8, 12})...)
	b = append(b, metaBlock(100, 50, [6]uint32{5, 6, 50, 5, 2, 3})...)
	return b
}

func TestParseSonyLiveViewMetaSynthetic(t *testing.T) {
	focus := ptpip.SonyFrame{Type: 1, State: 2, X: 10, Y: 20, H: 4, W: 6, XMax: 100, YMax: 50}
	face := ptpip.SonyFrame{Type: 3, State: 4, X: 30, Y: 40, H: 8, W: 12, XMax: 100, YMax: 50}
	track := ptpip.SonyFrame{Type: 5, State: 6, X: 50, Y: 5, H: 2, W: 3, XMax: 100, YMax: 50}
	for _, tc := range []struct {
		v    uint16
		want ptpip.SonyLiveViewInfo
	}{
		{0x68, ptpip.SonyLiveViewInfo{Version: 0x68, Level: &ptpip.SonyLevel{}, Focus: []ptpip.SonyFrame{focus}, Faces: []ptpip.SonyFrame{face}, Tracking: []ptpip.SonyFrame{track}}},
		{0x66, ptpip.SonyLiveViewInfo{Version: 0x66, Focus: []ptpip.SonyFrame{focus}, Faces: []ptpip.SonyFrame{face}, Tracking: []ptpip.SonyFrame{track}}},
		{0x64, ptpip.SonyLiveViewInfo{Version: 0x64, Focus: []ptpip.SonyFrame{focus}}},
		{0x27, ptpip.SonyLiveViewInfo{Version: 0x27}},
	} {
		got, err := ptpip.ParseSonyLiveViewMeta(synthMeta(tc.v))
		if err != nil {
			t.Fatalf("v%#x: %v", tc.v, err)
		}
		if !reflect.DeepEqual(got, tc.want) {
			t.Errorf("v%#x: got %+v, want %+v", tc.v, got, tc.want)
		}
	}
}

func TestParseSonyLiveViewMetaTruncated(t *testing.T) {
	full := synthMeta(0x68)
	for _, n := range []int{0, 1, 0x12, 0x27, 0x30, len(full) - 1} {
		if _, err := ptpip.ParseSonyLiveViewMeta(full[:n]); err == nil {
			t.Errorf("len %d: no error", n)
		}
	}
	// Oversized count must fail, not allocate or panic.
	bad := append([]byte(nil), full...)
	binary.LittleEndian.PutUint16(bad[0x28+8:], 0xFFFF)
	if _, err := ptpip.ParseSonyLiveViewMeta(bad); err == nil {
		t.Error("oversized count: no error")
	}
}
