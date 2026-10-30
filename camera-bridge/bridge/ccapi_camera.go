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
	"net/http"
	"path"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type ccapiDevice struct {
	Manufacturer    string
	ProductName     string
	SerialNumber    string
	GUID            string
	FirmwareVersion string
}

type ccapiPhoto struct {
	id        string
	contentID uint32
	files     []ccapiFile
}

type ccapiSnapshot struct {
	slot       int
	photos     []ccapiPhoto
	datesFresh bool
	pagingDay  string
}

// canonCamera owns one independently cancellable HTTP connection generation.
// The existing Sony session is not reused for Canon's HTTP commands.
type canonCamera struct {
	http           *ccapiHTTP
	resources      ccapiResources
	media          ccapiMedia
	device         ccapiDevice
	identity       string
	ctx            context.Context
	cancel         context.CancelFunc
	online         atomic.Bool
	op             sync.Mutex
	mu             sync.Mutex
	index          *ccapiSnapshot
	transferCancel context.CancelFunc
	liveCancel     context.CancelFunc
	liveContext    context.Context
	firstFrame     []byte
}

func connectCanon(parent context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (*canonCamera, string, error) {
	client, err := newCCAPIHTTP(cfg.Host, cfg.Port, cfg.User, cfg.Password, dial)
	if err != nil {
		return nil, "", err
	}
	ctx, cancel := context.WithCancel(parent)
	camera := &canonCamera{http: client, ctx: ctx, cancel: cancel}
	fail := func(err error) (*canonCamera, string, error) {
		cancel()
		client.close()
		return nil, "", err
	}
	connectCtx, stop := context.WithTimeout(ctx, 30*time.Second)
	defer stop()
	camera.resources, err = discoverCCAPI(connectCtx, client)
	if err != nil {
		return fail(err)
	}
	information, err := camera.resources.path(http.MethodGet, "deviceinformation")
	if err != nil {
		return fail(err)
	}
	if err = client.json(connectCtx, http.MethodGet, information, nil, &camera.device); err != nil {
		return fail(err)
	}
	if !strings.HasPrefix(strings.ToLower(strings.TrimSpace(camera.device.Manufacturer)), "canon") || camera.device.ProductName == "" {
		return fail(errors.New("当前地址未返回可识别的佳能机身信息"))
	}
	if camera.device.SerialNumber == "" && camera.device.GUID == "" {
		return fail(errors.New("机身未返回稳定设备标识，暂不建立照片索引"))
	}
	identity := sha256.Sum256([]byte("ccapi:" + camera.device.Manufacturer + ":" + camera.device.ProductName + ":" + camera.device.SerialNumber + ":" + camera.device.GUID))
	camera.identity = hex.EncodeToString(identity[:12])
	camera.media = ccapiMedia{http: client, resources: camera.resources}
	has := func(method, operation string) bool { return camera.resources[method+":"+operation] != "" }
	info, err := marshal(map[string]any{
		"id": camera.identity, "model": camera.device.ProductName, "manufacturer": camera.device.Manufacturer,
		"firmware": camera.device.FirmwareVersion, "hardwareVerified": false, "dateSource": "FILE_MODIFIED",
		"canBrowse": has(http.MethodGet, "contents"), "canPreview": has(http.MethodGet, "contents"), "canDownload": has(http.MethodGet, "contents"),
		"canCapture":    has(http.MethodPost, "shooting/control/shutterbutton") && has(http.MethodGet, "shooting/settings/drive"),
		"canLiveView":   has(http.MethodPost, "shooting/liveview") && has(http.MethodGet, "shooting/liveview/flip"),
		"canFocus":      has(http.MethodPut, "shooting/liveview/afframeposition") && has(http.MethodGet, "shooting/liveview/flipdetail") && has(http.MethodPost, "shooting/control/shutterbutton/manual"),
		"canAutoImport": false,
	})
	if err != nil {
		return fail(err)
	}
	camera.online.Store(true)
	go camera.watchConnection(information)
	return camera, info, nil
}

func (c *canonCamera) Connected() bool { return c.online.Load() && c.ctx.Err() == nil }

func (c *canonCamera) watchConnection(information string) {
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
			ctx, stop := context.WithTimeout(c.ctx, 3*time.Second)
			var current ccapiDevice
			err := c.http.json(ctx, http.MethodGet, information, nil, &current)
			stop()
			c.op.Unlock()
			if err == nil && (current.SerialNumber != c.device.SerialNumber || current.GUID != c.device.GUID || current.ProductName != c.device.ProductName) {
				c.online.Store(false)
				c.cancel()
				return
			}
			var network net.Error
			if errors.As(err, &network) {
				c.online.Store(false)
				c.cancel()
				return
			}
		}
	}
}

func (c *canonCamera) snapshot(ctx context.Context, slot int) (*ccapiSnapshot, error) {
	files, err := c.media.list(ctx, slot)
	if err != nil {
		return nil, err
	}
	result := &ccapiSnapshot{slot: slot, datesFresh: true}
	groups := make(map[string]int)
	for _, file := range files {
		stem := strings.TrimSuffix(file.name, path.Ext(file.name))
		key := path.Dir(file.path) + "/" + strings.ToUpper(stem)
		index, exists := groups[key]
		if !exists {
			index = len(result.photos)
			groups[key] = index
			result.photos = append(result.photos, ccapiPhoto{})
		}
		result.photos[index].files = append(result.photos[index].files, file)
	}
	usedIDs := make(map[uint32]bool)
	for groupKey, index := range groups {
		photo := &result.photos[index]
		slices.SortFunc(photo.files, func(a, b ccapiFile) int { return strings.Compare(a.path, b.path) })
		var revision strings.Builder
		revision.WriteString(c.identity + ":" + strconv.Itoa(slot) + ":" + groupKey)
		for _, file := range photo.files {
			fmt.Fprintf(&revision, ":%s:%d:%s", file.path, file.size, file.modifiedDate)
		}
		sum := sha256.Sum256([]byte(revision.String()))
		photo.contentID = binary.BigEndian.Uint32(sum[:4]) & 0x7fffffff
		if photo.contentID == 0 {
			photo.contentID = 1
		}
		if usedIDs[photo.contentID] {
			return nil, errors.New("相机资源编号发生冲突，已停止建立索引")
		}
		usedIDs[photo.contentID] = true
		photo.id = c.identity + ":ccapi:" + strconv.Itoa(slot) + ":" + hex.EncodeToString(sum[:])
	}
	c.mu.Lock()
	c.index = result
	c.mu.Unlock()
	return result, nil
}

func (c *canonCamera) Dates(slot int) (string, error) {
	c.op.Lock()
	defer c.op.Unlock()
	ctx, cancel := context.WithTimeout(c.ctx, 90*time.Second)
	defer cancel()
	index, err := c.snapshot(ctx, slot)
	if err != nil {
		return "", err
	}
	dates := make([]string, 0)
	for _, photo := range index.photos {
		if day := ccapiPhotoDay(photo); day != "" && !slices.Contains(dates, day) {
			dates = append(dates, day)
		}
	}
	slices.Sort(dates)
	slices.Reverse(dates)
	return marshal(dates)
}

func ccapiPhotoDay(photo ccapiPhoto) string {
	var newest time.Time
	for _, file := range photo.files {
		if date, valid := ccapiFileTime(file.modifiedDate); valid && (newest.IsZero() || date.After(newest)) {
			newest = date
		}
	}
	if !newest.IsZero() {
		return newest.Format("2006-01-02")
	}
	return ""
}

func (c *canonCamera) ListPage(slot int, cursor int64) (string, error) {
	return c.ListNewestPage(slot, "", cursor)
}

func (c *canonCamera) ListNewestPage(slot int, day string, offset int64) (string, error) {
	if slot < 1 || offset < 0 {
		return "", errors.New("存储卡或分页游标无效")
	}
	if day != "" {
		if _, err := time.Parse("2006-01-02", day); err != nil {
			return "", errors.New("文件日期无效")
		}
	}
	c.op.Lock()
	defer c.op.Unlock()
	c.mu.Lock()
	index := c.index
	c.mu.Unlock()
	if offset == 0 && (index == nil || index.slot != slot || !index.datesFresh) {
		ctx, cancel := context.WithTimeout(c.ctx, 90*time.Second)
		defer cancel()
		var err error
		index, err = c.snapshot(ctx, slot)
		if err != nil {
			return "", err
		}
	}
	if index == nil || index.slot != slot {
		return "", errors.New("相机媒体索引已变化，请刷新后继续")
	}
	if offset > 0 && index.pagingDay != day {
		return "", errors.New("分页日期已变化，请从第一页重新读取")
	}
	if offset == 0 {
		index.pagingDay = day
	}
	index.datesFresh = false
	filtered := make([]ccapiPhoto, 0, len(index.photos))
	for _, photo := range index.photos {
		if day == "" || ccapiPhotoDay(photo) == day {
			filtered = append(filtered, photo)
		}
	}
	if offset > int64(len(filtered)) {
		return "", errors.New("照片分页已变化，请刷新后继续")
	}
	until := min(offset+contentsPageSize, int64(len(filtered)))
	rows := make([]map[string]any, 0, until-offset)
	for _, photo := range filtered[offset:until] {
		files := make([]map[string]any, 0, len(photo.files))
		for i, file := range photo.files {
			var modified int64
			var fileDay string
			if date, valid := ccapiFileTime(file.modifiedDate); valid {
				modified, fileDay = date.UnixMilli(), date.Format("2006-01-02")
			}
			files = append(files, map[string]any{"fileId": i + 1, "name": file.name, "cameraPath": file.path, "size": file.size, "cameraDay": "", "fileModifiedAt": modified, "fileDay": fileDay})
		}
		rows = append(rows, map[string]any{"id": photo.id, "contentId": photo.contentID, "slot": slot, "capturedAt": 0, "groupId": 0, "files": files})
	}
	next := until
	if until == int64(len(filtered)) {
		next = 0
	}
	return marshal(map[string]any{"items": rows, "nextCursor": next})
}

func (c *canonCamera) lookup(slot, contentID, fileID int) (ccapiFile, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.index != nil && c.index.slot == slot {
		for _, photo := range c.index.photos {
			if photo.contentID == uint32(contentID) && fileID > 0 && fileID <= len(photo.files) {
				return photo.files[fileID-1], nil
			}
		}
	}
	return ccapiFile{}, errors.New("照片已不在当前相机索引，请刷新后再试")
}

func (c *canonCamera) Preview(slot, contentID, fileID int, target string, large bool) error {
	c.op.Lock()
	defer c.op.Unlock()
	file, err := c.lookup(slot, contentID, fileID)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(c.ctx, 25*time.Second)
	defer cancel()
	current, err := c.media.fileInfo(ctx, file.path)
	if err != nil {
		return err
	}
	if current.FileSize != file.size || current.LastModifiedDate != file.modifiedDate {
		return errors.New("相机照片已变化，请刷新后再查看")
	}
	return c.media.preview(ctx, file, target, large)
}

func (c *canonCamera) Download(raw, target string, progress Progress) (string, error) {
	var request downloadRequest
	if err := json.Unmarshal([]byte(raw), &request); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(c.ctx, 3*time.Minute)
	c.mu.Lock()
	c.transferCancel = cancel
	c.mu.Unlock()
	defer func() {
		cancel()
		c.mu.Lock()
		c.transferCancel = nil
		c.mu.Unlock()
	}()
	c.op.Lock()
	defer c.op.Unlock()
	if err := ctx.Err(); err != nil {
		return "", err
	}
	file, err := c.lookup(int(request.Slot), int(request.ContentID), int(request.FileID))
	if err != nil {
		return "", err
	}
	if request.Size != uint64(file.size) {
		return "", errors.New("下载资源长度与当前索引不一致")
	}
	return c.media.download(ctx, file, target, progress)
}

func (c *canonCamera) Properties() (string, error) {
	c.op.Lock()
	defer c.op.Unlock()
	ctx, cancel := context.WithTimeout(c.ctx, 20*time.Second)
	defer cancel()
	rows := make([]map[string]any, 0)
	seen := make(map[int]bool)
	for _, setting := range ccapiSettings {
		if seen[setting.code] || c.resources[http.MethodGet+":"+setting.operation] == "" {
			continue
		}
		state, err := readCCAPISetting(ctx, c.http, c.resources, setting)
		if err != nil {
			if errors.Is(err, errCCAPISettingValue) {
				continue
			}
			return "", err
		}
		info, _ := cameraPropertyInfo(uint16(setting.code))
		choices := make([]map[string]any, 0, len(state.order))
		for _, value := range state.order {
			if _, valid := state.choices[value]; !valid {
				continue
			}
			choices = append(choices, map[string]any{"value": value, "label": valueLabel(info, value)})
		}
		rows = append(rows, map[string]any{"code": setting.code, "name": setting.name, "value": state.current, "label": valueLabel(info, state.current),
			"writable": c.resources[http.MethodPut+":"+setting.operation] == state.path && len(choices) > 0, "choices": choices})
		seen[setting.code] = true
	}
	return marshal(rows)
}

func (c *canonCamera) SetProperty(code int, value int64) error {
	if !c.op.TryLock() {
		return errors.New("相机正在处理传输，请稍后修改参数")
	}
	defer c.op.Unlock()
	ctx, cancel := context.WithTimeout(c.ctx, 20*time.Second)
	defer cancel()
	for _, setting := range ccapiSettings {
		if setting.code == code && c.resources[http.MethodGet+":"+setting.operation] != "" {
			return setCCAPISetting(ctx, c.http, c.resources, setting, value)
		}
	}
	return errors.New("当前机身未开放此参数")
}

func (c *canonCamera) CancelTransfer() {
	c.mu.Lock()
	cancel := c.transferCancel
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}
