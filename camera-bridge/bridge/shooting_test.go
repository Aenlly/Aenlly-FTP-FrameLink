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

func shootingFixture(t *testing.T, hostOnly, failShutter bool, movableArea ...bool) (*Session, *ptpiptest.Server) {
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
	if len(movableArea) > 1 && movableArea[1] {
		props[1].Writable = true
		props[1].Form = 2
		props[1].Settable = []ptpip.SonyValue{{Int: ptpip.SonyStoreHost}, {Int: ptpip.SonyStoreCard}, {Int: ptpip.SonyStoreHostAndCard}}
		if !hostOnly {
			props[1].Current.Int = ptpip.SonyStoreHostAndCard
		}
	}
	fail := uint16(0)
	controls := []uint16{ptpip.SonyCtlShutterHalf, ptpip.SonyCtlShutterFull, ptpip.SonyCtlRemoteTouch}
	if len(movableArea) > 0 && movableArea[0] {
		controls = append(controls, ptpip.SonyCtlAFAreaPosition)
		props = append(props, ptpip.SonyProp{Code: 0xD22C, Type: 4, Writable: true, Enabled: 1,
			Current: ptpip.SonyValue{Int: 1}, Form: 2, Settable: []ptpip.SonyValue{{Int: 1}, {Int: 0x102}}})
	}
	if failShutter {
		fail = ptpip.SonyCtlShutterFull
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true, Props: props,
		ControlCodes: controls, FailControl: fail})
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
	want := []ptpiptest.ControlCall{{Code: ptpip.SonyCtlRemoteTouch, Value: 160<<16 | 359}, {Code: ptpip.SonyCtlShutterHalf, Value: 2}, {Code: ptpip.SonyCtlShutterHalf, Value: 1}}
	if !slices.Equal(server.Controls(), want) {
		t.Fatalf("focus sent unexpected control: %v", server.Controls())
	}
	for _, point := range [][2]float64{{-1, 0}, {1.1, 0}, {math.NaN(), 0}} {
		if err := s.FocusAt(point[0], point[1]); err == nil {
			t.Fatal("invalid focus coordinate accepted")
		}
	}
}

func TestSingleAutofocusDrivesLensWithoutChangingCameraArea(t *testing.T) {
	s, server := shootingFixture(t, false, false, true)
	if err := s.FocusAt(.75, .25); err != nil {
		t.Fatal(err)
	}
	want := []ptpiptest.ControlCall{{Code: ptpip.SonyCtlRemoteTouch, Value: 479<<16 | 120},
		{Code: ptpip.SonyCtlShutterHalf, Value: 2}, {Code: ptpip.SonyCtlShutterHalf, Value: 1}}
	if !slices.Equal(server.Controls(), want) {
		t.Fatalf("AF-S did not select position and drive AF: %v", server.Controls())
	}
	p, err := s.client.SonyGetProp(context.Background(), 0xD22C)
	if err != nil || p.Current.Int != 1 {
		t.Fatal("tap focus changed the camera's area mode")
	}
}

func TestRecordOnlyCaptureChangesDestinationBeforeShutter(t *testing.T) {
	s, _ := shootingFixture(t, false, false, false, true)
	if _, err := s.CapturePhoto(true, true, false); err != nil {
		t.Fatal(err)
	}
	p, err := s.client.SonyGetProp(context.Background(), ptpip.SonyPropStoreDestination)
	if err != nil || p.Current.Int != ptpip.SonyStoreCard {
		t.Fatal("record-only capture left the host queue enabled")
	}
}

func TestStorageToggleKeepsCardCopyInBothModes(t *testing.T) {
	s, _ := shootingFixture(t, false, false, false, true)
	for _, receive := range []bool{false, true, false} {
		if _, err := s.ConfigureCaptureStorage(receive); err != nil {
			t.Fatal(err)
		}
		p, err := s.client.SonyGetProp(context.Background(), ptpip.SonyPropStoreDestination)
		want := int64(ptpip.SonyStoreCard)
		if receive {
			want = ptpip.SonyStoreHostAndCard
		}
		if err != nil || p.Current.Int != want {
			t.Fatal("storage toggle did not match import choice")
		}
	}
}

func TestHostOnlyPendingFilesAreNotDiscardedByStorageChange(t *testing.T) {
	s, _ := shootingFixture(t, true, false, false, true)
	if err := s.Capture(false); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ConfigureCaptureStorage(false); err == nil {
		t.Fatal("host-only pending files were abandoned")
	}
	p, err := s.client.SonyGetProp(context.Background(), ptpip.SonyPropStoreDestination)
	if err != nil || p.Current.Int != ptpip.SonyStoreHost {
		t.Fatal("host-only destination changed before reception")
	}
}
