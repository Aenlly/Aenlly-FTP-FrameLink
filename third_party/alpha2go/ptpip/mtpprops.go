package ptpip

import (
	"bytes"
	"context"
	"fmt"
)

// GetObjectPropsSupported lists the object property codes valid for format (0x9801).
func (c *Client) GetObjectPropsSupported(ctx context.Context, format uint16) ([]uint16, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpMTPGetObjectPropsSupported, []uint32{uint32(format)}, nil, &buf); err != nil {
		return nil, err
	}
	r := &reader{b: buf.Bytes()}
	codes := r.u16array()
	if err := r.done(); err != nil {
		return nil, fmt.Errorf("ptpip: parse supported object props: %w", err)
	}
	return codes, nil
}

// ObjectPropDesc is the MTP ObjectPropDesc dataset (0x9802). Form: 0 none,
// 1 range (Min/Max/Step), 2 enum (Enum), 3 DateTime, 4 fixed-length array
// (MaxLen), 5 regular expression (Regex), 6 byte array (MaxLen),
// 0xFF long string (MaxLen).
type ObjectPropDesc struct {
	Code, Type     uint16
	Writable       bool
	Default        SonyValue
	Group          uint32
	Form           uint8
	Min, Max, Step SonyValue
	Enum           []SonyValue
	MaxLen         uint32
	Regex          string
}

// done returns the read error, or an error if bytes are left over.
func (r *reader) done() error {
	if r.err == nil && r.off != len(r.b) {
		return fmt.Errorf("%d trailing bytes", len(r.b)-r.off)
	}
	return r.err
}

func parseObjectPropDesc(b []byte) (ObjectPropDesc, error) {
	r := &reader{b: b}
	d := ObjectPropDesc{Code: r.u16(), Type: r.u16(), Writable: r.u8() == 1}
	d.Default = r.sonyValue(d.Type)
	d.Group = r.u32()
	switch d.Form = r.u8(); d.Form {
	case 0, 3:
	case 1:
		d.Min, d.Max, d.Step = r.sonyValue(d.Type), r.sonyValue(d.Type), r.sonyValue(d.Type)
	case 2:
		for i, n := 0, int(r.u16()); i < n && r.err == nil; i++ {
			d.Enum = append(d.Enum, r.sonyValue(d.Type))
		}
	case 4:
		d.MaxLen = uint32(r.u16())
	case 5:
		d.Regex = r.str()
	case 6, 0xFF:
		d.MaxLen = r.u32()
	default:
		if r.err == nil {
			r.err = fmt.Errorf("unknown form 0x%02X", d.Form)
		}
	}
	if err := r.done(); err != nil {
		return ObjectPropDesc{}, fmt.Errorf("ptpip: parse object prop desc: %w", err)
	}
	return d, nil
}

// GetObjectPropDesc describes property prop of objects of format (0x9802).
func (c *Client) GetObjectPropDesc(ctx context.Context, prop, format uint16) (ObjectPropDesc, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpMTPGetObjectPropDesc, []uint32{uint32(prop), uint32(format)}, nil, &buf); err != nil {
		return ObjectPropDesc{}, err
	}
	return parseObjectPropDesc(buf.Bytes())
}

// GetObjectPropValue reads one property of an object (0x9803); typ is its datatype.
func (c *Client) GetObjectPropValue(ctx context.Context, handle uint32, prop, typ uint16) (SonyValue, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpMTPGetObjectPropValue, []uint32{handle, uint32(prop)}, nil, &buf); err != nil {
		return SonyValue{}, err
	}
	r := &reader{b: buf.Bytes()}
	v := r.sonyValue(typ)
	if err := r.done(); err != nil {
		return SonyValue{}, fmt.Errorf("ptpip: parse object prop value: %w", err)
	}
	return v, nil
}

// ObjectPropListEntry is one element of the 0x9805 reply.
type ObjectPropListEntry struct {
	Handle     uint32
	Code, Type uint16
	Value      SonyValue
}

func parseObjectPropList(b []byte) ([]ObjectPropListEntry, error) {
	r := &reader{b: b}
	n := int(r.u32())
	if !r.need(n * 8) { // every entry has at least 8 bytes
		return nil, fmt.Errorf("ptpip: parse object prop list: %w", r.err)
	}
	out := make([]ObjectPropListEntry, 0, n)
	for i := 0; i < n && r.err == nil; i++ {
		e := ObjectPropListEntry{Handle: r.u32(), Code: r.u16(), Type: r.u16()}
		e.Value = r.sonyValue(e.Type)
		out = append(out, e)
	}
	if err := r.done(); err != nil {
		return nil, fmt.Errorf("ptpip: parse object prop list: %w", err)
	}
	return out, nil
}

// GetObjectPropList reads properties of handle (0x9805); prop 0xFFFFFFFF means all.
func (c *Client) GetObjectPropList(ctx context.Context, handle uint32, format uint16, prop, group, depth uint32) ([]ObjectPropListEntry, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpMTPGetObjPropList, []uint32{handle, uint32(format), prop, group, depth}, nil, &buf); err != nil {
		return nil, err
	}
	return parseObjectPropList(buf.Bytes())
}
