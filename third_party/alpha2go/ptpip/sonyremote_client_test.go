package ptpip_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

// connectRemote connects to an a6700 fake with an open session.
func connectRemote(t *testing.T, opts ptpiptest.Options) (*ptpiptest.Server, *ptpip.Client) {
	t.Helper()
	opts.A6700 = true
	srv, c := connect(t, opts)
	if err := c.OpenSession(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
	return srv, c
}

func TestSonyRemoteTransferAdvertised(t *testing.T) {
	_, c := connect(t, ptpiptest.Options{Dir: card(t), A6700: true})
	di, err := c.GetDeviceInfo(context.Background())
	if err != nil || !di.SupportsSonyRemoteTransfer() {
		t.Fatalf("%+v %v", di, err)
	}
	_, c = connect(t, ptpiptest.Options{Dir: card(t), A6700: true, NoRemote: true})
	if di, _ = c.GetDeviceInfo(context.Background()); di.SupportsSonyRemoteTransfer() {
		t.Fatal("NoRemote camera claims RemoteTransfer")
	}
}

// manyFiles creates n single-file contents with distinct mtimes.
func manyFiles(t *testing.T, n int) string {
	dir := t.TempDir()
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	for i := 0; i < n; i++ {
		p := filepath.Join(dir, "slot1", "DCIM", fmt.Sprintf("F%04d.JPG", i))
		os.MkdirAll(filepath.Dir(p), 0o755)
		os.WriteFile(p, []byte("x"), 0o644)
		ts := base.Add(time.Duration(i) * time.Second)
		os.Chtimes(p, ts, ts)
	}
	return dir
}

func TestSonyListAllContentsPaging(t *testing.T) {
	_, c := connectRemote(t, ptpiptest.Options{Dir: manyFiles(t, 250), A6700: true})
	cs, err := c.SonyListAllContents(context.Background(), 1)
	if err != nil || len(cs) != 250 {
		t.Fatalf("got %d contents, err %v", len(cs), err)
	}
	dates, err := c.SonyGetCapturedDateList(context.Background(), 1)
	if err != nil || len(dates) != 250 {
		t.Fatalf("got %d dates, err %v", len(dates), err)
	}
	if _, err := c.SonyListAllContents(context.Background(), 2); !ptpip.IsCode(err, 0x201D) || !errors.Is(err, ptpip.ErrSlotEmpty) {
		t.Fatalf("slot 2: %v", err)
	}
}

func TestSonyListAllContentsEmptySlotSendsNoContentsRequest(t *testing.T) {
	dir := t.TempDir()
	os.MkdirAll(filepath.Join(dir, "slot1"), 0o755)
	srv, c := connectRemote(t, ptpiptest.Options{Dir: dir, A6700: true})
	before := srv.Ops.Load()
	cs, err := c.SonyListAllContents(context.Background(), 1)
	if err != nil || len(cs) != 0 || srv.Ops.Load()-before != 1 {
		t.Fatalf("got %d contents, %d ops, err %v", len(cs), srv.Ops.Load()-before, err)
	}
}

func TestSonyCardUnavailable(t *testing.T) {
	_, c := connectRemote(t, ptpiptest.Options{Dir: manyFiles(t, 3), A6700: true, CardBusy: true})
	ctx := context.Background()
	_, err := c.SonyListAllContents(ctx, 1)
	if !errors.Is(err, ptpip.ErrCardUnavailable) || !ptpip.IsCode(err, ptpip.RespOperationNotSupported) || errors.Is(err, ptpip.ErrSlotEmpty) {
		t.Fatalf("list: %v", err)
	}
	if _, err := c.SonyGetContentsCompressed(ctx, 1, 1, 1, ptpip.SonyThumbnail, io.Discard); !errors.Is(err, ptpip.ErrCardUnavailable) {
		t.Fatalf("thumbnail: %v", err)
	}
	// the session survives the refusal
	if _, err := c.GetDeviceInfo(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestSonyListAllContentsStopsWhenCursorDoesNotAdvance(t *testing.T) {
	_, c := connectRemote(t, ptpiptest.Options{Dir: manyFiles(t, 150), A6700: true, IgnoreAfter: true})
	// A stuck cursor must not loop forever, and must not pass for a complete list.
	cs, err := c.SonyListAllContents(context.Background(), 1)
	if err == nil || !strings.Contains(err.Error(), "incomplete") || len(cs) != 100 {
		t.Fatalf("got %d contents, err %v", len(cs), err)
	}
}

func TestSonyDownloadContentFileChunks(t *testing.T) {
	srv, c := connectRemote(t, ptpiptest.Options{Dir: card(t), A6700: true})
	ctx := context.Background()
	cs, err := c.SonyListAllContents(ctx, 1)
	if err != nil || len(cs) != 1 || len(cs[0].Files) != 2 {
		t.Fatalf("%+v %v", cs, err)
	}
	var arw ptpip.SonyContentFile
	for _, f := range cs[0].Files {
		if filepath.Ext(f.Path) == ".ARW" {
			arw = f
		}
	}
	var buf bytes.Buffer
	var last int64
	n, err := c.SonyDownloadContentFile(ctx, 1, cs[0].ID, arw.ID, arw.Size, &buf, func(d int64) { last = d })
	if err != nil || n != 5_000_000 || last != n || buf.Len() != 5_000_000 {
		t.Fatalf("n=%d last=%d err=%v", n, last, err)
	}
	if !bytes.HasPrefix(buf.Bytes(), []byte("1.ARW1.ARW")) {
		t.Fatalf("content %q", buf.Bytes()[:10])
	}
	reqs := srv.ContentRequests()
	if len(reqs) != 2 || reqs[0].Last || !reqs[1].Last || reqs[1].Offset != 4<<20 || reqs[0].Slot != 1 || reqs[1].FileID != uint32(arw.ID) {
		t.Fatalf("requests %+v", reqs)
	}
}

func TestSonyGetContentsDataOffsetAbove4GiB(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "slot1", "BIG.MP4")
	os.MkdirAll(filepath.Dir(p), 0o755)
	f, err := os.Create(p)
	if err != nil {
		t.Fatal(err)
	}
	const off = 1<<32 + 5
	if err := f.Truncate(off + 10); err != nil {
		t.Skip("no sparse files:", err)
	}
	f.WriteAt([]byte("0123456789"), off)
	f.Close()
	_, c := connectRemote(t, ptpiptest.Options{Dir: dir, A6700: true})
	ctx := context.Background()
	cs, err := c.SonyListAllContents(ctx, 1)
	if err != nil || len(cs) != 1 || cs[0].Files[0].Size != off+10 {
		t.Fatalf("%+v %v", cs, err)
	}
	var buf bytes.Buffer
	n, err := c.SonyGetContentsData(ctx, 1, cs[0].ID, 1, off, 10, true, &buf)
	if err != nil || n != 10 || buf.String() != "0123456789" {
		t.Fatalf("n=%d %q %v", n, buf.String(), err)
	}
}

func TestSonyGetContentsCompressed(t *testing.T) {
	_, c := connectRemote(t, ptpiptest.Options{Dir: card(t), A6700: true})
	ctx := context.Background()
	cs, err := c.SonyListAllContents(ctx, 1)
	if err != nil || len(cs) != 1 {
		t.Fatalf("%+v %v", cs, err)
	}
	f := cs[0].Files[0]
	for _, typ := range []uint32{ptpip.SonyThumbnail, ptpip.SonyScreennail} {
		var buf bytes.Buffer
		n, err := c.SonyGetContentsCompressed(ctx, 1, cs[0].ID, f.ID, typ, &buf)
		if err != nil || n != int64(buf.Len()) || !bytes.Equal(buf.Bytes(), ptpiptest.CompressedData(typ)) {
			t.Fatalf("type %d: n=%d err=%v %x", typ, n, err, buf.Bytes())
		}
	}
	if _, err := c.SonyGetContentsCompressed(ctx, 1, cs[0].ID+1, f.ID, ptpip.SonyThumbnail, &bytes.Buffer{}); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatalf("unknown content: %v", err)
	}
}
