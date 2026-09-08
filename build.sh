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
#   ./build.sh --update-helper     -> force re-download of xhuskydg_helper even if cached
#   ./build.sh arm64 --update-xray -> combine with a target
#
# The downloaded xray binary's version is cached in bin/<arch>/xray.version.
# On each run, fetch_xray re-downloads whenever that cache is missing, stale
# (doesn't match the Xray-core version parsed from module.prop), or
# --update-xray was passed.
#
# xhuskydg_helper is fetched the same way: one release zip holds both arches
# (<abi>/xhuskydg_helper inside), cached per-arch in bin/<arch>/xhuskydg_helper.version,
# and re-fetched with --update-helper.
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

# xhuskydg_helper ships one release zip covering both arches, laid out as
# <abi>/xhuskydg_helper (e.g. arm64-v8a/xhuskydg_helper, x86_64/xhuskydg_helper).
HELPER_VERSION="v1.0"
HELPER_URL="https://github.com/vincentng295/xhuskydg_helper/releases/download/${HELPER_VERSION}/build-release.zip"

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

# Downloads and unpacks the xhuskydg_helper binary for one arch into
# bin/<arch>/xhuskydg_helper, skipping the fetch if already present at the
# current HELPER_VERSION. The upstream zip bundles both arches under
# <abi>/xhuskydg_helper, so we download it once per invocation (cached in
# HELPER_TMP) and just copy the relevant subfolder out for each arch.
HELPER_TMP=""
fetch_helper() {
    local arch="$1"
    local dest="bin/${arch}"
    local vfile="${dest}/xhuskydg_helper.version"
    if [[ "$FORCE_UPDATE_HELPER" != "1" && -x "${dest}/xhuskydg_helper" && -f "$vfile" && "$(cat "$vfile")" == "$HELPER_VERSION" ]]; then
        echo "==> bin/${arch}/xhuskydg_helper already at ${HELPER_VERSION}, skipping download"
        return
    fi
    if [[ -z "$HELPER_TMP" ]]; then
        HELPER_TMP=$(mktemp -d)
        echo "==> fetching ${HELPER_URL}"
        curl -fsSL -o "${HELPER_TMP}/build-release.zip" "$HELPER_URL"
        unzip -q -o "${HELPER_TMP}/build-release.zip" -d "$HELPER_TMP"
    fi
    need "${HELPER_TMP}/${arch}/xhuskydg_helper"
    mkdir -p "$dest"
    install -m 0755 "${HELPER_TMP}/${arch}/xhuskydg_helper" "${dest}/xhuskydg_helper"
    echo -n "${HELPER_VERSION}" > "$vfile"
    echo "    installed ${dest}/xhuskydg_helper (${HELPER_VERSION})"
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
FORCE_UPDATE_HELPER=0
args=()
for a in "$@"; do
    case "$a" in
        --update-xray)     FORCE_UPDATE_XRAY=1 ;;
        --update-helper)   FORCE_UPDATE_HELPER=1 ;;
        *) args+=("$a") ;;
    esac
done

target="${args[0]:-all}"

case "$target" in
    arm64|all)     fetch_xray arm64-v8a; fetch_helper arm64-v8a; pack arm64-v8a bin/arm64-v8a ;;&
    x64|x86_64|all) fetch_xray x86_64;    fetch_helper x86_64;    pack x86_64    bin/x86_64 ;;&
    universal|all) fetch_xray arm64-v8a; fetch_xray x86_64; fetch_helper arm64-v8a; fetch_helper x86_64; pack universal bin/arm64-v8a bin/x86_64 ;;&
    arm64|x64|x86_64|universal|all) ;;
    *) echo "usage: $0 [arm64|x64|universal|all]" >&2; exit 2 ;;
esac

[[ -n "$HELPER_TMP" ]] && rm -rf "$HELPER_TMP"

echo
echo "Built into $OUT/:"
ls -la "$OUT"
echo
echo "Reminder: point update.json's zipUrl at the universal zip unless you"
echo "publish per-arch update manifests — the module manager cannot pick."