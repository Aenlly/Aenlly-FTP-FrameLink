package bridge

import (
	"encoding/json"
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func TestShutterCapabilityFollowsAdvertisedControls(t *testing.T) {
	for _, advertised := range []bool{false, true} {
		controls := []uint16{0xD2C1}
		if advertised {
			controls = append(controls, 0xD2C2)
		}
		server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true, ControlCodes: controls})
		if err != nil {
			t.Fatal(err)
		}
		host, port, _ := net.SplitHostPort(server.Addr())
		session := NewSession()
		raw, err := session.Connect(fmt.Sprintf(`{"host":%q,"port":%s}`, host, port))
		session.Close()
		server.Close()
		if err != nil {
			t.Fatal(err)
		}
		var info struct{ CanCapture bool }
		if err = json.Unmarshal([]byte(raw), &info); err != nil {
			t.Fatal(err)
		}
		if info.CanCapture != advertised {
			t.Fatalf("canCapture=%v, advertised=%v", info.CanCapture, advertised)
		}
	}
}

func TestConnectionStateDetectsCameraDropAndReconnect(t *testing.T) {
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	session := NewSession()
	defer session.Close()
	if session.Connected() {
		t.Fatal("new session reports connected")
	}
	host, port, _ := net.SplitHostPort(server.Addr())
	config := fmt.Sprintf(`{"host":%q,"port":%s}`, host, port)
	if _, err = session.Connect(config); err != nil {
		t.Fatal(err)
	}
	if !session.Connected() {
		t.Fatal("connected transport was not reported")
	}
	server.DropConnections()
	deadline := time.Now().Add(2 * time.Second)
	for session.Connected() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if session.Connected() {
		t.Fatal("dropped event connection still reports connected")
	}
	if _, err = session.Connect(config); err != nil {
		t.Fatal(err)
	}
	if !session.Connected() {
		t.Fatal("reconnection did not restore transport state")
	}
	session.Close()
	if session.Connected() {
		t.Fatal("closed session reports connected")
	}
}
