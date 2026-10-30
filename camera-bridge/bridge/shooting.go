package bridge

import (
	"context"
	"errors"
	"fmt"
	"math"
	"slices"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

const driveModeProperty uint16 = 0x5013
const driveSingle int64 = 1

// applyCaptureStorage keeps record-only shooting out of the host transfer queue.
// Host-only pending files must be received before changing their destination.
func applyCaptureStorage(ctx context.Context, c *ptpip.Client, props []ptpip.SonyProp, receive bool) (int64, error) {
	wanted := int64(ptpip.SonyStoreCard)
	if receive {
		wanted = ptpip.SonyStoreHostAndCard
	}
	var descriptor *ptpip.SonyProp
	for i := range props {
		if props[i].Code == ptpip.SonyPropStoreDestination {
			descriptor = &props[i]
		}
	}
	if descriptor == nil {
		return 0, errors.New("机身未提供保存位置，请在相机设置为仅拍摄装置后再使用")
	}
	if descriptor.Current.Int == wanted {
		return wanted, nil
	}
	if descriptor.Current.Int == ptpip.SonyStoreHost {
		_, pending, err := c.SonyCapturedPending(ctx)
		if err != nil {
			return 0, errors.New("仅电脑保存的待传状态未能确认，暂不更改保存位置")
		}
		if pending > 0 {
			return 0, errors.New("机身有仅电脑保存的待传照片，请先完成接收，暂不更改保存位置")
		}
	}
	if !descriptor.Writable || descriptor.Enabled != 1 || !slices.ContainsFunc(descriptor.Settable, func(v ptpip.SonyValue) bool { return v.Int == wanted }) {
		return 0, errors.New("机身未允许切换保存位置，请在遥控拍摄设置中选择仅拍摄装置；自动导入需选择目的地与拍摄装置")
	}
	if err := c.SonySetProp(ctx, descriptor.Code, descriptor.Type, ptpip.SonyValue{Int: wanted}); err != nil {
		return 0, fmt.Errorf("保存位置未能确认，本次不拍摄，请在相机核对：%w", err)
	}
	actual, err := c.SonyGetProp(ctx, descriptor.Code)
	if err != nil || actual.Current.Int != wanted {
		return 0, errors.New("机身尚未确认保存位置，本次不拍摄，请在相机核对")
	}
	return wanted, nil
}

func (s *Session) ConfigureCaptureStorage(receive bool) (string, error) {
	if backend := s.alternate(); backend != nil {
		return backend.ConfigureCaptureStorage(receive)
	}
	if !s.op.TryLock() {
		return "", errors.New("相机正忙，请稍后再更改保存位置")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 10*time.Second)
	defer cancel()
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return "", err
	}
	store, err := applyCaptureStorage(ctx, c, props, receive)
	if err != nil {
		return "", err
	}
	return marshal(map[string]any{"storeDestination": store})
}

// CapturePhoto separates shutter completion from optional host-file reception.
// Single mode is verified before pressing and restored only after a successful release.
func (s *Session) CapturePhoto(autofocus, single, receive bool) (result string, err error) {
	if backend := s.alternate(); backend != nil {
		return backend.CapturePhoto(autofocus, single, receive)
	}
	if !s.op.TryLock() {
		return "", errors.New("相机正在处理传输，本次快门未发送，请稍后再拍")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return "", fmt.Errorf("未能核对拍摄设置，本次快门未发送：%w", err)
	}
	var drive *ptpip.SonyProp
	for i := range props {
		p := &props[i]
		if p.Code == driveModeProperty {
			drive = p
		}
	}
	if _, err = applyCaptureStorage(ctx, c, props, receive); err != nil {
		return "", err
	}
	changed := false
	shutterStarted := false
	defer func() {
		if changed && !shutterStarted {
			rctx, stop := context.WithTimeout(parent, 5*time.Second)
			defer stop()
			err = errors.Join(err, c.SonySetProp(rctx, drive.Code, drive.Type, drive.Current))
		}
	}()
	if single {
		if drive == nil {
			return "", errors.New("机身未提供单张模式，请在拍摄设置选择跟随机身，并在相机确认单张拍摄")
		}
		if drive.Current.Int != driveSingle {
			canSet := func(value int64) bool {
				return slices.ContainsFunc(drive.Settable, func(v ptpip.SonyValue) bool { return v.Int == value })
			}
			if !drive.Writable || drive.Enabled != 1 || !canSet(driveSingle) || !canSet(drive.Current.Int) {
				return "", errors.New("相机当前为连拍或定时模式，请先在机身切换单张拍摄")
			}
			if err = c.SonySetProp(ctx, drive.Code, drive.Type, ptpip.SonyValue{Int: driveSingle}); err != nil {
				return "", fmt.Errorf("单张模式未能生效，本次快门未发送：%w", err)
			}
			changed = true
			actual, e := c.SonyGetProp(ctx, driveModeProperty)
			if e != nil || actual.Current.Int != driveSingle {
				return "", errors.New("相机尚未确认单张模式，本次快门未发送，请核对机身设置")
			}
		}
	}
	shutterStarted = true
	if err = c.SonyCapture(ctx, autofocus); err != nil {
		// Do not restore a burst mode when the shutter release is uncertain.
		return "", fmt.Errorf("快门结果待确认，请先查看机身或新照片，不要连续重拍：%w", err)
	}
	warning := ""
	if changed {
		rctx, stop := context.WithTimeout(parent, 5*time.Second)
		defer stop()
		if e := c.SonySetProp(rctx, drive.Code, drive.Type, drive.Current); e != nil {
			warning = "快门已完成，机身拍摄模式未恢复，请在相机核对"
		} else if actual, e := c.SonyGetProp(rctx, drive.Code); e != nil || actual.Current.Int != drive.Current.Int {
			warning = "快门已完成，机身拍摄模式恢复待确认，请在相机核对"
		}
	}
	return marshal(map[string]any{"shutterComplete": true, "single": single, "cardStorageConfirmed": true, "warning": warning})
}

// FocusAt accepts normalized coordinates inside the actual preview image.
// It selects the position, pulses AF and releases half-press without a full shutter press.
func (s *Session) FocusAt(x, y float64) (err error) {
	if backend := s.alternate(); backend != nil {
		return backend.FocusAt(x, y)
	}
	if math.IsNaN(x) || math.IsNaN(y) || x < 0 || x > 1 || y < 0 || y > 1 {
		return errors.New("对焦位置无效")
	}
	if !s.op.TryLock() {
		return errors.New("相机正忙，请稍后再对焦")
	}
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return err
	}
	s.mu.Lock()
	controls := slices.Clone(s.controls)
	s.mu.Unlock()
	ctx, cancel := context.WithTimeout(parent, 6*time.Second)
	defer cancel()
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return err
	}
	values := make(map[uint16]int64, len(props))
	for _, p := range props {
		values[p.Code] = p.Current.Int
	}
	if values[0x500A] == 1 {
		return errors.New("相机当前为手动对焦，请先切换自动对焦")
	}
	position := int64(uint32(math.Round(x*639))<<16 | uint32(math.Round(y*479)))
	area := values[0xD22C]
	pointControl := slices.Contains(controls, ptpip.SonyCtlAFAreaPosition)
	remoteTouch := slices.Contains(controls, ptpip.SonyCtlRemoteTouch) && values[0xD284] == 1
	if remoteTouch {
		if err = c.SonyControl(ctx, ptpip.SonyCtlRemoteTouch, 6, ptpip.SonyValue{Int: position}); err != nil {
			return err
		}
	} else if pointControl && (area == 2 || area >= 0x101 && area <= 0x104) {
		if err = c.SonyControl(ctx, ptpip.SonyCtlAFAreaPosition, 6, ptpip.SonyValue{Int: position}); err != nil {
			return err
		}
	} else {
		return errors.New("当前对焦区域不支持点按，请在机身选择区或点对焦")
	}
	defer func() {
		rctx, stop := context.WithTimeout(context.WithoutCancel(parent), 3*time.Second)
		defer stop()
		err = errors.Join(err, c.SonyButton(rctx, ptpip.SonyCtlShutterHalf, false))
	}()
	if err = c.SonyButton(ctx, ptpip.SonyCtlShutterHalf, true); err != nil {
		return err
	}
	select {
	case <-time.After(800 * time.Millisecond):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// ShootingState exposes a small read-only snapshot for validation, never credentials.
func (s *Session) ShootingState() (string, error) {
	if backend := s.alternate(); backend != nil {
		return backend.ShootingState()
	}
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(parent, 10*time.Second)
	defer cancel()
	props, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return "", err
	}
	names := map[uint16]string{0x5013: "driveMode", 0x500A: "focusMode", 0x5009: "focusDistance", 0xD22C: "focusArea", 0xD284: "remoteTouchUsable", 0xD222: "storeDestination"}
	result := map[string]any{}
	for _, p := range props {
		if p.Code == ptpip.SonyPropCapturedImages {
			result["pendingReady"] = p.Current.Int&0x8000 != 0
			result["pendingCount"] = p.Current.Int & 0x7fff
		}
		if name, ok := names[p.Code]; ok {
			result[name] = p.Current.Int
		}
		if p.Code == ptpip.SonyPropStoreDestination {
			result["storeWritable"] = p.Writable
			result["storeEnabled"] = p.Enabled
		}
	}
	ready, pending, pendingErr := c.SonyCapturedPending(ctx)
	result["pendingStatusKnown"] = pendingErr == nil
	if pendingErr == nil {
		result["pendingReady"] = ready
		result["pendingCount"] = pending
	}
	return marshal(result)
}
