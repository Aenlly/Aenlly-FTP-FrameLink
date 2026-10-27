package ptpip

import (
	"context"
	"errors"
	"slices"
	"time"
)

// Control codes for SonyControl; button types take sonyReleased/sonyPressed.
// Only codes verified on the a6700 are named here.
const (
	SonyCtlShutterHalf = 0xD2C1 // focus + AE lock while pressed
	SonyCtlShutterFull = 0xD2C2
	SonyCtlMovie       = 0xD2C8 // press + release toggles recording

	// Positions are x<<16 | y on a 640×480 grid.
	SonyCtlAFAreaPosition    = 0xD2DC // u32 position; needs a spot or zone focus area
	SonyCtlRemoteTouch       = 0xD2E4 // u32 position; usable while 0xD284 is 1
	SonyCtlRemoteTouchCancel = 0xD2E5 // button; usable while 0xD285 is 1
	SonyCtlButton            = 0xD309 // u32 button<<16 | 1 up / 2 down; buttons listed in 0xD208
	SonyCtlDial              = 0xD30B // i32 dial<<16 | int16 steps (+ = clockwise); dials in 0xD20A
)

const (
	sonyReleased = 1
	sonyPressed  = 2
)

// sonyControls gives the PTP datatype of each named control and the property
// whose Enabled flag says whether it is usable now (0 = none). The camera
// returns no descriptor for controls, so this is kept by hand.
var sonyControls = map[uint16]struct{ typ, status uint16 }{
	0xD2C1: {4, 0}, 0xD2C2: {4, 0}, 0xD2C8: {4, 0},
	0xD2DC: {6, 0}, 0xD2E4: {6, 0xD284}, 0xD2E5: {4, 0xD285},
	0xD309: {6, 0xD208}, 0xD30B: {5, 0xD20A},
}

// SonyControlInfo returns the datatype and status property of a control code.
func SonyControlInfo(code uint16) (typ, status uint16, ok bool) {
	e, ok := sonyControls[code]
	return e.typ, e.status, ok
}

// SonyButton presses or releases a button-type control.
func (c *Client) SonyButton(ctx context.Context, code uint16, down bool) error {
	v := int64(sonyReleased)
	if down {
		v = sonyPressed
	}
	return c.SonyControl(ctx, code, 4, SonyValue{Int: v})
}

// sonyHold runs fn with press/release helpers and releases whatever is still
// pressed afterwards, newest first, even if fn or ctx failed (a stuck shutter
// button would keep firing).
func (c *Client) sonyHold(ctx context.Context, fn func(press, release func(code uint16) error) error) (err error) {
	var held []uint16
	defer func() {
		rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		for i := len(held) - 1; i >= 0; i-- {
			err = errors.Join(err, c.SonyButton(rctx, held[i], false))
		}
	}()
	press := func(code uint16) error {
		held = append(held, code) // before sending: a failed press may still have reached the camera
		return c.SonyButton(ctx, code, true)
	}
	release := func(code uint16) error {
		if err := c.SonyButton(ctx, code, false); err != nil {
			return err
		}
		if i := slices.Index(held, code); i >= 0 {
			held = slices.Delete(held, i, i+1)
		}
		return nil
	}
	return fn(press, release)
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	select {
	case <-time.After(d):
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// SonyCapture takes one picture, with autofocus (half-press first) or without.
// Without autofocus the a6700 only fires in manual focus; in AF modes it
// acknowledges the press but does not release.
func (c *Client) SonyCapture(ctx context.Context, autofocus bool) error {
	return c.sonyHold(ctx, func(press, release func(uint16) error) error {
		if autofocus {
			if err := press(SonyCtlShutterHalf); err != nil {
				return err
			}
			if err := sleepCtx(ctx, 500*time.Millisecond); err != nil {
				return err
			}
		}
		if err := press(SonyCtlShutterFull); err != nil {
			return err
		}
		if err := sleepCtx(ctx, 35*time.Millisecond); err != nil {
			return err
		}
		if err := release(SonyCtlShutterFull); err != nil {
			return err
		}
		if !autofocus {
			return nil
		}
		if err := sleepCtx(ctx, time.Second); err != nil {
			return err
		}
		return release(SonyCtlShutterHalf)
	})
}

// SonyToggleMovie starts or stops movie recording.
func (c *Client) SonyToggleMovie(ctx context.Context) error {
	return c.sonyHold(ctx, func(press, release func(uint16) error) error {
		if err := press(SonyCtlMovie); err != nil {
			return err
		}
		return release(SonyCtlMovie)
	})
}

// SonyPressButton presses and releases a camera button (an id from 0xD208,
// e.g. 5 = enter). The release is sent even when the press failed or ctx ended.
func (c *Client) SonyPressButton(ctx context.Context, button uint16) (err error) {
	send := func(ctx context.Context, state int64) error {
		return c.SonyControl(ctx, SonyCtlButton, 6, SonyValue{Int: int64(button)<<16 | state})
	}
	defer func() {
		rctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()
		err = errors.Join(err, send(rctx, sonyReleased))
	}()
	return send(ctx, sonyPressed)
}

// SonyTurnDial turns a dial (an id from 0xD20A, e.g. 0x4002 = front dial) by
// steps; positive is clockwise.
func (c *Client) SonyTurnDial(ctx context.Context, dial uint16, steps int16) error {
	return c.SonyControl(ctx, SonyCtlDial, 5, SonyValue{Int: int64(dial)<<16 | int64(uint16(steps))})
}
