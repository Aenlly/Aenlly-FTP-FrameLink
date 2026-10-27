package ptpip_test

import (
	"bytes"
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func sshSetup(t *testing.T) (*ptpiptest.Server, *ptpiptest.SSHServer) {
	t.Helper()
	cam, err := ptpiptest.Start(ptpiptest.Options{Dir: card(t), A6700: true})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(cam.Close)
	ssh, err := ptpiptest.StartSSH("user", "secret", cam.Addr())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(ssh.Close)
	return cam, ssh
}

func dialSSH(ssh *ptpiptest.SSHServer, cfg ptpip.SSHConfig) (*ptpip.Client, *ptpip.SSHDialer, error) {
	_, port, _ := splitHostPort(ssh.Addr())
	cfg.Port = port
	d := ptpip.NewSSHDialer(cfg)
	// The port of the dialed address is the PTP port (the fake ignores it).
	c, err := ptpip.Dial(context.Background(), "127.0.0.1:15740", ptpip.Options{DialContext: d.DialContext, IOTimeout: 5 * time.Second})
	return c, d, err
}

func TestSSHDialer(t *testing.T) {
	_, ssh := sshSetup(t)
	good := ptpip.SSHConfig{User: "user", Password: "secret", Fingerprint: "SHA256:" + ssh.Fingerprint}

	c, d, err := dialSSH(ssh, good)
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	defer c.Close()
	ctx := context.Background()
	di, err := c.GetDeviceInfo(ctx)
	if err != nil || !di.IsSony() {
		t.Fatalf("device info %v %v", di, err)
	}

	// Fingerprint without prefix works too.
	good.Fingerprint = ssh.Fingerprint
	if c2, d2, err := dialSSH(ssh, good); err != nil {
		t.Fatal(err)
	} else {
		c2.Close()
		d2.Close()
	}

	t.Run("wrong fingerprint never sends the password", func(t *testing.T) {
		_, ssh := sshSetup(t)
		_, _, err := dialSSH(ssh, ptpip.SSHConfig{User: "user", Password: "secret", Fingerprint: "AAAA"})
		var fe *ptpip.FingerprintMismatchError
		if !errors.As(err, &fe) {
			t.Fatalf("want FingerprintMismatchError, got %v", err)
		}
		if n := ssh.AuthAttempts.Load(); n != 0 {
			t.Fatalf("password was sent (%d attempts)", n)
		}
	})
	t.Run("wrong password", func(t *testing.T) {
		_, ssh := sshSetup(t)
		_, _, err := dialSSH(ssh, ptpip.SSHConfig{User: "user", Password: "nope", Fingerprint: ssh.Fingerprint})
		if !errors.Is(err, ptpip.ErrSSHAuth) {
			t.Fatalf("want ErrSSHAuth, got %v", err)
		}
	})
	t.Run("empty fingerprint refused", func(t *testing.T) {
		_, ssh := sshSetup(t)
		_, _, err := dialSSH(ssh, ptpip.SSHConfig{User: "user", Password: "secret"})
		if err == nil || ssh.AuthAttempts.Load() != 0 {
			t.Fatalf("want refusal without auth, got %v (attempts %d)", err, ssh.AuthAttempts.Load())
		}
	})
}

func TestA6700Download(t *testing.T) {
	dir := card(t)
	srv, err := ptpiptest.Start(ptpiptest.Options{Dir: dir, A6700: true})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()
	c, err := ptpip.Dial(context.Background(), srv.Addr(), ptpip.Options{IOTimeout: 5 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx := context.Background()
	di, _ := c.GetDeviceInfo(ctx)
	c.OpenSession(ctx, 1)
	if _, err := c.GetStorageIDs(ctx); !ptpip.IsCode(err, ptpip.RespStoreNotAvailable) {
		t.Fatalf("want StoreNotAvailable, got %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	if err := c.SonySetContentsTransferMode(ctx); err != nil {
		t.Fatal(err)
	}
	select {
	case ev := <-c.Events():
		if ev.Code != ptpip.EvStoreAdded {
			t.Fatalf("event %+v", ev)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no StoreAdded")
	}
	ids, err := c.GetStorageIDs(ctx)
	if err != nil || len(ids) == 0 {
		t.Fatalf("storages %v %v", ids, err)
	}
	hs, err := c.GetObjectHandles(ctx, ids[0], 0, ptpip.ParentAll)
	if err != nil {
		t.Fatal(err)
	}
	// Standard partial reads are rejected in this mode.
	if _, err := c.GetPartialObject(ctx, hs[len(hs)-1], 0, 10, &bytes.Buffer{}); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatalf("want InvalidObjectHandle, got %v", err)
	}
	for _, h := range hs {
		oi, err := c.GetObjectInfo(ctx, h)
		if err != nil {
			t.Fatal(err)
		}
		if oi.IsFolder() {
			continue
		}
		want, _ := os.ReadFile(filepath.Join(dir, "slot1/DCIM/100MSDCF", oi.Filename))
		for name, di := range map[string]*ptpip.DeviceInfo{"sony": di, "standard partial, falls back to GetObject": withoutSony(di)} {
			var buf bytes.Buffer
			n, err := c.DownloadObject(ctx, di, h, int64(oi.CompressedSize), &buf, nil)
			if err != nil || !bytes.Equal(buf.Bytes(), want) {
				t.Fatalf("%s %s: n=%d err=%v", name, oi.Filename, n, err)
			}
		}
	}
}

func withoutSony(di *ptpip.DeviceInfo) *ptpip.DeviceInfo {
	c := *di
	c.Operations = nil
	for _, op := range di.Operations {
		if op != ptpip.OpSonyGetPartialLargeObject {
			c.Operations = append(c.Operations, op)
		}
	}
	return &c
}

func splitHostPort(addr string) (string, int, error) {
	h, p, err := net.SplitHostPort(addr)
	if err != nil {
		return "", 0, err
	}
	n, err := strconv.Atoi(p)
	return h, n, err
}

// The SSH channel fakes read deadlines with a closing timer; an idle session
// must survive longer than IOTimeout.
func TestSSHIdleOutlivesIOTimeout(t *testing.T) {
	_, ssh := sshSetup(t)
	_, port, _ := splitHostPort(ssh.Addr())
	d := ptpip.NewSSHDialer(ptpip.SSHConfig{User: "user", Password: "secret", Fingerprint: ssh.Fingerprint, Port: port})
	defer d.Close()
	c, err := ptpip.Dial(context.Background(), "127.0.0.1:15740", ptpip.Options{DialContext: d.DialContext, IOTimeout: 300 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx := context.Background()
	if _, err := c.GetDeviceInfo(ctx); err != nil {
		t.Fatal(err)
	}
	time.Sleep(900 * time.Millisecond)
	select {
	case <-c.Done():
		t.Fatalf("session died while idle: %v", c.Err())
	default:
	}
	if _, err := c.GetDeviceInfo(ctx); err != nil {
		t.Fatalf("after idle: %v", err)
	}
}
