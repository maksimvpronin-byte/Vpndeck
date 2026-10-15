#!/usr/bin/env bash
# Start a VpnDeck release: bump the version, tag, push. GitHub Actions
# (.github/workflows/release.yml) builds and signs the APKs and leaves a DRAFT
# release; phones see it only after it is published:
#
#   scripts/release.sh 0.1.4 "Что нового — одна-две строки"
#   gh run watch                                   # ~10 min
#   gh release edit v0.1.4 --draft=false --latest  # publish → in-app updater picks it up
set -euo pipefail

VERSION=${1:?usage: release.sh <version> "<what's new>"}
NOTES=${2:?usage: release.sh <version> "<what's new>"}
cd "$(dirname "$0")/.."

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "version must look like 0.1.4" >&2; exit 1; }
[ "$(git branch --show-current)" = main ] || { echo "not on main" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean" >&2; exit 1; }
git fetch -q origin main --tags
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main differs from origin/main" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && { echo "tag v$VERSION already exists" >&2; exit 1; }

printf 'VERSION_NAME=%s\n' "$VERSION" > android/version.properties
git add android/version.properties
git diff --cached --quiet || git commit -q -m "Release $VERSION"
git tag -a "v$VERSION" -m "$NOTES"
git push -q origin main "v$VERSION"
# The run for the tag takes a few seconds to appear; don't print the previous one.
url=""
for _ in 1 2 3 4 5 6; do
  sleep 5
  url=$(gh run list --workflow release.yml --branch "v$VERSION" --limit 1 --json url --jq '.[0].url // empty' 2>/dev/null || true)
  [ -n "$url" ] && break
done
echo "v$VERSION pushed; build: ${url:-see gh run list --workflow release.yml}"
