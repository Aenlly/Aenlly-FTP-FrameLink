package bridge

// 独立手机协议端点，不复用AutoSave服务状态，也不是X-T30 II实机协议抓包
import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
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

type phonePeer struct {
	listener                                                     net.Listener
	mu                                                           sync.Mutex
	wg                                                           sync.WaitGroup
	connections                                                  map[net.Conn]bool
	xapp, withRAF, wrongState, drop, noSerial, changed, zeroXApp bool
	unsafe                                                       bool
	partialReads                                                 int
	files                                                        map[uint32][]byte
	thumb                                                        []byte
	client                                                       uint32
}

func newPhonePeer(t *testing.T, xapp, raw bool) *phonePeer {
	t.Helper()
	l, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var thumb bytes.Buffer
	image := image.NewRGBA(image.Rect(0, 0, 96, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 96; x++ {
			image.Set(x, y, color.RGBA{40, 110, 160, 255})
		}
	}
	if err = jpeg.Encode(&thumb, image, nil); err != nil {
		t.Fatal(err)
	}
	p := &phonePeer{listener: l, connections: map[net.Conn]bool{}, xapp: xapp, withRAF: raw, thumb: thumb.Bytes(), files: map[uint32][]byte{1: thumb.Bytes(), 2: thumb.Bytes()}}
	if raw {
		p.files[2] = append([]byte("FUJIFILMCCD-RAW "), bytes.Repeat([]byte{7, 9, 13}, (fujiChunk/3)+123)...)
		p.files[3] = thumb.Bytes()
	}
	p.wg.Add(1)
	go func() {
		defer p.wg.Done()
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			p.mu.Lock()
			p.connections[c] = true
			p.mu.Unlock()
			p.wg.Add(1)
			go func() {
				defer p.wg.Done()
				defer c.Close()
				defer func() { p.mu.Lock(); delete(p.connections, c); p.mu.Unlock() }()
				p.serve(c)
			}()
		}
	}()
	t.Cleanup(func() {
		l.Close()
		p.mu.Lock()
		for c := range p.connections {
			c.Close()
		}
		p.mu.Unlock()
		p.wg.Wait()
	})
	return p
}

func (p *phonePeer) config() string {
	_, port, _ := net.SplitHostPort(p.listener.Addr().String())
	n, _ := strconv.Atoi(port)
	b, _ := json.Marshal(map[string]any{"protocol": "fuji-phone", "host": "127.0.0.1", "port": n})
	return string(b)
}

func (p *phonePeer) object(handle uint32, original bool) []byte {
	name, date, format := fmt.Sprintf("DSCF%04d.JPG", handle), "20261023T130000Z", uint16(0x3801)
	if p.withRAF && handle == 2 {
		name, date, format = "DSCF0001.RAF", "20261023T130000Z", 0xb103
	}
	if handle == 1 {
		name = "DSCF0001.JPG"
	}
	if handle == uint32(len(p.files)) {
		date = "20261023T120000Z"
	}
	if p.changed && p.partialReads > 0 {
		name = "CHANGED.JPG"
	}
	size := len(p.files[handle])
	if !original {
		size /= 2
	}
	return ptpip.EncodeObjectInfo(&ptpip.ObjectInfo{StorageID: 1, ParentObject: 3, ObjectFormat: format, Filename: name, CompressedSize: uint32(size), CaptureDate: date, ModificationDate: date})
}

func (p *phonePeer) serve(conn net.Conn) {
	conn.SetDeadline(time.Now().Add(15 * time.Second))
	init := make([]byte, 82)
	if _, err := io.ReadFull(conn, init); err != nil {
		return
	}
	if binary.LittleEndian.Uint32(init) != 82 || binary.LittleEndian.Uint32(init[8:]) != 0x8f53e4f2 {
		return
	}
	ack := make([]byte, 28)
	binary.LittleEndian.PutUint32(ack[4:], 2)
	for _, ch := range "X-T30 II" {
		ack = binary.LittleEndian.AppendUint16(ack, uint16(ch))
	}
	ack = append(ack, 0, 0)
	binary.LittleEndian.PutUint32(ack, uint32(len(ack)))
	conn.Write(ack[:5])
	conn.Write(ack[5:])
	correct, compress := false, uint32(0)
	for {
		kind, op, tx, params, err := peerRead(conn)
		if err != nil || kind != 1 {
			return
		}
		param := func(i int) uint32 {
			if len(params) < (i+1)*4 {
				return 0
			}
			return binary.LittleEndian.Uint32(params[i*4:])
		}
		response := uint16(0x2001)
		var data []byte
		p.mu.Lock()
		switch op {
		case 0x1002:
			if tx != 1 || param(0) != 1 {
				response = 0x201d
			}
		case 0x1001:
			serial := "PHONE-SYNTHETIC"
			if p.noSerial {
				serial = ""
			}
			data = ptpip.EncodeDeviceInfo(&ptpip.DeviceInfo{Manufacturer: "Fujifilm", Model: "X-T30 II", DeviceVersion: "fixture-only", SerialNumber: serial, Operations: []uint16{0x1008, 0x100a, 0x101b}})
		case 0x1015:
			switch code := param(0); code {
			case 0xd212:
				state := uint32(2)
				if p.wrongState {
					state = 3
				}
				data = binary.LittleEndian.AppendUint16(nil, 2)
				data = binary.LittleEndian.AppendUint16(data, 0xdf00)
				data = binary.LittleEndian.AppendUint32(data, state)
				data = binary.LittleEndian.AppendUint16(data, 0xd222)
				data = binary.LittleEndian.AppendUint32(data, uint32(len(p.files)))
			case 0xdf22, 0xdf21, 0xdf24:
				data = binary.LittleEndian.AppendUint32(nil, 0x2000c)
			case 0xdf25:
				data = binary.LittleEndian.AppendUint32(nil, 5)
			case 0xdf28:
				if p.xapp {
					data = binary.LittleEndian.AppendUint32(nil, 1)
				} else if p.zeroXApp {
					data = binary.LittleEndian.AppendUint32(nil, 0)
				} else {
					response = 0x200a
				}
			case 0xd620, 0xd621:
				if p.xapp {
					data = binary.LittleEndian.AppendUint32(nil, uint32(len(p.files)))
				} else {
					p.unsafe = true
					response = 0x200a
				}
			default:
				p.unsafe = true
				response = 0x200a
			}
		case 0x1016:
			code := param(0)
			p.mu.Unlock()
			dataKind, dataOp, dataTx, payload, e := peerRead(conn)
			p.mu.Lock()
			if e != nil || dataKind != 2 || dataOp != op || dataTx != tx || len(payload) != 2 && len(payload) != 4 {
				p.mu.Unlock()
				return
			}
			var value uint32
			if len(payload) == 2 {
				value = uint32(binary.LittleEndian.Uint16(payload))
			} else {
				value = binary.LittleEndian.Uint32(payload)
			}
			switch code {
			case 0xdf01:
				p.client = value
				if p.xapp && value != 20 || !p.xapp && value != 11 {
					p.unsafe = true
				}
			case 0xdf25:
				if p.xapp || value != 5 {
					p.unsafe = true
				}
			case 0xdf28:
				if !p.xapp || value != 1 {
					p.unsafe = true
				}
			case 0xd227:
				if value > 1 {
					p.unsafe = true
				}
				correct = value == 1
			case 0xd226:
				if !p.xapp || value != 0 && value != 2 {
					p.unsafe = true
				}
				compress = value
			default:
				p.unsafe = true
				response = 0x200a
			}
		case 0x9054, 0x9055, 0x9050, 0x9053:
			if !p.xapp {
				p.unsafe = true
				response = 0x2005
			} else if op == 0x9055 {
				data = p.thumb
			} else {
				data = []byte{0, 0, 0, 0}
			}
		case 0x1008:
			if _, ok := p.files[param(0)]; ok {
				data = p.object(param(0), correct || p.xapp && compress == 2)
			} else {
				response = 0x2009
			}
		case 0x100a:
			if _, ok := p.files[param(0)]; ok {
				data = p.thumb
			} else {
				response = 0x2009
			}
		case 0x101b:
			file, ok := p.files[param(0)]
			offset, size := uint64(param(1)), uint64(param(2))
			p.partialReads++
			if !ok || offset+size > uint64(len(file)) || size > fujiChunk || !correct && !(p.xapp && compress == 2) {
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

func phoneRows(t *testing.T, s *Session) []map[string]any {
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
		Scope string           `json:"mediaScope"`
		Total int              `json:"totalCount"`
	}
	if err = json.Unmarshal([]byte(raw), &page); err != nil {
		t.Fatal(err)
	}
	if page.Scope != "FUJI_PHONE_EXPOSED_MEDIA" || page.Total != len(page.Items) {
		t.Fatal(raw)
	}
	return page.Items
}

func TestFujiPhoneModesBrowsePreviewAndActualAdvertisedOriginalBytes(t *testing.T) {
	for _, xapp := range []bool{false, true} {
		for _, raw := range []bool{false, true} {
			t.Run(fmt.Sprintf("xapp=%v/raw=%v", xapp, raw), func(t *testing.T) {
				p := newPhonePeer(t, xapp, raw)
				s := NewSession()
				defer s.Close()
				info, err := s.Connect(p.config())
				if err != nil {
					t.Fatal(err)
				}
				var capabilities map[string]any
				json.Unmarshal([]byte(info), &capabilities)
				if capabilities["protocol"] != protocolFujiPhone || capabilities["canPreview"] != true || capabilities["canCapture"] != false || capabilities["hardwareVerified"] != false {
					t.Fatal(info)
				}
				rows := phoneRows(t, s)
				files := rows[0]["files"].([]any)
				if raw && len(files) != 2 || !raw && len(files) != 1 {
					t.Fatal("advertised formats changed", rows)
				}
				preview := filepath.Join(t.TempDir(), "preview.jpg")
				if err = s.Preview(1, int(rows[0]["contentId"].(float64)), 1, preview, true); err != nil {
					t.Fatal(err)
				}
				b, _ := os.ReadFile(preview)
				if !bytes.Equal(b, p.thumb) {
					t.Fatal("preview bytes changed")
				}
				p.mu.Lock()
				reads := p.partialReads
				p.mu.Unlock()
				if reads != 0 {
					t.Fatal("thumbnail secretly downloaded an original")
				}
				for index, file := range files {
					target := filepath.Join(t.TempDir(), "original")
					if _, err = s.Download(fujiRequest(t, rows[0], index), target, nil); err != nil {
						t.Fatal(err)
					}
					name := file.(map[string]any)["name"].(string)
					handle := uint32(1)
					if filepath.Ext(name) == ".RAF" {
						handle = 2
					}
					actual, _ := os.ReadFile(target)
					if !bytes.Equal(actual, p.files[handle]) {
						t.Fatal("original bytes changed", name)
					}
				}
				if err = s.SetProperty(0xd001, 1); err == nil {
					t.Fatal("read-only mode allowed a camera setting")
				}
				p.mu.Lock()
				unsafe := p.unsafe
				client := p.client
				p.mu.Unlock()
				if unsafe || client == 3 {
					t.Fatal("phone route used an unrelated protocol command")
				}
			})
		}
	}
}

func TestFujiPhoneRejectsAutoSaveAndIncompleteFilesDoNotPublish(t *testing.T) {
	p := newPhonePeer(t, false, true)
	s := NewSession()
	defer s.Close()
	p.mu.Lock()
	p.wrongState = true
	p.mu.Unlock()
	if _, err := s.Connect(p.config()); err == nil {
		t.Fatal("AutoSave accepted as phone gallery")
	}
	p.mu.Lock()
	p.wrongState = false
	p.drop = true
	p.mu.Unlock()
	if _, err := s.Connect(p.config()); err != nil {
		t.Fatal(err)
	}
	rows := phoneRows(t, s)
	target := filepath.Join(t.TempDir(), "original.RAF")
	if _, err := s.Download(fujiRequest(t, rows[0], 1), target, nil); err == nil {
		t.Fatal("truncated transfer accepted")
	}
	if _, err := os.Stat(target); !os.IsNotExist(err) {
		t.Fatal("partial original published")
	}
}

func TestFujiPhoneMissingSerialRejectsResourcesFromPreviousSession(t *testing.T) {
	p := newPhonePeer(t, true, false)
	p.mu.Lock()
	p.noSerial = true
	p.mu.Unlock()
	s := NewSession()
	defer s.Close()
	first, err := s.Connect(p.config())
	if err != nil {
		t.Fatal(err)
	}
	rows := phoneRows(t, s)
	second, err := s.Connect(p.config())
	if err != nil {
		t.Fatal(err)
	}
	next := phoneRows(t, s)
	if first == second || rows[0]["id"] == next[0]["id"] {
		t.Fatal("unidentified phone sessions shared resource identity")
	}
	if _, err = s.Download(fujiRequest(t, rows[0], 0), filepath.Join(t.TempDir(), "late.jpg"), nil); err == nil {
		t.Fatal("old session resource accepted")
	}
}

func TestFujiPhoneEmptyGalleryAndZeroVersionDoNotInventCapabilities(t *testing.T) {
	for _, xapp := range []bool{false, true} {
		p := newPhonePeer(t, xapp, false)
		p.mu.Lock()
		p.files = map[uint32][]byte{}
		p.zeroXApp = !xapp
		p.mu.Unlock()
		s := NewSession()
		if _, err := s.Connect(p.config()); err != nil {
			t.Fatal(err)
		}
		if rows := phoneRows(t, s); len(rows) != 0 {
			t.Fatal("empty camera exposed photos")
		}
		s.Close()
		p.mu.Lock()
		unsafe := p.unsafe
		client := p.client
		p.mu.Unlock()
		if unsafe || !xapp && client != 11 {
			t.Fatal("zero version selected an unsupported mode")
		}
	}
}
