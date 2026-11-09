/* Magic V2Ray service entry point: boot sequencer (service.sh sections 1 and 9).
 * Applet of xhuskydg_helper:  xhuskydg_helper service -m $MODDIR [-f]
 * main.cpp calls run_service() with argv[0] = "service". */
#include "service.h"
#include "ipt.h"
#include <sys/socket.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

char g_moddir[256];
char g_self[256];
bool g_debug;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static bool boot_completed(void)
{
#ifdef __ANDROID__
    char v[PROP_VALUE_MAX] = "";
    __system_property_get("sys.boot_completed", v);
    return v[0] == '1';
#else
    return true;
#endif
}

/* module.prop: debug=1 */
static bool prop_debug(void)
{
    char p[300], b[4096];
    snprintf(p, sizeof p, "%s/module.prop", g_moddir);
    if (u_read(p, b, sizeof b) < 0) return false;
    for (char *l = strtok(b, "\n"); l; l = strtok(NULL, "\n")) {
        u_trim(l);
        if (!strncmp(l, "debug=", 6)) return l[6] == '1';
    }
    return false;
}

/* Fresh private tmpfs for runtime state. Unmount leftovers first: removing a
 * live mountpoint silently leaves the mount in place. */
static void prepare_stub_dir(void)
{
    umount2(STUB_DIR, MNT_DETACH);
    rmdir(STUB_DIR);
    u_mkdir_p(STUB_DIR, 0755);
    if (mount("proc", STUB_DIR, "tmpfs", 0, "mode=0755,context=u:object_r:proc_net:s0") != 0 &&
        mount("proc", STUB_DIR, "tmpfs", 0, "mode=0755") != 0)
        fprintf(stderr, "warning: tmpfs mount failed, falling back to a plain directory\n");
    u_mkdir_p(RUN_DIR, 0700); u_mkdir_p(PROC_DIR, 0700); u_mkdir_p(LOG_DIR, 0700);
    chmod(RUN_DIR, 0700); chmod(PROC_DIR, 0700); chmod(LOG_DIR, 0700);
}

static void *boot_thread(void *arg)
{
    (void)arg;
    int waited = 0;
    while (!u_exists(RT_TABLES_PATH) && waited < 300) { sleep(1); waited++; }   /* netd publishes the table map */
    if (!u_exists(RT_TABLES_PATH)) log_msg("rt_tables never appeared after %ds; continuing anyway", waited);

    ensure_exclude_app_chain();
    release_traffic();                       /* clear a stale gate if the loop died mid hard_restart */
    control_submit("start_monitor");

    if (!boot_completed()) {                 /* started during boot: let the system settle */
        for (waited = 0; !boot_completed() && waited < 600; waited++) sleep(1);
        sleep(5);
    }                                        /* else: started live (dynamic upgrade), resume at once */

    if (!u_exists("/dev/net/tun")) {
        u_mkdir_p("/dev/net", 0755);
        mknod("/dev/net/tun", S_IFCHR | 0600, makedev(10, 200));
        chmod("/dev/net/tun", 0600);
    }

    /* Resume only if the user left the engine running. */
    struct stat st;
    if (stat(DATADIR "/" CONFIG_NAME, &st) == 0 && st.st_size > 0 && u_exists(ENABLED_FLAG)) {
        log_msg("restoring previous session");
        control_submit("start");
        control_submit("wait");
    }
    log_msg("boot sequence complete");
    return NULL;
}

/* ------------------------------------------------------------ stop_svc --- */

/* Detach every mount at or below STUB_DIR (proc tracking binds, the tmpfs itself) plus the
 * legacy ip_forward stub, deepest first. Lazy: our own open fds into it stay valid. */
static void umount_stub_tree(void)
{
    enum { MAXM = 256 };
    static char mp[MAXM][256];
    int n = 0;
    char *buf = malloc(1 << 18);
    if (buf) {
        int r = u_read("/proc/self/mountinfo", buf, (1 << 18) - 1);
        if (r > 0) {
            buf[r] = 0;
            size_t sl = strlen(STUB_DIR);
            for (char *line = strtok(buf, "\n"); line && n < MAXM; line = strtok(NULL, "\n")) {
                char *p = line; int f;
                for (f = 0; f < 4 && p; f++) { p = strchr(p, ' '); if (p) p++; }   /* 5th field = mount point */
                if (!p) continue;
                char *e = strchr(p, ' ');
                if (e) *e = 0;
                char path[256]; size_t o = 0;
                for (const char *q = p; *q && o < sizeof path - 1; q++) {        /* \040 style escapes */
                    if (q[0] == '\\' && q[1] >= '0' && q[1] <= '3' && q[2] && q[3]) {
                        path[o++] = (char)(((q[1] - '0') << 6) | ((q[2] - '0') << 3) | (q[3] - '0')); q += 3;
                    } else path[o++] = *q;
                }
                path[o] = 0;
                if (!strncmp(path, STUB_DIR, sl) && (path[sl] == 0 || path[sl] == '/'))
                    snprintf(mp[n++], sizeof mp[0], "%s", path);
                else if (!strcmp(path, "/proc/sys/net/ipv4/ip_forward"))         /* legacy gateway stub */
                    snprintf(mp[n++], sizeof mp[0], "%s", path);
            }
        }
        free(buf);
    }
    for (int i = n - 1; i >= 0; i--)
        if (umount2(mp[i], MNT_DETACH) != 0 && errno != EINVAL && errno != ENOENT)
            fprintf(stderr, "umount %s: %s\n", mp[i], strerror(errno));
    rmdir(STUB_DIR);
}

/* The subscription updater is a shell subshell started by service.sh, outside our process. */
static void kill_sub_loop(void)
{
    char b[32], c[64], cmd[512];
    if (u_read(SUB_LOOP_PID_FILE, b, sizeof b - 1) > 0) {
        pid_t p = (pid_t)atoi(b);
        snprintf(c, sizeof c, "/proc/%d/cmdline", (int)p);
        int n = p > 1 ? u_read(c, cmd, sizeof cmd - 1) : -1;
        if (n > 0) {
            cmd[n] = 0;
            for (int i = 0; i < n; i++) if (!cmd[i]) cmd[i] = ' ';
            if (strstr(cmd, "service.sh")) { kill(p, SIGKILL); log_msg("stop_svc: killed subscription loop pid %d", (int)p); }
        }
    }
    unlink(SUB_LOOP_PID_FILE);
}

/* Counterpart of the boot sequence. Order matters: monitors first (they re-apply rules and
 * take g_lock, so never join them while holding it), then the rules and xray, then the
 * log daemon, and only last the mounts that hold the runtime state. */
void service_shutdown(void)
{
    log_msg("stop_svc: shutting down (moddir=%s)", g_moddir);
    bool was_enabled = u_exists(ENABLED_FLAG);       /* stop_xray() clears it; keep the user's intent */

    iface_monitor_stop();
    latency_monitor_stop();
    apps_monitor_stop();

    pthread_mutex_lock(&g_lock);
    stop_xray();                                     /* kills xray, drops iptables chains, ip rules, ULA, tun */
    remove_mark_rule();                              /* fwmark rule is kept by stop_xray; not ours any more */
    release_traffic();                               /* a hard_restart gate must never outlive us */
    pthread_mutex_unlock(&g_lock);

    if (was_enabled) u_write(ENABLED_FLAG, "");      /* the next service resumes xray on its own */
    proc_untrack("xray");
    kill_sub_loop();

    log_msg("stop_svc: bye");
    xray_helper_logservice("stop", NULL, NULL);      /* log daemon is a separate process */
    umount_stub_tree();
}

/* `service --stop` (a separate short-lived process): ask the running service to stop through
 * its control pipe and wait until it is really gone. */
int service_stop_request(void)
{
    char b[32] = ""; pid_t svc = 0;
    if (u_read(CONTROL_LOOP_PID_FILE, b, sizeof b - 1) > 0) svc = (pid_t)atoi(b);
    if (svc == getpid()) svc = 0;

    int fd = open(PIPE_FILE, O_WRONLY | O_NONBLOCK | O_CLOEXEC);   /* ENXIO: nobody reads it */
    if (fd < 0) {
        int e = errno;
        if (e == ENOENT || e == ENXIO) {
            printf("service is not running\n");
            if (e == ENXIO) umount_stub_tree();                    /* stale leftovers of a dead service */
            return 0;
        }
        fprintf(stderr, "open %s: %s\n", PIPE_FILE, strerror(e));
        return 1;
    }
    const char *cmd = "stop_svc\n";
    if (write(fd, cmd, strlen(cmd)) != (ssize_t)strlen(cmd)) {
        fprintf(stderr, "write %s: %s\n", PIPE_FILE, strerror(errno));
        close(fd);
        return 1;
    }
    close(fd);

    if (svc > 1) {
        for (int i = 0; i < 300; i++) {                            /* up to 30 s */
            if (kill(svc, 0) < 0 && errno == ESRCH) { printf("service stopped\n"); return 0; }
            usleep(100 * 1000);
        }
        fprintf(stderr, "service pid %d did not exit, killing it\n", (int)svc);
        kill(svc, SIGKILL);
        for (int i = 0; i < 50 && !(kill(svc, 0) < 0 && errno == ESRCH); i++) usleep(100 * 1000);
        umount_stub_tree();
        return 1;
    }
    sleep(2);                                                      /* no pid file: best effort */
    printf("stop requested\n");
    return 0;
}

/* `service -p`: read-only diagnostic. Tries to load every xtables table the
 * service needs and prints the exact errno, plus who we are (uid, SELinux
 * context, effective caps). Run it the same way the service runs (su). */
static void run_probe(void)
{
    char b[4096]; int n;
    printf("uid=%d euid=%d\n", (int)getuid(), (int)geteuid());
    if ((n = u_read("/proc/self/attr/current", b, sizeof b - 1)) > 0) { b[n] = 0; u_trim(b); printf("selinux=%s\n", b); }
    if ((n = u_read("/proc/self/status", b, sizeof b - 1)) > 0) {
        b[n] = 0; char *p = strstr(b, "CapEff:"); if (p) { char *e = strchr(p, '\n'); if (e) *e = 0; printf("%s\n", p); }
    }
    static const char *T[] = { "raw", "mangle", "nat", "filter" };
    for (int f = 0; f < 2; f++)
        for (int i = 0; i < 4; i++) {
            int fam = f ? AF_INET6 : AF_INET;
            ipt_tx *t = ipt_tx_begin(fam, T[i]);
            if (t) { printf("%s %-6s ok\n", f ? "ip6tables" : "iptables ", T[i]); ipt_tx_abort(t); }
            else {
                int e = errno; char d[2048] = "";
                printf("%s %-6s FAIL: %s\n", f ? "ip6tables" : "iptables ", T[i], strerror(e));
                if (e == EPROTO) { ipt_diagnose(fam, T[i], d, sizeof d); printf("    -> %s\n", d); }
            }
        }
    printf("xtables lock %s: %s\n", MV_XTABLES_LOCK, u_exists(MV_XTABLES_LOCK) ? "present" : "absent");
}

static void usage(const char *a0) { fprintf(stderr, "usage: xhuskydg_helper %s [-m MODDIR] [-f] [-p] [--netinfo] [--stop]\n  -m  module dir (default: parent of the dir holding this binary)\n  -f  stay in foreground\n  --stop  stop the running service (xray, monitors, routing rules, mounts)\n  -p  probe xtables access and print diagnostics, then exit\n  --netinfo  print the interface fwmark 255 routes to, with its IPv4/IPv6 addresses, then exit\n", a0); }

int run_service(int argc, char **argv)
{
    bool fg = false, probe = false, netinfo = false, stop = false;
    ssize_t n = readlink("/proc/self/exe", g_self, sizeof g_self - 1);
    g_self[n > 0 ? n : 0] = 0;
    char tmp[256]; snprintf(tmp, sizeof tmp, "%s", g_self);
    char *dir = dirname(tmp);                        /* .../bin */
    snprintf(g_moddir, sizeof g_moddir, "%s", dir);
    size_t dl = strlen(g_moddir);
    if (dl >= 4 && !strcmp(g_moddir + dl - 4, "/bin")) g_moddir[dl - 4] = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-m") && i + 1 < argc) snprintf(g_moddir, sizeof g_moddir, "%s", argv[++i]);
        else if (!strcmp(argv[i], "-f")) fg = true;
        else if (!strcmp(argv[i], "-p")) probe = true;
        else if (!strcmp(argv[i], "--netinfo")) netinfo = true;
        else if (!strcmp(argv[i], "--stop")) stop = true;
        else { usage(argv[0]); return 2; }
    }
    if (probe) { run_probe(); return 0; }
    if (stop) {
        if (geteuid() != 0) { fprintf(stderr, "must run as root\n"); return 1; }
        return service_stop_request();
    }
    if (netinfo) {                                   /* stdout carries the answer only */
        int nul = open("/dev/null", O_WRONLY);
        if (nul >= 0) dup2(nul, 2);
        print_netinfo();
        return 0;
    }
    if (geteuid() != 0) { fprintf(stderr, "must run as root\n"); return 1; }

    if (!fg) {                                       /* late_start runners must not wait for us */
        pid_t p = fork();
        if (p < 0) return 1;
        if (p > 0) return 0;
        setsid();
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) { dup2(nul, 0); dup2(nul, 1); dup2(nul, 2); }
    }
    signal(SIGPIPE, SIG_IGN);

    u_mkdir_p(DATADIR, 0700);
    prepare_stub_dir();
    char *a[] = { g_self, "logservice", "create", "-c", LOG_CTRL, "-i", XRAY_LOG, "--daemonize", NULL };
    u_run(a);                                        /* create the log pipe first so we can write to it */

    g_debug = prop_debug();
    log_open();
    log_attach_pipe(XRAY_LOG);
    log_msg("=== magic_v2ray service starting (debug=%d, moddir=%s) ===", g_debug, g_moddir);

    pthread_t bt;
    pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    /* the boot thread submits into the FIFO, so create it first */
    if (control_run_prepare() != 0) return 1;
    pthread_create(&bt, &at, boot_thread, NULL);
    return control_run() == 0 ? 0 : 1;
}
