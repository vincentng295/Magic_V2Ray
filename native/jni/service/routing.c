/* Routing rules (service.sh section 7): policy routing via netlink, firewall
 * via the native xtables engine. Semantics mirror the shell script rule for
 * rule; the only structural change is that every table's edits are committed
 * in ONE kernel call instead of one fork+exec per rule. */
#include "service.h"
#include "ipt.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <linux/fib_rules.h>
#include <net/if.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define SPEC IPT_SPEC
#define NOP(x) ((void)(x))

/* ------------------------------------------------------------------ data */

/* Single source of truth for "LAN bypass" (see the long comment in service.sh). */
static const char *LAN_V4[] = { "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
    "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
    "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32", NULL };
static const char *LAN_V6[] = { "::1/128", "::ffff:0:0/96", "64:ff9b::/96", "100::/64", "2001::/32",
    "2001:10::/28", "2001:20::/28", "2001:db8::/32", "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8", NULL };
#define ALWAYS_V4 "127.0.0.0/8"      /* xray's own inbounds live here: never removable */
#define ALWAYS_V6 "::1/128"

static const struct { uint32_t lo, hi; } UID_LIST[] = { { 0, 2147483647u } };
static const uint32_t UID_EXCLUDE[] = { 1001 };
static const char *MOBILE_IF[] = { "rmnet+", "ccmni+", "pdp+", "wwan+" };

typedef struct {
    bool ipv6, tether;
    int mode;                               /* networkMode 0/1/2 */
    const char *skip4[32], *skip6[32];      /* LAN entries that stay bypassed */
    int n4, n6;
    char bypass[8][IFNAMSIZ]; int nb;
} cfg_t;

static bool in_words(const char *words, const char *w)
{
    size_t l = strlen(w);
    for (const char *p = words; (p = strstr(p, w)); p += l)
        if ((p == words || p[-1] == ' ') && (p[l] == 0 || p[l] == ' ')) return true;
    return false;
}

/* lan_skip_filter: everything except the UNticked entries; <always> stays. */
static int lan_skip(const char **all, const char *unticked, const char *always, const char **out)
{
    int n = 0;
    for (; *all; all++)
        if (!in_words(unticked, *all) || !strcmp(*all, always)) out[n++] = *all;
    return n;
}

static void load_cfg(cfg_t *c)
{
    char v[1024];
    memset(c, 0, sizeof *c);
    c->ipv6 = settings_is_true("enableIPv6");
    log_msg("IPv6 enabled: %s", c->ipv6 ? "true" : "false");
    settings_query("networkMode", v, sizeof v);
    c->mode = v[0] ? atoi(v) : 0;
    log_msg("Network mode: %d", c->mode);
    c->tether = settings_is_true("allowTether");
    log_msg("Allow tether from proxy: %s", c->tether ? "true" : "false");

    /* lanProxyCidrs = the UNticked CIDRs. Legacy: includeLan=true meant "only loopback skipped". */
    char unticked[1024] = "";
    if (settings_present("lanProxyCidrs")) settings_query("lanProxyCidrs", unticked, sizeof unticked);
    else if (settings_is_true("includeLan")) {
        for (const char **p = LAN_V4; *p; p++) { strncat(unticked, *p, sizeof unticked - strlen(unticked) - 2); strcat(unticked, " "); }
        for (const char **p = LAN_V6; *p; p++) { strncat(unticked, *p, sizeof unticked - strlen(unticked) - 2); strcat(unticked, " "); }
    }
    c->n4 = lan_skip(LAN_V4, unticked, ALWAYS_V4, c->skip4);
    c->n6 = lan_skip(LAN_V6, unticked, ALWAYS_V6, c->skip6);
    log_msg("Bypass LAN IPv4: %d entries, IPv6: %d entries", c->n4, c->n6);

    /* bypassIface: file exists => enabled; content = comma-separated names */
    if (u_exists(BYPASS_IFACE_FILE)) {
        char raw[256]; u_read(BYPASS_IFACE_FILE, raw, sizeof raw); u_trim(raw);
        if (!raw[0]) snprintf(raw, sizeof raw, "tailscale0,wt0");
        char *save = NULL;
        for (char *t = strtok_r(raw, ", \t\r\n", &save); t && c->nb < 8; t = strtok_r(NULL, ", \t\r\n", &save))
            if (strlen(t) < IFNAMSIZ) snprintf(c->bypass[c->nb++], IFNAMSIZ, "%s", t);
    }
    log_msg("Bypass interfaces: %d", c->nb);
}

/* ---------------------------------------------------------- small helpers */

static void sysctl_w(const char *path, const char *v) { u_write(path, v); }

static void enable_forward(bool ipv6)
{
    sysctl_w("/proc/sys/net/ipv4/ip_forward", "1");
    sysctl_w("/proc/sys/net/ipv6/conf/all/forwarding", ipv6 ? "1" : "0");
    sysctl_w("/proc/sys/net/ipv6/conf/default/forwarding", ipv6 ? "1" : "0");
}

/* "loose" rp_filter everywhere except lo/tun: fixes Samsung hotspot breakage. */
static void loosen_rp_filter(void)
{
    DIR *d = opendir("/proc/sys/net/ipv4/conf"); struct dirent *e; char p[300];
    sysctl_w("/proc/sys/net/ipv4/conf/all/rp_filter", "2");
    sysctl_w("/proc/sys/net/ipv4/conf/default/rp_filter", "2");
    if (!d) return;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.' || !strcmp(e->d_name, "lo") || !strcmp(e->d_name, TUN_NAME)) continue;
        snprintf(p, sizeof p, "/proc/sys/net/ipv4/conf/%s/rp_filter", e->d_name);
        sysctl_w(p, "2");
    }
    closedir(d);
}

/* Keeps table 100 non-empty so marked packets are dropped, not leaked, while
 * the tun is gone (reload, crash). */
void install_tun_blackhole(void)
{
    NOP(nl_route_blackhole(AF_INET, 100, 4000));
    NOP(nl_route_blackhole(AF_INET6, 100, 4000));
}

int configure_tun_iface(void)
{
    int retry;
    for (retry = 0; retry < 20; retry++) {
        if (if_nametoindex(TUN_NAME)) break;
        u_msleep(500);
    }
    if (retry == 20) { log_msg("error: %s did not appear after 20 retries, abort", TUN_NAME); return -1; }
    bool v6 = settings_is_true("enableIPv6");

    if (access("/proc/sys/net/ipv4/conf/" TUN_NAME "/rp_filter", F_OK) == 0)
        sysctl_w("/proc/sys/net/ipv4/conf/" TUN_NAME "/rp_filter", "0");
    /* openxtun only does TUNSETIFF: the MTU must be set here */
    NOP(link_set_mtu(TUN_NAME, 8500));
    struct in_addr a4; inet_pton(AF_INET, "198.18.0.1", &a4);
    NOP(nl_addr_add(AF_INET, TUN_NAME, &a4, 16, 0, false)); // 198.18.0.1/16
    NOP(link_set_up(TUN_NAME, true));
    NOP(nl_route_replace_dev(AF_INET, 100, TUN_NAME, NULL, 0));
    if (v6) {
        struct in6_addr a, b;
        inet_pton(AF_INET6, "fdfe:dcba:9876::1", &a);
        inet_pton(AF_INET6, "2001:db8:7872:6179::1", &b);        /* VirtualAP IPv6 routed mode */
        NOP(nl_addr_add(AF_INET6, TUN_NAME, &a, 64, 0, false)); // fdfe:dcba:9876::1/64
        NOP(nl_addr_add(AF_INET6, TUN_NAME, &b, 64, 0, false)); // 2001:db8:7872:6179::1/64
        NOP(nl_route_replace_dev(AF_INET6, 100, TUN_NAME, NULL, 0));
    }
    return 0;
}

static void ip_rule(int fam, uint8_t action, uint32_t pref, int table, const char *iif,
                    const char *src, const char *dst, int suppress, uint32_t gt, bool mark, uint32_t markv)
{
    nl_rule r = NL_RULE(fam);
    uint8_t sb[16], db[16];
    r.action = action; r.pref = pref; r.table = table; r.iif = iif; r.suppress_plen = suppress;
    r.goto_pref = gt; r.has_mark = mark; r.mark = markv;
    char tmp[64];
    for (int i = 0; i < 2; i++) {
        const char *s = i ? dst : src;
        if (!s) continue;
        snprintf(tmp, sizeof tmp, "%s", s);
        char *sl = strchr(tmp, '/'); int len = fam == AF_INET ? 32 : 128;
        if (sl) { *sl = 0; len = atoi(sl + 1); }
        if (inet_pton(fam, tmp, i ? db : sb) != 1) return;
        if (i) { r.dst = db; r.dst_len = len; } else { r.src = sb; r.src_len = len; }
    }
    int rc = nl_rule_add(&r);
    if (rc < 0 && rc != -EEXIST) LOGD("ip rule add pref %u failed: %s", pref, strerror(-rc));
}
#define RULE_TABLE(f, pref, tbl, ...) ip_rule(f, 0, pref, tbl, __VA_ARGS__)

static void rule_del(int fam, uint32_t pref, bool all)
{
    nl_rule r = NL_RULE(fam); r.pref = pref;
    if (all) nl_rule_del_all(&r); else NOP(nl_rule_del(&r));
}

/* mirrors forward(): ACCEPT anything entering/leaving the tun */
static void forward_rules(ipt_tx *t, bool add)
{
    ipt_spec in = SPEC(.in_if = TUN_NAME, .target = MVT_ACCEPT), out = SPEC(.out_if = TUN_NAME, .target = MVT_ACCEPT);
    if (add) { NOP(ipt_insert(t, "FORWARD", 1, &in)); NOP(ipt_insert(t, "FORWARD", 1, &out)); }
    else { NOP(ipt_delete(t, "FORWARD", &in)); NOP(ipt_delete(t, "FORWARD", &out)); }
}

/* ---------------------------------------------------------- XRAY_MARK build */

static void mark_uid(ipt_tx *t, const char *oif)
{
    for (size_t i = 0; i < sizeof UID_LIST / sizeof *UID_LIST; i++)
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.out_if = oif, .owner = true, .uid_lo = UID_LIST[i].lo,
                                             .uid_hi = UID_LIST[i].hi, .target = MVT_MARK, .set_mark = 1)));
}

/* Proxying chain, shared by IPv4 and IPv6-enabled (the shell duplicated it). */
static void build_xray_mark(ipt_tx *t, bool v6, const cfg_t *c)
{
    const char *always = v6 ? ALWAYS_V6 : ALWAYS_V4;
    const char *const *skip = v6 ? c->skip6 : c->skip4; int ns = v6 ? c->n6 : c->n4;
    NOP(ipt_new_chain(t, "XRAY_EXCLUDE_APP"));              /* make sure the jump target exists */
    NOP(ipt_new_chain(t, "XRAY_MARK"));
    NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_EXCLUDE_APP")));
    NOP(ipt_append(t, "XRAY_MARK", &SPEC(.mark_match = true, .mark_val = FWMARK, .target = MVT_RETURN)));
    for (int i = 0; i < c->nb; i++) NOP(ipt_append(t, "XRAY_MARK", &SPEC(.out_if = c->bypass[i], .target = MVT_RETURN)));
    /* DNS capture: every local UDP/TCP 53 packet goes into the tun, except to loopback resolvers */
    static const int protos[2] = { IPPROTO_UDP, IPPROTO_TCP };
    for (int i = 0; i < 2; i++) {
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.proto = protos[i], .dport_lo = 53, .dst = always, .dst_inv = true, .target = MVT_MARK, .set_mark = 1)));
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.proto = protos[i], .dport_lo = 53, .dst = always, .dst_inv = true, .target = MVT_RETURN)));
    }
    for (int i = 0; i < ns; i++) NOP(ipt_append(t, "XRAY_MARK", &SPEC(.dst = skip[i], .target = MVT_RETURN)));
    for (size_t i = 0; i < sizeof UID_EXCLUDE / sizeof *UID_EXCLUDE; i++)
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.owner = true, .uid_lo = UID_EXCLUDE[i], .uid_hi = UID_EXCLUDE[i], .target = MVT_RETURN)));
    if (c->mode == 1) {                  /* Wi-Fi/Ethernet only: bypass mobile */
        for (int i = 0; i < 4; i++) NOP(ipt_append(t, "XRAY_MARK", &SPEC(.out_if = MOBILE_IF[i], .target = MVT_RETURN)));
        mark_uid(t, NULL);
    } else if (c->mode == 2) {           /* mobile only: mark only there, everything else falls through */
        for (int i = 0; i < 4; i++) mark_uid(t, MOBILE_IF[i]);
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_RETURN)));
    } else mark_uid(t, NULL);
    NOP(ipt_append(t, "OUTPUT", &SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")));
}

/* IPv6 disabled: XRAY_MARK just drops local IPv6 (honouring networkMode). */
static void build_v6_block(ipt_tx *t, const cfg_t *c)
{
    NOP(ipt_new_chain(t, "XRAY_EXCLUDE_APP"));
    NOP(ipt_new_chain(t, "XRAY_MARK"));
    NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_EXCLUDE_APP")));
    NOP(ipt_append(t, "XRAY_MARK", &SPEC(.mark_match = true, .mark_val = FWMARK, .target = MVT_RETURN)));
    if (c->mode == 1) {
        for (int i = 0; i < 4; i++) NOP(ipt_append(t, "XRAY_MARK", &SPEC(.out_if = MOBILE_IF[i], .target = MVT_RETURN)));
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_DROP)));
    } else if (c->mode == 2) {
        for (int i = 0; i < 4; i++) NOP(ipt_append(t, "XRAY_MARK", &SPEC(.out_if = MOBILE_IF[i], .target = MVT_DROP)));
        NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_RETURN)));
    } else NOP(ipt_append(t, "XRAY_MARK", &SPEC(.target = MVT_DROP)));
    NOP(ipt_append(t, "OUTPUT", &SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")));
}

static ipt_tx *begin(int fam, const char *table)
{
    ipt_tx *t = ipt_tx_begin(fam, table);
    if (!t) {
        int e = errno; char d[2048] = "";
        log_msg("%s %s table unavailable (%s), skipping its rules", fam == AF_INET ? "iptables" : "ip6tables", table, strerror(e));
        if (e == EPROTO) { ipt_diagnose(fam, table, d, sizeof d); log_msg("  parse diagnosis: %s", d); }
    }
    return t;
}
static void commit(ipt_tx *t, const char *what)
{
    if (!t) return;
    int rc = ipt_tx_commit(t);
    if (rc < 0) log_msg("firewall: %s failed: %s", what, strerror(-rc));
    else LOGD("firewall: %s committed", what);
}

/* ================================================================ apply === */

int apply_routing_rules(void)
{
    cfg_t c; load_cfg(&c);

    if (c.ipv6 && settings_is_true("enableIPv6ULA")) {
        /* /64 lets Android advertise the prefix to hotspot clients: only with tethering allowed */
        int len = c.tether ? 64 : 128;
        char ifn[64];
        log_msg("IPv6 ULA decoy: on (/%d)", len);
        u_writef(ULA_FLAG, "%d\n", len);
        for (int i = 0; i < 5 && get_active_interface(ifn, sizeof ifn) < 0; i++) usleep(200 * 1000);
        if (get_active_interface(ifn, sizeof ifn) == 0) ula_apply(ifn);
        else log_msg("ULA: no active interface yet, will attach when one appears");
    } else ula_remove();

    enable_forward(c.ipv6);
    if (configure_tun_iface() < 0) return 1;
    sync_exclude_apps();              /* before XRAY_MARK is attached to OUTPUT */
    loosen_rp_filter();

    /* ---------------------------------------------------------------- IPv4 */
    install_tun_blackhole();
    ip_rule(AF_INET, 0, 1010, 100, NULL, NULL, NULL, -1, 0, true, 1);

    ipt_tx *t = begin(AF_INET, "mangle");
    if (t) {
        build_xray_mark(t, false, &c);
        NOP(ipt_new_chain(t, "HOTSPOT_PREROUTING"));
        for (int i = 0; i < c.n4; i++) NOP(ipt_append(t, "HOTSPOT_PREROUTING", &SPEC(.dst = c.skip4[i], .target = MVT_RETURN)));
        if (c.tether) {               /* ICMP too, so it reaches table 100 like tcp/udp */
            static const int hp[3] = { IPPROTO_TCP, IPPROTO_UDP, IPPROTO_ICMP };
            for (int i = 0; i < 3; i++)
                NOP(ipt_append(t, "HOTSPOT_PREROUTING", &SPEC(.in_if = TUN_NAME, .in_inv = true, .proto = hp[i], .target = MVT_MARK, .set_mark = 1)));
        }
        NOP(ipt_insert(t, "PREROUTING", 1, &SPEC(.target = MVT_JUMP, .jump_chain = "HOTSPOT_PREROUTING")));
        /* clamp TCP MSS to avoid fragmentation over the tun */
        NOP(ipt_insert(t, "FORWARD", 1, &SPEC(.out_if = TUN_NAME, .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1350)));
        commit(t, "v4 mangle");
    }
    if ((t = begin(AF_INET, "filter"))) {
        forward_rules(t, true);
        /* hide the proxy ports from ordinary apps (uid >= 9999) */
        NOP(ipt_insert(t, "OUTPUT", 1, &SPEC(.proto = IPPROTO_TCP, .dport_lo = TUN_PORT, .dst = TUN_ADDR, .owner = true, .uid_lo = 9999, .uid_hi = 2147483647u, .target = MVT_REJECT, .reject_with = MVR_TCP_RESET)));
        NOP(ipt_insert(t, "OUTPUT", 1, &SPEC(.proto = IPPROTO_TCP, .dport_lo = PLAIN_POOL_PORT, .dst = PLAIN_POOL_NET, .owner = true, .uid_lo = 9999, .uid_hi = 2147483647u, .target = MVT_REJECT, .reject_with = MVR_TCP_RESET)));
        commit(t, "v4 filter");
    }
    if (c.tether && (t = begin(AF_INET, "nat"))) {   /* force tethered DNS to 1.1.1.1 */
        for (const char **p = LAN_V4; *p; p++) {
            NOP(ipt_insert(t, "PREROUTING", 1, &SPEC(.in_if = TUN_NAME, .in_inv = true, .dst = *p, .proto = IPPROTO_UDP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
            NOP(ipt_insert(t, "PREROUTING", 1, &SPEC(.in_if = TUN_NAME, .in_inv = true, .dst = *p, .proto = IPPROTO_TCP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
        }
        commit(t, "v4 nat");
    }
    ip_rule(AF_INET, FR_ACT_GOTO, 5000, -1, "lo", NULL, NULL, -1, 6000, false, 0);
    ip_rule(AF_INET, 0, 5010, RT_TABLE_MAIN, TUN_NAME, NULL, NULL, 0, 0, false, 0);
    ip_rule(AF_INET, FR_ACT_GOTO, 5020, -1, TUN_NAME, NULL, NULL, -1, 6000, false, 0);
    for (int i = 0; i < c.n4; i++) RULE_TABLE(AF_INET, 5025, RT_TABLE_MAIN, NULL, NULL, c.skip4[i], -1, 0, false, 0);
    if (c.tether) for (const char **p = LAN_V4; *p; p++) RULE_TABLE(AF_INET, 5030, 100, NULL, *p, NULL, -1, 0, false, 0);
    ip_rule(AF_INET, FR_ACT_NOP, 6000, -1, NULL, NULL, NULL, -1, 0, false, 0);

    /* ---------------------------------------------------------------- IPv6 */
    if (c.ipv6) {
        install_tun_blackhole();
        ip_rule(AF_INET6, 0, 1010, 100, NULL, NULL, NULL, -1, 0, true, 1);
        if ((t = begin(AF_INET6, "mangle"))) {
            build_xray_mark(t, true, &c);
            NOP(ipt_new_chain(t, "HOTSPOT_PREROUTING"));
            for (int i = 0; i < c.n6; i++) NOP(ipt_append(t, "HOTSPOT_PREROUTING", &SPEC(.in_if = TUN_NAME, .in_inv = true, .dst = c.skip6[i], .target = MVT_RETURN)));
            if (c.tether) NOP(ipt_append(t, "HOTSPOT_PREROUTING", &SPEC(.in_if = TUN_NAME, .in_inv = true, .target = MVT_MARK, .set_mark = 1)));
            NOP(ipt_insert(t, "PREROUTING", 1, &SPEC(.target = MVT_JUMP, .jump_chain = "HOTSPOT_PREROUTING")));
            NOP(ipt_new_chain(t, "HOTSPOT_FORWARD"));
            NOP(ipt_append(t, "HOTSPOT_FORWARD", &SPEC(.out_if = TUN_NAME, .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1330)));
            NOP(ipt_insert(t, "FORWARD", 1, &SPEC(.target = MVT_JUMP, .jump_chain = "HOTSPOT_FORWARD")));
            commit(t, "v6 mangle");
        }
        if ((t = begin(AF_INET6, "filter"))) { forward_rules(t, true); commit(t, "v6 filter"); }
        for (int i = 0; i < c.n6; i++) RULE_TABLE(AF_INET6, 5025, RT_TABLE_MAIN, NULL, NULL, c.skip6[i], -1, 0, false, 0);
        if (c.tether) for (const char **p = LAN_V6; *p; p++) RULE_TABLE(AF_INET6, 5030, 100, NULL, *p, NULL, -1, 0, false, 0);
    } else {
        /* Block IPv6 (modes 1/2 let the non-proxied interfaces keep their own) */
        if (c.mode != 1 && c.mode != 2) ip_rule(AF_INET6, FR_ACT_UNREACHABLE, 1010, -1, NULL, NULL, NULL, -1, 0, true, 1);
        if ((t = begin(AF_INET6, "mangle"))) {
            build_v6_block(t, &c);
            NOP(ipt_new_chain(t, "HOTSPOT_PREROUTING"));
            NOP(ipt_append(t, "HOTSPOT_PREROUTING", &SPEC(.target = MVT_DROP)));
            NOP(ipt_insert(t, "PREROUTING", 1, &SPEC(.target = MVT_JUMP, .jump_chain = "HOTSPOT_PREROUTING")));
            commit(t, "v6 mangle");
        }
        if ((t = begin(AF_INET6, "filter"))) {
            forward_rules(t, true);
            NOP(ipt_new_chain(t, "HOTSPOT_FORWARD"));
            NOP(ipt_append(t, "HOTSPOT_FORWARD", &SPEC(.target = MVT_REJECT, .reject_with = MVR_6_NO_ROUTE)));
            NOP(ipt_insert(t, "FORWARD", 1, &SPEC(.target = MVT_JUMP, .jump_chain = "HOTSPOT_FORWARD")));
            commit(t, "v6 filter");
        }
    }
    return 0;
}

/* ============================================================ fail-closed === */

void hold_traffic(void)
{
    for (int i = 0; i < 2; i++) {
        ipt_tx *t = ipt_tx_begin(i ? AF_INET6 : AF_INET, "filter");
        if (!t) continue;
        NOP(ipt_new_chain(t, "XRAY_HOLD")); NOP(ipt_flush_chain(t, "XRAY_HOLD"));
        NOP(ipt_append(t, "XRAY_HOLD", &SPEC(.out_if = "lo", .target = MVT_RETURN)));
        NOP(ipt_append(t, "XRAY_HOLD", &SPEC(.mark_match = true, .mark_val = FWMARK, .target = MVT_RETURN)));
        NOP(ipt_append(t, "XRAY_HOLD", &SPEC(.target = MVT_REJECT)));
        ipt_spec j = SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_HOLD");
        if (ipt_check(t, "OUTPUT", &j) != 0) NOP(ipt_insert(t, "OUTPUT", 1, &j));
        if (ipt_check(t, "FORWARD", &j) != 0) NOP(ipt_insert(t, "FORWARD", 1, &j));
        commit(t, "hold");
    }
}

void release_traffic(void)
{
    for (int i = 0; i < 2; i++) {
        ipt_tx *t = ipt_tx_begin(i ? AF_INET6 : AF_INET, "filter");
        if (!t) continue;
        ipt_spec j = SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_HOLD");
        while (ipt_delete(t, "OUTPUT", &j) == 0) {}
        while (ipt_delete(t, "FORWARD", &j) == 0) {}
        NOP(ipt_flush_chain(t, "XRAY_HOLD")); NOP(ipt_delete_chain(t, "XRAY_HOLD"));
        commit(t, "release");
    }
}

/* ================================================================ clear === */

static void drop_chain(ipt_tx *t, const char *hook, const char *chain)
{
    NOP(ipt_delete(t, hook, &SPEC(.target = MVT_JUMP, .jump_chain = chain)));
    NOP(ipt_flush_chain(t, chain)); NOP(ipt_delete_chain(t, chain));
}

void clear_routing_rules(void)
{
    ipt_tx *t;
    if ((t = ipt_tx_begin(AF_INET, "mangle"))) {
        drop_chain(t, "OUTPUT", "XRAY_MARK");
        drop_chain(t, "PREROUTING", "HOTSPOT_PREROUTING");
        NOP(ipt_delete(t, "FORWARD", &SPEC(.out_if = TUN_NAME, .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1350)));
        commit(t, "v4 mangle clear");
    }
    { nl_rule r = NL_RULE(AF_INET); r.has_mark = true; r.mark = 1; r.table = 100; r.pref = 1010; NOP(nl_rule_del(&r)); }
    if ((t = ipt_tx_begin(AF_INET, "filter"))) {
        NOP(ipt_delete(t, "OUTPUT", &SPEC(.proto = IPPROTO_TCP, .dport_lo = TUN_PORT, .dst = TUN_ADDR, .owner = true, .uid_lo = 9999, .uid_hi = 2147483647u, .target = MVT_REJECT, .reject_with = MVR_TCP_RESET)));
        NOP(ipt_delete(t, "OUTPUT", &SPEC(.proto = IPPROTO_TCP, .dport_lo = PLAIN_POOL_PORT, .dst = PLAIN_POOL_NET, .owner = true, .uid_lo = 9999, .uid_hi = 2147483647u, .target = MVT_REJECT, .reject_with = MVR_TCP_RESET)));
        forward_rules(t, false);
        commit(t, "v4 filter clear");
    }
    rule_del(AF_INET, 5000, false); rule_del(AF_INET, 5010, false); rule_del(AF_INET, 5020, false);
    rule_del(AF_INET, 5025, true); rule_del(AF_INET, 5030, true); rule_del(AF_INET, 6000, false);
    if ((t = ipt_tx_begin(AF_INET, "nat"))) {
        for (const char **p = LAN_V4; *p; p++) {
            NOP(ipt_delete(t, "PREROUTING", &SPEC(.in_if = TUN_NAME, .in_inv = true, .dst = *p, .proto = IPPROTO_UDP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
            NOP(ipt_delete(t, "PREROUTING", &SPEC(.in_if = TUN_NAME, .in_inv = true, .dst = *p, .proto = IPPROTO_TCP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
        }
        commit(t, "v4 nat clear");
    }

    if ((t = ipt_tx_begin(AF_INET6, "mangle"))) {
        drop_chain(t, "OUTPUT", "XRAY_MARK");
        drop_chain(t, "PREROUTING", "HOTSPOT_PREROUTING");
        drop_chain(t, "FORWARD", "HOTSPOT_FORWARD");
        commit(t, "v6 mangle clear");
    }
    rule_del(AF_INET6, 1010, true); rule_del(AF_INET6, 5025, true); rule_del(AF_INET6, 5030, true);
    if ((t = ipt_tx_begin(AF_INET6, "filter"))) {
        forward_rules(t, false);
        drop_chain(t, "FORWARD", "HOTSPOT_FORWARD");
        commit(t, "v6 filter clear");
    }
    ula_remove();
    /* nothing references table 100 any more: drop the fail-closed fallback */
    NOP(nl_route_flush_table(AF_INET, 100)); NOP(nl_route_flush_table(AF_INET6, 100));
    NOP(link_set_up(TUN_NAME, false));
}
