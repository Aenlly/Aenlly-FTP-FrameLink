package bridge

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/knallli/alpha2go/ptpip"
)

const contentsPageSize = 100
const maxIndexPages = 1000

// contentsIndex is a connection-local metadata snapshot; original images and previews are not read.
type contentsIndex struct {
	client *ptpip.Client
	slot   int
	day    string
	items  []ptpip.SonyContent
}

// ListNewestPage presents a stable newest-first snapshot. Offset is an opaque page cursor,
// unlike ListPage's legacy camera timestamp. Only verified forward-read operations are used.
func (s *Session) ListNewestPage(slot int, day string, offset int64) (string, error) {
	if backend := s.alternate(); backend != nil {
		return backend.ListNewestPage(slot, day, offset)
	}
	if slot < 1 || slot > 2 || offset < 0 {
		return "", errors.New("存储卡或分页游标无效")
	}
	var start, end int64
	if day != "" {
		date, err := time.Parse("2006-01-02", day)
		if err != nil || date.Year() < 1970 {
			return "", errors.New("拍摄日期无效")
		}
		// Camera local time can be on either side of UTC. Read the enclosing UTC range,
		// then select by the camera's wall-clock day, without using the phone timezone.
		start = max(0, date.AddDate(0, 0, -1).UnixMilli())
		end = date.AddDate(0, 0, 2).UnixMilli()
	}
	s.op.Lock()
	defer s.op.Unlock()
	c, parent, err := s.camera()
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	index := s.newestIndex
	identity := s.identity
	s.mu.Unlock()
	if offset == 0 {
		ctx, cancel := context.WithTimeout(parent, 90*time.Second)
		defer cancel()
		items, err := readNewestContents(ctx, c, uint32(slot), day, start, end)
		if err != nil {
			return "", err
		}
		index = &contentsIndex{client: c, slot: slot, day: day, items: items}
		s.mu.Lock()
		if s.client != c || parent.Err() != nil {
			s.mu.Unlock()
			return "", errors.New("相机连接已变化，请重新读取")
		}
		s.newestIndex = index
		s.mu.Unlock()
	}
	if index == nil || index.client != c || index.slot != slot || index.day != day || offset > int64(len(index.items)) {
		return "", errors.New("照片列表已变化，请刷新后继续")
	}
	until := min(offset+contentsPageSize, int64(len(index.items)))
	rows := make([]map[string]any, 0, until-offset)
	for _, item := range index.items[offset:until] {
		files := make([]map[string]any, 0, len(item.Files))
		for _, file := range item.Files {
			files = append(files, map[string]any{"fileId": file.ID, "name": filepath.Base(file.Path), "cameraPath": file.Path, "size": file.Size, "width": file.Width, "height": file.Height, "format": file.Format, "cameraDay": item.CreatedLocal.Format("2006-01-02")})
		}
		rows = append(rows, map[string]any{"id": fmt.Sprintf("%s:%d:%d:%d", identity, slot, item.ID, item.CreatedUTC.UnixMilli()), "contentId": item.ID, "slot": slot, "capturedAt": item.CreatedUTC.UnixMilli(), "groupId": item.GroupID, "files": files})
	}
	next := until
	if until == int64(len(index.items)) {
		next = 0
	}
	return marshal(map[string]any{"items": rows, "nextCursor": next})
}

func readNewestContents(ctx context.Context, c *ptpip.Client, slot uint32, day string, start, end int64) ([]ptpip.SonyContent, error) {
	dates, err := c.SonyGetCapturedDateList(ctx, slot)
	if errors.Is(err, ptpip.ErrSlotEmpty) || (err == nil && len(dates) == 0) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	items := make([]ptpip.SonyContent, 0)
	seen := make(map[uint32]bool)
	cursor := start
	complete := false
	for page := 0; page < maxIndexPages; page++ {
		contents, err := c.SonyGetContentsInfoList(ctx, slot, uint64(cursor))
		if err != nil {
			return nil, err
		}
		for _, item := range contents {
			if item.Type == 1 && !seen[item.ID] && (day == "" || item.CreatedLocal.Format("2006-01-02") == day) {
				seen[item.ID] = true
				items = append(items, item)
			}
		}
		if len(contents) < contentsPageSize {
			complete = true
			break
		}
		next := contents[len(contents)-1].CreatedUTC.UnixMilli()
		if next <= cursor {
			return nil, errors.New("相机索引游标未前进，已停止重复读取")
		}
		if end > 0 && next >= end {
			complete = true
			break
		}
		cursor = next
	}
	if !complete {
		return nil, errors.New("照片索引过大，请按拍摄日期浏览")
	}
	slices.SortStableFunc(items, func(a, b ptpip.SonyContent) int {
		if a.CreatedUTC.After(b.CreatedUTC) {
			return -1
		}
		if a.CreatedUTC.Before(b.CreatedUTC) {
			return 1
		}
		// Match the local library's capturedAt DESC, id ordering for equal timestamps.
		return strings.Compare(strconv.FormatUint(uint64(a.ID), 10), strconv.FormatUint(uint64(b.ID), 10))
	})
	return items, nil
}
