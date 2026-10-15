#!/usr/bin/env bash
# Build the sing-box core (libbox) as an Android AAR for the app.
#
# Only what VpnDeck uses is compiled in (VLESS + Reality/uTLS + gVisor TUN stack):
# no QUIC, WireGuard, Tailscale, naive etc. Smaller library, less memory,
# fewer moving parts.
set -euo pipefail

SING_BOX_VERSION=v1.14.3
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC="$ROOT/.deps/sing-box"
OUT="$ROOT/android/app/libs/libbox.aar"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}
export PATH="$(go env GOPATH)/bin:$JAVA_HOME/bin:$PATH"

if ! command -v gomobile >/dev/null; then
  go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
  go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
fi

if [ ! -d "$SRC" ]; then
  git clone --depth 1 --branch "$SING_BOX_VERSION" https://github.com/SagerNet/sing-box.git "$SRC"
fi
have=$(git -C "$SRC" describe --tags --exact-match 2>/dev/null || echo unknown)
if [ "$have" != "$SING_BOX_VERSION" ]; then
  echo "expected $SING_BOX_VERSION in $SRC, found $have" >&2
  exit 1
fi

TAGS=with_gvisor,with_utls,badlinkname,tfogo_checklinkname0
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION#v} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid="
TARGETS=${LIBBOX_TARGETS:-android/arm64,android/arm,android/amd64}

mkdir -p "$(dirname "$OUT")"
cd "$SRC"
gomobile bind -v \
  -o "$OUT" \
  -target "$TARGETS" \
  -androidapi 26 \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath -buildvcs=false \
  -ldflags "$LDFLAGS" \
  -tags "$TAGS" \
  ./experimental/libbox
rm -f "${OUT%.aar}-sources.jar"
ls -lh "$OUT"
