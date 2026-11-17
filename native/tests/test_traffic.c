/* traffic.c against a fake kernel table (persisting between polls) and stubbed netlink / sysfs.
 * Build: see run_host_tests.sh (MV_STUB_DIR points at a temp dir). */
#include "../jni/service/service.h"
#include "../jni/service/ipt.h"
#include "../jni/service/netlink.h"
#include <assert.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter_ipv4/ip_tables.h>
#include <linux/netfilter_ipv6/ip6_tables.h>

/* ---- stubs for what the service provides ---- */
void log_msg(const char *fmt, ...) { (void)fmt; }
bool g_tun_up = true;
int u_read(const char *path, char *buf, size_t cap)
{
    if (!g_tun_up) return -1;
    const char *v = strstr(path, "tx_bytes") ? "12345\n" : "67890\n";
    snprintf(buf, cap, "%s", v); return (int)strlen(v);
}
bool nl_addr_first_v4(const char *dev, char *out, size_t cap) { (void)dev; (void)out; (void)cap; return false; }
int nl_addr_list_v6(const char *dev, char *out, size_t cap) { (void)dev; (void)out; (void)cap; return 0; }
/* ifindex 7 = hotspot "ap0"; 9 = the upstream "wlan0" (its gateway must never become a client); 4 = the tun */
const char *mv_ifname(unsigned idx, char *buf)
{
    const char *n = idx == 7 ? "ap0" : idx == 9 ? "wlan0" : idx == 4 ? "xraytun0" : NULL;
    if (n) strcpy(buf, n);
    return n;
}
int get_upstream_interface(char *out, size_t cap) { snprintf(out, cap, "wlan0"); return 0; }
int nl_neigh_list(int family, nl_neigh *o, int max)
{
    (void)max; memset(o, 0, 8 * sizeof *o); int n = 0;
    if (family == AF_INET) {
        snprintf(o[n].addr, 64, "192.168.43.10"); o[n].has_mac = true; memcpy(o[n].mac, "\xaa\xbb\xcc\xdd\xee\x01", 6); o[n].state = 0x02; o[n].ifindex = 7; n++;
        snprintf(o[n].addr, 64, "192.168.43.99"); o[n].has_mac = true; o[n].state = 0x20; o[n].ifindex = 7; n++;   /* FAILED: skipped */
        snprintf(o[n].addr, 64, "192.168.43.1");  o[n].has_mac = false; o[n].state = 0x01; o[n].ifindex = 7; n++;  /* no mac: skipped */
        snprintf(o[n].addr, 64, "10.0.0.1"); o[n].has_mac = true; o[n].state = 0x02; o[n].ifindex = 9; memcpy(o[n].mac, "\x11\x22\x33\x44\x55\x66", 6); n++; /* upstream gateway: skipped */
    } else {
        snprintf(o[n].addr, 64, "fe80::1"); o[n].has_mac = true; o[n].state = 0x02; o[n].ifindex = 7; n++;                  /* link-local: skipped */
        snprintf(o[n].addr, 64, "fd00::5"); o[n].has_mac = true; memcpy(o[n].mac, "\xaa\xbb\xcc\xdd\xee\x03", 6); o[n].state = 0x04; o[n].ifindex = 7; n++;
    }
    return n;
}

/* ---- fake kernel: a mangle table with just a POSTROUTING policy; it keeps what the engine stores ---- */
static ipt_image kern[2]; static int have[2]; static int stores[2];
static void put(uint8_t *img, uint32_t *sz, int fam, const char *err, int verdict)
{
    uint32_t hdr = fam == AF_INET ? sizeof(struct ipt_entry) : sizeof(struct ip6t_entry);
    uint16_t toff = hdr, nxt = err ? hdr + 64 : hdr + 40;
    memcpy(img + *sz + (fam == AF_INET ? offsetof(struct ipt_entry, target_offset) : offsetof(struct ip6t_entry, target_offset)), &toff, 2);
    memcpy(img + *sz + (fam == AF_INET ? offsetof(struct ipt_entry, next_offset) : offsetof(struct ip6t_entry, next_offset)), &nxt, 2);
    struct xt_entry_target *t = (void *)(img + *sz + hdr);
    if (err) { t->u.user.target_size = 64; strcpy(t->u.user.name, "ERROR"); strcpy((char *)t + 32, err); }
    else { t->u.user.target_size = 40; memcpy((char *)t + 32, &verdict, 4); }
    *sz += nxt;
}
static int fk_load(int fam, const char *tbl, ipt_image *o)
{
    (void)tbl; int i = fam == AF_INET ? 0 : 1; memset(o, 0, sizeof *o);
    if (!have[i]) {
        uint8_t *img = calloc(1, 2048); uint32_t sz = 0;
        uint32_t hdr = fam == AF_INET ? sizeof(struct ipt_entry) : sizeof(struct ip6t_entry);
        put(img, &sz, fam, NULL, -2);                                   /* PREROUTING policy */
        uint32_t inp = sz; put(img, &sz, fam, NULL, -2);                /* INPUT policy */
        uint32_t fwd = sz; put(img, &sz, fam, NULL, -2);                /* FORWARD policy */
        uint32_t post = sz; put(img, &sz, fam, NULL, -2);               /* POSTROUTING policy */
        put(img, &sz, fam, "ERROR", 0);
        o->valid_hooks = 0x17; o->size = sz; o->num_entries = 5; o->entries = img;
        o->hook_entry[0] = o->underflow[0] = 0;
        o->hook_entry[1] = o->underflow[1] = inp; o->hook_entry[2] = o->underflow[2] = fwd;
        o->hook_entry[4] = o->underflow[4] = post; (void)hdr;
        return 0;
    }
    *o = kern[i]; o->entries = malloc(kern[i].size); memcpy(o->entries, kern[i].entries, kern[i].size); o->ctr_map = NULL;
    return 0;
}
static int fk_store(int fam, const char *tbl, const ipt_image *im, uint32_t old)
{
    (void)tbl; (void)old; int i = fam == AF_INET ? 0 : 1;
    free(kern[i].entries); kern[i] = *im; kern[i].ctr_map = NULL;
    kern[i].entries = malloc(im->size); memcpy(kern[i].entries, im->entries, im->size); have[i] = 1; stores[i]++;
    return 0;
}

static char *slurp(const char *p) { static char b[8192]; FILE *f = fopen(p, "r"); assert(f); size_t n = fread(b, 1, sizeof b - 1, f); b[n] = 0; fclose(f); return b; }

int main(void)
{
    static const ipt_backend be = { fk_load, fk_store }; ipt_set_backend(&be);
    char cmd[200]; snprintf(cmd, sizeof cmd, "mkdir -p %s %s", RUN_DIR, DATADIR); assert(system(cmd) == 0);

    assert(traffic_poll(true) == 0);
    char *o = slurp(TRAFFIC_FILE); puts(o);
    assert(strstr(o, "T 12345 67890\nO 1\n"));
    /* only the hotspot client: no catch-all, no tun address, nothing of the upstream gateway / FAILED / no-mac entries */
    assert(strstr(o, "R 192.168.43.10 0 0\n") && !strstr(o, "R *") && !strstr(o, "198.18"));
    assert(!strstr(o, "192.168.43.99") && !strstr(o, "192.168.43.1 ") && !strstr(o, "10.0.0.1"));
    assert(strstr(o, "R fd00::5 0 0\n") && !strstr(o, "fe80"));
    assert(strstr(o, "M 192.168.43.10 aa:bb:cc:dd:ee:01\n"));
    assert(strstr(o, "D 192.168.43.10 0 0\n") && strstr(o, "D fd00::5 0 0\n") && !strstr(o, "D *"));
    assert(strstr(o, "H ap0 67890 12345\n") && !strstr(o, "H wlan0"));              /* the door's own byte counters */
    assert(!strstr(o, "E 4") && !strstr(o, "E 6"));
    int s4 = stores[0], s6 = stores[1]; assert(s4 == 1 && s6 == 1);

    /* where the rules sit: upload in PREROUTING with -i ap0, download in POSTROUTING with -o ap0; nothing in FORWARD/INPUT */
    {
        ipt_tx *tt = ipt_tx_begin(AF_INET, "mangle"); assert(tt);
        assert(ipt_check(tt, "PREROUTING", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV2R_HS_UP")) == 0);
        assert(ipt_check(tt, "POSTROUTING", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV2R_HS_DN")) == 0);
        assert(ipt_check(tt, "MV2R_HS_UP", &IPT_SPEC(.in_if = "ap0", .src = "192.168.43.10", .target = MVT_COUNT)) == 0);
        assert(ipt_check(tt, "MV2R_HS_UP", &IPT_SPEC(.src = "192.168.43.10", .target = MVT_COUNT)) != 0);   /* the interface is part of the rule */
        assert(ipt_check(tt, "MV2R_HS_DN", &IPT_SPEC(.out_if = "ap0", .dst = "192.168.43.10", .target = MVT_COUNT)) == 0);
        assert(ipt_chain_stats(tt, "MV2R_HS_UP", NULL, 0) == 1 && ipt_chain_stats(tt, "MV2R_HS_DN", NULL, 0) == 1);
        assert(ipt_chain_stats(tt, "FORWARD", NULL, 0) == 0 && ipt_chain_stats(tt, "INPUT", NULL, 0) == 0);
        ipt_tx_abort(tt);
    }

    /* a second poll finds everything in place: no store at all */
    assert(traffic_poll(true) == 0);
    assert(stores[0] == s4 && stores[1] == s6);

    /* read-only mode: totals only, table untouched */
    assert(traffic_poll(false) == 0);
    o = slurp(TRAFFIC_FILE); assert(!strstr(o, "R ") && strstr(o, "T 12345 67890"));
    assert(stores[0] == s4);

    /* reset zeroes (commit happens) */
    assert(traffic_reset() == 0 && stores[0] == s4 + 1);

    /* ---- independent of the tun: without xraytun0 the counting goes on ---- */
    g_tun_up = false;
    assert(traffic_poll(true) == 0);
    o = slurp(TRAFFIC_FILE); assert(strstr(o, "T -1 -1\n") && strstr(o, "R 192.168.43.10 ") && strstr(o, "O 1\n"));
    assert(stores[0] == s4 + 1);                                                    /* nothing was torn down or re-added */
    g_tun_up = true;

    /* ---- on / off switch: off removes every rule, stays off across polls, on brings it back ---- */
    assert(traffic_set(false) == 0);
    {
        ipt_tx *tt = ipt_tx_begin(AF_INET, "mangle");
        assert(ipt_chain_stats(tt, "MV2R_HS_UP", NULL, 0) < 0 && ipt_chain_stats(tt, "PREROUTING", NULL, 0) == 0 && ipt_chain_stats(tt, "POSTROUTING", NULL, 0) == 0);
        ipt_tx_abort(tt);
    }
    int off4 = stores[0];
    assert(traffic_poll(true) == 0);                                                /* a poll must not turn it back on */
    o = slurp(TRAFFIC_FILE); assert(strstr(o, "O 0\n") && !strstr(o, "R ") && !strstr(o, "D ") && strstr(o, "T 12345 67890"));
    assert(stores[0] == off4);
    traffic_autostart(); assert(stores[0] == off4);                                 /* autostart respects the switch too */
    assert(traffic_set(true) == 0);
    traffic_autostart(); assert(stores[0] > off4);                                  /* service start: rules are back */
    assert(traffic_poll(true) == 0);
    o = slurp(TRAFFIC_FILE); assert(strstr(o, "O 1\n") && strstr(o, "R 192.168.43.10 0 0\n"));

    /* ---- what an older build left behind (xraytun0 hooks / chains, copies) is removed ---- */
    {
        ipt_tx *tt = ipt_tx_begin(AF_INET, "mangle"); assert(tt);
        (void)ipt_new_chain(tt, "MV2R_TRAFFIC"); (void)ipt_new_chain(tt, "MV2R_TRAFFIC_RX");
        (void)ipt_append(tt, "MV2R_TRAFFIC", &IPT_SPEC(.target = MVT_COUNT));
        for (int k = 0; k < 3; k++) (void)ipt_append(tt, "MV2R_TRAFFIC", &IPT_SPEC(.src = "192.168.43.10", .target = MVT_COUNT));
        (void)ipt_insert(tt, "POSTROUTING", 1, &IPT_SPEC(.out_if = "xraytun0", .target = MVT_JUMP, .jump_chain = "MV2R_TRAFFIC"));
        (void)ipt_insert(tt, "FORWARD", 1, &IPT_SPEC(.in_if = "xraytun0", .target = MVT_JUMP, .jump_chain = "MV2R_TRAFFIC_RX"));
        (void)ipt_insert(tt, "INPUT", 1, &IPT_SPEC(.in_if = "xraytun0", .target = MVT_JUMP, .jump_chain = "MV2R_TRAFFIC_RX"));
        assert(ipt_tx_commit(tt) == 0);
        assert(traffic_poll(true) == 0);
        tt = ipt_tx_begin(AF_INET, "mangle");
        assert(ipt_chain_stats(tt, "MV2R_TRAFFIC", NULL, 0) < 0 && ipt_chain_stats(tt, "MV2R_TRAFFIC_RX", NULL, 0) < 0);
        assert(ipt_chain_stats(tt, "FORWARD", NULL, 0) == 0 && ipt_chain_stats(tt, "INPUT", NULL, 0) == 0);
        assert(ipt_chain_stats(tt, "POSTROUTING", NULL, 0) == 1);                  /* only our download hook */
        ipt_tx_abort(tt);
    }

    /* ---- duplicates in the new chains (racing writer) are collapsed by the next poll ---- */
    {
        ipt_tx *tt = ipt_tx_begin(AF_INET, "mangle"); assert(tt);
        for (int k = 0; k < 5; k++) {
            (void)ipt_append(tt, "MV2R_HS_UP", &IPT_SPEC(.in_if = "ap0", .src = "192.168.43.10", .target = MVT_COUNT));
            (void)ipt_append(tt, "MV2R_HS_DN", &IPT_SPEC(.out_if = "ap0", .dst = "192.168.43.10", .target = MVT_COUNT));
            (void)ipt_insert(tt, "PREROUTING", 1, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV2R_HS_UP"));
        }
        assert(ipt_tx_commit(tt) == 0);
        tt = ipt_tx_begin(AF_INET, "mangle");
        assert(ipt_chain_stats(tt, "MV2R_HS_UP", NULL, 0) == 1 + 5 && ipt_chain_stats(tt, "PREROUTING", NULL, 0) == 1 + 5);
        ipt_tx_abort(tt);
        assert(traffic_poll(true) == 0);
        tt = ipt_tx_begin(AF_INET, "mangle");
        assert(ipt_chain_stats(tt, "MV2R_HS_UP", NULL, 0) == 1 && ipt_chain_stats(tt, "MV2R_HS_DN", NULL, 0) == 1);
        assert(ipt_chain_stats(tt, "PREROUTING", NULL, 0) == 1);
        ipt_tx_abort(tt);
        int st = stores[0]; assert(traffic_poll(true) == 0 && stores[0] == st);   /* and then it is quiet again */
    }
    /* remove takes everything; a second remove is a no-op */
    traffic_remove(); { int st = stores[0]; traffic_remove(); assert(stores[0] == st); }
    assert(traffic_poll(true) == 0);                                                /* a poll re-creates it (switch is on) */
    /* ---- device control: block / limit by MAC ---- */
    #define MACS(fam, out, n) do { ipt_tx *tt = ipt_tx_begin(fam, "mangle"); assert(tt); n = ipt_chain_macs(tt, "MV2R_TRAFFIC_CTL", out, 8); ipt_tx_abort(tt); } while (0)
    ipt_mac_stat ms[8]; int nm;
    unlink(MV_STUB_DIR "/../ctl_state_unused");
    assert(traffic_ctl_cmd("block AA:BB:CC:DD:EE:01") == 0);                    /* upper case is normalised */
    MACS(AF_INET, ms, nm); assert(nm == 1 && !strcmp(ms[0].mac, "aa:bb:cc:dd:ee:01") && ms[0].drop && !ms[0].quota);
    MACS(AF_INET6, ms, nm); assert(nm == 1 && ms[0].drop);                      /* v6 too */
    assert(traffic_ctl_cmd("block aa:bb:cc:dd:ee:01") == 0);                    /* idempotent */
    MACS(AF_INET, ms, nm); assert(nm == 1);
    assert(traffic_ctl_cmd("limit aa:bb:cc:dd:ee:02 1048576") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 2);
    for (int i = 0; i < nm; i++) if (!strcmp(ms[i].mac, "aa:bb:cc:dd:ee:02")) assert(ms[i].quota && ms[i].drop && ms[i].quota_left == 1048576);
    /* blocking a limited device turns it into a plain DROP, unblocking brings the quota back */
    assert(traffic_ctl_cmd("block aa:bb:cc:dd:ee:02") == 0);
    MACS(AF_INET, ms, nm); for (int i = 0; i < nm; i++) assert(!ms[i].quota);
    assert(traffic_ctl_cmd("unblock aa:bb:cc:dd:ee:02") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 2);
    for (int i = 0; i < nm; i++) if (!strcmp(ms[i].mac, "aa:bb:cc:dd:ee:02")) assert(ms[i].quota);
    assert(traffic_ctl_cmd("unblock aa:bb:cc:dd:ee:01") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 1 && !strcmp(ms[0].mac, "aa:bb:cc:dd:ee:02"));
    /* the poll reports state + quota */
    assert(traffic_poll(true) == 0);
    o = slurp(TRAFFIC_FILE);
    assert(strstr(o, "L aa:bb:cc:dd:ee:02 1048576\n") && strstr(o, "Q 4 aa:bb:cc:dd:ee:02 1048576\n") && !strstr(o, "B "));
    /* limit for everybody: the known device (aa:..:01 on the neighbour table) gets its own quota; a poll keeps it */
    assert(traffic_ctl_cmd("limit_all 2048") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 3);
    assert(traffic_poll(true) == 0); MACS(AF_INET, ms, nm); assert(nm == 3);
    assert(strstr(slurp(TRAFFIC_FILE), "L * 2048\n"));
    /* reset one device: its counters and its quota start over; the explicit limit is not the default */
    assert(traffic_ctl_cmd("reset_mac aa:bb:cc:dd:ee:02") == 0);
    MACS(AF_INET, ms, nm); for (int i = 0; i < nm; i++) if (!strcmp(ms[i].mac, "aa:bb:cc:dd:ee:02")) assert(ms[i].quota_left == 1048576);
    assert(traffic_ctl_cmd("reset_mac aa:bb:cc:dd:ee:01") == 0);
    /* removing limits */
    assert(traffic_ctl_cmd("limit_all 0") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 1);
    assert(traffic_ctl_cmd("limit aa:bb:cc:dd:ee:02 0") == 0);
    MACS(AF_INET, ms, nm); assert(nm == 0);
    /* bad input is refused and changes nothing */
    assert(traffic_ctl_cmd("block aa:bb:cc") < 0);
    assert(traffic_ctl_cmd("block 01:00:5e:00:00:01") < 0);                     /* multicast */
    assert(traffic_ctl_cmd("limit aa:bb:cc:dd:ee:02 12x") < 0);
    assert(traffic_ctl_cmd("frobnicate aa:bb:cc:dd:ee:02") < 0);
    MACS(AF_INET, ms, nm); assert(nm == 0);
    /* settings survive the chain being removed (service restart) */
    assert(traffic_ctl_cmd("block aa:bb:cc:dd:ee:09") == 0);
    traffic_ctl_remove(); MACS(AF_INET, ms, nm); assert(nm < 0 || nm == 0);
    traffic_ctl_apply(); MACS(AF_INET, ms, nm); assert(nm == 1 && ms[0].drop);
    assert(traffic_ctl_cmd("unblock aa:bb:cc:dd:ee:09") == 0);
    puts("PASS");
    return 0;
}
