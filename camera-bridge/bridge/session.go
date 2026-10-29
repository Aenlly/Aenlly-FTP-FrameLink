// Package bridge exposes a small gomobile-compatible camera interface.
// Original images are written to files; only metadata and preview frames cross JNI.
package bridge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/knallli/alpha2go/propdb"
	"github.com/knallli/alpha2go/ptpip"
)

const transferChunk = 4 << 20

// Progress receives durable byte counts. Implementations must return promptly.
type Progress interface{ OnProgress(done, total int64) }

// NetworkBinder binds a camera socket without changing the application's cloud network.
type NetworkBinder interface{ BindSocket(fd int64) error }

// Session owns one camera connection. It never deletes media or applies bulk settings restores.
type Session struct {
	mu             sync.Mutex
	op             sync.Mutex
	client         *ptpip.Client
	ssh            *ptpip.SSHDialer
	live           *ptpip.SonyLiveView
	ctx            context.Context
	cancel         context.CancelFunc
	identity       string
	binder         NetworkBinder
	transferCancel context.CancelFunc
	metadata       string
	newestIndex    *contentsIndex
	controls       []uint16
}

type connectionConfig struct {
	Host        string `json:"host"`
	Port        int    `json:"port"`
	User        string `json:"user"`
	Password    string `json:"password"`
	Fingerprint string `json:"fingerprint"`
	GUID        string `json:"guid"`
}

type downloadRequest struct {
	Slot      uint32 `json:"slot"`
	ContentID uint32 `json:"contentId"`
	FileID    uint16 `json:"fileId"`
	Size      uint64 `json:"size"`
}

// NewSession creates a disconnected session; no discovery or camera write occurs.
func NewSession() *Session { return &Session{} }

// SetNetworkBinder is called before Connect with the Android camera network.
func (s *Session) SetNetworkBinder(binder NetworkBinder) { s.binder = binder }

func decodeConfig(raw string) (connectionConfig, error) {
	var cfg connectionConfig
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		return cfg, err
	}
	if net.ParseIP(cfg.Host) == nil {
		return cfg, errors.New("请输入相机的局域网IP地址")
	}
	if cfg.Port == 0 {
		cfg.Port = 15740
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return cfg, errors.New("相机端口无效")
	}
	if cfg.User != "" && cfg.Fingerprint == "" {
		return cfg, errors.New("访问认证需要相机显示的SSH指纹")
	}
	if cfg.GUID != "" {
		if b, e := hex.DecodeString(cfg.GUID); e != nil || len(b) != 16 {
			return cfg, errors.New("设备配对标识无效")
		}
	}
	return cfg, nil
}

// Connect negotiates PTP/IP, verifies SSH identity when configured and reports real capabilities.
func (s *Session) Connect(raw string) (string, error) {
	cfg, err := decodeConfig(raw)
	if err != nil {
		return "", err
	}
	s.Close()
	ctx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	s.ctx, s.cancel = ctx, cancel
	s.mu.Unlock()
	connectCtx, stop := context.WithTimeout(ctx, 35*time.Second)
	defer stop()
	opts := ptpip.Options{Name: "FrameLink Android", DialTimeout: 10 * time.Second, IOTimeout: 30 * time.Second}
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	if s.binder != nil {
		dialer.Control = func(_, _ string, raw syscall.RawConn) error {
			var bindErr error
			err := raw.Control(func(fd uintptr) { bindErr = s.binder.BindSocket(int64(fd)) })
			return errors.Join(err, bindErr)
		}
	}
	opts.DialContext = dialer.DialContext
	if cfg.GUID != "" {
		b, _ := hex.DecodeString(cfg.GUID)
		copy(opts.GUID[:], b)
	}
	var tunnel *ptpip.SSHDialer
	if cfg.User != "" {
		tunnel = ptpip.NewSSHDialer(ptpip.SSHConfig{User: cfg.User, Password: cfg.Password, Fingerprint: cfg.Fingerprint, DialContext: dialer.DialContext})
		opts.DialContext = tunnel.DialContext
	}
	c, err := ptpip.Dial(connectCtx, net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port)), opts)
	if err != nil {
		cancel()
		if tunnel != nil {
			tunnel.Close()
		}
		return "", err
	}
	fail := func(e error) (string, error) {
		c.Close()
		cancel()
		if tunnel != nil {
			tunnel.Close()
		}
		return "", e
	}
	di, err := c.GetDeviceInfo(connectCtx)
	if err != nil {
		return fail(err)
	}
	if !di.IsSony() {
		return fail(errors.New("当前适配器仅支持Sony相机"))
	}
	if err = c.OpenSession(connectCtx, 1); err != nil {
		return fail(err)
	}
	ext, err := c.SonyHandshake(connectCtx)
	if err != nil {
		return fail(err)
	}
	props, err := c.SonyGetProps(connectCtx, false)
	if err != nil {
		return fail(err)
	}
	identity := sha256.Sum256([]byte(di.SerialNumber + fmt.Sprintf("%x", c.ResponderGUID)))
	canLive := false
	for _, p := range props {
		if p.Code == ptpip.SonyPropLiveViewURL && p.Current.Str != "" {
			canLive = true
		}
	}
	s.mu.Lock()
	if ctx.Err() != nil || s.ctx != ctx {
		s.mu.Unlock()
		return fail(errors.New("连接已取消"))
	}
	s.client, s.ssh, s.ctx, s.cancel, s.identity = c, tunnel, ctx, cancel, hex.EncodeToString(identity[:12])
	s.controls = slices.Clone(ext.Controls)
	s.mu.Unlock()
	return marshal(map[string]any{"id": s.identity, "model": di.Model, "firmware": di.DeviceVersion, "manufacturer": di.Manufacturer,
		"canBrowse": slices.Contains(di.Operations, uint16(0x923C)), "canDownload": slices.Contains(di.Operations, uint16(0x923D)),
		"canPreview": slices.Contains(di.Operations, uint16(0x923E)), "canCapture": slices.Contains(ext.Controls, uint16(0xD2C2)),
		"canLiveView": canLive, "canFocus": slices.Contains(ext.Controls, ptpip.SonyCtlRemoteTouch) || slices.Contains(ext.Controls, ptpip.SonyCtlAFAreaPosition), "hardwareVerified": false})
}

func (s *Session) camera() (*ptpip.Client, context.Context, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.client == nil {
		return nil, nil, errors.New("相机尚未连接")
	}
	select {
	case <-s.client.Done():
		return nil, nil, errors.New("相机连接已中断，请重新连接")
	default:
	}
	return s.client, s.ctx, nil
}

// Connected observes transport state without sending a command to the camera.
func (s *Session) Connected() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.client == nil || s.ctx == nil {
		return false
	}
	select {
	case <-s.client.Done():
		return false
	case <-s.ctx.Done():
		return false
	default:
		return true
	}
}

// Dates returns camera wall-clock capture days, encoded as YYYY-MM-DD without applying phone timezone.
func (s *Session) Dates(slot int) (string, error) {
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	dates, err := c.SonyGetCapturedDateList(ctx, uint32(slot))
	if err != nil {
		return "", err
	}
	result := make([]string, 0, len(dates))
	for _, date := range dates {
		result = append(result, date.Format("2006-01-02"))
	}
	slices.Sort(result)
	slices.Reverse(result)
	return marshal(result)
}

// ListPage fetches one camera page, preserving the camera's RAW/JPEG grouping.
func (s *Session) ListPage(slot int, cursor int64) (string, error) {
	if slot < 1 || slot > 2 || cursor < 0 {
		return "", errors.New("存储卡或分页游标无效")
	}
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 35*time.Second)
	defer cancel()
	if cursor == 0 {
		if _, err = c.SonyGetCapturedDateList(ctx, uint32(slot)); err != nil {
			if errors.Is(err, ptpip.ErrSlotEmpty) {
				return marshal(map[string]any{"items": []any{}, "nextCursor": 0})
			}
			return "", err
		}
	}
	items, err := c.SonyGetContentsInfoList(ctx, uint32(slot), uint64(cursor))
	if err != nil {
		return "", err
	}
	rows := make([]map[string]any, 0, len(items))
	var next int64
	for _, item := range items {
		files := make([]map[string]any, 0, len(item.Files))
		for _, file := range item.Files {
			files = append(files, map[string]any{"fileId": file.ID, "name": filepath.Base(file.Path), "cameraPath": file.Path, "size": file.Size, "width": file.Width, "height": file.Height, "format": file.Format, "cameraDay": item.CreatedLocal.Format("2006-01-02")})
		}
		rows = append(rows, map[string]any{"id": fmt.Sprintf("%s:%d:%d:%d", s.identity, slot, item.ID, item.CreatedUTC.UnixMilli()), "contentId": item.ID, "slot": slot, "capturedAt": item.CreatedUTC.UnixMilli(), "groupId": item.GroupID, "files": files})
		next = item.CreatedUTC.UnixMilli()
	}
	if len(items) < 100 {
		next = 0
	} else if next <= cursor {
		return "", errors.New("相机分页游标未前进，已停止重复读取")
	}
	return marshal(map[string]any{"items": rows, "nextCursor": next})
}

// Preview writes the camera-provided preview without decoding or altering it.
func (s *Session) Preview(slot, contentID, fileID int, target string, large bool) error {
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(parent, 25*time.Second)
	defer cancel()
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return err
	}
	f, err := os.OpenFile(target+".part", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	typ := ptpip.SonyThumbnail
	if large {
		typ = ptpip.SonyScreennail
	}
	_, transferErr := c.SonyGetContentsCompressed(ctx, uint32(slot), uint32(contentID), uint16(fileID), typ, f)
	syncErr := f.Sync()
	closeErr := f.Close()
	if err = errors.Join(transferErr, syncErr, closeErr); err != nil {
		return err
	}
	return os.Rename(target+".part", target)
}

// Download resumes only the same identity-specific target, preserving partial bytes on failure.
func (s *Session) Download(raw, target string, progress Progress) (string, error) {
	var req downloadRequest
	if err := json.Unmarshal([]byte(raw), &req); err != nil {
		return "", err
	}
	if req.Slot < 1 || req.Slot > 2 || req.Size == 0 || req.Size > math.MaxInt64 {
		return "", errors.New("下载文件信息无效")
	}
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return "", err
	}
	transferContext, transferCancel := context.WithCancel(parent)
	s.mu.Lock()
	s.transferCancel = transferCancel
	s.mu.Unlock()
	defer func() { transferCancel(); s.mu.Lock(); s.transferCancel = nil; s.mu.Unlock() }()
	f, err := os.OpenFile(target+".part", os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return "", err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	done := uint64(st.Size())
	if done > req.Size {
		return "", errors.New("暂存文件长度与原片不符，请清理此任务后重试")
	}
	if _, err = f.Seek(int64(done), io.SeekStart); err != nil {
		return "", err
	}
	for done < req.Size {
		want := uint32(min(uint64(transferChunk), req.Size-done))
		ctx, cancel := context.WithTimeout(transferContext, 90*time.Second)
		s.op.Lock()
		n, transferErr := c.SonyGetContentsData(ctx, req.Slot, req.ContentID, req.FileID, done, want, done+uint64(want) == req.Size, f)
		s.op.Unlock()
		cancel()
		if transferErr != nil {
			return "", transferErr
		}
		if n == 0 || n > want {
			return "", errors.New("相机返回的下载长度无效")
		}
		done += uint64(n)
		if err = f.Sync(); err != nil {
			return "", err
		}
		if progress != nil {
			progress.OnProgress(int64(done), int64(req.Size))
		}
	}
	if err = f.Close(); err != nil {
		return "", err
	}
	if err = os.Rename(target+".part", target); err != nil {
		return "", err
	}
	input, err := os.Open(target)
	if err != nil {
		return "", err
	}
	defer input.Close()
	hash := sha256.New()
	if _, err = io.Copy(hash, input); err != nil {
		return "", err
	}
	return marshal(map[string]any{"path": target, "size": done, "sha256": hex.EncodeToString(hash.Sum(nil))})
}

// Properties reports only camera-supplied values. Unknown or dangerous controls remain read-only.
func (s *Session) Properties() (string, error) {
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return "", err
	}
	rows := make([]map[string]any, 0)
	for _, p := range props {
		info, known := cameraPropertyInfo(p.Code)
		if !known || !safeGroup(info.Group) {
			continue
		}
		choices := make([]map[string]any, 0, len(p.Settable))
		for _, v := range p.Settable {
			choices = append(choices, map[string]any{"value": v.Int, "label": valueLabel(info, v.Int)})
		}
		rows = append(rows, map[string]any{"code": p.Code, "name": info.Name, "value": p.Current.Int, "label": valueLabel(info, p.Current.Int), "writable": p.Writable && p.Enabled == 1 && info.Danger == propdb.None && len(choices) > 0, "choices": choices})
	}
	return marshal(rows)
}

func safeGroup(group string) bool {
	return group == "Exposure" || group == "Focus" || group == "White balance"
}
func valueLabel(info propdb.Info, value int64) string {
	if v, ok := info.Labels[value]; ok {
		return v
	}
	if info.Scale != 0 {
		return fmt.Sprintf("%g%s", float64(value)/info.Scale, info.Unit)
	}
	return fmt.Sprintf("%d%s", value, info.Unit)
}

// SetProperty validates the exact value against the latest camera-supplied enum.
func (s *Session) SetProperty(code int, value int64) error {
	if !s.op.TryLock() {
		return errors.New("相机正在处理传输，请稍后修改参数")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	info, known := cameraPropertyInfo(uint16(code))
	if !known || !safeGroup(info.Group) || info.Danger != propdb.None {
		return errors.New("此参数未开放编辑")
	}
	p, err := c.SonyGetProp(ctx, uint16(code))
	if err != nil {
		return err
	}
	if !p.Writable || p.Enabled != 1 {
		return errors.New("当前拍摄模式不允许修改此参数")
	}
	if !slices.ContainsFunc(p.Settable, func(v ptpip.SonyValue) bool { return v.Int == value }) {
		return errors.New("参数值不在相机当前可选范围内")
	}
	return c.SonySetProp(ctx, p.Code, p.Type, ptpip.SonyValue{Int: value})
}

// Capture is deliberately never retried. A timeout can mean the camera already fired.
func (s *Session) Capture(autofocus bool) error {
	if !s.op.TryLock() {
		return errors.New("相机正在处理传输，本次快门未发送，请稍后再拍")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	if err = c.SonyCapture(ctx, autofocus); err != nil {
		return fmt.Errorf("快门结果待确认，请先查看机身或新照片，再决定是否重拍：%w", err)
	}
	return nil
}

// StartLiveView opens the camera-advertised HTTP stream through the existing transport.
func (s *Session) StartLiveView() error {
	c, parent, err := s.camera()
	if err != nil {
		return err
	}
	s.StopLiveView()
	live, err := c.SonyOpenLiveView(parent)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.live = live
	s.mu.Unlock()
	return nil
}

// NextFrame returns JPEG bytes only; it does not queue old frames.
func (s *Session) NextFrame() ([]byte, error) {
	s.mu.Lock()
	live := s.live
	s.mu.Unlock()
	if live == nil {
		return nil, errors.New("取景尚未启动")
	}
	frame, err := live.Next()
	if err == nil {
		if meta, e := ptpip.ParseSonyLiveViewMeta(frame.Meta); e == nil {
			value, _ := marshal(meta)
			s.mu.Lock()
			s.metadata = value
			s.mu.Unlock()
		}
	}
	return frame.JPEG, err
}
func (s *Session) LiveMetadata() string { s.mu.Lock(); defer s.mu.Unlock(); return s.metadata }

// FetchCaptured saves the camera's post-capture file and distinguishes a preview from original-size transfer.
func (s *Session) FetchCaptured(target string) (string, error) {
	if !s.op.TryLock() {
		return "", errors.New("相机正在处理其他操作，稍后可浏览拍摄结果")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 40*time.Second)
	defer cancel()
	ready, _, err := c.SonyCapturedPending(ctx)
	if err != nil {
		return "", err
	}
	if !ready {
		return marshal(map[string]any{"ready": false})
	}
	original := false
	if p, e := c.SonyGetProp(ctx, ptpip.SonyPropTransferSize); e == nil {
		original = p.Current.Int == ptpip.SonyTransferOriginal
	}
	if err = os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return "", err
	}
	file, err := os.OpenFile(target+".part", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return "", err
	}
	info, n, transferErr := c.SonyGetCapturedImage(ctx, file, nil)
	syncErr := file.Sync()
	closeErr := file.Close()
	if err = errors.Join(transferErr, syncErr, closeErr); err != nil {
		return "", err
	}
	if err = os.Rename(target+".part", target); err != nil {
		return "", err
	}
	return marshal(map[string]any{"ready": true, "path": target, "name": info.Filename, "size": n, "original": original})
}
func (s *Session) StopLiveView() {
	s.mu.Lock()
	live := s.live
	s.live = nil
	s.mu.Unlock()
	if live != nil {
		live.Close()
	}
}

// CancelTransfer interrupts only the active transfer context, preserving its partial file.
func (s *Session) CancelTransfer() {
	s.mu.Lock()
	cancel := s.transferCancel
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// Close releases streams and sockets before a reconnect or foreground service shutdown.
func (s *Session) Close() {
	s.StopLiveView()
	s.mu.Lock()
	c, tunnel, cancel := s.client, s.ssh, s.cancel
	s.client, s.ssh, s.cancel = nil, nil, nil
	s.newestIndex = nil
	s.controls = nil
	s.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if c != nil {
		c.Close()
	}
	if tunnel != nil {
		tunnel.Close()
	}
}
func marshal(v any) (string, error) { b, err := json.Marshal(v); return string(b), err }
