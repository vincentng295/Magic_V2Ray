/* Subscription auto-update (was `sub_loop` in service.sh). The WebUI cannot run in the background, so this
 * worker downloads the subscriptions once an hour and leaves the result for the WebUI:
 *   in : $DATADIR/autocheck_sublist.txt   "<url>#<category>" per line (written by the WebUI)
 *   out: $DATADIR/pending_sublist.txt     "<category>:<base64 of the node uris>" per line
 * The WebUI imports pending_sublist.txt every time it is opened, then deletes it. Only subscriptions whose
 * content changed are written out. The on-disk formats (sublist_cache/<md5 of the entry>.{sum,line,dirty})
 * are the ones service.sh used, so an upgrade does not make every subscription look changed.
 *
 * The HTTP request is made by our own curl applet (fork + exec of g_self, no shell): direct first, then
 * through xray's SOCKS5 inbound. The URL is a single argv entry after `--`, so it cannot be read as an option. */
#include "service.h"
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#define SUB_LIST        DATADIR "/autocheck_sublist.txt"
#define SUB_CACHE       DATADIR "/sublist_cache"
#define SUB_PENDING     DATADIR "/pending_sublist.txt"
#define SUB_TMP         DATADIR "/.sublist.tmp"
#define SUB_INTERVAL_S  3600
#define SUB_FIRST_DELAY_S 90               /* let the network come up after boot */
#define SUB_UA          "v2rayNG/2.2.3"
#define SUB_MAX_BODY    (16u << 20)
#define SUB_CURL_TIMEOUT 40

static worker_t g_sub;

/* ------------------------------------------------------------------ md5 (RFC 1321) */

typedef struct { uint32_t s[4]; uint64_t len; uint8_t buf[64]; size_t n; } md5_t;

static const uint32_t K5[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391 };
static const uint8_t R5[64] = {
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21 };

static void md5_block(md5_t *c, const uint8_t *p)
{
    uint32_t w[16], a = c->s[0], b = c->s[1], cc = c->s[2], d = c->s[3];
    for (int i = 0; i < 16; i++)
        w[i] = (uint32_t)p[i * 4] | (uint32_t)p[i * 4 + 1] << 8 | (uint32_t)p[i * 4 + 2] << 16 | (uint32_t)p[i * 4 + 3] << 24;
    for (int i = 0; i < 64; i++) {
        uint32_t f; int g;
        if (i < 16)      { f = (b & cc) | (~b & d); g = i; }
        else if (i < 32) { f = (d & b) | (~d & cc); g = (5 * i + 1) & 15; }
        else if (i < 48) { f = b ^ cc ^ d;          g = (3 * i + 5) & 15; }
        else             { f = cc ^ (b | ~d);       g = (7 * i) & 15; }
        uint32_t t = d; d = cc; cc = b;
        uint32_t x = a + f + K5[i] + w[g];
        b = b + ((x << R5[i]) | (x >> (32 - R5[i])));
        a = t;
    }
    c->s[0] += a; c->s[1] += b; c->s[2] += cc; c->s[3] += d;
}

static void md5_init(md5_t *c)
{
    c->s[0] = 0x67452301; c->s[1] = 0xefcdab89; c->s[2] = 0x98badcfe; c->s[3] = 0x10325476;
    c->len = 0; c->n = 0;
}

static void md5_update(md5_t *c, const void *data, size_t n)
{
    const uint8_t *p = data;
    c->len += n;
    while (n) {
        size_t k = 64 - c->n < n ? 64 - c->n : n;
        memcpy(c->buf + c->n, p, k);
        c->n += k; p += k; n -= k;
        if (c->n == 64) { md5_block(c, c->buf); c->n = 0; }
    }
}

static void md5_hex(const void *data, size_t n, char out[33])
{
    md5_t c; md5_init(&c); md5_update(&c, data, n);
    uint64_t bits = c.len * 8;
    uint8_t pad = 0x80;
    md5_update(&c, &pad, 1);
    pad = 0;
    while (c.n != 56) md5_update(&c, &pad, 1);
    uint8_t lb[8];
    for (int i = 0; i < 8; i++) lb[i] = (uint8_t)(bits >> (8 * i));
    md5_update(&c, lb, 8);
    for (int i = 0; i < 4; i++)
        for (int j = 0; j < 4; j++) sprintf(out + i * 8 + j * 2, "%02x", (c.s[i] >> (8 * j)) & 0xff);
}

/* ------------------------------------------------------------------ helpers */

/* whole file into a malloc'd, NUL-terminated buffer; NULL when missing / empty / larger than cap */
static char *slurp(const char *path, size_t cap, size_t *len)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size <= 0 || (size_t)st.st_size > cap) { close(fd); return NULL; }
    char *b = malloc((size_t)st.st_size + 1);
    if (!b) { close(fd); return NULL; }
    size_t got = 0;
    while (got < (size_t)st.st_size) {
        ssize_t r = read(fd, b + got, (size_t)st.st_size - got);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        got += (size_t)r;
    }
    close(fd);
    b[got] = 0;
    if (len) *len = got;
    return b;
}

static int write_file(const char *path, const void *d, size_t n, mode_t mode)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (fd < 0) return -1;
    const char *p = d;
    while (n) {
        ssize_t r = write(fd, p, n);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) { close(fd); return -1; }
        p += r; n -= (size_t)r;
    }
    fchmod(fd, mode);
    close(fd);
    return 0;
}

static char *b64_encode(const uint8_t *in, size_t n)
{
    static const char T[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    char *o = malloc((n + 2) / 3 * 4 + 1), *q = o;
    if (!o) return NULL;
    size_t i = 0;
    for (; i + 2 < n; i += 3) {
        uint32_t v = (uint32_t)in[i] << 16 | (uint32_t)in[i + 1] << 8 | in[i + 2];
        *q++ = T[v >> 18]; *q++ = T[(v >> 12) & 63]; *q++ = T[(v >> 6) & 63]; *q++ = T[v & 63];
    }
    if (n - i == 1) { uint32_t v = (uint32_t)in[i] << 16; *q++ = T[v >> 18]; *q++ = T[(v >> 12) & 63]; *q++ = '='; *q++ = '='; }
    else if (n - i == 2) {
        uint32_t v = (uint32_t)in[i] << 16 | (uint32_t)in[i + 1] << 8;
        *q++ = T[v >> 18]; *q++ = T[(v >> 12) & 63]; *q++ = T[(v >> 6) & 63]; *q++ = '=';
    }
    *q = 0;
    return o;
}

/* ^[A-Za-z0-9+.-]{2,16}:// */
static bool is_uri_line(const char *s, size_t n)
{
    size_t i = 0;
    while (i < n && (isalnum((unsigned char)s[i]) || s[i] == '+' || s[i] == '.' || s[i] == '-')) i++;
    return i >= 2 && i <= 16 && i + 2 < n && s[i] == ':' && s[i + 1] == '/' && s[i + 2] == '/';
}

/* The node uris of a subscription body, one per line (each ending in '\n'), as a malloc'd buffer.
 * Handles a plain list and base64 (standard or url-safe, padded or not). *out_len = 0 when none found. */
static char *sub_nodes(const char *body, size_t n, size_t *out_len)
{
    *out_len = 0;
    char *text = NULL;
    size_t tn = 0;
    bool plain = false;
    for (size_t i = 0; i + 2 < n && i < 512; i++)
        if (body[i] == ':' && body[i + 1] == '/' && body[i + 2] == '/') { plain = true; break; }
    if (plain) {
        text = malloc(n + 1);
        if (!text) return NULL;
        for (size_t i = 0; i < n; i++) if (body[i] != '\r') text[tn++] = body[i];
    } else {
        char *clean = malloc(n + 1);
        if (!clean) return NULL;
        size_t cn = 0;
        for (size_t i = 0; i < n; i++) {
            char ch = body[i];
            if (ch == '\r' || ch == '\n' || ch == '\t' || ch == ' ') continue;
            clean[cn++] = ch == '_' ? '/' : ch == '-' ? '+' : ch;
        }
        text = malloc(cn / 4 * 3 + 4);
        if (!text) { free(clean); return NULL; }
        tn = u_b64_decode(clean, cn, (uint8_t *)text);        /* stops at '=', skips anything else */
        free(clean);
        size_t k = 0;
        for (size_t i = 0; i < tn; i++) if (text[i] != '\r') text[k++] = text[i];
        tn = k;
    }
    char *res = malloc(tn + 2);
    if (!res) { free(text); return NULL; }
    size_t rn = 0;
    for (size_t i = 0; i < tn;) {
        size_t e = i;
        while (e < tn && text[e] != '\n') e++;
        if (is_uri_line(text + i, e - i)) { memcpy(res + rn, text + i, e - i); rn += e - i; res[rn++] = '\n'; }
        i = e + 1;
    }
    free(text);
    res[rn] = 0;
    *out_len = rn;
    return res;
}

/* ------------------------------------------------------------------ download */

static const char *capath(void)
{
    static const char *const d[] = { "/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts" };
    for (size_t i = 0; i < sizeof d / sizeof d[0]; i++) if (access(d[i], R_OK) == 0) return d[i];
    return NULL;
}

/* One curl run (our own applet). 0 = ok with a non-empty file. Killed when the worker is asked to stop. */
static int curl_once(const char *url, const char *outfile, bool via_socks, int stop_fd)
{
    char socks[64];
    snprintf(socks, sizeof socks, "%s:%d", TUN_ADDR, TUN_PORT);
    const char *ca = capath();
    char *argv[24]; int a = 0;
    argv[a++] = g_self; argv[a++] = "curl";
    if (via_socks) {
        argv[a++] = "--socks5-hostname"; argv[a++] = socks; 
    } else { 
        argv[a++] = "--doh-url"; argv[a++] = "https://1.1.1.1/dns-query"; 
    }
    if (ca) { argv[a++] = "--capath"; argv[a++] = (char *)ca; }
    argv[a++] = "-sSL"; argv[a++] = "-f";
    argv[a++] = "--max-redirs"; argv[a++] = "3";
    argv[a++] = "--max-time"; argv[a++] = "40";
    argv[a++] = "--max-filesize"; argv[a++] = "16777216";
    argv[a++] = "-A"; argv[a++] = SUB_UA;
    argv[a++] = "--proto"; argv[a++] = "=http,https";
    argv[a++] = "--proto-redir"; argv[a++] = "=http,https";
    argv[a++] = "-o"; argv[a++] = (char *)outfile;
    argv[a++] = "--"; argv[a++] = (char *)url;
    argv[a] = NULL;

    unlink(outfile);
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) { dup2(nul, 0); dup2(nul, 1); dup2(nul, 2); }
        for (int fd = 3; fd < 256; fd++) close(fd);           /* no pipe or socket of the service leaks in */
        execv(g_self, argv);
        _exit(127);
    }
    int st = 0, waited_ms = 0;
    for (;;) {
        pid_t r = waitpid(pid, &st, WNOHANG);
        if (r == pid) break;
        if (r < 0 && errno != EINTR) return -1;
        if (worker_wait(stop_fd, 200) || waited_ms > (SUB_CURL_TIMEOUT + 15) * 1000) {   /* stop request, or a wedged curl */
            kill(pid, SIGKILL);
            while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
            unlink(outfile);
            return -1;
        }
        waited_ms += 200;
    }
    struct stat sb;
    if (!WIFEXITED(st) || WEXITSTATUS(st) != 0 || stat(outfile, &sb) < 0 || sb.st_size <= 0) {
        unlink(outfile);
        return -1;
    }
    return 0;
}

/* direct first, then through xray's SOCKS5 inbound */
static int sub_download(const char *url, const char *outfile, int stop_fd)
{
    for (int via = 0; via < 2; via++) {
        if (curl_once(url, outfile, via == 1, stop_fd) == 0) return 0;
        if (worker_wait(stop_fd, 0)) return -1;
    }
    return -1;
}

/* ------------------------------------------------------------------ one round */

static void publish_pending(void)
{
    DIR *d = opendir(SUB_CACHE);
    if (!d) return;
    size_t cap = 1 << 16, len = 0;
    char *out = malloc(cap);
    bool any = false;
    struct dirent *e;
    while (out && (e = readdir(d))) {
        size_t nl = strlen(e->d_name);
        if (nl < 7 || strcmp(e->d_name + nl - 6, ".dirty") != 0) continue;
        any = true;
        char p[400];
        snprintf(p, sizeof p, SUB_CACHE "/%.*s.line", (int)(nl - 6), e->d_name);
        size_t ln = 0;
        char *line = slurp(p, SUB_MAX_BODY * 2, &ln);
        if (!line) continue;
        if (len + ln + 1 > cap) {
            while (len + ln + 1 > cap) cap *= 2;
            char *g = realloc(out, cap);
            if (!g) { free(line); free(out); out = NULL; break; }
            out = g;
        }
        memcpy(out + len, line, ln); len += ln;
        free(line);
    }
    closedir(d);
    if (!out) return;
    if (any) {                                   /* atomic rename: the WebUI never reads half a file */
        if (write_file(SUB_TMP ".pending", out, len, 0600) == 0) rename(SUB_TMP ".pending", SUB_PENDING);
        else log_msg("sublist: cannot write %s", SUB_PENDING);
    }
    free(out);
}

static void clear_dirty(void)
{
    DIR *d = opendir(SUB_CACHE);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        size_t nl = strlen(e->d_name);
        if (nl > 6 && !strcmp(e->d_name + nl - 6, ".dirty")) {
            char p[400];
            snprintf(p, sizeof p, SUB_CACHE "/%s", e->d_name);
            unlink(p);
        }
    }
    closedir(d);
}

static void sub_update_all(int stop_fd)
{
    size_t ln = 0;
    char *list = slurp(SUB_LIST, 1 << 20, &ln);
    if (!list) return;                            /* no subscriptions registered */
    u_mkdir_p(SUB_CACHE, 0700);
    /* No pending file => the WebUI took the previous one, nothing is waiting any more. */
    if (!u_exists(SUB_PENDING)) clear_dirty();

    int n = 0, fail = 0;
    char *save = NULL;
    for (char *entry = strtok_r(list, "\n", &save); entry; entry = strtok_r(NULL, "\n", &save)) {
        if (worker_wait(stop_fd, 0)) break;
        size_t el = strlen(entry);
        while (el && entry[el - 1] == '\r') entry[--el] = 0;
        if (!el || entry[0] == '#') continue;
        char *hash = strrchr(entry, '#');
        if (!hash) continue;
        char key[33], sum[33];
        md5_hex(entry, el, key);                  /* the whole "<url>#<category>" line, as service.sh did */
        *hash = 0;
        const char *url = entry, *cat = hash + 1;
        if (strncmp(url, "http", 4) != 0 || !*cat || strchr(cat, ':')) continue;   /* ':' separates category and payload */
        n++;

        if (sub_download(url, SUB_TMP ".body", stop_fd) != 0) {
            fail++; log_msg("sublist: download failed for '%s'", cat); continue;
        }
        size_t bn = 0;
        char *body = slurp(SUB_TMP ".body", SUB_MAX_BODY, &bn);
        unlink(SUB_TMP ".body");
        size_t un = 0;
        char *uris = body ? sub_nodes(body, bn, &un) : NULL;
        free(body);
        if (!uris || un == 0) { free(uris); fail++; log_msg("sublist: no node found in '%s'", cat); continue; }

        md5_hex(uris, un, sum);
        char path[400], old[64] = "";
        snprintf(path, sizeof path, SUB_CACHE "/%s.sum", key);
        int r = u_read(path, old, sizeof old - 1);
        if (r > 0) old[r] = 0;
        if (!strcmp(old, sum)) { free(uris); continue; }                            /* unchanged since the last push */

        char *b64 = b64_encode((const uint8_t *)uris, un);
        free(uris);
        if (!b64) { fail++; continue; }
        size_t cl = strlen(cat), bl = strlen(b64);
        char *line = malloc(cl + bl + 3);
        if (line) {
            size_t k = (size_t)snprintf(line, cl + bl + 3, "%s:%s\n", cat, b64);
            snprintf(path, sizeof path, SUB_CACHE "/%s.line", key);
            if (write_file(path, line, k, 0600) == 0) {
                snprintf(path, sizeof path, SUB_CACHE "/%s.sum", key);
                write_file(path, sum, strlen(sum), 0600);
                snprintf(path, sizeof path, SUB_CACHE "/%s.dirty", key);
                write_file(path, "", 0, 0600);
            } else { fail++; }
            free(line);
        }
        free(b64);
    }
    free(list);
    if (worker_wait(stop_fd, 0)) return;
    /* Rebuild pending from every dirty entry (also the ones the WebUI has not taken yet). */
    publish_pending();
    log_msg("sublist: %d url(s), %d failed", n, fail);
}

static void sub_loop(int stop_fd)
{
    if (worker_wait(stop_fd, SUB_FIRST_DELAY_S * 1000)) return;
    for (;;) {
        sub_update_all(stop_fd);
        if (worker_wait(stop_fd, SUB_INTERVAL_S * 1000)) return;
    }
}

void sub_update_start(void) { worker_start(&g_sub, "sub-update", sub_loop); }
void sub_update_stop(void)  { worker_stop(&g_sub); }

#ifdef MV_SUBUPDATE_TEST                           /* host test: see native/tests/test_subupdate.c */
size_t mv_test_nodes(const char *b, size_t n, char **out) { size_t k; *out = sub_nodes(b, n, &k); return k; }
void   mv_test_update_all(int fd) { sub_update_all(fd); }
void   mv_test_md5(const void *d, size_t n, char o[33]) { md5_hex(d, n, o); }
#endif
