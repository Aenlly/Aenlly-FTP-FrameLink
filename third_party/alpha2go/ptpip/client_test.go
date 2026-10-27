package ptpip_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func card(t *testing.T) string {
	dir := t.TempDir()
	files := map[string]int{
		"slot1/DCIM/100MSDCF/DSC00001.JPG":    300_000,
		"slot1/DCIM/100MSDCF/DSC00001.ARW":    5_000_000,
		"slot2/PRIVATE/M4ROOT/CLIP/C0001.MP4": 9_000_001,
	}
	for rel, size := range files {
		p := filepath.Join(dir, rel)
		os.MkdirAll(filepath.Dir(p), 0o755)
		b := bytes.Repeat([]byte(rel[len(rel)-5:]), size/5+1)[:size]
		if err := os.WriteFile(p, b, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func connect(t *testing.T, opts ptpiptest.Options) (*ptpiptest.Server, *ptpip.Client) {
	t.Helper()
	srv, err := ptpiptest.Start(opts)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(srv.Close)
	c, err := ptpip.Dial(context.Background(), srv.Addr(), ptpip.Options{IOTimeout: 5 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return srv, c
}

func TestHandshakeDeviceInfoAndSony(t *testing.T) {
	_, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true, SerialNumber: "123"})
	ctx := context.Background()
	if c.ConnNumber == 0 || c.ResponderName != "Fake" {
		t.Fatalf("ack: conn=%d name=%q", c.ConnNumber, c.ResponderName)
	}
	di, err := c.GetDeviceInfo(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if di.Model != "ILCE-6700" || !di.IsSony() || !di.Supports(ptpip.OpGetPartialObject) || di.SerialNumber != "123" {
		t.Fatalf("device info %+v", di)
	}
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatalf("re-open should be tolerated: %v", err)
	}
	ext, err := c.SonyHandshake(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if ext.ProtocolVersion != 0x12C || len(ext.Properties) != 2 || len(ext.Controls) != 1 {
		t.Fatalf("ext info %+v", ext)
	}
}

func TestListAndDownload(t *testing.T) {
	dir := card(t)
	_, c := connect(t, ptpiptest.Options{Dir: dir})
	ctx := context.Background()
	di, _ := c.GetDeviceInfo(ctx)
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	ids, err := c.GetStorageIDs(ctx)
	if err != nil || len(ids) != 2 {
		t.Fatalf("storages %v %v", ids, err)
	}
	hs, err := c.GetObjectHandles(ctx, ids[0], 0, ptpip.ParentAll)
	if err != nil {
		t.Fatal(err)
	}
	var files []*ptpip.ObjectInfo
	var handles []uint32
	for _, h := range hs {
		oi, err := c.GetObjectInfo(ctx, h)
		if err != nil {
			t.Fatal(err)
		}
		if !oi.IsFolder() {
			files, handles = append(files, oi), append(handles, h)
		}
	}
	if len(files) != 2 {
		t.Fatalf("files in slot 1: %d", len(files))
	}
	for i, oi := range files {
		want, _ := os.ReadFile(filepath.Join(dir, "slot1/DCIM/100MSDCF", oi.Filename))
		var buf bytes.Buffer
		var last int64
		n, err := c.DownloadObject(ctx, di, handles[i], int64(oi.CompressedSize), &buf, func(v int64) { last = v })
		if err != nil || n != int64(len(want)) || !bytes.Equal(buf.Bytes(), want) || last != n {
			t.Fatalf("%s: n=%d err=%v last=%d", oi.Filename, n, err, last)
		}
	}
	// Whole-object path.
	var buf bytes.Buffer
	if _, err := c.GetObject(ctx, handles[0], &buf, nil); err != nil || buf.Len() != int(files[0].CompressedSize) {
		t.Fatalf("GetObject: %d %v", buf.Len(), err)
	}
	if _, err := c.GetObjectInfo(ctx, 0xDEAD); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatalf("want InvalidObjectHandle, got %v", err)
	}
}

func TestRefusedInit(t *testing.T) {
	srv, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), RefuseInit: true})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	_, err = ptpip.Dial(context.Background(), srv.Addr(), ptpip.Options{})
	var ife *ptpip.InitFailError
	if !errors.As(err, &ife) {
		t.Fatalf("want InitFailError, got %v", err)
	}
}

func TestDropMidTransfer(t *testing.T) {
	dir := card(t)
	_, c := connect(t, ptpiptest.Options{Dir: dir, DropAfter: 1_000_000, NoPartial: true})
	ctx := context.Background()
	c.OpenSession(ctx, 1)
	ids, _ := c.GetStorageIDs(ctx)
	hs, _ := c.GetObjectHandles(ctx, ids[1], 0, ptpip.ParentAll)
	var mp4 uint32
	for _, h := range hs {
		if oi, _ := c.GetObjectInfo(ctx, h); oi != nil && oi.Filename == "C0001.MP4" {
			mp4 = h
		}
	}
	_, err := c.GetObject(ctx, mp4, &bytes.Buffer{}, nil)
	if err == nil {
		t.Fatal("expected error on drop")
	}
	select {
	case <-c.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("client did not notice the drop")
	}
	if _, err := c.GetStorageIDs(ctx); err == nil {
		t.Fatal("broken client must fail fast")
	}
}

func TestEventsAndCancel(t *testing.T) {
	srv, c := connect(t, ptpiptest.Options{Dir: t.TempDir()})
	time.Sleep(50 * time.Millisecond) // let the event channel register
	srv.Emit(ptpip.EvObjectAdded, 0x123)
	select {
	case ev := <-c.Events():
		if ev.Code != ptpip.EvObjectAdded || len(ev.Params) != 1 || ev.Params[0] != 0x123 {
			t.Fatalf("event %+v", ev)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no event")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.GetDeviceInfo(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("want context.Canceled, got %v", err)
	}
}

func TestParseDate(t *testing.T) {
	berlin, _ := time.LoadLocation("Europe/Berlin")
	cases := []struct {
		in   string
		want time.Time
	}{
		{"20260930T081530", time.Date(2026, 9, 30, 8, 15, 30, 0, berlin)},
		{"20260930T081530.5", time.Date(2026, 9, 30, 8, 15, 30, 5e8, berlin)},
		{"20260930T081530Z", time.Date(2026, 9, 30, 8, 15, 30, 0, time.UTC)},
		{"20260930T081530+0200", time.Date(2026, 9, 30, 6, 15, 30, 0, time.UTC)},
		{"20260930T081530.0-0130", time.Date(2026, 9, 30, 9, 45, 30, 0, time.UTC)},
	}
	for _, c := range cases {
		got, err := ptpip.ParseDate(c.in, berlin)
		if err != nil || !got.Equal(c.want) {
			t.Errorf("ParseDate(%q) = %v, %v; want %v", c.in, got, err, c.want)
		}
	}
	if _, err := ptpip.ParseDate("2026", time.UTC); err == nil {
		t.Error("expected error for short date")
	}
}

func TestTrace(t *testing.T) {
	srv, err := ptpiptest.Start(ptpiptest.Options{Dir: card(t)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(srv.Close)
	var mu sync.Mutex
	var phases []string
	c, err := ptpip.Dial(context.Background(), srv.Addr(), ptpip.Options{IOTimeout: 5 * time.Second, Trace: func(e ptpip.TraceEvent) {
		mu.Lock()
		phases = append(phases, fmt.Sprintf("%s:%#x", e.Phase, e.Op))
		mu.Unlock()
	}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.GetDeviceInfo(context.Background()); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	want := []string{"queued:0x1001", "start:0x1001", "end:0x1001"}
	if !slices.Equal(phases, want) {
		t.Fatalf("got %v, want %v", phases, want)
	}
}
