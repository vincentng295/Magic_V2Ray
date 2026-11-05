#include "ipt.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <unistd.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter/xt_tcpudp.h>
#include <linux/netfilter/xt_mark.h>
#include <linux/netfilter/xt_owner.h>
/* NDK's kernel headers omit xt_TCPMSS.h (it collides with xt_tcpmss.h on
 * case-insensitive filesystems). The target payload is just this (stable ABI): */
struct xt_tcpmss_info { uint16_t mss; };
#include <linux/netfilter_ipv4/ip_tables.h>
#include <linux/netfilter_ipv4/ipt_REJECT.h>
#include <linux/netfilter_ipv6/ip6_tables.h>
#include <linux/netfilter_ipv6/ip6t_REJECT.h>

/* Android's iptables takes this lock around every change; so do we, to stay
 * serialised with netd. Verify the path on your ROM; if absent we skip it. */
#ifndef MV_XTABLES_LOCK
#define MV_XTABLES_LOCK "/system/etc/xtables.lock"
#endif

#define V_ACCEPT (-2)
#define V_DROP   (-1)
#define V_RETURN (-5)

typedef struct {
    int family, level, domain;
    size_t hdr, ipsz, o_target, o_next, o_get_entries, o_ent_replace;
} fam_t;

static const fam_t F4 = { AF_INET, IPPROTO_IP, AF_INET, sizeof(struct ipt_entry), sizeof(struct ipt_ip),
    offsetof(struct ipt_entry, target_offset), offsetof(struct ipt_entry, next_offset), 0, 0 };
static const fam_t F6 = { AF_INET6, IPPROTO_IPV6, AF_INET6, sizeof(struct ip6t_entry), sizeof(struct ip6t_ip6),
    offsetof(struct ip6t_entry, target_offset), offsetof(struct ip6t_entry, next_offset), 0, 0 };

static const char *HOOKS[5] = { "PREROUTING", "INPUT", "FORWARD", "OUTPUT", "POSTROUTING" };
#define STD_SZ XT_ALIGN(sizeof(struct xt_standard_target))
#define ERR_SZ XT_ALIGN(sizeof(struct xt_error_target))

/* ======================================================= global locking === */

static pthread_mutex_t g_mu;
static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static int g_depth = -1, g_lockfd = -1;

static void mu_init(void)
{
    pthread_mutexattr_t a;
    pthread_mutexattr_init(&a);
    pthread_mutexattr_settype(&a, PTHREAD_MUTEX_RECURSIVE);   /* one thread may hold v4+v6 txs */
    pthread_mutex_init(&g_mu, &a);
}

static void glock(void)
{
    pthread_once(&g_once, mu_init);
    pthread_mutex_lock(&g_mu);
    if (++g_depth == 0) {
        g_lockfd = open(MV_XTABLES_LOCK, O_RDONLY | O_CLOEXEC);
        if (g_lockfd >= 0) flock(g_lockfd, LOCK_EX);
    }
}
static void gunlock(void)
{
    if (g_depth-- == 0 && g_lockfd >= 0) { flock(g_lockfd, LOCK_UN); close(g_lockfd); g_lockfd = -1; }
    pthread_mutex_unlock(&g_mu);
}

/* ============================================================== backend === */

static int be_load(int family, const char *table, ipt_image *im)
{
    const fam_t *f = family == AF_INET ? &F4 : &F6;
    int fd = socket(f->domain, SOCK_RAW | SOCK_CLOEXEC, IPPROTO_RAW), rc = 0;
    if (fd < 0) return -errno;
    struct ipt_getinfo info; socklen_t len = sizeof info;
    memset(&info, 0, sizeof info);
    snprintf(info.name, sizeof info.name, "%s", table);
    if (getsockopt(fd, f->level, IPT_SO_GET_INFO, &info, &len) < 0) { rc = -errno; goto out; }
    size_t hdr = family == AF_INET ? sizeof(struct ipt_get_entries) : sizeof(struct ip6t_get_entries);
    size_t alloc = hdr + info.size;
    uint8_t *buf = calloc(1, alloc);
    if (!buf) { rc = -ENOMEM; goto out; }
    snprintf((char *)buf, XT_TABLE_MAXNAMELEN, "%s", table);
    memcpy(buf + XT_TABLE_MAXNAMELEN, &info.size, 4);          /* struct ipt_get_entries.size */
    len = alloc;
    if (getsockopt(fd, f->level, IPT_SO_GET_ENTRIES, buf, &len) < 0) { rc = -errno; free(buf); goto out; }
    im->valid_hooks = info.valid_hooks; im->num_entries = info.num_entries; im->size = info.size;
    memcpy(im->hook_entry, info.hook_entry, sizeof im->hook_entry);
    memcpy(im->underflow, info.underflow, sizeof im->underflow);
    im->entries = malloc(info.size ? info.size : 1);
    if (!im->entries) { rc = -ENOMEM; free(buf); goto out; }
    memcpy(im->entries, buf + hdr, info.size);
    free(buf);
out:
    close(fd);
    return rc;
}

static int be_store(int family, const char *table, const ipt_image *im, uint32_t old_entries)
{
    const fam_t *f = family == AF_INET ? &F4 : &F6;
    int fd = socket(f->domain, SOCK_RAW | SOCK_CLOEXEC, IPPROTO_RAW), rc = 0;
    if (fd < 0) return -errno;
    size_t hdr = family == AF_INET ? sizeof(struct ipt_replace) : sizeof(struct ip6t_replace);
    uint8_t *buf = calloc(1, hdr + im->size);
    struct xt_counters *ctr = calloc(old_entries ? old_entries : 1, sizeof *ctr);
    if (!buf || !ctr) { rc = -ENOMEM; goto out; }
    if (family == AF_INET) {
        struct ipt_replace *r = (struct ipt_replace *)buf;
        snprintf(r->name, sizeof r->name, "%s", table);
        r->valid_hooks = im->valid_hooks; r->num_entries = im->num_entries; r->size = im->size;
        memcpy(r->hook_entry, im->hook_entry, sizeof r->hook_entry);
        memcpy(r->underflow, im->underflow, sizeof r->underflow);
        r->num_counters = old_entries; r->counters = ctr;
    } else {
        struct ip6t_replace *r = (struct ip6t_replace *)buf;
        snprintf(r->name, sizeof r->name, "%s", table);
        r->valid_hooks = im->valid_hooks; r->num_entries = im->num_entries; r->size = im->size;
        memcpy(r->hook_entry, im->hook_entry, sizeof r->hook_entry);
        memcpy(r->underflow, im->underflow, sizeof r->underflow);
        r->num_counters = old_entries; r->counters = ctr;
    }
    memcpy(buf + hdr, im->entries, im->size);
    if (setsockopt(fd, f->level, IPT_SO_SET_REPLACE, buf, hdr + im->size) < 0) rc = -errno;
out:
    free(buf); free(ctr); close(fd);
    return rc;
}

static const ipt_backend BE_SOCK = { be_load, be_store };
static const ipt_backend *BE = &BE_SOCK;
void ipt_set_backend(const ipt_backend *b) { BE = b ? b : &BE_SOCK; }

/* ================================================================ model === */

typedef struct xchain xchain;
/* fall: libiptc encodes a rule with NO target (counter-only, e.g. netd's bw_* chains)
 * as a standard target whose verdict is the offset of the NEXT entry. We keep it as
 * a flag and recompute that offset on write (src_off = offset in the loaded image). */
typedef struct { uint8_t *b; uint32_t len; xchain *jump; bool fall; uint32_t src_off; } xrule;
struct xchain {
    char name[XT_FUNCTION_MAXNAMELEN];
    int hook;                            /* 0..4 builtin, -1 user */
    xrule *r; int nr, cap;
    xrule pol;                           /* builtin: policy entry */
    uint32_t first_off;
};

typedef struct { ipt_spec s; char in_if[24], out_if[24], src[64], dst[64], jump[32], dnat[48]; } spec_store;
enum { OP_NEW, OP_FLUSH, OP_DEL_CHAIN, OP_APPEND, OP_INSERT, OP_DELETE };
struct op { int kind; char chain[32]; int pos; spec_store sp; };

struct ipt_tx {
    const fam_t *f; int family; char table[XT_TABLE_MAXNAMELEN];
    uint32_t valid_hooks, old_entries;
    xchain **ch; int nch, cap;
    struct op *log; int nlog, caplog;
    bool dirty, replaying;
};

static void free_rule(xrule *r) { free(r->b); r->b = NULL; }
static void free_chain(xchain *c)
{
    for (int i = 0; i < c->nr; i++) free_rule(&c->r[i]);
    free(c->r); free_rule(&c->pol); free(c);
}
static void free_chains(ipt_tx *t)
{
    for (int i = 0; i < t->nch; i++) free_chain(t->ch[i]);
    free(t->ch); t->ch = NULL; t->nch = t->cap = 0;
}

static xchain *find_chain(ipt_tx *t, const char *name)
{
    for (int i = 0; i < t->nch; i++) if (!strcmp(t->ch[i]->name, name)) return t->ch[i];
    return NULL;
}
static xchain *add_chain(ipt_tx *t, const char *name, int hook)
{
    if (t->nch == t->cap) {
        int nc = t->cap ? t->cap * 2 : 16;
        xchain **n = realloc(t->ch, nc * sizeof *n);
        if (!n) return NULL;
        t->ch = n; t->cap = nc;
    }
    xchain *c = calloc(1, sizeof *c);
    if (!c) return NULL;
    snprintf(c->name, sizeof c->name, "%s", name);
    c->hook = hook;
    t->ch[t->nch++] = c;
    return c;
}
static int chain_insert(xchain *c, int pos, xrule r)
{
    if (c->nr == c->cap) {
        int nc = c->cap ? c->cap * 2 : 8;
        xrule *n = realloc(c->r, nc * sizeof *n);
        if (!n) return -ENOMEM;
        c->r = n; c->cap = nc;
    }
    memmove(&c->r[pos + 1], &c->r[pos], (c->nr - pos) * sizeof *c->r);
    c->r[pos] = r; c->nr++;
    return 0;
}

/* ---- entry helpers ---- */
static uint16_t rd16(const uint8_t *p) { uint16_t v; memcpy(&v, p, 2); return v; }
static void wr16(uint8_t *p, uint16_t v) { memcpy(p, &v, 2); }
static int32_t rd32s(const uint8_t *p) { int32_t v; memcpy(&v, p, 4); return v; }
static void wr32s(uint8_t *p, int32_t v) { memcpy(p, &v, 4); }

static const struct xt_entry_target *tgt_of(const fam_t *f, const uint8_t *e)
{ return (const struct xt_entry_target *)(e + rd16(e + f->o_target)); }
static bool is_std(const fam_t *f, const uint8_t *e) { return tgt_of(f, e)->u.user.name[0] == 0; }
static bool is_error(const fam_t *f, const uint8_t *e) { return !strcmp(tgt_of(f, e)->u.user.name, XT_ERROR_TARGET); }

/* A standard-target-only entry: unconditional verdict. */
static uint8_t *make_std_entry(const fam_t *f, int32_t verdict, uint32_t *len)
{
    uint32_t n = f->hdr + STD_SZ;
    uint8_t *b = calloc(1, n);
    if (!b) return NULL;
    wr16(b + f->o_target, f->hdr); wr16(b + f->o_next, n);
    struct xt_entry_target *t = (struct xt_entry_target *)(b + f->hdr);
    t->u.user.target_size = STD_SZ;
    wr32s((uint8_t *)t + sizeof *t, verdict);
    *len = n;
    return b;
}
static uint8_t *make_error_entry(const fam_t *f, const char *name, uint32_t *len)
{
    uint32_t n = f->hdr + ERR_SZ;
    uint8_t *b = calloc(1, n);
    if (!b) return NULL;
    wr16(b + f->o_target, f->hdr); wr16(b + f->o_next, n);
    struct xt_error_target *t = (struct xt_error_target *)(b + f->hdr);
    t->target.u.user.target_size = ERR_SZ;
    snprintf(t->target.u.user.name, sizeof t->target.u.user.name, "%s", XT_ERROR_TARGET);
    snprintf(t->errorname, sizeof t->errorname, "%s", name);
    *len = n;
    return b;
}

/* ================================================================ parse === */

/* Where load_table() last rejected a table (for ipt_diagnose). */
static int g_perr_line; static uint32_t g_perr_a, g_perr_b;
#define PERR(a, b) do { g_perr_line = __LINE__; g_perr_a = (a); g_perr_b = (b); rc = -EPROTO; goto fail; } while (0)
static int load_table(ipt_tx *t);

/* Re-walks the kernel image with the same invariants load_table() enforces and
 * says which one broke (used when load fails with EPROTO). */
int ipt_diagnose(int family, const char *table, char *out, size_t cap)
{
    const fam_t *f = family == AF_INET ? &F4 : &F6;
    ipt_image im; memset(&im, 0, sizeof im);
    int rc = BE->load(family, table, &im);
    if (rc < 0) { snprintf(out, cap, "load: %s", strerror(-rc)); return rc; }
    char hd[200];
    snprintf(hd, sizeof hd, "size=%u n=%u valid=0x%x hook=[%u %u %u %u %u] under=[%u %u %u %u %u] hdr=%zu",
             im.size, im.num_entries, im.valid_hooks,
             im.hook_entry[0], im.hook_entry[1], im.hook_entry[2], im.hook_entry[3], im.hook_entry[4],
             im.underflow[0], im.underflow[1], im.underflow[2], im.underflow[3], im.underflow[4], f->hdr);
    bool pol[5] = { 0 }; uint32_t off = 0; int idx = 0, curhook = -2, usercnt = -1; char user[32] = "";
    const char *why = NULL; char w[200] = "";
    while (off < im.size) {
        uint8_t *e = im.entries + off;
        if (off + f->hdr + sizeof(struct xt_entry_target) > im.size) { snprintf(w, sizeof w, "entry #%d off=%u: truncated header", idx, off); why = w; break; }
        uint32_t len = rd16(e + f->o_next), toff = rd16(e + f->o_target);
        if (len < f->hdr + sizeof(struct xt_entry_target) || off + len > im.size || toff < f->hdr || toff >= len) {
            snprintf(w, sizeof w, "entry #%d off=%u: bad next_offset=%u target_offset=%u", idx, off, len, toff); why = w; break; }
        for (int h = 0; h < 5; h++) if ((im.valid_hooks & (1u << h)) && im.hook_entry[h] == off) { curhook = h; usercnt = -1; }
        char tn[32]; snprintf(tn, sizeof tn, "%s", tgt_of(f, e)->u.user.name);
        if (is_error(f, e)) {
            if (usercnt == 0) { snprintf(w, sizeof w, "user chain '%s' has no entries (before off=%u)", user, off); why = w; break; }
            if (off + len == im.size) break;
            snprintf(user, sizeof user, "%s", ((const struct xt_error_target *)(e + toff))->errorname);
            curhook = -1; usercnt = 0;
        } else {
            if (curhook == -2) { snprintf(w, sizeof w, "entry #%d off=%u target='%s' belongs to no chain", idx, off, tn); why = w; break; }
            if (curhook >= 0 && im.underflow[curhook] == off) pol[curhook] = true;
            if (curhook == -1) usercnt++;
        }
        off += len; idx++;
    }
    if (!why)
        for (int h = 0; h < 5; h++)
            if ((im.valid_hooks & (1u << h)) && !pol[h]) { snprintf(w, sizeof w, "chain %s: no entry at underflow=%u", HOOKS[h], im.underflow[h]); why = w; break; }
    free(im.entries);
    /* Authoritative part: run the real parser and report which check fired
     * (a = jump verdict, b = chain index, for the unresolved-jump check). */
    ipt_tx tx; memset(&tx, 0, sizeof tx);
    tx.f = f; tx.family = family; snprintf(tx.table, sizeof tx.table, "%s", table);
    g_perr_line = 0;
    int lr = load_table(&tx);
    if (lr == 0) free_chains(&tx);
    snprintf(out, cap, "%s | %s | load_table rc=%d src_line=%d a=%u b=%u", hd, why ? why : "walk ok", lr,
             g_perr_line, g_perr_a, g_perr_b);
    if (lr < 0 && g_perr_a) {                         /* unresolved jump: show where it points and the chain map */
        size_t n = strlen(out);
        ipt_image j; memset(&j, 0, sizeof j);
        if (BE->load(family, table, &j) == 0) {
            uint32_t o = 0, a = g_perr_a; int idx = 0, listed = 0;
            const char *what = "inside no entry boundary";
            char tgtname[40] = "";
            #define APP(...) do { if (n < cap) n += (size_t)snprintf(out + n, cap - n, __VA_ARGS__); } while (0)
            while (o < j.size) {
                uint8_t *e = j.entries + o; uint32_t len = rd16(e + f->o_next);
                if (len < f->hdr + sizeof(struct xt_entry_target) || o + len > j.size) break;
                if (o == a) {
                    snprintf(tgtname, sizeof tgtname, "%s", tgt_of(f, e)->u.user.name);
                    what = is_error(f, e) ? "an ERROR (chain header/terminator) entry"
                         : is_std(f, e) ? "a standard-target rule" : "a rule with extension target";
                } else if (o < a && a < o + len) { what = "the middle of an entry"; }
                o += len; idx++;
            }
            APP(" | jump a=%u points at %s%s%s%s", a, what, tgtname[0] ? " '" : "", tgtname, tgtname[0] ? "'" : "");
            APP(" | chains(hdr/first):");
            o = 0;
            while (o < j.size && listed < 24) {
                uint8_t *e = j.entries + o; uint32_t len = rd16(e + f->o_next);
                if (len < f->hdr + sizeof(struct xt_entry_target) || o + len > j.size) break;
                if (is_error(f, e) && o + len != j.size) {
                    APP(" %s:%u/%u", ((const struct xt_error_target *)(e + rd16(e + f->o_target)))->errorname, o, o + len);
                    listed++;
                }
                o += len;
            }
            #undef APP
            free(j.entries);
        }
    }
    return -EPROTO;
}

static int load_table(ipt_tx *t)
{
    const fam_t *f = t->f;
    ipt_image im; memset(&im, 0, sizeof im);
    int rc = BE->load(t->family, t->table, &im);
    if (rc < 0) return rc;
    t->valid_hooks = im.valid_hooks; t->old_entries = im.num_entries;
    xchain *builtin[5] = { 0 };
    for (int h = 0; h < 5; h++)
        if (im.valid_hooks & (1u << h)) {
            builtin[h] = add_chain(t, HOOKS[h], h);
            if (!builtin[h]) { rc = -ENOMEM; goto fail; }
        }
    xchain *cur = NULL;
    uint32_t off = 0;
    while (off < im.size) {
        if (off + f->hdr + sizeof(struct xt_entry_target) > im.size) { PERR(0, 0); }
        uint8_t *e = im.entries + off;
        uint32_t len = rd16(e + f->o_next), toff = rd16(e + f->o_target);
        if (len < f->hdr + sizeof(struct xt_entry_target) || off + len > im.size || toff < f->hdr || toff >= len) { PERR(0, 0); }
        for (int h = 0; h < 5; h++) if (builtin[h] && im.hook_entry[h] == off) cur = builtin[h];
        if (is_error(f, e)) {
            if (off + len == im.size) break;                   /* terminator */
            const struct xt_error_target *et = (const struct xt_error_target *)(e + toff);
            cur = add_chain(t, et->errorname, -1);
            if (!cur) { rc = -ENOMEM; goto fail; }
            cur->first_off = off + len;
        } else {
            if (!cur) { PERR(0, 0); }
            xrule r = { malloc(len), len, NULL, false, off };
            if (!r.b) { rc = -ENOMEM; goto fail; }
            memcpy(r.b, e, len);
            if (cur->hook >= 0 && im.underflow[cur->hook] == off) cur->pol = r;
            else if ((rc = chain_insert(cur, cur->nr, r)) < 0) { free(r.b); goto fail; }
        }
        off += len;
    }
    /* user chains end with an implicit RETURN: drop it, we regenerate it */
    for (int i = 0; i < t->nch; i++) {
        xchain *c = t->ch[i];
        if (c->hook >= 0) { if (!c->pol.b) { PERR(0, 0); } continue; }
        if (c->nr == 0) { PERR(0, 0); }
        free_rule(&c->r[--c->nr]);
    }
    /* resolve jump offsets to chain pointers */
    for (int i = 0; i < t->nch; i++)
        for (int j = 0; j < t->ch[i]->nr; j++) {
            xrule *r = &t->ch[i]->r[j];
            if (!is_std(f, r->b)) continue;
            uint8_t *vp = r->b + rd16(r->b + f->o_target) + sizeof(struct xt_entry_target);
            int32_t v = rd32s(vp);
            if (v < 0) continue;
            xchain *tgt = NULL;
            for (int k = 0; k < t->nch; k++) if (t->ch[k]->hook < 0 && t->ch[k]->first_off == (uint32_t)v) tgt = t->ch[k];
            if (!tgt) {
                if (r->src_off + r->len == (uint32_t)v) { r->fall = true; wr32s(vp, 0); continue; }   /* "fallthrough" */
                PERR((uint32_t)v, (uint32_t)i);
            }
            r->jump = tgt; wr32s(vp, 0);
        }
    free(im.entries);
    return 0;
fail:
    free(im.entries); free_chains(t);
    return rc;
}

/* ============================================================= serialise === */

static int cmp_chain(const void *a, const void *b)
{
    const xchain *x = *(xchain *const *)a, *y = *(xchain *const *)b;
    int hx = x->hook < 0 ? 100 : x->hook, hy = y->hook < 0 ? 100 : y->hook;
    return hx - hy;
}

static int serialise(ipt_tx *t, ipt_image *im)
{
    const fam_t *f = t->f;
    xchain **ord = malloc(t->nch * sizeof *ord);
    if (!ord) return -ENOMEM;
    memcpy(ord, t->ch, t->nch * sizeof *ord);
    /* stable order: builtins by hook, user chains in creation order */
    for (int i = 1; i < t->nch; i++) {            /* insertion sort = stable */
        xchain *x = ord[i]; int j = i - 1;
        while (j >= 0 && cmp_chain(&ord[j], &x) > 0) { ord[j + 1] = ord[j]; j--; }
        ord[j + 1] = x;
    }
    uint32_t off = 0, n = 0;
    memset(im, 0, sizeof *im);
    im->valid_hooks = t->valid_hooks;
    for (int i = 0; i < t->nch; i++) {
        xchain *c = ord[i];
        if (c->hook < 0) { off += f->hdr + ERR_SZ; n++; }
        c->first_off = off;
        if (c->hook >= 0) im->hook_entry[c->hook] = off;
        for (int j = 0; j < c->nr; j++) { off += c->r[j].len; n++; }
        if (c->hook >= 0) { im->underflow[c->hook] = off; off += c->pol.len; }
        else off += f->hdr + STD_SZ;
        n++;
    }
    off += f->hdr + ERR_SZ; n++;
    im->size = off; im->num_entries = n;
    im->entries = calloc(1, off);
    if (!im->entries) { free(ord); return -ENOMEM; }
    uint8_t *p = im->entries;
    for (int i = 0; i < t->nch; i++) {
        xchain *c = ord[i]; uint32_t l;
        if (c->hook < 0) {
            uint8_t *e = make_error_entry(f, c->name, &l);
            memcpy(p, e, l); p += l; free(e);
        }
        for (int j = 0; j < c->nr; j++) {
            xrule *r = &c->r[j];
            memcpy(p, r->b, r->len);
            wr16(p + f->o_next, r->len);
            if (r->jump) wr32s(p + rd16(p + f->o_target) + sizeof(struct xt_entry_target), r->jump->first_off);
            else if (r->fall) wr32s(p + rd16(p + f->o_target) + sizeof(struct xt_entry_target), (int32_t)((p - im->entries) + r->len));
            p += r->len;
        }
        if (c->hook >= 0) { memcpy(p, c->pol.b, c->pol.len); p += c->pol.len; }
        else { uint8_t *e = make_std_entry(f, V_RETURN, &l); memcpy(p, e, l); p += l; free(e); }
    }
    uint32_t l; uint8_t *e = make_error_entry(f, XT_ERROR_TARGET, &l);
    memcpy(p, e, l); free(e);
    free(ord);
    return 0;
}

/* ============================================================ rule build === */

typedef struct { uint8_t *p; size_t len, cap; } buf_t;
static uint8_t *bgrow(buf_t *b, size_t n)
{
    if (b->len + n > b->cap) {
        size_t nc = (b->len + n) * 2;
        uint8_t *np = realloc(b->p, nc);
        if (!np) return NULL;
        b->p = np; b->cap = nc;
    }
    uint8_t *r = b->p + b->len;
    memset(r, 0, n); b->len += n;
    return r;
}

static int parse_cidr(int family, const char *s, void *addr, void *mask)
{
    char tmp[64]; int al = family == AF_INET ? 4 : 16, plen = al * 8;
    snprintf(tmp, sizeof tmp, "%s", s);
    char *sl = strchr(tmp, '/');
    if (sl) { *sl = 0; plen = atoi(sl + 1); if (plen < 0 || plen > al * 8) return -EINVAL; }
    uint8_t *a = addr, *m = mask;
    if (inet_pton(family, tmp, a) != 1) return -EINVAL;
    memset(m, 0, al);
    for (int i = 0; i < plen; i++) m[i / 8] |= 0x80 >> (i % 8);
    for (int i = 0; i < al; i++) a[i] &= m[i];
    return 0;
}

static void set_iface(char *name, unsigned char *mask, const char *spec)
{
    char tmp[IFNAMSIZ]; size_t n;
    snprintf(tmp, sizeof tmp, "%s", spec);
    n = strlen(tmp);
    if (n && tmp[n - 1] == '+') { tmp[--n] = 0; memset(mask, 0xff, n); }
    else memset(mask, 0xff, n + 1);
    memcpy(name, tmp, n);
}

static int fill_ip(const fam_t *f, uint8_t *h, const ipt_spec *s)
{
    int rc;
    if (f->family == AF_INET) {
        struct ipt_ip *ip = (struct ipt_ip *)h;
        if (s->src && (rc = parse_cidr(AF_INET, s->src, &ip->src, &ip->smsk)) < 0) return rc;
        if (s->dst && (rc = parse_cidr(AF_INET, s->dst, &ip->dst, &ip->dmsk)) < 0) return rc;
        if (s->in_if) set_iface(ip->iniface, ip->iniface_mask, s->in_if);
        if (s->out_if) set_iface(ip->outiface, ip->outiface_mask, s->out_if);
        ip->proto = s->proto;
        if (s->in_inv) ip->invflags |= IPT_INV_VIA_IN;
        if (s->out_inv) ip->invflags |= IPT_INV_VIA_OUT;
        if (s->src_inv) ip->invflags |= IPT_INV_SRCIP;
        if (s->dst_inv) ip->invflags |= IPT_INV_DSTIP;
    } else {
        struct ip6t_ip6 *ip = (struct ip6t_ip6 *)h;
        if (s->src && (rc = parse_cidr(AF_INET6, s->src, &ip->src, &ip->smsk)) < 0) return rc;
        if (s->dst && (rc = parse_cidr(AF_INET6, s->dst, &ip->dst, &ip->dmsk)) < 0) return rc;
        if (s->in_if) set_iface(ip->iniface, ip->iniface_mask, s->in_if);
        if (s->out_if) set_iface(ip->outiface, ip->outiface_mask, s->out_if);
        if (s->proto) { ip->proto = s->proto; ip->flags |= IP6T_F_PROTO; }
        if (s->in_inv) ip->invflags |= IP6T_INV_VIA_IN;
        if (s->out_inv) ip->invflags |= IP6T_INV_VIA_OUT;
        if (s->src_inv) ip->invflags |= IP6T_INV_SRCIP;
        if (s->dst_inv) ip->invflags |= IP6T_INV_DSTIP;
    }
    return 0;
}

static int add_match(buf_t *b, const char *name, uint8_t rev, const void *data, size_t dl)
{
    size_t sz = XT_ALIGN(sizeof(struct xt_entry_match) + dl);
    uint8_t *p = bgrow(b, sz);
    if (!p) return -ENOMEM;
    struct xt_entry_match *m = (struct xt_entry_match *)p;
    m->u.match_size = sz; snprintf(m->u.user.name, sizeof m->u.user.name, "%s", name);
    m->u.user.revision = rev;
    memcpy(m->data, data, dl);
    return 0;
}
static int add_target(buf_t *b, const char *name, uint8_t rev, const void *data, size_t dl)
{
    size_t sz = XT_ALIGN(sizeof(struct xt_entry_target) + dl);
    uint8_t *p = bgrow(b, sz);
    if (!p) return -ENOMEM;
    struct xt_entry_target *t = (struct xt_entry_target *)p;
    t->u.target_size = sz; snprintf(t->u.user.name, sizeof t->u.user.name, "%s", name);
    t->u.user.revision = rev;
    if (dl) memcpy(t->data, data, dl);
    return 0;
}

static int build_rule(ipt_tx *t, const ipt_spec *s, xrule *out)
{
    const fam_t *f = t->f;
    buf_t b = { 0 };
    int rc = 0; xchain *jump = NULL;
    if (!bgrow(&b, f->hdr)) return -ENOMEM;
    if ((rc = fill_ip(f, b.p, s)) < 0) goto fail;

    if (s->dport_lo >= 0 || s->tcp_flags_mask >= 0) {
        if (s->proto == IPPROTO_TCP) {
            struct xt_tcp d; memset(&d, 0, sizeof d);
            d.spts[0] = 0; d.spts[1] = 0xffff;
            d.dpts[0] = s->dport_lo >= 0 ? s->dport_lo : 0;
            d.dpts[1] = s->dport_lo >= 0 ? (s->dport_hi >= 0 ? s->dport_hi : s->dport_lo) : 0xffff;
            if (s->tcp_flags_mask >= 0) { d.flg_mask = s->tcp_flags_mask; d.flg_cmp = s->tcp_flags_cmp; }
            else d.flg_mask = 0, d.flg_cmp = 0;
            if ((rc = add_match(&b, "tcp", 0, &d, sizeof d)) < 0) goto fail;
        } else if (s->proto == IPPROTO_UDP && s->dport_lo >= 0 && s->tcp_flags_mask < 0) {
            struct xt_udp d; memset(&d, 0, sizeof d);
            d.spts[0] = 0; d.spts[1] = 0xffff;
            d.dpts[0] = s->dport_lo; d.dpts[1] = s->dport_hi >= 0 ? s->dport_hi : s->dport_lo;
            if ((rc = add_match(&b, "udp", 0, &d, sizeof d)) < 0) goto fail;
        } else { rc = -EINVAL; goto fail; }
    }
    if (s->mark_match) {
        struct xt_mark_mtinfo1 d = { s->mark_val, s->mark_mask ? s->mark_mask : 0xffffffffu, s->mark_inv };
        if ((rc = add_match(&b, "mark", 1, &d, sizeof d)) < 0) goto fail;
    }
    if (s->owner) {
        struct xt_owner_match_info d; memset(&d, 0, sizeof d);
        d.uid_min = s->uid_lo; d.uid_max = s->uid_hi; d.match = XT_OWNER_UID; d.invert = s->owner_inv ? XT_OWNER_UID : 0;
        if ((rc = add_match(&b, "owner", 1, &d, sizeof d)) < 0) goto fail;
    }
    size_t toff = b.len;
    switch (s->target) {
    case MVT_ACCEPT: case MVT_DROP: case MVT_RETURN: case MVT_JUMP: {
        int32_t v = s->target == MVT_ACCEPT ? V_ACCEPT : s->target == MVT_DROP ? V_DROP : V_RETURN;
        if (s->target == MVT_JUMP) {
            if (!s->jump_chain) { rc = -EINVAL; goto fail; }
            jump = find_chain(t, s->jump_chain);
            if (!jump || jump->hook >= 0) { rc = -ENOENT; goto fail; }
            v = 0;
        }
        rc = add_target(&b, "", 0, &v, sizeof v); break; }
    case MVT_MARK: {
        struct xt_mark_tginfo2 d = { s->set_mark, s->set_mask ? s->set_mask : 0xffffffffu };
        rc = add_target(&b, "MARK", 2, &d, sizeof d); break; }
    case MVT_REJECT: {
        if (f->family == AF_INET) {
            int w = s->reject_with == MVR_TCP_RESET ? IPT_TCP_RESET : IPT_ICMP_PORT_UNREACHABLE;
            rc = add_target(&b, "REJECT", 0, &w, sizeof w);
        } else {
            int w = s->reject_with == MVR_6_NO_ROUTE ? IP6T_ICMP6_NO_ROUTE
                  : s->reject_with == MVR_TCP_RESET ? IP6T_TCP_RESET : IP6T_ICMP6_PORT_UNREACH;
            rc = add_target(&b, "REJECT", 0, &w, sizeof w);
        }
        break; }
    case MVT_TCPMSS: {
        struct xt_tcpmss_info d = { s->mss };
        rc = add_target(&b, "TCPMSS", 0, &d, sizeof d); break; }
    case MVT_DNAT: {
        if (f->family != AF_INET || !s->dnat_to) { rc = -EINVAL; goto fail; }
        struct { uint32_t rangesize; struct { uint32_t flags; uint32_t min_ip, max_ip; uint16_t min, max; } r; } d;
        memset(&d, 0, sizeof d);
        d.rangesize = 1; d.r.flags = 1;                        /* NF_NAT_RANGE_MAP_IPS */
        if (inet_pton(AF_INET, s->dnat_to, &d.r.min_ip) != 1) { rc = -EINVAL; goto fail; }
        d.r.max_ip = d.r.min_ip;
        rc = add_target(&b, "DNAT", 0, &d, sizeof d); break; }
    default: rc = -EINVAL;
    }
    if (rc < 0) goto fail;
    wr16(b.p + f->o_target, toff);
    wr16(b.p + f->o_next, b.len);
    out->b = b.p; out->len = b.len; out->jump = jump; out->fall = false; out->src_off = 0;
    return 0;
fail:
    free(b.p);
    return rc;
}

static bool rule_eq(const fam_t *f, const xrule *a, const xrule *b)
{
    if (a->len != b->len || a->jump != b->jump || a->fall != b->fall) return false;
    if (memcmp(a->b, b->b, f->ipsz)) return false;
    if (rd16(a->b + f->o_target) != rd16(b->b + f->o_target)) return false;
    return memcmp(a->b + f->hdr, b->b + f->hdr, a->len - f->hdr) == 0;
}

/* ========================================================= mutators / log === */

static int do_new(ipt_tx *t, const char *name)
{
    if (strlen(name) >= XT_FUNCTION_MAXNAMELEN - 1) return -EINVAL;
    if (find_chain(t, name)) return -EEXIST;
    xchain *c = add_chain(t, name, -1);
    return c ? 0 : -ENOMEM;
}
static int do_flush(ipt_tx *t, const char *name)
{
    xchain *c = find_chain(t, name);
    if (!c) return -ENOENT;
    for (int i = 0; i < c->nr; i++) free_rule(&c->r[i]);
    c->nr = 0;
    return 0;
}
static int do_delchain(ipt_tx *t, const char *name)
{
    xchain *c = find_chain(t, name);
    if (!c) return -ENOENT;
    if (c->hook >= 0) return -EINVAL;
    if (c->nr) return -ENOTEMPTY;
    for (int i = 0; i < t->nch; i++)
        for (int j = 0; j < t->ch[i]->nr; j++) if (t->ch[i]->r[j].jump == c) return -EMFILE;
    for (int i = 0; i < t->nch; i++) if (t->ch[i] == c) { memmove(&t->ch[i], &t->ch[i + 1], (t->nch - i - 1) * sizeof *t->ch); t->nch--; break; }
    free_chain(c);
    return 0;
}
static int do_add(ipt_tx *t, const char *chain, int pos, const ipt_spec *s)
{
    xchain *c = find_chain(t, chain);
    if (!c) return -ENOENT;
    xrule r; int rc = build_rule(t, s, &r);
    if (rc < 0) return rc;
    if (pos < 0 || pos > c->nr) pos = c->nr;
    if ((rc = chain_insert(c, pos, r)) < 0) { free(r.b); return rc; }
    return 0;
}
static int do_find(ipt_tx *t, const char *chain, const ipt_spec *s, xchain **cp, int *idx)
{
    xchain *c = find_chain(t, chain);
    if (!c) return -ENOENT;
    xrule r; int rc = build_rule(t, s, &r);
    if (rc < 0) return rc;
    rc = -ENOENT;
    for (int i = 0; i < c->nr; i++) if (rule_eq(t->f, &c->r[i], &r)) { *cp = c; *idx = i; rc = 0; break; }
    free(r.b);
    return rc;
}
static int do_del(ipt_tx *t, const char *chain, const ipt_spec *s)
{
    xchain *c; int i, rc = do_find(t, chain, s, &c, &i);
    if (rc < 0) return rc;
    free_rule(&c->r[i]);
    memmove(&c->r[i], &c->r[i + 1], (c->nr - i - 1) * sizeof *c->r); c->nr--;
    return 0;
}

static void sp_copy(spec_store *d, const ipt_spec *s)
{
    d->s = *s;
#define CP(field, buf) do { if (s->field) { snprintf(d->buf, sizeof d->buf, "%s", s->field); d->s.field = d->buf; } } while (0)
    CP(in_if, in_if); CP(out_if, out_if); CP(src, src); CP(dst, dst); CP(jump_chain, jump); CP(dnat_to, dnat);
#undef CP
}

/* After a spec_store is copied by value its string pointers still aim at the
 * original; point them at the copy's own buffers. */
static void sp_fix(spec_store *d)
{
    if (d->s.in_if) d->s.in_if = d->in_if;
    if (d->s.out_if) d->s.out_if = d->out_if;
    if (d->s.src) d->s.src = d->src;
    if (d->s.dst) d->s.dst = d->dst;
    if (d->s.jump_chain) d->s.jump_chain = d->jump;
    if (d->s.dnat_to) d->s.dnat_to = d->dnat;
}

static int run_op(ipt_tx *t, const struct op *o)
{
    switch (o->kind) {
    case OP_NEW: return do_new(t, o->chain);
    case OP_FLUSH: return do_flush(t, o->chain);
    case OP_DEL_CHAIN: return do_delchain(t, o->chain);
    case OP_APPEND: return do_add(t, o->chain, -1, &o->sp.s);
    case OP_INSERT: return do_add(t, o->chain, o->pos, &o->sp.s);
    case OP_DELETE: return do_del(t, o->chain, &o->sp.s);
    }
    return -EINVAL;
}

static int apply(ipt_tx *t, int kind, const char *chain, int pos, const ipt_spec *s)
{
    struct op o; memset(&o, 0, sizeof o);
    o.kind = kind; o.pos = pos;
    snprintf(o.chain, sizeof o.chain, "%s", chain);
    if (s) sp_copy(&o.sp, s);
    int rc = run_op(t, &o);
    if (rc == 0) t->dirty = true;
    if (t->nlog == t->caplog) {
        int nc = t->caplog ? t->caplog * 2 : 64;
        struct op *n = realloc(t->log, nc * sizeof *n);
        if (!n) return rc;
        t->log = n; t->caplog = nc;
    }
    t->log[t->nlog] = o;
    sp_fix(&t->log[t->nlog].sp);
    t->nlog++;
    return rc;
}

int ipt_new_chain(ipt_tx *t, const char *c)    { return apply(t, OP_NEW, c, 0, NULL); }
int ipt_flush_chain(ipt_tx *t, const char *c)  { return apply(t, OP_FLUSH, c, 0, NULL); }
int ipt_delete_chain(ipt_tx *t, const char *c) { return apply(t, OP_DEL_CHAIN, c, 0, NULL); }
int ipt_append(ipt_tx *t, const char *c, const ipt_spec *s) { return apply(t, OP_APPEND, c, -1, s); }
int ipt_insert(ipt_tx *t, const char *c, int pos1, const ipt_spec *s) { return apply(t, OP_INSERT, c, pos1 > 0 ? pos1 - 1 : 0, s); }
int ipt_delete(ipt_tx *t, const char *c, const ipt_spec *s) { return apply(t, OP_DELETE, c, 0, s); }
int ipt_check(ipt_tx *t, const char *c, const ipt_spec *s)
{ xchain *cc; int i; return do_find(t, c, s, &cc, &i); }

/* =============================================================== lifecycle === */

ipt_tx *ipt_tx_begin(int family, const char *table)
{
    glock();
    ipt_tx *t = calloc(1, sizeof *t);
    if (!t) { gunlock(); return NULL; }
    t->f = family == AF_INET ? &F4 : &F6; t->family = family;
    snprintf(t->table, sizeof t->table, "%s", table);
    int rc = load_table(t);
    if (rc < 0) { free(t); gunlock(); errno = -rc; return NULL; }   /* errno tells the caller why */
    return t;
}

static void tx_free(ipt_tx *t)
{
    free_chains(t); free(t->log); free(t);
    gunlock();
}
void ipt_tx_abort(ipt_tx *t) { tx_free(t); }

int ipt_tx_commit(ipt_tx *t)
{
    int rc = 0;
    if (!t->dirty) { tx_free(t); return 0; }
    for (int attempt = 0; attempt < 8; attempt++) {
        ipt_image im;
        if ((rc = serialise(t, &im)) < 0) break;
        rc = BE->store(t->family, t->table, &im, t->old_entries);
        free(im.entries);
        if (rc != -EAGAIN) break;
        /* Someone (netd) changed the table under us: reload and replay. */
        free_chains(t);
        if ((rc = load_table(t)) < 0) break;
        for (int i = 0; i < t->nlog; i++) run_op(t, &t->log[i]);
    }
    tx_free(t);
    return rc;
}
