package bridge

// Fuji PC AutoSave uses USB-style PTP containers on TCP, not Sony PTP/IP.
// Protocol sources and independent implementation boundaries: docs/FUJIFILM_WIRELESS_RESEARCH.md.
import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
	"unicode/utf16"
)

const (
	fujiCommandPort     = 55740
	fujiChunk           = 1 << 20
	fujiMetadataLimit   = 64 << 10
	fujiGetProperty     = 0x1015
	fujiSetProperty     = 0x1016
	fujiEvents          = 0xd212
	fujiCameraState     = 0xdf00
	fujiClientState     = 0xdf01
	fujiObjectCount     = 0xd222
	fujiAutoSaveVersion = 0xdf23
	fujiCorrectFileSize = 0xd227
)

type fujiResponseError uint16

func (e fujiResponseError) Error() string { return fmt.Sprintf("Fuji返回PTP状态0x%04x", uint16(e)) }

type fujiWire struct {
	conn   net.Conn
	mu     sync.Mutex
	tx     uint32
	once   sync.Once
	closed chan struct{}
}

func newFujiWire(conn net.Conn) *fujiWire {
	return &fujiWire{conn: conn, tx: 1, closed: make(chan struct{})}
}
func (w *fujiWire) close() { w.once.Do(func() { close(w.closed); w.conn.Close() }) }
func (w *fujiWire) online() bool {
	select {
	case <-w.closed:
		return false
	default:
		return true
	}
}

func fujiWriteAll(w io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := w.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}

func fujiInit(name string) ([]byte, error) {
	if !fujiImporterName(name) {
		return nil, errors.New("AutoSave接收名称需为1至24个ASCII字母、数字、空格或短横线")
	}
	b := make([]byte, 82)
	for i, value := range []uint32{82, 1, 0x8f53e4f2, 0x5d48a5ad, 0x0b7fb287, 0xd0ded5d3, 0} {
		binary.LittleEndian.PutUint32(b[i*4:], value)
	}
	for i, c := range []byte(name) {
		binary.LittleEndian.PutUint16(b[28+i*2:], uint16(c))
	}
	return b, nil
}

func fujiImporterName(name string) bool {
	if len(name) < 1 || len(name) > 24 {
		return false
	}
	for _, c := range []byte(name) {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == ' ' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

func (w *fujiWire) deadline(ctx context.Context) func() {
	deadline := time.Now().Add(15 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	w.conn.SetDeadline(deadline)
	stop := context.AfterFunc(ctx, w.close)
	return func() { stop() }
}

func (w *fujiWire) init(ctx context.Context, name string) (model string, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	defer w.deadline(ctx)()
	defer func() {
		if err != nil {
			w.close()
		}
	}()
	request, err := fujiInit(name)
	if err != nil {
		return "", err
	}
	if err = fujiWriteAll(w.conn, request); err != nil {
		return "", err
	}
	var head [8]byte
	if _, err = io.ReadFull(w.conn, head[:]); err != nil {
		return "", err
	}
	size, kind := binary.LittleEndian.Uint32(head[:4]), binary.LittleEndian.Uint32(head[4:])
	if size < 8 || size > fujiMetadataLimit {
		return "", errors.New("Fuji初始化响应长度无效")
	}
	b := make([]byte, int(size)-8)
	if _, err = io.ReadFull(w.conn, b); err != nil {
		return "", err
	}
	if kind != 2 || len(b) < 20 {
		return "", errors.New("Fuji未接受AutoSave初始化")
	}
	// libfuji parses four words after the 12-byte bulk header, then UTF-16LE model.
	if len(b[20:])%2 != 0 {
		return "", errors.New("Fuji初始化名称编码无效")
	}
	var chars []uint16
	terminated := false
	for i := 20; i+1 < len(b); i += 2 {
		v := binary.LittleEndian.Uint16(b[i:])
		if v == 0 {
			terminated = true
			break
		}
		chars = append(chars, v)
	}
	if !terminated || len(chars) == 0 {
		return "", errors.New("Fuji初始化未返回机身名称")
	}
	return string(utf16.Decode(chars)), nil
}

func fujiContainer(kind, code uint16, tx uint32, data []byte) []byte {
	b := make([]byte, 12, len(data)+12)
	binary.LittleEndian.PutUint32(b, uint32(len(data)+12))
	binary.LittleEndian.PutUint16(b[4:], kind)
	binary.LittleEndian.PutUint16(b[6:], code)
	binary.LittleEndian.PutUint32(b[8:], tx)
	return append(b, data...)
}

func (w *fujiWire) call(ctx context.Context, code uint16, params []uint32, send []byte, limit int) (data []byte, err error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.online() {
		return nil, errors.New("AutoSave会话已结束，请在相机重新发起传输")
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	if len(params) > 5 || limit < 0 || limit > fujiChunk {
		return nil, errors.New("AutoSave命令读取范围无效")
	}
	defer w.deadline(ctx)()
	// A partial or cancelled response cannot safely be followed by another command.
	defer func() {
		var response fujiResponseError
		if err != nil && !errors.As(err, &response) {
			w.close()
		}
	}()
	tx := w.tx
	w.tx++
	var p []byte
	for _, v := range params {
		p = binary.LittleEndian.AppendUint32(p, v)
	}
	if err = fujiWriteAll(w.conn, fujiContainer(1, code, tx, p)); err != nil {
		return nil, err
	}
	if send != nil {
		if err = fujiWriteAll(w.conn, fujiContainer(2, code, tx, send)); err != nil {
			return nil, err
		}
	}
	seenData := false
	for packets := 0; packets < 2; packets++ {
		var head [12]byte
		if _, err = io.ReadFull(w.conn, head[:]); err != nil {
			return nil, err
		}
		size := binary.LittleEndian.Uint32(head[:4])
		kind := binary.LittleEndian.Uint16(head[4:])
		reply := binary.LittleEndian.Uint16(head[6:])
		if size < 12 || binary.LittleEndian.Uint32(head[8:]) != tx {
			return nil, errors.New("AutoSave容器长度或事务编号不匹配")
		}
		bound := limit
		if kind == 3 {
			bound = 20
		}
		if size-12 > uint32(bound) {
			return nil, errors.New("AutoSave响应超过命令读取上限")
		}
		payload := make([]byte, int(size)-12)
		if _, err = io.ReadFull(w.conn, payload); err != nil {
			return nil, err
		}
		switch kind {
		case 2:
			if seenData || reply != code || send != nil {
				return nil, errors.New("AutoSave数据响应顺序无效")
			}
			data = payload
			seenData = true
		case 3:
			if reply != 0x2001 {
				return nil, fujiResponseError(reply)
			}
			return data, nil
		default:
			return nil, errors.New("AutoSave返回未知容器类型")
		}
	}
	return nil, errors.New("AutoSave数据缺少完成响应")
}

func (w *fujiWire) property(ctx context.Context, code uint16) ([]byte, error) {
	return w.call(ctx, fujiGetProperty, []uint32{uint32(code)}, nil, fujiMetadataLimit)
}
func (w *fujiWire) set16(ctx context.Context, code uint16, value uint16) error {
	_, err := w.call(ctx, fujiSetProperty, []uint32{uint32(code)}, binary.LittleEndian.AppendUint16(nil, value), 0)
	return err
}

// fujiReader bounds every array/string before allocation, including malformed metadata.
type fujiReader struct {
	b   []byte
	err error
}

func (r *fujiReader) take(n int) []byte {
	if n < 0 || n > len(r.b) {
		r.err = io.ErrUnexpectedEOF
		return make([]byte, max(0, min(n, 56)))
	}
	b := r.b[:n]
	r.b = r.b[n:]
	return b
}
func (r *fujiReader) u16() uint16 { return binary.LittleEndian.Uint16(r.take(2)) }
func (r *fujiReader) u32() uint32 { return binary.LittleEndian.Uint32(r.take(4)) }
func (r *fujiReader) str() string {
	if len(r.b) == 0 {
		r.err = io.ErrUnexpectedEOF
		return ""
	}
	n := int(r.b[0])
	r.b = r.b[1:]
	if n == 0 {
		return ""
	}
	if n > len(r.b)/2 {
		r.err = io.ErrUnexpectedEOF
		return ""
	}
	u := make([]uint16, n)
	for i := range u {
		u[i] = r.u16()
	}
	if u[n-1] != 0 {
		r.err = errors.New("PTP字符串缺少终止符")
		return ""
	}
	return string(utf16.Decode(u[:n-1]))
}
func (r *fujiReader) array() []uint16 {
	n := r.u32()
	if n > 4096 || int(n) > len(r.b)/2 {
		r.err = errors.New("PTP能力数组长度无效")
		return nil
	}
	a := make([]uint16, int(n))
	for i := range a {
		a[i] = r.u16()
	}
	return a
}
