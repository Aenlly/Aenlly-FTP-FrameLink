#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}
if ! xcrun --sdk iphoneos --show-sdk-path >/dev/null 2>&1; then
  echo '需要完整Xcode及iOS SDK，请先完成Xcode首次启动配置' >&2
  exit 2
fi
export GOPATH="$TASK_ROOT/.tools/go-tools"
export GOBIN="$GOPATH/bin"
export PATH="$TASK_ROOT/.tools/go/bin:$GOPATH/bin:$PATH"
if ! command -v gomobile >/dev/null 2>&1; then
  go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
fi
if [ ! -f "$GOBIN/gobind" ]; then
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
fi
mkdir -p "$TASK_ROOT/ios/Frameworks" "$TASK_ROOT/artifacts"
cd "$TASK_ROOT/camera-bridge"
# init会重新安装gobind@latest；这里使用已固定的工具，不拉取浮动版本
mkdir -p "$GOPATH/pkg/gomobile"
gomobile bind -target=ios,iossimulator -iosversion=17.0 -prefix=FL -o "$TASK_ROOT/ios/Frameworks/Camera.xcframework" ./bridge
cd "$TASK_ROOT"
xcodebuild -project ios/FrameLink.xcodeproj -scheme FrameLink -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/DerivedData "ARCHS=$(uname -m)" CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual build
printf 'iOS simulator app: %s\n' "$TASK_ROOT/ios/DerivedData/Build/Products/Debug-iphonesimulator/FrameLink.app"
