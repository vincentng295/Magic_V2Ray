#!/usr/bin/env bash
#
# Magic V2Ray — release builder.
#
# Builds the APKs only. There is no separate zip packaging step any more: each
# APK is also a flashable module zip (rename .apk -> .zip), because it carries
# the installer files (META-INF, customize.sh, ...) at its root next to the
# executables for its ABI(s). Flashing it installs the module, then the app.
#
#   ./build.sh                     -> APKs: arm64-v8a, x86_64 and universal
#   ./build.sh --update-xray       -> force rebuild of xray even if up to date
#   ./build.sh --update-helper     -> force rebuild of xhuskydg_helper even if up to date
#
# Needs JDK 17, Gradle 8.7+, Go (version from core/xray-core/go.mod) and an
# Android SDK (see check_apk_tools below).
#
# xray is built from source in core/xray-core (GOOS=android, cgo with the NDK clang). The result is cached per-arch in module/bin/<arch>/xray, stamped in
# module/bin/<arch>/xray.version with a hash of the sources, and rebuilt whenever
# they change or --update-xray was passed.
#
# xhuskydg_helper is built from source in native/ (C++ applets in native/jni,
# the C service daemon in native/jni/service) with the Android NDK. The result
# is cached per-arch in module/bin/<arch>/xhuskydg_helper, stamped in
# module/bin/<arch>/xhuskydg_helper.version with a hash of native/ + build settings, and
# rebuilt whenever the sources change or --update-helper is passed.
#
# APK: build_apk stages xray/helper/curl as app/src/main/jniLibs/<abi>/lib*.so and runs
# `gradle assembleRelease` (or ./gradlew if present). Output: dist/<id>-<version>-<abi>.apk.
# Set MV_KEYSTORE, MV_KEYSTORE_PASSWORD, MV_KEY_ALIAS, MV_KEY_PASSWORD to sign with your
# own key; otherwise the debug key is used.
# NDK: $ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME / ANDROID_NDK_ROOT),
# else ./android-ndk-r23b. Override the netd xtables lock with MV_XTABLES_LOCK.
#
set -euo pipefail

cd "$(dirname "$0")"
OUT="dist"
mkdir -p "$OUT"

VERSION=$(sed -n 's/^version=//p' module/module.prop | head -n 1 | tr ' /&' '_' | tr -d '\r')
MODID=$(sed -n 's/^id=//p' module/module.prop | head -n 1 | tr -d '\r')

need() {
    for f in "$@"; do
        [[ -e $f ]] || { echo "missing required path: $f" >&2; exit 1; }
    done
}

xray_src_hash() {
    {
        find core/xray-core -type f \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \) \
            -not -path '*/.git/*' -print0 | sort -z | xargs -0 sha256sum
        go version
    } | sha256sum | cut -d' ' -f1
}

# Builds module/bin/<arch>/xray from core/xray-core, skipping the build if the cached
# binary matches the hash of the current sources and Go toolchain.
build_xray() {
    local arch="$1"
    local root="$PWD"
    local dest="module/bin/${arch}"
    local vfile="${dest}/xray.version"
    local hash; hash=$(xray_src_hash)
    if [[ "$FORCE_UPDATE_XRAY" != "1" && -x "${dest}/xray" && -f "$vfile" && "$(cat "$vfile")" == "$hash" ]]; then
        echo "==> module/bin/${arch}/xray is up to date, skipping build"
        return
    fi
    local goarch triple
    case "$arch" in
        arm64-v8a) goarch=arm64; triple="aarch64-linux-android31" ;;
        x86_64)    goarch=amd64; triple="x86_64-linux-android31" ;;
        *) echo "unknown arch: $arch" >&2; exit 1 ;;
    esac
    # GOOS=android needs external (cgo) linking, so build with the NDK clang.
    local ndk tc cc; ndk=$(find_ndk)
    tc=$(echo "$ndk"/toolchains/llvm/prebuilt/*/bin | awk '{print $1}')
    cc="${tc}/${triple}-clang"
    [[ -x "$cc" ]] || { echo "missing ${cc}" >&2; exit 1; }
    echo "==> building xray for ${arch} from core/xray-core (NDK: ${ndk})"
    mkdir -p "$dest"
    ( cd core/xray-core && \
      CGO_ENABLED=1 CC="$cc" GOOS=android GOARCH="$goarch" \
      go build -trimpath -buildvcs=false -ldflags "-s -w -buildid= -checklinkname=0" \
          -o "${root}/${dest}/xray" ./main )
    chmod 0755 "${dest}/xray"
    echo -n "$hash" > "$vfile"
    echo "    built ${dest}/xray ($(du -h "${dest}/xray" | cut -f1))"
}

# Builds module/bin/<arch>/xhuskydg_helper from native/. The service daemon is plain C
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
    local dest="module/bin/${arch}"
    local vfile="${dest}/xhuskydg_helper.version"
    local hash; hash=$(helper_src_hash)
    if [[ "$FORCE_UPDATE_HELPER" != "1" && -x "${dest}/xhuskydg_helper" && -f "$vfile" && "$(cat "$vfile")" == "$hash" ]]; then
        echo "==> module/bin/${arch}/xhuskydg_helper is up to date, skipping build"
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

# BusyBox (osm0sis busybox-ndk, static, GPLv2) for the non-root app: its applets are symlinked
# into files/module/bin at runtime (NonRootEnv). Committed like curl; fetched only if missing.
fetch_busybox() {
    local arch="$1" name
    [[ -x "module/bin/${arch}/busybox" ]] && return
    case "$arch" in
        arm64-v8a) name=busybox-arm64-selinux ;;
        x86_64)    name=busybox-x86_64-selinux ;;
        *) echo "unknown arch: $arch" >&2; exit 1 ;;
    esac
    echo "==> fetching busybox (${arch})"
    mkdir -p "module/bin/${arch}"
    curl -fsSL -o "module/bin/${arch}/busybox" \
        "https://github.com/Magisk-Modules-Repo/busybox-ndk/raw/refs/heads/master/${name}"
    chmod 0755 "module/bin/${arch}/busybox"
}

# Stages the executables as fake JNI libs so the APK can carry them per
# ABI. The package manager extracts lib*.so to nativeLibraryDir; the app then
# copies them to /data/adb/modules/magic_v2ray/bin/ as root.
stage_jnilibs() {
    local arch="$1"
    local dst="app/src/main/jniLibs/${arch}"
    need "module/bin/${arch}/xray" "module/bin/${arch}/xhuskydg_helper" "module/bin/${arch}/curl" "module/bin/${arch}/busybox"
    mkdir -p "$dst"
    install -m 0755 "module/bin/${arch}/xray"             "$dst/libxray.so"
    install -m 0755 "module/bin/${arch}/xhuskydg_helper"  "$dst/libxhuskydg_helper.so"
    install -m 0755 "module/bin/${arch}/curl"             "$dst/libcurl.so"
    install -m 0755 "module/bin/${arch}/busybox"          "$dst/libbusybox.so"
    echo "    staged $dst"
}


# ---------------------------------------------------------------------------
# APK
# ---------------------------------------------------------------------------
GRADLE_CMD=""

# Verifies JDK / Gradle / Android SDK up front, before the slow downloads and
# native builds, and says how to install whatever is missing.
check_apk_tools() {
    local problems=() jv gv gmaj gmin
    if ! command -v java >/dev/null 2>&1; then
        problems+=("JDK 17 is not installed.
      Debian/Ubuntu: sudo apt install openjdk-17-jdk
      macOS:         brew install openjdk@17
      Other:         https://adoptium.net")
    else
        jv=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
        [[ "${jv:-0}" -ge 17 ]] || problems+=("JDK ${jv:-?} found, but JDK 17+ is required (java -version).")
    fi

    [[ -f core/xray-core/go.mod ]] || problems+=("core/xray-core is missing (run: git submodule update --init --recursive).")
    command -v go >/dev/null 2>&1 || problems+=("Go is not installed (see core/xray-core/go.mod for the required version): https://go.dev/dl/")

    if [[ -x ./gradlew ]]; then
        GRADLE_CMD="./gradlew"
    elif command -v gradle >/dev/null 2>&1; then
        GRADLE_CMD="gradle"
        read -r gmaj gmin < <(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9]*\)\.\([0-9]*\).*/\1 \2/p' | head -n 1)
        if [[ -z "${gmaj:-}" ]] || (( gmaj < 8 || (gmaj == 8 && gmin < 7) )); then
            problems+=("Gradle ${gmaj:-?}.${gmin:-?} is too old; the Android Gradle Plugin here needs Gradle 8.7+ (8.9 recommended).
      Install a newer one, e.g.: curl -s https://get.sdkman.io | bash && sdk install gradle 8.9
      (distro packages such as apt's gradle are usually too old)")
        fi
    else
        problems+=("Gradle is not installed. Install Gradle 8.9 (needs JDK 17):
      SDKMAN (Linux/macOS):  curl -s https://get.sdkman.io | bash && sdk install gradle 8.9
      Homebrew:              brew install gradle
      Manual:                https://gradle.org/install/   (apt's gradle package is too old)
      Then re-run ./build.sh. (Optional: 'gradle wrapper --gradle-version 8.9' once, to get ./gradlew.)")
    fi

    if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] && ! grep -qs '^sdk.dir=' local.properties; then
        problems+=("Android SDK not found. Set ANDROID_HOME (or sdk.dir in local.properties) to an SDK that has
      platform android-35 and build-tools:
      sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\"
      (the SDK comes with Android Studio, or as the standalone command-line tools:
       https://developer.android.com/studio#command-tools)")
    fi

    if (( ${#problems[@]} )); then
        echo "Cannot build the APK:" >&2
        local p
        for p in "${problems[@]}"; do echo "  - $p" >&2; done
        exit 1
    fi
}

# An APK is also a module zip (rename .apk -> .zip), so it must carry the installer
# files at its root and the executables for its ABI(s). Fail the build otherwise.
verify_flashable() {
    local f="$1" abis need lib abi list
    command -v unzip >/dev/null 2>&1 || { echo "    (unzip missing, skipped zip-layout check)"; return; }
    list=$(unzip -l "$f")
    for need in META-INF/com/google/android/update-binary META-INF/com/google/android/updater-script \
                customize.sh module.prop service.sh proxy_control.sh uninstall.sh action.sh \
                bin/geoip.dat bin/geosite.dat AndroidManifest.xml; do
        grep -q " ${need}\$" <<<"$list" || { echo "!! $f is missing ${need}" >&2; exit 1; }
    done
    case "$f" in
        *arm64-v8a*) abis="arm64-v8a" ;;
        *x86_64*)    abis="x86_64" ;;
        *)           abis="arm64-v8a x86_64" ;;
    esac
    for abi in $abis; do
        for lib in libxray.so libxhuskydg_helper.so libcurl.so libbusybox.so; do
            grep -q " lib/${abi}/${lib}\$" <<<"$list" || { echo "!! $f is missing lib/${abi}/${lib}" >&2; exit 1; }
        done
    done
}

build_apk() {
    local a f name
    for a in arm64-v8a x86_64; do
        build_xray "$a"; build_helper "$a"; fetch_busybox "$a"; stage_jnilibs "$a"
    done
    echo "==> ${GRADLE_CMD} assembleRelease"
    rm -f app/build/outputs/apk/release/*.apk
    "$GRADLE_CMD" --no-daemon assembleRelease
    for f in app/build/outputs/apk/release/*.apk; do
        [[ -e "$f" ]] || { echo "gradle produced no APK" >&2; exit 1; }
        name=$(basename "$f" .apk); name=${name#app-}; name=${name%-release}
        cp -f "$f" "$OUT/${MODID}-${VERSION}-${name}.apk"
        verify_flashable "$OUT/${MODID}-${VERSION}-${name}.apk"
        echo "    $OUT/${MODID}-${VERSION}-${name}.apk ($(du -h "$f" | cut -f1))"
    done
}

FORCE_UPDATE_XRAY=0
FORCE_UPDATE_HELPER=0
# xtables lock file netd uses (compiled into the service daemon); differs per ROM.
MV_XTABLES_LOCK="${MV_XTABLES_LOCK:-/system/etc/xtables.lock}"

for a in "$@"; do
    case "$a" in
        --update-xray)     FORCE_UPDATE_XRAY=1 ;;
        --update-helper)   FORCE_UPDATE_HELPER=1 ;;
        *) echo "usage: $0 [--update-xray] [--update-helper]" >&2; exit 2 ;;
    esac
done

# Fail fast (before downloads / NDK builds) if the APK toolchain is missing.
check_apk_tools

# Drop stale outputs, including zips left over from older versions of this script.
rm -f "$OUT"/*.apk "$OUT"/*.zip

build_apk

echo
echo "Built into $OUT/:"
ls -la "$OUT"
echo
echo "Each .apk is also a module zip: rename it to .zip to flash it (installs the module, then the app)."
echo "Reminder: point update.json's zipUrl at the universal build unless you"
echo "publish per-arch update manifests — the module manager cannot pick."