package ptpip_test

import (
	"bytes"
	"context"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func osdProp(cur int64) ptpip.SonyProp {
	return ptpip.SonyProp{Code: ptpip.SonyPropOSDMode, Type: 2, Writable: true, Enabled: 1, Form: 2, Current: ptpip.SonyValue{Int: cur},
		Settable: []ptpip.SonyValue{{Int: 0}, {Int: 1}}, Readable: []ptpip.SonyValue{{Int: 0}, {Int: 1}}}
}

func adminClient(t *testing.T) (*ptpiptest.Server, *ptpip.Client) {
	t.Helper()
	srv, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true, Props: []ptpip.SonyProp{osdProp(0)}})
	if err := c.OpenSession(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	return srv, c
}

func TestSonyOSDImage(t *testing.T) {
	srv, c := adminClient(t)
	ctx := context.Background()
	if _, err := c.SonyGetOSDImage(ctx); err == nil || !strings.Contains(err.Error(), "SonySetOSDMode") {
		t.Fatalf("mode off: %v", err)
	}
	if n := srv.OSD9238.Load(); n != 0 {
		t.Fatalf("0x9238 sent %d times with the mode off", n)
	}
	if err := c.SonySetOSDMode(ctx, true); err != nil {
		t.Fatal(err)
	}
	f, err := c.SonyGetOSDImage(ctx)
	if err != nil || !bytes.HasPrefix(f.JPEG, []byte("\x89PNG")) || len(f.Meta) != 36 || srv.OSD9238.Load() != 1 {
		t.Fatalf("%q %d, %v", f.JPEG, len(f.Meta), err)
	}
	if err := c.SonySetOSDMode(ctx, false); err != nil {
		t.Fatal(err)
	}
	if _, err := c.SonyGetOSDImage(ctx); err == nil || srv.OSD9238.Load() != 1 {
		t.Fatalf("after off: %v", err)
	}
}

func TestSonyTimeZoneSet(t *testing.T) {
	_, c := adminClient(t)
	ctx := context.Background()
	if tz, err := c.SonyGetTimeZone(ctx); err != nil || tz != (ptpip.SonyTimeZone{DateTime: "20260101T000000.0", Area: "+0100"}) {
		t.Fatalf("default %+v, %v", tz, err)
	}
	want := ptpip.SonyTimeZone{DateTime: "20260702T101112.3", Area: "-0330", DST: true}
	if err := c.SonySetTimeZone(ctx, want); err != nil {
		t.Fatal(err)
	}
	if tz, err := c.SonyGetTimeZone(ctx); err != nil || tz != want {
		t.Fatalf("%+v, %v", tz, err)
	}
	for _, bad := range []ptpip.SonyTimeZone{{DateTime: "x", Area: "+0100"}, {DateTime: want.DateTime, Area: "1"}} {
		if err := c.SonySetTimeZone(ctx, bad); err == nil {
			t.Errorf("%+v accepted", bad)
		}
	}
	if tz, _ := c.SonyGetTimeZone(ctx); tz != want {
		t.Fatalf("bad value reached the camera: %+v", tz)
	}
}

func TestSonyDeleteContent(t *testing.T) {
	srv, c := adminClient(t)
	ctx := context.Background()
	if err := c.SonyDeleteContent(ctx, 2, 0x20005); err != nil {
		t.Fatal(err)
	}
	if got := srv.Deletes(); !slices.Equal(got, []ptpiptest.DeleteCall{{Slot: 2, ContentID: 0x20005}}) {
		t.Fatalf("%v", got)
	}
	if err := c.SonyDeleteContent(ctx, 1, 0); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatalf("%v", err)
	}
}

func TestSonyImportLUT(t *testing.T) {
	srv, c := adminClient(t)
	ctx := context.Background()
	if err := c.SonyImportLUT(ctx, 3, "my.cube", []byte("LUT")); err != nil {
		t.Fatal(err)
	}
	up := srv.Uploads()
	if len(up) != 2 || up[0].Op != ptpip.OpSonyUploadData || up[0].Param != 0x20001 || !bytes.HasSuffix(up[0].Data, []byte("my.cube\x00LUT")) ||
		up[1].Op != ptpip.OpSonyControlUploadData || up[1].Param != 0x20000 || !bytes.Equal(up[1].Data, []byte{100, 0, 0, 0, 3, 0}) {
		t.Fatalf("%+v", up)
	}
	long := strings.Repeat("a", 256)
	for _, bad := range []struct {
		n    uint16
		name string
		data []byte
	}{{0, "a", []byte{1}}, {17, "a", []byte{1}}, {1, "", []byte{1}}, {1, "a/b", []byte{1}}, {1, `a\b`, []byte{1}}, {1, "ä", []byte{1}},
		{1, long, []byte{1}}, {1, "a", nil}} {
		if err := c.SonyImportLUT(ctx, bad.n, bad.name, bad.data); err == nil {
			t.Errorf("%+v accepted", bad)
		}
	}
	if n := len(srv.Uploads()); n != 2 {
		t.Fatalf("invalid input reached the camera (%d uploads)", n)
	}
}

func TestSonyRestoreSettings(t *testing.T) {
	defer ptpip.SetSonyRestorePace(30 * time.Millisecond)()
	srv, c := adminClient(t)
	ctx := context.Background()
	data := append([]byte("SONY1"), make([]byte, 100)...)
	start := time.Now()
	if err := c.SonyRestoreSettings(ctx, data); err != nil {
		t.Fatal(err)
	}
	if d := time.Since(start); d < 90*time.Millisecond {
		t.Errorf("only %v: not paced", d)
	}
	if got := srv.Restores(); len(got) != 1 || !bytes.Equal(got[0], data) {
		t.Fatalf("restored %d payloads", len(got))
	}
	for _, bad := range [][]byte{nil, data[:63], append([]byte("SONY2"), make([]byte, 100)...)} {
		if err := c.SonyRestoreSettings(ctx, bad); err == nil {
			t.Errorf("%d bytes accepted", len(bad))
		}
	}
	if len(srv.Restores()) != 1 {
		t.Fatal("invalid input reached the camera")
	}
	// A cancelled context stops the pacing.
	cctx, cancel := context.WithTimeout(ctx, 10*time.Millisecond)
	defer cancel()
	if err := c.SonyRestoreSettings(cctx, data); err == nil {
		t.Fatal("cancelled restore succeeded")
	}
}

func TestSonyDownloadSettingsNeedsInfo(t *testing.T) {
	_, c := adminClient(t)
	ctx := context.Background()
	if _, err := c.GetObject(ctx, 0xFFFFC004, &bytes.Buffer{}, nil); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatalf("%v", err)
	}
}
