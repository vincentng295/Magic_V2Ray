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

static void *reaper(void *arg)
{
    pid_t pid = (pid_t)(intptr_t)arg; int st;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    log_msg("xray pid %d exited (%s %d)", (int)pid, WIFSIGNALED(st) ? "signal" : "status",
            WIFSIGNALED(st) ? WTERMSIG(st) : WEXITSTATUS(st));
    return NULL;
}

/* Everything async-unsafe happens BEFORE fork; the child only dup2()s and execs. */
static pid_t spawn_xray(void)
{
    char xray[300], cfg[300], envfd[32];
    bin(xray, sizeof xray, "xray");
    snprintf(cfg, sizeof cfg, DATADIR "/" CONFIG_NAME);
    int tun = open("/dev/net/tun", O_RDWR);          /* no CLOEXEC: xray inherits it */
    if (tun < 0) { log_msg("cannot open /dev/net/tun: %s", strerror(errno)); return -1; }
    struct ifreq ifr; memset(&ifr, 0, sizeof ifr);
    snprintf(ifr.ifr_name, IFNAMSIZ, "%s", TUN_NAME);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (ioctl(tun, TUNSETIFF, &ifr) < 0) { log_msg("TUNSETIFF %s failed: %s", TUN_NAME, strerror(errno)); close(tun); return -1; }
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
        if (nul >= 0) dup2(nul, 0);
        if (out >= 0) { dup2(out, 1); dup2(out, 2); }
        execve(xray, argv, env);
        _exit(127);
    }
    close(tun); free(env); if (nul >= 0) close(nul);   /* only xray keeps the tun alive */
    if (pid > 0) {
        pthread_t th;
        if (pthread_create(&th, NULL, reaper, (void *)(intptr_t)pid) == 0) pthread_detach(th);
    }
    return pid;
}

static void kill_stale_pidfile(void)
{
    char b[32];
    if (u_read(PIDFILE, b, sizeof b) > 0) {
        pid_t p = atoi(b);
        if (p > 0 && p != g_xray) { log_msg("killing untracked xray pid %d from pidfile", (int)p); kill(p, SIGKILL); }
    }
}

static bool config_ok(void)
{
    struct stat st;
    return stat(DATADIR "/" CONFIG_NAME, &st) == 0 && st.st_size > 0;
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
    proc_track_pid(g_xray, "xray");
    apply_routing_rules();
    /* upstream setting is saved with settings that force a full restart and
     * the monitor only reacts to route events: re-pin the fwmark rule here */
    refresh_mark_rule();
    apps_monitor_start();
    u_write(ENABLED_FLAG, "");
    return 0;
}

int stop_xray(void)
{
    apps_monitor_stop();
    clear_routing_rules();
    if (g_xray > 0) kill(g_xray, SIGKILL);
    kill_stale_pidfile();
    unlink(PIDFILE);
    g_xray = 0;
    proc_untrack("xray");
    unlink(ENABLED_FLAG);
    log_msg("xray stopped");
    xray_helper_logservice("read", "-o", XRAY_LOG_FILE);   /* keep last run's output for the UI */
    xray_helper_logservice("flush", NULL, NULL);
    return 0;
}

/* Swap the process for a fresh one on the new config WITHOUT touching
 * firewall/policy routing (only the tun device is recreated). */
int restart_xray(void)
{
    if (!config_ok()) { log_msg("refusing to reload: %s missing or empty", CONFIG_NAME); return -1; }
    install_tun_blackhole();                    /* fail closed while the tun is gone */
    if (g_xray > 0) kill(g_xray, SIGKILL);
    kill_stale_pidfile();
    proc_untrack("xray");
    xray_helper_logservice("read", "-o", XRAY_LOG_FILE);
    xray_helper_logservice("flush", NULL, NULL);
    g_xray = spawn_xray();
    if (g_xray <= 0) { g_xray = 0; return -1; }
    u_writef(PIDFILE, "%d\n", (int)g_xray);
    log_msg("xray reloaded with pid %d (tun=%s re-created, firewall/ip-rule left untouched)", (int)g_xray, TUN_NAME);
    proc_track_pid(g_xray, "xray");
    if (configure_tun_iface() < 0) return -1;
    u_write(ENABLED_FLAG, "");
    return 0;
}
