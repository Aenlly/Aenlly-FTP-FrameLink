package ptpip

import (
	"context"
	"io"
)

// Captured images to the host (docs/protocol.md, "Captured images to the host"):
// with the store destination on Host or HostAndCard the camera queues every
// shot under one fixed handle until the host fetches it.
const (
	SonyPropStoreDestination uint16 = 0xD222
	SonyPropCapturedImages   uint16 = 0xD215 // bit 15: an image is ready; low 15 bits: count

	SonyStoreHost        = 0x01
	SonyStoreCard        = 0x10
	SonyStoreHostAndCard = 0x11

	SonyPropTransferSize uint16 = 0xD268 // SonyTransferOriginal or SonyTransferSmall
	SonyTransferOriginal        = 1
	SonyTransferSmall           = 2

	sonyCapturedHandle = 0xFFFFC001
)

// SonyCapturedPending reports whether a captured image waits for the host,
// and the count the camera gives with it.
func (c *Client) SonyCapturedPending(ctx context.Context) (ready bool, n int, err error) {
	p, err := c.SonyGetProp(ctx, SonyPropCapturedImages)
	if err != nil {
		return false, 0, err
	}
	return p.Current.Int&0x8000 != 0, int(p.Current.Int & 0x7FFF), nil
}

// SonyGetCapturedImage downloads the next queued captured image into w.
func (c *Client) SonyGetCapturedImage(ctx context.Context, w io.Writer, progress func(int64)) (*ObjectInfo, int64, error) {
	info, err := c.GetObjectInfo(ctx, sonyCapturedHandle)
	if err != nil {
		return nil, 0, err
	}
	n, err := c.GetObject(ctx, sonyCapturedHandle, w, progress)
	return info, n, err
}
