package ptpip

import (
	"encoding/binary"
	"slices"
	"testing"
)

func le(parts ...any) []byte {
	var b []byte
	for _, p := range parts {
		switch v := p.(type) {
		case uint8:
			b = append(b, v)
		case uint16:
			b = binary.LittleEndian.AppendUint16(b, v)
		case uint32:
			b = binary.LittleEndian.AppendUint32(b, v)
		case uint64:
			b = binary.LittleEndian.AppendUint64(b, v)
		case string:
			b = PutString(b, v)
		}
	}
	return b
}

func TestPropsSupportedFixture(t *testing.T) {
	// a6700 reply for format 0x3801
	b := []byte{0x0c, 0, 0, 0, 0x01, 0xdc, 0x02, 0xdc, 0x03, 0xdc, 0x04, 0xdc, 0x07, 0xdc, 0x08, 0xdc,
		0x87, 0xdc, 0x88, 0xdc, 0xd3, 0xdc, 0xaf, 0xd8, 0xb0, 0xd8, 0xb1, 0xd8}
	r := &reader{b: b}
	got := r.u16array()
	want := []uint16{0xDC01, 0xDC02, 0xDC03, 0xDC04, 0xDC07, 0xDC08, 0xDC87, 0xDC88, 0xDCD3, 0xD8AF, 0xD8B0, 0xD8B1}
	if err := r.done(); err != nil || !slices.Equal(got, want) {
		t.Fatalf("%X, %v", got, err)
	}
}

func TestParseObjectPropDesc(t *testing.T) {
	d, err := parseObjectPropDesc(le(uint16(0xDC87), uint16(6), uint8(0), uint32(0), uint32(7), uint8(1),
		uint32(1), uint32(9999), uint32(2)))
	if err != nil || d.Code != 0xDC87 || d.Writable || d.Form != 1 || d.Min.Int != 1 || d.Max.Int != 9999 || d.Step.Int != 2 || d.Group != 7 {
		t.Fatalf("range: %+v, %v", d, err)
	}
	d, err = parseObjectPropDesc(le(uint16(0xDC02), uint16(4), uint8(1), uint16(0x3000), uint32(0), uint8(2),
		uint16(2), uint16(0x3000), uint16(0x3801)))
	if err != nil || !d.Writable || len(d.Enum) != 2 || d.Enum[1].Int != 0x3801 {
		t.Fatalf("enum: %+v, %v", d, err)
	}
	d, err = parseObjectPropDesc(le(uint16(0xDC07), uint16(0xFFFF), uint8(0), "", uint32(0), uint8(0)))
	if err != nil || d.Type != ptpString || d.Default.Str != "" || d.Form != 0 {
		t.Fatalf("string: %+v, %v", d, err)
	}
	d, err = parseObjectPropDesc(le(uint16(0xDC41), uint16(0xFFFF), uint8(0), "x", uint32(0), uint8(0xFF), uint32(255)))
	if err != nil || d.Default.Str != "x" || d.MaxLen != 255 {
		t.Fatalf("long string: %+v, %v", d, err)
	}
	if _, err = parseObjectPropDesc(le(uint16(1), uint16(4), uint8(0), uint16(0), uint32(0), uint8(9))); err == nil {
		t.Fatal("unknown form accepted")
	}
	if _, err = parseObjectPropDesc(le(uint16(1), uint16(4), uint8(0), uint16(0), uint32(0), uint8(0), uint8(0))); err == nil {
		t.Fatal("trailing bytes accepted")
	}
}

func TestParseObjectPropList(t *testing.T) {
	b := le(uint32(2), uint32(5), uint16(0xDC07), uint16(0xFFFF), "a.jpg", uint32(5), uint16(0xDC04), uint16(8), uint64(1<<33))
	es, err := parseObjectPropList(b)
	if err != nil || len(es) != 2 || es[0].Handle != 5 || es[0].Value.Str != "a.jpg" || es[1].Value.Int != 1<<33 {
		t.Fatalf("%+v, %v", es, err)
	}
	if _, err = parseObjectPropList(b[:len(b)-3]); err == nil {
		t.Fatal("truncated list accepted")
	}
	if _, err = parseObjectPropList(le(uint32(0xFFFFFFFF))); err == nil {
		t.Fatal("bogus count accepted")
	}
	if _, err = parseObjectPropList(append(b, 0)); err == nil {
		t.Fatal("trailing bytes accepted")
	}
}
