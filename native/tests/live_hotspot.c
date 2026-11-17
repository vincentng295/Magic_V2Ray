/* Real kernel (root, run inside `unshare -n`): the hotspot rule shapes of traffic.c count a forwarded client's packets
 * exactly once in each direction, with no tun involved. Prints the rule counters; the shell driver compares them with
 * the interface byte counters. */
#include "../jni/service/ipt.h"
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
int main(int argc, char **argv)
{
    const char *ip = "10.9.0.2";
    ipt_tx *t = ipt_tx_begin(AF_INET, "mangle"); if (!t) { puts("no table"); return 1; }
    if (argc > 1 && !strcmp(argv[1], "install")) {
        (void)ipt_new_chain(t, "MV2R_HS_UP"); (void)ipt_new_chain(t, "MV2R_HS_DN");
        (void)ipt_insert(t, "PREROUTING", 1, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV2R_HS_UP"));
        (void)ipt_insert(t, "POSTROUTING", 1, &IPT_SPEC(.target = MVT_JUMP, .jump_chain = "MV2R_HS_DN"));
        (void)ipt_append(t, "MV2R_HS_UP", &IPT_SPEC(.in_if = "ap0", .src = ip, .target = MVT_COUNT));
        (void)ipt_append(t, "MV2R_HS_DN", &IPT_SPEC(.out_if = "ap0", .dst = ip, .target = MVT_COUNT));
        int rc = ipt_tx_commit(t); printf("install rc=%d\n", rc); return rc < 0;
    }
    ipt_rule_stat st[8];
    int n = ipt_chain_stats(t, "MV2R_HS_UP", st, 8);
    for (int i = 0; i < n; i++) printf("UP %s %llu %llu\n", st[i].src, (unsigned long long)st[i].pkts, (unsigned long long)st[i].bytes);
    n = ipt_chain_stats(t, "MV2R_HS_DN", st, 8);
    for (int i = 0; i < n; i++) printf("DN %s %llu %llu\n", st[i].dst, (unsigned long long)st[i].pkts, (unsigned long long)st[i].bytes);
    ipt_tx_abort(t); return 0;
}
