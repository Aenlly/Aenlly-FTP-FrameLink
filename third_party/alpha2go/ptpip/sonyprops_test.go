package ptpip

import (
	"bytes"
	"os"
	"testing"
)

func fixture(t *testing.T) ([]byte, map[uint16]SonyProp) {
	t.Helper()
	b, err := os.ReadFile("testdata/a6700-9209.bin")
	if err != nil {
		t.Fatal(err)
	}
	ps, err := parseSonyProps(b)
	if err != nil {
		t.Fatal(err)
	}
	if len(ps) != 352 {
		t.Fatalf("%d props", len(ps))
	}
	if got := EncodeSonyProps(ps); !bytes.Equal(got, b) {
		t.Fatal("re-encoded fixture differs")
	}
	m := map[uint16]SonyProp{}
	for _, p := range ps {
		m[p.Code] = p
	}
	return b, m
}

func TestSonyPropsFixture(t *testing.T) {
	b, m := fixture(t)
	if p := m[0xD20F]; p.Type != 4 || p.Form != 1 || p.Min.Int != 2500 || p.Max.Int != 9900 || p.Step.Int != 100 {
		t.Errorf("0xD20F %+v", p)
	}
	if p := m[0x500A]; p.Type != 4 || !p.Writable || p.Enabled != 1 || p.Current.Int != 0x8004 || len(p.Settable) != 5 {
		t.Errorf("0x500A %+v", p)
	}
	if s := m[0xD278].Current.Str; len(s) < 37 || s[:37] != "http://localhost:60152/liveviewstream" {
		t.Errorf("0xD278 %q", s)
	}
	if s := m[0xD07B].Current.Str; s != "E PZ 16-50mm F3.5-5.6 OSS" {
		t.Errorf("0xD07B %q", s)
	}
	if s := m[0xD1CE].Current.Str; s != "Test Owner" {
		t.Errorf("0xD1CE %q", s)
	}
	for _, n := range []int{len(b) - 1, 100, 0, 7} {
		if _, err := parseSonyProps(b[:n]); err == nil {
			t.Errorf("truncated to %d: no error", n)
		}
	}
}

func TestSonyPropsBadType(t *testing.T) {
	for _, typ := range []byte{9, 10, 0x77} {
		b := EncodeSonyProps([]SonyProp{{Code: 1, Type: 2}})
		b[10] = typ // datatype of the first prop
		if _, err := parseSonyProps(b); err == nil {
			t.Errorf("type %d: no error", typ)
		}
	}
	if _, err := encodeSonyValue(9, SonyValue{}); err == nil {
		t.Error("encode int128: no error")
	}
}

func TestSonyValueRoundTrip(t *testing.T) {
	for _, c := range []struct {
		typ uint16
		v   SonyValue
	}{
		{1, SonyValue{Int: -5}},
		{3, SonyValue{Int: -300}},
		{5, SonyValue{Int: -70000}},
		{8, SonyValue{Int: -1}}, // 0xFFFFFFFFFFFFFFFF
		{6, SonyValue{Int: 0xFFFFFFFF}},
		{0x4004, SonyValue{Arr: []int64{1, 0xFFFF}}},
		{0xFFFF, SonyValue{Str: "héllo"}},
	} {
		b, err := encodeSonyValue(c.typ, c.v)
		if err != nil {
			t.Fatal(err)
		}
		got, err := DecodeSonyValue(c.typ, b)
		if err != nil || got.Int != c.v.Int || got.Str != c.v.Str || len(got.Arr) != len(c.v.Arr) {
			t.Errorf("type 0x%X: %+v -> %+v (%v)", c.typ, c.v, got, err)
		}
	}
}

func TestSonyPropsUpdate(t *testing.T) {
	_, m := fixture(t)
	var all []SonyProp
	for _, p := range m {
		all = append(all, p)
	}
	s := SonyProps{}
	if got := s.Update(all); len(got) != 352 {
		t.Fatalf("first update changed %d", len(got))
	}
	if got := s.Update(all); len(got) != 0 {
		t.Fatalf("no-op update changed %v", got)
	}
	p := m[0xD20F]
	p.Current.Int++
	if got := s.Update([]SonyProp{p}); len(got) != 1 || got[0] != 0xD20F || s[0xD20F].Current.Int != p.Current.Int {
		t.Fatalf("diff update: %v", got)
	}
}
