package bridge

import (
	"context"
	"net"
)

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
const protocolFujiPhone = "fuji-phone"

type backendConnection struct {
	backend        cameraBackend
	info, identity string
}

type backendRegistration struct {
	defaultPort int
	connect     func(context.Context, connectionConfig, func(context.Context, string, string) (net.Conn, error)) (backendConnection, error)
}

// 注册只负责选择已实现的协议，不按型号复制驱动；会话发布和取消保护集中在Session
var networkBackends = map[string]backendRegistration{
	protocolCanon: {8080, func(ctx context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (backendConnection, error) {
		backend, info, err := connectCanon(ctx, cfg, dial)
		if err != nil {
			return backendConnection{}, err
		}
		return backendConnection{backend, info, backend.identity}, nil
	}},
	protocolFujiAutoSave: {fujiCommandPort, func(ctx context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (backendConnection, error) {
		backend, info, err := connectFujiAutoSave(ctx, cfg, dial)
		if err != nil {
			return backendConnection{}, err
		}
		return backendConnection{backend, info, backend.identity}, nil
	}},
	protocolFujiPhone: {fujiCommandPort, func(ctx context.Context, cfg connectionConfig, dial func(context.Context, string, string) (net.Conn, error)) (backendConnection, error) {
		backend, info, err := connectFujiPhone(ctx, cfg, dial)
		if err != nil {
			return backendConnection{}, err
		}
		return backendConnection{backend, info, backend.identity}, nil
	}},
}

func (s *Session) alternate() cameraBackend {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.backend
}
