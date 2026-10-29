package bridge

import (
	"context"
	"fmt"
	"math"
	"net"
	"slices"
	"testing"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func shootingFixture(t *testing.T, hostOnly, failShutter bool) (*Session, *ptpiptest.Server) {
	t.Helper()
	store := int64(ptpip.SonyStoreCard)
	if hostOnly {
		store = ptpip.SonyStoreHost
	}
	props := []ptpip.SonyProp{
		{Code: driveModeProperty, Type: 4, Writable: true, Enabled: 1, Current: ptpip.SonyValue{Int: 2}, Form: 2, Settable: []ptpip.SonyValue{{Int: 1}, {Int: 2}}},
		{Code: ptpip.SonyPropStoreDestination, Type: 4, Enabled: 1, Current: ptpip.SonyValue{Int: store}},
		{Code: 0x500A, Type: 4, Enabled: 1, Current: ptpip.SonyValue{Int: 2}},
		{Code: 0xD284, Type: 2, Enabled: 1, Current: ptpip.SonyValue{Int: 1}},
	}
	fail := uint16(0)
	if failShutter {
		fail = ptpip.SonyCtlShutterFull
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true, Props: props,
		ControlCodes: []uint16{ptpip.SonyCtlShutterHalf, ptpip.SonyCtlShutterFull, ptpip.SonyCtlRemoteTouch}, FailControl: fail})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	host, port, _ := net.SplitHostPort(server.Addr())
	s := NewSession()
	t.Cleanup(s.Close)
	if _, err = s.Connect(fmt.Sprintf(`{"host":%q,"port":%s}`, host, port)); err != nil {
		t.Fatal(err)
	}
	return s, server
}

func TestSingleCaptureReleasesOnceAndRestoresBurstMode(t *testing.T) {
	s, server := shootingFixture(t, false, false)
	if _, err := s.CapturePhoto(true, true, false); err != nil {
		t.Fatal(err)
	}
	want := []ptpiptest.ControlCall{{Code: ptpip.SonyCtlShutterHalf, Value: 2}, {Code: ptpip.SonyCtlShutterFull, Value: 2}, {Code: ptpip.SonyCtlShutterFull, Value: 1}, {Code: ptpip.SonyCtlShutterHalf, Value: 1}}
	if !slices.Equal(server.Controls(), want) {
		t.Fatalf("unexpected shutter calls: %v", server.Controls())
	}
	p, err := s.client.SonyGetProp(context.Background(), driveModeProperty)
	if err != nil || p.Current.Int != 2 {
		t.Fatalf("burst mode was not restored: %+v %v", p, err)
	}
	if !s.Connected() {
		t.Fatal("capture closed the connection")
	}
}

func TestHostOnlyStorageBlocksCaptureWithoutReception(t *testing.T) {
	s, server := shootingFixture(t, true, false)
	if _, err := s.CapturePhoto(true, true, false); err == nil {
		t.Fatal("host-only image could be abandoned")
	}
	if len(server.Controls()) != 0 {
		t.Fatal("shutter was sent despite storage preflight failure")
	}
	if _, err := s.CapturePhoto(true, true, true); err == nil {
		t.Fatal("host-only storage accepted without verified original-size reception")
	}
	if len(server.Controls()) != 0 {
		t.Fatal("shutter was sent without original-size protection")
	}
}

func TestUncertainShutterKeepsSingleModeAndNeverRetries(t *testing.T) {
	s, server := shootingFixture(t, false, true)
	if _, err := s.CapturePhoto(true, true, false); err == nil {
		t.Fatal("expected uncertain shutter result")
	}
	presses := 0
	for _, c := range server.Controls() {
		if c.Code == ptpip.SonyCtlShutterFull && c.Value == 2 {
			presses++
		}
	}
	if presses != 1 {
		t.Fatalf("shutter sent %d times", presses)
	}
	p, err := s.client.SonyGetProp(context.Background(), driveModeProperty)
	if err != nil || p.Current.Int != driveSingle {
		t.Fatal("uncertain release restored burst mode")
	}
}

func TestTouchFocusUsesAdvertisedControlWithoutTakingPicture(t *testing.T) {
	s, server := shootingFixture(t, false, false)
	if err := s.FocusAt(.25, .75); err != nil {
		t.Fatal(err)
	}
	want := []ptpiptest.ControlCall{{Code: ptpip.SonyCtlRemoteTouch, Value: 160<<16 | 359}}
	if !slices.Equal(server.Controls(), want) {
		t.Fatalf("focus sent unexpected control: %v", server.Controls())
	}
	for _, point := range [][2]float64{{-1, 0}, {1.1, 0}, {math.NaN(), 0}} {
		if err := s.FocusAt(point[0], point[1]); err == nil {
			t.Fatal("invalid focus coordinate accepted")
		}
	}
}
