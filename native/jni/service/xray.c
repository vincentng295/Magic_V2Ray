/* xray lifecycle (service.sh section 8). `openxtun` is done natively: open
 * /dev/net/tun, TUNSETIFF, hand the fd to xray via XRAY_TUN_FD, exec. */
#include "service.h"
#include "privdrop.h"
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
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;
static pid_t g_xray;

static void bin(char *out, size_t n, const char *name) { snprintf(out, n, "%s/bin/%s", g_moddir, name); }

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

#ifndef __NR_memfd_create
# if defined(__aarch64__)
#  define __NR_memfd_create 279
# elif defined(__x86_64__)
#  define __NR_memfd_create 319
# elif defined(__arm__)
#  define __NR_memfd_create 385
# elif defined(__i386__)
#  define __NR_memfd_create 356
# endif
#endif

/* An anonymous file holding a copy of the config, positioned at offset 0, ready to be xray's stdin
 * (`-c stdin:`). The config can sit in the root-only runtime dir (/mnt/runtime, 0700 root) or in DATADIR, and
 * xray no longer runs as root: it gets the content through an inherited fd instead of a path, and
 * nothing secret (UUIDs, keys) is ever copied to a world-readable place.
 * memfd_create first (kernel >= 3.17); else a file in /dev that is unlinked right after it is opened. */
static int config_to_fd(const char *path)
{
    int in = open(path, O_RDONLY | O_CLOEXEC);
    if (in < 0) { log_msg("cannot open config %s: %s", path, strerror(errno)); return -1; }
    int out = -1;
#ifdef __NR_memfd_create
    out = (int)syscall(__NR_memfd_create, "xray-config", 1u /* MFD_CLOEXEC */);
#endif
    if (out < 0) {
        char tmp[64];
        snprintf(tmp, sizeof tmp, "/dev/.mv_cfg.%d", (int)getpid());
        out = open(tmp, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (out >= 0) unlink(tmp);                   /* the open fd is all that is needed */
    }
    if (out < 0) { log_msg("cannot create a config copy: %s", strerror(errno)); close(in); return -1; }
    char buf[65536];
    for (;;) {
        ssize_t n = read(in, buf, sizeof buf);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        for (ssize_t off = 0; off < n; ) {
            ssize_t w = write(out, buf + off, (size_t)(n - off));
            if (w < 0 && errno == EINTR) continue;
            if (w <= 0) { log_msg("config copy failed: %s", strerror(errno)); close(in); close(out); return -1; }
            off += w;
        }
    }
    close(in);
    if (lseek(out, 0, SEEK_SET) < 0) { close(out); return -1; }
    return out;
}

/* The child sets oom_score_adj = -1000 itself right before the drop; this confirms it from the root side
 * (and retries once if the write did not take), so the log tells whether the kernel / lmkd can still kill xray. */
static void oom_check(pid_t pid)
{
    char path[64], b[16] = "";
    snprintf(path, sizeof path, "/proc/%d/oom_score_adj", (int)pid);
    if (u_read(path, b, sizeof b) > 0 && atoi(b) == -1000) { log_msg("xray oom_score_adj = -1000 (protected from the OOM killer)"); return; }
    if (mv_oom_protect(pid) == 0 && u_read(path, b, sizeof b) > 0 && atoi(b) == -1000)
        log_msg("xray oom_score_adj set to -1000 from the service");
    else
        log_msg("cannot protect xray from the OOM killer (oom_score_adj=%d): %s", atoi(b), strerror(errno));
}

/* Everything async-unsafe happens BEFORE fork; the child only dup2()s, drops privileges (system calls
 * only) and execs. If the drop (or the exec) fails, the child tells the parent through a CLOEXEC pipe:
 * a successful exec closes it, so EOF there means "xray is running". */
static pid_t spawn_xray(void)
{
    char xray[300], cfg[300], envfd[32];
    bin(xray, sizeof xray, "xray");
    u_ov_path(cfg, sizeof cfg, CONFIG_NAME, DATADIR "/" CONFIG_NAME);   /* override session first */
    int tun = open_tun_retry();
    if (tun < 0) return -1;
    int cfgfd = config_to_fd(cfg);
    if (cfgfd < 0) { close(tun); return -1; }
    snprintf(envfd, sizeof envfd, "XRAY_TUN_FD=%d", tun);
    int n = 0; while (environ[n]) n++;
    char **env = malloc((n + 2) * sizeof *env);
    for (int i = 0; i < n; i++) env[i] = environ[i];
    env[n] = envfd; env[n + 1] = NULL;
    char *argv[] = { xray, "run", "-c", "stdin:", "-format", "json", NULL };
    int out = log_fd();

    mv_priv pv;
    bool drop = mv_priv_prepare(&pv, MV_XRAY_UID, MV_XRAY_GID) == 0;
    if (!drop) log_msg("no ambient capabilities on this kernel (%s): xray keeps running as root", strerror(errno));

    int ep[2];
    if (pipe2(ep, O_CLOEXEC) < 0) {
        log_msg("pipe2 failed: %s", strerror(errno));
        close(tun); close(cfgfd); free(env);
        return -1;
    }

    pid_t pid = fork();
    if (pid == 0) {
        int rec[2];
        setsid();
        fcntl(tun, F_SETFD, 0);                      /* the one fd xray must inherit */
        dup2(cfgfd, 0);                              /* config; dup2 clears CLOEXEC on the copy */
        if (out >= 0) { dup2(out, 1); dup2(out, 2); }
        (void)mv_oom_protect(0);                     /* oom_score_adj -1000, needs root: before the drop; verified below */
        if (drop) {
            int step = mv_priv_drop(&pv);
            if (step != MV_PRIV_OK) {
                rec[0] = step; rec[1] = errno;
                (void)!write(ep[1], rec, sizeof rec);
                _exit(126);
            }
        }
        execve(xray, argv, env);
        rec[0] = -1; rec[1] = errno;
        (void)!write(ep[1], rec, sizeof rec);
        _exit(127);
    }
    close(ep[1]); close(tun); close(cfgfd); free(env);   /* only xray keeps the tun alive */
    if (pid < 0) { log_msg("fork failed: %s", strerror(errno)); close(ep[0]); return -1; }

    int rec[2] = { 0, 0 };
    ssize_t r;
    do r = read(ep[0], rec, sizeof rec); while (r < 0 && errno == EINTR);
    close(ep[0]);
    if (r == (ssize_t)sizeof rec) {
        int st; waitpid(pid, &st, 0);
        if (rec[0] < 0) log_msg("cannot execute %s: %s", xray, strerror(rec[1]));
        else log_msg("cannot drop xray to uid %d gid %d: %s failed: %s", (int)MV_XRAY_UID, (int)MV_XRAY_GID,
                     mv_priv_step_name(rec[0]), strerror(rec[1]));
        return -1;
    }
    oom_check(pid);
    if (drop) log_msg("xray runs as uid %d gid %d with all capabilities kept (ambient)", (int)MV_XRAY_UID, (int)MV_XRAY_GID);
    if (proc_track_pid(pid, "xray", xray_exited) != 0) {
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
    xlog_flush();
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
    xlog_save(XRAY_LOG_FILE);   /* keep last run's output for the UI */
    xlog_flush();
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
    xlog_save(XRAY_LOG_FILE);
    xlog_flush();
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
