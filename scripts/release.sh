#!/usr/bin/env bash
# Build, sign and publish a VpnDeck release to GitHub; phones pick it up via the in-app updater.
#
#   scripts/release.sh 0.1.4 "Что нового — одна-две строки"
#
# Needs: clean git tree on main, android/keystore.properties (the release key — never
# publish with any other key: phones would refuse the update), gh logged in.
set -euo pipefail

VERSION=${1:?usage: release.sh <version> [notes]}
NOTES=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
BT="$ANDROID_HOME/build-tools/36.0.0"
# Certificate every release must be signed with (SHA-256 of the signing cert).
EXPECTED_CERT=58d19c912cdb56e5c1360b0f053716eb6a897518099a4e0f39def6b2cfd2df84

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 0.1.4" >&2; exit 1; }
[ -f android/keystore.properties ] || { echo "android/keystore.properties missing — can't sign" >&2; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "not on main" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean" >&2; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main differs from origin/main" >&2; exit 1; }
git rev-parse "v$VERSION" >/dev/null 2>&1 && { echo "tag v$VERSION already exists" >&2; exit 1; }

[ -f android/app/libs/libbox.aar ] || scripts/build-libbox.sh

printf 'VERSION_NAME=%s\n' "$VERSION" > android/version.properties
(cd android && ./gradlew -q clean assembleRelease)

OUT=android/app/build/outputs/apk/release
ASSETS=()
mkdir -p dist
rm -f dist/*.apk
for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  f="$OUT/vpndeck-$VERSION-$abi-release.apk"
  cert=$("$BT/apksigner" verify --print-certs "$f" | awk '/certificate SHA-256 digest/ {print $NF; exit}')
  [ "$cert" = "$EXPECTED_CERT" ] || { echo "$f is signed with $cert, not the release key" >&2; exit 1; }
  cp "$f" dist/
  ASSETS+=("dist/$(basename "$f")")
done

SUMS=$(cd dist && shasum -a 256 *.apk)
BODY=$(cat <<EOF
${NOTES:-Обновление VpnDeck.}

**Какой файл скачать вручную:** \`arm64-v8a\` — почти все современные телефоны; \`universal\` — если не уверены.
Приложение с версии 0.1.3 обновляется само.

SHA-256:
\`\`\`
$SUMS
\`\`\`
Сертификат подписи (SHA-256): \`$EXPECTED_CERT\`
EOF
)

git add android/version.properties
git diff --cached --quiet || git commit -q -m "Release $VERSION"
git tag -a "v$VERSION" -m "VpnDeck $VERSION"
git push -q origin main "v$VERSION"
gh release create "v$VERSION" "${ASSETS[@]}" --title "VpnDeck $VERSION" --notes "$BODY"
echo "published v$VERSION"
