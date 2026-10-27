package ptpip

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
)

// SonyPropLiveViewURL holds the camera's live-view stream URL, e.g.
// "http://localhost:60152/liveviewstream?...".
const SonyPropLiveViewURL = 0xD278

// sonyMaxLiveViewFrame caps one frame; real frames are well below 1 MiB.
const sonyMaxLiveViewFrame = 8 << 20

// SonyLiveView is an open live-view stream. Not safe for concurrent use.
type SonyLiveView struct {
	body io.ReadCloser
	r    *bufio.Reader
}

// SonyLiveViewFrame is one frame: a JPEG plus the camera's overlay metadata
// (focus frames, level, ...; see ParseSonyLiveViewMeta).
type SonyLiveViewFrame struct {
	JPEG, Meta []byte
}

// SonyOpenLiveView reads the stream URL (prop 0xD278) and opens it. The URL
// names "localhost", so the request goes to the camera's host through the
// same dialer as PTP/IP (and so through the SSH tunnel if one is used).
func (c *Client) SonyOpenLiveView(ctx context.Context) (*SonyLiveView, error) {
	ps, err := c.SonyGetProps(ctx, false)
	if err != nil {
		return nil, err
	}
	var raw string
	for _, p := range ps {
		if p.Code == SonyPropLiveViewURL {
			raw = p.Current.Str
		}
	}
	if raw == "" {
		return nil, fmt.Errorf("ptpip: camera has no live-view URL (prop 0x%04X)", SonyPropLiveViewURL)
	}
	u, err := url.Parse(raw)
	if err != nil || u.Port() == "" {
		return nil, fmt.Errorf("ptpip: bad live-view URL %q", raw)
	}
	host, _, err := net.SplitHostPort(c.addr)
	if err != nil {
		return nil, err
	}
	target := net.JoinHostPort(host, u.Port())
	hc := &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return c.dial(ctx, network, target)
		},
		DisableCompression: true,
	}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, raw, nil)
	if err != nil {
		return nil, err
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ptpip: live view: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, fmt.Errorf("ptpip: live view: HTTP %s", resp.Status)
	}
	return &SonyLiveView{body: resp.Body, r: bufio.NewReaderSize(resp.Body, 256<<10)}, nil
}

// Next reads one frame. Each frame starts with four u32: image offset, image
// size, metadata offset, metadata size, all relative to the frame start.
func (lv *SonyLiveView) Next() (SonyLiveViewFrame, error) {
	return readSonyLiveViewFrame(lv.r)
}

func (lv *SonyLiveView) Close() error { return lv.body.Close() }

func readSonyLiveViewFrame(r io.Reader) (SonyLiveViewFrame, error) {
	var h [16]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return SonyLiveViewFrame{}, err
	}
	le := binary.LittleEndian
	imgOff, imgLen := uint64(le.Uint32(h[0:])), uint64(le.Uint32(h[4:]))
	metaOff, metaLen := uint64(le.Uint32(h[8:])), uint64(le.Uint32(h[12:]))
	total := max(imgOff+imgLen, metaOff+metaLen)
	if total < 16 || total > sonyMaxLiveViewFrame || (imgLen > 0 && imgOff < 16) || (metaLen > 0 && metaOff < 16) {
		return SonyLiveViewFrame{}, fmt.Errorf("ptpip: bad live-view frame header % x", h)
	}
	buf := make([]byte, total)
	copy(buf, h[:])
	if _, err := io.ReadFull(r, buf[16:]); err != nil {
		return SonyLiveViewFrame{}, err
	}
	return SonyLiveViewFrame{JPEG: buf[imgOff : imgOff+imgLen], Meta: buf[metaOff : metaOff+metaLen]}, nil
}
