package bridge

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestFujiDiscoveryParsesQuotedNamesAndRejectsAmbiguousMessages(t *testing.T) {
	message, err := parseFujiMessage("REGISTER * HTTP/1.1\r\nDSCNAME:FUJIFILM-test\r\nDSCMODEL:\"X-T30 II\"\r\n")
	if err != nil || message.headers["dscmodel"] != "X-T30 II" {
		t.Fatalf("quoted model not retained: %+v %v", message, err)
	}
	for _, raw := range []string{
		"DISCOVER * HTTP/1.1\r\nDSCADDR:127.0.0.1\r\nDSCADDR:127.0.0.2\r\n",
		"REGISTER * HTTP/1.1\r\nDSCMODEL:\"unterminated\r\n",
		"REGISTER * HTTP/1.1\r\nDSCNAME:bad\x00name\r\n",
		"DISCOVER * HTTP/1.1\r\n" + strings.Repeat("X", fujiDiscoveryLimit),
	} {
		if _, err := parseFujiMessage(raw); err == nil {
			t.Fatalf("ambiguous message accepted: %q", raw[:min(80, len(raw))])
		}
	}
}

func TestFujiAutoSaveDiscoveryRegistrationImportAndOriginalRoundTrip(t *testing.T) {
	peer := newFujiPeer(t)
	notify, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer notify.Close()
	notifications := make(chan string, 4)
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			conn, err := notify.Accept()
			if err != nil {
				return
			}
			conn.SetDeadline(time.Now().Add(2 * time.Second))
			reader := bufio.NewReader(conn)
			var request strings.Builder
			for {
				line, err := reader.ReadString('\n')
				request.WriteString(line)
				if err != nil || line == "\r\n" {
					break
				}
			}
			conn.Write([]byte("HTTP/1.1 200 OK\r\n"))
			conn.Close()
			notifications <- request.String()
		}
	}()
	r := NewFujiAutoSaveReceiver()
	if err = r.Start("127.0.0.1", "FrameLink QA", false); err == nil {
		t.Fatal("receiver started without opt-in")
	}
	if r.State() != `{"experimental":true,"hardwareVerified":false,"mediaScope":"AUTOSAVE_PENDING_BATCH","state":"disabled"}` {
		t.Fatal(r.State())
	}
	_, commandText, _ := net.SplitHostPort(peer.listener.Addr().String())
	command, _ := strconv.Atoi(commandText)
	if err = r.start("127.0.0.1", "FrameLink QA", fujiPorts{notify: notify.Addr().(*net.TCPAddr).Port, command: command, listen: "127.0.0.1"}); err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	invite := func(registration bool) {
		t.Helper()
		port, target, method, path := r.ports.importing, "FrameLink QA", "IMPORT", "/guest"
		if registration {
			port, target, method, path = r.ports.register, "*", "REGISTER", "*"
		}
		udp, err := net.Dial("udp4", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			t.Fatal(err)
		}
		udp.Write([]byte(fmt.Sprintf("DISCOVER %s HTTP/1.1\r\nDSCADDR:127.0.0.1\r\nSERVICE:PCAUTOSAVE/1.0\r\n", target)))
		udp.Close()
		select {
		case request := <-notifications:
			if !strings.Contains(request, "IMPORTER: FrameLink QA\r\n") {
				t.Fatal(request)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("no NOTIFY")
		}
		conn, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(2 * time.Second))
		request := fmt.Sprintf("%s %s HTTP/1.1\r\nDSCNAME:FUJIFILM-SYNTHETIC-0001\r\n", method, path)
		if registration {
			request += "DSCMODEL:\"X-T30 II\"\r\n"
		} else {
			request += "DSCPORT:$\r\n"
		}
		// No empty terminating header line, matching published Fuji examples.
		if _, err = conn.Write([]byte(request)); err != nil {
			t.Fatal(err)
		}
		response, err := bufio.NewReader(conn).ReadString('\n')
		if err != nil || strings.TrimSpace(response) != "HTTP/1.1 200 OK" {
			t.Fatalf("callback not accepted: %q %v", response, err)
		}
	}
	invite(true)
	if !strings.Contains(r.State(), `"state":"registered"`) {
		t.Fatal(r.State())
	}
	if _, err = r.NextInvitation(1); err == nil {
		t.Fatal("registration mistaken for media invitation")
	}
	invite(false)
	config, err := r.NextInvitation(2000)
	if err != nil {
		t.Fatal(err)
	}
	var settings map[string]any
	json.Unmarshal([]byte(config), &settings)
	if settings["mediaScope"] != "AUTOSAVE_PENDING_BATCH" || settings["experimentalFuji"] != true {
		t.Fatal(config)
	}
	s := NewSession()
	defer s.Close()
	if _, err = s.Connect(config); err != nil {
		t.Fatal(err)
	}
	rows := fujiList(t, s)
	targetFile := filepath.Join(t.TempDir(), "DSCF0001.RAF")
	if _, err = s.Download(fujiRequest(t, rows[1], 1), targetFile, nil); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(targetFile)
	if !bytes.Equal(data, peer.files[2]) {
		t.Fatal("discovery-to-original bytes differ")
	}
	r.Close()
	if _, err = r.NextInvitation(1); err == nil {
		t.Fatal("closed receiver still waiting")
	}
	notify.Close()
	<-done
}

func TestFujiReceiverFailedBindReleasesOpenedSockets(t *testing.T) {
	occupied, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer occupied.Close()
	free, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	first := free.Addr().(*net.TCPAddr).Port
	free.Close()
	r := NewFujiAutoSaveReceiver()
	if err = r.start("127.0.0.1", "FrameLink", fujiPorts{register: first, importing: occupied.Addr().(*net.TCPAddr).Port, listen: "127.0.0.1"}); err == nil {
		r.Close()
		t.Fatal("occupied callback port accepted")
	}
	for _, network := range []string{"tcp4", "udp4"} {
		if network == "tcp4" {
			socket, err := net.Listen(network, fmt.Sprintf("127.0.0.1:%d", first))
			if err != nil {
				t.Fatal("TCP leaked", err)
			}
			socket.Close()
		} else {
			socket, err := net.ListenPacket(network, fmt.Sprintf("127.0.0.1:%d", first))
			if err != nil {
				t.Fatal("UDP leaked", err)
			}
			socket.Close()
		}
	}
}
