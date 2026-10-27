// Package ptpip is a minimal PTP/IP (ISO 15740 / CIPA DC-005) initiator:
// connection handshake, transactions with data phases, events, and the
// standard object operations needed to list and download files. It also
// implements the publicly documented Sony "SDIO" connect sequence (as used
// by libgphoto2) so Sony bodies expose their extended operation list.
//
// All integers are little-endian. Every packet starts with
// uint32 length (including this header) and uint32 type.
package ptpip

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"
	"unicode/utf16"
)

// DefaultPort is the IANA port for PTP/IP.
const DefaultPort = 15740

// Packet types.
const (
	pktInitCommandRequest = 1
	pktInitCommandAck     = 2
	pktInitEventRequest   = 3
	pktInitEventAck       = 4
	pktInitFail           = 5
	pktOperationRequest   = 6
	pktOperationResponse  = 7
	pktEvent              = 8
	pktStartData          = 9
	pktData               = 10
	pktCancel             = 11
	pktEndData            = 12
	pktProbeRequest       = 13
	pktProbeResponse      = 14
)

// Operation codes (standard PTP).
const (
	OpGetDeviceInfo               uint16 = 0x1001
	OpOpenSession                 uint16 = 0x1002
	OpCloseSession                uint16 = 0x1003
	OpGetStorageIDs               uint16 = 0x1004
	OpGetStorageInfo              uint16 = 0x1005
	OpGetNumObjects               uint16 = 0x1006
	OpGetObjectHandles            uint16 = 0x1007
	OpGetObjectInfo               uint16 = 0x1008
	OpGetObject                   uint16 = 0x1009
	OpGetThumb                    uint16 = 0x100A
	OpSendObject                  uint16 = 0x100D
	OpGetPartialObject            uint16 = 0x101B
	OpSonySDIOConnect             uint16 = 0x9201
	OpSonyGetExtDevInfo           uint16 = 0x9202
	OpSonySetExtDevicePropValue   uint16 = 0x9205
	OpSonyControlDevice           uint16 = 0x9207
	OpSonyGetAllExtDevicePropInfo uint16 = 0x9209
	OpSonyGetFTPServerNames       uint16 = 0x920E
	OpSonyGetPartialLargeObject   uint16 = 0x9211
	OpSonyUploadData              uint16 = 0x921A
	OpSonyControlUploadData       uint16 = 0x921B
	OpSonyGetDisplayStrings       uint16 = 0x9215
	OpSonyGetOperationResults     uint16 = 0x922F
	OpSonyGetDeviceDescription    uint16 = 0x923A
	OpSonyGetOSDImage             uint16 = 0x9238
	OpSonyGetTimeZone             uint16 = 0x9248
	OpSonySetTimeZone             uint16 = 0x9249
	OpSonyDeleteContent           uint16 = 0x9250
	OpSonyGetExtDeviceProp        uint16 = 0x9251
	OpSonySetContentsTransferMode uint16 = 0x9212

	// MTP object property operations.
	OpMTPGetObjectPropsSupported uint16 = 0x9801
	OpMTPGetObjectPropDesc       uint16 = 0x9802
	OpMTPGetObjectPropValue      uint16 = 0x9803
	OpMTPGetObjPropList          uint16 = 0x9805

	// RemoteTransfer operations (docs/protocol.md).
	OpSonyGetCapturedDateList       uint16 = 0x923B
	OpSonyGetContentsInfoList       uint16 = 0x923C
	OpSonyGetContentsData           uint16 = 0x923D
	OpSonyGetContentsCompressedData uint16 = 0x923E
)

// Response codes used by the client logic (see names.go for the full table).
const (
	RespOK                    uint16 = 0x2001
	RespOperationNotSupported uint16 = 0x2005
	RespParameterNotSupported uint16 = 0x2006
	RespInvalidObjectHandle   uint16 = 0x2009
	RespDeviceBusy            uint16 = 0x2019
	RespStoreNotAvailable     uint16 = 0x2013
	RespSpecByFormatUnsupp    uint16 = 0x2014
	RespInvalidParameter      uint16 = 0x201D
	RespSessionAlreadyOpen    uint16 = 0x201E
)

// Event codes that indicate changed card contents.
const (
	EvObjectAdded        uint16 = 0x4002
	EvObjectRemoved      uint16 = 0x4003
	EvStoreAdded         uint16 = 0x4004
	EvStoreRemoved       uint16 = 0x4005
	EvObjectInfoChanged  uint16 = 0x4007
	EvStorageInfoChanged uint16 = 0x400C
	EvSonyObjectAdded    uint16 = 0xC201
	EvSonyObjectRemoved  uint16 = 0xC202
	EvSonyPropChanged    uint16 = 0xC203
	EvSonyContentsXfer   uint16 = 0xC20D
)

// FormatAssociation is the object format of folders.
const FormatAssociation uint16 = 0x3001

// VendorSony is the PTP vendor extension id Sony reports.
const VendorSony uint32 = 0x11

// ----------------------------------------------------------------- packets

type packet struct {
	typ     uint32
	payload []byte
}

const maxPacket = 64 << 20 // sanity limit for one packet

func writePacket(w io.Writer, typ uint32, payload []byte) error {
	buf := make([]byte, 8+len(payload))
	binary.LittleEndian.PutUint32(buf[0:], uint32(len(buf)))
	binary.LittleEndian.PutUint32(buf[4:], typ)
	copy(buf[8:], payload)
	_, err := w.Write(buf)
	return err
}

func readPacket(r io.Reader) (packet, error) {
	var hdr [8]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return packet{}, err
	}
	n := binary.LittleEndian.Uint32(hdr[0:])
	if n < 8 || n > maxPacket {
		return packet{}, fmt.Errorf("ptpip: bad packet length %d", n)
	}
	p := packet{typ: binary.LittleEndian.Uint32(hdr[4:]), payload: make([]byte, n-8)}
	if _, err := io.ReadFull(r, p.payload); err != nil {
		return packet{}, err
	}
	return p, nil
}

// ----------------------------------------------------------------- datasets

// Error is a PTP response code other than OK.
type Error struct {
	Op   uint16
	Code uint16
}

func (e *Error) Error() string {
	return fmt.Sprintf("ptp %s: %s", OpName(e.Op), RespName(e.Code))
}

// IsCode reports whether err is a PTP error with the given response code.
func IsCode(err error, code uint16) bool {
	var pe *Error
	return errors.As(err, &pe) && pe.Code == code
}

type reader struct {
	b   []byte
	off int
	err error
}

func (r *reader) need(n int) bool {
	if r.err != nil {
		return false
	}
	if r.off+n > len(r.b) {
		r.err = io.ErrUnexpectedEOF
		return false
	}
	return true
}

func (r *reader) u8() uint8 {
	if !r.need(1) {
		return 0
	}
	v := r.b[r.off]
	r.off++
	return v
}

func (r *reader) u16() uint16 {
	if !r.need(2) {
		return 0
	}
	v := binary.LittleEndian.Uint16(r.b[r.off:])
	r.off += 2
	return v
}

func (r *reader) u32() uint32 {
	if !r.need(4) {
		return 0
	}
	v := binary.LittleEndian.Uint32(r.b[r.off:])
	r.off += 4
	return v
}

func (r *reader) u64() uint64 {
	if !r.need(8) {
		return 0
	}
	v := binary.LittleEndian.Uint64(r.b[r.off:])
	r.off += 8
	return v
}

// str reads a PTP string: uint8 character count (including NUL), UCS-2LE.
func (r *reader) str() string {
	n := int(r.u8())
	if n == 0 || !r.need(2*n) {
		return ""
	}
	u := make([]uint16, n)
	for i := range u {
		u[i] = binary.LittleEndian.Uint16(r.b[r.off+2*i:])
	}
	r.off += 2 * n
	for len(u) > 0 && u[len(u)-1] == 0 {
		u = u[:len(u)-1]
	}
	return string(utf16.Decode(u))
}

func (r *reader) u16array() []uint16 {
	n := int(r.u32())
	if !r.need(2 * n) {
		return nil
	}
	out := make([]uint16, n)
	for i := range out {
		out[i] = r.u16()
	}
	return out
}

func (r *reader) u32array() []uint32 {
	n := int(r.u32())
	if !r.need(4 * n) {
		return nil
	}
	out := make([]uint32, n)
	for i := range out {
		out[i] = r.u32()
	}
	return out
}

// ucs2z encodes s as NUL-terminated UCS-2LE (used in the init handshake).
func ucs2z(s string) []byte {
	u := utf16.Encode([]rune(s))
	b := make([]byte, 0, 2*len(u)+2)
	for _, c := range u {
		b = binary.LittleEndian.AppendUint16(b, c)
	}
	return append(b, 0, 0)
}

// readUCS2Z decodes a NUL-terminated UCS-2LE string and returns the rest.
func readUCS2Z(b []byte) (string, []byte) {
	var u []uint16
	for len(b) >= 2 {
		c := binary.LittleEndian.Uint16(b)
		b = b[2:]
		if c == 0 {
			break
		}
		u = append(u, c)
	}
	return string(utf16.Decode(u)), b
}

// PutString appends a PTP string (used by tests / fake responders).
func PutString(b []byte, s string) []byte {
	if s == "" {
		return append(b, 0)
	}
	u := append(utf16.Encode([]rune(s)), 0)
	b = append(b, uint8(len(u)))
	for _, c := range u {
		b = binary.LittleEndian.AppendUint16(b, c)
	}
	return b
}

// DeviceInfo is the PTP DeviceInfo dataset.
type DeviceInfo struct {
	StandardVersion        uint16
	VendorExtensionID      uint32
	VendorExtensionVersion uint16
	VendorExtensionDesc    string
	FunctionalMode         uint16
	Operations             []uint16
	Events                 []uint16
	DeviceProperties       []uint16
	CaptureFormats         []uint16
	ImageFormats           []uint16
	Manufacturer           string
	Model                  string
	DeviceVersion          string
	SerialNumber           string
}

// Supports reports whether op is in the operation list.
func (d *DeviceInfo) Supports(op uint16) bool {
	for _, o := range d.Operations {
		if o == op {
			return true
		}
	}
	return false
}

// SupportsSonyRemoteTransfer reports whether the camera offers the Sony
// RemoteTransfer operations (list and download without locking the camera UI).
func (d *DeviceInfo) SupportsSonyRemoteTransfer() bool {
	return d.IsSony() && d.Supports(OpSonyGetCapturedDateList) && d.Supports(OpSonyGetContentsInfoList) && d.Supports(OpSonyGetContentsData)
}

// IsSony reports whether the device is a Sony camera.
func (d *DeviceInfo) IsSony() bool {
	return d.VendorExtensionID == VendorSony || strings.Contains(strings.ToLower(d.Manufacturer), "sony")
}

func parseDeviceInfo(b []byte) (*DeviceInfo, error) {
	r := &reader{b: b}
	d := &DeviceInfo{
		StandardVersion:        r.u16(),
		VendorExtensionID:      r.u32(),
		VendorExtensionVersion: r.u16(),
		VendorExtensionDesc:    r.str(),
		FunctionalMode:         r.u16(),
		Operations:             r.u16array(),
		Events:                 r.u16array(),
		DeviceProperties:       r.u16array(),
		CaptureFormats:         r.u16array(),
		ImageFormats:           r.u16array(),
		Manufacturer:           r.str(),
		Model:                  r.str(),
		DeviceVersion:          r.str(),
		SerialNumber:           r.str(),
	}
	if r.err != nil {
		return d, fmt.Errorf("ptpip: parse DeviceInfo: %w", r.err)
	}
	return d, nil
}

// EncodeDeviceInfo serialises a DeviceInfo (fake responders).
func EncodeDeviceInfo(d *DeviceInfo) []byte {
	b := binary.LittleEndian.AppendUint16(nil, d.StandardVersion)
	b = binary.LittleEndian.AppendUint32(b, d.VendorExtensionID)
	b = binary.LittleEndian.AppendUint16(b, d.VendorExtensionVersion)
	b = PutString(b, d.VendorExtensionDesc)
	b = binary.LittleEndian.AppendUint16(b, d.FunctionalMode)
	for _, arr := range [][]uint16{d.Operations, d.Events, d.DeviceProperties, d.CaptureFormats, d.ImageFormats} {
		b = binary.LittleEndian.AppendUint32(b, uint32(len(arr)))
		for _, v := range arr {
			b = binary.LittleEndian.AppendUint16(b, v)
		}
	}
	for _, s := range []string{d.Manufacturer, d.Model, d.DeviceVersion, d.SerialNumber} {
		b = PutString(b, s)
	}
	return b
}

// ObjectInfo is the PTP ObjectInfo dataset.
type ObjectInfo struct {
	StorageID        uint32
	ObjectFormat     uint16
	ProtectionStatus uint16
	CompressedSize   uint32 // 0xFFFFFFFF for objects >= 4 GiB
	ParentObject     uint32
	AssociationType  uint16
	SequenceNumber   uint32
	Filename         string
	CaptureDate      string // raw PTP date string, see ParseDate
	ModificationDate string
}

// IsFolder reports whether the object is an association (folder).
func (o *ObjectInfo) IsFolder() bool { return o.ObjectFormat == FormatAssociation }

func parseObjectInfo(b []byte) (*ObjectInfo, error) {
	r := &reader{b: b}
	o := &ObjectInfo{}
	o.StorageID = r.u32()
	o.ObjectFormat = r.u16()
	o.ProtectionStatus = r.u16()
	o.CompressedSize = r.u32()
	r.u16() // thumb format
	r.u32() // thumb compressed size
	r.u32() // thumb width
	r.u32() // thumb height
	r.u32() // image width
	r.u32() // image height
	r.u32() // image bit depth
	o.ParentObject = r.u32()
	o.AssociationType = r.u16()
	r.u32() // association desc
	o.SequenceNumber = r.u32()
	o.Filename = r.str()
	o.CaptureDate = r.str()
	o.ModificationDate = r.str()
	if r.err != nil {
		return o, fmt.Errorf("ptpip: parse ObjectInfo: %w", r.err)
	}
	return o, nil
}

// EncodeObjectInfo serialises an ObjectInfo (fake responders).
func EncodeObjectInfo(o *ObjectInfo) []byte {
	b := binary.LittleEndian.AppendUint32(nil, o.StorageID)
	b = binary.LittleEndian.AppendUint16(b, o.ObjectFormat)
	b = binary.LittleEndian.AppendUint16(b, o.ProtectionStatus)
	b = binary.LittleEndian.AppendUint32(b, o.CompressedSize)
	b = binary.LittleEndian.AppendUint16(b, 0)
	for i := 0; i < 6; i++ {
		b = binary.LittleEndian.AppendUint32(b, 0)
	}
	b = binary.LittleEndian.AppendUint32(b, o.ParentObject)
	b = binary.LittleEndian.AppendUint16(b, o.AssociationType)
	b = binary.LittleEndian.AppendUint32(b, 0)
	b = binary.LittleEndian.AppendUint32(b, o.SequenceNumber)
	b = PutString(b, o.Filename)
	b = PutString(b, o.CaptureDate)
	b = PutString(b, o.ModificationDate)
	return PutString(b, "") // keywords
}

// StorageInfo is the subset of the PTP StorageInfo dataset we use.
type StorageInfo struct {
	StorageType      uint16
	FilesystemType   uint16
	AccessCapability uint16
	MaxCapacity      uint64
	FreeSpace        uint64
	FreeObjects      uint32
	Description      string
	VolumeLabel      string
}

func parseStorageInfo(b []byte) (*StorageInfo, error) {
	r := &reader{b: b}
	s := &StorageInfo{
		StorageType:      r.u16(),
		FilesystemType:   r.u16(),
		AccessCapability: r.u16(),
		MaxCapacity:      r.u64(),
		FreeSpace:        r.u64(),
		FreeObjects:      r.u32(),
		Description:      r.str(),
		VolumeLabel:      r.str(),
	}
	if r.err != nil {
		return s, fmt.Errorf("ptpip: parse StorageInfo: %w", r.err)
	}
	return s, nil
}

// SonyExtInfo is the result of Sony's SDIO_GetExtDeviceInfo.
type SonyExtInfo struct {
	ProtocolVersion uint16
	Properties      []uint16 // extended device property codes
	Controls        []uint16 // extended control codes
}

func parseSonyExtInfo(b []byte) (*SonyExtInfo, error) {
	if len(b) == 0 {
		return &SonyExtInfo{}, nil
	}
	r := &reader{b: b}
	s := &SonyExtInfo{ProtocolVersion: r.u16(), Properties: r.u16array(), Controls: r.u16array()}
	return s, r.err
}

// ParseDate parses a PTP date string "YYYYMMDDThhmmss[.s][Z|±hhmm]".
// Strings without a zone are interpreted in loc.
func ParseDate(s string, loc *time.Location) (time.Time, error) {
	s = strings.TrimSpace(s)
	if len(s) < 15 {
		return time.Time{}, fmt.Errorf("ptpip: bad date %q", s)
	}
	base, rest := s[:15], s[15:]
	frac := ""
	if strings.HasPrefix(rest, ".") {
		i := 1
		for i < len(rest) && rest[i] >= '0' && rest[i] <= '9' {
			i++
		}
		frac, rest = rest[:i], rest[i:]
	}
	layout := "20060102T150405"
	value := base
	if frac != "" {
		layout += "." + strings.Repeat("0", len(frac)-1)
		value += frac
	}
	switch {
	case rest == "":
		return time.ParseInLocation(layout, value, loc)
	case rest == "Z":
		return time.ParseInLocation(layout, value, time.UTC)
	default:
		return time.Parse(layout+"-0700", value+rest)
	}
}

// EncodeU32Array serialises a PTP uint32 array (fake responders).
func EncodeU32Array(v []uint32) []byte {
	b := binary.LittleEndian.AppendUint32(nil, uint32(len(v)))
	for _, x := range v {
		b = binary.LittleEndian.AppendUint32(b, x)
	}
	return b
}
