package bridge

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode"
)

const fujiDiscoveryLimit = 8192

type fujiMessage struct {
	method, target string
	headers        map[string]string
}

func validFujiDeviceName(name string) bool {
	if len(name) < 1 || len(name) > 128 || strings.TrimSpace(name) != name {
		return false
	}
	for _, c := range name {
		if unicode.IsControl(c) {
			return false
		}
	}
	return true
}

func parseFujiMessage(raw string) (fujiMessage, error) {
	m := fujiMessage{headers: map[string]string{}}
	if len(raw) > fujiDiscoveryLimit || strings.ContainsRune(raw, '\x00') {
		return m, errors.New("AutoSave发现消息超过上限或含无效字符")
	}
	lines := strings.Split(strings.ReplaceAll(raw, "\r\n", "\n"), "\n")
	if len(lines) > 40 || len(lines) == 0 {
		return m, errors.New("AutoSave消息行数无效")
	}
	first := lines[0]
	space := strings.IndexByte(first, ' ')
	suffix := strings.LastIndex(first, " HTTP/1.1")
	if space < 1 || suffix <= space || suffix+9 != len(first) {
		return m, errors.New("AutoSave请求行无效")
	}
	m.method, m.target = first[:space], first[space+1:suffix]
	for _, line := range lines[1:] {
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, ":")
		key = strings.ToLower(strings.TrimSpace(key))
		value = strings.TrimSpace(value)
		if !ok || key == "" || len(key) > 64 || len(value) > 512 {
			return m, errors.New("AutoSave头字段无效")
		}
		for _, c := range key {
			if c < 'a' || c > 'z' {
				return m, errors.New("AutoSave头字段名称无效")
			}
		}
		if strings.HasPrefix(value, "\"") {
			v, err := strconv.Unquote(value)
			if err != nil {
				return m, err
			}
			value = v
		}
		if strings.ContainsAny(value, "\r\n\x00") {
			return m, errors.New("AutoSave头字段含无效字符")
		}
		if _, exists := m.headers[key]; exists {
			return m, errors.New("AutoSave头字段重复")
		}
		m.headers[key] = value
	}
	return m, nil
}

// Some published Fuji messages end after their required header rather than CRLF CRLF.
func readFujiCallback(conn net.Conn) (fujiMessage, error) {
	reader := bufio.NewReaderSize(io.LimitReader(conn, fujiDiscoveryLimit+1), fujiDiscoveryLimit+1)
	var message strings.Builder
	for message.Len() <= fujiDiscoveryLimit {
		line, err := reader.ReadString('\n')
		message.WriteString(line)
		if err != nil {
			return fujiMessage{}, err
		}
		parsed, parseErr := parseFujiMessage(message.String())
		if parseErr != nil {
			return parsed, parseErr
		}
		done := parsed.headers["dscname"] != "" && (parsed.method == "REGISTER" && parsed.headers["dscmodel"] != "" || parsed.method == "IMPORT" && parsed.headers["dscport"] != "")
		if done || line == "\r\n" || line == "\n" {
			return parsed, nil
		}
	}
	return fujiMessage{}, errors.New("AutoSave回调超过读取上限")
}

type fujiPorts struct {
	register, importing, notify, command int
	listen                               string
}
type fujiPending struct {
	host, method string
	expires      time.Time
}

// FujiAutoSaveReceiver has no open sockets until explicitly started. It never alters a Sony session.
type FujiAutoSaveReceiver struct {
	mu       sync.Mutex
	binder   NetworkBinder
	ctx      context.Context
	cancel   context.CancelFunc
	closers  []io.Closer
	wg       sync.WaitGroup
	pending  *fujiPending
	state    string
	name     string
	ports    fujiPorts
	invites  chan string
	requests chan fujiPending
}

// NewFujiAutoSaveReceiver allocates a disabled receiver; normal connection UI does not enable it.
func NewFujiAutoSaveReceiver() *FujiAutoSaveReceiver { return &FujiAutoSaveReceiver{state: "disabled"} }
func (r *FujiAutoSaveReceiver) SetNetworkBinder(binder NetworkBinder) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cancel == nil {
		r.binder = binder
	}
}

// Start requires explicit experimental consent and a local IPv4 address on the camera LAN.
func (r *FujiAutoSaveReceiver) Start(localIP, name string, experimental bool) error {
	if !experimental {
		return errors.New("Fuji AutoSave实验接收默认关闭")
	}
	return r.start(localIP, name, fujiPorts{51542, 51541, 51540, fujiCommandPort, "0.0.0.0"})
}

func (r *FujiAutoSaveReceiver) start(localIP, name string, ports fujiPorts) error {
	if ip := net.ParseIP(localIP); ip == nil || ip.To4() == nil || !fujiLocalAddress(localIP, localIP) {
		return errors.New("请选择手机当前相机局域网的IPv4地址")
	}
	if !fujiImporterName(name) {
		return errors.New("AutoSave接收名称无效")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.cancel != nil {
		return errors.New("AutoSave接收器已启动")
	}
	ctx, cancel := context.WithCancel(context.Background())
	lc := net.ListenConfig{}
	if r.binder != nil {
		lc.Control = func(_, _ string, raw syscall.RawConn) error {
			var e error
			err := raw.Control(func(fd uintptr) { e = r.binder.BindSocket(int64(fd)) })
			return errors.Join(err, e)
		}
	}
	var resources []io.Closer
	fail := func(err error) error {
		cancel()
		for _, c := range resources {
			c.Close()
		}
		return err
	}
	type endpoint struct {
		tcp      net.Listener
		udp      net.PacketConn
		register bool
	}
	var endpoints []endpoint
	for _, registration := range []bool{true, false} {
		port := ports.importing
		if registration {
			port = ports.register
		}
		tcp, err := lc.Listen(ctx, "tcp4", net.JoinHostPort(ports.listen, strconv.Itoa(port)))
		if err != nil {
			return fail(err)
		}
		resources = append(resources, tcp)
		actual := tcp.Addr().(*net.TCPAddr).Port
		udp, err := lc.ListenPacket(ctx, "udp4", net.JoinHostPort(ports.listen, strconv.Itoa(actual)))
		if err != nil {
			return fail(err)
		}
		resources = append(resources, udp)
		if registration {
			ports.register = actual
		} else {
			ports.importing = actual
		}
		endpoints = append(endpoints, endpoint{tcp, udp, registration})
	}
	r.ctx, r.cancel, r.closers, r.name, r.ports, r.state = ctx, cancel, resources, name, ports, "listening"
	r.invites = make(chan string, 1)
	r.requests = make(chan fujiPending, 4)
	for _, e := range endpoints {
		r.wg.Add(2)
		go r.readDatagrams(e.udp, e.register, localIP)
		go r.acceptCallbacks(e.tcp, e.register)
	}
	r.wg.Add(1)
	go r.notifyLoop()
	return nil
}

func fujiLocalAddress(local, peer string) bool {
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		return false
	}
	l, p := net.ParseIP(local), net.ParseIP(peer)
	if l == nil || p == nil {
		return false
	}
	for _, address := range addresses {
		ip, subnet, err := net.ParseCIDR(address.String())
		if err == nil && ip.Equal(l) && subnet.Contains(p) {
			return true
		}
	}
	return false
}

func (r *FujiAutoSaveReceiver) readDatagrams(socket net.PacketConn, registration bool, localIP string) {
	defer r.wg.Done()
	b := make([]byte, fujiDiscoveryLimit+1)
	for {
		n, source, err := socket.ReadFrom(b)
		if err != nil {
			return
		}
		message, err := parseFujiMessage(string(b[:n]))
		if err != nil {
			continue
		}
		host, _, err := net.SplitHostPort(source.String())
		if err != nil || message.method != "DISCOVER" || message.headers["service"] != "PCAUTOSAVE/1.0" {
			continue
		}
		if !net.ParseIP(host).Equal(net.ParseIP(message.headers["dscaddr"])) || !fujiLocalAddress(localIP, host) {
			continue
		}
		method := "IMPORT"
		if registration {
			method = "REGISTER"
			if message.target != "*" {
				continue
			}
		} else if message.target != r.name {
			continue
		}
		select {
		case r.requests <- fujiPending{host: host, method: method}:
		default:
		}
	}
}

func (r *FujiAutoSaveReceiver) track(conn net.Conn) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.ctx.Err() != nil {
		conn.Close()
		return false
	}
	r.closers = append(r.closers, conn)
	return true
}
func (r *FujiAutoSaveReceiver) untrack(conn net.Conn) {
	conn.Close()
	r.mu.Lock()
	defer r.mu.Unlock()
	for i, c := range r.closers {
		if c == conn {
			r.closers = append(r.closers[:i], r.closers[i+1:]...)
			break
		}
	}
}

func (r *FujiAutoSaveReceiver) notifyLoop() {
	defer r.wg.Done()
	for {
		select {
		case <-r.ctx.Done():
			return
		case request := <-r.requests:
			r.mu.Lock()
			busy := r.state == "ready" || r.pending != nil && time.Now().Before(r.pending.expires)
			if !busy {
				request.expires = time.Now().Add(30 * time.Second)
				r.pending = &request
				r.state = "awaiting-" + strings.ToLower(request.method)
			}
			r.mu.Unlock()
			if busy {
				continue
			}
			ctx, stop := context.WithTimeout(r.ctx, 3*time.Second)
			dialer := net.Dialer{}
			if r.binder != nil {
				dialer.Control = func(_, _ string, raw syscall.RawConn) error {
					var e error
					err := raw.Control(func(fd uintptr) { e = r.binder.BindSocket(int64(fd)) })
					return errors.Join(err, e)
				}
			}
			conn, err := dialer.DialContext(ctx, "tcp4", net.JoinHostPort(request.host, strconv.Itoa(r.ports.notify)))
			if err == nil && r.track(conn) {
				conn.SetDeadline(time.Now().Add(3 * time.Second))
				err = fujiWriteAll(conn, []byte(fmt.Sprintf("NOTIFY * HTTP/1.1\r\nHOST: %s:%d\r\nIMPORTER: %s\r\n\r\n", request.host, r.ports.notify, r.name)))
				if err == nil {
					var line string
					line, err = bufio.NewReaderSize(io.LimitReader(conn, 513), 512).ReadString('\n')
					if err == nil && (len(line) > 512 || strings.TrimSpace(line) != "HTTP/1.1 200 OK") {
						err = errors.New("机身拒绝AutoSave接收器")
					}
				}
				r.untrack(conn)
			}
			stop()
			if err != nil {
				r.mu.Lock()
				if r.pending != nil && r.pending.host == request.host && r.pending.method == request.method {
					r.pending = nil
					r.state = "listening"
				}
				r.mu.Unlock()
			}
		}
	}
}

func (r *FujiAutoSaveReceiver) acceptCallbacks(listener net.Listener, registration bool) {
	defer r.wg.Done()
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		if !r.track(conn) {
			return
		}
		r.callback(conn, registration)
		r.untrack(conn)
	}
}

func (r *FujiAutoSaveReceiver) callback(conn net.Conn, registration bool) {
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	host, _, err := net.SplitHostPort(conn.RemoteAddr().String())
	if err != nil {
		return
	}
	message, err := readFujiCallback(conn)
	if err != nil {
		return
	}
	method, target := "IMPORT", "/guest"
	if registration {
		method, target = "REGISTER", "*"
	}
	if message.method != method || message.target != target || !validFujiDeviceName(message.headers["dscname"]) {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.pending == nil || r.pending.host != host || r.pending.method != method || time.Now().After(r.pending.expires) || r.ctx.Err() != nil {
		return
	}
	response := "HTTP/1.1 200 OK\r\n\r\n"
	if registration {
		response = "HTTP/1.1 200 OK\r\nFOLDER: guest\r\nServiceName: PCAUTOSAVE/1.0\r\n\r\n"
	}
	if err = fujiWriteAll(conn, []byte(response)); err != nil {
		return
	}
	r.pending = nil
	if registration {
		r.state = "registered"
		return
	}
	config, _ := marshal(map[string]any{"protocol": protocolFujiAutoSave, "experimentalFuji": true, "host": host, "port": r.ports.command, "fujiDeviceName": message.headers["dscname"], "mediaScope": "AUTOSAVE_PENDING_BATCH"})
	select {
	case r.invites <- config:
		r.state = "ready"
	default:
	}
}

// NextInvitation returns only an accepted AutoSave import callback, never a registration.
func (r *FujiAutoSaveReceiver) NextInvitation(timeoutMs int) (string, error) {
	r.mu.Lock()
	ctx, invites := r.ctx, r.invites
	r.mu.Unlock()
	if ctx == nil {
		return "", errors.New("AutoSave接收器尚未启动")
	}
	if ctx.Err() != nil {
		return "", errors.New("AutoSave接收器已关闭")
	}
	if timeoutMs < 1 || timeoutMs > 60000 {
		return "", errors.New("AutoSave等待范围为1至60000毫秒")
	}
	timer := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
	defer timer.Stop()
	select {
	case value := <-invites:
		if ctx.Err() != nil {
			return "", errors.New("AutoSave接收器已关闭")
		}
		return value, nil
	case <-ctx.Done():
		return "", errors.New("AutoSave接收器已关闭")
	case <-timer.C:
		return "", errors.New("尚未收到AutoSave传输邀请，请在机身回放菜单启动PC AutoSave")
	}
}
func (r *FujiAutoSaveReceiver) State() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	value, _ := marshal(map[string]any{"state": r.state, "experimental": true, "hardwareVerified": false, "mediaScope": "AUTOSAVE_PENDING_BATCH"})
	return value
}
func (r *FujiAutoSaveReceiver) Close() {
	r.mu.Lock()
	if r.cancel == nil {
		r.mu.Unlock()
		return
	}
	r.cancel()
	for _, c := range r.closers {
		c.Close()
	}
	r.state = "closed"
	r.mu.Unlock()
	r.wg.Wait()
}
