package bridge

import "testing"

func TestConnectionValidation(t *testing.T) {
	for _, raw := range []string{`{}`, `{"host":"camera.invalid"}`, `{"host":"192.168.1.2","port":65536}`, `{"host":"192.168.1.2","user":"camera"}`, `{"host":"192.168.1.2","guid":"00"}`} {
		if _, err := decodeConfig(raw); err == nil {
			t.Errorf("accepted invalid config %s", raw)
		}
	}
	cfg, err := decodeConfig(`{"host":"192.168.1.2"}`)
	if err != nil || cfg.Port != 15740 {
		t.Fatalf("default port: %+v %v", cfg, err)
	}
}

func TestDisconnectedOperationsFailWithoutCameraWrites(t *testing.T) {
	s := NewSession()
	defer s.Close()
	if _, err := s.ListPage(1, 0); err == nil {
		t.Fatal("browse accepted disconnected camera")
	}
	if err := s.Capture(true); err == nil {
		t.Fatal("capture accepted disconnected camera")
	}
	if _, err := s.NextFrame(); err == nil {
		t.Fatal("live frame accepted disconnected camera")
	}
}
