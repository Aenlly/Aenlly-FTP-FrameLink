package ptpip

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"time"
)

// GetDeviceInfo may be called before a session is open.
func (c *Client) GetDeviceInfo(ctx context.Context) (*DeviceInfo, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpGetDeviceInfo, nil, nil, &buf); err != nil {
		return nil, err
	}
	return parseDeviceInfo(buf.Bytes())
}

// OpenSession opens session id (non-zero). An already open session is fine.
func (c *Client) OpenSession(ctx context.Context, id uint32) error {
	_, err := c.Transaction(ctx, OpOpenSession, []uint32{id}, nil, nil)
	if IsCode(err, RespSessionAlreadyOpen) {
		return nil
	}
	return err
}

// CloseSession closes the session.
func (c *Client) CloseSession(ctx context.Context) error {
	_, err := c.Transaction(ctx, OpCloseSession, nil, nil, nil)
	return err
}

func (c *Client) u32array(ctx context.Context, op uint16, params []uint32) ([]uint32, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, op, params, nil, &buf); err != nil {
		return nil, err
	}
	r := &reader{b: buf.Bytes()}
	out := r.u32array()
	return out, r.err
}

// GetStorageIDs lists storages. IDs whose low 16 bits are zero denote
// storages that are not present (e.g. an empty card slot).
func (c *Client) GetStorageIDs(ctx context.Context) ([]uint32, error) {
	return c.u32array(ctx, OpGetStorageIDs, nil)
}

// GetStorageInfo describes a storage.
func (c *Client) GetStorageInfo(ctx context.Context, id uint32) (*StorageInfo, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpGetStorageInfo, []uint32{id}, nil, &buf); err != nil {
		return nil, err
	}
	return parseStorageInfo(buf.Bytes())
}

// Parent values for GetObjectHandles.
const (
	ParentAll  uint32 = 0x00000000 // all objects in the storage, any depth
	ParentRoot uint32 = 0xFFFFFFFF // only objects in the root folder
)

// GetObjectHandles lists object handles in storage (0xFFFFFFFF = all storages).
func (c *Client) GetObjectHandles(ctx context.Context, storage uint32, format uint16, parent uint32) ([]uint32, error) {
	return c.u32array(ctx, OpGetObjectHandles, []uint32{storage, uint32(format), parent})
}

// GetObjectInfo describes an object.
func (c *Client) GetObjectInfo(ctx context.Context, handle uint32) (*ObjectInfo, error) {
	var buf bytes.Buffer
	if _, err := c.Transaction(ctx, OpGetObjectInfo, []uint32{handle}, nil, &buf); err != nil {
		return nil, err
	}
	return parseObjectInfo(buf.Bytes())
}

type countingWriter struct {
	w        io.Writer
	n        int64
	progress func(int64)
}

func (cw *countingWriter) Write(p []byte) (int, error) {
	n, err := cw.w.Write(p)
	cw.n += int64(n)
	if cw.progress != nil {
		cw.progress(cw.n)
	}
	return n, err
}

// GetObject downloads a whole object. progress (optional) gets bytes so far.
func (c *Client) GetObject(ctx context.Context, handle uint32, w io.Writer, progress func(int64)) (int64, error) {
	cw := &countingWriter{w: w, progress: progress}
	_, err := c.Transaction(ctx, OpGetObject, []uint32{handle}, nil, cw)
	return cw.n, err
}

// GetPartialObject downloads up to max bytes starting at offset and returns
// the number of bytes the camera sent.
func (c *Client) GetPartialObject(ctx context.Context, handle, offset, max uint32, w io.Writer) (uint32, error) {
	cw := &countingWriter{w: w}
	params, err := c.Transaction(ctx, OpGetPartialObject, []uint32{handle, offset, max}, nil, cw)
	return partialResult(params, cw.n, err)
}

// DownloadObject transfers an object to w in chunks when the camera supports
// a partial-read operation (so progress is reported and a stalled chunk is
// detected early), otherwise with a single GetObject. On Sony bodies the
// 64-bit SDIO_GetPartialLargeObject is preferred (also works for >= 4 GiB);
// the a6700 in remote-transfer mode rejects the standard GetPartialObject
// with InvalidObjectHandle, in which case GetObject is used. size is the
// expected size, or 0 if unknown. It returns the number of bytes written.
func (c *Client) DownloadObject(ctx context.Context, di *DeviceInfo, handle uint32, size int64, w io.Writer, progress func(int64)) (int64, error) {
	const chunk = 4 << 20
	if di == nil || size <= 0 {
		return c.GetObject(ctx, handle, w, progress)
	}
	var part func(offset int64) (uint32, error)
	switch {
	case di.IsSony() && di.Supports(OpSonyGetPartialLargeObject):
		part = func(off int64) (uint32, error) { return c.SonyGetPartialLargeObject(ctx, handle, off, chunk, w) }
	case di.Supports(OpGetPartialObject) && size < 0xFFFFFFFF:
		part = func(off int64) (uint32, error) { return c.GetPartialObject(ctx, handle, uint32(off), chunk, w) }
	default:
		return c.GetObject(ctx, handle, w, progress)
	}
	var done int64
	for done < size {
		n, err := part(done)
		done += int64(n)
		if err != nil {
			if done == 0 && IsCode(err, RespInvalidObjectHandle) {
				return c.GetObject(ctx, handle, w, progress)
			}
			return done, err
		}
		if n == 0 {
			return done, errors.New("ptpip: camera returned no data")
		}
		if progress != nil {
			progress(done)
		}
	}
	return done, nil
}

// Sony protocol version requested from SDIO_GetExtDeviceInfo ("2020 models or later").
const sonyProtocolV3 = 0x12C

// SonyHandshake performs the SDIO connect sequence Sony bodies expect from a
// "PC remote" host (phases 1 and 2, extended device info, phase 3), as
// documented by libgphoto2. It returns the extended capability lists.
func (c *Client) SonyHandshake(ctx context.Context) (*SonyExtInfo, error) {
	for _, phase := range []uint32{1, 2} {
		if _, err := c.Transaction(ctx, OpSonySDIOConnect, []uint32{phase, 0, 0}, nil, nil); err != nil {
			return nil, err
		}
	}
	var info *SonyExtInfo
	for try := 0; try < 20; try++ {
		var buf bytes.Buffer
		if _, err := c.Transaction(ctx, OpSonyGetExtDevInfo, []uint32{sonyProtocolV3, 1}, nil, &buf); err != nil {
			return nil, err
		}
		parsed, err := parseSonyExtInfo(buf.Bytes())
		if err != nil {
			return nil, err
		}
		info = parsed
		if len(info.Properties)+len(info.Controls) > 0 {
			break
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	if _, err := c.Transaction(ctx, OpSonySDIOConnect, []uint32{3, 0, 0}, nil, nil); err != nil {
		return info, err
	}
	return info, nil
}

// SonyGetPartialLargeObject downloads up to max bytes starting at a 64-bit
// offset (SDIO_GetPartialLargeObject, 0x9211) and returns the number of bytes
// the camera sent. Parameters: handle, offset low, offset high, max.
func (c *Client) SonyGetPartialLargeObject(ctx context.Context, handle uint32, offset int64, max uint32, w io.Writer) (uint32, error) {
	cw := &countingWriter{w: w}
	params, err := c.Transaction(ctx, OpSonyGetPartialLargeObject, []uint32{handle, uint32(offset), uint32(offset >> 32), max}, nil, cw)
	return partialResult(params, cw.n, err)
}

// SonySetContentsTransferMode sends SDIO_SetContentsTransferMode (0x9212) with
// (2, 1, 0). On the a6700 this enables remote content transfer and exposes the
// memory cards: without it storage operations fail with StoreNotAvailable.
func (c *Client) SonySetContentsTransferMode(ctx context.Context) error {
	_, err := c.Transaction(ctx, OpSonySetContentsTransferMode, []uint32{2, 1, 0}, nil, nil)
	return err
}

// SonyDisableContentsTransferMode sends SDIO_SetContentsTransferMode (0x9212)
// with (2, 0, 1), which releases the camera UI again.
func (c *Client) SonyDisableContentsTransferMode(ctx context.Context) error {
	_, err := c.Transaction(ctx, OpSonySetContentsTransferMode, []uint32{2, 0, 1}, nil, nil)
	return err
}

// partialResult returns the bytes actually written. The offset of the next
// chunk depends on it, so a response parameter that disagrees is an error
// rather than something to trust (it would silently corrupt the file).
func partialResult(params []uint32, written int64, err error) (uint32, error) {
	if err != nil {
		return uint32(written), err
	}
	if len(params) > 0 && int64(params[0]) != written {
		return uint32(written), fmt.Errorf("ptpip: camera reported %d bytes but sent %d", params[0], written)
	}
	return uint32(written), nil
}
