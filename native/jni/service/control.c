/* Command loop (service.sh section 8): the WebUI writes one command per line
 * into the FIFO. Opened O_RDWR so a reader is always present and read() never
 * sees EOF - it simply blocks in the kernel while idle. */
#include "service.h"
#include "netlink.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int g_fifo = -1;

int control_submit(const char *cmd)
{
    char b[128]; int n = snprintf(b, sizeof b, "%s\n", cmd);
    return g_fifo >= 0 && write(g_fifo, b, n) == n ? 0 : -1;
}

#define LOCKED(stmt) do { pthread_mutex_lock(&g_lock); stmt; pthread_mutex_unlock(&g_lock); } while (0)

/* 0 = done, <0 = unknown command, >0 = stop the service */
static int do_job(const char *c)
{
    if (!strcmp(c, "wait")) return 0;
    if (!strcmp(c, "apply_cur_iface")) { log_msg("re-applying mark rule"); LOCKED(refresh_mark_rule()); return 0; }
    if (!strcmp(c, "start")) { LOCKED(start_xray()); return 0; }
    if (!strcmp(c, "stop")) { LOCKED(stop_xray()); return 0; }
    if (!strcmp(c, "reload_config")) { LOCKED(restart_xray()); return 0; }
    if (!strcmp(c, "hard_restart")) {
        /* full stop+start with all non-xray traffic held so nothing leaks */
        pthread_mutex_lock(&g_lock);
        hold_traffic();
        stop_xray();
        if (start_xray() < 0) log_msg("hard_restart: start failed");
        release_traffic();
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    if (!strcmp(c, "start_monitor")) { iface_monitor_start(); return 0; }
    if (!strcmp(c, "stop_monitor")) { iface_monitor_stop(); return 0; }
    if (!strcmp(c, "start_monitor_apps")) { apps_monitor_start(); return 0; }
    if (!strcmp(c, "stop_monitor_apps")) { apps_monitor_stop(); log_msg("apps monitor stopped"); return 0; }
    if (!strcmp(c, "start_monitor_latency")) { latency_monitor_start(); return 0; }
    if (!strcmp(c, "stop_monitor_latency")) { latency_monitor_stop(); log_msg("latency monitor stopped"); return 0; }
    if (!strcmp(c, "latency_heartbeat")) {      /* refreshed by the UI while its Latency tab is open */
        if (u_exists(LATENCY_HB_FILE)) u_writef(LATENCY_HB_FILE, "%ld\n", u_now_s());
        return 0;
    }
    if (!strcmp(c, "reset_mobile_network")) {
        char ifn[64];
        pthread_mutex_lock(&g_lock);
        if (get_active_interface(ifn, sizeof ifn) == 0) { ip_hunt_reset(); check_ip_hunter(ifn); }
        pthread_mutex_unlock(&g_lock);
        return 0;
    }
    if (!strcmp(c, "stop_svc")) return 1;       /* leave the loop: service_shutdown() then exits */
    return -1;
}

int control_run_prepare(void)
{
    unlink(PIPE_FILE);
    if (mkfifo(PIPE_FILE, 0600) < 0 && errno != EEXIST) { log_msg("mkfifo %s: %s", PIPE_FILE, strerror(errno)); return -1; }
    chmod(PIPE_FILE, 0600);
    g_fifo = open(PIPE_FILE, O_RDWR | O_CLOEXEC);
    if (g_fifo < 0) return -1;
    u_writef(CONTROL_LOOP_PID_FILE, "%d\n", (int)getpid());
    return 0;
}

int control_run(void)
{
    char buf[1024]; size_t have = 0;
    for (;;) {
        ssize_t r = read(g_fifo, buf + have, sizeof buf - have - 1);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        have += r; buf[have] = 0;
        char *start = buf, *nl;
        while ((nl = strchr(start, '\n'))) {
            *nl = 0;
            u_trim(start);
            if (*start) {
                int jr = do_job(start);
                if (jr < 0) log_msg("unknown command: %s", start);
                else if (jr > 0) { service_shutdown(); return 0; }
            }
            start = nl + 1;
        }
        have = strlen(start);
        memmove(buf, start, have + 1);
        if (have >= sizeof buf - 1) have = 0;          /* discard an overlong line */
    }
}
