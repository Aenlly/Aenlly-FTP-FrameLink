// Package wol sends Wake-on-LAN magic packets. Some Sony bodies can be
// powered on remotely this way; for cameras without that feature the packets
// are simply ignored.
package wol

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"time"
)

// MagicPacket builds the 102-byte payload: 6×0xFF followed by the MAC 16 times.
func MagicPacket(mac string) ([]byte, error) {
	hw, err := net.ParseMAC(mac)
	if err != nil {
		return nil, err
	}
	if len(hw) != 6 {
		return nil, fmt.Errorf("wake-on-lan needs a 6-byte MAC, got %q", mac)
	}
	p := make([]byte, 0, 102)
	for i := 0; i < 6; i++ {
		p = append(p, 0xFF)
	}
	for i := 0; i < 16; i++ {
		p = append(p, hw...)
	}
	return p, nil
}

// Send broadcasts one magic packet to addr (e.g. "255.255.255.255:9").
func Send(mac, addr string) error {
	pkt, err := MagicPacket(mac)
	if err != nil {
		return err
	}
	conn, err := net.Dial("udp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()
	_, err = conn.Write(pkt)
	return err
}

// Loop sends a packet every interval while connected() is false, until ctx ends.
func Loop(ctx context.Context, mac, addr string, interval time.Duration, connected func() bool, log *slog.Logger) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		if !connected() {
			if err := Send(mac, addr); err != nil {
				log.Warn("wake-on-lan failed", "err", err)
			} else {
				log.Debug("sent wake-on-lan packet", "mac", mac, "addr", addr)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
