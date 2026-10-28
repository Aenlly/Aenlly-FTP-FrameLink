package bridge

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http/httptest"
	"os"
	"path"
	"path/filepath"
	"strings"
	"testing"
)

func TestNativeFTPClientRoundTripAndSameNameProtection(t *testing.T) {
	root := t.TempDir()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go ftpFixture(conn, root)
		}
	}()
	host, port, _ := net.SplitHostPort(listener.Addr().String())
	client := NewFTPClient()
	defer client.Close()
	if err = client.Connect(fmt.Sprintf(`{"host":%q,"port":%s,"user":"qa","password":"test"}`, host, port)); err != nil {
		t.Fatal(err)
	}
	payload := bytes.Repeat([]byte{0, 1, 255, 4}, 700003)
	source := filepath.Join(t.TempDir(), "source.ARW")
	os.WriteFile(source, payload, 0600)
	remote := "/中文  双空格.ARW"
	if err = client.Upload(remote, source, nil); err != nil {
		t.Fatal(err)
	}
	listed, err := client.List("/")
	if err != nil {
		t.Fatal(err)
	}
	var entries []ftpEntry
	if err = json.Unmarshal([]byte(listed), &entries); err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name != path.Base(remote) || entries[0].Size != int64(len(payload)) {
		t.Fatalf("listing: %s", listed)
	}
	target := filepath.Join(t.TempDir(), "download.ARW")
	if _, err = client.Download(remote, target, int64(len(payload)), nil); err != nil {
		t.Fatal(err)
	}
	received, _ := os.ReadFile(target)
	if !bytes.Equal(payload, received) {
		t.Fatal("download differs")
	}
	if err = client.Upload(remote, source, nil); err == nil {
		t.Fatal("same-name original was replaced")
	}
	if _, err = client.Download(remote, target, int64(len(payload)), nil); err == nil {
		t.Fatal("local original was replaced")
	}
	wrong := filepath.Join(t.TempDir(), "wrong.ARW")
	if _, err = client.Download(remote, wrong, int64(len(payload))+1, nil); err == nil {
		t.Fatal("accepted incomplete file")
	}
	if _, err = os.Stat(wrong); !os.IsNotExist(err) {
		t.Fatal("wrong-length file was published")
	}
}

func TestNativeFTPSRejectsUntrustedCertificateBeforeSendingCredentials(t *testing.T) {
	certificate := httptest.NewTLSServer(nil)
	defer certificate.Close()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		fmt.Fprint(conn, "220 TLS fixture\r\n")
		reader := bufio.NewReader(conn)
		line, _ := ftpReadLine(reader, 8192)
		if line != "AUTH TLS" {
			return
		}
		fmt.Fprint(conn, "234 Proceed\r\n")
		secure := tls.Server(conn, certificate.TLS.Clone())
		_ = secure.Handshake()
	}()
	host, port, _ := net.SplitHostPort(listener.Addr().String())
	client := NewFTPClient()
	defer client.Close()
	if err = client.Connect(fmt.Sprintf(`{"host":%q,"port":%s,"user":"qa","password":"test","tls":true}`, host, port)); err == nil {
		t.Fatal("accepted untrusted TLS certificate")
	}
}

func TestLegacyFTPListingPreservesNames(t *testing.T) {
	for _, line := range []string{"-rw-r--r-- 1 qa staff 42 Oct 28 10:00 中文  双空格.jpg", "10-28-26  10:00AM  42 中文  双空格.jpg"} {
		entry, ok := ftpLegacyEntry(line)
		if !ok || entry.Name != "中文  双空格.jpg" || entry.Size != 42 {
			t.Fatalf("%q => %+v %v", line, entry, ok)
		}
	}
}

// The fixture implements the wire operations used by the production streaming client.
func ftpFixture(conn net.Conn, root string) {
	defer conn.Close()
	reader := bufio.NewReader(conn)
	reply := func(code int, text string) { fmt.Fprintf(conn, "%d %s\r\n", code, text) }
	var passive net.Listener
	var rename string
	defer func() {
		if passive != nil {
			passive.Close()
		}
	}()
	reply(220, "QA fixture")
	for {
		line, err := ftpReadLine(reader, 8192)
		if err != nil {
			return
		}
		parts := strings.SplitN(line, " ", 2)
		arg := ""
		if len(parts) == 2 {
			arg = parts[1]
		}
		file := filepath.Join(root, path.Base(arg))
		switch parts[0] {
		case "USER":
			reply(331, "Password")
		case "PASS", "TYPE", "OPTS":
			reply(200, "OK")
		case "EPSV":
			if passive != nil {
				passive.Close()
			}
			passive, err = net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				return
			}
			reply(229, fmt.Sprintf("Entering Extended Passive Mode (|||%d|)", passive.Addr().(*net.TCPAddr).Port))
		case "MLSD", "STOR", "RETR":
			if passive == nil {
				reply(425, "No passive socket")
				continue
			}
			reply(150, "Data ready")
			data, err := passive.Accept()
			passive.Close()
			passive = nil
			if err != nil {
				return
			}
			switch parts[0] {
			case "MLSD":
				entries, _ := os.ReadDir(root)
				for _, entry := range entries {
					info, _ := entry.Info()
					fmt.Fprintf(data, "type=file;size=%d; %s\r\n", info.Size(), entry.Name())
				}
			case "STOR":
				out, e := os.Create(file)
				if e != nil {
					data.Close()
					reply(550, "Cannot save")
					continue
				}
				_, err = io.Copy(out, data)
				out.Close()
			case "RETR":
				in, e := os.Open(file)
				if e != nil {
					data.Close()
					reply(550, "Missing")
					continue
				}
				_, err = io.Copy(data, in)
				in.Close()
			}
			data.Close()
			if err != nil {
				reply(451, "Data failure")
			} else {
				reply(226, "Done")
			}
		case "RNFR":
			rename = file
			reply(350, "Ready")
		case "RNTO":
			if err = os.Rename(rename, file); err != nil {
				reply(550, "Rename failed")
			} else {
				reply(250, "Renamed")
			}
		default:
			reply(502, "Unsupported")
		}
	}
}
