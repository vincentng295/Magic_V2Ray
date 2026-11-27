/* Exclude Apps (service.sh section 6): one `-m owner --uid-owner <uid> -j ACCEPT`
 * rule per excluded uid in mangle/XRAY_EXCLUDE_APP, kept current by inotify. */
#include "service.h"
#include "ipt.h"
#include <dirent.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHAIN "XRAY_EXCLUDE_APP"
/* filter/XRAY_EXCLUDE_TUN, hooked from filter/OUTPUT with `-o xraytun0`: one `-m owner --uid-owner <uid> -j DROP`
 * per excluded uid. The mangle ACCEPT above only keeps an excluded app from being *marked* into the tun; a socket
 * it binds to the tun itself (SO_BINDTODEVICE / `curl --interface xraytun0`, no CAP_NET_RAW needed on kernel >= 5.7)
 * never consults the fwmark rule: the kernel treats the destination as on-link and sends straight into xraytun0,
 * i.e. into the proxy, leaking the server exit IP to an app the user excluded. Nothing legitimate leaves xraytun0
 * from an excluded uid, so refuse it. */
#define TUN_CHAIN "XRAY_EXCLUDE_TUN"
typedef struct { ipt_tx *m4, *m6, *f4, *f6; } xtx;   /* mangle + filter transactions, v4 and v6 */
static worker_t g_mon;
static pthread_mutex_t g_ex = PTHREAD_MUTEX_INITIALIZER;

static bool valid_pkg(const char *s)
{
    if (!*s || s[0] == '.' || strstr(s, "..")) return false;
    for (; *s; s++)
        if (!((*s >= 'a' && *s <= 'z') || (*s >= 'A' && *s <= 'Z') || (*s >= '0' && *s <= '9') || *s == '_' || *s == '.'))
            return false;
    return true;
}

/* The Magic V2Ray app itself is always bypassed while Exclude apps is on, whether
 * or not the user listed it: its own traffic (UI, update checks, helper calls)
 * must never be looped through the tunnel it controls. */
#define SELF_PKG "com.huskydg.magicv2ray"

/* Appends the entries of one list file: comments, CRs and blank lines removed. */
static void read_file(const char *path, char ***v, int *n, bool *self)
{
    char *raw = malloc(1 << 20);
    if (raw && u_read(path, raw, 1 << 20) >= 0) {
        char *save = NULL;
        for (char *l = strtok_r(raw, "\n", &save); l; l = strtok_r(NULL, "\n", &save)) {
            char *h = strchr(l, '#'); if (h) *h = 0;
            u_trim(l);
            if (!*l) continue;
            if (!strcmp(l, SELF_PKG)) *self = true;
            *v = realloc(*v, (*n + 1) * sizeof **v);
            (*v)[(*n)++] = strdup(l);
        }
    }
    free(raw);
}

/* The user's list (only while the switch is on, see sync_exclude_apps) plus the session list; SELF_PKG
 * always present. Caller frees. */
static char **read_list(int *n, bool with_user_list)
{
    char **v = NULL; *n = 0;
    bool self = false;
    if (with_user_list) {
        char path[300];
        read_file(u_ov_path(path, sizeof path, "excludelist.txt", EXCLUDE_LIST_FILE), &v, n, &self);
    }
    read_file(EXCLUDE_SESSION_FILE, &v, n, &self);
    if (!self) {
        v = realloc(v, (*n + 1) * sizeof *v);
        v[(*n)++] = strdup(SELF_PKG);
    }
    return v;
}
static void free_list(char **v, int n) { for (int i = 0; i < n; i++) free(v[i]); free(v); }

/* Exclusion is on when the user switched it on, or while an external session has apps to keep out of the tunnel. */
static bool session_has_apps(void)
{
    char buf[4096];
    ssize_t r = u_read(EXCLUDE_SESSION_FILE, buf, sizeof buf - 1);
    if (r <= 0) return false;
    buf[r] = 0;
    for (char *p = buf; *p; p++)
        if (*p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') return true;
    return false;
}
static bool exclude_on(void) { return settings_is_true("excludeApps") || session_has_apps(); }

static bool list_has(const char *pkg)
{
    int n; char **v = read_list(&n, settings_is_true("excludeApps")); bool r = false;
    for (int i = 0; i < n; i++) if (!strcmp(v[i], pkg)) { r = true; break; }
    free_list(v, n);
    return r;
}

static long app_uid(const char *pkg, int tries)
{
    char p[300]; struct stat st;
    snprintf(p, sizeof p, APP_DATA_DIR "/%s", pkg);
    for (int n = 0;;) {
        /* installd creates the dir first and chowns it a moment later */
        if (stat(p, &st) == 0 && st.st_uid >= 1000) return st.st_uid;
        if (++n >= tries) return -1;
        u_msleep(200);
    }
}

long self_app_uid(int tries) { return app_uid(SELF_PKG, tries); }

static long record_get(const char *pkg)
{
    char p[300], b[32];
    snprintf(p, sizeof p, EXCLUDE_MAP_DIR "/%s", pkg);
    if (u_read(p, b, sizeof b) <= 0) return -1;
    return atol(b);
}
static void record_set(const char *pkg, long uid)
{
    char p[300]; snprintf(p, sizeof p, EXCLUDE_MAP_DIR "/%s", pkg);
    if (uid < 0) unlink(p); else u_writef(p, "%ld\n", uid);
}
static bool held_by_other(const char *pkg, long uid)
{
    DIR *d = opendir(EXCLUDE_MAP_DIR); struct dirent *e; bool r = false;
    if (!d) return false;
    while ((e = readdir(d)))
        if (e->d_name[0] != '.' && strcmp(e->d_name, pkg) && record_get(e->d_name) == uid) { r = true; break; }
    closedir(d);
    return r;
}

/* x NULL => open, edit and commit our own transactions. */
static void uid_rule(long uid, bool add, xtx *x)
{
    ipt_spec acc = IPT_SPEC(.owner = true, .uid_lo = uid, .uid_hi = uid, .target = MVT_ACCEPT);
    ipt_spec blk = IPT_SPEC(.owner = true, .uid_lo = uid, .uid_hi = uid, .target = MVT_DROP);
    for (int i = 0; i < 2; i++) {
        int fam = i ? AF_INET6 : AF_INET;
        ipt_tx *m = x ? (i ? x->m6 : x->m4) : ipt_tx_begin(fam, "mangle");
        ipt_tx *f = x ? (i ? x->f6 : x->f4) : ipt_tx_begin(fam, "filter");
        if (m) { if (add) ipt_append(m, CHAIN, &acc); else ipt_delete(m, CHAIN, &acc); }
        if (f) { if (add) ipt_append(f, TUN_CHAIN, &blk); else ipt_delete(f, TUN_CHAIN, &blk); }
        if (!x) { if (m) ipt_tx_commit(m); if (f) ipt_tx_commit(f); }
    }
}

static int app_add(const char *pkg, int tries, xtx *x)
{
    long uid = app_uid(pkg, tries);
    if (!valid_pkg(pkg) || uid < 0) return -1;
    long old = record_get(pkg);
    if (old == uid) return 0;
    if (old >= 0) {                    /* came back under a different uid unnoticed */
        record_set(pkg, -1);
        if (!held_by_other(pkg, old)) uid_rule(old, false, x);
    }
    record_set(pkg, uid);
    if (!held_by_other(pkg, uid)) uid_rule(uid, true, x);   /* shared uid already has its rule */
    log_msg("exclude apps: %s (uid %ld) excluded", pkg, uid);
    return 0;
}

static int app_remove(const char *pkg, xtx *x)
{
    if (!valid_pkg(pkg)) return -1;
    long uid = record_get(pkg);
    if (uid < 0) return 0;
    record_set(pkg, -1);
    if (!held_by_other(pkg, uid)) uid_rule(uid, false, x);
    log_msg("exclude apps: %s (uid %ld) no longer excluded", pkg, uid);
    return 0;
}

static void reconcile(xtx *x)
{
    int n; char **v = read_list(&n, settings_is_true("excludeApps"));
    for (int i = 0; i < n; i++) {
        char p[300]; struct stat st;
        if (!valid_pkg(v[i])) continue;
        snprintf(p, sizeof p, APP_DATA_DIR "/%s", v[i]);
        if (stat(p, &st) == 0 && S_ISDIR(st.st_mode)) app_add(v[i], 1, x);
        else app_remove(v[i], x);
    }
    free_list(v, n);
}

void ensure_exclude_app_chain(void)
{
    for (int i = 0; i < 2; i++) {
        ipt_tx *t = ipt_tx_begin(i ? AF_INET6 : AF_INET, "mangle");
        if (t) { ipt_new_chain(t, CHAIN); ipt_tx_commit(t); }
        t = ipt_tx_begin(i ? AF_INET6 : AF_INET, "filter");
        if (t) { ipt_new_chain(t, TUN_CHAIN); ipt_tx_commit(t); }
    }
}

/* Full reset + rebuild, called BEFORE XRAY_MARK is attached to OUTPUT so
 * excluded apps are never briefly proxied. */
void sync_exclude_apps(void)
{
    bool on = exclude_on();
    pthread_mutex_lock(&g_ex);
    xtx x = { ipt_tx_begin(AF_INET, "mangle"), ipt_tx_begin(AF_INET6, "mangle"),
              ipt_tx_begin(AF_INET, "filter"),  ipt_tx_begin(AF_INET6, "filter") };
    ipt_tx *ts[4] = { x.m4, x.m6, x.f4, x.f6 };
    const char *cn[4] = { CHAIN, CHAIN, TUN_CHAIN, TUN_CHAIN };
    for (int i = 0; i < 4; i++) if (ts[i]) { ipt_new_chain(ts[i], cn[i]); ipt_flush_chain(ts[i], cn[i]); }
    u_clear_dir(EXCLUDE_MAP_DIR);
    u_mkdir_p(EXCLUDE_MAP_DIR, 0700);
    if (!on) log_msg("exclude apps: disabled");
    else reconcile(&x);            /* a missing family (e.g. no ip6tables) is simply skipped */
    for (int i = 0; i < 4; i++) if (ts[i]) ipt_tx_commit(ts[i]);
    if (on) {
        int cnt = 0; DIR *d = opendir(EXCLUDE_MAP_DIR); struct dirent *e;
        if (d) { while ((e = readdir(d))) if (e->d_name[0] != '.') cnt++; closedir(d); }
        log_msg("exclude apps: %d app(s) in " CHAIN, cnt);
    }
    pthread_mutex_unlock(&g_ex);
}

static void monitor_main(int stop_fd)
{
    int in = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (in < 0 || inotify_add_watch(in, APP_DATA_DIR, IN_CREATE | IN_DELETE) < 0) {
        log_msg("apps monitor: cannot watch %s (%s)", APP_DATA_DIR, strerror(errno));
        if (in >= 0) close(in);
        return;
    }
    if (worker_wait(stop_fd, 300)) { close(in); return; }
    if (exclude_on()) { pthread_mutex_lock(&g_ex); reconcile(NULL); pthread_mutex_unlock(&g_ex); }  /* anything missed meanwhile */
    proxy_allow_self(false);           /* the app's own uid may have changed (reinstall) while we were not watching */
    log_msg("apps monitor: watching %s", APP_DATA_DIR);
    for (;;) {
        struct pollfd p[2] = { { in, POLLIN, 0 }, { stop_fd, POLLIN, 0 } };
        if (poll(p, 2, -1) < 0 && errno != EINTR) break;
        if (p[1].revents) break;
        char buf[4096] __attribute__((aligned(8)));
        ssize_t n = read(in, buf, sizeof buf);
        for (char *q = buf; n > 0 && q < buf + n;) {
            struct inotify_event *ev = (struct inotify_event *)q;
            q += sizeof *ev + ev->len;
            if (!ev->len || !valid_pkg(ev->name)) continue;
            /* always: the app's own uid decides who may reach the proxy, exclusion or not */
            if (!strcmp(ev->name, SELF_PKG)) proxy_allow_self((ev->mask & IN_DELETE) != 0);
            pthread_mutex_lock(&g_ex);
            if (ev->mask & IN_CREATE) {
                /* the exclusion rules only exist while Exclude apps is on (read_list always adds SELF_PKG) */
                if (exclude_on() && list_has(ev->name) && app_add(ev->name, 15, NULL) < 0)
                    log_msg("apps monitor: could not read uid of %s", ev->name);
            } else if (ev->mask & IN_DELETE) app_remove(ev->name, NULL);
            pthread_mutex_unlock(&g_ex);
        }
    }
    close(in);
    log_msg("apps monitor exiting");
}

void apps_monitor_stop(void) { worker_stop(&g_mon); }

/* Always runs while the engine does: besides keeping the Exclude apps rules current (only when that is on) it
 * has to notice the Magic V2Ray app's uid, which gets access to the proxy inbound. */
void apps_monitor_start(void)
{
    apps_monitor_stop();
    if (worker_start(&g_mon, "monitor_apps", monitor_main) == 0) log_msg("apps monitor running");
}
