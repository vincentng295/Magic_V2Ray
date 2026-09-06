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
mkdir -p "$MODPATH/webroot"

case "$ARCH" in
    arm64)
        ui_print "- Extracting Xray-core for arm64-v8a..."
        unzip -j -o "$ZIPFILE" "bin/arm64-v8a/*" -d "$MODPATH/bin" >&2 || abort "! Failed to extract arm64 binaries"
        ;;
    x64)
        ui_print "- Extracting Xray-core for Android-x86_64..."
        unzip -j -o "$ZIPFILE" "bin/x86_64/*" -d "$MODPATH/bin" >&2 || abort "! Failed to extract x86_64 binaries"
        ;;
    *)
        ui_print "! Unsupported CPU architecture: $ARCH"
        abort "! This module supports arm64-v8a and x86_64 only."
        ;;
esac

# ---------------------------------------------------------------------------
# 2. Extract scripts, Web UI and routing databases.
#    uninstall.sh MUST be in this list — it was previously omitted, so the
#    root solution never found it and user credentials in $DATADIR survived
#    module removal indefinitely.
# ---------------------------------------------------------------------------
ui_print "- Extracting management scripts and Webroot components..."
unzip -o  "$ZIPFILE" "webroot/*"      -d "$MODPATH/"   >&2 || abort "! Failed to extract webroot"
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
         service.sh proxy_control.sh uninstall.sh action.sh webroot/index.html; do
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

ui_print " "
ui_print "  Magic V2Ray installed."
ui_print "  Open the module's WebUI to configure."
ui_print "  Magisk users need KsuWebUIStandalone to open it."
ui_print " "
