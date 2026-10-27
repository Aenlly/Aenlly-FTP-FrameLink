package propdb

import (
	"slices"
	"testing"
)

func TestTable(t *testing.T) {
	seen := map[uint16]bool{}
	for _, i := range All() {
		if seen[i.Code] {
			t.Errorf("duplicate 0x%04X", i.Code)
		}
		seen[i.Code] = true
		if i.Name == "" || i.Desc == "" || !slices.Contains(Groups, i.Group) {
			t.Errorf("0x%04X incomplete: %+v", i.Code, i)
		}
		if i.Danger != None && i.Note == "" {
			t.Errorf("0x%04X dangerous without a note", i.Code)
		}
	}
	codes := []uint16{0xD207, 0xD208, 0xD20A, 0xD20C, 0xD215, 0xD222, 0xD22C, 0xD268, 0xD278, 0xD284, 0xD285,
		0xD2C1, 0xD2C2, 0xD2C8, 0xD2DC, 0xD2E4, 0xD2E5, 0xD309, 0xD30B}
	for c := uint16(0x5001); c <= 0x501F; c++ {
		codes = append(codes, c)
	}
	for _, c := range codes {
		if _, ok := Lookup(c); !ok {
			t.Errorf("0x%04X missing", c)
		}
	}
	if i, _ := Lookup(0xD222); i.Danger != Dangerous {
		t.Error("0xD222 must be dangerous")
	}
	if _, ok := Lookup(0x1234); ok {
		t.Error("unknown code found")
	}
}
