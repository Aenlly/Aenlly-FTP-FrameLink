package ptpiptest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"io"
	"net"
	"sync/atomic"

	"golang.org/x/crypto/ssh"
)

// SSHServer emulates the camera's Access Authentication SSH server: only
// keyboard-interactive with the right password, and direct-tcpip only to
// "localhost" (not "127.0.0.1"), forwarded to Target.
type SSHServer struct {
	ln          net.Listener
	Target      string // host:port of the fake camera
	Fingerprint string // SHA256 base64 without padding, as shown on the camera
	// AuthAttempts counts password / keyboard-interactive attempts.
	AuthAttempts atomic.Int64
}

// StartSSH listens on 127.0.0.1 with a random port.
func StartSSH(user, password, target string) (*SSHServer, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(signer.PublicKey().Marshal())
	srv := &SSHServer{Target: target, Fingerprint: base64.RawStdEncoding.EncodeToString(sum[:])}
	cfg := &ssh.ServerConfig{
		KeyboardInteractiveCallback: func(c ssh.ConnMetadata, ch ssh.KeyboardInteractiveChallenge) (*ssh.Permissions, error) {
			srv.AuthAttempts.Add(1)
			ans, err := ch(c.User(), "", []string{"Password: "}, []bool{false})
			if err != nil {
				return nil, err
			}
			if c.User() != user || len(ans) != 1 || ans[0] != password {
				return nil, errors.New("denied")
			}
			return nil, nil
		},
	}
	cfg.AddHostKey(signer)
	if srv.ln, err = net.Listen("tcp", "127.0.0.1:0"); err != nil {
		return nil, err
	}
	go func() {
		for {
			c, err := srv.ln.Accept()
			if err != nil {
				return
			}
			go srv.serve(c, cfg)
		}
	}()
	return srv, nil
}

// Addr is host:port of the SSH server.
func (s *SSHServer) Addr() string { return s.ln.Addr().String() }

// Close stops listening (open sessions end when their clients close).
func (s *SSHServer) Close() { s.ln.Close() }

func (s *SSHServer) serve(raw net.Conn, cfg *ssh.ServerConfig) {
	conn, chans, reqs, err := ssh.NewServerConn(raw, cfg)
	if err != nil {
		raw.Close()
		return
	}
	defer conn.Close()
	go ssh.DiscardRequests(reqs)
	for nc := range chans {
		var req struct {
			Host  string
			Port  uint32
			OHost string
			OPort uint32
		}
		if nc.ChannelType() != "direct-tcpip" || ssh.Unmarshal(nc.ExtraData(), &req) != nil {
			nc.Reject(ssh.UnknownChannelType, "unsupported")
			continue
		}
		if req.Host != "localhost" {
			nc.Reject(ssh.Prohibited, "Administratively prohibited")
			continue
		}
		go func() {
			up, err := net.Dial("tcp", s.Target)
			if err != nil {
				nc.Reject(ssh.ConnectionFailed, err.Error())
				return
			}
			ch, creqs, err := nc.Accept()
			if err != nil {
				up.Close()
				return
			}
			go ssh.DiscardRequests(creqs)
			go func() { io.Copy(up, ch); up.Close() }()
			io.Copy(ch, up)
			ch.Close()
		}()
	}
}
