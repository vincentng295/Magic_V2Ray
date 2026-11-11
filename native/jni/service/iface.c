/* Interface + policy-routing helpers and the route-event monitor
 * (service.sh sections 4 and 6: monitor_net_interfaces). */
#include "service.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <errno.h>
#include <linux/fib_rules.h>
#include <net/if.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static worker_t g_mon;

int read_table_index(const char *iface)
{
    FILE *f = fopen(RT_TABLES_PATH, "r");
    char name[128]; int idx, found = -1;
    if (!f || !iface || !*iface) { if (f) fclose(f); return -1; }
    while (fscanf(f, "%d %127s", &idx, name) == 2)
        if (!strcmp(name, iface)) { found = idx; break; }
    fclose(f);
    return found;
}

static bool table_name(int id, char *out, size_t cap)
{
    FILE *f = fopen(RT_TABLES_PATH, "r");
    char name[128]; int idx;
    if (f) {
        while (fscanf(f, "%d %127s", &idx, name) == 2)
            if (idx == id) { fclose(f); snprintf(out, cap, "%s", name); return true; }
        fclose(f);
    }
    const char *d = id == 254 ? "main" : id == 255 ? "local" : id == 253 ? "default" : NULL;
    if (!d) return false;
    snprintf(out, cap, "%s", d);
    return true;
}

/* log a failure reason only when it changes (called from timers and every network event) */
static char g_why[128];
static void why(const char *fmt, ...)
{
    char b[128]; va_list ap;
    va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    if (strcmp(b, g_why)) { snprintf(g_why, sizeof g_why, "%s", b); log_msg("%s", b); }
}

/* netd names each network's routing table after its interface (rt_tables) and numbers it
 * 1000 + ifindex. rt_tables can be missing or mid-rewrite while netd reconfigures, so fall
 * back to the numbering instead of giving up. */
static bool iface_for_table(int tbl, char *out, size_t cap)
{
    if (table_name(tbl, out, cap)) return true;
    char n[IF_NAMESIZE];
    if (tbl > 1000 && if_indextoname((unsigned)(tbl - 1000), n)) { snprintf(out, cap, "%s", n); return true; }
    return false;
}

/* routing table netd uses for an interface */
static int table_for_iface(const char *iface)
{
    int idx = read_table_index(iface);
    if (idx >= 0) return idx;
    unsigned ix = iface ? if_nametoindex(iface) : 0;
    return ix ? (int)(1000 + ix) : -1;
}

int get_active_interface(char *out, size_t cap)
{
    int tbl = nl_default_table();
    if (tbl < 0) { why("active interface: no default-network rule found"); return -1; }
    if (!iface_for_table(tbl, out, cap)) { why("active interface: routing table %d has no interface name", tbl); return -1; }
    g_why[0] = 0;
    return 0;
}

void remove_mark_rule(void)
{
    nl_rule r4 = NL_RULE(AF_INET), r6 = NL_RULE(AF_INET6);
    r4.has_mark = r6.has_mark = true; r4.mark = r6.mark = FWMARK;
    r4.pref = r6.pref = RULE_PRIORITY;
    nl_rule_del_all(&r4); nl_rule_del_all(&r6);
}

static bool mark_rule_present(int fam, int table)
{
    return nl_rule_exists(fam, RULE_PRIORITY, true, FWMARK, table);
}

int apply_mark_rule(const char *iface)
{
    if (!iface || !*iface) return -1;
    /* Never point the mark rule at our own TUN: that would loop xray's egress. */
    if (!strcmp(iface, TUN_NAME)) { log_msg("ignoring %s as active interface", TUN_NAME); return -1; }
    int idx = table_for_iface(iface);
    if (idx < 0) { log_msg("no routing table for interface %s; mark rule not applied", iface); return -1; }
    remove_mark_rule();
    nl_rule r4 = NL_RULE(AF_INET), r6 = NL_RULE(AF_INET6);
    r4.has_mark = r6.has_mark = true; r4.mark = r6.mark = FWMARK;
    r4.table = r6.table = idx; r4.pref = r6.pref = RULE_PRIORITY;
    int a4 = nl_rule_add(&r4), a6 = nl_rule_add(&r6);
    if (a4 < 0 && a4 != -EEXIST) log_msg("mark rule v4 (pref %d fwmark %d -> table %d) add failed: %s", RULE_PRIORITY, FWMARK, idx, strerror(-a4));
    if (a6 < 0 && a6 != -EEXIST) log_msg("mark rule v6 (pref %d fwmark %d -> table %d) add failed: %s", RULE_PRIORITY, FWMARK, idx, strerror(-a6));
    /* Trust the kernel, not the return code: only claim success when the rule is really there. */
    if (!mark_rule_present(AF_INET, idx)) {
        log_msg("fwmark %d -> table %d (%s): rule NOT present in kernel after add", FWMARK, idx, iface);
        return -1;
    }
    log_msg("applied fwmark %d -> table %d (%s)", FWMARK, idx, iface);
    return 0;
}

/* ---- upstream detection when netd has no default network (yet) -------------------------
 * Wi-Fi off -> mobile: netd drops the "fwmark 0x0/0xffff iif lo lookup <wlan>" default rule
 * at once, but the mobile network only becomes the default a while later (it is often still
 * unvalidated, and the validation probes themselves go through our tun -> xray -> mark 0xff).
 * With the default rule missing the mark rule used to stay on the dead Wi-Fi table, xray had
 * no route out, the system saw a data stall and reset the radio (data + calls gone until
 * airplane mode). So when there is no default rule, pick the best network netd has already
 * set up: one with an "implicit select" rule (pref 23000, i.e. a normal unrestricted network;
 * IMS/MMS networks have none) whose table really holds a default route. */
#define NETD_IMPLICIT_PREF 23000

static bool is_mobile_name(const char *n)
{
    return !strncmp(n, "rmnet", 5) || !strncmp(n, "ccmni", 5) || !strncmp(n, "pdp", 3) || !strncmp(n, "wwan", 4);
}

static bool table_usable(int tbl)
{
    return tbl > 0 && (nl_table_has_default(AF_INET, (uint32_t)tbl) || nl_table_has_default(AF_INET6, (uint32_t)tbl));
}

static int fallback_upstream(char *out, size_t cap)
{
    struct if_nameindex *ni = if_nameindex();
    char mobile[IF_NAMESIZE] = "", other[IF_NAMESIZE] = "";
    if (!ni) return -1;
    for (struct if_nameindex *i = ni; i->if_index && i->if_name; i++) {
        const char *n = i->if_name;
        if (!strcmp(n, "lo") || !strcmp(n, TUN_NAME) || !strncmp(n, "dummy", 5) || !strncmp(n, "tun", 3)) continue;
        int tbl = table_for_iface(n);
        if (tbl < 0 || !nl_rule_exists(AF_INET, NETD_IMPLICIT_PREF, false, 0, tbl)) continue;
        if (!table_usable(tbl)) continue;
        if (is_mobile_name(n)) { if (!mobile[0]) snprintf(mobile, sizeof mobile, "%s", n); }
        else if (!other[0]) snprintf(other, sizeof other, "%s", n);
    }
    if_freenameindex(ni);
    const char *pick = other[0] ? other : mobile;
    if (!pick[0]) return -1;
    snprintf(out, cap, "%s", pick);
    return 0;
}

/* default-network interface, else whatever usable network netd already has */
static int auto_upstream(char *out, size_t cap)
{
    if (get_active_interface(out, cap) == 0) return 0;
    if (fallback_upstream(out, cap) == 0) {
        char m[96]; snprintf(m, sizeof m, "no default network yet, using %s as upstream", out);
        why("%s", m);
        return 0;
    }
    return -1;
}

int get_upstream_interface(char *out, size_t cap);

/* Interface fwmark FWMARK is routed to: read from the kernel rule itself, so it is what
 * traffic really does. With no rule installed (service not running) fall back to the
 * interface the service would pin it to. */
int get_marked_interface(char *out, size_t cap)
{
    int tbl = nl_rule_table(AF_INET, RULE_PRIORITY, true, FWMARK);
    if (tbl >= 0 && iface_for_table(tbl, out, cap) && if_nametoindex(out)) return 0;
    if (get_upstream_interface(out, cap) == 0 && if_nametoindex(out)) return 0;
    out[0] = 0;
    return -1;
}

/* `xhuskydg_helper service --netinfo` */
void print_netinfo(void)
{
    char ifn[64] = "", v4[INET_ADDRSTRLEN] = "", v6[1024] = "";
    if (get_marked_interface(ifn, sizeof ifn) < 0) {
        printf("interface=none\nipv4=0.0.0.0\nipv6=::\n");
        return;
    }
    if (!nl_addr_first_v4(ifn, v4, sizeof v4)) snprintf(v4, sizeof v4, "0.0.0.0");
    if (nl_addr_list_v6(ifn, v6, sizeof v6) == 0) snprintf(v6, sizeof v6, "::");
    printf("interface=%s\nipv4=%s\nipv6=%s\n", ifn, v4, v6);
}

/* "upstreamIface": auto/absent follows the default network; a name pins it,
 * falling back to auto when that interface has no table right now. */
static char g_upstream_warned[64];
int get_upstream_interface(char *out, size_t cap)
{
    char want[64];
    settings_query("upstreamIface", want, sizeof want);
    if (!want[0] || !strcmp(want, "auto")) { g_upstream_warned[0] = 0; return auto_upstream(out, cap); }
    for (const char *p = want; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9') ||
              *p == '_' || *p == '.' || *p == '-')) {
            if (strcmp(g_upstream_warned, want)) log_msg("upstream interface: invalid name, using auto");
            snprintf(g_upstream_warned, sizeof g_upstream_warned, "%s", want);
            return auto_upstream(out, cap);
        }
    char p[160]; snprintf(p, sizeof p, "/sys/class/net/%s", want);
    if (strcmp(want, TUN_NAME) && access(p, F_OK) == 0 && table_for_iface(want) >= 0) {
        g_upstream_warned[0] = 0;
        snprintf(out, cap, "%s", want);
        return 0;
    }
    if (strcmp(g_upstream_warned, want)) log_msg("upstream interface %s unavailable, falling back to auto", want);
    snprintf(g_upstream_warned, sizeof g_upstream_warned, "%s", want);
    return auto_upstream(out, cap);
}

int refresh_mark_rule(void)
{
    char up[64];
    if (get_upstream_interface(up, sizeof up) == 0) return apply_mark_rule(up);
    log_msg("no upstream interface to apply");
    return -1;
}

/* ---------------------------------------------------------------- monitor */

static void write_addr_info(const char *iface)
{
    char *t = nl_addr_show(iface);
    if (t) { u_write(ADDR_INFO_FILE, t); free(t); }
}

/* set while the upstream is unknown or its mark rule could not be applied: re-check quickly */
static bool g_retry_fast;

static void on_event(char *cur_act, char *cur_up, size_t cap)
{
    g_retry_fast = false;
    char new_act[64] = "", new_up[64] = "";
    if (get_active_interface(new_act, sizeof new_act) < 0) new_act[0] = 0;
    if (get_upstream_interface(new_up, sizeof new_up) < 0) new_up[0] = 0;

    if (!strcmp(new_act, cur_act) && !strcmp(new_up, cur_up)) {
        /* netd/IpClient may have flushed addresses during a reconnect */
        ula_apply(cur_act);
        /* the monitor also wakes on rule events: if something removed our mark rule, put it back */
        int idx = cur_up[0] ? table_for_iface(cur_up) : -1;
        if (idx >= 0 && !mark_rule_present(AF_INET, idx)) {
            log_msg("fwmark %d rule vanished, re-applying for %s", FWMARK, cur_up);
            apply_mark_rule(cur_up);
        }
        return;
    }
    if (!new_act[0] && !new_up[0]) {
        log_msg("network interface disconnected");
        cur_act[0] = cur_up[0] = 0;
        g_retry_fast = true;
        unlink(ADDR_INFO_FILE);
        return;
    }
    if (new_up[0] && strcmp(new_up, cur_up)) {
        log_msg("upstream interface changed: %s -> %s", cur_up[0] ? cur_up : "none", new_up);
        if (apply_mark_rule(new_up) == 0) snprintf(cur_up, cap, "%s", new_up);
        else g_retry_fast = true;                    /* table not published yet: try again in 2 s */
    }
    if (strcmp(new_act, cur_act)) {
        log_msg("network interface changed: %s -> %s", cur_act[0] ? cur_act : "none", new_act[0] ? new_act : "none");
        snprintf(cur_act, cap, "%s", new_act);
        ip_hunt_reset();
        if (new_act[0]) { ula_apply(new_act); write_addr_info(new_act); check_ip_hunter(new_act); }
        else unlink(ADDR_INFO_FILE);
    } else ula_apply(cur_act);
}

static int drain(int fd)
{
    char buf[8192]; int n = 0;
    while (recv(fd, buf, sizeof buf, MSG_DONTWAIT) > 0) n++;
    return n;
}

static void monitor_main(int stop_fd)
{
    int nl = nl_monitor_open();
    char cur_act[64] = "", cur_up[64] = "";
    if (nl < 0) { log_msg("interface monitor: netlink open failed (%s)", strerror(-nl)); return; }

    pthread_mutex_lock(&g_lock);
    if (get_active_interface(cur_act, sizeof cur_act) < 0) cur_act[0] = 0;
    if (get_upstream_interface(cur_up, sizeof cur_up) < 0) cur_up[0] = 0;
    if (cur_act[0] || cur_up[0]) {
        log_msg("initial active interface: %s, upstream: %s", cur_act[0] ? cur_act : "none", cur_up[0] ? cur_up : "none");
        if (cur_up[0] && apply_mark_rule(cur_up) != 0) cur_up[0] = 0;
        ula_apply(cur_act);
    } else log_msg("no active interface at startup");
    pthread_mutex_unlock(&g_lock);

    for (;;) {
        struct pollfd p[2] = { { nl, POLLIN, 0 }, { stop_fd, POLLIN, 0 } };
        /* nothing applied yet: re-check every 2 s; otherwise every 10 s as a safety net for missed events */
        int pr = poll(p, 2, (cur_up[0] && !g_retry_fast) ? 10000 : 2000);
        if (pr < 0 && errno != EINTR) break;
        if (p[1].revents) break;
        if (pr == 0) {
            pthread_mutex_lock(&g_lock);
            on_event(cur_act, cur_up, sizeof cur_act);
            pthread_mutex_unlock(&g_lock);
            continue;
        }
        if (!(p[0].revents & POLLIN)) continue;
        drain(nl);
        /* Debounce: a handover emits dozens of messages; act once it goes quiet. */
        for (int absorbed = 0; absorbed < 200; absorbed++) {
            struct pollfd q[2] = { { nl, POLLIN, 0 }, { stop_fd, POLLIN, 0 } };
            int r = poll(q, 2, 1000);
            if (r <= 0 || q[1].revents) break;
            drain(nl);
        }
        if (worker_wait(stop_fd, 0)) break;
        pthread_mutex_lock(&g_lock);
        on_event(cur_act, cur_up, sizeof cur_act);
        pthread_mutex_unlock(&g_lock);
    }
    close(nl);
    log_msg("interface monitor exiting");
}

void iface_monitor_start(void)
{
    worker_stop(&g_mon);
    if (worker_start(&g_mon, "monitor_net_interfaces", monitor_main) == 0)
        log_msg("interface monitor running");
}
void iface_monitor_stop(void) { worker_stop(&g_mon); log_msg("interface monitor stopped"); }
