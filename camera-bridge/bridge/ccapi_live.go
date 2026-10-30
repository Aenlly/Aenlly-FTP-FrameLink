package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/url"
	"time"
)

const ccapiLiveFrameLimit = 8 << 20

func (c *canonCamera) StartLiveView() error {
	if !c.Connected() {
		return errors.New("相机尚未连接")
	}
	start, err := c.resources.path(http.MethodPost, "shooting/liveview")
	if err != nil {
		return err
	}
	if _, err = c.resources.path(http.MethodGet, "shooting/liveview/flip"); err != nil {
		return err
	}
	c.StopLiveView()
	live, cancel := context.WithCancel(c.ctx)
	c.mu.Lock()
	c.liveContext, c.liveCancel = live, cancel
	c.mu.Unlock()
	ctx, stop := context.WithTimeout(live, 10*time.Second)
	defer stop()
	// Small is the documented baseline, not an advertised higher resolution.
	// Native JPEG dimensions are measured by the renderer after reception.
	if err = c.http.json(ctx, http.MethodPost, start, map[string]string{"liveviewsize": "small", "cameradisplay": "on"}, nil); err != nil {
		c.StopLiveView()
		return err
	}
	for attempt := 0; attempt < 20; attempt++ {
		frame, frameErr := c.readFrame(ctx)
		if frameErr == nil {
			c.mu.Lock()
			if c.liveContext == live && live.Err() == nil {
				c.firstFrame = frame
				c.mu.Unlock()
				return nil
			}
			c.mu.Unlock()
			return errors.New("取景启动已取消")
		}
		if ctx.Err() != nil {
			break
		}
		err = frameErr
		var status *ccapiHTTPError
		if !errors.As(frameErr, &status) || status.status != http.StatusServiceUnavailable {
			break
		}
		select {
		case <-ctx.Done():
		case <-time.After(150 * time.Millisecond):
		}
	}
	c.StopLiveView()
	return errors.Join(errors.New("机身取景尚未就绪"), err, ctx.Err())
}

func (c *canonCamera) readFrame(ctx context.Context) ([]byte, error) {
	resource, err := c.resources.path(http.MethodGet, "shooting/liveview/flip")
	if err != nil {
		return nil, err
	}
	response, err := c.http.request(ctx, http.MethodGet, resource, nil)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("机身未返回完整取景帧")
	}
	frame, err := io.ReadAll(io.LimitReader(response.Body, ccapiLiveFrameLimit+1))
	if err != nil {
		return nil, err
	}
	if len(frame) > ccapiLiveFrameLimit || len(frame) < 3 || frame[0] != 0xff || frame[1] != 0xd8 || frame[2] != 0xff {
		return nil, errors.New("机身未返回有效JPEG取景帧")
	}
	return frame, nil
}

func (c *canonCamera) NextFrame() ([]byte, error) {
	c.mu.Lock()
	ctx, frame := c.liveContext, c.firstFrame
	c.firstFrame = nil
	c.mu.Unlock()
	if ctx == nil || ctx.Err() != nil {
		return nil, errors.New("取景尚未启动")
	}
	if len(frame) > 0 {
		return frame, nil
	}
	request, stop := context.WithTimeout(ctx, 10*time.Second)
	defer stop()
	return c.readFrame(request)
}

func (c *canonCamera) LiveMetadata() string { return "" }

func (c *canonCamera) StopLiveView() {
	c.mu.Lock()
	cancel := c.liveCancel
	c.liveCancel, c.liveContext, c.firstFrame = nil, nil, nil
	c.mu.Unlock()
	if cancel == nil {
		return
	}
	cancel()
	if resource := c.resources[http.MethodPost+":shooting/liveview"]; resource != "" {
		ctx, stop := context.WithTimeout(context.Background(), 3*time.Second)
		defer stop()
		c.http.json(ctx, http.MethodPost, resource, map[string]string{"liveviewsize": "off", "cameradisplay": "keep"}, nil)
	}
}

func (c *canonCamera) Close() {
	c.online.Store(false)
	c.cancel()
	c.StopLiveView()
	c.http.close()
}

type ccapiLiveArea struct {
	PositionX      int
	PositionY      int
	PositionWidth  int
	PositionHeight int
}

func parseCCAPILiveArea(data []byte) (ccapiLiveArea, error) {
	// flipdetail may surround its JSON liveviewdata part with binary framing.
	// Decode exactly one object; do not treat arbitrary frame bytes as coordinates.
	key := bytes.Index(data, []byte("\"liveviewdata\""))
	if key < 0 {
		return ccapiLiveArea{}, errors.New("机身未返回取景坐标信息")
	}
	attempts := 0
	for start := bytes.LastIndexByte(data[:key], '{'); start >= 0 && start < key && attempts < 16; {
		attempts++
		var response struct {
			LiveViewData struct{ Image ccapiLiveArea }
		}
		if json.NewDecoder(bytes.NewReader(data[start:])).Decode(&response) == nil {
			area := response.LiveViewData.Image
			if area.PositionX >= 0 && area.PositionY >= 0 && area.PositionWidth > 0 && area.PositionHeight > 0 &&
				int64(area.PositionX)+int64(area.PositionWidth) <= math.MaxInt32 &&
				int64(area.PositionY)+int64(area.PositionHeight) <= math.MaxInt32 {
				return area, nil
			}
		}
		next := bytes.LastIndexByte(data[:start], '{')
		if next < 0 {
			break
		}
		start = next
	}
	return ccapiLiveArea{}, errors.New("机身取景坐标格式尚未识别，本次未发送对焦位置")
}

func (c *canonCamera) FocusAt(x, y float64) (err error) {
	if math.IsNaN(x) || math.IsNaN(y) || math.IsInf(x, 0) || math.IsInf(y, 0) || x < 0 || x > 1 || y < 0 || y > 1 {
		return errors.New("对焦位置不在画面内")
	}
	if !c.op.TryLock() {
		return errors.New("相机正在处理其他操作，请稍后对焦")
	}
	defer c.op.Unlock()
	detail, err := c.resources.path(http.MethodGet, "shooting/liveview/flipdetail")
	if err != nil {
		return err
	}
	position, err := c.resources.path(http.MethodPut, "shooting/liveview/afframeposition")
	if err != nil {
		return err
	}
	manual, err := c.resources.path(http.MethodPost, "shooting/control/shutterbutton/manual")
	if err != nil {
		return err
	}
	ctx, stop := context.WithTimeout(c.ctx, 12*time.Second)
	defer stop()
	query, err := c.media.queryPath(detail, url.Values{"kind": {"info"}})
	if err != nil {
		return err
	}
	response, err := c.http.request(ctx, http.MethodGet, query, nil)
	if err != nil {
		return err
	}
	data, readErr := io.ReadAll(io.LimitReader(response.Body, ccapiJSONLimit+1))
	response.Body.Close()
	if readErr != nil || len(data) > ccapiJSONLimit {
		return errors.Join(errors.New("机身取景坐标读取未完成"), readErr)
	}
	area, err := parseCCAPILiveArea(data)
	if err != nil {
		return err
	}
	px := area.PositionX + int(math.Round(x*float64(area.PositionWidth-1)))
	py := area.PositionY + int(math.Round(y*float64(area.PositionHeight-1)))
	if err = c.http.json(ctx, http.MethodPut, position, map[string]int{"positionx": px, "positiony": py}, nil); err != nil {
		return err
	}
	// A position write alone does not focus. Always release the half-press,
	// including when its acknowledgement times out; never send a full press here.
	defer func() {
		release, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		releaseErr := c.http.json(release, http.MethodPost, manual, map[string]any{"action": "release", "af": true}, nil)
		err = errors.Join(err, releaseErr)
	}()
	return c.http.json(ctx, http.MethodPost, manual, map[string]any{"action": "half_press", "af": true}, nil)
}
