#!/usr/bin/env bash
# Run ON the VPN server (as root): creates a VLESS+Reality Xray in Docker with
# fresh keys, matching what vpndeck-server expects. Idempotent: refuses to
# overwrite an existing config so a re-run can't silently rotate everyone's keys.
#
#   bash setup-xray.sh
#
# Tunables (env): XRAY_PORT=8443  XRAY_SNI=www.cloudflare.com  XRAY_DIR=/opt/xray-reality-8443
#                 XRAY_NAME=xray-reality-8443  XRAY_IMAGE=ghcr.io/xtls/xray-core:latest
set -euo pipefail

PORT=${XRAY_PORT:-8443}
SNI=${XRAY_SNI:-www.cloudflare.com}
DIR=${XRAY_DIR:-/opt/xray-reality-8443}
NAME=${XRAY_NAME:-xray-reality-8443}
IMAGE=${XRAY_IMAGE:-ghcr.io/xtls/xray-core:latest}
CONFIG="$DIR/config.json"

command -v docker >/dev/null || { echo "docker is not installed" >&2; exit 1; }
if [ -e "$CONFIG" ]; then
  echo "$CONFIG already exists — not touching it. Delete it first if you really want new keys." >&2
  exit 1
fi

docker pull -q "$IMAGE" >/dev/null
x() { docker run --rm "$IMAGE" "$@"; }

KEYS=$(x x25519)
# Output format varies by Xray version ("Private key: X" / "PrivateKey: X").
PRIVATE=$(echo "$KEYS" | awk -F': *' 'tolower($1) ~ /private/ {print $2; exit}')
UUID=$(x uuid)
SHORT_ID=$(openssl rand -hex 8)
[ -n "$PRIVATE" ] && [ -n "$UUID" ] || { echo "failed to generate keys:"; echo "$KEYS"; exit 1; } >&2

mkdir -p "$DIR"
umask 077
cat > "$CONFIG" <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "vless-reality-$PORT",
      "listen": "0.0.0.0",
      "port": $PORT,
      "protocol": "vless",
      "settings": {
        "clients": [ { "id": "$UUID", "flow": "xtls-rprx-vision" } ],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "show": false,
          "dest": "$SNI:443",
          "xver": 0,
          "serverNames": [ "$SNI" ],
          "privateKey": "$PRIVATE",
          "shortIds": [ "$SHORT_ID" ]
        }
      },
      "sniffing": { "enabled": true, "destOverride": [ "http", "tls", "quic" ] }
    }
  ],
  "outbounds": [
    { "protocol": "freedom", "tag": "direct" },
    { "protocol": "blackhole", "tag": "block" }
  ]
}
EOF

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --restart unless-stopped \
  -p "$PORT:$PORT/tcp" \
  -v "$CONFIG:/etc/xray/config.json:ro" \
  "$IMAGE" run -config /etc/xray/config.json >/dev/null

sleep 2
if [ "$(docker inspect -f '{{.State.Running}}' "$NAME")" != "true" ]; then
  docker logs "$NAME" >&2
  exit 1
fi
echo "Xray is running: $NAME, VLESS+Reality on :$PORT (sni $SNI), config $CONFIG"
