// Command ptpprobe talks to a camera over PTP/IP and runs single steps: list,
// download, read and set properties, capture, live view and more. Without
// step flags it shows what the camera offers.
//
//	ptpprobe -host 192.168.1.50 [-sony=auto|on|off] [-list 20] [-get HANDLE -out DIR] [-events 10s]
//
// With Access Authentication on, add the values the camera shows:
//
//	PTPPROBE_SSH_PASSWORD=... ptpprobe -host 192.168.1.50 -ssh-user USER -ssh-fingerprint SHA256:...
package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

func main() {
	host := flag.String("host", "", "camera IP or ip:port (required)")
	sony := flag.String("sony", "auto", "Sony SDIO handshake: auto|on|off")
	list := flag.Int("list", 20, "list up to N objects per storage (0 = none, -1 = all)")
	get := flag.Uint("get", 0, "download this object handle")
	out := flag.String("out", ".", "directory for -get")
	timeout := flag.Duration("timeout", 30*time.Second, "I/O timeout")
	events := flag.Duration("events", 10*time.Second, "how long to listen for camera events at the end")
	sshUser := flag.String("ssh-user", "", "Access Authentication user; tunnels PTP/IP through SSH (password in $PTPPROBE_SSH_PASSWORD)")
	sshFP := flag.String("ssh-fingerprint", "", "Access Authentication fingerprint shown on the camera")
	rawOps := flag.String("op", "", "send raw operations after the handshake and hex-dump the replies, e.g. \"0x923B:1;0x923C:1,0,0\" (data saved to -out)")
	getOp := flag.String("get-op", "auto", "download operation for -get: auto|object|sony (Sony GetPartialLargeObject 0x9211)")
	xfer := flag.String("sony-xfer", "", "send Sony SDIO_SetContentsTransferMode (0x9212) with these comma-separated parameters before listing, e.g. 2,1,0")
	props := flag.Bool("props", false, "after the Sony handshake, list all extended device properties (0x9209)")
	getprop := flag.String("getprop", "", "read single Sony extended properties (0x9251), e.g. \"0x500A,0xD20F\"")
	info := flag.Bool("info", false, "after the Sony handshake, print time zone, operation results, FTP server names, display strings and device description")
	backup := flag.String("backup", "", "save the Sony camera settings backup to this file")
	set := flag.String("set", "", "set Sony extended properties, e.g. \"0x500A=0x8002,0xD20F=5600\", then print what changed")
	capture := flag.String("capture", "off", "take a picture after the handshake: off|af|noaf")
	fetchCaptured := flag.Bool("fetch-captured", false, "after -capture, download the images the camera queued for the host (0xD215, handle 0xFFFFC001) to -out")
	control := flag.String("control", "", "send Sony controls (0x9207), e.g. \"0xD2C8=2,0xD2C8=1\", 100 ms apart")
	liveview := flag.Int("liveview", 0, "save this many Sony live-view frames as JPEGs to -out")
	osd := flag.String("osd", "", "save the Sony on-screen display image (PNG) to this file; switches the OSD mode on for the capture")
	setTime := flag.String("set-time", "", "set the Sony camera clock: \"now\" or \"YYYYMMDDThhmmss.s,+hhmm,0|1\" (date-time, standard UTC offset, DST)")
	compressed := flag.String("compressed", "", "save the thumbnail and screennail (0x923E) of every file of a Sony content to -out: \"SLOT:CONTENTID\"")
	del := flag.String("delete", "", "DELETE a Sony content from the card: \"SLOT:CONTENTID\"")
	importLUT := flag.String("import-lut", "", "import a LUT file into a Sony user slot: \"N:PATH\" (N = 1..16)")
	restore := flag.String("restore", "", "restore a Sony settings backup (see -backup) from this file")
	objProps := flag.String("objprops", "", "after the handshake, print MTP object properties (0x9801, 0x9802) for this object format, e.g. 0x3801")
	propList := flag.Uint("proplist", 0, "print all MTP object properties (0x9805) of this object handle (after listing)")
	flag.Parse()
	if *capture != "off" && *capture != "af" && *capture != "noaf" {
		fmt.Fprintln(os.Stderr, "bad -capture (want off|af|noaf)")
		os.Exit(2)
	}
	var xferParams []uint32
	if *xfer != "" {
		for _, f := range strings.Split(*xfer, ",") {
			v, err := strconv.ParseUint(strings.TrimSpace(f), 0, 32)
			if err != nil {
				fmt.Fprintln(os.Stderr, "bad -sony-xfer:", err)
				os.Exit(2)
			}
			xferParams = append(xferParams, uint32(v))
		}
	}
	if *host == "" {
		flag.Usage()
		os.Exit(2)
	}
	opts := ptpip.Options{Name: "alpha2go-probe", IOTimeout: *timeout}
	if *sshUser != "" {
		sd := ptpip.NewSSHDialer(ptpip.SSHConfig{User: *sshUser, Password: os.Getenv("PTPPROBE_SSH_PASSWORD"), Fingerprint: *sshFP})
		defer sd.Close()
		opts.DialContext = sd.DialContext
	}
	if err := run(*host, opts, *sony, xferParams, *rawOps, *props, *getprop, *info, *backup, *osd, *setTime, *compressed, *del, *importLUT, *restore, *set, *capture, *fetchCaptured, *control, *liveview, *list, uint32(*get), *objProps, uint32(*propList), *getOp, *out, *events); err != nil {
		fmt.Fprintln(os.Stderr, "ERROR:", err)
		os.Exit(1)
	}
}

func step(name string) func(error) error {
	fmt.Printf("== %s\n", name)
	return func(err error) error {
		if err != nil {
			fmt.Printf("   FAILED: %v\n", err)
		}
		return err
	}
}

func run(host string, opts ptpip.Options, sony string, xfer []uint32, rawOps string, props bool, getprop string, info bool, backup, osd, setTime, compressed, del, importLUT, restore, set, capture string, fetchCaptured bool, control string, liveview, list int, get uint32, objProps string, propList uint32, getOp, out string, events time.Duration) error {
	ctx := context.Background()
	done := step("connect (PTP/IP handshake on port 15740)")
	c, err := ptpip.Dial(ctx, host, opts)
	var ife *ptpip.InitFailError
	if errors.As(err, &ife) {
		fmt.Println("   The camera refused the connection. Likely Access Authentication is on or the host is not paired.")
	}
	if done(err) != nil {
		return err
	}
	defer c.Close()
	fmt.Printf("   connection #%d, responder name %q\n", c.ConnNumber, c.ResponderName)

	done = step("GetDeviceInfo")
	di, err := c.GetDeviceInfo(ctx)
	if done(err) != nil {
		return err
	}
	fmt.Printf("   %s %s, firmware %s, serial %s\n", di.Manufacturer, di.Model, di.DeviceVersion, di.SerialNumber)
	fmt.Printf("   vendor extension 0x%X v%d %q, functional mode %d\n", di.VendorExtensionID, di.VendorExtensionVersion, di.VendorExtensionDesc, di.FunctionalMode)
	printCodes("operations", di.Operations, ptpip.OpName)
	printCodes("events", di.Events, ptpip.EventName)
	fmt.Printf("   %d device properties, %d image formats\n", len(di.DeviceProperties), len(di.ImageFormats))

	done = step("OpenSession")
	if done(c.OpenSession(ctx, 1)) != nil {
		return c.Err()
	}

	if sony == "on" || (sony == "auto" && di.IsSony()) {
		done = step("Sony SDIO handshake")
		ext, err := c.SonyHandshake(ctx)
		if done(err) == nil {
			fmt.Printf("   protocol 0x%X, %d extended properties, %d controls\n", ext.ProtocolVersion, len(ext.Properties), len(ext.Controls))
			printCodes("extended codes", append(append([]uint16{}, ext.Properties...), ext.Controls...), ptpip.OpName)
		}
		select {
		case <-c.Done():
			return fmt.Errorf("connection closed after Sony handshake: %w", c.Err())
		default:
		}
	}

	if props || set != "" {
		if err := runProps(ctx, c, props, set); err != nil {
			return err
		}
	}

	if getprop != "" {
		if err := runGetProps(ctx, c, getprop); err != nil {
			return err
		}
	}

	if info {
		runInfo(ctx, c)
	}

	if backup != "" {
		done := step("Sony settings backup to " + backup)
		err := saveSettings(ctx, c, backup)
		if done(err) != nil {
			return err
		}
	}

	if osd != "" {
		if err := runOSD(ctx, c, osd); err != nil {
			return err
		}
	}

	if setTime != "" {
		if err := runSetTime(ctx, c, setTime); err != nil {
			return err
		}
	}

	if compressed != "" {
		if err := runCompressed(ctx, c, compressed, out); err != nil {
			return err
		}
	}

	if del != "" {
		if err := runDelete(ctx, c, del); err != nil {
			return err
		}
	}

	if importLUT != "" {
		if err := runImportLUT(ctx, c, importLUT); err != nil {
			return err
		}
	}

	if restore != "" {
		if err := runRestore(ctx, c, restore); err != nil {
			return err
		}
	}

	if rawOps != "" {
		if err := runRawOps(ctx, c, rawOps, out); err != nil {
			return err
		}
	}

	if control != "" {
		if err := runControls(ctx, c, control); err != nil {
			return err
		}
	}

	if capture != "off" {
		done = step("Sony capture (autofocus: " + capture + ")")
		if done(c.SonyCapture(ctx, capture == "af")) != nil {
			return c.Err()
		}
	}

	if fetchCaptured {
		if err := runFetchCaptured(ctx, c, out); err != nil {
			return err
		}
	}

	if liveview > 0 {
		if err := runLiveView(ctx, c, liveview, out); err != nil {
			return err
		}
	}

	if xfer != nil {
		done = step(fmt.Sprintf("Sony SetContentsTransferMode %v", xfer))
		resp, err := c.Transaction(ctx, ptpip.OpSonySetContentsTransferMode, xfer, nil, nil)
		if done(err) == nil {
			fmt.Printf("   response params %v\n", resp)
		}
		// The camera may answer with events (e.g. ContentsTransferEvent) or need a moment.
		wait := time.After(2 * time.Second)
	drain:
		for {
			select {
			case ev := <-c.Events():
				fmt.Printf("   event %s params=%v\n", ptpip.EventName(ev.Code), ev.Params)
			case <-wait:
				break drain
			}
		}
	}

	if objProps != "" {
		f, err := strconv.ParseUint(objProps, 0, 16)
		if err != nil {
			return fmt.Errorf("bad -objprops %q: %w", objProps, err)
		}
		runObjProps(ctx, c, uint16(f))
	}

	done = step("GetStorageIDs")
	ids, err := c.GetStorageIDs(ctx)
	if done(err) != nil {
		return err
	}
	for _, id := range ids {
		present := id&0xFFFF != 0
		fmt.Printf("   storage 0x%08X present=%v", id, present)
		if present {
			if si, err := c.GetStorageInfo(ctx, id); err == nil {
				fmt.Printf(" %q capacity=%d free=%d", si.Description, si.MaxCapacity, si.FreeSpace)
			} else {
				fmt.Printf(" (GetStorageInfo: %v)", err)
			}
		}
		fmt.Println()
		if !present || list == 0 {
			continue
		}
		done = step(fmt.Sprintf("GetObjectHandles storage 0x%08X", id))
		hs, err := c.GetObjectHandles(ctx, id, 0, ptpip.ParentAll)
		if err != nil {
			done(err)
			fmt.Println("   retrying with parent=root")
			hs, err = c.GetObjectHandles(ctx, id, 0, ptpip.ParentRoot)
		}
		if done(err) != nil {
			continue
		}
		fmt.Printf("   %d objects\n", len(hs))
		sort.Slice(hs, func(i, j int) bool { return hs[i] < hs[j] })
		for i, h := range hs {
			if list >= 0 && i >= list {
				fmt.Printf("   ... (%d more)\n", len(hs)-i)
				break
			}
			oi, err := c.GetObjectInfo(ctx, h)
			if err != nil {
				fmt.Printf("   0x%08X GetObjectInfo: %v\n", h, err)
				continue
			}
			kind := "file"
			if oi.IsFolder() {
				kind = "dir "
			}
			fmt.Printf("   0x%08X %s %-24s size=%-10d format=0x%04X parent=0x%08X captured=%q modified=%q\n",
				h, kind, oi.Filename, oi.CompressedSize, oi.ObjectFormat, oi.ParentObject, oi.CaptureDate, oi.ModificationDate)
		}
	}

	if propList != 0 {
		done = step(fmt.Sprintf("GetObjPropList 0x%08X (0x9805)", propList))
		es, err := c.GetObjectPropList(ctx, propList, 0, 0xFFFFFFFF, 0, 0)
		if done(err) == nil {
			for _, e := range es {
				fmt.Printf("   0x%08X 0x%04X %s = %s\n", e.Handle, e.Code, typeName(e.Type), fmtValue(ptpip.SonyProp{Type: e.Type}, e.Value))
			}
		}
	}

	if get != 0 {
		done = step(fmt.Sprintf("download object 0x%08X", get))
		oi, err := c.GetObjectInfo(ctx, get)
		if done(err) != nil {
			return err
		}
		name := filepath.Join(out, filepath.Base(oi.Filename))
		f, err := os.Create(name)
		if err != nil {
			return err
		}
		start := time.Now()
		var n int64
		switch getOp {
		case "object":
			n, err = c.GetObject(ctx, get, f, nil)
		case "sony":
			for size := int64(oi.CompressedSize); n < size; {
				var got uint32
				got, err = c.SonyGetPartialLargeObject(ctx, get, n, 4<<20, f)
				n += int64(got)
				if err != nil || got == 0 {
					break
				}
			}
		default:
			n, err = c.DownloadObject(ctx, di, get, int64(oi.CompressedSize), f, nil)
		}
		f.Close()
		if done(err) != nil {
			return err
		}
		fmt.Printf("   wrote %s (%d bytes, %.1f MB/s)\n", name, n, float64(n)/1e6/time.Since(start).Seconds())
	}

	step(fmt.Sprintf("events (listening %s; take a photo now to see capture events)", events))
	timer := time.After(events)
	for {
		select {
		case ev, ok := <-c.Events():
			if !ok {
				return nil
			}
			fmt.Printf("   event %s params=%v\n", ptpip.EventName(ev.Code), ev.Params)
		case <-timer:
			c.CloseSession(ctx)
			fmt.Println("== done")
			return nil
		}
	}
}

func printCodes(label string, codes []uint16, name func(uint16) string) {
	fmt.Printf("   %d %s:\n", len(codes), label)
	sorted := append([]uint16(nil), codes...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	for _, c := range sorted {
		fmt.Printf("     %s\n", name(c))
	}
}

var typeNames = map[uint16]string{1: "i8", 2: "u8", 3: "i16", 4: "u16", 5: "i32", 6: "u32", 7: "i64", 8: "u64", 0xFFFF: "str"}

func fmtValue(p ptpip.SonyProp, v ptpip.SonyValue) string {
	switch {
	case p.Type == 0xFFFF:
		return strconv.Quote(v.Str)
	case p.Type&0x4000 != 0:
		return fmt.Sprint(v.Arr)
	case p.Type == 8:
		return strconv.FormatUint(uint64(v.Int), 10)
	}
	return strconv.FormatInt(v.Int, 10)
}

func typeName(t uint16) string {
	if name, ok := typeNames[t]; ok {
		return name
	}
	return fmt.Sprintf("0x%04X", t)
}

func fmtProp(p ptpip.SonyProp) string {
	name := typeName(p.Type)
	rw := 0
	if p.Writable {
		rw = 1
	}
	s := fmt.Sprintf("0x%04X %s rw=%d en=%d cur=%s", p.Code, name, rw, p.Enabled, fmtValue(p, p.Current))
	switch p.Form {
	case 1:
		s += fmt.Sprintf(" range %s..%s/%s", fmtValue(p, p.Min), fmtValue(p, p.Max), fmtValue(p, p.Step))
	case 2:
		var vs []string
		for _, v := range p.Settable {
			vs = append(vs, fmtValue(p, v))
		}
		s += fmt.Sprintf(" enum %d settable [%s]", len(p.Settable), strings.Join(vs, " "))
	}
	return s
}

// runProps implements -props and -set.
func runProps(ctx context.Context, c *ptpip.Client, list bool, set string) error {
	done := step("Sony extended device properties (0x9209)")
	ps, err := c.SonyGetProps(ctx, false)
	if done(err) != nil {
		return err
	}
	byCode := map[uint16]ptpip.SonyProp{}
	for _, p := range ps {
		byCode[p.Code] = p
		if list {
			fmt.Println("   " + fmtProp(p))
		}
	}
	if set == "" {
		return nil
	}
	for _, item := range strings.Split(set, ",") {
		codeStr, valStr, ok := strings.Cut(strings.TrimSpace(item), "=")
		code, err := strconv.ParseUint(codeStr, 0, 16)
		if !ok || err != nil {
			return fmt.Errorf("bad -set %q (want CODE=VALUE)", item)
		}
		p, ok := byCode[uint16(code)]
		if !ok {
			return fmt.Errorf("-set: camera reports no property 0x%04X", code)
		}
		var v ptpip.SonyValue
		if p.Type == 0xFFFF {
			v.Str = valStr
		} else if v.Int, err = strconv.ParseInt(valStr, 0, 64); err != nil {
			return fmt.Errorf("bad -set %q: %w", item, err)
		}
		done = step(fmt.Sprintf("set 0x%04X = %s", code, valStr))
		if done(c.SonySetProp(ctx, p.Code, p.Type, v)) != nil {
			return c.Err()
		}
	}
	time.Sleep(500 * time.Millisecond)
	done = step("changed properties")
	ps, err = c.SonyGetProps(ctx, true)
	if done(err) != nil {
		return err
	}
	for _, p := range ps {
		fmt.Println("   " + fmtProp(p))
	}
	return nil
}

// runControls implements -control.
func runControls(ctx context.Context, c *ptpip.Client, spec string) error {
	for i, item := range strings.Split(spec, ",") {
		codeStr, valStr, ok := strings.Cut(strings.TrimSpace(item), "=")
		code, err := strconv.ParseUint(codeStr, 0, 16)
		if !ok || err != nil {
			return fmt.Errorf("bad -control %q (want CODE=VALUE)", item)
		}
		typ, _, known := ptpip.SonyControlInfo(uint16(code))
		if !known {
			return fmt.Errorf("-control: unknown control 0x%04X", code)
		}
		var v ptpip.SonyValue
		if typ == 0xFFFF {
			v.Str = valStr
		} else if v.Int, err = strconv.ParseInt(valStr, 0, 64); err != nil {
			return fmt.Errorf("bad -control %q: %w", item, err)
		}
		if i > 0 {
			time.Sleep(100 * time.Millisecond)
		}
		done := step(fmt.Sprintf("control 0x%04X = %s", code, valStr))
		if done(c.SonyControl(ctx, uint16(code), typ, v)) != nil {
			return c.Err()
		}
	}
	return nil
}

// runRawOps sends "OP:P1,P2;OP2" and hex-dumps each reply.
func runRawOps(ctx context.Context, c *ptpip.Client, spec, out string) error {
	for i, item := range strings.Split(spec, ";") {
		if strings.TrimSpace(item) == "" {
			continue
		}
		opStr, paramStr, _ := strings.Cut(strings.TrimSpace(item), ":")
		op, err := strconv.ParseUint(opStr, 0, 16)
		if err != nil {
			return fmt.Errorf("bad -op %q: %w", item, err)
		}
		var params []uint32
		for _, f := range strings.Split(paramStr, ",") {
			if f = strings.TrimSpace(f); f == "" {
				continue
			}
			v, err := strconv.ParseUint(f, 0, 32)
			if err != nil {
				return fmt.Errorf("bad -op %q: %w", item, err)
			}
			params = append(params, uint32(v))
		}
		done := step(fmt.Sprintf("raw %s %v", ptpip.OpName(uint16(op)), params))
		var buf bytes.Buffer
		resp, err := c.Transaction(ctx, uint16(op), params, nil, &buf)
		if done(err) == nil {
			fmt.Printf("   response params %v, %d data bytes\n", resp, buf.Len())
		}
		if buf.Len() > 0 {
			name := filepath.Join(out, fmt.Sprintf("op%d-0x%04X.bin", i, op))
			if err := os.WriteFile(name, buf.Bytes(), 0o644); err != nil {
				fmt.Printf("   (not saved: %v)\n", err)
			}
			dump := buf.Bytes()
			if len(dump) > 512 {
				dump = dump[:512]
			}
			fmt.Print(hex.Dump(dump))
		}
		select {
		case <-c.Done():
			return fmt.Errorf("connection closed: %w", c.Err())
		default:
		}
	}
	return nil
}

// runLiveView implements -liveview.
func runLiveView(ctx context.Context, c *ptpip.Client, n int, out string) error {
	done := step("Sony live view")
	lv, err := c.SonyOpenLiveView(ctx)
	if done(err) != nil {
		return err
	}
	defer lv.Close()
	start := time.Now()
	for i := 0; i < n; i++ {
		f, err := lv.Next()
		if done(err) != nil {
			return err
		}
		name := filepath.Join(out, fmt.Sprintf("liveview-%03d.jpg", i))
		if err := os.WriteFile(name, f.JPEG, 0o644); err != nil {
			return err
		}
		if err := os.WriteFile(strings.TrimSuffix(name, ".jpg")+".meta", f.Meta, 0o644); err != nil {
			return err
		}
		fmt.Printf("   %s: jpeg %d bytes (starts % x), meta %d bytes\n", name, len(f.JPEG), f.JPEG[:min(4, len(f.JPEG))], len(f.Meta))
		fmt.Println("   " + fmtLiveViewMeta(f.Meta))
	}
	fmt.Printf("   %d frames in %s\n", n, time.Since(start).Round(time.Millisecond))
	return nil
}

// fmtLiveViewMeta renders the overlay metadata on one line.
func fmtLiveViewMeta(meta []byte) string {
	info, err := ptpip.ParseSonyLiveViewMeta(meta)
	if err != nil {
		return "meta: " + err.Error()
	}
	s := fmt.Sprintf("meta v0x%02X", info.Version)
	if l := info.Level; l != nil {
		s += fmt.Sprintf(" level state=%d x=%d y=%d z=%d", l.State, l.X, l.Y, l.Z)
	}
	for _, g := range []struct {
		name string
		fs   []ptpip.SonyFrame
	}{{"focus", info.Focus}, {"face", info.Faces}, {"track", info.Tracking}} {
		for _, f := range g.fs {
			s += fmt.Sprintf(" %s[%d/%d %d,%d %dx%d of %dx%d]", g.name, f.Type, f.State, f.X, f.Y, f.W, f.H, f.XMax, f.YMax)
		}
	}
	return s
}

// runObjProps implements -objprops; a failing descriptor does not stop the next one.
func runObjProps(ctx context.Context, c *ptpip.Client, format uint16) {
	done := step(fmt.Sprintf("GetObjectPropsSupported format 0x%04X (0x9801)", format))
	codes, err := c.GetObjectPropsSupported(ctx, format)
	if done(err) != nil {
		return
	}
	for _, code := range codes {
		done = step(fmt.Sprintf("GetObjectPropDesc 0x%04X (0x9802)", code))
		d, err := c.GetObjectPropDesc(ctx, code, format)
		if done(err) == nil {
			fmt.Println("   " + fmtObjectPropDesc(d))
		}
	}
}

func fmtObjectPropDesc(d ptpip.ObjectPropDesc) string {
	p := ptpip.SonyProp{Type: d.Type}
	s := fmt.Sprintf("0x%04X %s writable=%v default=%s group=%d form=%d", d.Code, typeName(d.Type), d.Writable, fmtValue(p, d.Default), d.Group, d.Form)
	switch d.Form {
	case 1:
		s += fmt.Sprintf(" range %s..%s/%s", fmtValue(p, d.Min), fmtValue(p, d.Max), fmtValue(p, d.Step))
	case 2:
		var vs []string
		for _, v := range d.Enum {
			vs = append(vs, fmtValue(p, v))
		}
		s += fmt.Sprintf(" enum [%s]", strings.Join(vs, " "))
	case 4, 6, 0xFF:
		s += fmt.Sprintf(" maxlen=%d", d.MaxLen)
	case 5:
		s += fmt.Sprintf(" regex=%q", d.Regex)
	}
	return s
}

// runGetProps implements -getprop.
func runGetProps(ctx context.Context, c *ptpip.Client, spec string) error {
	for _, f := range strings.Split(spec, ",") {
		code, err := strconv.ParseUint(strings.TrimSpace(f), 0, 16)
		if err != nil {
			return fmt.Errorf("bad -getprop %q: %w", f, err)
		}
		done := step(fmt.Sprintf("Sony get property 0x%04X (0x9251)", code))
		p, err := c.SonyGetProp(ctx, uint16(code))
		if done(err) != nil {
			return c.Err()
		}
		fmt.Println("   " + fmtProp(p))
	}
	return nil
}

// runInfo implements -info; a failing step does not stop the next one.
func runInfo(ctx context.Context, c *ptpip.Client) {
	done := step("Sony time zone (0x9248)")
	tz, err := c.SonyGetTimeZone(ctx)
	if done(err) == nil {
		t, terr := tz.Time()
		fmt.Printf("   %+v time=%v err=%v\n", tz, t, terr)
	}
	done = step("Sony operation results (0x922F)")
	ops, err := c.SonyGetOperationResults(ctx)
	if done(err) == nil {
		for _, o := range ops {
			fmt.Printf("   0x%04X via %s\n", o.Code, ptpip.OpName(o.Op))
		}
	}
	done = step("Sony FTP server names (0x920E)")
	names, err := c.SonyGetFTPServerNames(ctx)
	if done(err) == nil {
		fmt.Printf("   %q\n", names)
	}
	done = step("Sony display strings (0x9215)")
	lists, err := c.SonyGetDisplayStrings(ctx, 0)
	if done(err) == nil {
		for _, l := range lists {
			fmt.Printf("   type 0x%X (datatype %d), %d items\n", l.Type, l.DataType, len(l.Items))
			for _, it := range l.Items {
				fmt.Printf("     %d = %q\n", it.Value, it.Text)
			}
		}
	}
	done = step("Sony device description (0x923A)")
	xml, err := c.SonyGetDeviceDescription(ctx)
	if done(err) == nil {
		fmt.Printf("   %d bytes\n%s\n", len(xml), xml[:min(len(xml), 200)])
	}
}

func saveSettings(ctx context.Context, c *ptpip.Client, name string) error {
	f, err := os.Create(name)
	if err != nil {
		return err
	}
	n, err := c.SonyDownloadSettings(ctx, f)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		fmt.Printf("   %d bytes\n", n)
	}
	return err
}

// printEvents prints the events arriving within d (the camera reports some
// results, e.g. of a LUT import, only that way).
func printEvents(c *ptpip.Client, d time.Duration) {
	step(fmt.Sprintf("events (%s)", d))
	timer := time.After(d)
	for {
		select {
		case ev, ok := <-c.Events():
			if !ok {
				return
			}
			fmt.Printf("   event %s params=%v\n", ptpip.EventName(ev.Code), ev.Params)
		case <-timer:
			return
		}
	}
}

// runOSD implements -osd; the OSD mode is switched off again afterwards
// (the camera sends garbage for the image while it is off).
func runOSD(ctx context.Context, c *ptpip.Client, name string) (err error) {
	done := step("Sony OSD image (0x9238) to " + name)
	if done(c.SonySetOSDMode(ctx, true)) != nil {
		return c.Err()
	}
	defer func() {
		off := step("Sony OSD mode off")
		err = errors.Join(err, off(c.SonySetOSDMode(context.WithoutCancel(ctx), false)))
	}()
	f, err := c.SonyGetOSDImage(ctx)
	if done(err) != nil {
		return err
	}
	if err := os.WriteFile(name, f.JPEG, 0o644); err != nil {
		return err
	}
	fmt.Printf("   %d bytes (starts % x), meta %d bytes: %x\n", len(f.JPEG), f.JPEG[:min(4, len(f.JPEG))], len(f.Meta), f.Meta)
	return nil
}

// runSetTime implements -set-time.
func runSetTime(ctx context.Context, c *ptpip.Client, spec string) error {
	var tz ptpip.SonyTimeZone
	if spec == "now" {
		tz = ptpip.SonyTimeZoneAt(time.Now())
	} else {
		f := strings.Split(spec, ",")
		if len(f) != 3 || (f[2] != "0" && f[2] != "1") {
			return fmt.Errorf("bad -set-time %q (want now or YYYYMMDDThhmmss.s,+hhmm,0|1)", spec)
		}
		tz = ptpip.SonyTimeZone{DateTime: f[0], Area: f[1], DST: f[2] == "1"}
	}
	show := func(label string) {
		done := step("Sony time zone " + label)
		cur, err := c.SonyGetTimeZone(ctx)
		if done(err) == nil {
			fmt.Printf("   %+v\n", cur)
		}
	}
	show("before")
	done := step(fmt.Sprintf("Sony set time zone (0x9249) %+v", tz))
	if done(c.SonySetTimeZone(ctx, tz)) != nil {
		return c.Err()
	}
	show("after")
	printEvents(c, 5*time.Second)
	return nil
}

// runCompressed implements -compressed.
func runCompressed(ctx context.Context, c *ptpip.Client, spec, out string) error {
	slotStr, idStr, ok := strings.Cut(spec, ":")
	slot, err1 := strconv.ParseUint(slotStr, 0, 32)
	id, err2 := strconv.ParseUint(idStr, 0, 32)
	if !ok || err1 != nil || err2 != nil {
		return fmt.Errorf("bad -compressed %q (want SLOT:CONTENTID)", spec)
	}
	done := step(fmt.Sprintf("Sony thumbnail and screennail of content 0x%X in slot %d (0x923E)", id, slot))
	cs, err := c.SonyListAllContents(ctx, uint32(slot))
	if err != nil {
		return done(err)
	}
	for _, ct := range cs {
		if ct.ID != uint32(id) {
			continue
		}
		for _, f := range ct.Files {
			for typ, kind := range map[uint32]string{ptpip.SonyThumbnail: "thumb", ptpip.SonyScreennail: "screen"} {
				var buf bytes.Buffer
				if _, err := c.SonyGetContentsCompressed(ctx, uint32(slot), ct.ID, f.ID, typ, &buf); err != nil {
					return done(err)
				}
				name := filepath.Join(out, fmt.Sprintf("%s-%s.bin", filepath.Base(f.Path), kind))
				if err := os.WriteFile(name, buf.Bytes(), 0o644); err != nil {
					return done(err)
				}
				fmt.Printf("   %s: %d bytes\n", name, buf.Len())
			}
		}
		return done(nil)
	}
	return done(fmt.Errorf("content 0x%X not in slot %d", id, slot))
}

// runDelete implements -delete.
func runDelete(ctx context.Context, c *ptpip.Client, spec string) error {
	slotStr, idStr, ok := strings.Cut(spec, ":")
	slot, err1 := strconv.ParseUint(slotStr, 0, 32)
	id, err2 := strconv.ParseUint(idStr, 0, 32)
	if !ok || err1 != nil || err2 != nil {
		return fmt.Errorf("bad -delete %q (want SLOT:CONTENTID)", spec)
	}
	// Delete only a content the camera lists, and check afterwards that it is gone.
	find := func() (*ptpip.SonyContent, []ptpip.SonyContent, error) {
		cs, err := c.SonyListAllContents(ctx, uint32(slot))
		for i := range cs {
			if cs[i].ID == uint32(id) {
				return &cs[i], cs, err
			}
		}
		return nil, cs, err
	}
	ct, cs, err := find()
	if err != nil {
		return err
	}
	if ct == nil {
		for _, x := range cs[max(0, len(cs)-5):] {
			fmt.Printf("   content 0x%X %s %v\n", x.ID, x.CreatedLocal.Format("2006-01-02 15:04:05"), contentPaths(x))
		}
		return fmt.Errorf("content 0x%X not in slot %d (newest contents above)", id, slot)
	}
	fmt.Printf("   deleting content 0x%X %v\n", ct.ID, contentPaths(*ct))
	done := step(fmt.Sprintf("Sony delete content 0x%X in slot %d (0x9250)", id, slot))
	if done(c.SonyDeleteContent(ctx, uint32(slot), uint32(id))) != nil {
		return c.Err()
	}
	printEvents(c, 5*time.Second)
	if ct, _, err := find(); err != nil || ct != nil {
		return errors.Join(err, fmt.Errorf("content 0x%X still listed after delete", id))
	}
	fmt.Println("   content no longer listed")
	return nil
}

func contentPaths(ct ptpip.SonyContent) []string {
	var p []string
	for _, f := range ct.Files {
		p = append(p, f.Path)
	}
	return p
}

// runImportLUT implements -import-lut.
func runImportLUT(ctx context.Context, c *ptpip.Client, spec string) error {
	numStr, path, ok := strings.Cut(spec, ":")
	n, err := strconv.ParseUint(numStr, 0, 16)
	if !ok || err != nil {
		return fmt.Errorf("bad -import-lut %q (want N:PATH)", spec)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	done := step(fmt.Sprintf("Sony import LUT %s as user slot %d (0x921A, 0x921B)", path, n))
	if done(c.SonyImportLUT(ctx, uint16(n), filepath.Base(path), data)) != nil {
		return c.Err()
	}
	printEvents(c, 5*time.Second)
	return nil
}

// runRestore implements -restore.
func runRestore(ctx context.Context, c *ptpip.Client, name string) error {
	data, err := os.ReadFile(name)
	if err != nil {
		return err
	}
	done := step(fmt.Sprintf("Sony restore settings from %s (%d bytes)", name, len(data)))
	if done(c.SonyRestoreSettings(ctx, data)) != nil {
		return c.Err()
	}
	printEvents(c, 5*time.Second)
	return nil
}

// runFetchCaptured implements -fetch-captured: waits up to 10 s for the first
// image, then downloads while the camera reports one ready.
func runFetchCaptured(ctx context.Context, c *ptpip.Client, out string) error {
	done := step("Sony captured images (0xD215 / 0xFFFFC001)")
	deadline := time.Now().Add(10 * time.Second)
	got := 0
	for {
		ready, n, err := c.SonyCapturedPending(ctx)
		if err != nil {
			return done(err)
		}
		fmt.Printf("   0xD215: ready=%v count=%d\n", ready, n)
		if !ready {
			if got > 0 || time.Now().After(deadline) {
				break
			}
			time.Sleep(500 * time.Millisecond)
			continue
		}
		var buf bytes.Buffer
		info, size, err := c.SonyGetCapturedImage(ctx, &buf, nil)
		if err != nil {
			return done(err)
		}
		name := filepath.Join(out, filepath.Base(info.Filename))
		if err := os.WriteFile(name, buf.Bytes(), 0o644); err != nil {
			return done(err)
		}
		fmt.Printf("   %s: %d bytes (info size %d, format 0x%04X)\n", name, size, info.CompressedSize, info.ObjectFormat)
		got++
	}
	fmt.Printf("   %d image(s)\n", got)
	return done(nil)
}
