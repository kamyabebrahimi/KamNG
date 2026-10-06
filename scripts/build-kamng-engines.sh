#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCES="$ROOT/native/sources"
OUTPUT="$ROOT/V2rayNG/app/src/main/jniLibs"
mkdir -p "$SOURCES"
checkout_source() {
  local name="$1" url="$2" revision="$3"
  if [ ! -d "$SOURCES/$name/.git" ]; then
    git clone --no-checkout "$url" "$SOURCES/$name"
  fi
  git -C "$SOURCES/$name" fetch origin "$revision"
  git -C "$SOURCES/$name" checkout --detach "$revision"
  test "$(git -C "$SOURCES/$name" rev-parse HEAD)" = "$revision"
}
checkout_source amneziawg-go https://github.com/amnezia-vpn/amneziawg-go.git b5928efb6ca19f0153958460c3d141f04abc5c2e
checkout_source CottenDNS https://github.com/WhiteDNS/CottenDNS.git f4a22770dcbfb72f5332be0bdf28cb9c3e26a9a9
mkdir -p "$ROOT/V2rayNG/app/src/main/assets/kamng-licenses"
cp "$SOURCES/amneziawg-go/LICENSE" "$ROOT/V2rayNG/app/src/main/assets/kamng-licenses/AmneziaWG.txt"
cp "$SOURCES/CottenDNS/LICENSE" "$ROOT/V2rayNG/app/src/main/assets/kamng-licenses/CottenDNS.txt"
cd "$ROOT/native/amneziawg"
go mod tidy
gofmt -w ./*.go
go test -race ./...
: "${ANDROID_HOME:?Android SDK is required}"
NDK="$ANDROID_HOME/ndk/30.0.16248370"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
build_awg() {
  local abi="$1" arch="$2" cc="$3" arm="${4:-}"
  mkdir -p "$OUTPUT/$abi"
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" GOARM="$arm" CC="$TOOLCHAIN/$cc" \
    go build -trimpath -buildmode=pie \
    -ldflags='-s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
    -o "$OUTPUT/$abi/libkamng_awg.so" .
  chmod 755 "$OUTPUT/$abi/libkamng_awg.so"
}
build_awg arm64-v8a arm64 aarch64-linux-android29-clang
build_awg armeabi-v7a arm armv7a-linux-androideabi29-clang 7
build_awg x86_64 amd64 x86_64-linux-android29-clang
build_awg x86 386 i686-linux-android29-clang
NDK_ROOT="$ANDROID_HOME/ndk/29.0.14206865" OUTPUT_DIR="$OUTPUT" \
  BUILD_VERSION=f4a22770dcbfb72f5332be0bdf28cb9c3e26a9a9 \
  bash "$SOURCES/CottenDNS/scripts/build-android-client.sh" all
python3 "$ROOT/scripts/validate-kamng-engines.py"
