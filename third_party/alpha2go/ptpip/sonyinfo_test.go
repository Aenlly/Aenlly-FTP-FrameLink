package ptpip

import (
	"encoding/hex"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(strings.ReplaceAll(s, " ", ""))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestParseSonyProp(t *testing.T) {
	b := unhex(t, "0a50040001010000048002050002000580048006800100050002000580048006800100")
	r := &reader{b: b}
	p := r.sonyProp()
	want := []SonyValue{{Int: 2}, {Int: 0x8005}, {Int: 0x8004}, {Int: 0x8006}, {Int: 1}}
	if r.err != nil || r.off != len(b) || p.Code != 0x500A || p.Type != 4 || !p.Writable || p.Enabled != 1 ||
		p.Current.Int != 0x8004 || p.Form != 2 || !reflect.DeepEqual(p.Settable, want) || !reflect.DeepEqual(p.Readable, want) {
		t.Fatalf("%+v, %v", p, r.err)
	}
	if enc, ok := EncodeSonyProp(p); !ok || !reflect.DeepEqual(enc, b) {
		t.Fatalf("re-encode %x", enc)
	}
	r = &reader{b: b[:len(b)-1]}
	if r.sonyProp(); r.err == nil {
		t.Fatal("truncated prop accepted")
	}
}

func TestParseSonyInfo(t *testing.T) {
	tzB := unhex(t, "64000000323032363130303954313831333132"+"2e34002b3031303000"+"01")
	tz, err := parseSonyTimeZone(tzB)
	if err != nil || tz != (SonyTimeZone{"20261009T181312.4", "+0100", true}) {
		t.Fatalf("%+v, %v", tz, err)
	}
	if got, err := tz.Time(); err != nil || !got.Equal(time.Date(2026, 10, 9, 16, 13, 12, 400_000_000, time.UTC)) {
		t.Fatalf("time %v, %v", got, err)
	}

	opsB := unhex(t, "0e000000cad10592ced10592cfd10592dcd10592d1d20792ddd20792e4d20792ebd20792f1d20792000020920dd30792"+"14d307920dd205929fd10592")
	ops, err := parseSonyOperationResults(opsB)
	if err != nil || len(ops) != 14 || ops[0] != (SonyOperationResult{0xD1CA, 0x9205}) || ops[9] != (SonyOperationResult{0, 0x9220}) || ops[13] != (SonyOperationResult{0xD19F, 0x9205}) {
		t.Fatalf("%+v, %v", ops, err)
	}

	ftpB := unhex(t, "0800000064000000"+"09")
	for _, n := range "123456789" {
		ftpB = append(ftpB, PutString(nil, "FTP"+string(n))...)
	}
	ftp, err := parseSonyFTPServerNames(ftpB)
	if err != nil || len(ftp) != 9 || ftp[0] != "FTP1" || ftp[8] != "FTP9" {
		t.Fatalf("%v, %v", ftp, err)
	}

	for name, c := range map[string]struct {
		b     []byte
		parse func([]byte) (any, error)
	}{
		"timezone": {tzB, func(b []byte) (any, error) { return parseSonyTimeZone(b) }},
		"opresult": {opsB, func(b []byte) (any, error) { return parseSonyOperationResults(b) }},
		"ftp":      {ftpB, func(b []byte) (any, error) { return parseSonyFTPServerNames(b) }},
	} {
		if _, err := c.parse(c.b[:len(c.b)-1]); err == nil {
			t.Errorf("%s: truncated reply accepted", name)
		}
	}
}

func TestParseSonyDisplayStrings(t *testing.T) {
	b, err := os.ReadFile("testdata/a6700-9215.bin")
	if err != nil {
		t.Fatal(err)
	}
	ls, err := parseSonyDisplayStrings(b)
	if err != nil || len(ls) != 6 {
		t.Fatalf("%d lists, %v", len(ls), err)
	}
	for i, w := range []struct {
		typ   uint32
		dt    uint16
		n     int
		first SonyStringItem
	}{
		{3, 4, 19, SonyStringItem{1, "S-Log3"}}, {8, 2, 2, SonyStringItem{1, "ISO 12800"}}, {9, 0, 0, SonyStringItem{}},
		{0x13, 0, 16, SonyStringItem{1, "ST"}}, {0x18, 0, 13, SonyStringItem{1, "Up button"}}, {0x1A, 0, 3, SonyStringItem{16385, "Control wheel"}},
	} {
		l := ls[i]
		if l.Type != w.typ || len(l.Items) != w.n || (w.dt != 0 && l.DataType != w.dt) || (w.n > 0 && l.Items[0] != w.first) {
			t.Errorf("list %d: %+v", i, l)
		}
	}
	if got := ls[0].Items[3]; got != (SonyStringItem{257, "User1:(No Import)"}) {
		t.Errorf("item %+v", got)
	}
	if got := ls[1].Items[1]; got != (SonyStringItem{2, "ISO 800"}) {
		t.Errorf("item %+v", got)
	}
	for _, bad := range [][]byte{b[:len(b)-1], append(append([]byte(nil), b...), 0)} {
		if _, err := parseSonyDisplayStrings(bad); err == nil {
			t.Error("bad length accepted")
		}
	}
}
