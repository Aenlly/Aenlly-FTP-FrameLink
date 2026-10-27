package main

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func TestProbeAgainstFakeCamera(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "slot1", "DCIM", "100MSDCF", "DSC00001.JPG")
	os.MkdirAll(filepath.Dir(p), 0o755)
	os.WriteFile(p, []byte{0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9}, 0o644)
	srv, err := ptpiptest.Start(ptpiptest.Options{Dir: dir, Sony: true})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	out := t.TempDir()
	// Handle 0x103 is the first file (dirs DCIM, 100MSDCF come first).
	if err := run(srv.Addr(), ptpip.Options{IOTimeout: 5 * time.Second}, "auto", nil, "", true, "", false, "", "", "", "", "", "", "", "0x500A=0x8002", "af", false, "0xD2C8=2,0xD2C8=1", 0, -1, 0x103, "0x3801", 0x103, "auto", out, 0); err != nil {
		t.Fatal(err)
	}
	want := []ptpiptest.ControlCall{{Code: 0xD2C8, Value: 2}, {Code: 0xD2C8, Value: 1},
		{Code: 0xD2C1, Value: 2}, {Code: 0xD2C2, Value: 2}, {Code: 0xD2C2, Value: 1}, {Code: 0xD2C1, Value: 1}}
	if got := srv.Controls(); !slices.Equal(got, want) {
		t.Fatalf("controls %v, want %v", got, want)
	}
	if b, err := os.ReadFile(filepath.Join(out, "DSC00001.JPG")); err != nil || len(b) != 7 {
		t.Fatalf("downloaded %d bytes, err %v", len(b), err)
	}
}
