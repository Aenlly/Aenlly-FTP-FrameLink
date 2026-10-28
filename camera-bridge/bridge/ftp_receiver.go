package bridge

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
)

// ReceiverEvents reports a receipt already persisted beside the original.
type ReceiverEvents interface{ OnReceived(receipt string) }

// FTPReceiver is a receive-only foreground service for the native iOS application.
// It exposes neither remote original reads nor deletion/rename commands.
type FTPReceiver struct {
	mu                   sync.Mutex
	listener             net.Listener
	connections          map[net.Conn]bool
	passives             map[net.Listener]bool
}

func NewFTPReceiver() *FTPReceiver { return &FTPReceiver{} }

func (s *FTPReceiver) Start(address, user, password, root string, events ReceiverEvents) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener != nil {
		return errors.New("FTP接收已启动")
	}
	if user == "" || len(password) < 8 || !filepath.IsAbs(root) {
		return errors.New("请设置账号、至少8位密码和App保存目录")
	}
	if safeFTP(user) != nil || safeFTP(password) != nil {
		return errors.New("认证信息包含无效字符")
	}
	for _, directory := range []string{"received", ".incoming", "receipts"} {
		if err := os.MkdirAll(filepath.Join(root, directory), 0700); err != nil {
			return err
		}
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	s.connections = map[net.Conn]bool{}
	s.passives = map[net.Listener]bool{}
	s.listener = listener
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			s.mu.Lock()
			if s.listener != listener || len(s.connections) >= 4 {
				s.mu.Unlock()
				conn.Close()
				continue
			}
			s.connections[conn] = true
			s.mu.Unlock()
			go s.serve(conn, listener, root, user, password, events)
		}
	}()
	return nil
}

func (s *FTPReceiver) Address() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener == nil {
		return ""
	}
	return s.listener.Addr().String()
}

func (s *FTPReceiver) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.listener != nil {
		s.listener.Close()
		s.listener = nil
	}
	for conn := range s.connections {
		conn.Close()
	}
	for listener := range s.passives {
		listener.Close()
	}
}

func (s *FTPReceiver) serve(conn net.Conn, owner net.Listener, root, username, password string, events ReceiverEvents) {
	defer func() { conn.Close(); s.mu.Lock(); delete(s.connections, conn); s.mu.Unlock() }()
	reader := bufio.NewReaderSize(conn, 8192)
	reply := func(code int, text string) {
		conn.SetWriteDeadline(time.Now().Add(20 * time.Second))
		fmt.Fprintf(conn, "%d %s\r\n", code, text)
	}
	reply(220, "FrameLink receive-only FTP")
	var passive net.Listener
	closePassive := func() {
		if passive != nil {
			passive.Close()
			s.mu.Lock()
			delete(s.passives, passive)
			s.mu.Unlock()
			passive = nil
		}
	}
	defer closePassive()
	name := ""
	authenticated := false
	binary := false
	attempts := 0
	var expected int64 = -1
	for {
		conn.SetReadDeadline(time.Now().Add(3 * time.Minute))
		line, err := ftpReadLine(reader, 8192)
		if err != nil {
			return
		}
		if len(line) > 8192 {
			reply(500, "Command too long")
			return
		}
		line = strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
		parts := strings.SplitN(line, " ", 2)
		command := strings.ToUpper(parts[0])
		arg := ""
		if len(parts) == 2 {
			arg = parts[1]
		}
		if safeFTP(arg) != nil {
			reply(501, "Invalid argument")
			continue
		}
		switch command {
		case "USER":
			name = arg
			authenticated = false
			reply(331, "Password required")
			continue
		case "PASS":
			authenticated = subtle.ConstantTimeCompare([]byte(name), []byte(username)) == 1 && subtle.ConstantTimeCompare([]byte(arg), []byte(password)) == 1
			if authenticated {
				reply(230, "Logged in")
			} else {
				attempts++
				reply(530, "Login incorrect")
				if attempts >= 3 {
					return
				}
			}
			continue
		case "QUIT":
			reply(221, "Goodbye")
			return
		case "FEAT":
			io.WriteString(conn, "211-Features\r\n UTF8\r\n EPSV\r\n211 End\r\n")
			continue
		case "SYST":
			reply(215, "UNIX Type: L8")
			continue
		case "NOOP":
			reply(200, "OK")
			continue
		}
		if !authenticated {
			reply(530, "Login required")
			continue
		}
		switch command {
		case "TYPE":
			binary = strings.EqualFold(arg, "I")
			if binary {
				reply(200, "Binary originals")
			} else {
				reply(504, "Use TYPE I")
			}
		case "OPTS":
			if strings.EqualFold(arg, "UTF8 ON") {
				reply(200, "UTF8 enabled")
			} else {
				reply(501, "UTF8 ON supported")
			}
		case "PWD", "XPWD":
			reply(257, "\"/\" is the virtual camera receive directory")
		case "CWD", "CDUP":
			reply(250, "Receive directory ready")
		case "MKD", "XMKD":
			reply(257, "\"/\" receive directory ready")
		case "ALLO":
			fields := strings.Fields(arg)
			if len(fields) == 0 {
				reply(501, "Invalid allocation")
				continue
			}
			expected, err = strconv.ParseInt(fields[0], 10, 64)
			if err != nil || expected < 0 {
				expected = -1
				reply(501, "Invalid allocation")
			} else {
				reply(200, "Allocation noted")
			}
		case "PASV", "EPSV":
			closePassive()
			local := conn.LocalAddr().(*net.TCPAddr).IP
			passive, err = net.Listen("tcp", net.JoinHostPort(local.String(), "0"))
			if err != nil {
				reply(425, "Cannot open passive port")
				continue
			}
			s.mu.Lock()
			if s.listener != owner {
				s.mu.Unlock()
				closePassive()
				return
			}
			s.passives[passive] = true
			s.mu.Unlock()
			port := passive.Addr().(*net.TCPAddr).Port
			if command == "EPSV" {
				reply(229, fmt.Sprintf("Entering Extended Passive Mode (|||%d|)", port))
			} else {
				ip := local.To4()
				if ip == nil {
					closePassive()
					reply(522, "Use EPSV for IPv6")
					continue
				}
				reply(227, fmt.Sprintf("Entering Passive Mode (%d,%d,%d,%d,%d,%d)", ip[0], ip[1], ip[2], ip[3], port/256, port%256))
			}
		case "STOR":
			if !binary {
				reply(550, "Use TYPE I")
				continue
			}
			if passive == nil {
				reply(503, "Use PASV or EPSV first")
				continue
			}
			if tcp, ok := passive.(*net.TCPListener); ok {
				tcp.SetDeadline(time.Now().Add(20 * time.Second))
			}
			reply(150, "Opening binary data connection")
			data, openErr := passive.Accept()
			closePassive()
			if openErr != nil {
				reply(425, "Data connection failed")
				continue
			}
			if !data.RemoteAddr().(*net.TCPAddr).IP.Equal(conn.RemoteAddr().(*net.TCPAddr).IP) {
				data.Close()
				reply(425, "Data peer must match control peer")
				continue
			}
			s.mu.Lock()
			if s.listener != owner {
				s.mu.Unlock()
				data.Close()
				return
			}
			s.connections[data] = true
			s.mu.Unlock()
			receipt, storeErr := s.receive(root, arg, data, expected)
			data.Close()
			s.mu.Lock()
			delete(s.connections, data)
			s.mu.Unlock()
			expected = -1
			if storeErr != nil {
				reply(551, "Upload not committed; resend original")
				continue
			}
			reply(226, "Original and receipt saved")
			if events != nil {
				events.OnReceived(receipt)
			}
		default:
			reply(502, "Receive-only command not supported")
		}
	}
}

func (s *FTPReceiver) receive(root, name string, data net.Conn, expected int64) (string, error) {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, name)
	if name == "" || name == "." || name == ".." {
		return "", errors.New("文件名无效")
	}
	runes := []rune(name)
	if len(runes) > 160 {
		name = string(runes[:160])
	}
	idBytes := make([]byte, 16)
	if _, err := rand.Read(idBytes); err != nil {
		return "", err
	}
	id := hex.EncodeToString(idBytes)
	part := filepath.Join(root, ".incoming", id+".part")
	destination := filepath.Join(root, "received", id+"_"+name)
	file, err := os.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	defer os.Remove(part)
	hash := sha256.New()
	buffer := make([]byte, 256<<10)
	var count int64
	for {
		data.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, readErr := data.Read(buffer)
		if n > 0 {
			if _, err = file.Write(buffer[:n]); err != nil {
				break
			}
			hash.Write(buffer[:n])
			count += int64(n)
		}
		if readErr != nil {
			if readErr != io.EOF {
				err = readErr
			}
			break
		}
	}
	if err == nil && expected >= 0 && count != expected {
		err = errors.New("上传长度不符")
	}
	if err == nil {
		err = file.Sync()
	}
	file.Close()
	if err != nil {
		return "", err
	}
	if err = os.Rename(part, destination); err != nil {
		return "", err
	}
	value := map[string]any{"id": id, "name": name, "path": destination, "size": count, "sha256": hex.EncodeToString(hash.Sum(nil)), "receivedAt": time.Now().UnixMilli()}
	bytes, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	journal := filepath.Join(root, "receipts", id+".json")
	log, err := os.OpenFile(journal+".part", os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	_, err = log.Write(bytes)
	if err == nil {
		err = log.Sync()
	}
	log.Close()
	if err != nil {
		return "", err
	}
	if err = os.Rename(journal+".part", journal); err != nil {
		return "", err
	}
	for _, directory := range []string{filepath.Dir(destination), filepath.Dir(journal)} {
		if folder, e := os.Open(directory); e == nil {
			folder.Sync()
			folder.Close()
		}
	}
	return string(bytes), nil
}
