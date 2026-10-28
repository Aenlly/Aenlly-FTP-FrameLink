package bridge

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestNativeFTPReceiverPersistsBytesReceiptsAndProtectsOriginals(t *testing.T) {
	server := NewFTPReceiver()
	root := t.TempDir()
	if err := server.Start("127.0.0.1:0", "qa-camera", "test-password-123", root, nil); err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	conn, err := net.Dial("tcp", server.Address())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	reply := func(want int) string {
		t.Helper()
		line, err := ftpReadLine(reader, 8192)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(line, strconv.Itoa(want)) {
			t.Fatalf("want %d got %s", want, line)
		}
		return line
	}
	command := func(command string, want int) string {
		t.Helper()
		fmt.Fprint(conn, command+"\r\n")
		return reply(want)
	}
	reply(220)
	command("USER qa-camera", 331)
	command("PASS test-password-123", 230)
	command("TYPE I", 200)
	command("ALLO", 501)
	bytesOut := bytes.Repeat([]byte{0, 1, 2, 255, 3}, 700_001)
	for round := 0; round < 2; round++ {
		text := command("EPSV", 229)
		parts := strings.Split(text, "|")
		port := parts[len(parts)-2]
		data, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", port))
		if err != nil {
			t.Fatal(err)
		}
		command(fmt.Sprintf("ALLO %d", len(bytesOut)), 200)
		command("STOR 同名 原片.ARW", 150)
		if _, err = data.Write(bytesOut); err != nil {
			t.Fatal(err)
		}
		data.Close()
		reply(226)
	}
	command("DELE 同名 原片.ARW", 502)
	entries, err := os.ReadDir(filepath.Join(root, "receipts"))
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 2 {
		t.Fatalf("receipts: %d", len(entries))
	}
	paths := map[string]bool{}
	for _, entry := range entries {
		data, err := os.ReadFile(filepath.Join(root, "receipts", entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		var receipt struct {
			Path   string
			Size   int64
			SHA256 string `json:"sha256"`
		}
		if err = json.Unmarshal(data, &receipt); err != nil {
			t.Fatal(err)
		}
		original, err := os.ReadFile(receipt.Path)
		if err != nil {
			t.Fatal(err)
		}
		hash := sha256.Sum256(bytesOut)
		if !bytes.Equal(original, bytesOut) || receipt.Size != int64(len(bytesOut)) || receipt.SHA256 != hex.EncodeToString(hash[:]) {
			t.Fatal("original or receipt differs")
		}
		paths[receipt.Path] = true
	}
	if len(paths) != 2 {
		t.Fatal("same-name upload overwrote original")
	}
}

func TestNativeFTPClientAuthenticatesAndRejectsInjectedCommands(t *testing.T) {
	server := NewFTPReceiver()
	if err := server.Start("127.0.0.1:0", "qa", "test-password-123", t.TempDir(), nil); err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	host, port, _ := net.SplitHostPort(server.Address())
	config := fmt.Sprintf(`{"host":%q,"port":%s,"user":"qa","password":"test-password-123"}`, host, port)
	client := NewFTPClient()
	defer client.Close()
	if err := client.Connect(config); err != nil {
		t.Fatal(err)
	}
	if _, err := client.List("/\r\nDELE x"); err == nil {
		t.Fatal("accepted command injection")
	}
	if err := client.MakeDirectory("/QA"); err != nil {
		t.Fatal(err)
	}
	client.Close()
	if err := client.Connect(strings.Replace(config, "test-password-123", "wrong", 1)); err == nil {
		t.Fatal("accepted wrong password")
	}
}
