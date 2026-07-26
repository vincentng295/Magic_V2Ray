#!/system/bin/sh
#
# Magic V2Ray — control front-end.
#
# Thin client that pushes commands into service.sh's control FIFO. Invoked by
# the WebUI (through the root exec bridge) and by action.sh.
#
# Contract with the WebUI: `status` prints exactly one word on stdout
# (running / crashed / stopped) and encodes the same in its exit code.
# Nothing else may write to stdout.

MODDIR=${0%/*}
DATADIR="/data/adb/magic_v2ray"
STUB_DIR=/dev/sysctl_stubs
RUN_DIR="$STUB_DIR/run"

XRAY_PROC="$STUB_DIR/proc/xray"
PIPE_FILE="$RUN_DIR/control.pipe"
CONTROL_LOOP_PID_FILE="$RUN_DIR/control_loop.pid"
LOG_FILE="$DATADIR/proxy_control.log"

# Diagnostics go to a log file on stderr's channel only.
#
# The previous form was `set -x >"$LOG_FILE" 2>&1`, where the redirection
# applied to the `set` builtin itself and was discarded — so nothing was ever
# logged, while xtrace stayed on for the whole script and sprayed the trace
# into the caller's stderr on every UI action. Note we must NOT redirect
# stdout here: the WebUI parses it.
exec 2>>"$LOG_FILE"
if [ "$(sed -n 's/^debug=//p' "$MODDIR/module.prop" 2>/dev/null | head -n 1)" = "1" ]; then
    set -x
fi

# --- FIFO plumbing ---------------------------------------------------------

# True if service.sh's control loop is alive and the FIFO exists. Without this
# check a write to the FIFO blocks forever waiting for a reader that will
# never come, and the WebUI's exec bridge has no timeout — the UI would sit on
# its loading overlay until the manager app was force-stopped.
control_loop_alive() {
    [ -p "$PIPE_FILE" ] || return 1
    local pid
    pid=$(cat "$CONTROL_LOOP_PID_FILE" 2>/dev/null)
    [ -n "$pid" ] || return 1
    [ -d "/proc/$pid" ] || return 1
    return 0
}

# Sends one command. Bounded by `timeout` as a second line of defence in case
# the loop dies between the liveness check and the write.
send_cmd() {
    local cmd="$1"
    if ! control_loop_alive; then
        echo "service is not running; reboot to recover" >&2
        return 1
    fi
    if ! timeout 10 sh -c "echo '$cmd' > '$PIPE_FILE'"; then
        echo "timed out sending '$cmd' to the service" >&2
        return 1
    fi
    return 0
}

# `wait` is a no-op command used purely as a barrier: it is only consumed once
# the loop has finished handling the command before it.
send_cmd_sync() {
    send_cmd "$1" || return 1
    send_cmd "wait" || return 1
    return 0
}

# --- Commands --------------------------------------------------------------

get_status() {
    if [ -d "$XRAY_PROC" ]; then
        if [ -e "$XRAY_PROC/exe" ]; then
            echo "running"
            return 0
        fi
        # Tracked but gone: the process crashed or exited unexpectedly and its
        # bind mount has not been torn down yet.
        echo "crashed"
        return 2
    fi
    echo "stopped"
    return 1
}

start_proxy() {
    if get_status >/dev/null; then
        echo "running"
        return 0
    fi
    if [ ! -s "$DATADIR/config.json" ]; then
        echo "no config" >&2
        return 1
    fi
    send_cmd_sync "start" || return 1
    echo "started"
}

stop_proxy() {
    # NOTE: config.json is deliberately NOT deleted here any more. Boot-time
    # resume now keys off the separate `enabled` marker that service.sh
    # manages, so stopping the engine no longer destroys the user's selected
    # node — which previously made "stop then start" impossible from
    # action.sh without reopening the WebUI.
    send_cmd_sync "stop" || return 1
    echo "stopped"
}

case "$1" in
    start)   start_proxy ;;
    stop)    stop_proxy ;;
    restart) send_cmd_sync "stop" && sleep 1 && send_cmd_sync "start" && echo "restarted" ;;
    status)  get_status ;;
    reapply) send_cmd_sync "apply_cur_iface" ;;
    start_monitor)          send_cmd_sync "start_monitor" ;;
    stop_monitor)           send_cmd_sync "stop_monitor" ;;
    start_monitor_latency)  send_cmd_sync "start_monitor_latency" ;;
    stop_monitor_latency)   send_cmd_sync "stop_monitor_latency" ;;
    latency_heartbeat)      send_cmd "latency_heartbeat" ;;
    reset_mobile_network)   send_cmd_sync "reset_mobile_network" ;;
    *)
        echo "usage: $0 {start|stop|restart|status|reapply|start_monitor|stop_monitor|start_monitor_latency|stop_monitor_latency|latency_heartbeat|reset_mobile_network}" >&2
        exit 2
        ;;
esac
