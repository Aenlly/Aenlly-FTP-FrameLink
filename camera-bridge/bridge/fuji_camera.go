package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

type fujiObject struct {
	handle                   uint32
	storage, parent          uint32
	name, captured, modified string
	size                     uint32
	format                   uint16
}
type fujiPhoto struct {
	id       string
	content  uint32
	files    []fujiObject
	captured int64
	day      string
}
type fujiCamera struct {
	fujiMediaOnly
	wire      *fujiWire
	ctx       context.Context
	cancel    context.CancelFunc
	op        sync.Mutex
	mu        sync.Mutex
	identity  string
	model     string
	count     int
	state     int
	photos    []fujiPhoto
	indexed   bool
	pagingDay string
}

func connectFujiAutoSave(parent context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (*fujiCamera, string, error) {
	ctx, cancel := context.WithCancel(parent)
	setup, stop := context.WithTimeout(ctx, 35*time.Second)
	defer stop()
	conn, err := dial(setup, "tcp", net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)))
	if err != nil {
		cancel()
		return nil, "", err
	}
	c := &fujiCamera{wire: newFujiWire(conn), ctx: ctx, cancel: cancel, count: -1, state: -1}
	fail := func(err error) (*fujiCamera, string, error) { c.Close(); return nil, "", err }
	c.model, err = c.wire.init(setup, "FrameLink")
	if err != nil {
		return fail(err)
	}
	_, err = c.wire.call(setup, 0x1002, []uint32{1}, nil, 0)
	if err != nil && err != fujiResponseError(0x201e) {
		return fail(err)
	}
	for {
		state, count, e := c.events(setup)
		if e != nil {
			return fail(e)
		}
		if state == 3 && count >= 0 {
			c.count = count
			break
		}
		if state != 0 && state != -1 {
			return fail(errors.New("当前机身不是PC AutoSave待传批次模式"))
		}
		select {
		case <-setup.Done():
			return fail(setup.Err())
		case <-time.After(100 * time.Millisecond):
		}
	}
	// Only documented AutoSave negotiation writes are allowed. No card or D228 writes.
	for _, code := range []uint16{0xdf22, 0xdf25, 0xdf21, 0xdf24} {
		_, e := c.wire.property(setup, code)
		if e != nil && e != fujiResponseError(0x200a) {
			return fail(e)
		}
	}
	if err = c.wire.set16(setup, fujiClientState, 3); err != nil {
		return fail(err)
	}
	state, count, err := c.events(setup)
	if err != nil {
		return fail(err)
	}
	if state != 3 || count < 0 {
		return fail(errors.New("机身未进入AutoSave接收状态"))
	}
	c.count = count
	version, err := c.wire.property(setup, fujiAutoSaveVersion)
	if err != nil {
		return fail(err)
	}
	if len(version) != 4 {
		return fail(errors.New("AutoSave版本字段长度无效"))
	}
	if _, err = c.wire.call(setup, fujiSetProperty, []uint32{fujiAutoSaveVersion}, version, 0); err != nil {
		return fail(err)
	}
	if err = c.wire.set16(setup, fujiCorrectFileSize, 1); err != nil {
		return fail(err)
	}
	firmware, serial := "", ""
	// Standard DeviceInfo is optional in this mode; the discovered DSCNAME remains mandatory.
	device, err := c.wire.call(setup, 0x1001, nil, nil, fujiMetadataLimit)
	if err == nil {
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
			return fail(errors.New("设备信息未返回富士机身"))
		}
		if !slices.Contains(ops, uint16(0x1008)) || !slices.Contains(ops, uint16(0x101b)) {
			return fail(errors.New("机身未公布AutoSave原片读取能力"))
		}
		c.model = model
	} else if err != fujiResponseError(0x2005) {
		return fail(err)
	}
	sum := sha256.Sum256([]byte("fuji-autosave:" + cfg.FujiDeviceName + ":" + c.model + ":" + serial))
	c.identity = hex.EncodeToString(sum[:12])
	info, err := marshal(map[string]any{"id": c.identity, "model": c.model, "manufacturer": "Fujifilm", "firmware": firmware,
		"protocol": protocolFujiAutoSave, "experimental": true, "hardwareVerified": false, "mediaScope": "AUTOSAVE_PENDING_BATCH",
		"canBrowse": true, "canDownload": true, "canPreview": false, "canCapture": false, "canLiveView": false, "canFocus": false, "canAutoImport": false})
	if err != nil {
		return fail(err)
	}
	go c.watch()
	return c, info, nil
}

func (c *fujiCamera) watch() {
	ticker := time.NewTicker(8 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.ctx.Done():
			return
		case <-ticker.C:
			if !c.op.TryLock() {
				continue
			}
			previous := c.count
			ctx, stop := context.WithTimeout(c.ctx, 3*time.Second)
			state, count, err := c.events(ctx)
			stop()
			if count != previous {
				c.indexed = false
			}
			c.op.Unlock()
			if err != nil || state != 3 {
				c.Close()
				return
			}
		}
	}
}

func (c *fujiCamera) events(ctx context.Context) (int, int, error) {
	b, err := c.wire.property(ctx, fujiEvents)
	if err != nil {
		return -1, -1, err
	}
	if len(b) < 2 {
		return -1, -1, errors.New("AutoSave事件载荷不完整")
	}
	n := int(binary.LittleEndian.Uint16(b))
	if n > (len(b)-2)/6 || 2+n*6 != len(b) {
		return -1, -1, errors.New("AutoSave事件计数无效")
	}
	state, count := c.state, c.count
	for i := 0; i < n; i++ {
		code := binary.LittleEndian.Uint16(b[2+i*6:])
		value := binary.LittleEndian.Uint32(b[4+i*6:])
		if code == fujiCameraState {
			state = int(value)
		}
		if code == fujiObjectCount {
			if value > 10000 {
				return -1, -1, errors.New("AutoSave待传批次超过10000个文件，请分批传输")
			}
			count = int(value)
		}
	}
	c.state, c.count = state, count
	return state, count, nil
}

func (c *fujiCamera) Connected() bool { return c.ctx.Err() == nil && c.wire.online() }
func (c *fujiCamera) Close()          { c.mu.Lock(); defer c.mu.Unlock(); c.cancel(); c.wire.close() }
func (c *fujiCamera) CancelTransfer() { c.Close() }

func (c *fujiCamera) object(ctx context.Context, handle uint32) (fujiObject, error) {
	b, err := c.wire.call(ctx, 0x1008, []uint32{handle}, nil, fujiMetadataLimit)
	if err != nil {
		return fujiObject{}, err
	}
	r := fujiReader{b: b}
	storage := r.u32()
	format := r.u16()
	r.u16()
	size := r.u32()
	r.take(26)
	parent := r.u32()
	r.take(10)
	name, captured, modified := r.str(), r.str(), r.str()
	if r.err != nil {
		return fujiObject{}, r.err
	}
	if name == "" || filepath.Base(name) != name || strings.ContainsAny(name, "\\\x00\r\n") {
		return fujiObject{}, errors.New("AutoSave对象名称无效")
	}
	if size == 0 || size == 0xffffffff {
		return fujiObject{}, errors.New("AutoSave未返回可核对的原片长度")
	}
	return fujiObject{handle: handle, storage: storage, parent: parent, name: name, captured: captured, modified: modified, size: size, format: format}, nil
}

func (c *fujiCamera) snapshot(ctx context.Context) error {
	state, count, err := c.events(ctx)
	if err != nil {
		return err
	}
	if state != 3 || count < 0 {
		return errors.New("AutoSave批次已结束，请在机身重新发起")
	}
	groups := map[string]int{}
	var photos []fujiPhoto
	for handle := 1; handle <= count; handle++ {
		file, err := c.object(ctx, uint32(handle))
		if err != nil {
			return err
		}
		if file.format != 0x3801 && file.format != 0xb103 {
			continue
		}
		stem := strings.ToUpper(strings.TrimSuffix(file.name, filepath.Ext(file.name)))
		key := fmt.Sprintf("%d:%d:%s:%s", file.storage, file.parent, stem, file.captured)
		index, ok := groups[key]
		if !ok {
			index = len(photos)
			groups[key] = index
			photos = append(photos, fujiPhoto{})
		}
		photos[index].files = append(photos[index].files, file)
	}
	ids := map[uint32]bool{}
	for i := range photos {
		p := &photos[i]
		slices.SortFunc(p.files, func(a, b fujiObject) int { return strings.Compare(a.name, b.name) })
		var revision strings.Builder
		revision.WriteString(c.identity)
		for _, f := range p.files {
			fmt.Fprintf(&revision, "\x00%d:%d:%s:%d:%s:%s", f.storage, f.parent, f.name, f.size, f.captured, f.modified)
			if date, e := ptpip.ParseDate(f.captured, time.UTC); e == nil && date.UnixMilli() > p.captured {
				p.captured = date.UnixMilli()
				p.day = date.Format("2006-01-02")
			}
		}
		sum := sha256.Sum256([]byte(revision.String()))
		p.id = c.identity + ":fuji:" + hex.EncodeToString(sum[:])
		p.content = binary.BigEndian.Uint32(sum[:4]) & 0x7fffffff
		if p.content == 0 {
			p.content = 1
		}
		if ids[p.content] {
			return errors.New("AutoSave媒体编号冲突，请重新建立批次")
		}
		ids[p.content] = true
	}
	slices.SortFunc(photos, func(a, b fujiPhoto) int {
		if a.captured > b.captured {
			return -1
		}
		if a.captured < b.captured {
			return 1
		}
		return strings.Compare(a.id, b.id)
	})
	c.mu.Lock()
	c.photos = photos
	c.count = count
	c.indexed = true
	c.mu.Unlock()
	return nil
}

func (c *fujiCamera) Dates(slot int) (string, error) {
	if slot != 1 {
		return "", errors.New("AutoSave仅提供当前待传批次")
	}
	c.op.Lock()
	defer c.op.Unlock()
	ctx, stop := context.WithTimeout(c.ctx, 90*time.Second)
	defer stop()
	if err := c.snapshot(ctx); err != nil {
		return "", err
	}
	var dates = []string{}
	for _, p := range c.photos {
		if p.day != "" && !slices.Contains(dates, p.day) {
			dates = append(dates, p.day)
		}
	}
	return marshal(dates)
}
func (c *fujiCamera) ListPage(slot int, cursor int64) (string, error) {
	return c.ListNewestPage(slot, "", cursor)
}
func (c *fujiCamera) ListNewestPage(slot int, day string, offset int64) (string, error) {
	if slot != 1 || offset < 0 {
		return "", errors.New("AutoSave批次或游标无效")
	}
	if day != "" {
		if _, err := time.Parse("2006-01-02", day); err != nil {
			return "", err
		}
	}
	c.op.Lock()
	defer c.op.Unlock()
	if !c.indexed && offset > 0 {
		return "", errors.New("AutoSave批次已变化，请从第一页重新读取")
	}
	if offset > 0 && c.pagingDay != day {
		return "", errors.New("AutoSave分页日期已变化，请从第一页重新读取")
	}
	if !c.indexed {
		ctx, stop := context.WithTimeout(c.ctx, 90*time.Second)
		defer stop()
		if err := c.snapshot(ctx); err != nil {
			return "", err
		}
	}
	if offset == 0 {
		c.pagingDay = day
	}
	photos := make([]fujiPhoto, 0)
	for _, p := range c.photos {
		if day == "" || p.day == day {
			photos = append(photos, p)
		}
	}
	if offset > int64(len(photos)) {
		return "", errors.New("AutoSave分页已变化，请刷新")
	}
	until := min(offset+contentsPageSize, int64(len(photos)))
	rows := []map[string]any{}
	for _, p := range photos[offset:until] {
		files := []map[string]any{}
		for i, f := range p.files {
			files = append(files, map[string]any{"fileId": i + 1, "name": f.name, "size": f.size, "cameraDay": p.day})
		}
		rows = append(rows, map[string]any{"id": p.id, "contentId": p.content, "slot": 1, "capturedAt": p.captured, "groupId": 0, "files": files})
	}
	next := until
	if until == int64(len(photos)) {
		next = 0
	}
	return marshal(map[string]any{"items": rows, "nextCursor": next, "totalCount": len(photos), "mediaScope": "AUTOSAVE_PENDING_BATCH"})
}

func (c *fujiCamera) Download(raw, target string, progress Progress) (string, error) {
	var req downloadRequest
	if err := json.Unmarshal([]byte(raw), &req); err != nil {
		return "", err
	}
	c.op.Lock()
	defer c.op.Unlock()
	var selected *fujiObject
	if req.Slot == 1 {
		for _, p := range c.photos {
			if p.content == req.ContentID && req.FileID > 0 && int(req.FileID) <= len(p.files) {
				f := p.files[req.FileID-1]
				selected = &f
				break
			}
		}
	}
	if selected == nil || req.Size != uint64(selected.size) {
		return "", errors.New("AutoSave下载资源与当前批次不一致")
	}
	ctx, stop := context.WithTimeout(c.ctx, 5*time.Minute)
	defer stop()
	before, err := c.object(ctx, selected.handle)
	if err != nil {
		return "", err
	}
	if before != *selected {
		return "", errors.New("AutoSave对象已变化，请刷新批次")
	}
	if _, err = os.Lstat(target); !os.IsNotExist(err) {
		return "", errors.New("目标文件已存在或无法检查，不覆盖已有原片")
	}
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return "", err
	}
	file, err := os.CreateTemp(filepath.Dir(target), filepath.Base(target)+"-*.part")
	if err != nil {
		return "", err
	}
	defer func() { file.Close(); os.Remove(file.Name()) }()
	hash := sha256.New()
	var done uint32
	for done < selected.size {
		want := min(uint32(fujiChunk), selected.size-done)
		data, e := c.wire.call(ctx, 0x101b, []uint32{selected.handle, done, want}, nil, int(want))
		if e != nil {
			return "", e
		}
		if len(data) != int(want) {
			c.Close()
			return "", errors.New("AutoSave分块长度不完整，会话已结束")
		}
		if err = ctx.Err(); err != nil {
			return "", err
		}
		if err = fujiWriteAll(file, data); err != nil {
			c.Close()
			return "", err
		}
		hash.Write(data)
		done += want
		if err = file.Sync(); err != nil {
			c.Close()
			return "", err
		}
		if progress != nil {
			progress.OnProgress(int64(done), int64(selected.size))
		}
	}
	after, err := c.object(ctx, selected.handle)
	if err != nil {
		return "", err
	}
	if after != before {
		return "", errors.New("AutoSave原片在接收时变化，未发布文件")
	}
	if err = ctx.Err(); err != nil {
		return "", err
	}
	if err = file.Close(); err != nil {
		return "", err
	}
	// The platform primitive atomically refuses an existing target; no check-then-Rename fallback.
	c.mu.Lock()
	defer c.mu.Unlock()
	if err = ctx.Err(); err != nil {
		return "", err
	}
	if err = fujiPublishOriginal(file.Name(), target); err != nil {
		return "", err
	}
	return marshal(map[string]any{"path": target, "size": done, "sha256": hex.EncodeToString(hash.Sum(nil))})
}

// AutoSave is media-only. No preview call may silently fetch a full RAF.
type fujiMediaOnly struct{}

func (fujiMediaOnly) Properties() (string, error) { return "[]", nil }
func (fujiMediaOnly) Preview(int, int, int, string, bool) error {
	return errors.New("AutoSave实验通道尚未开放安全预览，请按需导入原片")
}
func (fujiMediaOnly) SetProperty(int, int64) error {
	return errors.New("AutoSave不提供机身参数控制")
}
func (fujiMediaOnly) Capture(bool) error { return errors.New("AutoSave不提供遥控拍摄") }
func (fujiMediaOnly) CapturePhoto(bool, bool, bool) (string, error) {
	return "", errors.New("AutoSave不提供遥控拍摄")
}
func (fujiMediaOnly) ConfigureCaptureStorage(bool) (string, error) {
	return "", errors.New("AutoSave不修改机身保存位置")
}
func (fujiMediaOnly) ShootingState() (string, error) {
	return "", errors.New("AutoSave不提供拍摄状态")
}
func (fujiMediaOnly) FocusAt(float64, float64) error {
	return errors.New("AutoSave不提供对焦控制")
}
func (fujiMediaOnly) StartLiveView() error { return errors.New("AutoSave不提供实时取景") }
func (fujiMediaOnly) NextFrame() ([]byte, error) {
	return nil, errors.New("AutoSave不提供实时取景")
}
func (fujiMediaOnly) LiveMetadata() string { return "" }
func (fujiMediaOnly) FetchCaptured(string) (string, error) {
	return "", errors.New("请从AutoSave待传批次选择原片")
}
func (fujiMediaOnly) StopLiveView() {}
