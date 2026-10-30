package bridge

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

// Only synthetic local HTTP services are used. This is not an EOS850D firmware test.
func TestIOSSwiftEncodedCanonReadOnlyBoundary(t *testing.T) {
	executable := os.Getenv("FRAMELINK_SWIFT_CONFIG_FIXTURE")
	if executable == "" {
		t.Skip("requires the isolated Swift FrameLinkConnectionFixture executable")
	}
	type service struct {
		fixture *ccapiFixture
		server  *httptest.Server
		writes  atomic.Int64
		denied  atomic.Int64
		reads   atomic.Int64
	}
	start := func(t *testing.T, serial string, discovery bool) *service {
		fixture, original := newCCAPIFixture(t)
		original.Close()
		result := &service{fixture: fixture}
		result.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet {
				result.writes.Add(1)
				http.Error(w, "read only", http.StatusMethodNotAllowed)
				return
			}
			user, password, ok := r.BasicAuth()
			if !ok || user != "fixture-user" || password != "fixture-secret" {
				result.denied.Add(1)
				w.Header().Set("WWW-Authenticate", `Basic realm="Synthetic Camera"`)
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			result.reads.Add(1)
			if r.URL.Path == "/ccapi" && !discovery {
				w.Header().Set("Content-Type", "application/json")
				w.Write([]byte(`{"ver100":[]}`))
				return
			}
			if r.URL.Path == "/ccapi/ver100/deviceinformation" {
				w.Header().Set("Content-Type", "application/json")
				json.NewEncoder(w).Encode(map[string]any{"manufacturer": "Canon", "productname": "EOS Synthetic", "serialnumber": serial, "firmwareversion": "fixture-only"})
				return
			}
			fixture.serve(w, r)
		}))
		t.Cleanup(result.server.Close)
		return result
	}
	encode := func(t *testing.T, server *httptest.Server, protocol, mode string) ([]byte, error) {
		t.Helper()
		host, port, err := net.SplitHostPort(server.Listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		return exec.Command(executable, protocol, host, port, mode).Output()
	}
	connect := func(t *testing.T, service *service) (*Session, map[string]any) {
		t.Helper()
		raw, err := encode(t, service.server, protocolCanon, "authenticated")
		if err != nil {
			t.Fatal(err)
		}
		var wire map[string]any
		if err = json.Unmarshal(raw, &wire); err != nil || wire["protocol"] != protocolCanon || wire["fingerprint"] != "" || wire["guid"] != "" {
			t.Fatal("Swift wire configuration did not isolate CCAPI from Sony", err)
		}
		session := NewSession()
		t.Cleanup(session.Close)
		info, err := session.Connect(string(raw))
		if err != nil {
			t.Fatal(err)
		}
		var decoded map[string]any
		if err = json.Unmarshal([]byte(info), &decoded); err != nil {
			t.Fatal(err)
		}
		return session, decoded
	}
	t.Run("SwiftJSONRoutesDiscoveryIndexPreviewAndOriginalBytes", func(t *testing.T) {
		service := start(t, "SERIAL-A", true)
		session, info := connect(t, service)
		if info["dateSource"] != "FILE_MODIFIED" || info["manufacturer"] != "Canon" || info["id"] == "" || info["hardwareVerified"] != false {
			t.Fatal("incorrect identity/date provenance", info)
		}
		if info["canBrowse"] != true || info["canPreview"] != true || info["canDownload"] != true || info["canLiveView"] != true {
			t.Fatal("backend capabilities were not mapped from discovered endpoints", info)
		}
		dates, err := session.Dates(1)
		if err != nil || dates != `["2026-10-23"]` {
			t.Fatalf("date discovery %s %v", dates, err)
		}
		result, err := session.ListNewestPage(1, "", 0)
		if err != nil {
			t.Fatal(err)
		}
		var page struct {
			Items []struct {
				ID         string `json:"id"`
				ContentID  uint32 `json:"contentId"`
				CapturedAt int64  `json:"capturedAt"`
				Files      []struct {
					FileID   uint16 `json:"fileId"`
					Name     string `json:"name"`
					Size     uint64 `json:"size"`
					Modified int64  `json:"fileModifiedAt"`
				} `json:"files"`
			} `json:"items"`
			Total int   `json:"totalCount"`
			Next  int64 `json:"nextCursor"`
		}
		if err = json.Unmarshal([]byte(result), &page); err != nil || len(page.Items) != 1 || page.Total != 1 || page.Next != 0 || len(page.Items[0].Files) != 2 || page.Items[0].CapturedAt != 0 {
			t.Fatal("JPEG+CR3 grouping or date provenance lost", err, result)
		}
		folder := t.TempDir()
		infoJSON, _ := json.Marshal(info)
		if err = os.WriteFile(filepath.Join(folder, "connection-info.json"), infoJSON, 0600); err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(folder, "page.json"), []byte(result), 0600); err != nil {
			t.Fatal(err)
		}
		for _, file := range page.Items[0].Files {
			if file.Modified == 0 {
				t.Fatal("file modification metadata missing")
			}
			preview := filepath.Join(folder, file.Name+"-preview.jpg")
			for _, large := range []bool{false, true} {
				if err = session.Preview(1, int(page.Items[0].ContentID), int(file.FileID), preview, large); err != nil {
					t.Fatal(err)
				}
				image, err := os.ReadFile(preview)
				if err != nil || !bytes.Equal(image, service.fixture.jpeg) {
					t.Fatal("preview bytes changed", err)
				}
			}
			request, _ := json.Marshal(map[string]any{"slot": 1, "contentId": page.Items[0].ContentID, "fileId": file.FileID, "size": file.Size})
			target := filepath.Join(folder, file.Name)
			receipt, err := session.Download(string(request), target, nil)
			if err != nil {
				t.Fatal(err)
			}
			original, err := os.ReadFile(target)
			if err != nil || !bytes.Equal(original, service.fixture.files[testCCAPIFolder+"/"+file.Name]) {
				t.Fatal("original bytes changed", file.Name, err)
			}
			if !strings.Contains(receipt, `"sha256"`) {
				t.Fatal("missing original receipt")
			}
		}
		if service.denied.Load() == 0 || service.reads.Load() == 0 || service.writes.Load() != 0 {
			t.Fatal("authentication or read-only boundary not exercised")
		}
		verification, err := exec.Command(executable, "--verify-media", folder).CombinedOutput()
		if err != nil || !bytes.Contains(verification, []byte(`"passed":true`)) {
			t.Fatalf("Swift decode/cache/import contract failed: %s %v", verification, err)
		}
		t.Logf("Swift media contract: %s", verification)
		session.Close()
		wire, err := encode(t, service.server, protocolCanon, "authenticated")
		if err != nil {
			t.Fatal(err)
		}
		reconnected, err := session.Connect(string(wire))
		if err != nil {
			t.Fatal("reconnect failed", err)
		}
		var reconnectedInfo map[string]any
		if json.Unmarshal([]byte(reconnected), &reconnectedInfo) != nil || reconnectedInfo["id"] != info["id"] {
			t.Fatal("stable identity lost on reconnect")
		}
		if _, err = session.ListNewestPage(1, "", 0); err != nil || service.writes.Load() != 0 {
			t.Fatal("read-only reconnect index failed", err)
		}
	})
	t.Run("HTTPAuthenticationRejectedWithoutSonyFallback", func(t *testing.T) {
		service := start(t, "SERIAL-A", true)
		raw, err := encode(t, service.server, protocolCanon, "invalid")
		if err != nil {
			t.Fatal(err)
		}
		session := NewSession()
		defer session.Close()
		if _, err = session.Connect(string(raw)); err == nil {
			t.Fatal("invalid HTTP credentials accepted")
		}
		if session.Connected() || service.reads.Load() != 0 || service.denied.Load() == 0 || service.writes.Load() != 0 {
			t.Fatal("invalid authentication state")
		}
	})
	t.Run("UnknownProtocolRejectedBySwiftEncoder", func(t *testing.T) {
		service := start(t, "SERIAL-A", true)
		if _, err := encode(t, service.server, "unknown-camera", "authenticated"); err == nil {
			t.Fatal("unknown protocol encoded as Sony")
		}
		if service.reads.Load() != 0 || service.denied.Load() != 0 {
			t.Fatal("unknown protocol contacted backend")
		}
	})
	t.Run("MissingStableIdentityCannotBuildMediaIndex", func(t *testing.T) {
		service := start(t, "", true)
		raw, err := encode(t, service.server, protocolCanon, "authenticated")
		if err != nil {
			t.Fatal(err)
		}
		session := NewSession()
		defer session.Close()
		if _, err = session.Connect(string(raw)); err == nil || !strings.Contains(err.Error(), "稳定设备标识") {
			t.Fatal("model-only identity accepted", err)
		}
		if session.Connected() {
			t.Fatal("failed identity left session online")
		}
		if _, err = session.ListNewestPage(1, "", 0); err == nil {
			t.Fatal("missing identity produced an index")
		}
	})
	t.Run("SameModelDifferentSerialHasDifferentResourceIdentity", func(t *testing.T) {
		a := start(t, "SERIAL-A", true)
		b := start(t, "SERIAL-B", true)
		sa, ia := connect(t, a)
		sb, ib := connect(t, b)
		if ia["id"] == ib["id"] {
			t.Fatal("same model reused device identity")
		}
		pa, ea := sa.ListNewestPage(1, "", 0)
		pb, eb := sb.ListNewestPage(1, "", 0)
		if ea != nil || eb != nil || pa == pb {
			t.Fatal("resource identity crossed cameras", ea, eb)
		}
	})
	t.Run("MissingDiscoveryEndpointIsAnError", func(t *testing.T) {
		service := start(t, "SERIAL-A", false)
		raw, err := encode(t, service.server, protocolCanon, "authenticated")
		if err != nil {
			t.Fatal(err)
		}
		session := NewSession()
		defer session.Close()
		if _, err = session.Connect(string(raw)); err == nil {
			t.Fatal("invalid discovery accepted")
		}
		if session.Connected() || service.writes.Load() != 0 {
			t.Fatal("discovery failure changed camera state")
		}
	})
}
