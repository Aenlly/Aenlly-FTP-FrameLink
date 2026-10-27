// Simulator is a local QA tool. It is not included in the Android application.
package main

import (
	"encoding/binary"
	"flag"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"math"
	"net"
	"net/http"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	dir := flag.String("dir", "../artifacts/fixtures", "synthetic QA image directory")
	withSSH := flag.Bool("ssh", false, "enable loopback-only SSH identity acceptance fixture")
	flag.Parse()
	if err := os.MkdirAll(*dir, 0700); err != nil {
		panic(err)
	}
	for n := 0; n < 8; n++ {
		canvas := image.NewRGBA(image.Rect(0, 0, 1200, 900))
		for y := 0; y < 900; y++ {
			for x := 0; x < 1200; x++ {
				t := float64(y) / 900
				horizon := .55 + .09*math.Sin(float64(x)/200+float64(n))
				r, g, b := float64(184+n*3)-30*t, float64(205+n)-25*t, float64(214-n*4)-15*t
				if t > horizon {
					r, g, b = 104+30*t+float64(n*4), 132+15*t, 118+15*t
				}
				if math.Pow(float64(x-820), 2)+math.Pow(float64(y-230), 2) < float64((65+n*4)*(65+n*4)) {
					r, g, b = 235, 219, 180
				}
				canvas.SetRGBA(x, y, color.RGBA{uint8(r), uint8(g), uint8(b), 255})
			}
		}
		file, err := os.Create(filepath.Join(*dir, fmt.Sprintf("QA_DSC%05d.JPG", n+1)))
		if err != nil {
			panic(err)
		}
		if err = jpeg.Encode(file, canvas, &jpeg.Options{Quality: 90}); err != nil {
			panic(err)
		}
		file.Close()
	}
	preview, err := os.ReadFile(filepath.Join(*dir, "QA_DSC00001.JPG"))
	if err != nil {
		panic(err)
	}
	ptpiptest.CapturedImage = preview
	stream := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/octet-stream")
		ticker := time.NewTicker(time.Second / 10)
		defer ticker.Stop()
		meta := make([]byte, 104)
		binary.LittleEndian.PutUint16(meta, 0x68)
		binary.LittleEndian.PutUint32(meta[16:], 1)
		header := make([]byte, 16)
		binary.LittleEndian.PutUint32(header, 16)
		binary.LittleEndian.PutUint32(header[4:], uint32(len(preview)))
		binary.LittleEndian.PutUint32(header[8:], uint32(16+len(preview)))
		binary.LittleEndian.PutUint32(header[12:], uint32(len(meta)))
		for {
			select {
			case <-r.Context().Done():
				return
			case <-ticker.C:
				if _, err := w.Write(header); err != nil {
					return
				}
				if _, err := w.Write(preview); err != nil {
					return
				}
				if _, err := w.Write(meta); err != nil {
					return
				}
				w.(http.Flusher).Flush()
			}
		}
	})}
	streamListener, err := net.Listen("tcp", "127.0.0.1:16001")
	if err != nil {
		panic(err)
	}
	go stream.Serve(streamListener)
	defer stream.Close()
	props := []ptpip.SonyProp{{Code: ptpip.SonyPropLiveViewURL, Type: 0xffff, Enabled: 1, Current: ptpip.SonyValue{Str: "http://localhost:16001/liveview"}},
		{Code: 0x500F, Type: 4, Writable: true, Enabled: 1, Current: ptpip.SonyValue{Int: 100}, Form: 2, Settable: []ptpip.SonyValue{{Int: 100}, {Int: 200}, {Int: 400}}},
		{Code: ptpip.SonyPropTransferSize, Type: 4, Enabled: 1, Current: ptpip.SonyValue{Int: ptpip.SonyTransferOriginal}}}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: *dir, A6700: true, Model: "QA SIMULATED CAMERA", SerialNumber: "FRAMELINK-QA-NOT-HARDWARE", Preview: preview, Props: props})
	if err != nil {
		panic(err)
	}
	defer server.Close()
	if *withSSH {
		sshServer, err := ptpiptest.StartSSH("qa-camera", "test-password-123", server.Addr())
		if err != nil {
			panic(err)
		}
		defer sshServer.Close()
		sshListener, err := net.Listen("tcp", "127.0.0.1:16022")
		if err != nil {
			panic(err)
		}
		defer sshListener.Close()
		go func() {
			for {
				incoming, err := sshListener.Accept()
				if err != nil {
					return
				}
				go proxyConnection(incoming, sshServer.Addr())
			}
		}()
		statsListener, err := net.Listen("tcp", "127.0.0.1:16023")
		if err != nil {
			panic(err)
		}
		stats := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			fmt.Fprint(w, sshServer.AuthAttempts.Load())
		})}
		go stats.Serve(statsListener)
		defer stats.Close()
	}
	listener, err := net.Listen("tcp", "127.0.0.1:15740")
	if err != nil {
		panic(err)
	}
	defer listener.Close()
	go func() {
		for {
			incoming, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer incoming.Close()
				upstream, err := net.Dial("tcp", server.Addr())
				if err != nil {
					return
				}
				defer upstream.Close()
				finished := make(chan struct{}, 1)
				go func() { io.Copy(upstream, incoming); finished <- struct{}{} }()
				go func() { io.Copy(incoming, upstream); finished <- struct{}{} }()
				<-finished
			}()
		}
	}()
	fmt.Println("SIMULATED_CAMERA=127.0.0.1:15740")
	fmt.Println("FIXTURES=" + *dir)
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	<-signals
}

func proxyConnection(incoming net.Conn, address string) {
	defer incoming.Close()
	upstream, err := net.Dial("tcp", address)
	if err != nil {
		return
	}
	defer upstream.Close()
	finished := make(chan struct{}, 2)
	go func() { io.Copy(upstream, incoming); finished <- struct{}{} }()
	go func() { io.Copy(incoming, upstream); finished <- struct{}{} }()
	<-finished
}
