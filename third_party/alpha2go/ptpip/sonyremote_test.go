package ptpip

import (
	"testing"
	"time"
)

func sampleContents() []SonyContent {
	t := time.Date(2026, 10, 8, 12, 34, 14, 797_000_000, time.UTC)
	cs := []SonyContent{
		{
			Type: 1, ID: 0x20001, DirNumber: 100, FileNumber: 1, Representative: true,
			CreatedUTC: t, ModifiedUTC: t, CreatedLocal: t.Add(2 * time.Hour), ModifiedLocal: t.Add(2 * time.Hour),
			Rating: -1, Protected: true, ShotMarks: []byte{1, 2, 3},
			Files: []SonyContentFile{
				{ID: 1, Path: "A:/DCIM/100MSDCF/DSC00001.JPG", Format: 0x3801, Size: 5 << 32, Width: 6000, Height: 4000},
				{ID: 2, Path: "A:/DCIM/100MSDCF/DSC00001.ARW", Format: 0xB101, Size: 24_000_000, UMID: [32]byte{1, 31: 9}},
			},
		},
		{
			Type: 4, ID: 0x20002, Dummy: true, CreatedUTC: t.Add(time.Minute),
			Files: []SonyContentFile{{ID: 1, Path: "A:/PRIVATE/M4ROOT/CLIP/C0001.MP4", Format: 0xB982, Size: 99, HasVideo: true, HasAudio: true}},
		},
	}
	return cs
}

func TestSonyContentsInfoListRoundTrip(t *testing.T) {
	want := sampleContents()
	got, err := parseSonyContentsInfoList(EncodeSonyContentsInfoList(want))
	if err != nil || len(got) != 2 {
		t.Fatalf("%+v %v", got, err)
	}
	a, b := got[0], got[1]
	if a.ID != 0x20001 || !a.Representative || !a.Protected || a.Rating != -1 || string(a.ShotMarks) != "\x01\x02\x03" ||
		!a.CreatedUTC.Equal(want[0].CreatedUTC) || !a.CreatedLocal.Equal(want[0].CreatedLocal) || len(a.Files) != 2 {
		t.Fatalf("content 0: %+v", a)
	}
	f := a.Files[0]
	if f.Path != "A:/DCIM/100MSDCF/DSC00001.JPG" || f.Size != 5<<32 || f.Width != 6000 || f.Height != 4000 || a.Files[1].UMID != want[0].Files[1].UMID {
		t.Fatalf("files: %+v", a.Files)
	}
	if !b.Dummy || len(b.Files) != 1 || !b.Files[0].HasVideo || !b.Files[0].HasAudio || b.Files[0].ID != 1 {
		t.Fatalf("content 1: %+v", b)
	}
}

func TestSonyContentsInfoListTruncated(t *testing.T) {
	full := EncodeSonyContentsInfoList(sampleContents())
	for n := 0; n < len(full); n++ {
		if _, err := parseSonyContentsInfoList(full[:n]); err == nil {
			t.Fatalf("no error for %d of %d bytes", n, len(full))
		}
	}
	// A huge count must fail cleanly, not allocate or panic.
	bad := append([]byte(nil), full...)
	bad[sonyListHeader], bad[sonyListHeader+1], bad[sonyListHeader+2], bad[sonyListHeader+3] = 0xFF, 0xFF, 0xFF, 0xFF
	if _, err := parseSonyContentsInfoList(bad); err == nil {
		t.Fatal("no error for bogus count")
	}
}
