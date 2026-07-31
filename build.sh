#!/usr/bin/env bash
#
# Magic V2Ray — release packager.
#
# Produces one zip per CPU architecture instead of a single universal zip.
# The universal zip carried both arm64-v8a and x86_64 payloads (~118 MB) and
# then discarded one of them at install time, so every arm64 user downloaded
# ~45 MB of x86_64 binaries they could never run.
#
#   ./build.sh              -> per-arch zips + a universal zip
#   ./build.sh arm64        -> just arm64-v8a
#   ./build.sh universal    -> just the combined zip (for update.json fallback)
#
set -euo pipefail

cd "$(dirname "$0")"
OUT="dist"
mkdir -p "$OUT"
rm -f "$OUT"/*.zip

VERSION=$(sed -n 's/^version=//p' module.prop | head -n 1 | tr ' /&' '_' | tr -d '\r')
MODID=$(sed -n 's/^id=//p' module.prop | head -n 1 | tr -d '\r')

# Xray-core version is embedded in module.prop's version string, e.g.
#   version=v1.10.1 & Xray-core@v26.7.28
XRAY_VERSION=$(sed -n 's/^version=//p' module.prop | head -n 1 | tr -d '\r' | sed -n 's/.*Xray-core@\(v[0-9.]*\).*/\1/p')
[[ -n "$XRAY_VERSION" ]] || { echo "could not parse Xray-core version from module.prop" >&2; exit 1; }

XRAY_BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}"

# Maps our arch dir names to the asset name Xray-core publishes under.
xray_asset_for() {
    case "$1" in
        arm64-v8a) echo "Xray-linux-arm64-v8a.zip" ;;
        x86_64)    echo "Xray-linux-64.zip" ;;
        *) echo "unknown arch: $1" >&2; exit 1 ;;
    esac
}

# Downloads and unpacks the xray binary for one arch into bin/<arch>/xray,
# skipping the fetch if the binary is already present.
fetch_xray() {
    local arch="$1"
    local dest="bin/${arch}"
    if [[ -x "${dest}/xray" ]]; then
        echo "==> bin/${arch}/xray already present, skipping download"
        return
    fi
    local asset; asset=$(xray_asset_for "$arch")
    local url="${XRAY_BASE_URL}/${asset}"
    local tmp; tmp=$(mktemp -d)
    echo "==> fetching ${url}"
    curl -fsSL -o "${tmp}/${asset}" "$url"
    mkdir -p "$dest"
    unzip -q -o "${tmp}/${asset}" -d "$tmp"
    need "${tmp}/xray"
    install -m 0755 "${tmp}/xray" "${dest}/xray"
    rm -rf "$tmp"
    echo "    installed ${dest}/xray (${XRAY_VERSION})"
}

# Files every build contains, regardless of architecture.
COMMON=(
    META-INF
    webroot
    bin/geoip.dat
    bin/geosite.dat
    customize.sh
    service.sh
    proxy_control.sh
    uninstall.sh
    action.sh
    module.prop
    LICENSE
)

need() {
    for f in "$@"; do
        [[ -e $f ]] || { echo "missing required path: $f" >&2; exit 1; }
    done
}

pack() {
    local arch="$1"; shift
    local zipname="$OUT/${MODID}-${VERSION}-${arch}.zip"
    echo "==> $zipname"
    need "${COMMON[@]}" "$@"
    # -x excludes the other arch; -r recurses into webroot/META-INF.
    zip -q -r -X "$zipname" "${COMMON[@]}" "$@"
    echo "    $(du -h "$zipname" | cut -f1)"
}

target="${1:-all}"

case "$target" in
    arm64|all)     fetch_xray arm64-v8a; pack arm64-v8a bin/arm64-v8a ;;&
    x64|x86_64|all) fetch_xray x86_64;    pack x86_64    bin/x86_64 ;;&
    universal|all) fetch_xray arm64-v8a; fetch_xray x86_64; pack universal bin/arm64-v8a bin/x86_64 ;;&
    arm64|x64|x86_64|universal|all) ;;
    *) echo "usage: $0 [arm64|x64|universal|all]" >&2; exit 2 ;;
esac

echo
echo "Built into $OUT/:"
ls -la "$OUT"
echo
echo "Reminder: point update.json's zipUrl at the universal zip unless you"
echo "publish per-arch update manifests — the module manager cannot pick."