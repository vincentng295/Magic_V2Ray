SKIPUNZIP=1

mkdir -p "$MODPATH/bin"
mkdir -p "$MODPATH/webroot"

ui_print "- Detected Architecture: $ARCH"

# 2. Extract only the matching binary directly into the module's private directory
case "$ARCH" in
    arm64)
        ui_print "- Extracting Xray-core for arm64-v8a..."
        unzip -j -o "$ZIPFILE" "bin/arm64-v8a/*" -d "$MODPATH/bin"
        ;;
    x64)
        ui_print "- Extracting Xray-core for Android-x86_64..."
        unzip -j -o "$ZIPFILE" "bin/x86_64/*" -d "$MODPATH/bin"
        ;;
    *)
        ui_print "❌ Unsupported CPU architecture: $ARCH"
        abort "Unsupported device target!"
        ;;
esac

# 3. Extract core scripts, webroot UI files and structural assets
ui_print "- Extracting management scripts and Webroot components..."
unzip -o "$ZIPFILE" "webroot/*" -d "$MODPATH/"
unzip -j -o "$ZIPFILE" "proxy_control.sh" -d "$MODPATH"
unzip -j -o "$ZIPFILE" "service.sh" -d "$MODPATH"
unzip -j -o "$ZIPFILE" "module.prop" -d "$MODPATH"

# 4. Enforce strict executable permissions natively
ui_print "- Setting executable permissions..."
chmod 755 "$MODPATH/bin/"*

ui_print "- Setup /data/adb/magic_v2ray directory"
if [ ! -d "/data/adb/magic_v2ray" ]; then
    rm -rf "/data/adb/magic_v2ray"
    mkdir -p "/data/adb/magic_v2ray"
fi

ui_print "Magic V2Ray configuration deployment complete!"