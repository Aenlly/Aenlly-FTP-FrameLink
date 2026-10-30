package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

type ccapiEnumState struct {
	value   string
	raw     json.RawMessage
	ability map[string]json.RawMessage
}

func (c *canonCamera) readEnum(ctx context.Context, resource string) (ccapiEnumState, error) {
	var response struct {
		Value   json.RawMessage
		Ability []json.RawMessage
	}
	state := ccapiEnumState{ability: make(map[string]json.RawMessage)}
	if err := c.http.json(ctx, http.MethodGet, resource, nil, &response); err != nil {
		return state, err
	}
	value, valid := ccapiScalar(response.Value)
	if !valid {
		return state, errors.New("机身未提供可识别的拍摄设置")
	}
	state.value, state.raw = value, response.Value
	for _, choice := range response.Ability {
		if value, valid := ccapiScalar(choice); valid {
			state.ability[value] = choice
		}
	}
	return state, nil
}

func canonSingleDrive(value string) bool {
	value = strings.NewReplacer("_", "", "-", "", " ", "").Replace(strings.ToLower(value))
	return value == "single" || value == "singleshooting"
}

func (c *canonCamera) cardAvailable(ctx context.Context) error {
	root, err := c.resources.path(http.MethodGet, "contents")
	if err != nil {
		return err
	}
	cards, err := c.media.paths(ctx, root)
	if err != nil {
		return err
	}
	if len(cards) == 0 {
		return errors.New("相机未提供可用存储卡，本次快门未发送")
	}
	return nil
}

func (c *canonCamera) ConfigureCaptureStorage(receive bool) (string, error) {
	if receive {
		return "", errors.New("CCAPI连接不提供电脑待传队列，请从相机图库选择导入")
	}
	if !c.op.TryLock() {
		return "", errors.New("相机正忙，请稍后检查存储卡")
	}
	defer c.op.Unlock()
	ctx, stop := context.WithTimeout(c.ctx, 10*time.Second)
	defer stop()
	if err := c.cardAvailable(ctx); err != nil {
		return "", err
	}
	// CCAPI shutter capture uses camera media; there is no Sony host queue or
	// Sony destination property to write on this transport.
	return marshal(map[string]any{"cameraStorageAvailable": true, "hostReception": false, "protocol": "CCAPI"})
}

func (c *canonCamera) Capture(autofocus bool) error {
	_, err := c.CapturePhoto(autofocus, false, false)
	return err
}

func (c *canonCamera) CapturePhoto(autofocus, single, receive bool) (result string, err error) {
	if receive {
		return "", errors.New("此连接不提供拍后电脑队列，请关闭自动导入后拍摄")
	}
	if !c.op.TryLock() {
		return "", errors.New("相机正在处理传输，本次快门未发送")
	}
	defer c.op.Unlock()
	ctx, stop := context.WithTimeout(c.ctx, 20*time.Second)
	defer stop()
	shutter, err := c.resources.path(http.MethodPost, "shooting/control/shutterbutton")
	if err != nil {
		return "", err
	}
	if err = c.cardAvailable(ctx); err != nil {
		return "", err
	}
	var restore func() error
	started := false
	defer func() {
		if restore != nil && !started {
			err = errors.Join(err, restore())
		}
	}()
	if single {
		drivePath, e := c.resources.path(http.MethodGet, "shooting/settings/drive")
		if e != nil {
			return "", errors.New("机身未提供单张模式核对，本次快门未发送")
		}
		drive, e := c.readEnum(ctx, drivePath)
		if e != nil {
			return "", e
		}
		if !canonSingleDrive(drive.value) {
			if c.resources[http.MethodPut+":shooting/settings/drive"] != drivePath {
				return "", errors.New("相机未允许切换单张，请先在机身调整拍摄方式")
			}
			var chosen json.RawMessage
			for value, raw := range drive.ability {
				if canonSingleDrive(value) {
					chosen = raw
					break
				}
			}
			if chosen == nil {
				return "", errors.New("机身当前未提供单张候选，本次快门未发送")
			}
			if err = c.http.json(ctx, http.MethodPut, drivePath, map[string]json.RawMessage{"value": chosen}, nil); err != nil {
				return "", err
			}
			restore = func() error {
				cleanup, cancel := context.WithTimeout(c.ctx, 5*time.Second)
				defer cancel()
				if e := c.http.json(cleanup, http.MethodPut, drivePath, map[string]json.RawMessage{"value": drive.raw}, nil); e != nil {
					return e
				}
				actual, e := c.readEnum(cleanup, drivePath)
				if e != nil || actual.value != drive.value {
					return errors.New("拍摄方式恢复尚未确认，请在机身核对")
				}
				return nil
			}
			actual, e := c.readEnum(ctx, drivePath)
			if e != nil || !canonSingleDrive(actual.value) {
				return "", errors.New("机身未确认单张模式，本次快门未发送")
			}
		}
	}
	payload, _ := json.Marshal(map[string]bool{"af": autofocus})
	started = true
	response, err := c.http.request(ctx, http.MethodPost, shutter, payload)
	if err != nil {
		return "", fmt.Errorf("快门结果待确认，请查看机身或新照片，不要连续重拍：%w", err)
	}
	status := response.StatusCode
	response.Body.Close()
	warning := ""
	if status != http.StatusOK {
		warning = "机身已接收拍摄请求，完成状态待确认，请先查看相机"
	} else if restore != nil {
		if e := restore(); e != nil {
			warning = "快门请求已完成，拍摄方式恢复待确认，请在机身核对"
		}
	}
	c.mu.Lock()
	c.index = nil
	c.mu.Unlock()
	return marshal(map[string]any{"shutterComplete": status == http.StatusOK, "single": single, "cardStorageConfirmed": true, "warning": warning})
}

func (c *canonCamera) ShootingState() (string, error) {
	// This channel has no host queue. Unknown queue counts are not reported as zero.
	return marshal(map[string]any{"hostQueueAvailable": false, "protocol": "CCAPI"})
}

func (c *canonCamera) FetchCaptured(target string) (string, error) {
	return "", errors.New("CCAPI连接不提供电脑待传文件，请从相机图库导入")
}
