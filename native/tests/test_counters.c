/* Counter handling of the ipt engine (fake kernel):
 *  - ipt_chain_stats reads the counters of the loaded entries (src + pkts/bytes),
 *  - a table replace maps the old counters onto the entries that survive (ctr_map),
 *  - ipt_zero_chain drops them, a new MVT_COUNT rule is a no-target (fallthrough) rule. */
#include "../jni/service/ipt.h"
#include <assert.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter_ipv4/ip_tables.h>

static uint8_t img[8192]; static uint32_t sz;
static ipt_image stored; static int32_t map_copy[64]; static uint32_t map_n; static int stores;

/* returns the entry's offset; ctr_p/ctr_b = counters; src = optional /32 source */
static uint32_t put(const char *err, int verdict, uint64_t ctr_p, uint64_t ctr_b, const char *src)
{
    struct ipt_entry *e = (void *)(img + sz); uint32_t hdr = sizeof(struct ipt_entry);
    e->target_offset = hdr; struct xt_entry_target *t = (void *)(img + sz + hdr);
    if (err) { e->next_offset = hdr + 64; t->u.user.target_size = 64; strcpy(t->u.user.name, "ERROR"); strcpy((char *)t + 32, err); }
    else { e->next_offset = hdr + 40; t->u.user.target_size = 40; *(int *)((char *)t + 32) = verdict; }
    e->counters.pcnt = ctr_p; e->counters.bcnt = ctr_b;
    if (src) { inet_pton(AF_INET, src, &e->ip.src); e->ip.smsk.s_addr = 0xffffffffu; }
    uint32_t o = sz; sz += e->next_offset; return o;
}

static int ld(int fam, const char *tbl, ipt_image *o)
{
    (void)fam; (void)tbl; sz = 0; memset(img, 0, sizeof img);
    put(NULL, -1 /*ACCEPT*/ - 1, 7, 700, NULL);            /* 0: POSTROUTING policy  (7 pkts / 700 B) */
    put("CH", 0, 0, 0, NULL);                              /* 1: chain header */
    uint32_t r2 = sz;
    put(NULL, (int)(r2 + 152), 5, 500, NULL);              /* 2: catch-all count rule */
    uint32_t r3 = sz;
    put(NULL, (int)(r3 + 152), 3, 300, "10.0.0.2");        /* 3: count rule for 10.0.0.2 */
    put(NULL, -5, 9, 900, NULL);                           /* 4: implicit RETURN */
    put("ERROR", 0, 0, 0, NULL);                           /* 5: terminator */
    memset(o, 0, sizeof *o); o->valid_hooks = 0x10; o->size = sz; o->num_entries = 6;
    o->entries = malloc(sz); memcpy(o->entries, img, sz); o->hook_entry[4] = 0; o->underflow[4] = 0;
    return 0;
}
static int st(int fam, const char *tbl, const ipt_image *im, uint32_t old)
{
    (void)fam; (void)tbl; (void)old;
    assert(im->ctr_map);
    map_n = im->num_entries; memcpy(map_copy, im->ctr_map, map_n * sizeof *map_copy);
    free(stored.entries);
    stored = *im; stored.ctr_map = NULL; stored.entries = malloc(im->size); memcpy(stored.entries, im->entries, im->size); stores++;
    return 0;
}

int main(void)
{
    static const ipt_backend be = { ld, st }; ipt_set_backend(&be);

    /* 1. read counters */
    ipt_tx *t = ipt_tx_begin(AF_INET, "mangle");
    assert(t);
    ipt_rule_stat s[8];
    assert(ipt_chain_stats(t, "CH", NULL, 0) == 2);
    assert(ipt_chain_stats(t, "NOPE", s, 8) == -2 /* -ENOENT */);
    assert(ipt_chain_stats(t, "CH", s, 8) == 2);
    assert(s[0].any && s[0].pkts == 5 && s[0].bytes == 500);
    assert(!s[1].any && !strcmp(s[1].src, "10.0.0.2") && s[1].pkts == 3 && s[1].bytes == 300);
    ipt_tx_abort(t);

    /* 2. add a rule: old counters must be mapped onto the surviving entries */
    t = ipt_tx_begin(AF_INET, "mangle");
    ipt_spec newr = IPT_SPEC(.src = "10.0.0.9", .target = MVT_COUNT);
    assert(ipt_check(t, "CH", &newr) != 0);
    assert(ipt_append(t, "CH", &newr) == 0);
    assert(ipt_check(t, "CH", &newr) == 0);
    ipt_spec have = IPT_SPEC(.src = "10.0.0.2", .target = MVT_COUNT);
    assert(ipt_check(t, "CH", &have) == 0 && "loaded count rule must equal a freshly built one");
    assert(ipt_tx_commit(t) == 0 && stores == 1);
    /* new order: 0 policy, 1 hdr, 2 catch-all, 3 10.0.0.2, 4 NEW 10.0.0.9, 5 RETURN, 6 terminator */
    assert(map_n == 7);
    int32_t want[7] = { 0, -1, 2, 3, -1, -1, -1 };
    for (int i = 0; i < 7; i++) { if (map_copy[i] != want[i]) { printf("map[%d]=%d want %d\n", i, map_copy[i], want[i]); assert(0); } }
    /* the new no-target rule's verdict points at the next entry */
    struct ipt_entry *e = (void *)(stored.entries + 0);
    uint32_t off = e->next_offset + (sizeof(struct ipt_entry) + 64);          /* policy + chain header */
    for (int i = 2; i <= 4; i++) {
        e = (void *)(stored.entries + off);
        struct xt_standard_target *sv = (void *)((uint8_t *)e + e->target_offset);
        assert(sv->verdict == (int)(off + e->next_offset));
        off += e->next_offset;
    }

    /* 3. zero the chain: its rules lose their counters, the policy keeps them */
    t = ipt_tx_begin(AF_INET, "mangle");
    assert(ipt_zero_chain(t, "CH") == 0 && ipt_zero_chain(t, "NOPE") != 0);
    assert(ipt_tx_commit(t) == 0 && stores == 2);
    int32_t want2[6] = { 0, -1, -1, -1, -1, -1 };
    assert(map_n == 6);
    for (int i = 0; i < 6; i++) { if (map_copy[i] != want2[i]) { printf("map2[%d]=%d want %d\n", i, map_copy[i], want2[i]); assert(0); } }

    /* 4. an untouched transaction does not store anything */
    t = ipt_tx_begin(AF_INET, "mangle");
    assert(ipt_tx_commit(t) == 0 && stores == 2);
    free(stored.entries);
    puts("PASS");
    return 0;
}
