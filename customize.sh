# shellcheck disable=SC2034
# Read by the root manager's install framework, which sources this file —
# not used within customize.sh itself, hence the "unused" false positive.
SKIPUNZIP=1

DATADIR="/data/adb/magic_v2ray"

ui_print "- Detected Architecture: $ARCH"

# ---------------------------------------------------------------------------
# 1. Extract only the matching architecture's binaries.
#    SKIPUNZIP=1 means nothing is extracted unless it is listed here, so this
#    block is the module's complete file manifest.
# ---------------------------------------------------------------------------
mkdir -p "$MODPATH/bin"

case "$ARCH" in
    arm64) ABI=arm64-v8a ;;
    x64)   ABI=x86_64 ;;
    *)
        ui_print "! Unsupported CPU architecture: $ARCH"
        abort "! This module supports arm64-v8a and x86_64 only."
        ;;
esac

# This file is shipped in two layouts:
#   * the Magic V2Ray APK (flash it renamed to .zip): executables are the app's
#     native libs, lib/<abi>/lib{xray,xhuskydg_helper,curl}.so
#   * the headless module zip from build.sh: bin/<abi>/{xray,xhuskydg_helper,curl}
if unzip -l "$ZIPFILE" 2>/dev/null | grep -q "lib/$ABI/libxray.so"; then
    ui_print "- Extracting Xray-core for $ABI (from APK)..."
    LIBTMP="${TMPDIR:-/data/local/tmp}/mv_lib"
    rm -rf "$LIBTMP"; mkdir -p "$LIBTMP"
    unzip -j -o "$ZIPFILE" "lib/$ABI/libxray.so" "lib/$ABI/libxhuskydg_helper.so" "lib/$ABI/libcurl.so" \
        -d "$LIBTMP" >&2 || abort "! Failed to extract $ABI binaries"
    mv -f "$LIBTMP/libxray.so"            "$MODPATH/bin/xray"
    mv -f "$LIBTMP/libxhuskydg_helper.so" "$MODPATH/bin/xhuskydg_helper"
    mv -f "$LIBTMP/libcurl.so"            "$MODPATH/bin/curl"
    rm -rf "$LIBTMP"
else
    ui_print "- Extracting Xray-core for $ABI..."
    unzip -j -o "$ZIPFILE" "bin/$ABI/*" -d "$MODPATH/bin" >&2 || abort "! Failed to extract $ABI binaries"
fi

# ---------------------------------------------------------------------------
# 2. Extract scripts and routing databases. (The Web UI is the Magic V2Ray app.)
#    uninstall.sh MUST be in this list — it was previously omitted, so the
#    root solution never found it and user credentials in $DATADIR survived
#    module removal indefinitely.
# ---------------------------------------------------------------------------
ui_print "- Extracting management scripts..."
unzip -j -o "$ZIPFILE" "proxy_control.sh" -d "$MODPATH" >&2 || abort "! Failed to extract proxy_control.sh"
unzip -j -o "$ZIPFILE" "service.sh"       -d "$MODPATH" >&2 || abort "! Failed to extract service.sh"
unzip -j -o "$ZIPFILE" "uninstall.sh"     -d "$MODPATH" >&2 || abort "! Failed to extract uninstall.sh"
unzip -j -o "$ZIPFILE" "action.sh"        -d "$MODPATH" >&2 || abort "! Failed to extract action.sh"
unzip -j -o "$ZIPFILE" "bin/geoip.dat"    -d "$MODPATH/bin" >&2 || abort "! Failed to extract geoip.dat"
unzip -j -o "$ZIPFILE" "bin/geosite.dat"  -d "$MODPATH/bin" >&2 || abort "! Failed to extract geosite.dat"
unzip -j -o "$ZIPFILE" "module.prop"      -d "$MODPATH" >&2 || abort "! Failed to extract module.prop"

# ---------------------------------------------------------------------------
# 3. Verify the payload actually landed before we let the install succeed.
#    A half-extracted module used to install "successfully" and then fail
#    silently at boot with no indication of why.
# ---------------------------------------------------------------------------
ui_print "- Verifying payload..."
for f in bin/xray bin/xhuskydg_helper bin/curl bin/geoip.dat bin/geosite.dat \
         service.sh proxy_control.sh uninstall.sh action.sh; do
    [ -s "$MODPATH/$f" ] || abort "! Missing or empty after extraction: $f"
done

# ---------------------------------------------------------------------------
# 4. Permissions.
#    Magisk applies its defaults BEFORE sourcing this script, and with
#    SKIPUNZIP=1 nothing existed at that point — so every mode must be set
#    here explicitly.
# ---------------------------------------------------------------------------
ui_print "- Setting permissions..."
set_perm_recursive "$MODPATH"        0 0 0755 0644
set_perm_recursive "$MODPATH/bin"    0 0 0755 0755
set_perm "$MODPATH/service.sh"       0 0 0755
set_perm "$MODPATH/proxy_control.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh"     0 0 0755
set_perm "$MODPATH/action.sh"        0 0 0755
# geo databases are data, not executables
set_perm "$MODPATH/bin/geoip.dat"    0 0 0644
set_perm "$MODPATH/bin/geosite.dat"  0 0 0644

# ---------------------------------------------------------------------------
# 5. Private data directory.
#    Holds profiles, settings and config.json — i.e. every server address,
#    UUID, password and WireGuard private key the user imports. 0700/0600,
#    root-owned. (/data/adb is already 0700, this is defence in depth.)
# ---------------------------------------------------------------------------
ui_print "- Preparing $DATADIR"
[ -e "$DATADIR" ] && [ ! -d "$DATADIR" ] && rm -f "$DATADIR"
mkdir -p "$DATADIR"
set_perm "$DATADIR" 0 0 0700
for f in profiles.base64 settings.base64 active_config.txt config.json ip_hunt.list; do
    [ -f "$DATADIR/$f" ] && set_perm "$DATADIR/$f" 0 0 0600
done

# ---------------------------------------------------------------------------
# 6. Install the app too, when this zip is the APK itself (magic_v2ray.apk
#    renamed to .zip). Best effort: the module is already installed, and the app
#    also (re)installs/updates it on first launch.
# ---------------------------------------------------------------------------
if unzip -l "$ZIPFILE" 2>/dev/null | grep -q "AndroidManifest.xml"; then
    if command -v pm >/dev/null 2>&1; then
        ui_print "- Installing the Magic V2Ray app..."
        TMPAPK="/data/local/tmp/magic_v2ray_install.apk"
        if cp -f "$ZIPFILE" "$TMPAPK" && chmod 644 "$TMPAPK"; then
            PMOUT="$(pm install -r "$TMPAPK" 2>&1)"
            case "$PMOUT" in
                *Success*) ui_print "  App installed." ;;
                *) ui_print "! App install failed: $PMOUT"
                   ui_print "  Install the .apk manually (rename back to .apk)." ;;
            esac
        else
            ui_print "! Could not stage the APK for installation."
        fi
        rm -f "$TMPAPK"
    else
        ui_print "! 'pm' not available here (recovery?). Install the .apk manually."
    fi
fi

ui_print " "
ui_print "  Magic V2Ray installed."
ui_print "  Open the Magic V2Ray app to configure."
ui_print " "
