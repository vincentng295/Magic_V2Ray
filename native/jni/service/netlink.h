/* rtnetlink client: replaces every `ip ...` call in service.sh. */
#ifndef MV_NETLINK_H
#define MV_NETLINK_H
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

typedef struct {
    int family;                 /* AF_INET / AF_INET6 */
    uint8_t action;             /* FR_ACT_* ; 0 = unspecified (delete by other keys) */
    uint32_t pref;              /* 0 = unspecified */
    int table;                  /* -1 = none */
    bool has_mark; uint32_t mark;
    const char *iif;
    const void *src, *dst; uint8_t src_len, dst_len;
    int suppress_plen;          /* -1 = none */
    uint32_t goto_pref;
} nl_rule;
#define NL_RULE(fam) { .family = (fam), .table = -1, .suppress_plen = -1 }

int  nl_rule_add(const nl_rule *r);        /* 0 / -errno (EEXIST is returned, caller may ignore) */
int  nl_rule_del(const nl_rule *r);        /* removes ONE matching rule */
int  nl_rule_del_all(const nl_rule *r);    /* removes all, returns how many */

int  nl_route_replace_dev(int family, uint32_t table, const char *dev,
                          const void *prefsrc, uint32_t metric);
int  nl_route_blackhole(int family, uint32_t table, uint32_t metric);
int  nl_route_del_dev(int family, const void *dst, int plen, const char *dev); /* main table */
int  nl_route_flush_table(int family, uint32_t table);

enum { NL_ADDR_NODAD = 1, NL_ADDR_NOPREFIXROUTE = 2 };
int  nl_addr_add(int family, const char *dev, const void *addr, int plen, int flags, bool replace);
int  nl_addr_del(int family, const char *dev, const void *addr, int plen);
bool nl_addr_has(int family, const char *dev, const void *addr, int plen);
bool nl_addr_first_v4(const char *dev, char *out, size_t cap);
/* Text laid out like `ip addr show <dev>`; malloc'd, NULL on failure. */
char *nl_addr_show(const char *dev);

/* Default-network lookup (replaces `ip rule show | grep | sed`). Returns the
 * table id of netd's "fwmark 0x0/0xffff iif lo lookup <tbl>" rule, or -1. */
int  nl_default_table(void);

/* Route/rule change notifications (replaces `ip monitor route rule`). */
int  nl_monitor_open(void);

int  link_set_mtu(const char *dev, int mtu);
int  link_set_up(const char *dev, bool up);
#endif
