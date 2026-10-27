package ptpip

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// Options configure a connection.
type Options struct {
	Name        string        // initiator friendly name shown by some cameras
	GUID        [16]byte      // initiator GUID; random if zero. Keep it stable for pairing.
	DialTimeout time.Duration // per TCP connect (default 10s)
	IOTimeout   time.Duration // max silence while waiting for a packet (default 30s)
	// DialContext opens the command and event connections. Default: TCP.
	// Cameras with Access Authentication on need an SSH tunnel (see SSHDialer).
	DialContext func(ctx context.Context, network, addr string) (net.Conn, error)
	// Trace, if set, is called for every operation when it is queued, when it
	// starts on the wire and when it ends. It must not block.
	Trace func(TraceEvent)
}

// TraceEvent describes one step of an operation for Options.Trace.
type TraceEvent struct {
	Op     uint16
	Params []uint32
	Phase  string        // "queued", "start" or "end"
	Dur    time.Duration // start: time spent waiting for the connection; end: time on the wire
	Err    error         // end only
}

// InitFailError is returned when the camera refuses the PTP/IP handshake,
// e.g. because pairing/authentication is required.
type InitFailError struct{ Reason uint32 }

func (e *InitFailError) Error() string {
	return fmt.Sprintf("ptpip: camera refused connection (InitFail reason 0x%X)", e.Reason)
}

// Event is a PTP event received on the event channel.
type Event struct {
	Code          uint16
	TransactionID uint32
	Params        []uint32
}

// Client is a PTP/IP initiator connection (command + event channel).
type Client struct {
	opts Options
	addr string // camera host:port as dialled
	dial func(ctx context.Context, network, addr string) (net.Conn, error)
	cmd  net.Conn
	evt  net.Conn

	// Responder identity from the InitCommandAck.
	ConnNumber    uint32
	ResponderName string
	ResponderGUID [16]byte

	mu  sync.Mutex // one transaction at a time
	tid uint32

	events    chan Event
	done      chan struct{}
	closeOnce sync.Once
	errMu     sync.Mutex
	err       error
}

// Dial connects to a PTP/IP responder at addr ("host" or "host:port").
func Dial(ctx context.Context, addr string, opts Options) (*Client, error) {
	if _, _, err := net.SplitHostPort(addr); err != nil {
		addr = net.JoinHostPort(addr, fmt.Sprint(DefaultPort))
	}
	if opts.DialTimeout <= 0 {
		opts.DialTimeout = 10 * time.Second
	}
	if opts.IOTimeout <= 0 {
		opts.IOTimeout = 30 * time.Second
	}
	if opts.Name == "" {
		opts.Name = "alpha2go"
	}
	if opts.GUID == ([16]byte{}) {
		rand.Read(opts.GUID[:])
	}
	d := opts.DialContext
	if d == nil {
		nd := net.Dialer{Timeout: opts.DialTimeout, KeepAlive: 15 * time.Second}
		d = nd.DialContext
	}

	cmd, err := d(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	// The handshake reads use IOTimeout; also honour ctx (e.g. connect timeouts).
	var evtMu sync.Mutex
	var evtConn net.Conn
	stop := context.AfterFunc(ctx, func() {
		cmd.Close()
		evtMu.Lock()
		if evtConn != nil {
			evtConn.Close()
		}
		evtMu.Unlock()
	})
	defer stop()
	c := &Client{opts: opts, addr: addr, dial: d, cmd: cmd, events: make(chan Event, 64), done: make(chan struct{})}
	if err := c.initCommand(); err != nil {
		cmd.Close()
		return nil, err
	}
	evt, err := d(ctx, "tcp", addr)
	if err != nil {
		cmd.Close()
		return nil, err
	}
	evtMu.Lock()
	evtConn = evt
	evtMu.Unlock()
	c.evt = evt
	if err := c.initEvent(); err != nil {
		cmd.Close()
		evt.Close()
		return nil, err
	}
	if !stop() {
		cmd.Close()
		evt.Close()
		return nil, ctx.Err()
	}
	go c.eventLoop()
	return c, nil
}

func (c *Client) readCmd() (packet, error) {
	c.cmd.SetReadDeadline(time.Now().Add(c.opts.IOTimeout))
	p, err := readPacket(c.cmd)
	// The SSH channel emulates deadlines with a timer that closes the channel;
	// left armed it would kill an idle session IOTimeout after the last reply.
	c.cmd.SetReadDeadline(time.Time{})
	return p, err
}

func (c *Client) initCommand() error {
	payload := append([]byte(nil), c.opts.GUID[:]...)
	payload = append(payload, ucs2z(c.opts.Name)...)
	payload = binary.LittleEndian.AppendUint32(payload, 0x00010000) // version 1.0
	if err := writePacket(c.cmd, pktInitCommandRequest, payload); err != nil {
		return err
	}
	p, err := c.readCmd()
	if err != nil {
		return fmt.Errorf("ptpip: waiting for InitCommandAck: %w", err)
	}
	switch p.typ {
	case pktInitCommandAck:
		if len(p.payload) < 20 {
			return errors.New("ptpip: short InitCommandAck")
		}
		c.ConnNumber = binary.LittleEndian.Uint32(p.payload)
		copy(c.ResponderGUID[:], p.payload[4:20])
		c.ResponderName, _ = readUCS2Z(p.payload[20:])
		return nil
	case pktInitFail:
		var reason uint32
		if len(p.payload) >= 4 {
			reason = binary.LittleEndian.Uint32(p.payload)
		}
		return &InitFailError{Reason: reason}
	}
	return fmt.Errorf("ptpip: unexpected packet type %d during init", p.typ)
}

func (c *Client) initEvent() error {
	if err := writePacket(c.evt, pktInitEventRequest, binary.LittleEndian.AppendUint32(nil, c.ConnNumber)); err != nil {
		return err
	}
	c.evt.SetReadDeadline(time.Now().Add(c.opts.IOTimeout))
	p, err := readPacket(c.evt)
	c.evt.SetReadDeadline(time.Time{})
	if err != nil {
		return fmt.Errorf("ptpip: waiting for InitEventAck: %w", err)
	}
	switch p.typ {
	case pktInitEventAck:
		return nil
	case pktInitFail:
		var reason uint32
		if len(p.payload) >= 4 {
			reason = binary.LittleEndian.Uint32(p.payload)
		}
		return &InitFailError{Reason: reason}
	}
	return fmt.Errorf("ptpip: unexpected packet type %d on event channel", p.typ)
}

func (c *Client) eventLoop() {
	defer close(c.events)
	for {
		p, err := readPacket(c.evt)
		if err != nil {
			c.fail(fmt.Errorf("ptpip: event channel: %w", err))
			return
		}
		switch p.typ {
		case pktEvent:
			r := &reader{b: p.payload}
			ev := Event{Code: r.u16(), TransactionID: r.u32()}
			for len(p.payload)-r.off >= 4 {
				ev.Params = append(ev.Params, r.u32())
			}
			select {
			case c.events <- ev:
			default: // consumer too slow; events are only hints
			}
		case pktProbeRequest:
			writePacket(c.evt, pktProbeResponse, nil)
		}
	}
}

// Events delivers camera events. The channel closes when the connection ends.
func (c *Client) Events() <-chan Event { return c.events }

// Done is closed when the connection is broken or closed.
func (c *Client) Done() <-chan struct{} { return c.done }

// Err returns why the connection ended.
func (c *Client) Err() error {
	c.errMu.Lock()
	defer c.errMu.Unlock()
	return c.err
}

func (c *Client) fail(err error) {
	c.closeOnce.Do(func() {
		c.errMu.Lock()
		c.err = err
		c.errMu.Unlock()
		c.cmd.Close()
		if c.evt != nil {
			c.evt.Close()
		}
		close(c.done)
	})
}

// Close terminates the connection.
func (c *Client) Close() error {
	c.fail(errors.New("ptpip: closed"))
	return nil
}

// Transaction runs one PTP operation. If send is non-nil it is sent as the
// data phase; received data is written to recv (may be nil to discard).
// It returns the response parameters.
func (c *Client) Transaction(ctx context.Context, op uint16, params []uint32, send []byte, recv io.Writer) ([]uint32, error) {
	return c.transaction(ctx, op, params, send, recv, 0)
}

// transaction is Transaction; pace > 0 sends the data phase the way the camera
// expects for a settings restore: pauses between request, StartData, one
// Data packet and an EndData that carries only the transaction ID.
func (c *Client) transaction(ctx context.Context, op uint16, params []uint32, send []byte, recv io.Writer, pace time.Duration) (out []uint32, err error) {
	select {
	case <-c.done:
		return nil, c.Err()
	default:
	}
	tr := c.opts.Trace
	queued := time.Now()
	if tr != nil {
		tr(TraceEvent{Op: op, Params: params, Phase: "queued"})
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if tr != nil {
		started := time.Now()
		tr(TraceEvent{Op: op, Params: params, Phase: "start", Dur: started.Sub(queued)})
		defer func() { tr(TraceEvent{Op: op, Params: params, Phase: "end", Dur: time.Since(started), Err: err}) }()
	}

	// A cancelled context breaks the connection: PTP/IP has no reliable way
	// to abort an in-flight transfer, and the caller reconnects.
	stop := context.AfterFunc(ctx, func() { c.fail(ctx.Err()) })
	defer stop()

	var tid uint32
	if op == OpOpenSession {
		tid, c.tid = 0, 0
	} else {
		c.tid++
		tid = c.tid
	}
	dataphase := uint32(1)
	if send != nil {
		dataphase = 2
	}
	req := binary.LittleEndian.AppendUint32(nil, dataphase)
	req = binary.LittleEndian.AppendUint16(req, op)
	req = binary.LittleEndian.AppendUint32(req, tid)
	for _, p := range params {
		req = binary.LittleEndian.AppendUint32(req, p)
	}
	if err := writePacket(c.cmd, pktOperationRequest, req); err != nil {
		return nil, c.ioErr(ctx, err)
	}
	if send != nil {
		pause := func() error {
			if pace > 0 {
				return sleepCtx(ctx, pace)
			}
			return nil
		}
		if err := pause(); err != nil {
			return nil, c.ioErr(ctx, err)
		}
		start := binary.LittleEndian.AppendUint32(nil, tid)
		start = binary.LittleEndian.AppendUint64(start, uint64(len(send)))
		if err := writePacket(c.cmd, pktStartData, start); err != nil {
			return nil, c.ioErr(ctx, err)
		}
		end := append(binary.LittleEndian.AppendUint32(nil, tid), send...)
		if pace > 0 {
			if err := pause(); err != nil {
				return nil, c.ioErr(ctx, err)
			}
			if err := writePacket(c.cmd, pktData, end); err != nil {
				return nil, c.ioErr(ctx, err)
			}
			if err := pause(); err != nil {
				return nil, c.ioErr(ctx, err)
			}
			end = binary.LittleEndian.AppendUint32(nil, tid)
		}
		if err := writePacket(c.cmd, pktEndData, end); err != nil {
			return nil, c.ioErr(ctx, err)
		}
	}

	var received uint64
	for {
		p, err := c.readCmd()
		if err != nil {
			return nil, c.ioErr(ctx, err)
		}
		switch p.typ {
		case pktStartData:
			// total length (uint64, may be 0xFFFFFFFFFFFFFFFF when unknown)
		case pktData, pktEndData:
			if len(p.payload) < 4 {
				return nil, c.ioErr(ctx, errors.New("short data packet"))
			}
			chunk := p.payload[4:]
			received += uint64(len(chunk))
			if recv != nil {
				if _, err := recv.Write(chunk); err != nil {
					// Keep the connection in sync: drain the rest of the transaction.
					return c.drain(ctx, op, err)
				}
			}
		case pktOperationResponse:
			r := &reader{b: p.payload}
			code := r.u16()
			r.u32() // transaction id
			var out []uint32
			for len(p.payload)-r.off >= 4 {
				out = append(out, r.u32())
			}
			if code != RespOK {
				return out, &Error{Op: op, Code: code}
			}
			return out, nil
		case pktProbeRequest:
			writePacket(c.cmd, pktProbeResponse, nil)
		case pktCancel:
			return nil, &Error{Op: op, Code: 0x201F}
		default:
			return nil, c.ioErr(ctx, fmt.Errorf("unexpected packet type %d", p.typ))
		}
	}
}

// drain reads until the response of the current transaction and returns writeErr.
func (c *Client) drain(ctx context.Context, op uint16, writeErr error) ([]uint32, error) {
	for {
		p, err := c.readCmd()
		if err != nil {
			return nil, c.ioErr(ctx, err)
		}
		if p.typ == pktOperationResponse {
			return nil, fmt.Errorf("ptp %s: writing data: %w", OpName(op), writeErr)
		}
	}
}

func (c *Client) ioErr(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		err = ctx.Err()
	}
	c.fail(err)
	return err
}
