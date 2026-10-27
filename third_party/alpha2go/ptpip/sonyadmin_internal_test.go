package ptpip

import (
	"bytes"
	"testing"
	"time"
)

func TestEncodeSonyTimeZone(t *testing.T) {
	b, err := encodeSonyTimeZone(SonyTimeZone{DateTime: "20260102T030405.6", Area: "+0100", DST: true})
	want := "64000000 01 3230323630313032543033303430352e3600 01 2b30313030 00 01 01"
	if err != nil || !bytes.Equal(b, unhex(t, want)) || len(b) != 32 {
		t.Fatalf("%x, %v", b, err)
	}
	for _, tz := range []SonyTimeZone{
		{"20260102T030405", "+0100", false}, {"20260102T030405.66", "+0100", false}, {"2026-01-02T03:04:05.6", "+0100", false},
		{"20261302T030405.6", "+0100", false}, {"20260102T030405.6", "0100", false}, {"20260102T030405.6", "+01:00", false},
		{"20260102T030405.6", "+1", false}, {"", "", false},
	} {
		if _, err := encodeSonyTimeZone(tz); err == nil {
			t.Errorf("%+v accepted", tz)
		}
	}
}

func TestSonyTimeZoneAt(t *testing.T) {
	loc, err := time.LoadLocation("Europe/Berlin")
	if err != nil {
		t.Skip("no tzdata:", err)
	}
	for _, c := range []struct {
		t    time.Time
		want SonyTimeZone
	}{
		{time.Date(2026, 7, 1, 12, 30, 15, 234e6, loc), SonyTimeZone{"20260701T123015.2", "+0100", true}},
		{time.Date(2026, 1, 1, 12, 30, 15, 987e6, loc), SonyTimeZone{"20260101T123015.9", "+0100", false}},
	} {
		tz := SonyTimeZoneAt(c.t)
		if tz != c.want {
			t.Errorf("%+v, want %+v", tz, c.want)
		}
		if got, err := tz.Time(); err != nil || !got.Equal(c.t.Truncate(100*time.Millisecond)) {
			t.Errorf("round trip %v, %v, want %v", got, err, c.t.Truncate(100*time.Millisecond))
		}
	}
	if tz := SonyTimeZoneAt(time.Date(2026, 1, 1, 0, 0, 0, 0, time.FixedZone("", -(3*3600+30*60)))); tz.Area != "-0330" || tz.DST {
		t.Errorf("%+v", tz)
	}
}

func TestEncodeSonyLUT(t *testing.T) {
	got := encodeSonyLUTUpload("a.cube", []byte{1, 2, 3})
	want := unhex(t, "64000000 18000000 07000000 1f000000 03000000 00000000 612e6375626500 010203")
	if !bytes.Equal(got, want) {
		t.Fatalf("%x", got)
	}
	if got := encodeSonyLUTSelect(16); !bytes.Equal(got, unhex(t, "64000000 1000")) {
		t.Fatalf("%x", got)
	}
}
