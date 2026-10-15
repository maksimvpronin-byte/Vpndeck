#!/usr/bin/env bash
# Check a set of release APKs before they can reach phones:
# every ABI present, the version in each matches, and each is signed with the
# VpnDeck release key — phones refuse an update signed with any other key, so
# a release built with the wrong key would strand everyone on the old version.
#
#   scripts/verify-apks.sh <version> <dir> [--allow-unsigned]
set -euo pipefail

VERSION=${1:?usage: verify-apks.sh <version> <dir> [--allow-unsigned]}
DIR=${2:?usage: verify-apks.sh <version> <dir> [--allow-unsigned]}
ALLOW_UNSIGNED=${3:-}
EXPECTED_CERT=58d19c912cdb56e5c1360b0f053716eb6a897518099a4e0f39def6b2cfd2df84

BT=$(ls -d "${ANDROID_HOME:?ANDROID_HOME not set}"/build-tools/* | sort -V | tail -1)
status=0
for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  f="$DIR/vpndeck-$VERSION-$abi-release.apk"
  if [ ! -f "$f" ]; then echo "missing: $f"; status=1; continue; fi
  name=$("$BT/aapt2" dump badging "$f" | sed -nE "s/.*versionName='([^']+)'.*/\1/p" | head -1)
  [ "$name" = "$VERSION" ] || { echo "$f: versionName $name, expected $VERSION"; status=1; }
  cert=$("$BT/apksigner" verify --print-certs "$f" | awk '/certificate SHA-256 digest/ {print $NF; exit}')
  if [ "$cert" != "$EXPECTED_CERT" ]; then
    if [ "$ALLOW_UNSIGNED" = "--allow-unsigned" ]; then
      echo "warning: $f is not signed with the release key (test build)"
    else
      echo "$f: signed with $cert, not the release key"; status=1
    fi
  fi
done
[ $status -eq 0 ] && echo "APKs for $VERSION look right"
exit $status
