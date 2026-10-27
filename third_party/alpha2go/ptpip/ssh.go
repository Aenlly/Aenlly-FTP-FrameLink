package ptpip

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// SSHConfig is what the camera shows under Access Authentication info.
type SSHConfig struct {
	User        string
	Password    string
	Fingerprint string // required: "SHA256:..." or just the base64 part
	Port        int    // SSH port on the camera (default 22)
	// DialContext optionally binds only camera sockets to the Android camera network.
	DialContext func(context.Context, string, string) (net.Conn, error)
}

// SSHDialer tunnels PTP/IP through the camera's SSH server. With Access
// Authentication on, Sony cameras close port 15740 and only allow SSH
// port forwarding to "localhost:15740" (not "127.0.0.1"). Pass its
// DialContext in Options. Both PTP/IP connections share one SSH session.
type SSHDialer struct {
	cfg SSHConfig

	mu     sync.Mutex
	client *ssh.Client
}

func NewSSHDialer(cfg SSHConfig) *SSHDialer {
	if cfg.Port == 0 {
		cfg.Port = 22
	}
	return &SSHDialer{cfg: cfg}
}

// ErrSSHAuth means the camera's SSH server rejected the user/password.
var ErrSSHAuth = errors.New("camera rejected the Access Authentication user/password")

// FingerprintMismatchError means the camera's host key does not match the
// fingerprint shown on the camera: a different device answered.
type FingerprintMismatchError struct{ Got, Want string }

func (e *FingerprintMismatchError) Error() string {
	return fmt.Sprintf("ptpip: camera SSH fingerprint %s does not match expected %s", e.Got, e.Want)
}

func normFingerprint(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "SHA256:")
	return strings.TrimRight(s, "=")
}

// DialContext connects (or reuses) the SSH session to addr's host and opens
// a direct-tcpip channel to localhost:<addr's port>.
func (d *SSHDialer) DialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	c, err := d.session(ctx, host)
	if err != nil {
		return nil, err
	}
	conn, err := c.DialContext(ctx, network, net.JoinHostPort("localhost", port))
	if err != nil {
		d.reset(c)
		return nil, fmt.Errorf("ptpip: SSH tunnel to camera port %s: %w", port, err)
	}
	// SSH channels do not support deadlines; emulate them so IOTimeout works.
	return &deadlineConn{Conn: conn}, nil
}

func (d *SSHDialer) session(ctx context.Context, host string) (*ssh.Client, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.client != nil {
		return d.client, nil
	}
	// The host key is checked before the password is sent, so a fingerprint
	// is mandatory: it keeps the password from other SSH hosts on the LAN.
	want := normFingerprint(d.cfg.Fingerprint)
	if want == "" {
		return nil, errors.New("ptpip: the Access Authentication fingerprint is required (CAMERA_FINGERPRINT, shown on the camera under Access Authen. Info)")
	}
	cfg := &ssh.ClientConfig{
		User: d.cfg.User,
		Auth: []ssh.AuthMethod{
			ssh.Password(d.cfg.Password),
			// The camera may only offer keyboard-interactive; answer every prompt with the password.
			ssh.KeyboardInteractive(func(_, _ string, questions []string, _ []bool) ([]string, error) {
				answers := make([]string, len(questions))
				for i := range answers {
					answers[i] = d.cfg.Password
				}
				return answers, nil
			}),
		},
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			sum := sha256.Sum256(key.Marshal())
			got := base64.RawStdEncoding.EncodeToString(sum[:])
			if got != want {
				return &FingerprintMismatchError{Got: "SHA256:" + got, Want: "SHA256:" + want}
			}
			return nil
		},
		Timeout: 10 * time.Second,
	}
	addr := net.JoinHostPort(host, fmt.Sprint(d.cfg.Port))
	var nd net.Dialer
	dial := nd.DialContext
	if d.cfg.DialContext != nil {
		dial = d.cfg.DialContext
	}
	raw, err := dial(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	// NewClientConn ignores cfg.Timeout and ctx: bound the handshake ourselves,
	// a stalled SSH server (or a non-camera found by the scan) must not hang us.
	raw.SetDeadline(time.Now().Add(cfg.Timeout))
	stop := context.AfterFunc(ctx, func() { raw.Close() })
	conn, chans, reqs, err := ssh.NewClientConn(raw, addr, cfg)
	stop()
	raw.SetDeadline(time.Time{})
	if err == nil && ctx.Err() != nil {
		conn.Close()
		err = ctx.Err()
	}
	if err != nil {
		raw.Close()
		if strings.Contains(err.Error(), "unable to authenticate") {
			err = fmt.Errorf("%w (%v)", ErrSSHAuth, err)
		}
		return nil, fmt.Errorf("ptpip: SSH login to camera: %w", err)
	}
	d.client = ssh.NewClient(conn, chans, reqs)
	go func(c *ssh.Client) { c.Wait(); d.reset(c) }(d.client)
	return d.client, nil
}

func (d *SSHDialer) reset(c *ssh.Client) {
	d.mu.Lock()
	if d.client == c {
		d.client = nil
	}
	d.mu.Unlock()
	c.Close()
}

// Close ends the SSH session and all tunnels.
func (d *SSHDialer) Close() error {
	d.mu.Lock()
	c := d.client
	d.client = nil
	d.mu.Unlock()
	if c != nil {
		return c.Close()
	}
	return nil
}

// deadlineConn emulates SetReadDeadline on an SSH channel (x/crypto returns
// "deadline not supported") by closing the channel when the deadline passes.
// After a timeout the connection is unusable, which is fine for PTP/IP: the
// client treats any I/O timeout as fatal.
type deadlineConn struct {
	net.Conn
	mu       sync.Mutex
	timer    *time.Timer
	timedOut bool
}

func (c *deadlineConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.timer != nil {
		c.timer.Stop()
		c.timer = nil
	}
	if t.IsZero() {
		return nil
	}
	c.timer = time.AfterFunc(time.Until(t), func() {
		c.mu.Lock()
		c.timedOut = true
		c.mu.Unlock()
		c.Conn.Close()
	})
	return nil
}

func (c *deadlineConn) SetDeadline(t time.Time) error { return c.SetReadDeadline(t) }

func (c *deadlineConn) SetWriteDeadline(time.Time) error { return nil }

func (c *deadlineConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if err != nil {
		c.mu.Lock()
		if c.timedOut {
			err = os.ErrDeadlineExceeded
		}
		c.mu.Unlock()
	}
	return n, err
}

func (c *deadlineConn) Close() error {
	c.mu.Lock()
	if c.timer != nil {
		c.timer.Stop()
	}
	c.mu.Unlock()
	return c.Conn.Close()
}
