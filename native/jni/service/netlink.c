#include "netlink.h"
#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/fib_rules.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef IFA_FLAGS
#define IFA_FLAGS 8
#endif
#ifndef IFA_F_NOPREFIXROUTE
#define IFA_F_NOPREFIXROUTE 0x200
#endif
#ifndef SOL_NETLINK
#define SOL_NETLINK 270
#endif
#ifndef NETLINK_ADD_MEMBERSHIP
#define NETLINK_ADD_MEMBERSHIP 1
#endif
#define RT_TABLES "/data/misc/net/rt_tables"

static unsigned g_seq;

static int nl_open(void)
{
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (fd < 0) return -errno;
    struct sockaddr_nl sa = { .nl_family = AF_NETLINK };
    struct timeval tv = { 5, 0 };
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0) { int e = -errno; close(fd); return e; }
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    return fd;
}

static int addattr(struct nlmsghdr *n, size_t max, int type, const void *d, int len)
{
    int rl = RTA_LENGTH(len);
    if (NLMSG_ALIGN(n->nlmsg_len) + RTA_ALIGN(rl) > max) return -EMSGSIZE;
    struct rtattr *a = (struct rtattr *)((char *)n + NLMSG_ALIGN(n->nlmsg_len));
    a->rta_type = type; a->rta_len = rl;
    if (len) memcpy(RTA_DATA(a), d, len);
    n->nlmsg_len = NLMSG_ALIGN(n->nlmsg_len) + RTA_ALIGN(rl);
    return 0;
}
static int addattr32(struct nlmsghdr *n, size_t max, int type, uint32_t v) { return addattr(n, max, type, &v, 4); }
static int addattr_str(struct nlmsghdr *n, size_t max, int type, const char *s) { return addattr(n, max, type, s, strlen(s) + 1); }

static void parse_rta(struct rtattr **tb, int max, struct rtattr *rta, int len)
{
    memset(tb, 0, sizeof(*tb) * (max + 1));
    for (; RTA_OK(rta, len); rta = RTA_NEXT(rta, len))
        if (rta->rta_type <= max) tb[rta->rta_type] = rta;
}

/* Send a request that wants an ACK. 0 or -errno. */
static int nl_talk(struct nlmsghdr *n)
{
    int fd = nl_open(); if (fd < 0) return fd;
    struct sockaddr_nl dst = { .nl_family = AF_NETLINK };
    char buf[8192]; int err = -EIO;
    n->nlmsg_flags |= NLM_F_REQUEST | NLM_F_ACK;
    n->nlmsg_seq = ++g_seq;
    if (sendto(fd, n, n->nlmsg_len, 0, (struct sockaddr *)&dst, sizeof dst) < 0) { err = -errno; goto out; }
    for (;;) {
        ssize_t r = recv(fd, buf, sizeof buf, 0);
        if (r < 0) { if (errno == EINTR) continue; err = -errno; goto out; }
        for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, (size_t)r); h = NLMSG_NEXT(h, r))
            if (h->nlmsg_type == NLMSG_ERROR) { err = ((struct nlmsgerr *)NLMSG_DATA(h))->error; goto out; }
    }
out:
    close(fd);
    return err;
}

typedef int (*dump_cb)(struct nlmsghdr *, void *);

/* Dump request; callback returns nonzero to stop early. */
static int nl_dump(struct nlmsghdr *n, dump_cb cb, void *arg)
{
    int fd = nl_open(); if (fd < 0) return fd;
    struct sockaddr_nl dst = { .nl_family = AF_NETLINK };
    size_t cap = 65536; char *buf = malloc(cap); int err = 0;
    if (!buf) { close(fd); return -ENOMEM; }
    n->nlmsg_flags |= NLM_F_REQUEST | NLM_F_DUMP;
    n->nlmsg_seq = ++g_seq;
    if (sendto(fd, n, n->nlmsg_len, 0, (struct sockaddr *)&dst, sizeof dst) < 0) { err = -errno; goto out; }
    for (;;) {
        ssize_t r = recv(fd, buf, cap, 0);
        if (r < 0) { if (errno == EINTR) continue; err = -errno; goto out; }
        for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, (size_t)r); h = NLMSG_NEXT(h, r)) {
            if (h->nlmsg_type == NLMSG_DONE) goto out;
            if (h->nlmsg_type == NLMSG_ERROR) { err = ((struct nlmsgerr *)NLMSG_DATA(h))->error; goto out; }
            if (cb(h, arg)) goto out;
        }
    }
out:
    free(buf); close(fd);
    return err;
}

/* ------------------------------------------------------------------ rules */

static int rule_msg(int cmd, const nl_rule *r)
{
    struct { struct nlmsghdr n; struct fib_rule_hdr f; char buf[512]; } q;
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.f);
    q.n.nlmsg_type = cmd;
    if (cmd == RTM_NEWRULE) q.n.nlmsg_flags = NLM_F_CREATE | NLM_F_EXCL;
    q.f.family = r->family;
    q.f.action = r->action;
    size_t m = sizeof q;
    if (r->table >= 0) {
        q.f.table = r->table < 256 ? r->table : 0;
        addattr32(&q.n, m, FRA_TABLE, r->table);
        if (!q.f.action) q.f.action = FR_ACT_TO_TBL;
    }
    if (r->pref) addattr32(&q.n, m, FRA_PRIORITY, r->pref);
    if (r->has_mark) addattr32(&q.n, m, FRA_FWMARK, r->mark);
    if (r->iif) addattr_str(&q.n, m, FRA_IIFNAME, r->iif);
    int al = r->family == AF_INET ? 4 : 16;
    if (r->src) { q.f.src_len = r->src_len; addattr(&q.n, m, FRA_SRC, r->src, al); }
    if (r->dst) { q.f.dst_len = r->dst_len; addattr(&q.n, m, FRA_DST, r->dst, al); }
    if (r->suppress_plen >= 0) addattr32(&q.n, m, FRA_SUPPRESS_PREFIXLEN, r->suppress_plen);
    if (r->action == FR_ACT_GOTO) addattr32(&q.n, m, FRA_GOTO, r->goto_pref);
    return nl_talk(&q.n);
}

int nl_rule_add(const nl_rule *r) { return rule_msg(RTM_NEWRULE, r); }
int nl_rule_del(const nl_rule *r) { return rule_msg(RTM_DELRULE, r); }
int nl_rule_del_all(const nl_rule *r)
{
    int n = 0;
    while (rule_msg(RTM_DELRULE, r) == 0 && n < 4096) n++;
    return n;
}

/* ----------------------------------------------------------------- routes */

static int route_msg(int cmd, int flags, int family, uint32_t table, uint8_t type, uint8_t scope,
                     const char *dev, const void *dst, int plen, const void *prefsrc, uint32_t metric)
{
    struct { struct nlmsghdr n; struct rtmsg r; char buf[512]; } q;
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.r);
    q.n.nlmsg_type = cmd; q.n.nlmsg_flags = flags;
    q.r.rtm_family = family; q.r.rtm_dst_len = plen;
    q.r.rtm_table = table < 256 ? table : RT_TABLE_UNSPEC;
    q.r.rtm_protocol = cmd == RTM_DELROUTE ? 0 : RTPROT_BOOT;
    q.r.rtm_scope = scope; q.r.rtm_type = type;
    size_t m = sizeof q; int al = family == AF_INET ? 4 : 16;
    if (dst) addattr(&q.n, m, RTA_DST, dst, al);
    if (dev) {
        unsigned idx = if_nametoindex(dev);
        if (!idx) return -ENODEV;
        addattr32(&q.n, m, RTA_OIF, idx);
    }
    if (prefsrc) addattr(&q.n, m, RTA_PREFSRC, prefsrc, al);
    if (metric) addattr32(&q.n, m, RTA_PRIORITY, metric);
    addattr32(&q.n, m, RTA_TABLE, table);
    return nl_talk(&q.n);
}

int nl_route_replace_dev(int family, uint32_t table, const char *dev, const void *prefsrc, uint32_t metric)
{
    return route_msg(RTM_NEWROUTE, NLM_F_CREATE | NLM_F_REPLACE, family, table, RTN_UNICAST,
                     RT_SCOPE_LINK, dev, NULL, 0, prefsrc, metric);
}
int nl_route_blackhole(int family, uint32_t table, uint32_t metric)
{
    return route_msg(RTM_NEWROUTE, NLM_F_CREATE | NLM_F_REPLACE, family, table, RTN_BLACKHOLE,
                     RT_SCOPE_UNIVERSE, NULL, NULL, 0, NULL, metric);
}
int nl_route_del_dev(int family, const void *dst, int plen, const char *dev)
{
    return route_msg(RTM_DELROUTE, 0, family, RT_TABLE_MAIN, RTN_UNICAST, RT_SCOPE_NOWHERE,
                     dev, dst, plen, NULL, 0);
}

struct rt_item { struct rtmsg h; uint8_t dst[16]; bool has_dst, has_gw; uint8_t gw[16];
                 uint32_t oif, prio, table; };
struct rt_list { int family; uint32_t table; struct rt_item *v; int n, cap; };

static int route_cb(struct nlmsghdr *h, void *arg)
{
    struct rt_list *L = arg;
    if (h->nlmsg_type != RTM_NEWROUTE) return 0;
    struct rtmsg *r = NLMSG_DATA(h);
    struct rtattr *tb[RTA_MAX + 1];
    parse_rta(tb, RTA_MAX, RTM_RTA(r), RTM_PAYLOAD(h));
    uint32_t t = tb[RTA_TABLE] ? *(uint32_t *)RTA_DATA(tb[RTA_TABLE]) : r->rtm_table;
    if (t != L->table || r->rtm_family != L->family) return 0;
    if (L->n == L->cap) {
        int nc = L->cap ? L->cap * 2 : 16;
        struct rt_item *nv = realloc(L->v, nc * sizeof *nv);
        if (!nv) return 1;
        L->v = nv; L->cap = nc;
    }
    struct rt_item *it = &L->v[L->n++];
    memset(it, 0, sizeof *it);
    it->h = *r; it->table = t;
    int al = r->rtm_family == AF_INET ? 4 : 16;
    if (tb[RTA_DST]) { memcpy(it->dst, RTA_DATA(tb[RTA_DST]), al); it->has_dst = true; }
    if (tb[RTA_GATEWAY]) { memcpy(it->gw, RTA_DATA(tb[RTA_GATEWAY]), al); it->has_gw = true; }
    if (tb[RTA_OIF]) it->oif = *(uint32_t *)RTA_DATA(tb[RTA_OIF]);
    if (tb[RTA_PRIORITY]) it->prio = *(uint32_t *)RTA_DATA(tb[RTA_PRIORITY]);
    return 0;
}

int nl_route_flush_table(int family, uint32_t table)
{
    struct { struct nlmsghdr n; struct rtmsg r; } q;
    struct rt_list L = { .family = family, .table = table };
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.r);
    q.n.nlmsg_type = RTM_GETROUTE; q.r.rtm_family = family;
    int rc = nl_dump(&q.n, route_cb, &L);
    for (int i = 0; i < L.n; i++) {
        struct rt_item *it = &L.v[i];
        struct { struct nlmsghdr n; struct rtmsg r; char buf[256]; } d;
        memset(&d, 0, sizeof d);
        d.n.nlmsg_len = NLMSG_LENGTH(sizeof d.r);
        d.n.nlmsg_type = RTM_DELROUTE;
        d.r = it->h;
        int al = family == AF_INET ? 4 : 16;
        if (it->has_dst) addattr(&d.n, sizeof d, RTA_DST, it->dst, al);
        if (it->has_gw) addattr(&d.n, sizeof d, RTA_GATEWAY, it->gw, al);
        if (it->oif) addattr32(&d.n, sizeof d, RTA_OIF, it->oif);
        if (it->prio) addattr32(&d.n, sizeof d, RTA_PRIORITY, it->prio);
        addattr32(&d.n, sizeof d, RTA_TABLE, it->table);
        nl_talk(&d.n);
    }
    free(L.v);
    return rc;
}

/* -------------------------------------------------------------- addresses */

int nl_addr_add(int family, const char *dev, const void *addr, int plen, int flags, bool replace)
{
    struct { struct nlmsghdr n; struct ifaddrmsg a; char buf[256]; } q;
    unsigned idx = if_nametoindex(dev);
    if (!idx) return -ENODEV;
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.a);
    q.n.nlmsg_type = RTM_NEWADDR;
    q.n.nlmsg_flags = NLM_F_CREATE | (replace ? NLM_F_REPLACE : NLM_F_EXCL);
    q.a.ifa_family = family; q.a.ifa_prefixlen = plen; q.a.ifa_index = idx;
    int al = family == AF_INET ? 4 : 16;
    addattr(&q.n, sizeof q, IFA_LOCAL, addr, al);
    addattr(&q.n, sizeof q, IFA_ADDRESS, addr, al);
    uint32_t f = 0;
    if (flags & NL_ADDR_NODAD) f |= 0x02;                    /* IFA_F_NODAD */
    if (flags & NL_ADDR_NOPREFIXROUTE) f |= IFA_F_NOPREFIXROUTE;
    if (f) { q.a.ifa_flags = f & 0xff; addattr32(&q.n, sizeof q, IFA_FLAGS, f); }
    return nl_talk(&q.n);
}

int nl_addr_del(int family, const char *dev, const void *addr, int plen)
{
    struct { struct nlmsghdr n; struct ifaddrmsg a; char buf[128]; } q;
    unsigned idx = if_nametoindex(dev);
    if (!idx) return -ENODEV;
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.a);
    q.n.nlmsg_type = RTM_DELADDR;
    q.a.ifa_family = family; q.a.ifa_prefixlen = plen; q.a.ifa_index = idx;
    int al = family == AF_INET ? 4 : 16;
    addattr(&q.n, sizeof q, IFA_LOCAL, addr, al);
    addattr(&q.n, sizeof q, IFA_ADDRESS, addr, al);
    return nl_talk(&q.n);
}

struct addr_q { int family; unsigned idx; const void *addr; int plen; bool found;
                char v4[INET_ADDRSTRLEN]; bool got_v4; };

static int addr_cb(struct nlmsghdr *h, void *arg)
{
    struct addr_q *Q = arg;
    if (h->nlmsg_type != RTM_NEWADDR) return 0;
    struct ifaddrmsg *a = NLMSG_DATA(h);
    struct rtattr *tb[IFA_MAX + 1];
    if (a->ifa_index != Q->idx) return 0;
    parse_rta(tb, IFA_MAX, IFA_RTA(a), IFA_PAYLOAD(h));
    struct rtattr *p = tb[IFA_LOCAL] ? tb[IFA_LOCAL] : tb[IFA_ADDRESS];
    if (!p) return 0;
    if (a->ifa_family == AF_INET && !Q->got_v4) {
        inet_ntop(AF_INET, RTA_DATA(p), Q->v4, sizeof Q->v4); Q->got_v4 = true;
    }
    if (Q->addr && a->ifa_family == Q->family && a->ifa_prefixlen == Q->plen &&
        memcmp(RTA_DATA(p), Q->addr, Q->family == AF_INET ? 4 : 16) == 0) { Q->found = true; return 1; }
    return 0;
}

static int addr_dump(int family, struct addr_q *Q)
{
    struct { struct nlmsghdr n; struct ifaddrmsg a; } q;
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.a);
    q.n.nlmsg_type = RTM_GETADDR; q.a.ifa_family = family;
    return nl_dump(&q.n, addr_cb, Q);
}

bool nl_addr_has(int family, const char *dev, const void *addr, int plen)
{
    struct addr_q Q = { .family = family, .idx = if_nametoindex(dev), .addr = addr, .plen = plen };
    if (!Q.idx) return false;
    addr_dump(family, &Q);
    return Q.found;
}

bool nl_addr_first_v4(const char *dev, char *out, size_t cap)
{
    struct addr_q Q = { .family = AF_INET, .idx = if_nametoindex(dev) };
    if (!Q.idx) return false;
    addr_dump(AF_INET, &Q);
    if (!Q.got_v4) return false;
    snprintf(out, cap, "%s", Q.v4);
    return true;
}

/* ------------------------------------------------- `ip addr show` look-alike */

struct sbuf { char *p; size_t n, cap; };
static void sb_printf(struct sbuf *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
static void sb_printf(struct sbuf *s, const char *fmt, ...)
{
    va_list ap; va_start(ap, fmt);
    for (;;) {
        va_list c; va_copy(c, ap);
        int w = vsnprintf(s->p + s->n, s->cap - s->n, fmt, c);
        va_end(c);
        if (w < 0) break;
        if ((size_t)w < s->cap - s->n) { s->n += w; break; }
        char *np = realloc(s->p, s->cap * 2 + w + 1);
        if (!np) break;
        s->p = np; s->cap = s->cap * 2 + w + 1;
    }
    va_end(ap);
}

struct show_ctx { unsigned idx; struct sbuf *s; int have_link; };

static void mac_str(char *o, size_t n, const uint8_t *m, int len)
{
    o[0] = 0;
    for (int i = 0; i < len && (size_t)(i * 3 + 3) < n; i++)
        sprintf(o + i * 3, i ? ":%02x" : "%02x", m[i]), (void)0;
}

static int link_cb(struct nlmsghdr *h, void *arg)
{
    struct show_ctx *C = arg;
    if (h->nlmsg_type != RTM_NEWLINK) return 0;
    struct ifinfomsg *i = NLMSG_DATA(h);
    if ((unsigned)i->ifi_index != C->idx) return 0;
    struct rtattr *tb[IFLA_MAX + 1];
    parse_rta(tb, IFLA_MAX, IFLA_RTA(i), IFLA_PAYLOAD(h));
    static const struct { unsigned f; const char *n; } F[] = {
        {IFF_LOOPBACK,"LOOPBACK"},{IFF_BROADCAST,"BROADCAST"},{IFF_POINTOPOINT,"POINTOPOINT"},
        {IFF_MULTICAST,"MULTICAST"},{IFF_NOARP,"NOARP"},{IFF_ALLMULTI,"ALLMULTI"},{IFF_PROMISC,"PROMISC"},
        {IFF_MASTER,"MASTER"},{IFF_SLAVE,"SLAVE"},{IFF_DEBUG,"DEBUG"},{IFF_DYNAMIC,"DYNAMIC"},
        {IFF_AUTOMEDIA,"AUTOMEDIA"},{IFF_PORTSEL,"PORTSEL"},{IFF_NOTRAILERS,"NOTRAILERS"},{IFF_UP,"UP"},
        {0x10000,"LOWER_UP"},{0x20000,"DORMANT"},{0x40000,"ECHO"} };
    static const char *ST[] = {"UNKNOWN","NOTPRESENT","DOWN","LOWERLAYERDOWN","TESTING","DORMANT","UP"};
    char fl[256] = ""; 
    for (size_t k = 0; k < sizeof F / sizeof *F; k++)
        if (i->ifi_flags & F[k].f) { if (*fl) strcat(fl, ","); strcat(fl, F[k].n); }
    int st = tb[IFLA_OPERSTATE] ? *(uint8_t *)RTA_DATA(tb[IFLA_OPERSTATE]) : 0;
    struct sbuf *s = C->s;
    sb_printf(s, "%d: %s: <%s> mtu %u", i->ifi_index,
              tb[IFLA_IFNAME] ? (char *)RTA_DATA(tb[IFLA_IFNAME]) : "?", fl,
              tb[IFLA_MTU] ? *(uint32_t *)RTA_DATA(tb[IFLA_MTU]) : 0);
    if (tb[IFLA_QDISC]) sb_printf(s, " qdisc %s", (char *)RTA_DATA(tb[IFLA_QDISC]));
    sb_printf(s, " state %s group default", st < 7 ? ST[st] : "UNKNOWN");
    if (tb[IFLA_TXQLEN]) sb_printf(s, " qlen %u", *(uint32_t *)RTA_DATA(tb[IFLA_TXQLEN]));
    sb_printf(s, "\n");
    if (i->ifi_type == ARPHRD_ETHER || i->ifi_type == ARPHRD_LOOPBACK) {
        char m[64] = "", b[64] = "";
        if (tb[IFLA_ADDRESS]) mac_str(m, sizeof m, RTA_DATA(tb[IFLA_ADDRESS]), RTA_PAYLOAD(tb[IFLA_ADDRESS]));
        if (tb[IFLA_BROADCAST]) mac_str(b, sizeof b, RTA_DATA(tb[IFLA_BROADCAST]), RTA_PAYLOAD(tb[IFLA_BROADCAST]));
        sb_printf(s, "    link/%s %s brd %s\n", i->ifi_type == ARPHRD_ETHER ? "ether" : "loopback", m, b);
    } else sb_printf(s, "    link/none\n");
    C->have_link = 1;
    return 1;
}

static int show_addr_cb(struct nlmsghdr *h, void *arg)
{
    struct show_ctx *C = arg; struct sbuf *s = C->s;
    if (h->nlmsg_type != RTM_NEWADDR) return 0;
    struct ifaddrmsg *a = NLMSG_DATA(h);
    if (a->ifa_index != C->idx) return 0;
    struct rtattr *tb[IFA_MAX + 1];
    parse_rta(tb, IFA_MAX, IFA_RTA(a), IFA_PAYLOAD(h));
    struct rtattr *p = tb[IFA_LOCAL] ? tb[IFA_LOCAL] : tb[IFA_ADDRESS];
    if (!p) return 0;
    char ab[INET6_ADDRSTRLEN], bb[INET_ADDRSTRLEN];
    inet_ntop(a->ifa_family, RTA_DATA(p), ab, sizeof ab);
    const char *sc = a->ifa_scope == RT_SCOPE_HOST ? "host" : a->ifa_scope == RT_SCOPE_LINK ? "link" :
                     a->ifa_scope == RT_SCOPE_UNIVERSE ? "global" : "site";
    uint32_t fl = tb[IFA_FLAGS] ? *(uint32_t *)RTA_DATA(tb[IFA_FLAGS]) : a->ifa_flags;
    if (a->ifa_family == AF_INET) {
        sb_printf(s, "    inet %s/%d", ab, a->ifa_prefixlen);
        if (tb[IFA_BROADCAST]) { inet_ntop(AF_INET, RTA_DATA(tb[IFA_BROADCAST]), bb, sizeof bb); sb_printf(s, " brd %s", bb); }
        sb_printf(s, " scope %s", sc);
        if (tb[IFA_LABEL]) sb_printf(s, " %s", (char *)RTA_DATA(tb[IFA_LABEL]));
    } else {
        sb_printf(s, "    inet6 %s/%d scope %s", ab, a->ifa_prefixlen, sc);
        if (fl & 0x200) sb_printf(s, " noprefixroute");
        if (fl & 0x01) sb_printf(s, " temporary");
        if (fl & 0x08) sb_printf(s, " tentative");
        if (fl & 0x20) sb_printf(s, " deprecated");
    }
    sb_printf(s, " \n");
    struct ifa_cacheinfo *ci = tb[IFA_CACHEINFO] ? RTA_DATA(tb[IFA_CACHEINFO]) : NULL;
    char vl[32] = "forever", pl[32] = "forever";
    if (ci && ci->ifa_valid != 0xFFFFFFFFu) snprintf(vl, sizeof vl, "%usec", ci->ifa_valid);
    if (ci && ci->ifa_prefered != 0xFFFFFFFFu) snprintf(pl, sizeof pl, "%usec", ci->ifa_prefered);
    sb_printf(s, "       valid_lft %s preferred_lft %s\n", vl, pl);
    return 0;
}

char *nl_addr_show(const char *dev)
{
    unsigned idx = dev ? if_nametoindex(dev) : 0;
    if (!idx) return NULL;
    struct sbuf s = { malloc(1024), 0, 1024 };
    if (!s.p) return NULL;
    s.p[0] = 0;
    struct show_ctx C = { idx, &s, 0 };
    struct { struct nlmsghdr n; struct ifinfomsg i; } lq;
    memset(&lq, 0, sizeof lq);
    lq.n.nlmsg_len = NLMSG_LENGTH(sizeof lq.i); lq.n.nlmsg_type = RTM_GETLINK;
    nl_dump(&lq.n, link_cb, &C);
    if (!C.have_link) { free(s.p); return NULL; }
    struct { struct nlmsghdr n; struct ifaddrmsg a; } aq;
    memset(&aq, 0, sizeof aq);
    aq.n.nlmsg_len = NLMSG_LENGTH(sizeof aq.a); aq.n.nlmsg_type = RTM_GETADDR;
    nl_dump(&aq.n, show_addr_cb, &C);
    return s.p;
}

/* ------------------------------------------------ default network lookup */

struct def_q { int table; };

static int def_cb(struct nlmsghdr *h, void *arg)
{
    struct def_q *Q = arg;
    if (h->nlmsg_type != RTM_NEWRULE) return 0;
    struct fib_rule_hdr *f = NLMSG_DATA(h);
    struct rtattr *tb[FRA_MAX + 1];
    parse_rta(tb, FRA_MAX, (struct rtattr *)((char *)f + NLMSG_ALIGN(sizeof *f)), NLMSG_PAYLOAD(h, sizeof *f));
    if (f->action != FR_ACT_TO_TBL) return 0;
    if (!tb[FRA_FWMARK] || *(uint32_t *)RTA_DATA(tb[FRA_FWMARK]) != 0) return 0;
    if (!tb[FRA_FWMASK] || *(uint32_t *)RTA_DATA(tb[FRA_FWMASK]) != 0xffff) return 0;
    if (!tb[FRA_IIFNAME] || strcmp(RTA_DATA(tb[FRA_IIFNAME]), "lo") != 0) return 0;
    if (tb[FRA_OIFNAME]) return 0;
    if (tb[FRA_UID_RANGE]) {      /* narrower per-app ranges are not the system default */
        struct fib_rule_uid_range *u = RTA_DATA(tb[FRA_UID_RANGE]);
        if (u->start != 0 || u->end != 2147483647u) return 0;
    }
    Q->table = tb[FRA_TABLE] ? (int)*(uint32_t *)RTA_DATA(tb[FRA_TABLE]) : f->table;
    return 1;                      /* first match wins (dump is priority-ordered) */
}

int nl_default_table(void)
{
    struct { struct nlmsghdr n; struct fib_rule_hdr f; } q;
    struct def_q Q = { -1 };
    memset(&q, 0, sizeof q);
    q.n.nlmsg_len = NLMSG_LENGTH(sizeof q.f); q.n.nlmsg_type = RTM_GETRULE; q.f.family = AF_INET;
    nl_dump(&q.n, def_cb, &Q);
    return Q.table;
}

/* ---------------------------------------------------------------- monitor */

int nl_monitor_open(void)
{
    int fd = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, NETLINK_ROUTE);
    if (fd < 0) return -errno;
    struct sockaddr_nl sa = { .nl_family = AF_NETLINK };
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0) { int e = -errno; close(fd); return e; }
    int groups[] = { RTNLGRP_IPV4_ROUTE, RTNLGRP_IPV4_RULE, RTNLGRP_IPV6_ROUTE, RTNLGRP_IPV6_RULE };
    for (size_t i = 0; i < sizeof groups / sizeof *groups; i++)
        setsockopt(fd, SOL_NETLINK, NETLINK_ADD_MEMBERSHIP, &groups[i], sizeof groups[i]);
    return fd;
}

/* ------------------------------------------------------------------ links */

int link_set_mtu(const char *dev, int mtu)
{
    struct ifreq ifr; int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0), rc = 0;
    if (s < 0) return -errno;
    memset(&ifr, 0, sizeof ifr);
    snprintf(ifr.ifr_name, IFNAMSIZ, "%s", dev); ifr.ifr_mtu = mtu;
    if (ioctl(s, SIOCSIFMTU, &ifr) < 0) rc = -errno;
    close(s);
    return rc;
}

int link_set_up(const char *dev, bool up)
{
    struct ifreq ifr; int s = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0), rc = 0;
    if (s < 0) return -errno;
    memset(&ifr, 0, sizeof ifr);
    snprintf(ifr.ifr_name, IFNAMSIZ, "%s", dev);
    if (ioctl(s, SIOCGIFFLAGS, &ifr) < 0) { rc = -errno; goto out; }
    if (up) ifr.ifr_flags |= IFF_UP; else ifr.ifr_flags &= ~IFF_UP;
    if (ioctl(s, SIOCSIFFLAGS, &ifr) < 0) rc = -errno;
out:
    close(s);
    return rc;
}
