#!/system/bin/sh
# Magisk/KernelSU only run a file named service.sh. All engine logic lives in the
# native binary; this hands over to it (exec is a shell builtin: no other tool needed).
MODDIR=${0%/*}

# ---------------------------------------------------------------------------
# Subscription auto-update. The WebUI cannot run in the background, so this loop
# does the downloading once an hour and leaves the result for the WebUI:
#   in : $DATADIR/autocheck_sublist.txt   "<url>#<category>" per line (written by the WebUI)
#   out: $DATADIR/pending_sublist.txt     "<category>:<base64 of the node uris>" per line
# The WebUI imports pending_sublist.txt every time it is opened, then deletes it.
# Only subscriptions whose content changed are written out.
# ---------------------------------------------------------------------------
DATADIR=/data/adb/magic_v2ray
SUB_INTERVAL=3600
SUB_FIRST_DELAY=90                      # let the network come up after boot
SUB_UA="v2rayNG/2.2.3"

sub_log() { echo "$(date '+%Y-%m-%d %H:%M:%S') sublist: $*" >> "$DATADIR/service.log" 2>/dev/null; }

# sub_download <url> <outfile> : direct first, then through xray's SOCKS5 inbound
sub_download() {
    for proxy in "" "--socks5-hostname 127.17.1.3:808"; do
        # shellcheck disable=SC2086
        "$MODDIR/bin/curl" $proxy -sSL -f --max-redirs 3 --max-time 40 -A "$SUB_UA" \
            --proto '=http,https' --proto-redir '=http,https' -o "$2" -- "$1" 2>/dev/null \
            && [ -s "$2" ] && return 0
    done
    return 1
}

# sub_nodes <body file> > uris (one per line); handles plain lists and base64 (std / url-safe)
sub_nodes() {
    if head -c 512 "$1" | grep -q '://'; then
        tr -d '\r' < "$1"
    else
        tr -d '\r\n\t ' < "$1" | tr '_-' '/+' > "$1.b64"
        case $(( $(wc -c < "$1.b64") % 4 )) in 2) printf '==' >> "$1.b64";; 3) printf '=' >> "$1.b64";; esac
        base64 -d < "$1.b64" 2>/dev/null | tr -d '\r'
        rm -f "$1.b64"
    fi | grep -E '^[A-Za-z0-9+.-]{2,16}://'
}

sub_update_all() {
    list="$DATADIR/autocheck_sublist.txt"
    cache="$DATADIR/sublist_cache"
    [ -s "$list" ] || return 0
    mkdir -p "$cache"
    # No pending file => the WebUI took the previous one, nothing is waiting any more.
    [ -f "$DATADIR/pending_sublist.txt" ] || rm -f "$cache"/*.dirty
    tmp="$DATADIR/.sublist.tmp"
    n=0; fail=0
    while IFS= read -r entry || [ -n "$entry" ]; do
        entry=$(printf '%s' "$entry" | tr -d '\r')
        case "$entry" in ""|"#"*) continue;; esac
        url=${entry%#*}; cat=${entry##*#}
        case "$url:$cat" in http*:?*) ;; *) continue;; esac
        case "$entry" in *"#"*) ;; *) continue;; esac
        case "$cat" in *:*) continue;; esac                     # ':' separates category and payload
        key=$(printf '%s' "$entry" | md5sum | cut -d' ' -f1)
        n=$((n + 1))
        if ! sub_download "$url" "$tmp.body"; then fail=$((fail + 1)); sub_log "download failed for '$cat'"; continue; fi
        sub_nodes "$tmp.body" > "$tmp.uris"
        rm -f "$tmp.body"
        if [ ! -s "$tmp.uris" ]; then fail=$((fail + 1)); sub_log "no node found in '$cat'"; continue; fi
        sum=$(md5sum < "$tmp.uris" | cut -d' ' -f1)
        [ "$(cat "$cache/$key.sum" 2>/dev/null)" = "$sum" ] && continue    # unchanged since the last push
        payload=$(base64 -w0 < "$tmp.uris" 2>/dev/null || base64 < "$tmp.uris" | tr -d '\n')
        printf '%s:%s\n' "$cat" "$payload" > "$cache/$key.line"
        printf '%s' "$sum" > "$cache/$key.sum"
        : > "$cache/$key.dirty"
    done < "$list"
    rm -f "$tmp.uris"
    # Rebuild pending from every dirty entry (also the ones the WebUI has not taken yet) and
    # publish it with an atomic rename, so the WebUI never reads half a file.
    set -- "$cache"/*.dirty
    if [ -e "$1" ]; then
        : > "$tmp.pending"
        for d in "$@"; do cat "${d%.dirty}.line" >> "$tmp.pending" 2>/dev/null; done
        chmod 600 "$tmp.pending"
        mv -f "$tmp.pending" "$DATADIR/pending_sublist.txt"
    fi
    sub_log "$n url(s), $fail failed"
}

sub_loop() {
    sleep "$SUB_FIRST_DELAY"
    while :; do
        sub_update_all
        sleep "$SUB_INTERVAL"
    done
}

# Detach the loop completely (own session, no inherited fds), then become the engine.
# The loop's pid is kept so that a stopped / upgraded service (xhuskydg_helper service --stop)
# never leaves a second copy behind: the helper kills it on shutdown, and a fresh start
# kills any leftover first.
SUB_PID_FILE="$DATADIR/.sub_loop.pid"
old=$(cat "$SUB_PID_FILE" 2>/dev/null)
if [ -n "$old" ] && grep -q 'service\.sh' "/proc/$old/cmdline" 2>/dev/null; then kill -9 "$old" 2>/dev/null; fi
rm -f "$SUB_PID_FILE"
if [ -x "$MODDIR/bin/curl" ]; then
    ( sub_loop </dev/null >/dev/null 2>&1 & echo $! > "$SUB_PID_FILE" )
fi

exec "$MODDIR/bin/xhuskydg_helper" service -m "$MODDIR"
