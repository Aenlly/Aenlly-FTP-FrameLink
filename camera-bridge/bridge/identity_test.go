package bridge

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func TestIdentityProbeReadsKeyWithoutSendingCredentials(t *testing.T) {
	server, err := ptpiptest.StartSSH("camera", "must-not-be-sent", "127.0.0.1:1")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	identity, err := NewSession().probeIdentity(ctx, server.Addr())
	if err != nil {
		t.Fatal(err)
	}
	if identity.Fingerprint != "SHA256:"+server.Fingerprint || identity.Host != "127.0.0.1" || identity.Algorithm == "" {
		t.Fatalf("unexpected identity: %+v", identity)
	}
	if server.AuthAttempts.Load() != 0 {
		t.Fatal("probe attempted authentication before confirmation")
	}
	if _, err = NewSession().ProbeIdentity("camera.invalid"); err == nil {
		t.Fatal("probe accepted a non-IP address")
	}
}

func TestIdentityProbeCancellationBoundsStalledHandshake(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			buffer := make([]byte, 256)
			for {
				if _, err = conn.Read(buffer); err != nil {
					return
				}
			}
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	_, err = NewSession().probeIdentity(ctx, listener.Addr().String())
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("stalled handshake did not observe cancellation: %v", err)
	}
}
