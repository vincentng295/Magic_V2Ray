/* IPv6 ULA decoy (service.sh "IPv6 ULA decoy"): gives the physical interface a
 * private IPv6 address + policy route so apps believe IPv6 works. */
#include "service.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <linux/fib_rules.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <errno.h>
#include <stdarg.h>
#include <unistd.h>

/* ula_apply runs on every network event: log a state only when it changes. */
static char g_note[160];
static void ula_note(const char *fmt, ...)
{
    char b[160]; va_list ap;
    va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    if (strcmp(b, g_note)) { snprintf(g_note, sizeof g_note, "%s", b); log_msg("%s", b); }
}

static int ula_suffix(char *out, size_t cap)
{
    char b[64];
    if (u_read(ULA_SUFFIX_FILE, b, sizeof b) > 0) { u_trim(b); if (b[0]) { snprintf(out, cap, "%s", b); return 0; } }
    uint8_t r[4];
    if (getrandom(r, sizeof r, 0) != (ssize_t)sizeof r) return -1;
    unsigned hi = (r[0] << 8) | r[1], lo = (r[2] << 8) | r[3];
    if (hi == 0) hi = 1;       /* a zero first group would be compressed away by the kernel's text form */
    snprintf(out, cap, "%x:%x", hi, lo);
    u_write(ULA_SUFFIX_FILE, out);
    return 0;
}

static void addr_del_both(const struct in6_addr *a, const char *dev)
{
    nl_addr_del(AF_INET6, dev, a, 64);
    nl_addr_del(AF_INET6, dev, a, 128);
}

/* default dev <iface> src <ULA> in table 31999, plus two rules just before
 * Android's catch-all unreachable. */
static int route_apply(const char *iface, const struct in6_addr *a)
{
    if (nl_route_replace_dev(AF_INET6, ULA_TABLE, iface, a, 1024) < 0) {
        log_msg("ULA: route add failed on %s", iface);
        return -1;
    }
    nl_rule block = NL_RULE(AF_INET6); block.action = FR_ACT_UNREACHABLE; block.has_mark = true;
    block.mark = FWMARK; block.pref = ULA_BLOCK_PREF;
    nl_rule look = NL_RULE(AF_INET6); look.table = ULA_TABLE; look.pref = ULA_RULE_PREF;
    int b = nl_rule_add(&block), l = nl_rule_add(&look);      /* EEXIST when already present: fine */
    if ((b < 0 && b != -EEXIST) || (l < 0 && l != -EEXIST))
        log_msg("ULA: policy rule add failed (block: %s, lookup: %s)", b < 0 ? strerror(-b) : "ok", l < 0 ? strerror(-l) : "ok");
    // else ula_note("ULA: route and policy rules in place on %s", iface);
    return 0;
}

static void route_remove(void)
{
    nl_rule a = NL_RULE(AF_INET6), b = NL_RULE(AF_INET6);
    a.pref = ULA_RULE_PREF; b.pref = ULA_BLOCK_PREF;
    nl_rule_del_all(&a); nl_rule_del_all(&b);
    nl_route_flush_table(AF_INET6, ULA_TABLE);
}

void ula_apply(const char *iface)
{
    char suffix[40], text[128], flag[16], old[64];
    struct in6_addr addr;
    if (!iface || !*iface || !strcmp(iface, TUN_NAME)) return;
    if (!u_exists(ULA_FLAG)) { LOGD("ULA: skipped, decoy not enabled (no %s)", ULA_FLAG); return; }
    if (ula_suffix(suffix, sizeof suffix) < 0) { log_msg("ULA: could not generate address"); return; }
    snprintf(text, sizeof text, "%s%s", ULA_PREFIX, suffix);
    if (inet_pton(AF_INET6, text, &addr) != 1) { log_msg("ULA: bad address %s", text); return; }

    int len = 128;           /* flag content = prefix length; anything odd -> /128 */
    if (u_read(ULA_FLAG, flag, sizeof flag) > 0 && atoi(flag) == 64) len = 64;

    if (u_read(ULA_IFACE_FILE, old, sizeof old) > 0) u_trim(old); else old[0] = 0;
    if (old[0] && strcmp(old, iface)) { addr_del_both(&addr, old); log_msg("ULA: removed %s from %s", text, old); }

    if (nl_addr_has(AF_INET6, iface, &addr, len)) {
        u_write(ULA_IFACE_FILE, iface);
        // ula_note("ULA: %s/%d already on %s", text, len, iface);
        route_apply(iface, &addr);
        return;
    }
    /* Address can't be re-added with a different prefix length: drop both first. */
    addr_del_both(&addr, iface);
    if (nl_addr_add(AF_INET6, iface, &addr, len, NL_ADDR_NODAD | NL_ADDR_NOPREFIXROUTE, true) == 0 ||
        nl_addr_add(AF_INET6, iface, &addr, len, 0, true) == 0) {
        if (len == 64) {     /* on-link /64 on the upstream would misroute hotspot replies */
            struct in6_addr pfx; inet_pton(AF_INET6, ULA_PREFIX, &pfx);
            nl_route_del_dev(AF_INET6, &pfx, 64, iface);
        }
        u_write(ULA_IFACE_FILE, iface);
        log_msg("ULA: added %s/%d to %s (%s)", text, len, iface,
                nl_addr_has(AF_INET6, iface, &addr, len) ? "visible in kernel" : "NOT visible right after add");
        route_apply(iface, &addr);
        return;
    }
    log_msg("ULA: failed to add %s/%d to %s", text, len, iface);
}

void ula_remove(void)
{
    char old[64], suffix[40], text[128]; struct in6_addr a;
    if (u_read(ULA_IFACE_FILE, old, sizeof old) > 0) {
        u_trim(old);
        if (old[0]) {
            if (u_read(ULA_SUFFIX_FILE, suffix, sizeof suffix) > 0) {
                u_trim(suffix);
                snprintf(text, sizeof text, "%s%s", ULA_PREFIX, suffix);
                if (suffix[0] && inet_pton(AF_INET6, text, &a) == 1) addr_del_both(&a, old);
            }
            log_msg("ULA: removed from %s", old);
        }
    }
    route_remove();
    unlink(ULA_FLAG); unlink(ULA_IFACE_FILE);
}
