package bridge

import (
	"bytes"
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

// Synthetic PTP/IP descriptors only; no physical camera descriptor or transport identity is used.
func TestIOSSwiftModernCreativeLookWireBoundary(t *testing.T) {
	executable := os.Getenv("FRAMELINK_SWIFT_CONFIG_FIXTURE")
	if executable == "" {
		t.Skip("requires isolated Swift FrameLinkConnectionFixture executable")
	}
	connect := func(t *testing.T, prop *ptpip.SonyProp) *Session {
		t.Helper()
		props := []ptpip.SonyProp{}
		if prop != nil {
			props = append(props, *prop)
		}
		server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), Sony: true, Model: "QA MODERN LOOK SIMULATED", SerialNumber: "QA-SYNTHETIC-NOT-HARDWARE", Props: props})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(server.Close)
		host, port, _ := net.SplitHostPort(server.Addr())
		config, err := exec.Command(executable, protocolSony, host, port, "anonymous").Output()
		if err != nil {
			t.Fatal(err)
		}
		session := NewSession()
		t.Cleanup(session.Close)
		if _, err = session.Connect(string(config)); err != nil {
			t.Fatal(err)
		}
		return session
	}
	snapshot := func(t *testing.T, session *Session, folder string) {
		t.Helper()
		properties, err := session.Properties()
		if err != nil {
			t.Fatal(err)
		}
		diagnostics, err := session.CreativeLookDiagnostics()
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(folder, "properties.json"), []byte(properties), 0600); err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(folder, "diagnostics.json"), []byte(diagnostics), 0600); err != nil {
			t.Fatal(err)
		}
	}
	t.Run("SwiftEncodesExactModernPTPAndConfirmsFLBWST", func(t *testing.T) {
		p := modernLookFixture()
		p.Settable = append(p.Settable, ptpip.SonyValue{Int: 9})
		for value := int64(256); value <= 263; value++ {
			p.Settable = append(p.Settable, ptpip.SonyValue{Int: value})
		}
		session := connect(t, &p)
		folder := t.TempDir()
		snapshot(t, session, folder)
		for _, target := range []int64{6, 9, 1} {
			raw, err := exec.Command(executable, "--look-request", folder, strconv.FormatInt(target, 10), protocolSony).Output()
			if err != nil {
				t.Fatal(err)
			}
			var request struct {
				Code  int   `json:"code"`
				Value int64 `json:"value"`
			}
			if json.Unmarshal(raw, &request) != nil || request.Code != int(sonyCreativeLook) || request.Value != target {
				t.Fatal("incorrect Swift PTP request", string(raw))
			}
			if err = session.SetProperty(request.Code, request.Value); err != nil {
				t.Fatal(err)
			}
			snapshot(t, session, folder)
			verified, err := exec.Command(executable, "--look-verify", folder, strconv.FormatInt(target, 10)).CombinedOutput()
			if err != nil || !bytes.Contains(verified, []byte(`"confirmed":true`)) {
				t.Fatalf("readback mismatch %s %v", verified, err)
			}
			t.Logf("Swift actual PTP readback: %s", verified)
		}
		for _, target := range []int64{4, 256, 257, 258, 259, 260, 261, 262, 263} {
			if _, err := exec.Command(executable, "--look-request", folder, strconv.FormatInt(target, 10), protocolSony).Output(); err == nil {
				t.Fatal("unadvertised or unknown candidate accepted", target)
			}
			if err := session.SetProperty(int(sonyCreativeLook), target); err == nil {
				t.Fatal("shared backend accepted protected candidate", target)
			}
		}
		if _, err := exec.Command(executable, "--look-request", folder, "6", protocolCanon).Output(); err == nil {
			t.Fatal("Canon requested Sony style write")
		}
	})
	for _, kind := range []string{"readonly", "disabled", "not-advertised", "unknown-current"} {
		t.Run(kind, func(t *testing.T) {
			p := modernLookFixture()
			var pointer *ptpip.SonyProp = &p
			switch kind {
			case "readonly":
				p.Writable = false
			case "disabled":
				p.Enabled = 0
			case "not-advertised":
				pointer = nil
			case "unknown-current":
				p.Current.Int = 256
			}
			session := connect(t, pointer)
			folder := t.TempDir()
			snapshot(t, session, folder)
			if _, err := exec.Command(executable, "--look-request", folder, "6", protocolSony).Output(); err == nil {
				t.Fatal("unsafe Swift request accepted", kind)
			}
			if err := session.SetProperty(int(sonyCreativeLook), 6); err == nil {
				t.Fatal("unsafe shared backend write accepted", kind)
			}
		})
	}
}
