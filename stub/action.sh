#!/system/bin/sh
#
# Magic V2Ray — installer stub, manager "Action" button.
#
# This zip is only a catalog entry: it carries module.prop and this script.
# Tapping Action downloads the Magic V2Ray APK of the same version from the
# GitHub release, checks its SHA-256 (pinned when the stub was built), installs
# it and opens it. On first launch the app installs the real module
# (service.sh, xray, ...) over this stub in /data/adb/modules/magic_v2ray.
#
# The @...@ placeholders are filled in by stub/build-stub.sh.
# Output goes straight to the manager's action dialog, so keep it short.

PKG="com.huskydg.magicv2ray"
VERSION="@VERSION@"
VERSION_CODE="@VERSION_CODE@"
BASE_URL="@BASE_URL@"
APK_ARM64="@APK_ARM64@"
SHA_ARM64="@SHA_ARM64@"
APK_X64="@APK_X64@"
SHA_X64="@SHA_X64@"
APK_ARM32="@APK_ARM32@"
SHA_ARM32="@SHA_ARM32@"
APK_X86="@APK_X86@"
SHA_X86="@SHA_X86@"
TMPAPK="/data/local/tmp/magic_v2ray_install.apk"

trap 'rm -f "$TMPAPK"' EXIT

installed_code() {
    dumpsys package "$PKG" 2>/dev/null \
        | sed -n 's/^ *versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1
}

launch() {
    echo "Starting Magic V2Ray..."
    if ! am start --user 0 -n "$PKG/.MainActivity" >/dev/null 2>&1; then
        # Fallback: let the package manager resolve the launcher activity.
        monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
            || { echo "Could not start the app."; exit 1; }
    fi
    echo "Open the app once: it installs the module files."
    exit 0
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v toybox >/dev/null 2>&1; then
        toybox sha256sum "$1" | cut -d' ' -f1
    fi
}

# fetch URL FILE — curl if present, then wget (busybox applet or a known
# busybox binary). Returns non-zero if every method fails.
fetch() {
    _url=$1
    _out=$2
    if command -v curl >/dev/null 2>&1 \
        && curl -fsSL --connect-timeout 20 --max-time 900 -o "$_out" "$_url"; then
        return 0
    fi
    rm -f "$_out"
    for _bb in "" /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
        if [ -z "$_bb" ]; then
            command -v wget >/dev/null 2>&1 || continue
            wget -q -T 30 -O "$_out" "$_url" && return 0
        else
            [ -x "$_bb" ] || continue
            "$_bb" wget -q -T 30 -O "$_out" "$_url" && return 0
        fi
        rm -f "$_out"
    done
    return 1
}

# Already installed at this version or newer: just open it.
cur="$(installed_code)"
if [ -n "$cur" ] && [ "$cur" -ge "$VERSION_CODE" ] 2>/dev/null; then
    echo "App $VERSION is already installed."
    launch
fi

# Pick the APK for the device's primary supported ABI.
abis="$(getprop ro.product.cpu.abilist 2>/dev/null)"
[ -n "$abis" ] || abis="$(getprop ro.product.cpu.abi 2>/dev/null)"
APK=""
SHA=""
oldifs=$IFS
IFS=,
for a in $abis; do
    case "$a" in
        arm64-v8a) APK="$APK_ARM64"; SHA="$SHA_ARM64"; break ;;
        armeabi-v7a) APK="$APK_ARM32"; SHA="$SHA_ARM32"; break ;;
        x86_64)    APK="$APK_X64";   SHA="$SHA_X64";   break ;;
        x86)       APK="$APK_X86";   SHA="$SHA_X86";   break ;;
    esac
done
IFS=$oldifs
if [ -z "$APK" ]; then
    echo "Unsupported CPU ($abis): arm64-v8a, armeabi-v7a, x86_64 or x86 is required."
    exit 1
fi

URL="$BASE_URL/$APK"
echo "Downloading $APK ..."
rm -f "$TMPAPK"
if ! fetch "$URL" "$TMPAPK" || [ ! -s "$TMPAPK" ]; then
    echo "Download failed. Check the connection, or download it manually:"
    echo "$URL"
    exit 1
fi

if [ "$(sha256_of "$TMPAPK")" != "$SHA" ]; then
    echo "Checksum mismatch - the APK was NOT installed."
    exit 1
fi

chmod 644 "$TMPAPK"
echo "Installing Magic V2Ray app..."
PMOUT="$(pm install -r "$TMPAPK" 2>&1)"
case "$PMOUT" in
    *Success*) echo "App installed." ;;
    *)
        echo "App install failed: $PMOUT"
        exit 1
        ;;
esac

launch
