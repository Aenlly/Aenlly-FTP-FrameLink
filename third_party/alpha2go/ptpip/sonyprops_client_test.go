package ptpip_test

import (
	"context"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func TestSonyGetSetProps(t *testing.T) {
	_, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true})
	ctx := context.Background()
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	ps, err := c.SonyGetProps(ctx, false)
	if err != nil || len(ps) != 3 {
		t.Fatalf("%d props, %v", len(ps), err)
	}
	cache := ptpip.SonyProps{}
	cache.Update(ps)

	if err := c.SonySetProp(ctx, 0x500A, 4, ptpip.SonyValue{Int: 0x8002}); err != nil {
		t.Fatal(err)
	}
	select {
	case ev := <-c.Events():
		if ev.Code != ptpip.EvSonyPropChanged || len(ev.Params) != 1 || ev.Params[0] != 0x500A {
			t.Fatalf("event %+v", ev)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no EvSonyPropChanged")
	}
	if ps, err = c.SonyGetProps(ctx, true); err != nil {
		t.Fatal(err)
	}
	if got := cache.Update(ps); len(got) != 1 || got[0] != 0x500A || cache[0x500A].Current.Int != 0x8002 {
		t.Fatalf("changed %v", got)
	}

	err = c.SonySetProp(ctx, 0xD07B, 0xFFFF, ptpip.SonyValue{Str: "x"})
	if !ptpip.IsCode(err, 0x200F) {
		t.Fatalf("read-only set: %v", err)
	}
	if err = c.SonySetProp(ctx, 0x1234, 4, ptpip.SonyValue{}); !ptpip.IsCode(err, 0x200A) {
		t.Fatalf("unknown prop set: %v", err)
	}
	if err = c.SonyControl(ctx, 0xD2C1, 4, ptpip.SonyValue{Int: 2}); err != nil {
		t.Fatal(err)
	}
}

func TestSonyGetProp(t *testing.T) {
	_, c := connect(t, ptpiptest.Options{Dir: card(t), Sony: true})
	ctx := context.Background()
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	p, err := c.SonyGetProp(ctx, 0x500A)
	if err != nil || p.Code != 0x500A || p.Current.Int != 0x8001 || len(p.Settable) != 3 {
		t.Fatalf("%+v, %v", p, err)
	}
	if _, err = c.SonyGetProp(ctx, 0x1234); !ptpip.IsCode(err, 0x200A) {
		t.Fatalf("unknown prop: %v", err)
	}
}

func TestMTPObjectProps(t *testing.T) {
	dir := card(t)
	_, c := connect(t, ptpiptest.Options{Dir: dir, Sony: true})
	ctx := context.Background()
	if err := c.OpenSession(ctx, 1); err != nil {
		t.Fatal(err)
	}
	hs, err := c.GetObjectHandles(ctx, 0xFFFFFFFF, 0, ptpip.ParentAll)
	if err != nil || len(hs) == 0 {
		t.Fatalf("%v, %v", hs, err)
	}
	var h uint32
	for _, x := range hs {
		if oi, err := c.GetObjectInfo(ctx, x); err == nil && oi.ObjectFormat != ptpip.FormatAssociation {
			h = x
			break
		}
	}
	oi, _ := c.GetObjectInfo(ctx, h)
	es, err := c.GetObjectPropList(ctx, h, 0, 0xFFFFFFFF, 0, 0)
	if err != nil || len(es) != 4 || es[3].Code != 0xDC07 || es[3].Value.Str != oi.Filename || es[2].Value.Int != int64(oi.CompressedSize) {
		t.Fatalf("%+v, %v", es, err)
	}
	if es, err = c.GetObjectPropList(ctx, h, 0, 0xDC02, 0, 0); err != nil || len(es) != 1 {
		t.Fatalf("%+v, %v", es, err)
	}
	if v, err := c.GetObjectPropValue(ctx, h, 0xDC07, 0xFFFF); err != nil || v.Str != oi.Filename {
		t.Fatalf("%+v, %v", v, err)
	}
	if _, err := c.GetObjectPropList(ctx, 0x7777, 0, 0xFFFFFFFF, 0, 0); !ptpip.IsCode(err, ptpip.RespInvalidObjectHandle) {
		t.Fatal(err)
	}
	codes, err := c.GetObjectPropsSupported(ctx, 0x3801)
	if err != nil || len(codes) != 4 {
		t.Fatalf("%X, %v", codes, err)
	}
	d, err := c.GetObjectPropDesc(ctx, 0xDC04, 0x3801)
	if err != nil || d.Type != 8 || d.Writable {
		t.Fatalf("%+v, %v", d, err)
	}
}
