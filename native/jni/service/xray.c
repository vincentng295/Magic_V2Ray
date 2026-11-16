/* xray lifecycle (service.sh section 8). `openxtun` is done natively: open
 * /dev/net/tun, TUNSETIFF, hand the fd to xray via XRAY_TUN_FD, exec. */
#include "service.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static pid_t g_xray;

static void bin(char *out, size_t n, const char *name) { snprintf(out, n, "%s/bin/%s", g_moddir, name); }

void xray_helper_logservice(const char *verb, const char *flag, const char *val)
{
    char *h = g_self;                              /* logservice is an applet of this very binary */
    char *argv[8] = { h, "logservice", (char *)verb, NULL, NULL, NULL, NULL, NULL };
    int i = 3;
    argv[i++] = "-c"; argv[i++] = LOG_CTRL;
    if (flag) { argv[i++] = (char *)flag; argv[i++] = (char *)val; }
    u_run(argv);
}

/* Tell the app that xray started / stopped, so it can announce it to other apps (README "State events").
 * Fire and forget on a helper thread: `am` takes a second to start and must never hold up the control loop.
 * Bursts (hard_restart = stop + reaper + start) are merged. The app does not trust anything in this broadcast,
 * it only re-reads the real status. */
static volatile int g_notify_busy, g_notify_again;

static void *notify_thread(void *arg)
{
    (void)arg;
    for (;;) {
        g_notify_again = 0;
        char *argv[] = { "/system/bin/am", "broadcast", "--user", "0",
                         "-a", "com.huskydg.magicv2ray.action.ENGINE_CHANGED",
                         "-p", "com.huskydg.magicv2ray", NULL };
        u_run(argv);
        if (!g_notify_again) {
            g_notify_busy = 0;
            __sync_synchronize();
            if (!g_notify_again || __sync_lock_test_and_set(&g_notify_busy, 1)) break;
        }
    }
    return NULL;
}

void notify_app_state(void)
{
    if (__sync_lock_test_and_set(&g_notify_busy, 1)) { g_notify_again = 1; return; }
    pthread_t th;
    if (pthread_create(&th, NULL, notify_thread, NULL) == 0) pthread_detach(th);
    else g_notify_busy = 0;
}

/* Called by proc_track's reaper thread once xray has been waitpid()ed (no zombie is left behind). */
static void xray_exited(pid_t pid, int st)
{
    if (st < 0) log_msg("xray pid %d exited (status unknown)", (int)pid);
    else log_msg("xray pid %d exited (%s %d)", (int)pid, WIFSIGNALED(st) ? "signal" : "status",
                 WIFSIGNALED(st) ? WTERMSIG(st) : WEXITSTATUS(st));
    notify_app_state();                            /* covers a crash; our own kills are merged / ignored by the app */
}

/* SIGKILL is asynchronous: kill() returns before the process is gone, and a non-persistent
 * tun device is only released when the LAST holder of its fd exits. Starting the next xray
 * right after the kill therefore raced the old one and failed with
 * "TUNSETIFF xraytun0 failed: Device or resource busy". Block until the pid is really gone
 * (for our own child that means: its reaper thread has waitpid()ed it). */
static void kill_and_wait(pid_t pid)
{
    if (pid <= 1) return;
    kill(pid, SIGKILL);
    if (!proc_wait_gone(pid, 3000)) log_msg("xray pid %d still alive 3s after SIGKILL", (int)pid);
}

/* Opens /dev/net/tun and attaches it to TUN_NAME. EBUSY (old holder not released yet) is
 * retried, as the old openxtun did. O_CLOEXEC: no concurrent fork()/exec elsewhere in the
 * service can inherit the fd and keep the device busy; the xray child clears the flag. */
static int open_tun_retry(void)
{
    for (int attempt = 0; attempt < 25; attempt++) {
        int tun = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
        if (tun < 0) { log_msg("cannot open /dev/net/tun: %s", strerror(errno)); return -1; }
        struct ifreq ifr; memset(&ifr, 0, sizeof ifr);
        snprintf(ifr.ifr_name, IFNAMSIZ, "%s", TUN_NAME);
        ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
        if (ioctl(tun, TUNSETIFF, &ifr) == 0) return tun;
        int e = errno;
        close(tun);
        if (e != EBUSY) { log_msg("TUNSETIFF %s failed: %s", TUN_NAME, strerror(e)); return -1; }
        if (attempt == 0) log_msg("TUNSETIFF %s busy, waiting for the previous owner to release it", TUN_NAME);
        u_msleep(200);
    }
    log_msg("TUNSETIFF %s failed: %s (still busy after retries)", TUN_NAME, strerror(EBUSY));
    return -1;
}

/* Everything async-unsafe happens BEFORE fork; the child only dup2()s and execs. */
static pid_t spawn_xray(void)
{
    char xray[300], cfg[300], envfd[32];
    bin(xray, sizeof xray, "xray");
    u_ov_path(cfg, sizeof cfg, CONFIG_NAME, DATADIR "/" CONFIG_NAME);   /* override session first */
    int tun = open_tun_retry();
    if (tun < 0) return -1;
    snprintf(envfd, sizeof envfd, "XRAY_TUN_FD=%d", tun);
    int n = 0; while (environ[n]) n++;
    char **env = malloc((n + 2) * sizeof *env);
    for (int i = 0; i < n; i++) env[i] = environ[i];
    env[n] = envfd; env[n + 1] = NULL;
    char *argv[] = { xray, "run", "-c", cfg, NULL };
    int nul = open("/dev/null", O_RDONLY | O_CLOEXEC), out = log_fd();

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        fcntl(tun, F_SETFD, 0);                      /* the one fd xray must inherit */
        if (nul >= 0) dup2(nul, 0);
        if (out >= 0) { dup2(out, 1); dup2(out, 2); }
        execve(xray, argv, env);
        _exit(127);
    }
    close(tun); free(env); if (nul >= 0) close(nul);   /* only xray keeps the tun alive */
    if (pid > 0 && proc_track_pid(pid, "xray", xray_exited) != 0) {
        /* without a tracked pid + reaper we could neither tell its state nor clear its zombie */
        log_msg("cannot track xray pid %d (no reaper thread): killing it", (int)pid);
        int st; kill(pid, SIGKILL); waitpid(pid, &st, 0);
        return -1;
    }
    return pid;
}

/* Deliberate stop of our own xray. It is untracked FIRST, so that its exit is never reported as a crash
 * (status would flicker "crashed" between the kill and the untrack). A child that already died is not
 * signalled: its pid is free and may belong to somebody else by now. */
static void kill_tracked_xray(void)
{
    bool alive = proc_running("xray");
    pid_t p = g_xray;
    proc_untrack("xray");
    if (alive) kill_and_wait(p);
}

/* A pid left in the pidfile by a previous service. Pids get reused, so it is only killed when it still is
 * this module's xray binary. (Our own child never goes through here: it is tracked.) */
static void kill_stale_pidfile(void)
{
    char b[32];
    if (u_read(PIDFILE, b, sizeof b) > 0) {
        pid_t p = atoi(b);
        if (p > 1 && p != g_xray) {
            char link[64], want[300], got[300];
            snprintf(link, sizeof link, "/proc/%d/exe", (int)p);
            bin(want, sizeof want, "xray");
            ssize_t n = readlink(link, got, sizeof got - 1);
            if (n > 0) {
                got[n] = 0;
                if (!strcmp(got, want)) { log_msg("killing untracked xray pid %d from pidfile", (int)p); kill_and_wait(p); }
                else log_msg("pidfile pid %d is %s, not xray: left alone", (int)p, got);
            }
        }
    }
}

static bool config_ok(void)
{
    struct stat st;
    char p[300];
    u_ov_path(p, sizeof p, CONFIG_NAME, DATADIR "/" CONFIG_NAME);
    return stat(p, &st) == 0 && st.st_size > 0;
}

/* The ENABLED_FLAG decides what is resumed at boot. A temporary override session must never change it:
 * after a reboot the session is gone and only what the user had set up may come back. */
static void enabled_set(bool on)
{
    if (u_ov_active()) return;
    if (on) u_write(ENABLED_FLAG, ""); else unlink(ENABLED_FLAG);
}

int start_xray(void)
{
    if (proc_running("xray")) { log_msg("xray already running (pid %d)", (int)g_xray); return 0; }
    if (!config_ok()) { log_msg("refusing to start: %s missing or empty", CONFIG_NAME); return -1; }
    xray_helper_logservice("flush", NULL, NULL);
    g_xray = spawn_xray();
    if (g_xray <= 0) { g_xray = 0; return -1; }
    u_writef(PIDFILE, "%d\n", (int)g_xray);
    log_msg("xray (tun=%s) started with pid %d", TUN_NAME, (int)g_xray);
    apply_routing_rules();
    /* upstream setting is saved with settings that force a full restart and
     * the monitor only reacts to route events: re-pin the fwmark rule here */
    refresh_mark_rule();
    apps_monitor_start();
    enabled_set(true);
    notify_app_state();
    return 0;
}

int stop_xray(void)
{
    apps_monitor_stop();
    clear_routing_rules();
    kill_tracked_xray();
    kill_stale_pidfile();
    unlink(PIDFILE);
    g_xray = 0;
    enabled_set(false);
    log_msg("xray stopped");
    notify_app_state();
    xray_helper_logservice("read", "-o", XRAY_LOG_FILE);   /* keep last run's output for the UI */
    xray_helper_logservice("flush", NULL, NULL);
    return 0;
}

/* Swap the process for a fresh one on the new config WITHOUT touching
 * firewall/policy routing (only the tun device is recreated). */
int restart_xray(void)
{
    if (!config_ok()) { log_msg("refusing to reload: %s missing or empty", CONFIG_NAME); return -1; }
    root_direct_window(true);                   /* uid 0 (dnsjson) keeps the real network while the tun is down */
    install_tun_blackhole();                    /* fail closed for everyone else while the tun is gone */
    kill_tracked_xray();
    kill_stale_pidfile();
    xray_helper_logservice("read", "-o", XRAY_LOG_FILE);
    xray_helper_logservice("flush", NULL, NULL);
    g_xray = spawn_xray();
    if (g_xray <= 0) { g_xray = 0; root_direct_window(false); return -1; }
    u_writef(PIDFILE, "%d\n", (int)g_xray);
    log_msg("xray reloaded with pid %d (tun=%s re-created, firewall/ip-rule left untouched)", (int)g_xray, TUN_NAME);
    int rc = configure_tun_iface();
    root_direct_window(false);                  /* tun is back: uid 0 returns to normal routing */
    if (rc < 0) return -1;
    enabled_set(true);
    return 0;
}
