/* LIVE (real kernel, root): another writer adds the same rule while we commit -> EAGAIN -> reload + replay must not
 * leave two copies. gcc -Wno-override-init -Inative/jni/service -o /tmp/live_race native/tests/live_race.c -lpthread
 * && unshare -n /tmp/live_race   (expect: rules in chain: 2) */
#include "ipt.c"
static int injected;
static const char *INJ_IP = "203.0.113.47";
static int wl(int fam, const char *tb, ipt_image *o) { return be_load(fam, tb, o); }
static int ws(int fam, const char *tb, const ipt_image *im, uint32_t old)
{
    if (!injected) {            /* another writer (netd / second process) adds the very same rule first */
        injected = 1;
        ipt_set_backend(NULL);
        ipt_tx *t = ipt_tx_begin(AF_INET, "mangle");
        (void)ipt_new_chain(t, "MV2R_TRAFFIC");
        (void)ipt_append(t, "MV2R_TRAFFIC", &IPT_SPEC(.src = INJ_IP, .target = MVT_COUNT));
        int rc = ipt_tx_commit(t); printf("  [other writer commit=%d]\n", rc);
        static const ipt_backend me = { wl, ws }; ipt_set_backend(&me);
    }
    return be_store(fam, tb, im, old);
}
int main(void)
{
    static const ipt_backend me = { wl, ws }; ipt_set_backend(&me);
    ipt_tx *t = ipt_tx_begin(AF_INET, "mangle");
    (void)ipt_new_chain(t, "MV2R_TRAFFIC");
    if (ipt_chain_stats(t, "MV2R_TRAFFIC", NULL, 0) == 0) (void)ipt_append(t, "MV2R_TRAFFIC", &IPT_SPEC(.target = MVT_COUNT));
    ipt_spec s = IPT_SPEC(.src = INJ_IP, .target = MVT_COUNT);
    if (ipt_check(t, "MV2R_TRAFFIC", &s) != 0) (void)ipt_append(t, "MV2R_TRAFFIC", &s);
    printf("commit=%d\n", ipt_tx_commit(t));
    ipt_set_backend(NULL);
    t = ipt_tx_begin(AF_INET, "mangle"); ipt_rule_stat st[16];
    int n = ipt_chain_stats(t, "MV2R_TRAFFIC", st, 16);
    printf("rules in chain: %d ->", n); for (int i = 0; i < n; i++) printf(" [%s]", st[i].any ? "*" : st[i].src); puts(""); ipt_tx_abort(t);
    return 0;
}
