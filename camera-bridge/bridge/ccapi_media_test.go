package bridge

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

const testCCAPIContents = "/ccapi/ver100/contents"
const testCCAPICard = testCCAPIContents + "/sd"
const testCCAPIFolder = testCCAPICard + "/100CANON"

func TestCCAPIPairedFileDayFollowsTheLatestFileTimestamp(t *testing.T) {
	jpeg := ccapiFile{name: "IMG_0001.JPG", modifiedDate: "2026-10-23T23:59:59+08:00"}
	raw := ccapiFile{name: "IMG_0001.CR3", modifiedDate: "2026-10-24T00:00:01+08:00"}
	for _, files := range [][]ccapiFile{{jpeg, raw}, {raw, jpeg}} {
		if day := ccapiPhotoDay(ccapiPhoto{files: files}); day != "2026-10-24" {
			t.Fatal("paired date does not match file-time order", day)
		}
	}
}

func ccapiTestMedia(t *testing.T, handler http.HandlerFunc) (*ccapiMedia, *httptest.Server) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return &ccapiMedia{http: ccapiTestClient(t, server, "", ""), resources: ccapiResources{"GET:contents": testCCAPIContents}}, server
}

func TestCCAPIMetadataPaginationUsesAllPagesAndSortsFileTime(t *testing.T) {
	var previews, originals, infoReads atomic.Int32
	media, _ := ccapiTestMedia(t, func(w http.ResponseWriter, r *http.Request) {
		encode := func(value any) { json.NewEncoder(w).Encode(value) }
		switch r.URL.Path {
		case testCCAPIContents:
			encode(map[string]any{"path": []string{testCCAPICard}})
		case testCCAPICard:
			encode(map[string]any{"path": []string{testCCAPIFolder}})
		case testCCAPIFolder:
			if r.URL.Query().Get("kind") == "number" {
				encode(map[string]any{"pagenumber": 3})
				return
			}
			page, _ := strconv.Atoi(r.URL.Query().Get("page"))
			values := []string{}
			for i := (page - 1) * 100; i < min(page*100, 250); i++ {
				values = append(values, fmt.Sprintf("%s/IMG_%04d.JPG", testCCAPIFolder, i))
			}
			encode(map[string]any{"path": values})
		default:
			switch r.URL.Query().Get("kind") {
			case "info":
				infoReads.Add(1)
				stamp := "2026-10-23T10:00:00+08:00"
				// A low file number is newest; path/filename ordering would be wrong.
				if strings.HasSuffix(r.URL.Path, "0002.JPG") {
					stamp = "2026-10-24T23:59:00+08:00"
				}
				encode(map[string]any{"filesize": 4096, "lastmodifieddate": stamp})
			case "thumbnail", "display":
				previews.Add(1)
			default:
				originals.Add(1)
			}
		}
	})
	files, err := media.list(context.Background(), 1)
	if err != nil || len(files) != 250 || files[0].name != "IMG_0002.JPG" {
		t.Fatalf("metadata pagination len=%d err=%v", len(files), err)
	}
	if infoReads.Load() != 250 || previews.Load() != 0 || originals.Load() != 0 {
		t.Fatalf("metadata read image payloads: info=%d preview=%d original=%d", infoReads.Load(), previews.Load(), originals.Load())
	}
}

func TestCCAPIDuplicatePageFailsInsteadOfClaimingCompletion(t *testing.T) {
	media, _ := ccapiTestMedia(t, func(w http.ResponseWriter, r *http.Request) {
		var value any
		switch r.URL.Path {
		case testCCAPIContents:
			value = map[string]any{"path": []string{testCCAPICard}}
		case testCCAPICard:
			value = map[string]any{"path": []string{testCCAPIFolder}}
		case testCCAPIFolder:
			if r.URL.Query().Get("kind") == "number" {
				value = map[string]any{"pagenumber": 2}
			} else {
				value = map[string]any{"path": []string{testCCAPIFolder + "/IMG_0001.JPG"}}
			}
		default:
			value = map[string]any{"filesize": 100, "lastmodifieddate": "2026-10-23T12:00:00+08:00"}
		}
		json.NewEncoder(w).Encode(value)
	})
	if _, err := media.list(context.Background(), 1); err == nil {
		t.Fatal("duplicate camera page was accepted")
	}
}

func TestCCAPIDownloadPreservesJPEGAndRAWAndKeepsPreviewSeparate(t *testing.T) {
	jpeg := append([]byte{0xff, 0xd8, 0xff}, bytes.Repeat([]byte{11, 0, 255, 33}, 2048)...)
	raw := append([]byte{0, 0, 0, 24, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' '}, bytes.Repeat([]byte{0, 255, 128, 42}, 8192)...)
	preview := []byte{0xff, 0xd8, 0xff, 0x01, 0x02, 0xff, 0xd9}
	media, _ := ccapiTestMedia(t, func(w http.ResponseWriter, r *http.Request) {
		data := jpeg
		if strings.HasSuffix(r.URL.Path, ".CR3") {
			data = raw
		}
		if r.URL.Query().Get("kind") == "info" {
			json.NewEncoder(w).Encode(map[string]any{"filesize": len(data), "lastmodifieddate": "2026-10-23T12:00:00+08:00"})
			return
		}
		if r.URL.Query().Get("kind") != "" {
			w.Header().Set("Content-Type", "image/jpeg")
			w.Write(preview)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Write(data)
	})
	for name, original := range map[string][]byte{"IMG_0001.JPG": jpeg, "IMG_0001.CR3": raw} {
		file := ccapiFile{path: testCCAPIFolder + "/" + name, name: name, size: int64(len(original)), modifiedDate: "2026-10-23T12:00:00+08:00"}
		target := filepath.Join(t.TempDir(), name)
		if err := os.WriteFile(target+".part", []byte("unverified old partial"), 0600); err != nil {
			t.Fatal(err)
		}
		receipt, err := media.download(context.Background(), file, target, nil)
		if err != nil {
			t.Fatal(err)
		}
		saved, _ := os.ReadFile(target)
		want := sha256.Sum256(original)
		var result map[string]any
		json.Unmarshal([]byte(receipt), &result)
		if !bytes.Equal(original, saved) || result["sha256"] != hex.EncodeToString(want[:]) {
			t.Fatal("original bytes or checksum changed")
		}
		thumb := target + ".preview.jpg"
		if err := media.preview(context.Background(), file, thumb, false); err != nil {
			t.Fatal(err)
		}
		savedPreview, _ := os.ReadFile(thumb)
		if !bytes.Equal(preview, savedPreview) || bytes.Equal(original, savedPreview) {
			t.Fatal("preview and original were conflated")
		}
	}
}

func TestCCAPIChangedFileAndJPEGSubstituteDoNotPublishRAW(t *testing.T) {
	for _, changed := range []bool{true, false} {
		t.Run(strconv.FormatBool(changed), func(t *testing.T) {
			media, _ := ccapiTestMedia(t, func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Query().Get("kind") == "info" {
					stamp := "2026-10-23T12:00:00+08:00"
					if changed {
						stamp = "2026-10-24T12:00:00+08:00"
					}
					json.NewEncoder(w).Encode(map[string]any{"filesize": 7, "lastmodifieddate": stamp})
					return
				}
				w.Header().Set("Content-Type", "image/jpeg")
				w.Write([]byte{0xff, 0xd8, 0xff, 0, 0, 0, 0})
			})
			file := ccapiFile{path: testCCAPIFolder + "/IMG_0001.CR3", name: "IMG_0001.CR3", size: 7, modifiedDate: "2026-10-23T12:00:00+08:00"}
			target := filepath.Join(t.TempDir(), file.name)
			if _, err := media.download(context.Background(), file, target, nil); err == nil {
				t.Fatal("changed or substituted RAW was accepted")
			}
			if _, err := os.Stat(target); !errors.Is(err, os.ErrNotExist) {
				t.Fatal("invalid original was published")
			}
		})
	}
}

func TestCCAPIIncompleteTransferPreservesExistingTarget(t *testing.T) {
	target := filepath.Join(t.TempDir(), "existing.CR3")
	original := []byte("keep existing original")
	os.WriteFile(target, original, 0600)
	if _, _, err := storeCCAPIFile(strings.NewReader("short"), target, 100, 100, nil); err == nil {
		t.Fatal("truncated transfer was accepted")
	}
	saved, _ := os.ReadFile(target)
	if !bytes.Equal(saved, original) {
		t.Fatal("failed download replaced the existing file")
	}
	if _, err := os.Stat(target + ".part"); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("failed non-resumable transfer left a stale partial")
	}
}
