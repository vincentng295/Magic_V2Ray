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
#   ./build.sh --update-helper    -> force rebuild of xhuskydg_helper even if up to date
#   ./build.sh arm64 --update-xray -> combine with a target
#
# The downloaded xray binary's version is cached in bin/<arch>/xray.version.
# On each run, fetch_xray re-downloads whenever that cache is missing, stale
# (doesn't match the Xray-core version parsed from module.prop), or
# --update-xray was passed.
#
# xhuskydg_helper is built from source in native/ (C++ applets in native/jni,
# the C service daemon in native/jni/service) with the Android NDK. The result
# is cached per-arch in bin/<arch>/xhuskydg_helper, stamped in
# bin/<arch>/xhuskydg_helper.version with a hash of native/ + build settings, and
# rebuilt whenever the sources change or --update-helper is passed.
# NDK: $ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME / ANDROID_NDK_ROOT),
# else ./android-ndk-r23b. Override the netd xtables lock with MV_XTABLES_LOCK.
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

# Builds bin/<arch>/xhuskydg_helper from native/. The service daemon is plain C
# (compiled with the C driver, clang) and the applets are C++ (clang++); both are
# linked statically by clang++. Skipped when the cached binary matches the hash
# of the current sources and build settings.
find_ndk() {
    local d
    for d in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}" "${ANDROID_NDK_ROOT:-}" "./android-ndk-r23b"; do
        [[ -n "$d" && -d "$d/toolchains/llvm/prebuilt" ]] && { echo "$d"; return; }
    done
    echo "Android NDK not found: set ANDROID_NDK_HOME (or unpack android-ndk-r23b next to build.sh)" >&2
    exit 1
}

helper_src_hash() {
    { find native -type f \( -name '*.c' -o -name '*.cpp' -o -name '*.h' \) -not -path 'native/tests/*' -print0 \
        | sort -z | xargs -0 sha256sum
      echo "xtables_lock=${MV_XTABLES_LOCK}"
    } | sha256sum | cut -d' ' -f1
}

build_helper() {
    local arch="$1"
    local dest="bin/${arch}"
    local vfile="${dest}/xhuskydg_helper.version"
    local hash; hash=$(helper_src_hash)
    if [[ "$FORCE_UPDATE_HELPER" != "1" && -x "${dest}/xhuskydg_helper" && -f "$vfile" && "$(cat "$vfile")" == "$hash" ]]; then
        echo "==> bin/${arch}/xhuskydg_helper is up to date, skipping build"
        return
    fi
    local triple
    case "$arch" in
        arm64-v8a) triple="aarch64-linux-android31" ;;
        x86_64)    triple="x86_64-linux-android31" ;;
        *) echo "unknown arch: $arch" >&2; exit 1 ;;
    esac
    local ndk tc; ndk=$(find_ndk)
    tc=$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin | awk '{print $1}')
    [[ -x "${tc}/${triple}-clang" ]] || { echo "missing ${tc}/${triple}-clang" >&2; exit 1; }
    local cc="${tc}/${triple}-clang" cxx="${tc}/${triple}-clang++"

    echo "==> building xhuskydg_helper for ${arch} (NDK: ${ndk})"
    local obj; obj=$(mktemp -d)
    local cflags=(-std=gnu11 -D_GNU_SOURCE -Os -ffunction-sections -fdata-sections
                  -fstack-protector-strong -Wall -Wextra -Wno-override-init
                  "-DMV_XTABLES_LOCK=\"${MV_XTABLES_LOCK}\"")
    local cxxflags=(-std=c++17 -Os -ffunction-sections -fdata-sections)
    local objs=() src o
    for src in native/jni/service/*.c; do
        o="${obj}/$(basename "${src%.c}").o"
        "$cc" "${cflags[@]}" -c "$src" -o "$o"
        objs+=("$o")
    done
    for src in native/jni/*.cpp; do
        o="${obj}/$(basename "${src%.cpp}").o"
        "$cxx" "${cxxflags[@]}" -c "$src" -o "$o"
        objs+=("$o")
    done
    mkdir -p "$dest"
    "$cxx" "${objs[@]}" -static -Wl,--gc-sections -o "${obj}/xhuskydg_helper"
    "${tc}/llvm-strip" "${obj}/xhuskydg_helper"
    install -m 0755 "${obj}/xhuskydg_helper" "${dest}/xhuskydg_helper"
    echo -n "$hash" > "$vfile"
    rm -rf "$obj"
    echo "    built ${dest}/xhuskydg_helper ($(du -h "${dest}/xhuskydg_helper" | cut -f1))"
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
# xtables lock file netd uses (compiled into the service daemon); differs per ROM.
MV_XTABLES_LOCK="${MV_XTABLES_LOCK:-/system/etc/xtables.lock}"
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
    arm64|all)     fetch_xray arm64-v8a; build_helper arm64-v8a; pack arm64-v8a bin/arm64-v8a ;;&
    x64|x86_64|all) fetch_xray x86_64;    build_helper x86_64;    pack x86_64    bin/x86_64 ;;&
    universal|all) fetch_xray arm64-v8a; fetch_xray x86_64; build_helper arm64-v8a; build_helper x86_64; pack universal bin/arm64-v8a bin/x86_64 ;;&
    arm64|x64|x86_64|universal|all) ;;
    *) echo "usage: $0 [arm64|x64|universal|all]" >&2; exit 2 ;;
esac

echo
echo "Built into $OUT/:"
ls -la "$OUT"
echo
echo "Reminder: point update.json's zipUrl at the universal zip unless you"
echo "publish per-arch update manifests — the module manager cannot pick."