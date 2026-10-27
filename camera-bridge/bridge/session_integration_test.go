package bridge

import (
	"encoding/json"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestCardBrowseAndRawJpegDownloadPreservesBytes(t *testing.T) {
	dir := t.TempDir()
	jpeg := []byte("camera-original-jpeg-payload")
	raw := make([]byte, 5<<20)
	for i := range raw {
		raw[i] = byte(i % 251)
	}
	if err := os.WriteFile(filepath.Join(dir, "DSC00001.JPG"), jpeg, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "DSC00001.ARW"), raw, 0600); err != nil {
		t.Fatal(err)
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: dir, A6700: true})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	host, port, _ := net.SplitHostPort(server.Addr())
	number, _ := strconv.Atoi(port)
	session := NewSession()
	defer session.Close()
	if _, err = session.Connect(`{"host":"` + host + `","port":` + strconv.Itoa(number) + `}`); err != nil {
		t.Fatal(err)
	}
	page, err := session.ListPage(1, 0)
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		Items []struct {
			ContentID int `json:"contentId"`
			Files     []struct {
				FileID int    `json:"fileId"`
				Name   string `json:"name"`
				Size   int64  `json:"size"`
			} `json:"files"`
		} `json:"items"`
	}
	if err = json.Unmarshal([]byte(page), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Items) != 1 || len(result.Items[0].Files) != 2 {
		t.Fatalf("RAW/JPEG not grouped: %s", page)
	}
	for _, file := range result.Items[0].Files {
		target := filepath.Join(t.TempDir(), file.Name)
		request, _ := json.Marshal(map[string]any{"slot": 1, "contentId": result.Items[0].ContentID, "fileId": file.FileID, "size": file.Size})
		if _, err = session.Download(string(request), target, nil); err != nil {
			t.Fatal(err)
		}
		got, err := os.ReadFile(target)
		if err != nil {
			t.Fatal(err)
		}
		expected, _ := os.ReadFile(filepath.Join(dir, file.Name))
		if string(got) != string(expected) {
			t.Fatalf("original changed: %s", file.Name)
		}
	}
	if server.Xfer9212.Load() != 0 {
		t.Fatal("browse unexpectedly entered UI-locking transfer mode")
	}
}
