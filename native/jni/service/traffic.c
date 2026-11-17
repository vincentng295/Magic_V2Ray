/* Traffic Monitor tab backend (replaces the iptables shell snippets the WebUI used to run).
 *
 * Counts what the system pushes INTO xraytun0 (upload as seen from the clients) per source IP:
 *
 *   mangle POSTROUTING:  -o xraytun0 -j MV2R_TRAFFIC          (inserted at position 1: nothing before it can
 *                                                              end the walk with a verdict)
 *   MV2R_TRAFFIC:        <catch-all, no target>                (everything that enters the chain)
 *                        -s <ip>   (no target)                (one counter-only rule per known source)
 *
 * mangle POSTROUTING runs before nat POSTROUTING, so a forwarded hotspot packet still carries the client's own
 * address. Sources are found, on every poll, from the neighbour table (hotspot / LAN clients) and from the
 * addresses of xraytun0 itself; whatever else enters the chain is only visible in the catch-all rule, which the
 * UI shows as "this device / other".
 *
 * One poll = one table load per family. New sources go in with a single commit; ipt_tx_commit hands the old
 * counters back to the surviving entries (IPT_SO_SET_ADD_COUNTERS), so adding a source never resets the others.
 *
 * Output: TRAFFIC_FILE, rewritten atomically (the control reply buffer is far too small for a per-IP list).
 *   T <tx> <rx>               xraytun0 byte counters, -1 when the interface is absent. tx = bytes sent INTO the
 *                             tunnel, rx = bytes that came back out of it
 *   E <4|6>                   the table of that family could not be loaded / edited
 *   R <src|*> <pkts> <bytes>  one line per rule of the chain; "*" = the catch-all rule (v4 and v6 both give one)
 *   S <ip>                    an address of xraytun0 ("this device")
 *   M <ip> <mac>              neighbour with a link-layer address
 */
#include "service.h"
#include "ipt.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define TM_CHAIN       "MV2R_TRAFFIC"
#define TM_MAX_RULES   200          /* per family, catch-all included */
#define TM_MAX_NEIGH   512
#define NUD_INCOMPLETE_ 0x01
#define NUD_FAILED_     0x20

static bool is_link_local6(const char *a)
{
    struct in6_addr x;
    if (inet_pton(AF_INET6, a, &x) != 1) return true;               /* unparsable: never a source */
    return x.s6_addr[0] == 0xfe && (x.s6_addr[1] & 0xc0) == 0x80;
}

static long long read_stat(const char *name)
{
    char path[96], b[40];
    snprintf(path, sizeof path, "/sys/class/net/%s/statistics/%s", TUN_NAME, name);
    if (u_read(path, b, sizeof b) <= 0) return -1;
    return atoll(b);
}

/* ipt_spec for "-o xraytun0 -j MV2R_TRAFFIC" */
#define HOOK_SPEC() IPT_SPEC(.out_if = TUN_NAME, .target = MVT_JUMP, .jump_chain = TM_CHAIN)

/* The addresses of xraytun0 that can be a packet's source (v4: the first one; v6: global / ULA). */
static int tun_addrs(int fam, char out[][64], int max)
{
    int n = 0;
    if (fam == AF_INET) {
        char a[64];
        if (max > 0 && nl_addr_first_v4(TUN_NAME, a, sizeof a)) { snprintf(out[n++], 64, "%s", a); }
    } else {
        char list[512];
        nl_addr_list_v6(TUN_NAME, list, sizeof list);
        for (char *save = NULL, *tok = strtok_r(list, " ", &save); tok && n < max; tok = strtok_r(NULL, " ", &save))
            snprintf(out[n++], 64, "%s", tok);
    }
    return n;
}

/* One family: make the chain + hook exist, learn new sources, read the counters, print them. */
static void family_pass(int fam, FILE *f, nl_neigh *nb)
{
    ipt_tx *t = ipt_tx_begin(fam, "mangle");
    if (!t) { fprintf(f, "E %d\n", fam == AF_INET ? 4 : 6); return; }

    (void)ipt_new_chain(t, TM_CHAIN);                                   /* -EEXIST is fine */
    if (ipt_chain_stats(t, TM_CHAIN, NULL, 0) == 0)
        (void)ipt_append(t, TM_CHAIN, &IPT_SPEC(.target = MVT_COUNT));  /* the catch-all goes first */
    ipt_spec hook = HOOK_SPEC();
    if (ipt_check(t, "POSTROUTING", &hook) != 0) (void)ipt_insert(t, "POSTROUTING", 1, &hook);

    int nrules = ipt_chain_stats(t, TM_CHAIN, NULL, 0);
    char self[8][64];
    int nself = tun_addrs(fam, self, 8);
    int nn = nl_neigh_list(fam, nb, TM_MAX_NEIGH);
    if (nn < 0) nn = 0;

    for (int i = 0; i < nself + nn; i++) {
        const char *ip;
        if (i < nself) ip = self[i];
        else {
            const nl_neigh *e = &nb[i - nself];
            if (!e->has_mac || (e->state & (NUD_INCOMPLETE_ | NUD_FAILED_))) continue;
            if (fam == AF_INET6 && is_link_local6(e->addr)) continue;
            ip = e->addr;
        }
        ipt_spec s = IPT_SPEC(.src = ip, .target = MVT_COUNT);
        if (ipt_check(t, TM_CHAIN, &s) == 0) continue;
        if (nrules >= TM_MAX_RULES) break;
        if (ipt_append(t, TM_CHAIN, &s) == 0) nrules++;
    }

    ipt_rule_stat *st = calloc(TM_MAX_RULES + 8, sizeof *st);
    int cnt = st ? ipt_chain_stats(t, TM_CHAIN, st, TM_MAX_RULES + 8) : 0;
    if (cnt > TM_MAX_RULES + 8) cnt = TM_MAX_RULES + 8;
    for (int i = 0; i < cnt; i++)
        fprintf(f, "R %s %llu %llu\n", st[i].any ? "*" : st[i].src,
                (unsigned long long)st[i].pkts, (unsigned long long)st[i].bytes);
    free(st);
    for (int i = 0; i < nself; i++) fprintf(f, "S %s\n", self[i]);
    for (int i = 0; i < nn; i++) {
        const nl_neigh *e = &nb[i];
        if (!e->has_mac || (e->state & (NUD_INCOMPLETE_ | NUD_FAILED_))) continue;
        if (fam == AF_INET6 && is_link_local6(e->addr)) continue;
        fprintf(f, "M %s %02x:%02x:%02x:%02x:%02x:%02x\n", e->addr,
                e->mac[0], e->mac[1], e->mac[2], e->mac[3], e->mac[4], e->mac[5]);
    }
    int rc = ipt_tx_commit(t);              /* no-op when nothing changed */
    if (rc < 0) { log_msg("traffic: v%d commit failed: %s", fam == AF_INET ? 4 : 6, strerror(-rc)); fprintf(f, "E %d\n", fam == AF_INET ? 4 : 6); }
}

int traffic_poll(bool counting)
{
    const char *tmp = TRAFFIC_FILE ".tmp";
    FILE *f = fopen(tmp, "w");
    if (!f) return -errno;
    long long tx = read_stat("tx_bytes"), rx = read_stat("rx_bytes");
    fprintf(f, "T %lld %lld\n", tx, rx);
    /* No tun, no point in leaving rules around: clear_routing_rules() removes them when xray stops. */
    if (counting && tx >= 0) {
        nl_neigh *nb = calloc(TM_MAX_NEIGH, sizeof *nb);
        if (nb) {
            family_pass(AF_INET, f, nb);
            family_pass(AF_INET6, f, nb);
            free(nb);
        } else {
            fprintf(f, "E 4\nE 6\n");
        }
    }
    int rc = (fclose(f) == 0) ? 0 : -errno;
    if (rc == 0 && rename(tmp, TRAFFIC_FILE) != 0) rc = -errno;
    return rc;
}

int traffic_reset(void)
{
    for (int i = 0; i < 2; i++) {
        int fam = i ? AF_INET6 : AF_INET;
        ipt_tx *t = ipt_tx_begin(fam, "mangle");
        if (!t) continue;
        (void)ipt_zero_chain(t, TM_CHAIN);          /* -ENOENT: nothing counted yet, commit is a no-op */
        (void)ipt_tx_commit(t);
    }
    return 0;
}

void traffic_remove(void)
{
    for (int i = 0; i < 2; i++) {
        int fam = i ? AF_INET6 : AF_INET;
        ipt_tx *t = ipt_tx_begin(fam, "mangle");
        if (!t) continue;
        ipt_spec hook = HOOK_SPEC();
        while (ipt_delete(t, "POSTROUTING", &hook) == 0) {}
        (void)ipt_flush_chain(t, TM_CHAIN);
        (void)ipt_delete_chain(t, TM_CHAIN);
        (void)ipt_tx_commit(t);
    }
}
