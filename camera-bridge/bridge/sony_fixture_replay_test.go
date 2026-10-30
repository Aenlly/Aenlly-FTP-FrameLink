package bridge

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"

	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

// Upstream captured payload: ILCE-6700 FW2.00 (author's docs/a6700.md).
// Only 0x9209 is replayed. The PTP/IP envelope and advertised controls are synthetic.
func TestA6700CapturedPropertiesReachTheMobileSessionBoundary(t *testing.T) {
	payload, err := os.ReadFile(filepath.Join("..", "..", "third_party", "alpha2go", "ptpip", "testdata", "a6700-9209.bin"))
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(payload)
	if hex.EncodeToString(sum[:]) != "8da2772278330529bc7180a6879c4f4223446871787a134da75f0b36020c8367" {
		t.Fatal("captured fixture changed")
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true, Model: "ILCE-6700", AllPropsPayload: payload})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	host, port, _ := net.SplitHostPort(server.Addr())
	session := NewSession()
	defer session.Close()
	info, err := session.Connect(fmt.Sprintf("{\"host\":%q,\"port\":%s}", host, port))
	if err != nil {
		t.Fatal(err)
	}
	var device struct{ CanLiveView, CanCapture, HardwareVerified bool }
	if err = json.Unmarshal([]byte(info), &device); err != nil {
		t.Fatal(err)
	}
	if !device.CanLiveView || device.CanCapture || device.HardwareVerified {
		t.Fatalf("incorrect replay capability boundary: %s", info)
	}
	result, err := session.Properties()
	if err != nil {
		t.Fatal(err)
	}
	var properties []struct {
		Code     int
		Value    int64
		Writable bool
		Choices  []struct{ Value int64 }
	}
	if err = json.Unmarshal([]byte(result), &properties); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, property := range properties {
		if property.Code == 0x500A {
			found = true
			if property.Value != 0x8004 || !property.Writable || len(property.Choices) != 5 {
				t.Fatalf("captured focus property lost: %+v", property)
			}
		}
		if property.Code == 0xD278 || property.Code == 0xD1CE || property.Code == 0xD07B {
			t.Fatalf("transport or personal strings leaked into editable controls: %x", property.Code)
		}
	}
	if !found {
		t.Fatal("captured focus descriptor did not reach Session.Properties")
	}
	if server.Xfer9212.Load() != 0 {
		t.Fatal("property replay entered UI-locking transfer mode")
	}
}

func TestA6700TruncatedCapturedReplyDoesNotEstablishAConnection(t *testing.T) {
	payload, err := os.ReadFile(filepath.Join("..", "..", "third_party", "alpha2go", "ptpip", "testdata", "a6700-9209.bin"))
	if err != nil {
		t.Fatal(err)
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), A6700: true, AllPropsPayload: payload[:len(payload)-1]})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	host, port, _ := net.SplitHostPort(server.Addr())
	session := NewSession()
	defer session.Close()
	if _, err = session.Connect(fmt.Sprintf("{\"host\":%q,\"port\":%s}", host, port)); err == nil || session.Connected() {
		t.Fatal("truncated descriptors produced a usable connection")
	}
}
