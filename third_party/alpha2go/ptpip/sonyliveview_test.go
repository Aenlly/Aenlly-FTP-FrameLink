package ptpip_test

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

// lvFrame builds a frame like the a6700's: header, meta, then the JPEG padded with 0xFF.
func lvFrame(jpeg, meta []byte) []byte {
	le := binary.LittleEndian
	metaOff := uint32(16)
	imgOff := metaOff + uint32(len(meta))
	b := le.AppendUint32(nil, imgOff)
	b = le.AppendUint32(b, uint32(len(jpeg)))
	b = le.AppendUint32(b, metaOff)
	b = le.AppendUint32(b, uint32(len(meta)))
	return append(append(b, meta...), jpeg...)
}

func TestSonyLiveView(t *testing.T) {
	jpegs := [][]byte{{0xFF, 0xD8, 1, 0xFF, 0xD9, 0xFF, 0xFF}, {0xFF, 0xD8, 2, 3, 0xFF, 0xD9}}
	meta := bytes.Repeat([]byte{7}, 136)
	var gotPath string
	hs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		for _, j := range jpegs {
			w.Write(lvFrame(j, meta))
			w.(http.Flusher).Flush() // chunked, like the camera
		}
	}))
	defer hs.Close()
	_, port, _ := net.SplitHostPort(hs.Listener.Addr().String())

	// The camera names "localhost"; the client must dial the camera host
	// instead, so an unresolvable host here must not matter.
	url := "http://camera.invalid:" + port + "/liveviewstream?id=1"
	_, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true, Props: []ptpip.SonyProp{
		{Code: ptpip.SonyPropLiveViewURL, Type: 0xFFFF, Enabled: 1, Current: ptpip.SonyValue{Str: url, NoNUL: true}},
	}})
	ctx := context.Background()
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	lv, err := c.SonyOpenLiveView(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer lv.Close()
	for i, j := range jpegs {
		f, err := lv.Next()
		if err != nil || !bytes.Equal(f.JPEG, j) || !bytes.Equal(f.Meta, meta) {
			t.Fatalf("frame %d: %x / %d meta bytes, %v", i, f.JPEG, len(f.Meta), err)
		}
	}
	if gotPath != "/liveviewstream?id=1" {
		t.Fatalf("requested %q", gotPath)
	}
	if _, err := lv.Next(); err == nil {
		t.Fatal("want EOF after the last frame")
	}
}

func TestSonyLiveViewNoURL(t *testing.T) {
	_, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true})
	ctx := context.Background()
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	if _, err := c.SonyOpenLiveView(ctx); err == nil {
		t.Fatal("want error without prop 0xD278")
	}
}
