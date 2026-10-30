package bridge

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/knallli/alpha2go/ptpip"
	"github.com/knallli/alpha2go/ptpip/ptpiptest"
)

func creativeSession(t *testing.T, props []ptpip.SonyProp, payload []byte) *Session {
	t.Helper()
	server, err := ptpiptest.Start(ptpiptest.Options{Dir: t.TempDir(), Sony: true, Props: props, AllPropsPayload: payload, Model: "QA CREATIVE LOOK SIMULATOR"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(server.Close)
	host, port, _ := net.SplitHostPort(server.Addr())
	s := NewSession()
	t.Cleanup(s.Close)
	if _, err = s.Connect(fmt.Sprintf(`{"host":%q,"port":%s}`, host, port)); err != nil {
		t.Fatal(err)
	}
	return s
}
func modernLookFixture() ptpip.SonyProp {
	return ptpip.SonyProp{Code: sonyCreativeLook, Type: 4, Writable: true, Enabled: 1, Current: ptpip.SonyValue{Int: 1}, Form: 2, Settable: []ptpip.SonyValue{{Int: 1}, {Int: 6}}}
}

type lookRow struct {
	Code     int
	Value    int64
	Writable bool
	Choices  []struct {
		Value int64
		Label string
	}
}

func lookRows(t *testing.T, s *Session) []lookRow {
	t.Helper()
	raw, err := s.Properties()
	if err != nil {
		t.Fatal(err)
	}
	var rows []lookRow
	if err = json.Unmarshal([]byte(raw), &rows); err != nil {
		t.Fatal(err)
	}
	return rows
}

func TestModernCreativeLookUsesAdvertisedEnumAndReadsBackActualValue(t *testing.T) {
	s := creativeSession(t, []ptpip.SonyProp{modernLookFixture()}, nil)
	rows := lookRows(t, s)
	if len(rows) != 1 || rows[0].Code != int(sonyCreativeLook) || !rows[0].Writable || rows[0].Value != 1 || len(rows[0].Choices) != 2 {
		t.Fatalf("descriptor not preserved: %+v", rows)
	}
	if rows[0].Choices[0].Label != "ST" || rows[0].Choices[1].Label != "FL" {
		t.Fatal(rows[0].Choices)
	}
	if err := s.SetProperty(int(sonyCreativeLook), 6); err != nil {
		t.Fatal(err)
	}
	if actual := lookRows(t, s)[0].Value; actual != 6 {
		t.Fatalf("readback = %d", actual)
	}
	if err := s.SetProperty(int(sonyCreativeLook), 4); err == nil {
		t.Fatal("unadvertised VV value written")
	}
	if actual := lookRows(t, s)[0].Value; actual != 6 {
		t.Fatal("rejected value changed camera")
	}
	for _, code := range []int{0x01c5, 0xd200, 0xd21b, 0xd0fb, 0xd108, 0x1d0fa, -1} {
		if err := s.SetProperty(code, 1); err == nil {
			t.Fatalf("SDK, legacy or unverified adjustment enabled: %x", code)
		}
	}
}

func TestModernCreativeLookRefusesDisabledMalformedAndUnknownDescriptors(t *testing.T) {
	for _, kind := range []string{"readonly", "disabled", "display-only", "range", "string", "unknown-current", "unknown-choice"} {
		t.Run(kind, func(t *testing.T) {
			p := modernLookFixture()
			switch kind {
			case "readonly":
				p.Writable = false
			case "disabled":
				p.Enabled = 0
			case "display-only":
				p.Enabled = 2
			case "range":
				p.Form = 1
				p.Min = ptpip.SonyValue{Int: 1}
				p.Max = ptpip.SonyValue{Int: 6}
				p.Step = ptpip.SonyValue{Int: 1}
				p.Settable = nil
			case "string":
				p.Type = 0xffff
				p.Current = ptpip.SonyValue{Str: "not-a-style"}
				p.Settable = []ptpip.SonyValue{{Str: "not-a-style"}}
			case "unknown-current":
				p.Current.Int = 99
			case "unknown-choice":
				p.Settable = []ptpip.SonyValue{{Int: 256}}
			}
			s := creativeSession(t, []ptpip.SonyProp{p}, nil)
			rows := lookRows(t, s)
			if len(rows) != 1 || rows[0].Writable {
				t.Fatalf("unsafe descriptor enabled: %+v", rows)
			}
			if err := s.SetProperty(int(sonyCreativeLook), 6); err == nil {
				t.Fatal("unsafe write accepted")
			}
		})
	}
}

func TestCreativeLookDiagnosticsDoesNotExposeTransportOrOwnerStrings(t *testing.T) {
	props := []ptpip.SonyProp{modernLookFixture(), {Code: 0xd278, Type: 0xffff, Enabled: 1, Current: ptpip.SonyValue{Str: "PRIVATE_TRANSPORT_TOKEN"}}, {Code: 0xd07b, Type: 0xffff, Enabled: 1, Current: ptpip.SonyValue{Str: "PRIVATE_OWNER"}}}
	s := creativeSession(t, props, nil)
	raw, err := s.CreativeLookDiagnostics()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(raw, "PRIVATE_") {
		t.Fatal("private string leaked")
	}
	var result struct {
		ReadOnly             bool
		ReplyCodeCount       int
		CandidateDescriptors []struct {
			Code    int
			Current int64
		}
	}
	if err = json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if !result.ReadOnly || result.ReplyCodeCount != 3 || len(result.CandidateDescriptors) != 1 || result.CandidateDescriptors[0].Current != 1 {
		t.Fatal(raw)
	}
}

func TestA7CIIObservedDescriptorProjectionPreservesCandidatesAndRejectsUnmappedWrites(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("testdata", "a7cii-creative-look-20261030.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Model                string
		CameraSettingWritten bool
		Descriptor           struct {
			Code, Type    uint16
			Current       int64
			Enabled, Form uint8
			Writable      bool
			Settable      []int64
		}
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Model != "ILCE-7CM2" || fixture.CameraSettingWritten {
		t.Fatal("fixture provenance changed")
	}
	d := fixture.Descriptor
	p := ptpip.SonyProp{Code: d.Code, Type: d.Type, Current: ptpip.SonyValue{Int: d.Current}, Enabled: d.Enabled, Form: d.Form, Writable: d.Writable}
	for _, v := range d.Settable {
		p.Settable = append(p.Settable, ptpip.SonyValue{Int: v})
	}
	s := creativeSession(t, []ptpip.SonyProp{p}, nil)
	rows := lookRows(t, s)
	if len(rows) != 1 || rows[0].Value != 6 || len(rows[0].Choices) != 18 || !rows[0].Writable {
		t.Fatalf("observed descriptors lost: %+v", rows)
	}
	for _, value := range []int64{11, 12, 257, 262} {
		if err = s.SetProperty(int(sonyCreativeLook), value); err == nil {
			t.Fatalf("unmapped value %d enabled", value)
		}
	}
	for _, value := range []int64{1, 6} {
		if err = s.SetProperty(int(sonyCreativeLook), value); err != nil {
			t.Fatal(err)
		}
		if lookRows(t, s)[0].Value != value {
			t.Fatal("projection readback mismatch")
		}
	}
}

func TestA6700CapturedReplyReportsCreativeCandidateWithoutAssumingSupport(t *testing.T) {
	payload, err := os.ReadFile(filepath.Join("..", "..", "third_party", "alpha2go", "ptpip", "testdata", "a6700-9209.bin"))
	if err != nil {
		t.Fatal(err)
	}
	s := creativeSession(t, nil, payload)
	raw, err := s.CreativeLookDiagnostics()
	if err != nil {
		t.Fatal(err)
	}
	var result struct {
		ReplyCodeCount       int
		CandidateDescriptors []json.RawMessage
		HardwareVerified     bool
	}
	if err = json.Unmarshal([]byte(raw), &result); err != nil {
		t.Fatal(err)
	}
	if result.ReplyCodeCount < 10 || result.HardwareVerified {
		t.Fatal(raw)
	}
	t.Logf("sourced a6700 9209: properties=%d, candidate descriptors=%d; not ILCE-7CM2 evidence", result.ReplyCodeCount, len(result.CandidateDescriptors))
	for _, row := range result.CandidateDescriptors {
		t.Logf("look descriptor: %s", row)
	}
	for _, row := range lookRows(t, s) {
		if row.Code == int(sonyCreativeLook) && (row.Writable || len(row.Choices) != 0) {
			t.Fatal("disabled captured descriptor incorrectly enabled")
		}
	}
	if err = s.SetProperty(int(sonyCreativeLook), 6); err == nil {
		t.Fatal("captured disabled candidate accepted a write")
	}
}
