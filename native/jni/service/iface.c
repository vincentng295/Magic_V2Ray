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

/* netd names each network's routing table after its interface. */
int get_active_interface(char *out, size_t cap)
{
    int tbl = nl_default_table();
    if (tbl < 0 || !table_name(tbl, out, cap)) return -1;
    return 0;
}

static void remove_mark_rule(void)
{
    nl_rule r4 = NL_RULE(AF_INET), r6 = NL_RULE(AF_INET6);
    r4.has_mark = r6.has_mark = true; r4.mark = r6.mark = FWMARK;
    r4.pref = r6.pref = RULE_PRIORITY;
    nl_rule_del_all(&r4); nl_rule_del_all(&r6);
}

int apply_mark_rule(const char *iface)
{
    if (!iface || !*iface) return -1;
    /* Never point the mark rule at our own TUN: that would loop xray's egress. */
    if (!strcmp(iface, TUN_NAME)) { log_msg("ignoring %s as active interface", TUN_NAME); return -1; }
    int idx = read_table_index(iface);
    if (idx < 0) { log_msg("no routing table for interface %s; mark rule not applied", iface); return -1; }
    remove_mark_rule();
    nl_rule r4 = NL_RULE(AF_INET), r6 = NL_RULE(AF_INET6);
    r4.has_mark = r6.has_mark = true; r4.mark = r6.mark = FWMARK;
    r4.table = r6.table = idx; r4.pref = r6.pref = RULE_PRIORITY;
    LOGD("mark rule v4: %d, v6: %d", nl_rule_add(&r4), nl_rule_add(&r6));
    log_msg("applied fwmark %d -> table %d (%s)", FWMARK, idx, iface);
    return 0;
}

/* "upstreamIface": auto/absent follows the default network; a name pins it,
 * falling back to auto when that interface has no table right now. */
static char g_upstream_warned[64];
int get_upstream_interface(char *out, size_t cap)
{
    char want[64];
    settings_query("upstreamIface", want, sizeof want);
    if (!want[0] || !strcmp(want, "auto")) { g_upstream_warned[0] = 0; return get_active_interface(out, cap); }
    for (const char *p = want; *p; p++)
        if (!((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9') ||
              *p == '_' || *p == '.' || *p == '-')) {
            if (strcmp(g_upstream_warned, want)) log_msg("upstream interface: invalid name, using auto");
            snprintf(g_upstream_warned, sizeof g_upstream_warned, "%s", want);
            return get_active_interface(out, cap);
        }
    char p[160]; snprintf(p, sizeof p, "/sys/class/net/%s", want);
    if (strcmp(want, TUN_NAME) && access(p, F_OK) == 0 && read_table_index(want) >= 0) {
        g_upstream_warned[0] = 0;
        snprintf(out, cap, "%s", want);
        return 0;
    }
    if (strcmp(g_upstream_warned, want)) log_msg("upstream interface %s unavailable, falling back to auto", want);
    snprintf(g_upstream_warned, sizeof g_upstream_warned, "%s", want);
    return get_active_interface(out, cap);
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

static void on_event(char *cur_act, char *cur_up, size_t cap)
{
    char new_act[64] = "", new_up[64] = "";
    if (get_active_interface(new_act, sizeof new_act) < 0) new_act[0] = 0;
    if (get_upstream_interface(new_up, sizeof new_up) < 0) new_up[0] = 0;

    if (!strcmp(new_act, cur_act) && !strcmp(new_up, cur_up)) {
        /* netd/IpClient may have flushed addresses during a reconnect */
        ula_apply(cur_act);
        return;
    }
    if (!new_act[0] && !new_up[0]) {
        log_msg("network interface disconnected");
        cur_act[0] = cur_up[0] = 0;
        unlink(ADDR_INFO_FILE);
        return;
    }
    if (new_up[0] && strcmp(new_up, cur_up)) {
        log_msg("upstream interface changed: %s -> %s", cur_up[0] ? cur_up : "none", new_up);
        if (apply_mark_rule(new_up) == 0) snprintf(cur_up, cap, "%s", new_up);
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
        if (poll(p, 2, -1) < 0 && errno != EINTR) break;
        if (p[1].revents) break;
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
