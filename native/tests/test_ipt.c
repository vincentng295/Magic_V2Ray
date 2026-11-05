/* Offline test of the xtables engine against a fake kernel that validates the
 * table with the same structural rules translate_table() enforces. */
#include "../jni/service/ipt.h"
#include <arpa/inet.h>
#include <assert.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter_ipv4/ip_tables.h>
#include <linux/netfilter_ipv6/ip6_tables.h>

static ipt_image cur; static int fam, eagain_once, stores;
static size_t HDR, O_T, O_N;
#define STD XT_ALIGN(sizeof(struct xt_standard_target))
#define ERR XT_ALIGN(sizeof(struct xt_error_target))
static uint16_t r16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }

static void setfam(int f)
{
    fam = f;
    HDR = f == AF_INET ? sizeof(struct ipt_entry) : sizeof(struct ip6t_entry);
    O_T = f == AF_INET ? offsetof(struct ipt_entry, target_offset) : offsetof(struct ip6t_entry, target_offset);
    O_N = f == AF_INET ? offsetof(struct ipt_entry, next_offset) : offsetof(struct ip6t_entry, next_offset);
}
static uint8_t *std_entry(int verdict, uint32_t *l, bool err, const char *name)
{
    uint32_t n = HDR + (err ? ERR : STD);
    uint8_t *b = calloc(1, n); uint16_t t = HDR, nx = n;
    memcpy(b + O_T, &t, 2); memcpy(b + O_N, &nx, 2);
    struct xt_entry_target *tg = (void *)(b + HDR);
    if (err) {
        tg->u.user.target_size = ERR; strcpy(tg->u.user.name, "ERROR");
        strcpy((char *)tg->data, name);
    } else { tg->u.user.target_size = STD; memcpy(tg->data, &verdict, 4); }
    *l = n; return b;
}

static int validate(const ipt_image *im, const char *why)
{
    uint8_t *ok = calloc(1, im->size + 1); uint32_t off = 0, n = 0, last = 0;
    int nerr = 0;
    while (off < im->size) {
        const uint8_t *e = im->entries + off;
        uint32_t nx = r16(e + O_N), to = r16(e + O_T);
        if (nx % 8 || off + nx > im->size || to < HDR || to + sizeof(struct xt_entry_target) > nx) { printf("BAD offsets @%u (%s)\n", off, why); return -EINVAL; }
        uint32_t m = HDR;
        while (m < to) { const struct xt_entry_match *mm = (void *)(e + m);
            if (mm->u.match_size < sizeof *mm || mm->u.match_size % 8 || m + mm->u.match_size > to) { printf("BAD match @%u (%s)\n", off, why); return -EINVAL; }
            m += mm->u.match_size; }
        if (m != to) { printf("match sum != target_offset @%u\n", off); return -EINVAL; }
        const struct xt_entry_target *t = (void *)(e + to);
        if (t->u.target_size != nx - to) { printf("target_size mismatch @%u (%s)\n", off, why); return -EINVAL; }
        if (!t->u.user.name[0] && t->u.target_size != STD) { printf("std size @%u\n", off); return -EINVAL; }
        if (!strcmp(t->u.user.name, "ERROR")) { nerr++; if (t->u.target_size != ERR) return -EINVAL; }
        ok[off] = 1; last = off; off += nx; n++;
    }
    if (n != im->num_entries) { printf("num_entries %u != %u\n", im->num_entries, n); return -EINVAL; }
    const struct xt_entry_target *lt = (void *)(im->entries + last + r16(im->entries + last + O_T));
    if (strcmp(lt->u.user.name, "ERROR")) { printf("no terminator\n"); return -EINVAL; }
    for (int h = 0; h < 5; h++) if (im->valid_hooks & (1u << h)) {
        if (!ok[im->hook_entry[h]] || !ok[im->underflow[h]]) { printf("hook %d bad\n", h); return -EINVAL; }
        const uint8_t *u = im->entries + im->underflow[h];
        const struct xt_entry_target *ut = (void *)(u + r16(u + O_T));
        int v; memcpy(&v, ut->data, 4);
        if (ut->u.user.name[0] || (v != -1 && v != -2)) { printf("underflow %d not ACCEPT/DROP\n", h); return -EINVAL; }
    }
    /* every jump must land right after an ERROR (chain head) entry */
    for (off = 0; off < im->size; off += r16(im->entries + off + O_N)) {
        const uint8_t *e = im->entries + off; const struct xt_entry_target *t = (void *)(e + r16(e + O_T));
        if (t->u.user.name[0]) continue;
        int v; memcpy(&v, t->data, 4);
        if (v < 0) continue;
        if (!ok[v]) { printf("jump to non-entry %d\n", v); return -EINVAL; }
        uint32_t prev = 0, p2 = 0; while (p2 < (uint32_t)v) { prev = p2; p2 += r16(im->entries + p2 + O_N); }
        const uint8_t *pe = im->entries + prev; const struct xt_entry_target *pt = (void *)(pe + r16(pe + O_T));
        if (strcmp(pt->u.user.name, "ERROR")) { printf("jump %d not after chain head\n", v); return -EINVAL; }
    }
    free(ok);
    return 0;
}

static int fk_load(int f, const char *tbl, ipt_image *o)
{ (void)f; (void)tbl; *o = cur; o->entries = malloc(cur.size); memcpy(o->entries, cur.entries, cur.size); return 0; }
static int fk_store(int f, const char *tbl, const ipt_image *im, uint32_t old)
{
    (void)f; (void)tbl;
    if (eagain_once) { eagain_once = 0; return -EAGAIN; }
    if (old != cur.num_entries) return -EAGAIN;
    int rc = validate(im, "store"); if (rc) return rc;
    free(cur.entries); cur = *im; cur.entries = malloc(im->size); memcpy(cur.entries, im->entries, im->size);
    stores++;
    return 0;
}
static const ipt_backend FK = { fk_load, fk_store };

static void init_table(uint32_t valid, bool netd_chain)
{
    free(cur.entries); memset(&cur, 0, sizeof cur);
    uint32_t off = 0, l; uint8_t *buf = calloc(1, 8192);
    cur.valid_hooks = valid;
    for (int h = 0; h < 5; h++) if (valid & (1u << h)) {
        cur.hook_entry[h] = off; cur.underflow[h] = off;
        uint8_t *e = std_entry(-2, &l, false, 0); memcpy(buf + off, e, l); off += l; cur.num_entries++; free(e);
    }
    if (netd_chain) {  /* a netd-style user chain with one RETURN-only body */
        uint8_t *e = std_entry(0, &l, true, "netd_chain"); memcpy(buf + off, e, l); off += l; free(e);
        e = std_entry(-5, &l, false, 0); memcpy(buf + off, e, l); off += l; free(e); cur.num_entries += 2;
    }
    uint8_t *e = std_entry(0, &l, true, "ERROR"); memcpy(buf + off, e, l); off += l; free(e); cur.num_entries++;
    cur.size = off; cur.entries = buf;
}

#define OK(x) do { int _r = (x); if (_r) { printf("FAIL line %d: %s -> %d\n", __LINE__, #x, _r); exit(1); } } while (0)

static void scenario(int family, bool v6)
{
    setfam(family);
    init_table(0x1f, true);
    uint8_t *orig = malloc(cur.size); size_t osz = cur.size; memcpy(orig, cur.entries, osz);
    ipt_tx *t = ipt_tx_begin(family, "mangle"); assert(t);
    OK(ipt_new_chain(t, "XRAY_EXCLUDE_APP"));
    OK(ipt_new_chain(t, "XRAY_MARK"));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_EXCLUDE_APP")));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.mark_match = true, .mark_val = 255, .target = MVT_RETURN)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.out_if = "tailscale0", .target = MVT_RETURN)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.proto = IPPROTO_UDP, .dport_lo = 53, .dst = v6 ? "::1/128" : "127.0.0.0/8", .dst_inv = true, .target = MVT_MARK, .set_mark = 1)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.proto = IPPROTO_TCP, .dport_lo = 53, .target = MVT_RETURN)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.dst = v6 ? "fc00::/7" : "10.0.0.0/8", .target = MVT_RETURN)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.owner = true, .uid_lo = 1001, .uid_hi = 1001, .target = MVT_RETURN)));
    OK(ipt_append(t, "XRAY_MARK", &IPT_SPEC(.out_if = "rmnet+", .owner = true, .uid_lo = 0, .uid_hi = 2147483647, .target = MVT_MARK, .set_mark = 1)));
    OK(ipt_append(t, "XRAY_EXCLUDE_APP", &IPT_SPEC(.owner = true, .uid_lo = 10123, .uid_hi = 10123, .target = MVT_ACCEPT)));
    OK(ipt_append(t, "OUTPUT", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")));
    OK(ipt_insert(t, "PREROUTING", 1, &IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .proto = IPPROTO_ICMP + (v6 ? 57 : 0), .target = MVT_MARK, .set_mark = 1)));
    OK(ipt_insert(t, "FORWARD", 1, &IPT_SPEC(.out_if = "xraytun0", .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1350)));
    OK(ipt_append(t, "FORWARD", &IPT_SPEC(.target = MVT_REJECT, .reject_with = v6 ? MVR_6_NO_ROUTE : MVR_TCP_RESET, .proto = IPPROTO_TCP, .dport_lo = 808)));
    if (!v6) OK(ipt_insert(t, "PREROUTING", 1, &IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .dst = "192.168.0.0/16", .proto = IPPROTO_UDP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
    assert(ipt_new_chain(t, "XRAY_MARK") == -EEXIST);
    assert(ipt_delete_chain(t, "XRAY_MARK") < 0);              /* in use */
    assert(ipt_check(t, "OUTPUT", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")) == 0);
    eagain_once = 1;                                           /* force a reload+replay */
    OK(ipt_tx_commit(t));
    assert(stores >= 1);
    printf("  v%d: applied, %u entries, %u bytes\n", v6 ? 6 : 4, cur.num_entries, cur.size);

    /* a second transaction must re-parse what the first wrote */
    t = ipt_tx_begin(family, "mangle"); assert(t);
    assert(ipt_check(t, "OUTPUT", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")) == 0);
    assert(ipt_check(t, "XRAY_EXCLUDE_APP", &IPT_SPEC(.owner = true, .uid_lo = 10123, .uid_hi = 10123, .target = MVT_ACCEPT)) == 0);
    assert(ipt_check(t, "XRAY_EXCLUDE_APP", &IPT_SPEC(.owner = true, .uid_lo = 10124, .uid_hi = 10124, .target = MVT_ACCEPT)) == -ENOENT);
    /* teardown, in the order clear_routing_rules uses */
    OK(ipt_delete(t, "OUTPUT", &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "XRAY_MARK")));
    OK(ipt_flush_chain(t, "XRAY_MARK")); OK(ipt_delete_chain(t, "XRAY_MARK"));
    OK(ipt_flush_chain(t, "XRAY_EXCLUDE_APP")); OK(ipt_delete_chain(t, "XRAY_EXCLUDE_APP"));
    OK(ipt_delete(t, "PREROUTING", &IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .proto = IPPROTO_ICMP + (v6 ? 57 : 0), .target = MVT_MARK, .set_mark = 1)));
    OK(ipt_delete(t, "FORWARD", &IPT_SPEC(.out_if = "xraytun0", .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1350)));
    OK(ipt_delete(t, "FORWARD", &IPT_SPEC(.target = MVT_REJECT, .reject_with = v6 ? MVR_6_NO_ROUTE : MVR_TCP_RESET, .proto = IPPROTO_TCP, .dport_lo = 808)));
    if (!v6) OK(ipt_delete(t, "PREROUTING", &IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .dst = "192.168.0.0/16", .proto = IPPROTO_UDP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1")));
    OK(ipt_tx_commit(t));
    if (cur.size != osz || memcmp(cur.entries, orig, osz)) { printf("FAIL: table did not return to its original bytes (%u vs %zu)\n", cur.size, osz); exit(1); }
    printf("  v%d: teardown restored the original table byte-for-byte (netd chain preserved)\n", v6 ? 6 : 4);
    free(orig);
}

int main(void)
{
    ipt_set_backend(&FK);
    puts("ipt engine tests");
    scenario(AF_INET, false);
    scenario(AF_INET6, true);
    puts("PASS");
    return 0;
}
