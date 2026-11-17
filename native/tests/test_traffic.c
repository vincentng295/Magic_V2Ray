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
bool nl_addr_first_v4(const char *dev, char *out, size_t cap) { (void)dev; snprintf(out, cap, "198.18.0.1"); return true; }
int nl_addr_list_v6(const char *dev, char *out, size_t cap) { (void)dev; snprintf(out, cap, "fdfe:dcba:9876::1"); return 1; }
int nl_neigh_list(int family, nl_neigh *o, int max)
{
    (void)max; memset(o, 0, 4 * sizeof *o); int n = 0;
    if (family == AF_INET) {
        snprintf(o[n].addr, 64, "192.168.43.10"); o[n].has_mac = true; memcpy(o[n].mac, "\xaa\xbb\xcc\xdd\xee\x01", 6); o[n].state = 0x02; n++;
        snprintf(o[n].addr, 64, "192.168.43.99"); o[n].has_mac = true; o[n].state = 0x20; n++;           /* FAILED: skipped */
        snprintf(o[n].addr, 64, "192.168.43.1");  o[n].has_mac = false; o[n].state = 0x01; n++;           /* no mac: skipped */
    } else {
        snprintf(o[n].addr, 64, "fe80::1"); o[n].has_mac = true; o[n].state = 0x02; n++;                  /* link-local: skipped */
        snprintf(o[n].addr, 64, "fd00::5"); o[n].has_mac = true; memcpy(o[n].mac, "\xaa\xbb\xcc\xdd\xee\x03", 6); o[n].state = 0x04; n++;
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
        uint8_t *img = calloc(1, 1024); uint32_t sz = 0;
        uint32_t hdr = fam == AF_INET ? sizeof(struct ipt_entry) : sizeof(struct ip6t_entry);
        put(img, &sz, fam, NULL, -2);                                   /* INPUT policy */
        uint32_t fwd = sz; put(img, &sz, fam, NULL, -2);                /* FORWARD policy */
        uint32_t post = sz; put(img, &sz, fam, NULL, -2);               /* POSTROUTING policy */
        put(img, &sz, fam, "ERROR", 0);
        o->valid_hooks = 0x16; o->size = sz; o->num_entries = 4; o->entries = img;
        o->hook_entry[1] = o->underflow[1] = 0; o->hook_entry[2] = o->underflow[2] = fwd;
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
    assert(strstr(o, "T 12345 67890\n"));
    assert(strstr(o, "R * 0 0\nR 198.18.0.1 0 0\nR 192.168.43.10 0 0\n"));          /* v4: catch-all, tun addr, client */
    assert(!strstr(o, "192.168.43.99") && !strstr(o, "192.168.43.1 "));            /* FAILED / no-mac skipped */
    assert(strstr(o, "R fdfe:dcba:9876::1 0 0\nR fd00::5 0 0\n") || strstr(o, "R fd00::5 0 0\n"));
    assert(!strstr(o, "fe80"));
    assert(strstr(o, "S 198.18.0.1\n") && strstr(o, "M 192.168.43.10 aa:bb:cc:dd:ee:01\n"));
    /* the way back: catch-all + one -d rule per source, v4 and v6 */
    assert(strstr(o, "D * 0 0\nD 198.18.0.1 0 0\nD 192.168.43.10 0 0\n"));
    assert(strstr(o, "D fd00::5 0 0\n"));
    assert(!strstr(o, "E 4") && !strstr(o, "E 6"));
    int s4 = stores[0], s6 = stores[1]; assert(s4 == 1 && s6 == 1);

    /* a second poll finds everything in place: no store at all */
    assert(traffic_poll(true) == 0);
    assert(stores[0] == s4 && stores[1] == s6);

    /* read-only mode: totals only, table untouched */
    assert(traffic_poll(false) == 0);
    o = slurp(TRAFFIC_FILE); assert(!strstr(o, "R ") && strstr(o, "T 12345 67890"));
    assert(stores[0] == s4);

    /* reset zeroes (commit happens); stop removes hook + chain (second stop is a no-op) */
    assert(traffic_reset() == 0 && stores[0] == s4 + 1);
    traffic_remove(); assert(stores[0] == s4 + 2 && stores[1] == s6 + 2);
    traffic_remove(); assert(stores[0] == s4 + 2);

    /* poll after stop re-creates everything; no tun = no rules */
    assert(traffic_poll(true) == 0 && stores[0] == s4 + 3);
    traffic_remove(); g_tun_up = false; int before = stores[0];
    assert(traffic_poll(true) == 0);
    o = slurp(TRAFFIC_FILE); assert(!strstr(o, "R ") && strstr(o, "T -1 -1")); assert(stores[0] == before);
    g_tun_up = true;
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
