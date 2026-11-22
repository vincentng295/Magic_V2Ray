#!/usr/bin/env bash
#
# Builds the Magic V2Ray installer stub zip: module.prop + action.sh only.
#
# Run it AFTER build.sh, when dist/ already holds the release APKs
# (<id>-<version>-arm64-v8a.apk and <id>-<version>-x86_64.apk). The SHA-256 of
# both APKs is written into action.sh, so the stub only ever installs exactly
# those files from the GitHub release with the same tag.
#
#   stub/build-stub.sh [dist-dir]       -> dist/<id>-<version>-stub.zip
#
# Env: GITHUB_REPOSITORY (default vincentng295/Magic_V2Ray), MODULE_PROP
# (default module/module.prop), TAG (default: first word of version=, like the
# release job in ci.yml).

set -euo pipefail
cd "$(dirname "$0")/.."

DIST="${1:-dist}"
REPO="${GITHUB_REPOSITORY:-vincentng295/Magic_V2Ray}"
PROP="${MODULE_PROP:-module/module.prop}"

prop() { sed -n "s/^$1=//p" "$PROP" | head -n 1 | tr -d '\r'; }

MODID="$(prop id)"
VERSION_LINE="$(prop version)"
VERSION_CODE="$(prop versionCode)"
# Same derivations as build.sh (file names) and ci.yml (release tag).
VERSION="$(printf '%s' "$VERSION_LINE" | tr ' /&' '_')"
TAG="${TAG:-$(printf '%s' "$VERSION_LINE" | sed -E 's/^([^ ]+).*/\1/')}"

[ -n "$MODID" ] && [ -n "$VERSION" ] && [ -n "$VERSION_CODE" ] \
    || { echo "!! id/version/versionCode missing in $PROP" >&2; exit 1; }
case "$MODID$VERSION$TAG$VERSION_CODE" in
    *[!A-Za-z0-9._-]*) echo "!! unexpected characters in id/version/tag/versionCode" >&2; exit 1 ;;
esac
case "$REPO" in
    */*) ;;
    *) echo "!! GITHUB_REPOSITORY must look like owner/name" >&2; exit 1 ;;
esac

ARM64="$DIST/${MODID}-${VERSION}-arm64-v8a.apk"
X64="$DIST/${MODID}-${VERSION}-x86_64.apk"
for f in "$ARM64" "$X64"; do
    [ -s "$f" ] || { echo "!! missing $f (run build.sh first)" >&2; exit 1; }
done

sha() { sha256sum "$1" | cut -d' ' -f1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

sed \
    -e "s|@VERSION@|$VERSION|g" \
    -e "s|@VERSION_CODE@|$VERSION_CODE|g" \
    -e "s|@BASE_URL@|https://github.com/$REPO/releases/download/$TAG|g" \
    -e "s|@APK_ARM64@|$(basename "$ARM64")|g" \
    -e "s|@SHA_ARM64@|$(sha "$ARM64")|g" \
    -e "s|@APK_X64@|$(basename "$X64")|g" \
    -e "s|@SHA_X64@|$(sha "$X64")|g" \
    stub/action.sh > "$STAGE/action.sh"
if grep -q '@[A-Z0-9_]*@' "$STAGE/action.sh"; then
    echo "!! unreplaced placeholder left in action.sh" >&2; exit 1
fi
chmod 755 "$STAGE/action.sh"

# Same module.prop as the real module (id, version, versionCode, ...); only the
# description tells users what this entry is.
tr -d '\r' < "$PROP" \
    | sed 's|^description=.*|description=Installer stub: tap Action to download and install the Magic V2Ray app, which then installs the module.|' \
    > "$STAGE/module.prop"

mkdir -p "$DIST"
OUT="$(cd "$DIST" && pwd)/${MODID}-${VERSION}-stub.zip"
rm -f "$OUT"
(cd "$STAGE" && zip -X -q "$OUT" module.prop action.sh)

echo "==> $OUT ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    echo "zip=$OUT" >> "$GITHUB_OUTPUT"
fi
