package discovery

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestARPLookup(t *testing.T) {
	p := filepath.Join(t.TempDir(), "arp")
	os.WriteFile(p, []byte(`IP address       HW type     Flags       HW address            Mask     Device
192.168.1.1      0x1         0x2         aa:bb:cc:00:00:01     *        eth0
192.168.1.57     0x1         0x2         D8:12:34:56:78:9A     *        wlan0
`), 0o644)
	got := ARPLookup(p, "d8-12-34-56-78-9a")
	if len(got) != 1 || got[0] != "192.168.1.57" {
		t.Fatalf("got %v", got)
	}
	if ARPLookup(p, "not a mac") != nil {
		t.Fatal("bad MAC should yield nothing")
	}
}

func TestHostsSkipsNetworkAndBroadcast(t *testing.T) {
	h := hosts(netip.MustParsePrefix("192.168.7.99/24"))
	if len(h) != 254 || h[0].String() != "192.168.7.1" || h[253].String() != "192.168.7.254" {
		t.Fatalf("len=%d first=%v last=%v", len(h), h[0], h[len(h)-1])
	}
}

func TestScanFindsOpenPort(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port
	got := Find(context.Background(), Options{
		Port: port, SSDPAddr: "-", Subnets: []netip.Prefix{netip.MustParsePrefix("127.0.0.1/32")},
	})
	if len(got) != 1 || got[0] != "127.0.0.1:"+strconv.Itoa(port) {
		t.Fatalf("got %v", got)
	}
}

func TestSSDPSony(t *testing.T) {
	desc := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `<root><device><manufacturer>Sony Corporation</manufacturer><modelName>ILCE-6700</modelName></device></root>`)
	}))
	defer desc.Close()
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if strings.HasPrefix(string(buf[:n]), "M-SEARCH") {
				// Generic headers; the device description reveals Sony.
				pc.WriteTo([]byte("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\nLOCATION: "+desc.URL+"/desc.xml\r\nSERVER: Linux UPnP/1.0\r\n\r\n"), from)
			}
		}
	}()
	got := SSDPSony(context.Background(), pc.LocalAddr().String(), 500*time.Millisecond)
	if len(got) != 1 || got[0] != "127.0.0.1" {
		t.Fatalf("got %v", got)
	}
}
