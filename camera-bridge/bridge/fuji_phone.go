package bridge

// 手机无线会话独立于PC AutoSave，复用的仅为Fuji封包和经过校验的媒体读取
// 来源固定于libfuji 3328efc51f74d754f8fa76af70c6becdde62271f/lib/fuji.c
import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"image/jpeg"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

const fujiPhonePreviewLimit = 16 << 20

type fujiPhone struct{ *fujiCamera }

func connectFujiPhone(parent context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (*fujiPhone, string, error) {
	ctx, cancel := context.WithCancel(parent)
	setup, stop := context.WithTimeout(ctx, 35*time.Second)
	defer stop()
	conn, err := dial(setup, "tcp", net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)))
	if err != nil {
		cancel()
		return nil, "", err
	}
	c := &fujiPhone{&fujiCamera{wire: newFujiWire(conn), ctx: ctx, cancel: cancel, count: -1, state: -1, mode: fujiClassicPhoneMedia}}
	fail := func(err error) (*fujiPhone, string, error) { c.Close(); return nil, "", err }
	c.model, err = c.wire.init(setup, "FrameLink")
	if err != nil {
		return fail(err)
	}
	select {
	case <-time.After(50 * time.Millisecond):
	case <-setup.Done():
		return fail(setup.Err())
	}
	if _, err = c.wire.call(setup, 0x1002, []uint32{1}, nil, 0); err != nil && err != fujiResponseError(0x201e) {
		return fail(err)
	}
	for {
		state, _, err := c.events(setup)
		if err != nil {
			return fail(err)
		}
		if state != 0 && state != -1 {
			if !c.mediaState(state) {
				return fail(errors.New("机身未开放手机图库，请选择无线通信并在相机上允许访问；AutoSave和机身选图发送是独立模式"))
			}
			break
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-setup.Done():
			return fail(errors.New("等待机身确认超时，请在相机上允许FrameLink访问"))
		}
	}
	versions := map[uint16][]byte{}
	for _, code := range []uint16{0xdf22, 0xdf25, 0xdf21, 0xdf24, 0xdf28} {
		value, err := c.wire.property(setup, code)
		if err == fujiResponseError(0x200a) || err == fujiResponseError(0x2005) {
			continue
		}
		if err != nil {
			return fail(err)
		}
		if len(value) != 2 && len(value) != 4 {
			return fail(errors.New("富士手机协议版本字段不完整"))
		}
		if len(value) == 2 && binary.LittleEndian.Uint16(value) == 0 || len(value) == 4 && binary.LittleEndian.Uint32(value) == 0 {
			continue
		}
		versions[code] = value
	}
	if len(versions[0xdf22]) == 0 {
		return fail(errors.New("机身未公布手机照片读取协议"))
	}
	if value := versions[0xdf28]; len(value) > 0 {
		c.mode = fujiXAppPhoneMedia
		if err = c.wire.set16(setup, fujiClientState, 20); err != nil {
			return fail(err)
		}
		if _, err = c.wire.call(setup, fujiSetProperty, []uint32{0xdf28}, value, 0); err != nil {
			return fail(err)
		}
		if err = c.wire.set16(setup, 0xd226, 0); err != nil {
			return fail(err)
		}
		if err = c.wire.set16(setup, fujiCorrectFileSize, 0); err != nil {
			return fail(err)
		}
		// XApp的扩展图库初始化只读取元数据与缩略图，不触发拍摄或发送原片
		for _, request := range []struct {
			code   uint16
			params []uint32
			limit  int
		}{
			{0x9054, []uint32{0x10000001}, fujiMetadataLimit},
			{0x9055, []uint32{0x10000001}, fujiPhonePreviewLimit},
			{0x9050, nil, fujiMetadataLimit},
			{0x9053, []uint32{0, 30000}, fujiMetadataLimit},
		} {
			if c.count == 0 && (request.code == 0x9054 || request.code == 0x9055) {
				continue
			}
			if _, err = c.wire.call(setup, request.code, request.params, nil, request.limit); err != nil {
				return fail(err)
			}
		}
		for _, code := range []uint16{0xd620, 0xd621} {
			if _, err = c.wire.property(setup, code); err != nil {
				return fail(err)
			}
		}
	} else {
		client := uint16(2)
		if len(versions[0xdf25]) > 0 {
			client = 11
		}
		if err = c.wire.set16(setup, fujiClientState, client); err != nil {
			return fail(err)
		}
		if value := versions[0xdf25]; len(value) > 0 {
			data := make([]byte, 4)
			binary.LittleEndian.PutUint32(data, 5)
			if _, err = c.wire.call(setup, fujiSetProperty, []uint32{0xdf25}, data, 0); err != nil {
				return fail(err)
			}
		} else if _, err = c.wire.call(setup, fujiSetProperty, []uint32{0xdf22}, versions[0xdf22], 0); err != nil {
			return fail(err)
		}
	}
	if state, count, e := c.events(setup); e != nil {
		return fail(e)
	} else if !c.mediaState(state) || count < 0 {
		return fail(errors.New("机身尚未提供手机图库范围，请回到相机无线通信页面确认"))
	}
	firmware, serial := "", ""
	device, e := c.wire.call(setup, 0x1001, nil, nil, fujiMetadataLimit)
	if e == nil {
		r := fujiReader{b: device}
		r.u16()
		r.u32()
		r.u16()
		r.str()
		r.u16()
		ops := r.array()
		for i := 0; i < 4; i++ {
			r.array()
		}
		manufacturer, model := r.str(), r.str()
		firmware, serial = r.str(), r.str()
		if r.err != nil {
			return fail(r.err)
		}
		if !strings.Contains(strings.ToLower(manufacturer), "fujifilm") || model == "" {
			return fail(errors.New("设备未返回富士机身身份"))
		}
		if !slices.Contains(ops, uint16(0x1008)) || !slices.Contains(ops, uint16(0x101b)) {
			return fail(errors.New("机身未公布可校验的媒体原字节读取操作"))
		}
		c.model = model
	} else if e != fujiResponseError(0x2005) {
		return fail(e)
	}
	model := strings.TrimPrefix(strings.ToUpper(c.model), "FUJIFILM ")
	if !(strings.HasPrefix(model, "X") && len(model) > 1) && !strings.HasPrefix(model, "GFX") {
		return fail(errors.New("初始化响应未确认富士机身型号"))
	}
	identityScope := "STABLE_DEVICE"
	identity := protocolFujiPhone + ":" + c.model + ":" + serial
	if strings.TrimSpace(serial) == "" {
		var nonce [16]byte
		if _, err = rand.Read(nonce[:]); err != nil {
			return fail(err)
		}
		identity += ":session:" + hex.EncodeToString(nonce[:])
		identityScope = "SESSION_BATCH"
	}
	sum := sha256.Sum256([]byte(identity))
	c.identity = hex.EncodeToString(sum[:12])
	info, err := marshal(map[string]any{"id": c.identity, "model": c.model, "manufacturer": "Fujifilm", "firmware": firmware,
		"protocol": protocolFujiPhone, "hardwareVerified": false, "mediaScope": c.mediaScope(), "identityScope": identityScope,
		"canBrowse": true, "canDownload": true, "canPreview": true, "canCapture": false, "canLiveView": false, "canFocus": false, "canAutoImport": false})
	if err != nil {
		return fail(err)
	}
	go c.watch()
	return c, info, nil
}

// GetThumb只接收JPEG预览，不会因查看RAF缩略图而暗中下载整张RAW
func (c *fujiPhone) Preview(slot, contentID, fileID int, target string, large bool) (err error) {
	c.op.Lock()
	defer c.op.Unlock()
	var selected *fujiObject
	if slot == 1 {
		for _, p := range c.photos {
			if p.content == uint32(contentID) && fileID > 0 && fileID <= len(p.files) {
				f := p.files[fileID-1]
				selected = &f
				break
			}
		}
	}
	if selected == nil {
		return errors.New("照片不在当前富士手机图库中，请刷新后重试")
	}
	ctx, stop := context.WithTimeout(c.ctx, 20*time.Second)
	defer stop()
	restore, err := c.originalSize(ctx)
	if err != nil {
		return err
	}
	defer func() {
		if restore != nil {
			err = errors.Join(err, restore())
		}
	}()
	before, err := c.object(ctx, selected.handle)
	if err != nil {
		return err
	}
	if before != *selected {
		return errors.New("相机照片已变化，请刷新后重新查看")
	}
	data, err := c.wire.call(ctx, 0x100a, []uint32{selected.handle}, nil, fujiPhonePreviewLimit)
	if err != nil {
		return err
	}
	after, err := c.object(ctx, selected.handle)
	if err != nil {
		return err
	}
	if after != before {
		return errors.New("预览期间相机照片已变化，未发布缓存")
	}
	if err = restore(); err != nil {
		restore = nil
		return err
	}
	restore = nil
	config, err := jpeg.DecodeConfig(bytes.NewReader(data))
	if err != nil || config.Width <= 0 || config.Height <= 0 || config.Width > 16384 || config.Height > 16384 {
		return errors.New("机身未返回有效JPEG缩略图")
	}
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return err
	}
	file, err := os.CreateTemp(filepath.Dir(target), "fuji-preview-*.part")
	if err != nil {
		return err
	}
	defer func() { file.Close(); os.Remove(file.Name()) }()
	if err = fujiWriteAll(file, data); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if err = ctx.Err(); err != nil {
		return err
	}
	return fujiPublishOriginal(file.Name(), target)
}
