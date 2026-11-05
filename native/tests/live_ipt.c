/* Live smoke test against the real kernel. Run inside `unshare -n` (needs root,
 * and NOT a user namespace: xt_owner refuses those). Installs the exact rule
 * shapes service.sh uses, re-reads them, and removes them again. */
#include "../jni/service/ipt.h"
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>

static int fails;
static void report(const char *what, int rc) { printf("  %-46s %s\n", what, rc ? strerror(-rc) : "ok"); if (rc) fails++; }

static void run(int fam, const char *table, const char *hook, const ipt_spec *rules, int n, const char *label)
{
    ipt_tx *t = ipt_tx_begin(fam, table);
    if (!t) { printf("  %-46s cannot load table\n", label); fails++; return; }
    ipt_new_chain(t, "MV_TEST");
    for (int i = 0; i < n; i++) ipt_append(t, "MV_TEST", &rules[i]);
    ipt_insert(t, hook, 1, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV_TEST"));
    report(label, ipt_tx_commit(t));
    t = ipt_tx_begin(fam, table);
    int c = ipt_check(t, hook, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV_TEST"));
    ipt_delete(t, hook, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV_TEST"));
    ipt_flush_chain(t, "MV_TEST"); ipt_delete_chain(t, "MV_TEST");
    int rc = ipt_tx_commit(t);
    report("  re-read + cleanup", c ? c : rc);
}

int main(void)
{
    ipt_spec mangle[] = {
        IPT_SPEC(.mark_match = true, .mark_val = 255, .target = MVT_RETURN),
        IPT_SPEC(.out_if = "tailscale0", .target = MVT_RETURN),
        IPT_SPEC(.proto = IPPROTO_UDP, .dport_lo = 53, .dst = "127.0.0.0/8", .dst_inv = true, .target = MVT_MARK, .set_mark = 1),
        IPT_SPEC(.dst = "10.0.0.0/8", .target = MVT_RETURN),
        IPT_SPEC(.owner = true, .uid_lo = 1001, .uid_hi = 1001, .target = MVT_RETURN),
        IPT_SPEC(.out_if = "rmnet+", .owner = true, .uid_lo = 0, .uid_hi = 2147483647, .target = MVT_MARK, .set_mark = 1),
        IPT_SPEC(.target = MVT_RETURN) };
    run(AF_INET, "mangle", "OUTPUT", mangle, 7, "v4 mangle OUTPUT (XRAY_MARK shapes)");
    ipt_spec pre[] = {
        IPT_SPEC(.dst = "192.168.0.0/16", .target = MVT_RETURN),
        IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .proto = IPPROTO_TCP, .target = MVT_MARK, .set_mark = 1),
        IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .proto = IPPROTO_ICMP, .target = MVT_MARK, .set_mark = 1) };
    run(AF_INET, "mangle", "PREROUTING", pre, 3, "v4 mangle PREROUTING (HOTSPOT shapes)");
    ipt_spec fwd[] = {
        IPT_SPEC(.out_if = "xraytun0", .proto = IPPROTO_TCP, .tcp_flags_mask = 6, .tcp_flags_cmp = 2, .target = MVT_TCPMSS, .mss = 1350) };
    run(AF_INET, "mangle", "FORWARD", fwd, 1, "v4 mangle FORWARD (TCPMSS clamp)");
    ipt_spec ffwd[] = {
        IPT_SPEC(.in_if = "xraytun0", .target = MVT_ACCEPT), IPT_SPEC(.out_if = "xraytun0", .target = MVT_ACCEPT) };
    run(AF_INET, "filter", "FORWARD", ffwd, 2, "v4 filter FORWARD (accept tun)");
    ipt_spec out[] = {
        IPT_SPEC(.proto = IPPROTO_TCP, .dport_lo = 808, .dst = "127.17.1.3", .owner = true, .uid_lo = 9999, .uid_hi = 2147483647, .target = MVT_REJECT, .reject_with = MVR_TCP_RESET),
        IPT_SPEC(.out_if = "lo", .target = MVT_RETURN), IPT_SPEC(.target = MVT_REJECT) };
    run(AF_INET, "filter", "OUTPUT", out, 3, "v4 filter OUTPUT (REJECT tcp-reset, hold)");
    ipt_spec nat[] = {
        IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .dst = "10.0.0.0/8", .proto = IPPROTO_UDP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1"),
        IPT_SPEC(.in_if = "xraytun0", .in_inv = true, .dst = "10.0.0.0/8", .proto = IPPROTO_TCP, .dport_lo = 53, .target = MVT_DNAT, .dnat_to = "1.1.1.1") };
    run(AF_INET, "nat", "PREROUTING", nat, 2, "v4 nat PREROUTING (DNAT 1.1.1.1)");
    puts(fails ? "SOME FAILED" : "ALL LIVE CHECKS PASSED");
    return fails != 0;
}
