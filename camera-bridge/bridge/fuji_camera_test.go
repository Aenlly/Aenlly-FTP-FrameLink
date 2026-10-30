package bridge

// This independent TCP peer constructs protocol responses from published layouts.
// It is not an X-T30 II capture, firmware emulator or a real-camera acceptance test.
import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

type fujiPeer struct {
	listener    net.Listener
	mu          sync.Mutex
	connections map[net.Conn]bool
	wg          sync.WaitGroup
	files       map[uint32][]byte
	unsafe      bool
	drop        bool
	wrongState  bool
	changed     bool
	reads       int
	writes      []uint32
}

func newFujiPeer(t *testing.T) *fujiPeer {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &fujiPeer{listener: listener, connections: map[net.Conn]bool{}, files: map[uint32][]byte{1: bytes.Repeat([]byte("JPEG synthetic bytes"), 100), 2: append([]byte("FUJIFILMCCD-RAW "), bytes.Repeat([]byte{0x17, 0x82, 0x53}, fujiChunk)...), 3: []byte("second JPEG synthetic bytes")}}
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			p.mu.Lock()
			p.connections[conn] = true
			p.mu.Unlock()
			p.wg.Add(1)
			go func() {
				defer p.wg.Done()
				defer conn.Close()
				defer func() { p.mu.Lock(); delete(p.connections, conn); p.mu.Unlock() }()
				p.serve(conn)
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		p.mu.Lock()
		for conn := range p.connections {
			conn.Close()
		}
		p.mu.Unlock()
		p.wg.Wait()
	})
	return p
}

func (p *fujiPeer) config() string {
	_, port, _ := net.SplitHostPort(p.listener.Addr().String())
	number, _ := strconv.Atoi(port)
	b, _ := json.Marshal(map[string]any{"protocol": protocolFujiAutoSave, "experimentalFuji": true, "fujiDeviceName": "FUJIFILM-SYNTHETIC-0001", "host": "127.0.0.1", "port": number})
	return string(b)
}

// Deliberately independent encoder; no production fujiContainer/reader is used.
func peerPacket(kind, code uint16, tx uint32, payload []byte) []byte {
	b := binary.LittleEndian.AppendUint32(nil, uint32(len(payload)+12))
	b = binary.LittleEndian.AppendUint16(b, kind)
	b = binary.LittleEndian.AppendUint16(b, code)
	b = binary.LittleEndian.AppendUint32(b, tx)
	return append(b, payload...)
}
func peerRead(conn net.Conn) (uint16, uint16, uint32, []byte, error) {
	b := make([]byte, 12)
	_, err := io.ReadFull(conn, b)
	if err != nil {
		return 0, 0, 0, nil, err
	}
	size := binary.LittleEndian.Uint32(b)
	if size < 12 || size > fujiChunk+12 {
		return 0, 0, 0, nil, fmt.Errorf("invalid peer request size")
	}
	payload := make([]byte, int(size)-12)
	_, err = io.ReadFull(conn, payload)
	return binary.LittleEndian.Uint16(b[4:]), binary.LittleEndian.Uint16(b[6:]), binary.LittleEndian.Uint32(b[8:]), payload, err
}
func (p *fujiPeer) info(handle uint32) []byte {
	name, date, format := "DSCF0001.JPG", "20261023T120000Z", uint16(0x3801)
	if handle == 2 {
		name, format = "DSCF0001.RAF", 0xb103
	}
	if handle == 3 {
		name, date = "DSCF0002.JPG", "20261023T130000Z"
	}
	if p.changed && handle == 2 && p.reads > 0 {
		name = "CHANGED.RAF"
	}
	return ptpip.EncodeObjectInfo(&ptpip.ObjectInfo{StorageID: 1, ParentObject: 9, ObjectFormat: format, Filename: name, CompressedSize: uint32(len(p.files[handle])), CaptureDate: date, ModificationDate: date})
}

func (p *fujiPeer) serve(conn net.Conn) {
	conn.SetDeadline(time.Now().Add(10 * time.Second))
	init := make([]byte, 82)
	if _, err := io.ReadFull(conn, init); err != nil {
		return
	}
	if binary.LittleEndian.Uint32(init) != 82 || binary.LittleEndian.Uint32(init[8:]) != 0x8f53e4f2 {
		return
	}
	ack := make([]byte, 28)
	binary.LittleEndian.PutUint32(ack[4:], 2)
	for _, c := range "FUJI-SYNTHETIC" {
		ack = binary.LittleEndian.AppendUint16(ack, uint16(c))
	}
	ack = append(ack, 0, 0)
	binary.LittleEndian.PutUint32(ack, uint32(len(ack)))
	// Exercise fragmented TCP headers and payloads, not one Read per packet.
	if _, err := conn.Write(ack[:3]); err != nil {
		return
	}
	if _, err := conn.Write(ack[3:]); err != nil {
		return
	}
	for {
		kind, op, tx, params, err := peerRead(conn)
		if err != nil {
			return
		}
		if kind != 1 {
			return
		}
		var data []byte
		response := uint16(0x2001)
		param := func(i int) uint32 {
			if len(params) < (i+1)*4 {
				return 0
			}
			return binary.LittleEndian.Uint32(params[i*4:])
		}
		p.mu.Lock()
		switch op {
		case 0x1002:
			if tx != 1 || param(0) != 1 {
				response = 0x201d
			}
		case 0x1001:
			data = ptpip.EncodeDeviceInfo(&ptpip.DeviceInfo{Manufacturer: "Fujifilm", Model: "FUJI-SYNTHETIC", DeviceVersion: "fixture-only", SerialNumber: "SYNTHETIC-NOT-HARDWARE", Operations: []uint16{0x1008, 0x101b}})
		case fujiGetProperty:
			if param(0) == fujiEvents {
				data = binary.LittleEndian.AppendUint16(nil, 2)
				data = binary.LittleEndian.AppendUint16(data, fujiCameraState)
				state := uint32(3)
				if p.wrongState {
					state = 1
				}
				data = binary.LittleEndian.AppendUint32(data, state)
				data = binary.LittleEndian.AppendUint16(data, fujiObjectCount)
				data = binary.LittleEndian.AppendUint32(data, 3)
			} else {
				data = binary.LittleEndian.AppendUint32(nil, 0x20001)
			}
		case fujiSetProperty:
			code := param(0)
			p.writes = append(p.writes, code)
			if code != fujiClientState && code != fujiAutoSaveVersion && code != fujiCorrectFileSize {
				p.unsafe = true
			}
			p.mu.Unlock()
			dataKind, dataCode, dataTx, payload, e := peerRead(conn)
			p.mu.Lock()
			if e != nil || dataKind != 2 || dataCode != op || dataTx != tx {
				p.mu.Unlock()
				return
			}
			if code == fujiClientState && !bytes.Equal(payload, []byte{3, 0}) || code == fujiCorrectFileSize && !bytes.Equal(payload, []byte{1, 0}) {
				p.unsafe = true
			}
		case 0x1008:
			if _, ok := p.files[param(0)]; ok {
				data = p.info(param(0))
			} else {
				response = 0x2009
			}
		case 0x101b:
			file, ok := p.files[param(0)]
			offset, size := uint64(param(1)), uint64(param(2))
			p.reads++
			if !ok || size > fujiChunk || offset+size > uint64(len(file)) {
				p.unsafe = true
				response = 0x201d
			} else {
				data = append([]byte(nil), file[offset:offset+size]...)
			}
		default:
			p.unsafe = true
			response = 0x2005
		}
		drop := p.drop && op == 0x101b
		p.mu.Unlock()
		if data != nil {
			packet := peerPacket(2, op, tx, data)
			if drop {
				conn.Write(packet[:len(packet)/2])
				return
			}
			if _, err = conn.Write(packet); err != nil {
				return
			}
		}
		if _, err = conn.Write(peerPacket(3, response, tx, nil)); err != nil {
			return
		}
	}
}

func fujiList(t *testing.T, s *Session) []map[string]any {
	t.Helper()
	if _, err := s.Dates(1); err != nil {
		t.Fatal(err)
	}
	raw, err := s.ListNewestPage(1, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	var page struct {
		Items []map[string]any `json:"items"`
		Next  int64            `json:"nextCursor"`
		Scope string           `json:"mediaScope"`
	}
	if err = json.Unmarshal([]byte(raw), &page); err != nil {
		t.Fatal(err)
	}
	if page.Scope != "AUTOSAVE_PENDING_BATCH" || page.Next != 0 || len(page.Items) != 2 {
		t.Fatalf("unexpected pending batch: %s", raw)
	}
	return page.Items
}
func fujiRequest(t *testing.T, row map[string]any, index int) string {
	t.Helper()
	files := row["files"].([]any)
	file := files[index].(map[string]any)
	b, err := json.Marshal(map[string]any{"slot": 1, "contentId": row["contentId"], "fileId": file["fileId"], "size": file["size"]})
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestFujiAutoSaveReadOnlyBatchAndOriginalBytes(t *testing.T) {
	p := newFujiPeer(t)
	s := NewSession()
	defer s.Close()
	raw, err := s.Connect(p.config())
	if err != nil {
		t.Fatal(err)
	}
	var info map[string]any
	json.Unmarshal([]byte(raw), &info)
	if info["canPreview"] != false || info["canCapture"] != false || info["hardwareVerified"] != false || info["mediaScope"] != "AUTOSAVE_PENDING_BATCH" {
		t.Fatal(raw)
	}
	rows := fujiList(t, s)
	if len(rows[0]["files"].([]any)) != 1 || len(rows[1]["files"].([]any)) != 2 {
		t.Fatal("JPEG/RAF grouping or newest-first failed")
	}
	for i, handle := range []uint32{1, 2} {
		target := filepath.Join(t.TempDir(), "original")
		receipt, err := s.Download(fujiRequest(t, rows[1], i), target, nil)
		if err != nil {
			t.Fatal(err)
		}
		data, _ := os.ReadFile(target)
		if !bytes.Equal(data, p.files[handle]) {
			t.Fatal("original bytes changed")
		}
		sum := sha256.Sum256(data)
		if !bytes.Contains([]byte(receipt), []byte(hex.EncodeToString(sum[:]))) {
			t.Fatal("missing actual file hash")
		}
	}
	if err = s.Preview(1, int(rows[1]["contentId"].(float64)), 1, filepath.Join(t.TempDir(), "preview"), false); err == nil {
		t.Fatal("unsafe preview enabled")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.unsafe || len(p.writes) != 3 {
		t.Fatalf("unexpected operations: %+v", p.writes)
	}
}

type fujiProgress func(int64, int64)

func (f fujiProgress) OnProgress(done, total int64) { f(done, total) }
func TestFujiAutoSaveCancellationDisconnectAndExistingFileProtection(t *testing.T) {
	for _, mode := range []string{"cancel", "drop", "changed", "existing"} {
		t.Run(mode, func(t *testing.T) {
			p := newFujiPeer(t)
			s := NewSession()
			defer s.Close()
			if _, err := s.Connect(p.config()); err != nil {
				t.Fatal(err)
			}
			rows := fujiList(t, s)
			target := filepath.Join(t.TempDir(), "RAW.RAF")
			var progress Progress
			switch mode {
			case "cancel":
				progress = fujiProgress(func(done, total int64) { s.CancelTransfer() })
			case "drop":
				p.mu.Lock()
				p.drop = true
				p.mu.Unlock()
			case "changed":
				p.mu.Lock()
				p.changed = true
				p.mu.Unlock()
			case "existing":
				os.WriteFile(target, []byte("KEEP"), 0600)
			}
			if _, err := s.Download(fujiRequest(t, rows[1], 1), target, progress); err == nil {
				t.Fatal("expected incomplete/replaced/cancelled resource refusal")
			}
			if mode == "existing" {
				data, _ := os.ReadFile(target)
				if string(data) != "KEEP" {
					t.Fatal("existing original overwritten")
				}
			} else if _, err := os.Stat(target); !os.IsNotExist(err) {
				t.Fatal("unfinished original published")
			}
			partials, _ := filepath.Glob(target + "-*.part")
			if len(partials) > 0 {
				t.Fatal("temporary file leaked")
			}
			if mode == "cancel" || mode == "drop" {
				if s.Connected() {
					t.Fatal("partial session remained usable")
				}
			}
		})
	}
}

func TestFujiAutoSaveRequiresExplicitOptInAndCorrectMode(t *testing.T) {
	if _, err := decodeConfig(`{"protocol":"fuji-autosave","host":"127.0.0.1","fujiDeviceName":"fixture"}`); err == nil {
		t.Fatal("experimental protocol enabled by default")
	}
	p := newFujiPeer(t)
	p.mu.Lock()
	p.wrongState = true
	p.mu.Unlock()
	s := NewSession()
	defer s.Close()
	if _, err := s.Connect(p.config()); err == nil {
		t.Fatal("non-AutoSave mode accepted")
	}
	if s.Connected() {
		t.Fatal("failed setup remains connected")
	}
}

func TestFujiPublishRefusesExistingTargetAtCommit(t *testing.T) {
	dir := t.TempDir()
	source, target := filepath.Join(dir, "complete.part"), filepath.Join(dir, "original.RAF")
	os.WriteFile(source, []byte("NEW"), 0600)
	os.WriteFile(target, []byte("KEEP"), 0600)
	if err := fujiPublishOriginal(source, target); err == nil {
		t.Fatal("atomic publish replaced original")
	}
	data, _ := os.ReadFile(target)
	if string(data) != "KEEP" {
		t.Fatal("existing original changed")
	}
	data, _ = os.ReadFile(source)
	if string(data) != "NEW" {
		t.Fatal("failed publish consumed source")
	}
}

func TestFujiWireRejectsMalformedDataBeforeAllocatingOrPublishing(t *testing.T) {
	for _, failure := range []string{"oversized", "transaction", "data-order"} {
		t.Run(failure, func(t *testing.T) {
			client, peer := net.Pipe()
			wire := newFujiWire(client)
			defer wire.close()
			done := make(chan struct{})
			go func() {
				defer close(done)
				defer peer.Close()
				_, op, tx, _, err := peerRead(peer)
				if err != nil {
					return
				}
				header := peerPacket(2, op, tx, nil)
				if failure == "oversized" {
					binary.LittleEndian.PutUint32(header, 0xffffffff)
				}
				if failure == "transaction" {
					binary.LittleEndian.PutUint32(header[8:], tx+1)
				}
				peer.Write(header)
				if failure == "data-order" {
					peer.Write(header)
				}
			}()
			if _, err := wire.call(context.Background(), 0x101b, nil, nil, 4); err == nil {
				t.Fatal("malformed packet accepted")
			}
			if wire.online() {
				t.Fatal("desynchronised socket kept open")
			}
			<-done
		})
	}
}
