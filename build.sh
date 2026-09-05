#!/usr/bin/env bash
#
# Magic V2Ray — release packager.
#
# Produces one zip per CPU architecture instead of a single universal zip.
# The universal zip carried both arm64-v8a and x86_64 payloads (~118 MB) and
# then discarded one of them at install time, so every arm64 user downloaded
# ~45 MB of x86_64 binaries they could never run.
#
#   ./build.sh                    -> per-arch zips + a universal zip
#   ./build.sh arm64              -> just arm64-v8a
#   ./build.sh universal          -> just the combined zip (for update.json fallback)
#   ./build.sh --update-xray      -> force re-download of xray even if cached
#   ./build.sh --update-openxtun  -> force re-download of openxtun even if cached
#   ./build.sh arm64 --update-xray -> combine with a target
#
# The downloaded xray binary's version is cached in bin/<arch>/xray.version.
# On each run, fetch_xray re-downloads whenever that cache is missing, stale
# (doesn't match the Xray-core version parsed from module.prop), or
# --update-xray was passed.
#
# openxtun is fetched the same way: one release zip holds both arches
# (<abi>/openxtun inside), cached per-arch in bin/<arch>/openxtun.version,
# and re-fetched with --update-openxtun.
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

# openxtun ships one release zip covering both arches, laid out as
# <abi>/openxtun (e.g. arm64-v8a/openxtun, x86_64/openxtun).
OPENXTUN_VERSION="v1.0"
OPENXTUN_URL="https://github.com/vincentng295/openxtun/releases/download/${OPENXTUN_VERSION}/build-release.zip"

# Maps our arch dir names to the asset name Xray-core publishes under.
xray_asset_for() {
    case "$1" in
        arm64-v8a) echo "Xray-android-arm64-v8a.zip" ;;
        x86_64)    echo "Xray-android-amd64.zip" ;;
        *) echo "unknown arch: $1" >&2; exit 1 ;;
    esac
}

# Downloads and unpacks the xray binary for one arch into bin/<arch>/xray,
# skipping the fetch if the binary is already present.
fetch_xray() {
    local arch="$1"
    local dest="bin/${arch}"
    local vfile="${dest}/xray.version"
    if [[ "$FORCE_UPDATE_XRAY" != "1" && -x "${dest}/xray" && -f "$vfile" && "$(cat "$vfile")" == "$XRAY_VERSION" ]]; then
        echo "==> bin/${arch}/xray already at ${XRAY_VERSION}, skipping download"
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
    echo -n "${XRAY_VERSION}" > "$vfile"
    rm -rf "$tmp"
    echo "    installed ${dest}/xray (${XRAY_VERSION})"
}

# Downloads and unpacks the openxtun binary for one arch into
# bin/<arch>/openxtun, skipping the fetch if already present at the
# current OPENXTUN_VERSION. The upstream zip bundles both arches under
# <abi>/openxtun, so we download it once per invocation (cached in
# OPENXTUN_TMP) and just copy the relevant subfolder out for each arch.
OPENXTUN_TMP=""
fetch_openxtun() {
    local arch="$1"
    local dest="bin/${arch}"
    local vfile="${dest}/openxtun.version"
    if [[ "$FORCE_UPDATE_OPENXTUN" != "1" && -x "${dest}/openxtun" && -f "$vfile" && "$(cat "$vfile")" == "$OPENXTUN_VERSION" ]]; then
        echo "==> bin/${arch}/openxtun already at ${OPENXTUN_VERSION}, skipping download"
        return
    fi
    if [[ -z "$OPENXTUN_TMP" ]]; then
        OPENXTUN_TMP=$(mktemp -d)
        echo "==> fetching ${OPENXTUN_URL}"
        curl -fsSL -o "${OPENXTUN_TMP}/build-release.zip" "$OPENXTUN_URL"
        unzip -q -o "${OPENXTUN_TMP}/build-release.zip" -d "$OPENXTUN_TMP"
    fi
    need "${OPENXTUN_TMP}/${arch}/openxtun"
    mkdir -p "$dest"
    install -m 0755 "${OPENXTUN_TMP}/${arch}/openxtun" "${dest}/openxtun"
    echo -n "${OPENXTUN_VERSION}" > "$vfile"
    echo "    installed ${dest}/openxtun (${OPENXTUN_VERSION})"
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
    # -r recurses into webroot/META-INF/bin/<arch>; -x drops our internal
    # version-cache marker (not part of the module payload).
    zip -q -r -X "$zipname" "${COMMON[@]}" "$@" -x '*/*.version'
    echo "    $(du -h "$zipname" | cut -f1)"
}

FORCE_UPDATE_XRAY=0
FORCE_UPDATE_OPENXTUN=0
args=()
for a in "$@"; do
    case "$a" in
        --update-xray)     FORCE_UPDATE_XRAY=1 ;;
        --update-openxtun) FORCE_UPDATE_OPENXTUN=1 ;;
        *) args+=("$a") ;;
    esac
done

target="${args[0]:-all}"

case "$target" in
    arm64|all)     fetch_xray arm64-v8a; fetch_openxtun arm64-v8a; pack arm64-v8a bin/arm64-v8a ;;&
    x64|x86_64|all) fetch_xray x86_64;    fetch_openxtun x86_64;    pack x86_64    bin/x86_64 ;;&
    universal|all) fetch_xray arm64-v8a; fetch_xray x86_64; fetch_openxtun arm64-v8a; fetch_openxtun x86_64; pack universal bin/arm64-v8a bin/x86_64 ;;&
    arm64|x64|x86_64|universal|all) ;;
    *) echo "usage: $0 [arm64|x64|universal|all]" >&2; exit 2 ;;
esac

[[ -n "$OPENXTUN_TMP" ]] && rm -rf "$OPENXTUN_TMP"

echo
echo "Built into $OUT/:"
ls -la "$OUT"
echo
echo "Reminder: point update.json's zipUrl at the universal zip unless you"
echo "publish per-arch update manifests — the module manager cannot pick."