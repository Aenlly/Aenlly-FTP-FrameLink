package bridge

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

const ccapiMaxMedia = 100000
const ccapiMaxPages = 1000
const ccapiPreviewLimit = 32 << 20

type ccapiFile struct {
	path         string
	name         string
	size         int64
	modifiedDate string
}

// CCAPI's lastmodifieddate is deliberately retained as file metadata. It is not
// renamed to capture time; the browser adapter must expose the timestamp basis.
type ccapiFileInfo struct {
	FileSize         int64
	LastModifiedDate string
}

type ccapiMedia struct {
	http      *ccapiHTTP
	resources ccapiResources
}

func (m *ccapiMedia) queryPath(resource string, values url.Values) (string, error) {
	u, err := m.http.resource(resource)
	if err != nil {
		return "", err
	}
	if u.RawQuery != "" {
		return "", errors.New("相机原始资源包含未确认的查询参数")
	}
	u.RawQuery = values.Encode()
	return u.RequestURI(), nil
}

func (m *ccapiMedia) paths(ctx context.Context, resource string) ([]string, error) {
	var response struct{ Path []string }
	if err := m.http.json(ctx, http.MethodGet, resource, nil, &response); err != nil {
		return nil, err
	}
	if response.Path == nil {
		return nil, errors.New("相机未返回媒体资源列表")
	}
	parent, err := m.http.resource(resource)
	if err != nil {
		return nil, err
	}
	prefix := strings.TrimSuffix(parent.Path, "/") + "/"
	for _, value := range response.Path {
		u, err := m.http.resource(value)
		if err != nil || u.RawQuery != "" || !strings.HasPrefix(u.Path, prefix) {
			return nil, errors.New("相机媒体资源不属于当前原片通道")
		}
	}
	return response.Path, nil
}

func (m *ccapiMedia) fileInfo(ctx context.Context, resource string) (ccapiFileInfo, error) {
	var info ccapiFileInfo
	query, err := m.queryPath(resource, url.Values{"kind": {"info"}})
	if err != nil {
		return info, err
	}
	err = m.http.json(ctx, http.MethodGet, query, nil, &info)
	if err == nil && info.FileSize <= 0 {
		err = errors.New("相机未提供有效原片长度")
	}
	return info, err
}

// list fetches metadata only. Previews and original bytes are separate requests,
// and a failed or changing page never becomes a successful complete snapshot.
func (m *ccapiMedia) list(ctx context.Context, slot int) ([]ccapiFile, error) {
	root, err := m.resources.path(http.MethodGet, "contents")
	if err != nil {
		return nil, err
	}
	cards, err := m.paths(ctx, root)
	if err != nil {
		return nil, err
	}
	if slot < 1 || slot > len(cards) {
		return nil, errors.New("相机未提供此存储卡")
	}
	directories, err := m.paths(ctx, cards[slot-1])
	if err != nil {
		return nil, err
	}
	files := make([]ccapiFile, 0)
	seen := make(map[string]bool)
	for _, directory := range directories {
		query, err := m.queryPath(directory, url.Values{"kind": {"number"}})
		if err != nil {
			return nil, err
		}
		var count struct{ PageNumber json.Number }
		if err = m.http.json(ctx, http.MethodGet, query, nil, &count); err != nil {
			return nil, err
		}
		pages, err := strconv.Atoi(count.PageNumber.String())
		if err != nil || pages < 0 || pages > ccapiMaxPages {
			return nil, errors.New("相机媒体页数无效，已停止重复读取")
		}
		for page := 1; page <= pages; page++ {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			query, err := m.queryPath(directory, url.Values{"page": {strconv.Itoa(page)}})
			if err != nil {
				return nil, err
			}
			items, err := m.paths(ctx, query)
			if err != nil {
				return nil, err
			}
			for _, item := range items {
				u, err := m.http.resource(item)
				if err != nil {
					return nil, err
				}
				if !ccapiPhotoName(path.Base(u.Path)) {
					continue
				}
				if seen[u.Path] {
					return nil, errors.New("相机媒体分页发生重复，请刷新后再试")
				}
				seen[u.Path] = true
				if len(files) >= ccapiMaxMedia {
					return nil, errors.New("相机媒体索引超过当前单次读取范围")
				}
				info, err := m.fileInfo(ctx, u.Path)
				if err != nil {
					return nil, err
				}
				files = append(files, ccapiFile{path: u.Path, name: path.Base(u.Path), size: info.FileSize, modifiedDate: info.LastModifiedDate})
			}
		}
	}
	// This is file-time ordering, not a claim about EXIF capture time.
	slices.SortFunc(files, func(a, b ccapiFile) int {
		aTime, aOK := ccapiFileTime(a.modifiedDate)
		bTime, bOK := ccapiFileTime(b.modifiedDate)
		if aOK != bOK {
			if aOK {
				return -1
			}
			return 1
		}
		if aOK && !aTime.Equal(bTime) {
			return bTime.Compare(aTime)
		}
		return strings.Compare(b.path, a.path)
	})
	return files, nil
}

func ccapiPhotoName(name string) bool {
	switch strings.ToUpper(path.Ext(name)) {
	case ".JPG", ".JPEG", ".CR2", ".CR3", ".HIF", ".HEIF", ".HEIC":
		return true
	default:
		return false
	}
}

func ccapiFileTime(value string) (time.Time, bool) {
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05", "2006:01:02 15:04:05", "2006-01-02 15:04:05"} {
		if date, err := time.Parse(layout, value); err == nil {
			return date, true
		}
	}
	return time.Time{}, false
}

func (m *ccapiMedia) preview(ctx context.Context, file ccapiFile, target string, large bool) error {
	kind := "thumbnail"
	if large {
		kind = "display"
	}
	resource, err := m.queryPath(file.path, url.Values{"kind": {kind}})
	if err != nil {
		return err
	}
	response, err := m.http.request(ctx, http.MethodGet, resource, nil)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return errors.New("机身未返回完整预览")
	}
	reader := bufio.NewReader(response.Body)
	header, err := reader.Peek(3)
	if err != nil || len(header) != 3 || header[0] != 0xff || header[1] != 0xd8 || header[2] != 0xff {
		return errors.New("机身未返回JPEG预览，可稍后重试或下载原片")
	}
	_, _, err = storeCCAPIFile(reader, target, 0, ccapiPreviewLimit, nil)
	return err
}

func (m *ccapiMedia) download(ctx context.Context, file ccapiFile, target string, progress Progress) (string, error) {
	info, err := m.fileInfo(ctx, file.path)
	if err != nil {
		return "", err
	}
	if info.FileSize != file.size || info.LastModifiedDate != file.modifiedDate {
		return "", errors.New("相机文件已变化，请刷新列表后重新选择")
	}
	response, err := m.http.request(ctx, http.MethodGet, file.path, nil)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", errors.New("机身未返回完整原片")
	}
	mediaType := strings.ToLower(strings.Split(response.Header.Get("Content-Type"), ";")[0])
	encoding := strings.ToLower(response.Header.Get("Content-Encoding"))
	if mediaType == "application/json" || strings.HasPrefix(mediaType, "text/") || (encoding != "" && encoding != "identity") {
		return "", errors.New("相机未返回可验证的原片内容")
	}
	if response.ContentLength >= 0 && response.ContentLength != file.size {
		return "", errors.New("相机原片长度与媒体索引不一致")
	}
	reader := bufio.NewReader(response.Body)
	header, err := reader.Peek(3)
	if err != nil {
		return "", errors.New("机身原片头部不完整")
	}
	isJPEG := header[0] == 0xff && header[1] == 0xd8 && header[2] == 0xff
	switch strings.ToUpper(path.Ext(file.name)) {
	case ".JPG", ".JPEG":
		if !isJPEG {
			return "", errors.New("机身未返回JPEG原片")
		}
	case ".CR2", ".CR3":
		if isJPEG || mediaType == "image/jpeg" {
			return "", errors.New("机身返回的是JPEG副本，未将其保存为RAW")
		}
	}
	// HTTP range support has not been verified. A retry starts a fresh .part file.
	size, sum, err := storeCCAPIFile(reader, target, file.size, file.size, progress)
	if err != nil {
		return "", err
	}
	return marshal(map[string]any{"path": target, "size": size, "sha256": sum})
}

func storeCCAPIFile(source io.Reader, target string, expected, limit int64, progress Progress) (int64, string, error) {
	if limit <= 0 || limit == math.MaxInt64 {
		return 0, "", errors.New("相机文件读取范围无效")
	}
	if err := os.MkdirAll(filepath.Dir(target), 0700); err != nil {
		return 0, "", err
	}
	partial := target + ".part"
	file, err := os.OpenFile(partial, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return 0, "", err
	}
	published := false
	defer func() {
		file.Close()
		if !published {
			os.Remove(partial)
		}
	}()
	hash := sha256.New()
	reader := io.LimitReader(source, limit+1)
	buffer := make([]byte, 256<<10)
	var done, synced int64
	for {
		n, readErr := reader.Read(buffer)
		if n > 0 {
			if done+int64(n) > limit {
				return 0, "", errors.New("相机返回的文件超过约定长度")
			}
			written, writeErr := file.Write(buffer[:n])
			if writeErr != nil {
				return 0, "", writeErr
			}
			if written != n {
				return 0, "", io.ErrShortWrite
			}
			hash.Write(buffer[:n])
			done += int64(n)
		}
		if readErr != nil && readErr != io.EOF {
			return 0, "", readErr
		}
		if done-synced >= transferChunk || readErr == io.EOF {
			if err := file.Sync(); err != nil {
				return 0, "", err
			}
			synced = done
			if progress != nil {
				progress.OnProgress(done, expected)
			}
		}
		if readErr == io.EOF {
			break
		}
	}
	if expected > 0 && done != expected {
		return 0, "", fmt.Errorf("原片接收不完整：%d/%d字节", done, expected)
	}
	if done == 0 {
		return 0, "", errors.New("相机返回了空文件")
	}
	if err := file.Close(); err != nil {
		return 0, "", err
	}
	if err := os.Rename(partial, target); err != nil {
		return 0, "", err
	}
	published = true
	return done, hex.EncodeToString(hash.Sum(nil)), nil
}
