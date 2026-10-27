package wol

import (
	"bytes"
	"net"
	"testing"
	"time"
)

func TestMagicPacket(t *testing.T) {
	p, err := MagicPacket("02:00:00:00:67:00")
	if err != nil {
		t.Fatal(err)
	}
	if len(p) != 102 || !bytes.Equal(p[:6], bytes.Repeat([]byte{0xFF}, 6)) {
		t.Fatalf("bad header: % x", p[:12])
	}
	for i := 0; i < 16; i++ {
		if !bytes.Equal(p[6+i*6:12+i*6], []byte{2, 0, 0, 0, 0x67, 0}) {
			t.Fatalf("bad repetition %d", i)
		}
	}
	if _, err := MagicPacket("not-a-mac"); err == nil {
		t.Fatal("expected error")
	}
}

func TestSend(t *testing.T) {
	l, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	if err := Send("aa:bb:cc:dd:ee:ff", l.LocalAddr().String()); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 200)
	l.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, _, err := l.ReadFrom(buf)
	if err != nil || n != 102 {
		t.Fatalf("n=%d err=%v", n, err)
	}
}
