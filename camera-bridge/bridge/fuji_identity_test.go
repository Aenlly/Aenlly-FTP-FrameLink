package bridge

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestFujiMissingStableIdentityIsSessionScopedAndRejectsOldResources(t *testing.T) {
	for _, unavailable := range []bool{false, true} {
		p := newFujiPeer(t)
		p.mu.Lock()
		p.noSerial = true
		p.noDeviceInfo = unavailable
		p.mu.Unlock()
		s := NewSession()
		defer s.Close()
		first, err := s.Connect(p.config())
		if err != nil {
			t.Fatal(err)
		}
		var a map[string]any
		json.Unmarshal([]byte(first), &a)
		if a["identityScope"] != "SESSION_BATCH" {
			t.Fatal(first)
		}
		rows := fujiList(t, s)
		refresh := fujiList(t, s)
		if rows[1]["id"] != refresh[1]["id"] {
			t.Fatal("refresh changed session identity")
		}
		request := fujiRequest(t, rows[1], 1)
		target := filepath.Join(t.TempDir(), "saved.RAF")
		if _, err = s.Download(request, target, nil); err != nil {
			t.Fatal(err)
		}
		saved, err := os.ReadFile(target)
		if err != nil {
			t.Fatal(err)
		}
		second, err := s.Connect(p.config())
		if err != nil {
			t.Fatal(err)
		}
		var b map[string]any
		json.Unmarshal([]byte(second), &b)
		if a["id"] == b["id"] {
			t.Fatal("same DSCNAME/model without stable ID collided")
		}
		next := fujiList(t, s)
		if rows[1]["id"] == next[1]["id"] {
			t.Fatal("resources crossed sessions")
		}
		if _, err = s.Download(request, filepath.Join(t.TempDir(), "late.RAF"), nil); err == nil {
			t.Fatal("old batch accepted")
		}
		retained, _ := os.ReadFile(target)
		if !bytes.Equal(saved, retained) {
			t.Fatal("saved original changed")
		}
	}
}

func TestFujiStableSerialPreservesExistingIdentity(t *testing.T) {
	p := newFujiPeer(t)
	s := NewSession()
	defer s.Close()
	first, err := s.Connect(p.config())
	if err != nil {
		t.Fatal(err)
	}
	rows := fujiList(t, s)
	second, err := s.Connect(p.config())
	if err != nil {
		t.Fatal(err)
	}
	next := fujiList(t, s)
	var a, b map[string]any
	json.Unmarshal([]byte(first), &a)
	json.Unmarshal([]byte(second), &b)
	// Frozen identity from the pre-isolation algorithm for this synthetic device.
	if a["id"] != "4d6ba3271a7f2adb2f9c662e" {
		t.Fatal("existing stable identity namespace changed", first)
	}
	if a["identityScope"] != "STABLE_DEVICE" || a["id"] != b["id"] || rows[1]["id"] != next[1]["id"] {
		t.Fatal("stable identity changed", first, second)
	}
}
