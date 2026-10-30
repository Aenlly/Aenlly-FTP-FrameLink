package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// This is a synthetic CCAPI service, not a recording from an EOS 850D.
type ccapiFixture struct {
	mu           sync.Mutex
	files        map[string][]byte
	stamps       map[string]string
	jpeg         []byte
	drive        string
	driveWrites  []string
	aperture     string
	manual       []string
	focus        map[string]int
	live         bool
	shots        int
	shutterReady chan struct{}
	blockShutter bool
}

func newCCAPIFixture(t *testing.T) (*ccapiFixture, *httptest.Server) {
	t.Helper()
	picture := image.NewRGBA(image.Rect(0, 0, 96, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 96; x++ {
			picture.Set(x, y, color.RGBA{R: uint8(x * 2), G: uint8(y * 3), B: 80, A: 255})
		}
	}
	var jpegData bytes.Buffer
	jpeg.Encode(&jpegData, picture, nil)
	raw := append([]byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' '}, bytes.Repeat([]byte{0, 255, 128, 43}, 8192)...)
	fixture := &ccapiFixture{
		jpeg: jpegData.Bytes(), files: map[string][]byte{}, stamps: map[string]string{},
		drive: "high_speed", aperture: "2.8",
	}
	for name, data := range map[string][]byte{"IMG_0001.JPG": fixture.jpeg, "IMG_0001.CR3": raw} {
		resource := testCCAPIFolder + "/" + name
		fixture.files[resource], fixture.stamps[resource] = data, "2026-10-23T12:00:00+08:00"
	}
	server := httptest.NewServer(http.HandlerFunc(fixture.serve))
	t.Cleanup(server.Close)
	return fixture, server
}

func (f *ccapiFixture) serve(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	respond := func(value any) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(value)
	}
	const prefix = "/ccapi/ver100/"
	switch r.URL.Path {
	case "/ccapi":
		entries := []map[string]any{
			{"path": prefix + "deviceinformation", "get": true},
			{"path": testCCAPIContents, "get": true},
			{"path": prefix + "shooting/settings/av", "get": true, "put": true},
			{"path": prefix + "shooting/settings/drive", "get": true, "put": true},
			{"path": prefix + "shooting/liveview", "post": true},
			{"path": prefix + "shooting/liveview/flip", "get": true},
			{"path": prefix + "shooting/liveview/flipdetail", "get": true},
			{"path": prefix + "shooting/liveview/afframeposition", "put": true},
			{"path": prefix + "shooting/control/shutterbutton", "post": true},
			{"path": prefix + "shooting/control/shutterbutton/manual", "post": true},
		}
		respond(map[string]any{"ver100": entries})
	case prefix + "deviceinformation":
		respond(map[string]any{"manufacturer": "Canon", "productname": "Canon EOS 850D", "serialnumber": "synthetic-only-850d", "firmwareversion": "fixture-not-firmware"})
	case testCCAPIContents:
		respond(map[string]any{"path": []string{testCCAPICard}})
	case testCCAPICard:
		respond(map[string]any{"path": []string{testCCAPIFolder}})
	case testCCAPIFolder:
		if r.URL.Query().Get("kind") == "number" {
			respond(map[string]any{"pagenumber": 1})
			return
		}
		paths := make([]string, 0, len(f.files))
		for resource := range f.files {
			paths = append(paths, resource)
		}
		slices.Sort(paths)
		respond(map[string]any{"path": paths})
	case prefix + "shooting/settings/av", prefix + "shooting/settings/drive":
		drive := strings.HasSuffix(r.URL.Path, "/drive")
		if r.Method == http.MethodPut {
			var payload struct{ Value string }
			json.NewDecoder(r.Body).Decode(&payload)
			if drive {
				f.drive = payload.Value
				f.driveWrites = append(f.driveWrites, payload.Value)
			} else {
				f.aperture = payload.Value
			}
		}
		if drive {
			respond(map[string]any{"value": f.drive, "ability": []string{"single", "high_speed"}})
		} else {
			respond(map[string]any{"value": f.aperture, "ability": []string{"2.8", "4.0", "5.6"}})
		}
	case prefix + "shooting/liveview":
		var payload struct{ Liveviewsize string }
		json.NewDecoder(r.Body).Decode(&payload)
		f.live = payload.Liveviewsize != "off"
		respond(map[string]any{})
	case prefix + "shooting/liveview/flip":
		if !f.live {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(f.jpeg)
	case prefix + "shooting/liveview/flipdetail":
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write([]byte{0, 0, 0, 8, 0, 0, 0, 0})
		w.Write([]byte("{\"liveviewdata\":{\"image\":{\"positionx\":0,\"positiony\":0,\"positionwidth\":6000,\"positionheight\":4000}}}"))
	case prefix + "shooting/liveview/afframeposition":
		json.NewDecoder(r.Body).Decode(&f.focus)
		respond(map[string]any{})
	case prefix + "shooting/control/shutterbutton/manual":
		var payload struct{ Action string }
		json.NewDecoder(r.Body).Decode(&payload)
		f.manual = append(f.manual, payload.Action)
		respond(map[string]any{})
	case prefix + "shooting/control/shutterbutton":
		var payload map[string]any
		json.NewDecoder(r.Body).Decode(&payload)
		f.shots++
		if f.shutterReady != nil {
			select {
			case f.shutterReady <- struct{}{}:
			default:
			}
		}
		if f.blockShutter {
			f.mu.Unlock()
			<-r.Context().Done()
			f.mu.Lock()
			return
		}
		f.files[testCCAPIFolder+"/IMG_0002.JPG"] = f.jpeg
		f.stamps[testCCAPIFolder+"/IMG_0002.JPG"] = "2026-10-24T14:00:00+08:00"
		respond(map[string]any{})
	default:
		data, exists := f.files[r.URL.Path]
		if !exists {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		switch r.URL.Query().Get("kind") {
		case "info":
			respond(map[string]any{"filesize": len(data), "lastmodifieddate": f.stamps[r.URL.Path]})
		case "thumbnail", "display":
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(f.jpeg)
		default:
			w.Header().Set("Content-Type", "application/octet-stream")
			w.Write(data)
		}
	}
}

func connectCCAPIFixture(t *testing.T, session *Session, server *httptest.Server) map[string]any {
	t.Helper()
	host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
	number, _ := strconv.Atoi(port)
	config, _ := json.Marshal(map[string]any{"protocol": protocolCanon, "host": host, "port": number})
	result, err := session.Connect(string(config))
	if err != nil {
		t.Fatal(err)
	}
	var info map[string]any
	if err := json.Unmarshal([]byte(result), &info); err != nil {
		t.Fatal(err)
	}
	return info
}

func TestCanonSessionRoutesMediaWithoutChangingCaptureTimestamps(t *testing.T) {
	fixture, server := newCCAPIFixture(t)
	session := NewSession()
	defer session.Close()
	info := connectCCAPIFixture(t, session, server)
	if info["dateSource"] != "FILE_MODIFIED" || info["hardwareVerified"] != false || info["canAutoImport"] != false {
		t.Fatal("incorrect transport or acceptance flags", info)
	}
	dates, err := session.Dates(1)
	if err != nil || dates != "[\"2026-10-23\"]" {
		t.Fatalf("file dates %s %v", dates, err)
	}
	raw, err := session.ListNewestPage(1, "2026-10-23", 0)
	if err != nil {
		t.Fatal(err)
	}
	var page struct {
		Items []struct {
			ContentID  int
			CapturedAt int64
			Files      []struct {
				FileID         int
				Name           string
				Size           int64
				FileModifiedAt int64
				CameraDay      string
			}
		}
		NextCursor int64
	}
	if err := json.Unmarshal([]byte(raw), &page); err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || len(page.Items[0].Files) != 2 || page.Items[0].CapturedAt != 0 || page.NextCursor != 0 {
		t.Fatal("invalid paired photo page", raw)
	}
	for _, file := range page.Items[0].Files {
		if file.FileModifiedAt <= 0 || file.CameraDay != "" {
			t.Fatal("file modification time was presented as capture time")
		}
		target := filepath.Join(t.TempDir(), file.Name)
		request, _ := json.Marshal(map[string]any{"slot": 1, "contentId": page.Items[0].ContentID, "fileId": file.FileID, "size": file.Size})
		if _, err := session.Download(string(request), target, nil); err != nil {
			t.Fatal(err)
		}
		saved, _ := os.ReadFile(target)
		if !bytes.Equal(saved, fixture.files[testCCAPIFolder+"/"+file.Name]) {
			t.Fatal("session altered original bytes", file.Name)
		}
		if err := session.Preview(1, page.Items[0].ContentID, file.FileID, target+".preview.jpg", false); err != nil {
			t.Fatal(err)
		}
	}
	if !session.Connected() {
		t.Fatal("browsing disconnected the camera")
	}
}

func TestCanonSessionFocusReallyPulsesAFAndCaptureRestoresDrive(t *testing.T) {
	fixture, server := newCCAPIFixture(t)
	session := NewSession()
	defer session.Close()
	connectCCAPIFixture(t, session, server)
	if err := session.StartLiveView(); err != nil {
		t.Fatal(err)
	}
	frame, err := session.NextFrame()
	if err != nil || !bytes.Equal(frame, fixture.jpeg) {
		t.Fatal("live frame was changed", err)
	}
	if err := session.FocusAt(.25, .75); err != nil {
		t.Fatal(err)
	}
	fixture.mu.Lock()
	if fmt.Sprint(fixture.manual) != "[half_press release]" || fixture.shots != 0 || fixture.focus["positionx"] != 1500 || fixture.focus["positiony"] != 2999 {
		t.Errorf("focus actions=%v shots=%d point=%v", fixture.manual, fixture.shots, fixture.focus)
	}
	fixture.mu.Unlock()
	if err := session.SetProperty(0x5007, 400); err != nil {
		t.Fatal(err)
	}
	if _, err := session.CapturePhoto(true, true, false); err != nil {
		t.Fatal(err)
	}
	fixture.mu.Lock()
	if fixture.shots != 1 || fixture.drive != "high_speed" || fmt.Sprint(fixture.driveWrites) != "[single high_speed]" || fixture.aperture != "4.0" {
		t.Errorf("capture shots=%d drive=%s changes=%v aperture=%s", fixture.shots, fixture.drive, fixture.driveWrites, fixture.aperture)
	}
	fixture.mu.Unlock()
	if _, err := session.ConfigureCaptureStorage(true); err == nil {
		t.Fatal("unsupported host reception was enabled")
	}
}

func TestCanonAmbiguousShutterDoesNotRetryOrRestoreBurst(t *testing.T) {
	fixture, server := newCCAPIFixture(t)
	fixture.blockShutter, fixture.shutterReady = true, make(chan struct{}, 1)
	session := NewSession()
	connectCCAPIFixture(t, session, server)
	done := make(chan error, 1)
	go func() {
		_, err := session.CapturePhoto(true, true, false)
		done <- err
	}()
	select {
	case <-fixture.shutterReady:
	case <-time.After(3 * time.Second):
		session.Close()
		t.Fatal("shutter did not reach camera")
	}
	session.Close()
	if err := <-done; err == nil || !strings.Contains(err.Error(), "待确认") {
		t.Fatal("ambiguous shutter was not reported", err)
	}
	fixture.mu.Lock()
	defer fixture.mu.Unlock()
	if fixture.shots != 1 || fixture.drive != "single" || fmt.Sprint(fixture.driveWrites) != "[single]" {
		t.Fatal("shutter was retried or burst restored before confirmation")
	}
}

type ccapiBoundCounter struct{ count atomic.Int32 }

func (b *ccapiBoundCounter) BindSocket(fd int64) error { b.count.Add(1); return nil }

func TestCanonSessionUsesCameraNetworkBinderAndCanReconnectToSony(t *testing.T) {
	_, server := newCCAPIFixture(t)
	session := NewSession()
	defer session.Close()
	binder := &ccapiBoundCounter{}
	session.SetNetworkBinder(binder)
	connectCCAPIFixture(t, session, server)
	if binder.count.Load() == 0 {
		t.Fatal("CCAPI bypassed the camera network binding")
	}
	session.Close()
	if session.Connected() {
		t.Fatal("closed CCAPI session still appears connected")
	}
	// A fresh connect must not retain a Canon backend after a failed Sony dial.
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	listener.Close()
	host, port, _ := net.SplitHostPort(address)
	if _, err := session.Connect(fmt.Sprintf("{\"host\":%q,\"port\":%s}", host, port)); err == nil || session.Connected() {
		t.Fatal("failed protocol switch kept stale Canon connection")
	}
}
