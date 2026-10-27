package ptpip_test

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func controlClient(t *testing.T, fail uint16) (*ptpiptest.Server, *ptpip.Client) {
	t.Helper()
	srv, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true, FailControl: fail})
	if err := c.OpenSession(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	return srv, c
}

func wantControls(t *testing.T, srv *ptpiptest.Server, want ...ptpiptest.ControlCall) {
	t.Helper()
	if got := srv.Controls(); !slices.Equal(got, want) {
		t.Fatalf("controls %v, want %v", got, want)
	}
}

func TestSonyCapture(t *testing.T) {
	srv, c := controlClient(t, 0)
	if err := c.SonyCapture(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	wantControls(t, srv, ptpiptest.ControlCall{Code: 0xD2C2, Value: 2}, ptpiptest.ControlCall{Code: 0xD2C2, Value: 1})

	srv, c = controlClient(t, 0)
	if err := c.SonyCapture(context.Background(), true); err != nil {
		t.Fatal(err)
	}
	wantControls(t, srv, ptpiptest.ControlCall{Code: 0xD2C1, Value: 2}, ptpiptest.ControlCall{Code: 0xD2C2, Value: 2},
		ptpiptest.ControlCall{Code: 0xD2C2, Value: 1}, ptpiptest.ControlCall{Code: 0xD2C1, Value: 1})
}

func TestSonyCaptureReleasesOnFailure(t *testing.T) {
	srv, c := controlClient(t, 0xD2C2)
	if err := c.SonyCapture(context.Background(), true); err == nil {
		t.Fatal("want error")
	}
	got := srv.Controls()
	if len(got) == 0 || got[len(got)-1] != (ptpiptest.ControlCall{Code: 0xD2C1, Value: 1}) {
		t.Fatalf("shutter-half not released last: %v", got)
	}
}

func TestSonyCaptureCancelled(t *testing.T) {
	srv, c := controlClient(t, 0)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if err := c.SonyCapture(ctx, true); err == nil {
		t.Fatal("want error")
	}
	wantControls(t, srv, ptpiptest.ControlCall{Code: 0xD2C1, Value: 2}, ptpiptest.ControlCall{Code: 0xD2C1, Value: 1})
}

func TestSonyToggleMovie(t *testing.T) {
	srv, c := controlClient(t, 0)
	if err := c.SonyToggleMovie(context.Background()); err != nil {
		t.Fatal(err)
	}
	wantControls(t, srv, ptpiptest.ControlCall{Code: 0xD2C8, Value: 2}, ptpiptest.ControlCall{Code: 0xD2C8, Value: 1})
}

func TestSonyControlUnknown(t *testing.T) {
	_, c := controlClient(t, 0)
	if err := c.SonyControl(context.Background(), 0x1234, 4, ptpip.SonyValue{Int: 2}); !ptpip.IsCode(err, 0x200A) {
		t.Fatalf("unknown control: %v", err)
	}
}

func TestSonyControlTable(t *testing.T) {
	for c := 0; c <= 0xFFFF; c++ {
		if typ, _, ok := ptpip.SonyControlInfo(uint16(c)); ok && typ != 0xFFFF && (typ < 1 || typ > 6) {
			t.Errorf("0x%04X: bad type %d", c, typ)
		}
	}
}

func TestSonyPressButtonAndDial(t *testing.T) {
	srv, c := controlClient(t, 0)
	ctx := context.Background()
	if err := c.SonyPressButton(ctx, 5); err != nil {
		t.Fatal(err)
	}
	if err := c.SonyTurnDial(ctx, 0x4002, -1); err != nil {
		t.Fatal(err)
	}
	if err := c.SonyTurnDial(ctx, 0x4002, 1); err != nil {
		t.Fatal(err)
	}
	wantControls(t, srv,
		ptpiptest.ControlCall{Code: 0xD309, Value: 5<<16 | 2}, ptpiptest.ControlCall{Code: 0xD309, Value: 5<<16 | 1},
		ptpiptest.ControlCall{Code: 0xD30B, Value: 0x4002FFFF}, ptpiptest.ControlCall{Code: 0xD30B, Value: 0x40020001})
}

func TestSonyPressButtonReleasesOnFailure(t *testing.T) {
	srv, c := controlClient(t, 0xD309)
	if err := c.SonyPressButton(context.Background(), 5); err == nil {
		t.Fatal("want error")
	}
	got := srv.Controls()
	if len(got) == 0 || got[len(got)-1] != (ptpiptest.ControlCall{Code: 0xD309, Value: 5<<16 | 1}) {
		t.Fatalf("button not released last: %v", got)
	}
}
