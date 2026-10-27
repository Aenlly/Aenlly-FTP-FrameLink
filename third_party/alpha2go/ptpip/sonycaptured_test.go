package ptpip_test

import (
	"bytes"
	"context"
	"testing"

	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func TestSonyCapturedImage(t *testing.T) {
	ctx := context.Background()
	_, c := controlClient(t, 0)
	pending := func(wantReady bool, wantN int) {
		t.Helper()
		ready, n, err := c.SonyCapturedPending(ctx)
		if err != nil || ready != wantReady || n != wantN {
			t.Fatalf("pending = %v %d %v, want %v %d", ready, n, err, wantReady, wantN)
		}
	}
	pending(false, 0)
	for range 2 {
		if err := c.SonyCapture(ctx, false); err != nil {
			t.Fatal(err)
		}
	}
	pending(true, 2)
	var buf bytes.Buffer
	info, n, err := c.SonyGetCapturedImage(ctx, &buf, nil)
	if err != nil || info.Filename != "DSC00001.JPG" || n != int64(len(ptpiptest.CapturedImage)) || !bytes.Equal(buf.Bytes(), ptpiptest.CapturedImage) {
		t.Fatalf("got %+v %d %v", info, n, err)
	}
	pending(true, 1)
}
