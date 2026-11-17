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
 *   B <mac>                   device that is blocked
 *   L <mac|*> <bytes>         traffic limit of a device ("*" = the limit for every device without its own)
 *   Q <4|6> <mac> <bytes>     quota the kernel still has for that device (per family)
 *
 * Device control (block / limit), keyed by MAC address. A second chain, MV2R_TRAFFIC_CTL, hangs off mangle FORWARD
 * (the "mac" match is not valid in POSTROUTING), at position 1, for every outgoing interface:
 *   blocked:  -m mac --mac-source X -j DROP
 *   limited:  -m mac --mac-source X -m quota --quota N -j RETURN
 *             -m mac --mac-source X -j DROP                 (reached once the quota is used up)
 * The quota counts what the device SENDS (packets forwarded for it), the same direction the Devices list shows.
 * IPv4 and IPv6 each have their own quota. What the user asked for lives in CTL_STATE_FILE (persistent) and is
 * re-applied at service start and on every poll; the chain is removed on service shutdown.
 */
#include "service.h"
#include "ipt.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define TM_CHAIN       "MV2R_TRAFFIC"
#define CTL_CHAIN      "MV2R_TRAFFIC_CTL"
#define CTL_STATE_FILE DATADIR "/traffic_ctl"
#define CTL_MAX        64
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

/* ================================================================ device control === */

typedef struct {
    char blocked[CTL_MAX][18]; int nblocked;
    struct { char mac[18]; uint64_t bytes; } lim[CTL_MAX]; int nlim;
    uint64_t all;                                   /* limit for every device without its own, 0 = none */
} ctl_state;

/* ip -> MAC of everything seen on the neighbour table since the service started: the table forgets idle clients,
 * the byte counter of their address does not, so "reset this device" has to know the old addresses too. */
static struct { char ip[64]; uint8_t mac[6]; } g_memo[TM_MAX_NEIGH];
static int g_nmemo;
static void memo_put(const char *ip, const uint8_t *mac)
{
    for (int i = 0; i < g_nmemo; i++)
        if (!strcmp(g_memo[i].ip, ip)) { memcpy(g_memo[i].mac, mac, 6); return; }
    if (g_nmemo >= TM_MAX_NEIGH) return;
    snprintf(g_memo[g_nmemo].ip, 64, "%s", ip); memcpy(g_memo[g_nmemo].mac, mac, 6); g_nmemo++;
}

/* "AA:bb:..." -> "aa:bb:..."; false when it is not a unicast MAC address. */
static bool mac_norm(const char *in, char out[18])
{
    if (!in || strlen(in) != 17) return false;
    for (int i = 0; i < 17; i++) {
        if (i % 3 == 2) { if (in[i] != ':') return false; out[i] = ':'; }
        else { if (!isxdigit((unsigned char)in[i])) return false; out[i] = (char)tolower((unsigned char)in[i]); }
    }
    out[17] = 0;
    return (strtol(out, NULL, 16) & 1) == 0;        /* multicast / broadcast can never be a client */
}

static int ctl_load(ctl_state *s)
{
    memset(s, 0, sizeof *s);
    FILE *f = fopen(CTL_STATE_FILE, "r");
    if (!f) return 0;
    char line[96];
    while (fgets(line, sizeof line, f)) {
        char kind, mac[40]; unsigned long long v;
        char m[18];
        if (sscanf(line, "B %39s", mac) == 1 && mac_norm(mac, m) && s->nblocked < CTL_MAX) {
            memcpy(s->blocked[s->nblocked++], m, 18);
        } else if (sscanf(line, "%c %39s %llu", &kind, mac, &v) == 3 && kind == 'L') {
            if (!strcmp(mac, "*")) s->all = v;
            else if (mac_norm(mac, m) && s->nlim < CTL_MAX) { memcpy(s->lim[s->nlim].mac, m, 18); s->lim[s->nlim++].bytes = v; }
        }
    }
    fclose(f);
    return 0;
}

static int ctl_save(const ctl_state *s)
{
    const char *tmp = CTL_STATE_FILE ".tmp";
    FILE *f = fopen(tmp, "w");
    if (!f) return -errno;
    for (int i = 0; i < s->nblocked; i++) fprintf(f, "B %s\n", s->blocked[i]);
    for (int i = 0; i < s->nlim; i++) fprintf(f, "L %s %llu\n", s->lim[i].mac, (unsigned long long)s->lim[i].bytes);
    if (s->all) fprintf(f, "L * %llu\n", (unsigned long long)s->all);
    int rc = fclose(f) == 0 ? 0 : -errno;
    if (rc == 0 && rename(tmp, CTL_STATE_FILE) != 0) rc = -errno;
    return rc;
}

static int blocked_idx(const ctl_state *s, const char *mac)
{ for (int i = 0; i < s->nblocked; i++) if (!strcmp(s->blocked[i], mac)) return i; return -1; }
static int lim_idx(const ctl_state *s, const char *mac)
{ for (int i = 0; i < s->nlim; i++) if (!strcmp(s->lim[i].mac, mac)) return i; return -1; }

#define CTL_HOOK() IPT_SPEC(.target = MVT_JUMP, .jump_chain = CTL_CHAIN)

/* What a MAC should be: 0 = untouched, 1 = blocked, 2 = limited to *lim bytes. */
static int ctl_want(const ctl_state *s, const char *mac, uint64_t *lim)
{
    if (blocked_idx(s, mac) >= 0) return 1;
    int i = lim_idx(s, mac);
    if (i >= 0) { *lim = s->lim[i].bytes; return 2; }
    return 0;
}

/* One family: make chain + hook exist, bring the rules in line with `s`. `refresh` (may be NULL): that device's rules
 * are rebuilt from scratch (fresh quota); `refresh_default`: so are those of devices that only have the default
 * limit. `known`: MACs seen on the neighbour table (the default limit applies to them). Prints Q lines into `rep`. */
static void ctl_family(int fam, const ctl_state *s, const char *refresh, bool refresh_default,
                       char known[][18], int nknown, FILE *rep)
{
    ipt_tx *t = ipt_tx_begin(fam, "mangle");
    if (!t) return;
    (void)ipt_new_chain(t, CTL_CHAIN);
    ipt_spec hook = CTL_HOOK();
    if (ipt_check(t, "FORWARD", &hook) != 0) (void)ipt_insert(t, "FORWARD", 1, &hook);

    if (refresh) (void)ipt_delete_mac(t, CTL_CHAIN, refresh);
    ipt_mac_stat have[CTL_MAX * 2];
    int nh = ipt_chain_macs(t, CTL_CHAIN, have, CTL_MAX * 2);
    if (nh < 0) nh = 0;

    /* wanted set = explicit blocked / limited + known devices under the default limit */
    char want[CTL_MAX * 2][18]; int kind[CTL_MAX * 2]; uint64_t lim[CTL_MAX * 2]; int nw = 0;
    for (int i = 0; i < s->nblocked && nw < CTL_MAX * 2; i++) { memcpy(want[nw], s->blocked[i], 18); kind[nw] = 1; lim[nw++] = 0; }
    for (int i = 0; i < s->nlim && nw < CTL_MAX * 2; i++)
        if (blocked_idx(s, s->lim[i].mac) < 0) { memcpy(want[nw], s->lim[i].mac, 18); kind[nw] = 2; lim[nw++] = s->lim[i].bytes; }
    if (s->all)
        for (int i = 0; i < nknown && nw < CTL_MAX * 2; i++) {
            bool dup = false;
            for (int j = 0; j < nw; j++) if (!strcmp(want[j], known[i])) dup = true;
            if (!dup) { memcpy(want[nw], known[i], 18); kind[nw] = 2; lim[nw++] = s->all; }
        }
    /* a device that only had the default limit and the default is gone / changed: drop its rules, they are rebuilt below */
    for (int i = 0; i < nh; i++) {
        int w = -1;
        for (int j = 0; j < nw; j++) if (!strcmp(want[j], have[i].mac)) w = j;
        bool ok = w >= 0 && ((kind[w] == 1 && have[i].drop && !have[i].quota) || (kind[w] == 2 && have[i].quota));
        bool by_default = w >= 0 && kind[w] == 2 && lim_idx(s, have[i].mac) < 0;
        if (ok && refresh_default && by_default) ok = false;
        if (!ok) (void)ipt_delete_mac(t, CTL_CHAIN, have[i].mac);
    }
    nh = ipt_chain_macs(t, CTL_CHAIN, have, CTL_MAX * 2);          /* what is left after the deletions */
    if (nh < 0) nh = 0;
    for (int j = 0; j < nw; j++) {
        bool present = false;
        for (int i = 0; i < nh; i++) if (!strcmp(have[i].mac, want[j])) present = true;
        if (present) continue;
        if (kind[j] == 2) (void)ipt_append(t, CTL_CHAIN, &IPT_SPEC(.mac = want[j], .quota_on = true, .quota = lim[j], .target = MVT_RETURN));
        (void)ipt_append(t, CTL_CHAIN, &IPT_SPEC(.mac = want[j], .target = MVT_DROP));
    }
    if (rep) {
        int nn2 = ipt_chain_macs(t, CTL_CHAIN, have, CTL_MAX * 2);
        for (int i = 0; i < nn2; i++)
            if (have[i].quota) fprintf(rep, "Q %d %s %llu\n", fam == AF_INET ? 4 : 6, have[i].mac, (unsigned long long)have[i].quota_left);
    }
    int rc = ipt_tx_commit(t);
    if (rc < 0) log_msg("traffic: control v%d commit failed: %s", fam == AF_INET ? 4 : 6, strerror(-rc));
}

/* MACs of the neighbour table, both families (the "known devices"). */
static int known_macs(nl_neigh *nb, char out[][18], int max)
{
    int n = 0;
    for (int i = 0; i < 2; i++) {
        int cnt = nl_neigh_list(i ? AF_INET6 : AF_INET, nb, TM_MAX_NEIGH);
        for (int j = 0; j < cnt; j++) {
            const nl_neigh *e = &nb[j];
            if (!e->has_mac || (e->state & (NUD_INCOMPLETE_ | NUD_FAILED_))) continue;
            char m[18]; snprintf(m, 18, "%02x:%02x:%02x:%02x:%02x:%02x", e->mac[0], e->mac[1], e->mac[2], e->mac[3], e->mac[4], e->mac[5]);
            bool zero = !(e->mac[0] | e->mac[1] | e->mac[2] | e->mac[3] | e->mac[4] | e->mac[5]);
            if (!zero && !(e->mac[0] & 1) && n < max) {
                bool dup = false;
                for (int k = 0; k < n; k++) if (!strcmp(out[k], m)) dup = true;
                if (!dup) memcpy(out[n++], m, 18);
            }
        }
    }
    return n;
}

static void ctl_apply(const char *refresh, bool refresh_default, FILE *rep)
{
    ctl_state s; ctl_load(&s);
    nl_neigh *nb = calloc(TM_MAX_NEIGH, sizeof *nb);
    char (*known)[18] = calloc(TM_MAX_NEIGH, 18);
    int nk = (nb && known) ? known_macs(nb, known, TM_MAX_NEIGH) : 0;
    if (nk > CTL_MAX) nk = CTL_MAX;
    ctl_family(AF_INET, &s, refresh, refresh_default, known, nk, rep);
    ctl_family(AF_INET6, &s, refresh, refresh_default, known, nk, rep);
    free(nb); free(known);
}

void traffic_ctl_apply(void)
{
    ctl_state s; ctl_load(&s);
    if (s.nblocked || s.nlim || s.all) ctl_apply(NULL, false, NULL);
}

void traffic_ctl_remove(void)
{
    for (int i = 0; i < 2; i++) {
        ipt_tx *t = ipt_tx_begin(i ? AF_INET6 : AF_INET, "mangle");
        if (!t) continue;
        ipt_spec hook = CTL_HOOK();
        while (ipt_delete(t, "FORWARD", &hook) == 0) {}
        (void)ipt_flush_chain(t, CTL_CHAIN);
        (void)ipt_delete_chain(t, CTL_CHAIN);
        (void)ipt_tx_commit(t);
    }
}

/* Zero the counters of every address that belongs to `mac` (neighbour table now + what was seen before). */
static void reset_mac_counters(const char *mac)
{
    nl_neigh *nb = calloc(TM_MAX_NEIGH, sizeof *nb);
    for (int i = 0; i < 2; i++) {
        int fam = i ? AF_INET6 : AF_INET;
        ipt_tx *t = ipt_tx_begin(fam, "mangle");
        if (!t) continue;
        for (int k = 0; k < g_nmemo; k++) {
            char m[18]; const uint8_t *a = g_memo[k].mac;
            snprintf(m, 18, "%02x:%02x:%02x:%02x:%02x:%02x", a[0], a[1], a[2], a[3], a[4], a[5]);
            if (!strcmp(m, mac) && (strchr(g_memo[k].ip, ':') != NULL) == (fam == AF_INET6)) (void)ipt_zero_src(t, TM_CHAIN, g_memo[k].ip);
        }
        int cnt = nb ? nl_neigh_list(fam, nb, TM_MAX_NEIGH) : 0;
        for (int j = 0; j < cnt; j++) {
            const uint8_t *a = nb[j].mac; char m[18];
            snprintf(m, 18, "%02x:%02x:%02x:%02x:%02x:%02x", a[0], a[1], a[2], a[3], a[4], a[5]);
            if (nb[j].has_mac && !strcmp(m, mac)) (void)ipt_zero_src(t, TM_CHAIN, nb[j].addr);
        }
        (void)ipt_tx_commit(t);
    }
    free(nb);
}

/* "block <mac>", "unblock <mac>", "limit <mac> <bytes>", "limit_all <bytes>", "reset_mac <mac>" (the part of the
 * verb after "traffic_"). bytes 0 removes the limit. 0 or -errno. */
int traffic_ctl_cmd(const char *cmd)
{
    char verb[24] = "", a1[40] = "", a2[40] = "";
    int n = sscanf(cmd, "%23s %39s %39s", verb, a1, a2);
    if (n < 2) return -EINVAL;
    ctl_state s; ctl_load(&s);
    char mac[18];
    unsigned long long bytes = 0;

    if (!strcmp(verb, "limit_all")) {
        if (n != 2 || strspn(a1, "0123456789") != strlen(a1) || strlen(a1) > 18) return -EINVAL;
        s.all = strtoull(a1, NULL, 10);
        int rc = ctl_save(&s);
        if (rc < 0) return rc;
        ctl_apply(NULL, true, NULL);
        return 0;
    }
    if (!mac_norm(a1, mac)) return -EINVAL;
    if (!strcmp(verb, "block")) {
        if (n != 2) return -EINVAL;
        if (blocked_idx(&s, mac) < 0) { if (s.nblocked >= CTL_MAX) return -ENOSPC; memcpy(s.blocked[s.nblocked++], mac, 18); }
        int rc = ctl_save(&s); if (rc < 0) return rc;
        ctl_apply(NULL, false, NULL);
    } else if (!strcmp(verb, "unblock")) {
        if (n != 2) return -EINVAL;
        int i = blocked_idx(&s, mac);
        if (i >= 0) { memmove(s.blocked[i], s.blocked[i + 1], (size_t)(s.nblocked - i - 1) * 18); s.nblocked--; }
        int rc = ctl_save(&s); if (rc < 0) return rc;
        ctl_apply(NULL, false, NULL);
    } else if (!strcmp(verb, "limit")) {
        if (n != 3 || strspn(a2, "0123456789") != strlen(a2) || strlen(a2) > 18) return -EINVAL;
        bytes = strtoull(a2, NULL, 10);
        int i = lim_idx(&s, mac);
        if (bytes == 0) { if (i >= 0) { memmove(&s.lim[i], &s.lim[i + 1], (size_t)(s.nlim - i - 1) * sizeof s.lim[0]); s.nlim--; } }
        else if (i >= 0) s.lim[i].bytes = bytes;
        else { if (s.nlim >= CTL_MAX) return -ENOSPC; memcpy(s.lim[s.nlim].mac, mac, 18); s.lim[s.nlim++].bytes = bytes; }
        int rc = ctl_save(&s); if (rc < 0) return rc;
        ctl_apply(mac, false, NULL);                        /* fresh quota */
    } else if (!strcmp(verb, "reset_mac")) {
        if (n != 2) return -EINVAL;
        reset_mac_counters(mac);
        ctl_apply(mac, false, NULL);                        /* the quota starts over too */
    } else return -EINVAL;
    return 0;
}

static void ctl_print_state(FILE *f)
{
    ctl_state s; ctl_load(&s);
    for (int i = 0; i < s.nblocked; i++) fprintf(f, "B %s\n", s.blocked[i]);
    for (int i = 0; i < s.nlim; i++) fprintf(f, "L %s %llu\n", s.lim[i].mac, (unsigned long long)s.lim[i].bytes);
    if (s.all) fprintf(f, "L * %llu\n", (unsigned long long)s.all);
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
    for (int i = 0; i < nn; i++) if (nb[i].has_mac && !(nb[i].state & (NUD_INCOMPLETE_ | NUD_FAILED_))) memo_put(nb[i].addr, nb[i].mac);

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
    ctl_print_state(f);
    /* No tun, no point in leaving rules around: clear_routing_rules() removes them when xray stops. */
    if (counting && tx >= 0) {
        nl_neigh *nb = calloc(TM_MAX_NEIGH, sizeof *nb);
        if (nb) {
            family_pass(AF_INET, f, nb);
            family_pass(AF_INET6, f, nb);
            free(nb);
            ctl_state cs; ctl_load(&cs);
            if (cs.nblocked || cs.nlim || cs.all)           /* nothing set: leave the table alone */
                ctl_apply(NULL, false, f);                  /* picks up new devices for the default limit, reports the quotas */
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
