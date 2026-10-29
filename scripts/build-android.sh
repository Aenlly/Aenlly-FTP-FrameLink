#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home}
export ANDROID_HOME="$TASK_ROOT/.tools/android-sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$TASK_ROOT/.tools/go/bin:$TASK_ROOT/.tools/go-tools/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"
export GOPATH="$TASK_ROOT/.tools/go-tools"
export GOBIN="$GOPATH/bin"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -d "$ANDROID_HOME/platforms/android-36" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  python3 "$TASK_ROOT/scripts/install-sdk.py"
fi
if [ ! -f "$GOBIN/gomobile" ]; then
  go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20261011034528-197770444867
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
fi
if [ ! -f "$GOBIN/gobind" ]; then
  go install golang.org/x/mobile/cmd/gobind@v0.0.0-20261011034528-197770444867
fi
mkdir -p "$TASK_ROOT/android/app/libs"
cd "$TASK_ROOT/camera-bridge"
go mod tidy
# init会重新安装gobind@latest；这里使用已固定的工具，不拉取浮动版本
mkdir -p "$GOPATH/pkg/gomobile"
gomobile bind -target=android/arm64,android/amd64 -androidapi=26 -ldflags='-extldflags=-Wl,-z,max-page-size=16384' -javapkg=app.framelink.nativecamera -o "$TASK_ROOT/android/app/libs/camera.aar" ./bridge
cd "$TASK_ROOT/android"
./gradlew --no-daemon testDebugUnitTest assembleDebug
mkdir -p "$TASK_ROOT/artifacts"
cp app/build/outputs/apk/debug/app-debug.apk "$TASK_ROOT/artifacts/FrameLink-0.1.8-debug.apk"
