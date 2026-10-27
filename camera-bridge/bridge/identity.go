package bridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"syscall"
	"time"

	"golang.org/x/crypto/ssh"
)

// cameraIdentity is an observed SSH host key, not an authenticated camera model.
type cameraIdentity struct {
	Host        string `json:"host"`
	Port        int    `json:"port"`
	Fingerprint string `json:"fingerprint"`
	Algorithm   string `json:"algorithm"`
}

// ProbeIdentity reads the host key and stops before any user authentication.
// The caller must obtain user confirmation and pin this key before Connect.
func (s *Session) ProbeIdentity(host string) (string, error) {
	address := net.ParseIP(host)
	if address == nil {
		return "", errors.New("请输入相机的局域网IP地址")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	identity, err := s.probeIdentity(ctx, net.JoinHostPort(address.String(), "22"))
	if err != nil {
		return "", fmt.Errorf("读取相机身份失败，请检查相机IP、电脑遥控与访问身份验证是否开启：%w", err)
	}
	return marshal(identity)
}

func (s *Session) probeIdentity(ctx context.Context, address string) (cameraIdentity, error) {
	var identity cameraIdentity
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	if s.binder != nil {
		dialer.Control = func(_, _ string, raw syscall.RawConn) error {
			var bindErr error
			err := raw.Control(func(fd uintptr) { bindErr = s.binder.BindSocket(int64(fd)) })
			return errors.Join(err, bindErr)
		}
	}
	raw, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return identity, err
	}
	defer raw.Close()
	if deadline, ok := ctx.Deadline(); ok {
		if err = raw.SetDeadline(deadline); err != nil {
			return identity, err
		}
	}
	stop := context.AfterFunc(ctx, func() { raw.Close() })
	defer stop()
	observed := errors.New("SSH identity observed before authentication")
	config := &ssh.ClientConfig{
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			host, _, _ := net.SplitHostPort(address)
			identity = cameraIdentity{Host: host, Port: 22, Fingerprint: ssh.FingerprintSHA256(key), Algorithm: key.Type()}
			return observed
		},
	}
	conn, _, _, err := ssh.NewClientConn(raw, address, config)
	if conn != nil {
		conn.Close()
	}
	if ctx.Err() != nil {
		return cameraIdentity{}, ctx.Err()
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return cameraIdentity{}, context.DeadlineExceeded
	}
	if !errors.Is(err, observed) || identity.Fingerprint == "" {
		if err == nil {
			err = errors.New("SSH server did not provide a host key")
		}
		return cameraIdentity{}, err
	}
	return identity, nil
}
