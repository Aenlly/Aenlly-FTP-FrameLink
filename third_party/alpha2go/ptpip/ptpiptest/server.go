// Package ptpiptest is a fake PTP/IP responder ("camera") serving a local
// directory, for tests of the PTP/IP client. <Dir>/slot1 and
// <Dir>/slot2 are storages; otherwise <Dir> is the
// only storage.
package ptpiptest

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

// Options control the fake camera.
type Options struct {
	Dir          string
	Sony         bool   // advertise and accept the Sony SDIO operations
	RefuseInit   bool   // answer InitCommandRequest with InitFail (auth required)
	NoPartial    bool   // do not support GetPartialObject
	DropAfter    int64  // close the command connection once after serving this many object bytes (0 = never)
	ChunkSize    int    // data packet size (default 64 KiB)
	DateSuffix   string // appended to capture dates, e.g. "Z" or "+0200"; empty = no zone
	A6700        bool   // emulate a6700 remote mode (implies Sony): storage ops fail until 0x9212(2,1,0); no GetPartialObject; 0x9211 instead; empty ModificationDate
	IgnoreAfter  bool   // 0x923C always returns the first page (broken paging cursor)
	NoRemote     bool   // A6700 without 0x923B-0x923D (legacy cameras: only ContentsTransfer mode)
	CardBusy     bool   // 0x923B-0x923E answer OperationNotSupported (camera refuses card access right now)
	Model        string // default "ILCE-6700"
	SerialNumber string
	Props        []ptpip.SonyProp // Sony extended properties (0x9209); default: a few small ones
	Preview      []byte // optional synthetic JPEG for application UI acceptance
	FailControl  uint16           // 0x9207 for this code answers GeneralError when the value is 2 (pressed)
}

// Server is a running fake camera.
type Server struct {
	opts Options
	ln   net.Listener

	mu       sync.Mutex
	nextConn uint32
	evtConns map[uint32]net.Conn
	handles  map[string]uint32 // relative path (with storage prefix) -> handle
	nextH    uint32
	conns    []net.Conn

	served  atomic.Int64
	dropped atomic.Bool
	Ops     atomic.Int64 // operations handled (for assertions)

	// Xfer9212 counts SDIO_SetContentsTransferMode calls: on a real camera
	// they lock its UI, so clients using the remote mode must leave this at 0.
	Xfer9212 atomic.Int64

	// OSD9238 counts GetOSDImage (0x9238) calls; with the OSD mode off the
	// real camera answers with garbage, so clients must not send it then.
	OSD9238 atomic.Int64

	props      []ptpip.SonyProp
	contentIDs map[string]uint32 // storage+stem -> content ID
	requests   []ContentRequest
	controls   []ControlCall
	timeZone   []byte // payload of the last 0x9249
	deletes    []DeleteCall
	uploads    []UploadCall
	restores   [][]byte
	captured   [][]byte // images queued for the host by the shutter (handle 0xFFFFC001)
}

// CompressedData is what 0x923E returns for a data type (1 thumbnail, 2 screennail).
func CompressedData(typ uint32) []byte { return []byte{0xFF, 0xD8, byte(typ), 0xFF, 0xD9} }

// CapturedImage is what one full shutter press queues for the host.
var CapturedImage = append([]byte("\xff\xd8captured"), make([]byte, 100)...)

// settingsBackup is what GetObject on the settings handle returns.
var settingsBackup = append([]byte("SONY1"), make([]byte, 100)...)

// DeleteCall is one 0x9250 call received.
type DeleteCall struct{ Slot, ContentID uint32 }

// UploadCall is one 0x921A or 0x921B call received.
type UploadCall struct {
	Op    uint16
	Param uint32
	Data  []byte
}

// Deletes returns the 0x9250 calls received so far.
func (s *Server) Deletes() []DeleteCall {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]DeleteCall(nil), s.deletes...)
}

// Uploads returns the 0x921A/0x921B calls received so far.
func (s *Server) Uploads() []UploadCall {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]UploadCall(nil), s.uploads...)
}

// Restores returns the settings payloads received by SendObject so far.
func (s *Server) Restores() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([][]byte(nil), s.restores...)
}

// ControlCall is one 0x9207 call received.
type ControlCall struct {
	Code  uint16
	Value int64
}

// Controls returns the 0x9207 calls received so far.
func (s *Server) Controls() []ControlCall {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]ControlCall(nil), s.controls...)
}

// ContentRequest is one 0x923D call received.
type ContentRequest struct {
	ContentID, FileID uint32
	Slot              uint32
	Offset            uint64
	Size              uint32
	Last              bool
}

// ContentRequests returns the 0x923D calls received so far.
func (s *Server) ContentRequests() []ContentRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]ContentRequest(nil), s.requests...)
}

// Start listens on 127.0.0.1 with a random port.
func Start(opts Options) (*Server, error) {
	if opts.A6700 {
		opts.Sony, opts.NoPartial = true, true
	}
	if opts.ChunkSize <= 0 {
		opts.ChunkSize = 64 << 10
	}
	if opts.Model == "" {
		opts.Model = "ILCE-6700"
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	if opts.Props == nil {
		opts.Props = []ptpip.SonyProp{
			{Code: 0x500A, Type: 4, Writable: true, Enabled: 1, Default: ptpip.SonyValue{Int: 0x8001}, Current: ptpip.SonyValue{Int: 0x8001}, Form: 2,
				Settable: []ptpip.SonyValue{{Int: 0x8001}, {Int: 0x8002}, {Int: 0x8004}}, Readable: []ptpip.SonyValue{{Int: 0x8001}, {Int: 0x8002}, {Int: 0x8004}}},
			{Code: 0xD07B, Type: 0xFFFF, Enabled: 1, Current: ptpip.SonyValue{Str: "E PZ 16-50mm F3.5-5.6 OSS"}},
			{Code: 0xD20F, Type: 4, Writable: true, Enabled: 1, Default: ptpip.SonyValue{Int: 5500}, Current: ptpip.SonyValue{Int: 5500}, Form: 1,
				Min: ptpip.SonyValue{Int: 2500}, Max: ptpip.SonyValue{Int: 9900}, Step: ptpip.SonyValue{Int: 100}},
		}
	}
	s := &Server{props: append([]ptpip.SonyProp(nil), opts.Props...), opts: opts, ln: ln, evtConns: map[uint32]net.Conn{}, handles: map[string]uint32{}, nextH: 0x100, contentIDs: map[string]uint32{}}
	go s.accept()
	return s, nil
}

// Addr is host:port of the server.
func (s *Server) Addr() string { return s.ln.Addr().String() }

// Close stops the server and drops all connections.
func (s *Server) Close() {
	s.ln.Close()
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.conns {
		c.Close()
	}
}

// DropConnections closes every open connection (simulates Wi-Fi loss).
func (s *Server) DropConnections() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.conns {
		c.Close()
	}
	s.conns = nil
}

// Emit sends an event to all connected initiators.
func (s *Server) Emit(code uint16, params ...uint32) {
	p := binary.LittleEndian.AppendUint16(nil, code)
	p = binary.LittleEndian.AppendUint32(p, 0xFFFFFFFF)
	for _, v := range params {
		p = binary.LittleEndian.AppendUint32(p, v)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, c := range s.evtConns {
		write(c, 8, p)
	}
}

func write(w io.Writer, typ uint32, payload []byte) error {
	b := make([]byte, 8+len(payload))
	binary.LittleEndian.PutUint32(b, uint32(len(b)))
	binary.LittleEndian.PutUint32(b[4:], typ)
	copy(b[8:], payload)
	_, err := w.Write(b)
	return err
}

func read(r io.Reader) (uint32, []byte, error) {
	var h [8]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return 0, nil, err
	}
	n := binary.LittleEndian.Uint32(h[:])
	if n < 8 || n > 1<<20 {
		return 0, nil, fmt.Errorf("bad length %d", n)
	}
	p := make([]byte, n-8)
	_, err := io.ReadFull(r, p)
	return binary.LittleEndian.Uint32(h[4:]), p, err
}

func (s *Server) accept() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.conns = append(s.conns, c)
		s.mu.Unlock()
		go s.serve(c)
	}
}

func (s *Server) serve(c net.Conn) {
	defer c.Close()
	typ, p, err := read(c)
	if err != nil {
		return
	}
	switch typ {
	case 1: // InitCommandRequest
		if s.opts.RefuseInit {
			write(c, 5, binary.LittleEndian.AppendUint32(nil, 0x1)) // InitFail
			return
		}
		s.mu.Lock()
		s.nextConn++
		conn := s.nextConn
		s.mu.Unlock()
		ack := binary.LittleEndian.AppendUint32(nil, conn)
		ack = append(ack, make([]byte, 16)...)
		ack = append(ack, 'F', 0, 'a', 0, 'k', 0, 'e', 0, 0, 0)
		ack = binary.LittleEndian.AppendUint32(ack, 0x00010000)
		write(c, 2, ack)
		s.commands(c)
	case 3: // InitEventRequest
		if len(p) < 4 {
			return
		}
		conn := binary.LittleEndian.Uint32(p)
		s.mu.Lock()
		s.evtConns[conn] = c
		s.mu.Unlock()
		write(c, 4, nil)
		for {
			if _, _, err := read(c); err != nil {
				s.mu.Lock()
				delete(s.evtConns, conn)
				s.mu.Unlock()
				return
			}
		}
	}
}

// ---------------------------------------------------------------- object model

type object struct {
	handle  uint32
	storage uint32
	parent  uint32
	abs     string
	info    ptpip.ObjectInfo
}

func (s *Server) storages() map[uint32]string {
	out := map[uint32]string{}
	for i, slot := range []string{"slot1", "slot2"} {
		d := filepath.Join(s.opts.Dir, slot)
		if st, err := os.Stat(d); err == nil && st.IsDir() {
			out[uint32(i+1)<<16|1] = d
		}
	}
	if len(out) == 0 {
		out[0x00010001] = s.opts.Dir
	}
	return out
}

func (s *Server) handleFor(key string) uint32 {
	if h, ok := s.handles[key]; ok {
		return h
	}
	s.nextH++
	s.handles[key] = s.nextH
	return s.nextH
}

func (s *Server) scan() map[uint32]*object {
	s.mu.Lock()
	defer s.mu.Unlock()
	objs := map[uint32]*object{}
	for sid, root := range s.storages() {
		dirHandles := map[string]uint32{root: ptpip.ParentAll}
		filepath.Walk(root, func(p string, fi os.FileInfo, err error) error {
			if err != nil || p == root || strings.HasPrefix(fi.Name(), ".") {
				return nil
			}
			h := s.handleFor(fmt.Sprintf("%08x:%s", sid, p))
			o := &object{handle: h, storage: sid, parent: dirHandles[filepath.Dir(p)], abs: p}
			o.info = ptpip.ObjectInfo{
				StorageID: sid, ParentObject: o.parent, Filename: fi.Name(),
				CaptureDate:      fi.ModTime().UTC().Format("20060102T150405") + s.opts.DateSuffix,
				ModificationDate: fi.ModTime().UTC().Format("20060102T150405") + s.opts.DateSuffix,
			}
			if s.opts.A6700 {
				o.info.ModificationDate = ""
			}
			if fi.IsDir() {
				o.info.ObjectFormat = ptpip.FormatAssociation
				o.info.AssociationType = 1
				dirHandles[p] = h
			} else {
				o.info.ObjectFormat = 0x3000
				if strings.EqualFold(filepath.Ext(p), ".jpg") {
					o.info.ObjectFormat = 0x3801
				}
				o.info.CompressedSize = uint32(fi.Size())
			}
			objs[h] = o
			return nil
		})
	}
	return objs
}

// ---------------------------------------------------------------- command channel

type session struct {
	open         bool
	in           []byte // data phase received with the current request
	xfer         bool   // 0x9212(2,1,0) received
	settingsInfo bool   // GetObjectInfo on the settings handle seen

	objs map[uint32]*object
}

func (s *Server) commands(c net.Conn) {
	sess := &session{}
	for {
		typ, p, err := read(c)
		if err != nil {
			return
		}
		if typ == 13 { // probe
			write(c, 14, nil)
			continue
		}
		if typ != 6 || len(p) < 10 {
			return
		}
		op := binary.LittleEndian.Uint16(p[4:])
		tid := binary.LittleEndian.Uint32(p[6:])
		var params []uint32
		for off := 10; off+4 <= len(p); off += 4 {
			params = append(params, binary.LittleEndian.Uint32(p[off:]))
		}
		sess.in = nil
		if binary.LittleEndian.Uint32(p) == 2 { // initiator sends a data phase
			for {
				t, d, err := read(c)
				if err != nil || len(d) < 4 && t != 9 {
					return
				}
				if t != 9 {
					sess.in = append(sess.in, d[4:]...)
				}
				if t == 12 {
					break
				}
			}
		}
		s.Ops.Add(1)
		code, rparams, data, err := s.op(sess, op, params)
		if err != nil {
			return // simulated connection drop
		}
		if data != nil {
			if err := s.sendData(c, tid, data); err != nil {
				return
			}
		}
		resp := binary.LittleEndian.AppendUint16(nil, code)
		resp = binary.LittleEndian.AppendUint32(resp, tid)
		for _, v := range rparams {
			resp = binary.LittleEndian.AppendUint32(resp, v)
		}
		if err := write(c, 7, resp); err != nil {
			return
		}
	}
}

var errDrop = errors.New("drop")

func (s *Server) sendData(c net.Conn, tid uint32, data []byte) error {
	start := binary.LittleEndian.AppendUint32(nil, tid)
	start = binary.LittleEndian.AppendUint64(start, uint64(len(data)))
	if err := write(c, 9, start); err != nil {
		return err
	}
	for {
		n := min(len(data), s.opts.ChunkSize)
		typ := uint32(10)
		if n == len(data) {
			typ = 12
		}
		if err := write(c, typ, append(binary.LittleEndian.AppendUint32(nil, tid), data[:n]...)); err != nil {
			return err
		}
		data = data[n:]
		if typ == 12 {
			return nil
		}
	}
}

func (s *Server) objectBytes(o *object, offset, max int64) ([]byte, error) {
	b, err := os.ReadFile(o.abs)
	if err != nil {
		return nil, err
	}
	if offset > int64(len(b)) {
		offset = int64(len(b))
	}
	end := int64(len(b))
	if max >= 0 && offset+max < end {
		end = offset + max
	}
	out := b[offset:end]
	if s.opts.DropAfter > 0 && s.served.Add(int64(len(out))) > s.opts.DropAfter && s.dropped.CompareAndSwap(false, true) {
		return nil, errDrop
	}
	return out, nil
}

func (s *Server) op(sess *session, op uint16, params []uint32) (uint16, []uint32, []byte, error) {
	param := func(i int) uint32 {
		if i < len(params) {
			return params[i]
		}
		return 0
	}
	switch op {
	case ptpip.OpGetDeviceInfo:
		ops := []uint16{0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009}
		if !s.opts.NoPartial || s.opts.A6700 {
			ops = append(ops, ptpip.OpGetPartialObject)
		}
		if s.opts.Sony {
			ops = append(ops, ptpip.OpSonySDIOConnect, ptpip.OpSonyGetExtDevInfo,
				ptpip.OpSonySetExtDevicePropValue, ptpip.OpSonyControlDevice, ptpip.OpSonyGetAllExtDevicePropInfo, ptpip.OpSonyGetExtDeviceProp,
				ptpip.OpSonyGetTimeZone, ptpip.OpSonySetTimeZone, ptpip.OpSonyDeleteContent, ptpip.OpSonyUploadData, ptpip.OpSonyControlUploadData,
				ptpip.OpSonyGetOSDImage, ptpip.OpSendObject,
				ptpip.OpMTPGetObjectPropsSupported, ptpip.OpMTPGetObjectPropDesc, ptpip.OpMTPGetObjectPropValue, ptpip.OpMTPGetObjPropList)
		}
		if s.opts.A6700 {
			ops = append(ops, ptpip.OpSonyGetPartialLargeObject, ptpip.OpSonySetContentsTransferMode)
			if !s.opts.NoRemote {
				ops = append(ops, ptpip.OpSonyGetCapturedDateList, ptpip.OpSonyGetContentsInfoList, ptpip.OpSonyGetContentsData, ptpip.OpSonyGetContentsCompressedData)
			}
		}
		di := &ptpip.DeviceInfo{
			StandardVersion: 100, VendorExtensionID: 0xFFFFFFFF, VendorExtensionDesc: "microsoft.com: 1.0;",
			Operations: ops, Events: []uint16{0x4002, 0x4003}, Manufacturer: "Sony Corporation",
			Model: s.opts.Model, DeviceVersion: "2.00", SerialNumber: s.opts.SerialNumber,
		}
		return ptpip.RespOK, nil, ptpip.EncodeDeviceInfo(di), nil
	case ptpip.OpOpenSession:
		if sess.open {
			return ptpip.RespSessionAlreadyOpen, nil, nil, nil
		}
		sess.open = true
		return ptpip.RespOK, nil, nil, nil
	}
	if !sess.open {
		return 0x2003, nil, nil, nil
	}
	if s.opts.A6700 && !sess.xfer {
		switch op {
		case ptpip.OpGetStorageIDs, ptpip.OpGetStorageInfo, ptpip.OpGetObjectHandles:
			return ptpip.RespStoreNotAvailable, nil, nil, nil
		}
	}
	switch op {
	case ptpip.OpCloseSession:
		sess.open = false
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonySDIOConnect:
		if !s.opts.Sony {
			break
		}
		return ptpip.RespOK, nil, []byte{}, nil
	case ptpip.OpSonyGetExtDevInfo:
		if !s.opts.Sony {
			break
		}
		b := binary.LittleEndian.AppendUint16(nil, 0x12C)
		b = binary.LittleEndian.AppendUint32(b, 2)
		b = binary.LittleEndian.AppendUint16(b, 0x5004)
		b = binary.LittleEndian.AppendUint16(b, 0xD200)
		b = binary.LittleEndian.AppendUint32(b, 1)
		b = binary.LittleEndian.AppendUint16(b, 0xD2C1)
		return ptpip.RespOK, nil, b, nil
	case ptpip.OpSonyGetAllExtDevicePropInfo: // changedOnly (param 0) is ignored: always the full list
		if !s.opts.Sony {
			break
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		return ptpip.RespOK, nil, ptpip.EncodeSonyProps(s.props), nil
	case ptpip.OpSonyGetExtDeviceProp:
		if !s.opts.Sony {
			break
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if param(0) == uint32(ptpip.SonyPropCapturedImages) {
			v := int64(len(s.captured))
			if v > 0 {
				v |= 0x8000
			}
			b, _ := ptpip.EncodeSonyProp(ptpip.SonyProp{Code: ptpip.SonyPropCapturedImages, Type: 4, Enabled: 1, Current: ptpip.SonyValue{Int: v}})
			return ptpip.RespOK, nil, b, nil
		}
		for _, p := range s.props {
			if p.Code == uint16(param(0)) {
				if b, ok := ptpip.EncodeSonyProp(p); ok {
					return ptpip.RespOK, nil, b, nil
				}
			}
		}
		return 0x200A, nil, nil, nil // DevicePropNotSupported
	case ptpip.OpSonySetExtDevicePropValue:
		if !s.opts.Sony {
			break
		}
		code := uint16(param(0))
		s.mu.Lock()
		for i := range s.props {
			if s.props[i].Code != code {
				continue
			}
			if !s.props[i].Writable {
				s.mu.Unlock()
				return 0x200F, nil, nil, nil // AccessDenied
			}
			v, err := ptpip.DecodeSonyValue(s.props[i].Type, sess.in)
			if err != nil {
				s.mu.Unlock()
				return 0x201D, nil, nil, nil // InvalidParameter
			}
			s.props[i].Current = v
			s.mu.Unlock()
			s.Emit(ptpip.EvSonyPropChanged, uint32(code))
			return ptpip.RespOK, nil, nil, nil
		}
		s.mu.Unlock()
		return 0x200A, nil, nil, nil // DevicePropNotSupported
	case ptpip.OpSonyControlDevice:
		if !s.opts.Sony {
			break
		}
		code := uint16(param(0))
		typ, _, ok := ptpip.SonyControlInfo(code)
		if !ok {
			return 0x200A, nil, nil, nil // DevicePropNotSupported
		}
		v, err := ptpip.DecodeSonyValue(typ, sess.in)
		if err != nil {
			return 0x201D, nil, nil, nil // InvalidParameter
		}
		s.mu.Lock()
		s.controls = append(s.controls, ControlCall{code, v.Int})
		if code == ptpip.SonyCtlShutterFull && v.Int == 2 {
			s.captured = append(s.captured, CapturedImage)
		}
		s.mu.Unlock()
		if code == s.opts.FailControl && v.Int&0xFFFF == 2 {
			return 0x2002, nil, nil, nil // GeneralError
		}
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonyGetTimeZone:
		if !s.opts.Sony {
			break
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.timeZone != nil {
			return ptpip.RespOK, nil, s.timeZone, nil
		}
		b := binary.LittleEndian.AppendUint32(nil, 100)
		return ptpip.RespOK, nil, append(b, "20260101T000000.0\x00+0100\x00\x00"...), nil
	case ptpip.OpSonySetTimeZone:
		if !s.opts.Sony {
			break
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		s.timeZone = timeZoneReply(sess.in)
		if s.timeZone == nil {
			return 0x201D, nil, nil, nil // InvalidParameter
		}
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonyDeleteContent:
		if !s.opts.Sony {
			break
		}
		if param(0) == 0 {
			return ptpip.RespInvalidObjectHandle, nil, nil, nil
		}
		s.mu.Lock()
		s.deletes = append(s.deletes, DeleteCall{Slot: param(1), ContentID: param(0)})
		s.mu.Unlock()
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonyUploadData, ptpip.OpSonyControlUploadData:
		if !s.opts.Sony {
			break
		}
		s.mu.Lock()
		s.uploads = append(s.uploads, UploadCall{Op: op, Param: param(0), Data: sess.in})
		s.mu.Unlock()
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSendObject:
		if !s.opts.Sony || param(0) != 0xFFFFC004 {
			break
		}
		s.mu.Lock()
		s.restores = append(s.restores, sess.in)
		s.mu.Unlock()
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonyGetOSDImage:
		if !s.opts.Sony {
			break
		}
		s.OSD9238.Add(1)
		s.mu.Lock()
		on := false
		for _, p := range s.props {
			on = on || p.Code == 0xD207 && p.Current.Int == 1
		}
		s.mu.Unlock()
		if !on {
			return ptpip.RespOK, nil, []byte("garbl!"), nil
		}
		img := []byte("\x89PNG fake")
		b := binary.LittleEndian.AppendUint32(nil, 16)
		b = binary.LittleEndian.AppendUint32(b, uint32(len(img)))
		b = binary.LittleEndian.AppendUint32(b, uint32(16+len(img)))
		b = binary.LittleEndian.AppendUint32(b, 36)
		b = append(b, img...)
		return ptpip.RespOK, nil, append(b, make([]byte, 36)...), nil
	case ptpip.OpSonySetContentsTransferMode:
		if !s.opts.A6700 {
			break
		}
		s.Xfer9212.Add(1)
		if len(params) == 3 && params[0] == 2 && params[1] == 1 && params[2] == 0 && !sess.xfer {
			sess.xfer = true
			go s.Emit(ptpip.EvStoreAdded, 0x00010001)
		}
		return ptpip.RespOK, nil, nil, nil
	case ptpip.OpSonyGetCapturedDateList, ptpip.OpSonyGetContentsInfoList, ptpip.OpSonyGetContentsData, ptpip.OpSonyGetContentsCompressedData:
		if !s.opts.A6700 || s.opts.NoRemote {
			break
		}
		if s.opts.CardBusy {
			return ptpip.RespOperationNotSupported, nil, nil, nil
		}
		return s.remote(op, params)
	case ptpip.OpGetStorageIDs:
		var ids []uint32
		for id := range s.storages() {
			ids = append(ids, id)
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		return ptpip.RespOK, nil, ptpip.EncodeU32Array(ids), nil
	case ptpip.OpGetStorageInfo:
		b := make([]byte, 6+8+8+4)
		b = append(b, 0, 0)
		return ptpip.RespOK, nil, b, nil
	case ptpip.OpGetObjectHandles:
		sess.objs = s.scan()
		storage, parent := param(0), param(2)
		var hs []uint32
		for h, o := range sess.objs {
			if storage != 0xFFFFFFFF && o.storage != storage {
				continue
			}
			switch parent {
			case ptpip.ParentAll:
			case ptpip.ParentRoot:
				if o.parent != ptpip.ParentAll {
					continue
				}
			default:
				if o.parent != parent {
					continue
				}
			}
			hs = append(hs, h)
		}
		sort.Slice(hs, func(i, j int) bool { return hs[i] < hs[j] })
		return ptpip.RespOK, nil, ptpip.EncodeU32Array(hs), nil
	case ptpip.OpMTPGetObjectPropsSupported:
		codes := make([]uint16, len(mtpProps))
		for i, p := range mtpProps {
			codes[i] = p.code
		}
		b := binary.LittleEndian.AppendUint32(nil, uint32(len(codes)))
		for _, c := range codes {
			b = binary.LittleEndian.AppendUint16(b, c)
		}
		return ptpip.RespOK, nil, b, nil
	case ptpip.OpMTPGetObjectPropDesc:
		for _, p := range mtpProps {
			if uint32(p.code) == param(0) {
				b := binary.LittleEndian.AppendUint16(nil, p.code)
				b = binary.LittleEndian.AppendUint16(b, p.typ)
				b = append(b, 0) // get only
				switch p.typ {
				case 0xFFFF:
					b = ptpip.PutString(b, "")
				default:
					b = append(b, make([]byte, map[uint16]int{4: 2, 6: 4, 8: 8}[p.typ])...)
				}
				b = binary.LittleEndian.AppendUint32(b, 0)  // group
				return ptpip.RespOK, nil, append(b, 0), nil // form none
			}
		}
		return 0x200A, nil, nil, nil // InvalidObjectPropCode
	case ptpip.OpMTPGetObjectPropValue, ptpip.OpMTPGetObjPropList:
		if sess.objs == nil {
			sess.objs = s.scan()
		}
		o, ok := sess.objs[param(0)]
		if !ok {
			return ptpip.RespInvalidObjectHandle, nil, nil, nil
		}
		want := param(1) // 0x9803: (handle, prop); 0x9805: (handle, format, prop, ...)
		if op == ptpip.OpMTPGetObjPropList {
			want = param(2)
		}
		var sel []mtpProp
		for _, p := range mtpProps {
			if want == 0xFFFFFFFF || uint32(p.code) == want {
				sel = append(sel, p)
			}
		}
		if len(sel) == 0 {
			return 0x200A, nil, nil, nil
		}
		if op == ptpip.OpMTPGetObjectPropValue {
			return ptpip.RespOK, nil, sel[0].value(o), nil
		}
		b := binary.LittleEndian.AppendUint32(nil, uint32(len(sel)))
		for _, p := range sel {
			b = binary.LittleEndian.AppendUint32(b, o.handle)
			b = binary.LittleEndian.AppendUint16(b, p.code)
			b = binary.LittleEndian.AppendUint16(b, p.typ)
			b = append(b, p.value(o)...)
		}
		return ptpip.RespOK, nil, b, nil
	case ptpip.OpGetObjectInfo, ptpip.OpGetObject, ptpip.OpGetPartialObject, ptpip.OpSonyGetPartialLargeObject:
		if s.opts.Sony && param(0) == 0xFFFFC004 && (op == ptpip.OpGetObjectInfo || op == ptpip.OpGetObject) {
			if op == ptpip.OpGetObject && !sess.settingsInfo {
				return ptpip.RespInvalidObjectHandle, nil, nil, nil // like the real camera
			}
			if op == ptpip.OpGetObject {
				return ptpip.RespOK, nil, settingsBackup, nil
			}
			sess.settingsInfo = true
			return ptpip.RespOK, nil, ptpip.EncodeObjectInfo(&ptpip.ObjectInfo{Filename: "settings", CompressedSize: uint32(len(settingsBackup))}), nil
		}
		if s.opts.Sony && param(0) == 0xFFFFC001 && (op == ptpip.OpGetObjectInfo || op == ptpip.OpGetObject) {
			s.mu.Lock()
			defer s.mu.Unlock()
			if len(s.captured) == 0 {
				return ptpip.RespInvalidObjectHandle, nil, nil, nil
			}
			img := s.captured[0]
			if op == ptpip.OpGetObjectInfo {
				return ptpip.RespOK, nil, ptpip.EncodeObjectInfo(&ptpip.ObjectInfo{Filename: "DSC00001.JPG", ObjectFormat: 0x3801, CompressedSize: uint32(len(img))}), nil
			}
			s.captured = s.captured[1:]
			return ptpip.RespOK, nil, img, nil
		}
		if sess.objs == nil {
			sess.objs = s.scan()
		}
		o, ok := sess.objs[param(0)]
		if !ok {
			return ptpip.RespInvalidObjectHandle, nil, nil, nil
		}
		switch op {
		case ptpip.OpGetObjectInfo:
			return ptpip.RespOK, nil, ptpip.EncodeObjectInfo(&o.info), nil
		case ptpip.OpGetObject:
			b, err := s.objectBytes(o, 0, -1)
			if err != nil {
				return 0, nil, nil, err
			}
			return ptpip.RespOK, nil, b, nil
		case ptpip.OpSonyGetPartialLargeObject:
			if !s.opts.A6700 {
				break
			}
			b, err := s.objectBytes(o, int64(param(1))|int64(param(2))<<32, int64(param(3)))
			if err != nil {
				return 0, nil, nil, err
			}
			return ptpip.RespOK, []uint32{uint32(len(b))}, b, nil
		default:
			if s.opts.A6700 {
				return ptpip.RespInvalidObjectHandle, nil, nil, nil
			}
			if s.opts.NoPartial {
				break
			}
			b, err := s.objectBytes(o, int64(param(1)), int64(param(2)))
			if err != nil {
				return 0, nil, nil, err
			}
			return ptpip.RespOK, []uint32{uint32(len(b))}, b, nil
		}
	}
	return ptpip.RespOperationNotSupported, nil, nil, nil
}

// mtpProps are the object properties the fake serves (MTP codes and types).
var mtpProps = []mtpProp{
	{0xDC01, 6, func(o *object) []byte { return binary.LittleEndian.AppendUint32(nil, o.storage) }},
	{0xDC02, 4, func(o *object) []byte { return binary.LittleEndian.AppendUint16(nil, o.info.ObjectFormat) }},
	{0xDC04, 8, func(o *object) []byte { return binary.LittleEndian.AppendUint64(nil, uint64(o.info.CompressedSize)) }},
	{0xDC07, 0xFFFF, func(o *object) []byte { return ptpip.PutString(nil, o.info.Filename) }},
}

type mtpProp struct {
	code, typ uint16
	value     func(*object) []byte
}

// timeZoneReply turns a 0x9249 payload (all three fields present) into the
// 0x9248 reply (same fields without the presence flags); nil if malformed.
func timeZoneReply(in []byte) []byte {
	if len(in) != 32 || in[4] != 1 || in[23] != 1 || in[30] != 1 {
		return nil
	}
	b := append(in[:4:4], in[5:23]...)
	b = append(b, in[24:30]...)
	return append(b, in[31])
}

// Touch is a helper for tests that sets a file's mtime.
func Touch(path string, t time.Time) error { return os.Chtimes(path, t, t) }

// ---------------------------------------------------------------- RemoteTransfer (0x923B-0x923D)

type content struct {
	c     ptpip.SonyContent
	paths []string // absolute, parallel to c.Files
}

// contents groups the files of a slot by directory and stem (DSC00001.JPG +
// DSC00001.ARW = one content), sorted by creation time then path. Timestamps
// are file mtimes, so tests that page need distinct ones.
func (s *Server) contents(slot uint32) ([]content, bool) {
	root, ok := s.storages()[slot<<16|1]
	if !ok {
		return nil, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	groups := map[string]*content{}
	var keys []string
	filepath.Walk(root, func(p string, fi os.FileInfo, err error) error {
		if err != nil || fi.IsDir() || strings.HasPrefix(fi.Name(), ".") {
			return nil
		}
		key := strings.TrimSuffix(p, filepath.Ext(p))
		g := groups[key]
		if g == nil {
			g = &content{}
			groups[key] = g
			keys = append(keys, key)
			if _, ok := s.contentIDs[key]; !ok {
				s.contentIDs[key] = 0x00020001 + uint32(len(s.contentIDs))
			}
			g.c = ptpip.SonyContent{Type: 1, ID: s.contentIDs[key], CreatedUTC: fi.ModTime().UTC()}
		}
		rel, _ := filepath.Rel(root, p)
		format := uint32(0xB982)
		switch strings.ToUpper(filepath.Ext(p)) {
		case ".JPG":
			format = 0x3801
		case ".ARW":
			format = 0xB101
		}
		g.c.Files = append(g.c.Files, ptpip.SonyContentFile{ID: uint16(len(g.c.Files) + 1), Path: "A:/" + filepath.ToSlash(rel), Format: format, Size: uint64(fi.Size())})
		g.paths = append(g.paths, p)
		if fi.ModTime().UTC().Before(g.c.CreatedUTC) {
			g.c.CreatedUTC = fi.ModTime().UTC()
		}
		return nil
	})
	out := make([]content, 0, len(keys))
	for _, k := range keys {
		g := groups[k]
		g.c.ModifiedUTC, g.c.CreatedLocal, g.c.ModifiedLocal = g.c.CreatedUTC, g.c.CreatedUTC, g.c.CreatedUTC
		out = append(out, *g)
	}
	sort.Slice(out, func(i, j int) bool {
		if !out[i].c.CreatedUTC.Equal(out[j].c.CreatedUTC) {
			return out[i].c.CreatedUTC.Before(out[j].c.CreatedUTC)
		}
		return out[i].c.ID < out[j].c.ID
	})
	return out, true
}

func (s *Server) remote(op uint16, params []uint32) (uint16, []uint32, []byte, error) {
	param := func(i int) uint32 {
		if i < len(params) {
			return params[i]
		}
		return 0
	}
	switch op {
	case ptpip.OpSonyGetCapturedDateList:
		cs, ok := s.contents(param(0))
		if !ok {
			return 0x201D, nil, nil, nil // InvalidParameter
		}
		b := binary.LittleEndian.AppendUint32(nil, uint32(len(cs)))
		for _, c := range cs {
			b = binary.LittleEndian.AppendUint64(b, uint64(c.c.CreatedLocal.UnixMilli()))
		}
		return ptpip.RespOK, nil, b, nil
	case ptpip.OpSonyGetContentsInfoList:
		// (T low, T high, max, slot, 0): contents created after T
		cs, ok := s.contents(param(3))
		if !ok {
			return 0x201D, nil, nil, nil
		}
		after := int64(uint64(param(0)) | uint64(param(1))<<32)
		var page []ptpip.SonyContent
		for _, c := range cs {
			if (s.opts.IgnoreAfter || c.c.CreatedUTC.UnixMilli() > after) && len(page) < int(min(param(2), 100)) {
				page = append(page, c.c)
			}
		}
		return ptpip.RespOK, nil, ptpip.EncodeSonyContentsInfoList(page), nil
	case ptpip.OpSonyGetContentsCompressedData:
		// (content, slot<<24|file, type)
		cs, _ := s.contents(param(1) >> 24)
		for _, c := range cs {
			if fileID := int(param(1) & 0xFFFF); c.c.ID == param(0) && fileID >= 1 && fileID <= len(c.paths) && (param(2) == 1 || param(2) == 2) {
				if len(s.opts.Preview)>0 { return ptpip.RespOK,nil,s.opts.Preview,nil }
				return ptpip.RespOK, nil, CompressedData(param(2)), nil
			}
		}
		return ptpip.RespInvalidObjectHandle, nil, nil, nil
	}
	// OpSonyGetContentsData: (content, slot<<24|file, off low, off high, size|last<<31)
	slot, fileID := param(1)>>24, param(1)&0xFFFF
	req := ContentRequest{ContentID: param(0), FileID: fileID, Slot: slot, Offset: uint64(param(2)) | uint64(param(3))<<32, Size: param(4) &^ (1 << 31), Last: param(4)>>31 != 0}
	s.mu.Lock()
	s.requests = append(s.requests, req)
	s.mu.Unlock()
	cs, ok := s.contents(slot)
	if !ok {
		return 0x201D, nil, nil, nil
	}
	for _, c := range cs {
		if c.c.ID != req.ContentID {
			continue
		}
		if fileID < 1 || int(fileID) > len(c.paths) {
			break
		}
		f, err := os.Open(c.paths[fileID-1])
		if err != nil {
			return 0, nil, nil, err
		}
		defer f.Close()
		b := make([]byte, req.Size)
		n, err := f.ReadAt(b, int64(req.Offset))
		if err != nil && err != io.EOF {
			return 0, nil, nil, err
		}
		if s.opts.DropAfter > 0 && s.served.Add(int64(n)) > s.opts.DropAfter && s.dropped.CompareAndSwap(false, true) {
			return 0, nil, nil, errDrop
		}
		return ptpip.RespOK, []uint32{0x1B8BA067, 0x1A1}, b[:n], nil
	}
	return ptpip.RespInvalidObjectHandle, nil, nil, nil
}
