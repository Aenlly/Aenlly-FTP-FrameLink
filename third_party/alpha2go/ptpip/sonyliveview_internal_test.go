package ptpip

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestReadSonyLiveViewFrameBadHeader(t *testing.T) {
	le := binary.LittleEndian
	for name, h := range map[string][4]uint32{
		"empty":         {0, 0, 0, 0},
		"overlaps head": {8, 100, 0, 0},
		"too big":       {16, 64 << 20, 0, 0},
	} {
		var b []byte
		for _, v := range h {
			b = le.AppendUint32(b, v)
		}
		b = append(b, make([]byte, 200)...)
		if _, err := readSonyLiveViewFrame(bytes.NewReader(b)); err == nil {
			t.Errorf("%s: want error", name)
		}
	}
	// Truncated body.
	b := le.AppendUint32(nil, 16)
	b = le.AppendUint32(b, 100)
	b = append(b, make([]byte, 8+10)...)
	if _, err := readSonyLiveViewFrame(bytes.NewReader(b)); err == nil {
		t.Error("truncated: want error")
	}
}
