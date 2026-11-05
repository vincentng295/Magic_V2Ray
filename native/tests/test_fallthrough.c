/* Regression: a rule with no target (libiptc "fallthrough": standard target whose
 * verdict is the offset of the NEXT entry) must load, and its verdict must be
 * re-pointed at the new next-entry offset when rules are inserted before it.
 * This is how netd's bw_* chains look; it used to fail with EPROTO. */
#include "../jni/service/ipt.h"
#include <assert.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter_ipv4/ip_tables.h>

static uint8_t img[4096]; static uint32_t sz, fall_off;
static ipt_image stored; static int have_store;

static uint32_t put(const char *err, int verdict)
{
    struct ipt_entry *e = (void *)(img + sz); uint32_t hdr = sizeof(struct ipt_entry);
    e->target_offset = hdr; struct xt_entry_target *t = (void *)(img + sz + hdr);
    if (err) { e->next_offset = hdr + 64; t->u.user.target_size = 64; strcpy(t->u.user.name, "ERROR"); strcpy((char *)t + 32, err); }
    else { e->next_offset = hdr + 40; t->u.user.target_size = 40; *(int *)((char *)t + 32) = verdict; }
    uint32_t o = sz; sz += e->next_offset; return o;
}
static int ld(int fam, const char *t, ipt_image *o)
{
    (void)fam; (void)t; sz = 0; memset(img, 0, sizeof img);
    put(NULL, -2);                                   /* OUTPUT policy @0 */
    put("chainA", 0);                                /* user chain header */
    fall_off = sz;
    put(NULL, (int)(sz + 112 + 40));                 /* no-target rule: verdict = next entry */
    put(NULL, -5);                                   /* implicit RETURN */
    put("ERROR", 0);
    memset(o, 0, sizeof *o); o->valid_hooks = 0x8; o->size = sz; o->num_entries = 5;
    o->entries = malloc(sz); memcpy(o->entries, img, sz); o->hook_entry[3] = 0; o->underflow[3] = 0;
    return 0;
}
static int st(int fam, const char *t, const ipt_image *im, uint32_t old)
{
    (void)fam; (void)t; (void)old;
    stored = *im; stored.entries = malloc(im->size); memcpy(stored.entries, im->entries, im->size); have_store = 1;
    return 0;
}
int main(void)
{
    static const ipt_backend be = { ld, st }; ipt_set_backend(&be);
    ipt_tx *t = ipt_tx_begin(AF_INET, "x");
    assert(t && "fallthrough rule must load");
    assert(ipt_insert(t, "chainA", 1, &IPT_SPEC(.target = MVT_ACCEPT)) == 0);
    assert(ipt_tx_commit(t) == 0 && have_store);
    /* chainA header @152 (176), new rule @328 (152), old fallthrough rule @480 (152) */
    struct ipt_entry *e = (void *)(stored.entries + 480);
    struct xt_standard_target *s = (void *)((uint8_t *)e + e->target_offset);
    printf("fallthrough verdict now %d (expect 632)\n", s->verdict);
    assert(s->verdict == 480 + 152);
    puts("PASS");
    return 0;
}
