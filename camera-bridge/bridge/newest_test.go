package bridge

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

type newestResult struct {
	Items []struct {
		ID         string `json:"id"`
		CapturedAt int64  `json:"capturedAt"`
		Files      []struct {
			Name string `json:"name"`
		} `json:"files"`
	} `json:"items"`
	Next int64 `json:"nextCursor"`
}

func newestFixture(t *testing.T, count int, stuck bool, sameTime ...bool) (*Session, *ptpiptest.Server) {
	t.Helper()
	dir := t.TempDir()
	base := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	for i := 0; i < count; i++ {
		file := filepath.Join(dir, fmt.Sprintf("F%04d.JPG", i))
		if err := os.WriteFile(file, []byte("original"), 0600); err != nil {
			t.Fatal(err)
		}
		ts := base.Add(time.Duration(i) * time.Second)
		if len(sameTime) > 0 && sameTime[0] {
			ts = base
		}
		if i >= 250 {
			ts = ts.Add(24 * time.Hour)
		}
		if err := os.Chtimes(file, ts, ts); err != nil {
			t.Fatal(err)
		}
	}
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: dir, A6700: true, IgnoreAfter: stuck})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close() })
	host, port, _ := net.SplitHostPort(server.Addr())
	number, _ := strconv.Atoi(port)
	s := NewSession()
	t.Cleanup(s.Close)
	if _, err := s.Connect(fmt.Sprintf(`{"host":%q,"port":%d}`, host, number)); err != nil {
		t.Fatal(err)
	}
	return s, server
}

func readNewest(t *testing.T, s *Session, day string, offset int64) newestResult {
	t.Helper()
	raw, err := s.ListNewestPage(1, day, offset)
	if err != nil {
		t.Fatal(err)
	}
	var result newestResult
	if err := json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func TestNewestPagingStartsWithLastShotAndKeepsStableOrder(t *testing.T) {
	s, server := newestFixture(t, 250, false)
	first := readNewest(t, s, "2026-01-01", 0)
	if len(first.Items) != 100 || first.Items[0].Files[0].Name != "F0249.JPG" || first.Next != 100 {
		t.Fatalf("first page: %+v", first)
	}
	ops := server.Ops.Load()
	all := first.Items
	for offset := first.Next; offset != 0; {
		page := readNewest(t, s, "2026-01-01", offset)
		all = append(all, page.Items...)
		offset = page.Next
	}
	if server.Ops.Load() != ops {
		t.Fatal("cached pagination fetched camera data again")
	}
	if len(all) != 250 {
		t.Fatalf("got %d entries", len(all))
	}
	seen := map[string]bool{}
	for i, item := range all {
		if seen[item.ID] {
			t.Fatal("duplicate entry")
		}
		seen[item.ID] = true
		if i > 0 && item.CapturedAt > all[i-1].CapturedAt {
			t.Fatal("later page inserted a newer shot")
		}
	}
}

func TestNewestScopeFiltersCameraDayAndRejectsStaleCursor(t *testing.T) {
	s, _ := newestFixture(t, 260, false)
	day := readNewest(t, s, "2026-01-01", 0)
	if day.Items[0].Files[0].Name != "F0249.JPG" {
		t.Fatal("day scope included a later day")
	}
	all := readNewest(t, s, "", 0)
	if all.Items[0].Files[0].Name != "F0259.JPG" {
		t.Fatal("all-day scope omitted latest shot")
	}
	if _, err := s.ListNewestPage(1, "2026-01-01", day.Next); err == nil {
		t.Fatal("accepted cursor from another scope")
	}
	if _, err := s.ListNewestPage(1, "invalid", 0); err == nil {
		t.Fatal("accepted malformed day")
	}
	s.Close()
	if _, err := s.ListNewestPage(1, "", all.Next); err == nil {
		t.Fatal("accepted cursor after disconnect")
	}
}

func TestNewestRejectsStuckCameraCursorWithoutPartialSnapshot(t *testing.T) {
	s, _ := newestFixture(t, 250, true)
	if _, err := s.ListNewestPage(1, "", 0); err == nil {
		t.Fatal("accepted an incomplete index")
	}
}

func TestNewestEmptySlotDoesNotRequestContents(t *testing.T) {
	s, server := newestFixture(t, 0, false)
	before := server.Ops.Load()
	result := readNewest(t, s, "", 0)
	if len(result.Items) != 0 || result.Next != 0 || server.Ops.Load()-before != 1 {
		t.Fatalf("empty slot: %+v", result)
	}
}

func TestNewestEqualTimestampsMatchLibraryTieOrder(t *testing.T) {
	s, _ := newestFixture(t, 12, false, true)
	result := readNewest(t, s, "", 0)
	if len(result.Items) != 12 {
		t.Fatalf("got %d entries", len(result.Items))
	}
	for i := 1; i < len(result.Items); i++ {
		if result.Items[i-1].ID > result.Items[i].ID {
			t.Fatal("equal timestamps did not match local library id order")
		}
	}
}

func TestNewestExactPageBoundaryHasNoPhantomNextPage(t *testing.T) {
	for _, count := range []int{100, 200} {
		t.Run(strconv.Itoa(count), func(t *testing.T) {
			s, _ := newestFixture(t, count, false)
			loaded := 0
			for offset := int64(0); ; {
				page := readNewest(t, s, "", offset)
				loaded += len(page.Items)
				if page.Next == 0 {
					break
				}
				offset = page.Next
				if loaded >= count {
					t.Fatal("complete list still advertised another page")
				}
			}
			if loaded != count {
				t.Fatalf("loaded %d of %d", loaded, count)
			}
		})
	}
}
