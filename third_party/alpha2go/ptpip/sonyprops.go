package ptpip

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"slices"
)

// SonyValue holds one property value: numbers in Int (uint64 bit-for-bit),
// strings in Str, arrays in Arr.
type SonyValue struct {
	Int int64
	Str string
	Arr []int64

	// NoNUL marks a string sent without its terminating NUL (the a6700 does
	// this for 0xD278); kept so re-encoding is byte-exact.
	NoNUL bool
}

// SonyProp is one entry of the 0x9209 reply.
type SonyProp struct {
	Code, Type         uint16
	Writable           bool
	Enabled            uint8 // 0 disabled, 1 enabled, 2 display only
	Default, Current   SonyValue
	Form               uint8 // 0 none, 1 range, 2 enum
	Min, Max, Step     SonyValue
	Settable, Readable []SonyValue // enum: values the camera accepts / may report
}

const (
	ptpString = 0xFFFF
	ptpArray  = 0x4000
)

// sonyScalarSize is the byte size of a PTP integer type, 0 if unsupported
// (128-bit and unknown types cannot be skipped safely).
func sonyScalarSize(t uint16) int {
	switch t {
	case 1, 2:
		return 1
	case 3, 4:
		return 2
	case 5, 6:
		return 4
	case 7, 8:
		return 8
	}
	return 0
}

func (r *reader) sonyScalar(t uint16) int64 {
	switch t {
	case 1:
		return int64(int8(r.u8()))
	case 2:
		return int64(r.u8())
	case 3:
		return int64(int16(r.u16()))
	case 4:
		return int64(r.u16())
	case 5:
		return int64(int32(r.u32()))
	case 6:
		return int64(r.u32())
	}
	return int64(r.u64())
}

func (r *reader) sonyValue(t uint16) SonyValue {
	var v SonyValue
	switch {
	case t == ptpString:
		v.Str = r.str()
		v.NoNUL = v.Str != "" && (r.b[r.off-1] != 0 || r.b[r.off-2] != 0)
	case t&ptpArray != 0 && sonyScalarSize(t&^ptpArray) != 0:
		n := int(r.u32())
		if !r.need(n * sonyScalarSize(t&^ptpArray)) {
			return v
		}
		v.Arr = make([]int64, n)
		for i := range v.Arr {
			v.Arr[i] = r.sonyScalar(t &^ ptpArray)
		}
	case sonyScalarSize(t) != 0:
		v.Int = r.sonyScalar(t)
	default:
		if r.err == nil {
			r.err = fmt.Errorf("unsupported datatype 0x%04X", t)
		}
	}
	return v
}

// DecodeSonyValue decodes a single value of PTP datatype typ (fake responders).
func DecodeSonyValue(typ uint16, b []byte) (SonyValue, error) {
	r := &reader{b: b}
	v := r.sonyValue(typ)
	if r.err != nil {
		return v, fmt.Errorf("ptpip: decode Sony value: %w", r.err)
	}
	return v, nil
}

// sonyProp reads one property entry (the 0x9209 entry layout, also the 0x9251 reply).
func (r *reader) sonyProp() SonyProp {
	p := SonyProp{Code: r.u16(), Type: r.u16(), Writable: r.u8() == 1, Enabled: r.u8()}
	p.Default, p.Current = r.sonyValue(p.Type), r.sonyValue(p.Type)
	switch p.Form = r.u8(); p.Form {
	case 1:
		p.Min, p.Max, p.Step = r.sonyValue(p.Type), r.sonyValue(p.Type), r.sonyValue(p.Type)
	case 2:
		for _, dst := range []*[]SonyValue{&p.Settable, &p.Readable} {
			for j, m := 0, int(r.u16()); j < m && r.err == nil; j++ {
				*dst = append(*dst, r.sonyValue(p.Type))
			}
		}
	}
	return p
}

func parseSonyProps(b []byte) ([]SonyProp, error) {
	r := &reader{b: b}
	n := r.u64()
	var out []SonyProp
	for i := uint64(0); i < n && r.err == nil; i++ {
		out = append(out, r.sonyProp())
	}
	if r.err != nil {
		return nil, fmt.Errorf("ptpip: parse Sony props: %w", r.err)
	}
	return out, nil
}

func encodeSonyScalar(b []byte, t uint16, v int64) []byte {
	switch sonyScalarSize(t) {
	case 1:
		return append(b, byte(v))
	case 2:
		return binary.LittleEndian.AppendUint16(b, uint16(v))
	case 4:
		return binary.LittleEndian.AppendUint32(b, uint32(v))
	}
	return binary.LittleEndian.AppendUint64(b, uint64(v))
}

func encodeSonyValue(typ uint16, v SonyValue) ([]byte, error) {
	switch {
	case typ == ptpString:
		b := PutString(nil, v.Str)
		if v.NoNUL && v.Str != "" {
			b = b[:len(b)-2]
			b[0]--
		}
		return b, nil
	case typ&ptpArray != 0 && sonyScalarSize(typ&^ptpArray) != 0:
		b := binary.LittleEndian.AppendUint32(nil, uint32(len(v.Arr)))
		for _, x := range v.Arr {
			b = encodeSonyScalar(b, typ&^ptpArray, x)
		}
		return b, nil
	case sonyScalarSize(typ) != 0:
		return encodeSonyScalar(nil, typ, v.Int), nil
	}
	return nil, fmt.Errorf("ptpip: unsupported datatype 0x%04X", typ)
}

// EncodeSonyProp is the inverse of reader.sonyProp (fake responders, tests);
// ok is false for an unsupported datatype.
func EncodeSonyProp(p SonyProp) (b []byte, ok bool) {
	b = binary.LittleEndian.AppendUint16(nil, p.Code)
	b = binary.LittleEndian.AppendUint16(b, p.Type)
	w := uint8(0)
	if p.Writable {
		w = 1
	}
	b = append(b, w, p.Enabled)
	ok = true
	add := func(v SonyValue) {
		e, err := encodeSonyValue(p.Type, v)
		ok = ok && err == nil
		b = append(b, e...)
	}
	add(p.Default)
	add(p.Current)
	b = append(b, p.Form)
	switch p.Form {
	case 1:
		add(p.Min)
		add(p.Max)
		add(p.Step)
	case 2:
		for _, vs := range [][]SonyValue{p.Settable, p.Readable} {
			b = binary.LittleEndian.AppendUint16(b, uint16(len(vs)))
			for _, v := range vs {
				add(v)
			}
		}
	}
	return b, ok
}

// EncodeSonyProps is the inverse of parseSonyProps (fake responders, tests).
// Props with an unsupported datatype are skipped.
func EncodeSonyProps(ps []SonyProp) []byte {
	var body []byte
	n := 0
	for _, p := range ps {
		if b, ok := EncodeSonyProp(p); ok {
			body = append(body, b...)
			n++
		}
	}
	return append(binary.LittleEndian.AppendUint64(nil, uint64(n)), body...)
}

// SonyGetProps reads all extended device properties; changedOnly asks for
// those changed since the previous call.
func (c *Client) SonyGetProps(ctx context.Context, changedOnly bool) ([]SonyProp, error) {
	var buf bytes.Buffer
	p := uint32(0)
	if changedOnly {
		p = 1
	}
	if _, err := c.Transaction(ctx, OpSonyGetAllExtDevicePropInfo, []uint32{p}, nil, &buf); err != nil {
		return nil, err
	}
	return parseSonyProps(buf.Bytes())
}

func (c *Client) sonySend(ctx context.Context, op, code, typ uint16, v SonyValue) error {
	data, err := encodeSonyValue(typ, v)
	if err != nil {
		return err
	}
	_, err = c.Transaction(ctx, op, []uint32{uint32(code)}, data, nil)
	return err
}

// SonySetProp writes a property value (0x9205).
func (c *Client) SonySetProp(ctx context.Context, code, typ uint16, v SonyValue) error {
	return c.sonySend(ctx, OpSonySetExtDevicePropValue, code, typ, v)
}

// SonyControl triggers a button/action (0x9207), e.g. shutter half-press.
func (c *Client) SonyControl(ctx context.Context, code, typ uint16, v SonyValue) error {
	return c.sonySend(ctx, OpSonyControlDevice, code, typ, v)
}

// SonyProps is a property cache keyed by code.
type SonyProps map[uint16]SonyProp

// Update merges a full or diff list and returns the codes whose Current,
// Enabled or Writable changed, or that are new.
func (s SonyProps) Update(ps []SonyProp) (changed []uint16) {
	for _, p := range ps {
		old, ok := s[p.Code]
		if !ok || old.Enabled != p.Enabled || old.Writable != p.Writable || old.Current.Int != p.Current.Int ||
			old.Current.Str != p.Current.Str || !slices.Equal(old.Current.Arr, p.Current.Arr) {
			changed = append(changed, p.Code)
		}
		s[p.Code] = p
	}
	return changed
}
