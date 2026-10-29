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

// CapturePhoto separates shutter completion from optional host-file reception.
// Single mode is verified before pressing and restored only after a successful release.
func (s *Session) CapturePhoto(autofocus, single, receive bool) (result string, err error) {
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
	card := false
	store := int64(0)
	transferOriginal := false
	for i := range props {
		p := &props[i]
		if p.Code == driveModeProperty {
			drive = p
		}
		if p.Code == ptpip.SonyPropStoreDestination {
			store = p.Current.Int
			card = p.Current.Int == ptpip.SonyStoreCard || p.Current.Int == ptpip.SonyStoreHostAndCard
		}
		if p.Code == ptpip.SonyPropTransferSize {
			transferOriginal = p.Current.Int == ptpip.SonyTransferOriginal
		}
	}
	if store == ptpip.SonyStoreHost {
		if !receive {
			return "", errors.New("相机当前仅保存到电脑，请先在机身将保存位置改为存储卡，或开启拍后自动导入")
		}
		if !transferOriginal {
			return "", errors.New("相机仅保存到电脑，请先设置原尺寸传输，或改为存储卡保存")
		}
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
	return marshal(map[string]any{"shutterComplete": true, "single": single, "cardStorageConfirmed": card, "warning": warning})
}

// FocusAt accepts normalized coordinates inside the actual preview image.
// It never sends a full shutter press or silently changes the focus area.
func (s *Session) FocusAt(x, y float64) (err error) {
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
	if slices.Contains(controls, ptpip.SonyCtlRemoteTouch) && values[0xD284] == 1 {
		return c.SonyControl(ctx, ptpip.SonyCtlRemoteTouch, 6, ptpip.SonyValue{Int: position})
	}
	area := values[0xD22C]
	if !slices.Contains(controls, ptpip.SonyCtlAFAreaPosition) || !(area == 2 || area >= 0x101 && area <= 0x104) {
		return errors.New("当前对焦区域不支持点按，请在机身选择区或点对焦")
	}
	if err = c.SonyControl(ctx, ptpip.SonyCtlAFAreaPosition, 6, ptpip.SonyValue{Int: position}); err != nil {
		return err
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
	case <-time.After(300 * time.Millisecond):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
