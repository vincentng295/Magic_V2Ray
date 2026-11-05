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

    for (waited = 0; !boot_completed() && waited < 600; waited++) sleep(1);
    sleep(5);

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

static void usage(const char *a0) { fprintf(stderr, "usage: xhuskydg_helper %s [-m MODDIR] [-f] [-p] [--netinfo]\n  -m  module dir (default: parent of the dir holding this binary)\n  -f  stay in foreground\n  -p  probe xtables access and print diagnostics, then exit\n  --netinfo  print the interface fwmark 255 routes to, with its IPv4/IPv6 addresses, then exit\n", a0); }

int run_service(int argc, char **argv)
{
    bool fg = false, probe = false, netinfo = false;
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
        else { usage(argv[0]); return 2; }
    }
    if (probe) { run_probe(); return 0; }
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
