#!/usr/bin/env bash
# Build vpndeck-server for linux and install/upgrade it on the VPN server.
#
#   server/deploy/deploy.sh root@<server-ip>
#
# First run also needs the one-time setup printed at the end. The systemd unit
# is replaced every time; keep local changes in a drop-in
# (systemctl edit vpndeck-server), it survives deploys.
set -euo pipefail

TARGET=${1:?usage: deploy.sh user@host}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

ARCH=$(ssh "$TARGET" uname -m)
case "$ARCH" in
  x86_64) GOARCH=amd64 ;;
  aarch64) GOARCH=arm64 ;;
  *) echo "unsupported arch $ARCH" >&2; exit 1 ;;
esac

# go.mod sets the minimum Go; an older local Go fetches it (GOTOOLCHAIN=auto).
(cd "$HERE/.." && echo "building with $(go version)" && CGO_ENABLED=0 GOOS=linux GOARCH=$GOARCH go build -trimpath -ldflags="-s -w" -o "$OUT/vpndeck-server" .)

scp -q "$OUT/vpndeck-server" "$TARGET:/usr/local/bin/vpndeck-server.new"
scp -q "$HERE/vpndeck-server.service" "$TARGET:/etc/systemd/system/vpndeck-server.service"
ssh "$TARGET" '
  set -e
  chmod 755 /usr/local/bin/vpndeck-server.new
  mv /usr/local/bin/vpndeck-server.new /usr/local/bin/vpndeck-server
  systemctl daemon-reload
  if [ -f /etc/vpndeck/server.json ]; then
    systemctl enable --now vpndeck-server >/dev/null 2>&1
    systemctl restart vpndeck-server
    sleep 1
    systemctl --no-pager --lines=3 status vpndeck-server
  else
    echo "installed; one-time setup still needed:"
    echo "  vpndeck-server init -host <public ip> -xray /opt/xray-reality-8443/config.json"
    echo "  vpndeck-server passwd family   # type the password, then Enter"
    echo "  systemctl enable --now vpndeck-server"
  fi
'
