/* Minimal native xtables client (legacy IPT/IP6T socket interface).
 * Replaces iptables/ip6tables: a table is loaded once, edited in memory
 * through a transaction, and swapped into the kernel with ONE
 * IPT_SO_SET_REPLACE - so a whole rule set lands atomically. */
#ifndef MV_IPT_H
#define MV_IPT_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

typedef enum { MVT_ACCEPT = 1, MVT_DROP, MVT_RETURN, MVT_JUMP, MVT_MARK, MVT_REJECT,
               MVT_TCPMSS, MVT_DNAT,
               MVT_COUNT /* no target: counter-only rule, the packet keeps walking the chain */ } mv_target_t;
enum { MVR_DEFAULT = 0, MVR_TCP_RESET, MVR_6_NO_ROUTE };

typedef struct ipt_spec {
    const char *in_if, *out_if;          /* trailing '+' = wildcard, as in iptables */
    bool in_inv, out_inv;
    int proto;                           /* 0 = any */
    const char *src, *dst;               /* "addr[/len]" */
    bool src_inv, dst_inv;
    int dport_lo, dport_hi;              /* -1 = no port match (tcp/udp only) */
    int tcp_flags_mask, tcp_flags_cmp;   /* -1 = none ("--tcp-flags MASK CMP") */
    bool mark_match, mark_inv; uint32_t mark_val, mark_mask;   /* -m mark */
    bool owner, owner_inv; uint32_t uid_lo, uid_hi;            /* -m owner --uid-owner lo[-hi] */
    const char *mac; bool mac_inv;       /* -m mac --mac-source aa:bb:cc:dd:ee:ff (PREROUTING / FORWARD / INPUT only) */
    bool quota_on; uint64_t quota;       /* -m quota --quota N: matches while bytes are left, then stops matching */
    mv_target_t target;
    const char *jump_chain;              /* MVT_JUMP */
    uint32_t set_mark, set_mask;         /* MVT_MARK: --set-xmark value[/mask] (mask 0 => 0xffffffff) */
    int reject_with;                     /* IPT_REJECT_* */
    uint16_t mss;                        /* MVT_TCPMSS */
    const char *dnat_to;                 /* MVT_DNAT, IPv4 address */
} ipt_spec;

#define IPT_SPEC(...) ((ipt_spec){ .dport_lo = -1, .dport_hi = -1, .tcp_flags_mask = -1, \
                                   .tcp_flags_cmp = -1, __VA_ARGS__ })

typedef struct ipt_tx ipt_tx;

/* Begin: takes the process-wide xtables lock and loads the table. NULL on error. */
ipt_tx *ipt_tx_begin(int family /*AF_INET|AF_INET6*/, const char *table);
/* Commit pushes every change in one kernel call (retrying on EAGAIN against a
 * fresh copy of the table). Always releases the transaction. 0 or -errno. */
int  ipt_tx_commit(ipt_tx *t);
void ipt_tx_abort(ipt_tx *t);
/* Explains why loading a table failed with EPROTO (which invariant broke, plus the
 * table geometry). Always fills `out`. */
int  ipt_diagnose(int family, const char *table, char *out, size_t cap);

/* Per-op results are -errno-like: -EEXIST, -ENOENT, -ENOTEMPTY, -EMFILE, -EINVAL.
 * Failures leave the table untouched, matching how service.sh ignores them. */
int ipt_new_chain(ipt_tx *t, const char *chain);
int ipt_flush_chain(ipt_tx *t, const char *chain);
int ipt_delete_chain(ipt_tx *t, const char *chain);
int ipt_append(ipt_tx *t, const char *chain, const ipt_spec *s);
int ipt_insert(ipt_tx *t, const char *chain, int pos1, const ipt_spec *s);  /* -I chain pos */
int ipt_delete(ipt_tx *t, const char *chain, const ipt_spec *s);            /* -D, first match */
int ipt_check(ipt_tx *t, const char *chain, const ipt_spec *s);             /* -C: 0 if present */
/* -Z: zero the counters of every rule in the chain (applied by the commit). */
int ipt_zero_chain(ipt_tx *t, const char *chain);

/* Collapse identical rules: every group of equal rules in the chain becomes ONE (the one with the biggest counter
 * survives). 0 if something was removed, -ENOENT otherwise. A counter-only rule counts every packet that passes it,
 * so a duplicate multiplies the number it reports. */
int ipt_dedup_chain(ipt_tx *t, const char *chain);
/* Same, for the copies of one rule only (e.g. our jump in a builtin chain that holds foreign rules too). */
int ipt_dedup_rule(ipt_tx *t, const char *chain, const ipt_spec *s);

/* -Z for the rules of one source address only (`src` is an address; v4 / v6 as the transaction's family). */
int ipt_zero_src(ipt_tx *t, const char *chain, const char *src);
/* Same for rules matching this destination address. */
int ipt_zero_dst(ipt_tx *t, const char *chain, const char *dst);
/* Removes every rule of the chain whose source (dst=false) or destination (dst=true) address is a multicast group
 * (224.0.0.0/4, ff00::/8) or the limited broadcast. 0, or -ENOENT when there was none. */
int ipt_purge_group(ipt_tx *t, const char *chain, bool dst);
/* Removes every rule of the chain that matches this source MAC. 0, or -ENOENT when there was none. */
int ipt_delete_mac(ipt_tx *t, const char *chain, const char *mac);
/* One entry per distinct MAC found in the chain's rules (rules with a "mac" match). */
typedef struct { char mac[18]; bool drop; bool quota; uint64_t quota_left; } ipt_mac_stat;
int ipt_chain_macs(ipt_tx *t, const char *chain, ipt_mac_stat *out, int max);

/* Counters as they were when the table was loaded (or 0 for rules added in this transaction). */
typedef struct { char src[64], dst[64]; bool any, any_dst; uint64_t pkts, bytes; } ipt_rule_stat;   /* any / any_dst: no source / destination match */
/* Fills up to `max` entries in chain order; returns the number of rules in the chain (may exceed
 * `max`), or -ENOENT. `out` may be NULL with max 0 to just count. */
int ipt_chain_stats(ipt_tx *t, const char *chain, ipt_rule_stat *out, int max);

/* ---- backend seam (tests substitute a fake kernel) ---- */
typedef struct {
    uint32_t valid_hooks, hook_entry[5], underflow[5], num_entries, size;
    uint8_t *entries;                    /* malloc'd, `size` bytes */
    /* Written by ipt_tx_commit for store(): for every entry of the NEW image the ordinal of the entry
     * it was loaded as (-1 = brand new or zeroed). The kernel resets all counters on a table replace;
     * a real backend hands the old ones back through IPT_SO_SET_ADD_COUNTERS using this map.
     * malloc'd, num_entries items; NULL in images produced by load(). */
    int32_t *ctr_map;
} ipt_image;
typedef struct {
    int (*load)(int family, const char *table, ipt_image *out);
    int (*store)(int family, const char *table, const ipt_image *in, uint32_t old_entries);
} ipt_backend;
void ipt_set_backend(const ipt_backend *b);
#endif
