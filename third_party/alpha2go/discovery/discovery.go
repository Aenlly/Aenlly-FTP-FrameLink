// Package discovery finds PTP/IP cameras on the local network so the user
// does not have to configure the camera's IP address. It combines:
//
//  1. the ARP table, if the camera's MAC address is known,
//  2. SSDP (UPnP) announcements from Sony devices,
//  3. a scan of the local /24 networks for TCP port 15740 (PTP/IP).
//
// Results are candidates; the caller confirms them with a PTP/IP handshake.
package discovery

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Options configure a discovery run. Zero values select sensible defaults.
type Options struct {
	Port        int            // PTP/IP port (default 15740)
	MAC         string         // camera MAC, enables the ARP lookup
	ARPFile     string         // default /proc/net/arp
	SSDPAddr    string         // default 239.255.255.250:1900; "-" disables SSDP
	SSDPWait    time.Duration  // default 2s
	Subnets     []netip.Prefix // default: private IPv4 networks of local interfaces, max /24 each
	DialTimeout time.Duration  // per host during the port scan (default 400ms)
	Workers     int            // concurrent dials (default 64)
}

func (o *Options) defaults() {
	if o.Port == 0 {
		o.Port = 15740
	}
	if o.ARPFile == "" {
		o.ARPFile = "/proc/net/arp"
	}
	if o.SSDPAddr == "" {
		o.SSDPAddr = "239.255.255.250:1900"
	}
	if o.SSDPWait <= 0 {
		o.SSDPWait = 2 * time.Second
	}
	if o.DialTimeout <= 0 {
		o.DialTimeout = 400 * time.Millisecond
	}
	if o.Workers <= 0 {
		o.Workers = 64
	}
}

// Find returns candidate camera addresses ("ip:port"), most likely first.
func Find(ctx context.Context, opts Options) []string {
	opts.defaults()
	var out []string
	seen := map[string]bool{}
	add := func(ips ...string) {
		for _, ip := range ips {
			if ip == "" || seen[ip] {
				continue
			}
			seen[ip] = true
			out = append(out, net.JoinHostPort(ip, itoa(opts.Port)))
		}
	}
	if opts.MAC != "" {
		add(ARPLookup(opts.ARPFile, opts.MAC)...)
	}
	if opts.SSDPAddr != "-" {
		add(SSDPSony(ctx, opts.SSDPAddr, opts.SSDPWait)...)
	}
	subnets := opts.Subnets
	if subnets == nil {
		subnets = LocalSubnets()
	}
	add(ScanPort(ctx, subnets, opts.Port, opts.DialTimeout, opts.Workers)...)
	return out
}

func itoa(n int) string { return strconv.Itoa(n) }

// ARPLookup returns IPv4 addresses whose MAC matches (Linux /proc/net/arp format).
func ARPLookup(path, mac string) []string {
	want, err := net.ParseMAC(mac)
	if err != nil {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 4 {
			continue
		}
		hw, err := net.ParseMAC(fields[3])
		if err == nil && hw.String() == want.String() {
			out = append(out, fields[0])
		}
	}
	return out
}

// LocalSubnets returns the private IPv4 networks of the host's interfaces,
// each narrowed to at most a /24 around the host's own address.
func LocalSubnets() []netip.Prefix {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []netip.Prefix
	seen := map[netip.Prefix]bool{}
	for _, a := range addrs {
		ipn, ok := a.(*net.IPNet)
		if !ok {
			continue
		}
		ip, ok := netip.AddrFromSlice(ipn.IP.To4())
		if !ok || !ip.IsPrivate() {
			continue
		}
		bits, _ := ipn.Mask.Size()
		if bits < 24 {
			bits = 24
		}
		p, err := ip.Prefix(bits)
		if err != nil || seen[p] {
			continue
		}
		seen[p] = true
		out = append(out, p)
	}
	return out
}

func hosts(p netip.Prefix) []netip.Addr {
	p = p.Masked()
	if p.Bits() >= 31 {
		return []netip.Addr{p.Addr()}
	}
	var out []netip.Addr
	for a := p.Addr().Next(); p.Contains(a); a = a.Next() {
		if !p.Contains(a.Next()) { // skip broadcast
			break
		}
		out = append(out, a)
	}
	return out
}

// ScanPort returns the addresses in subnets that accept TCP connections on port.
func ScanPort(ctx context.Context, subnets []netip.Prefix, port int, timeout time.Duration, workers int) []string {
	var all []netip.Addr
	for _, p := range subnets {
		all = append(all, hosts(p)...)
	}
	jobs := make(chan netip.Addr)
	var mu sync.Mutex
	var found []netip.Addr
	var wg sync.WaitGroup
	d := net.Dialer{Timeout: timeout}
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for a := range jobs {
				c, err := d.DialContext(ctx, "tcp", netip.AddrPortFrom(a, uint16(port)).String())
				if err == nil {
					c.Close()
					mu.Lock()
					found = append(found, a)
					mu.Unlock()
				}
			}
		}()
	}
	for _, a := range all {
		select {
		case jobs <- a:
		case <-ctx.Done():
		}
	}
	close(jobs)
	wg.Wait()
	sort.Slice(found, func(i, j int) bool { return found[i].Less(found[j]) })
	out := make([]string, len(found))
	for i, a := range found {
		out[i] = a.String()
	}
	return out
}

// SSDPSony sends an M-SEARCH and returns the IPs of responders that identify
// as Sony devices (in the SSDP headers or their UPnP device description).
func SSDPSony(ctx context.Context, addr string, wait time.Duration) []string {
	dst, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		return nil
	}
	conn, err := net.ListenUDP("udp4", nil)
	if err != nil {
		return nil
	}
	defer conn.Close()
	msg := "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
	for i := 0; i < 2; i++ {
		conn.WriteToUDP([]byte(msg), dst)
	}
	deadline := time.Now().Add(wait)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	conn.SetReadDeadline(deadline)

	type resp struct {
		ip, location string
		sony         bool
	}
	byIP := map[string]*resp{}
	var order []string
	buf := make([]byte, 4096)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			break
		}
		text := string(buf[:n])
		ip := from.IP.String()
		r, ok := byIP[ip]
		if !ok {
			r = &resp{ip: ip}
			byIP[ip] = r
			order = append(order, ip)
		}
		if strings.Contains(strings.ToLower(text), "sony") {
			r.sony = true
		}
		for _, line := range strings.Split(text, "\r\n") {
			if k, v, ok := strings.Cut(line, ":"); ok && strings.EqualFold(strings.TrimSpace(k), "location") {
				r.location = strings.TrimSpace(v)
			}
		}
	}
	var out []string
	client := http.Client{Timeout: time.Second}
	checked := 0
	for _, ip := range order {
		r := byIP[ip]
		if !r.sony && r.location != "" && checked < 16 {
			checked++
			if u, err := url.Parse(r.location); err == nil && u.Hostname() == ip {
				if resp, err := client.Get(r.location); err == nil {
					b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
					resp.Body.Close()
					r.sony = strings.Contains(strings.ToLower(string(b)), "sony")
				}
			}
		}
		if r.sony {
			out = append(out, ip)
		}
	}
	return out
}
