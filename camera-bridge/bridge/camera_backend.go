package bridge

// cameraBackend preserves the mobile boundary while each protocol owns its
// transport, capability negotiation, timestamps and cancellation lifecycle.
type cameraBackend interface {
	Connected() bool
	Dates(int) (string, error)
	ListPage(int, int64) (string, error)
	ListNewestPage(int, string, int64) (string, error)
	Preview(int, int, int, string, bool) error
	Download(string, string, Progress) (string, error)
	Properties() (string, error)
	SetProperty(int, int64) error
	Capture(bool) error
	CapturePhoto(bool, bool, bool) (string, error)
	ConfigureCaptureStorage(bool) (string, error)
	ShootingState() (string, error)
	FocusAt(float64, float64) error
	StartLiveView() error
	NextFrame() ([]byte, error)
	LiveMetadata() string
	FetchCaptured(string) (string, error)
	StopLiveView()
	CancelTransfer()
	Close()
}

const protocolSony = "sony-ptpip"
const protocolCanon = "canon-ccapi"
const protocolFujiAutoSave = "fuji-autosave"

func (s *Session) alternate() cameraBackend {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.backend
}
