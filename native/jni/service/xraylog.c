/* xray's output, kept in the service itself (this replaces the separate `logservice` daemon).
 *
 * An anonymous pipe, no FIFO file and no control pipe of its own: the service creates it once (xlog_init), hands
 * the WRITE end to xray as stdout / stderr (it is inherited, so xray running as uid 9999 needs no path
 * and no permission for it) and keeps a thread reading the other end into a ring buffer in RAM. The service's own
 * log_msg() lines go into the same pipe. The service keeps the write end open for its whole life, so the reader
 * never sees EOF when xray exits or is replaced.
 *
 * The ring keeps the LAST max_lines lines (default 1000), older ones are dropped. Clients ask for it through the
 * service's normal control pipe (`proxy_control log_read N`, `log_flush`), see control.c. */
#include "service.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

enum { DEFAULT_MAX_LINES = 1000, MAX_LINES_LIMIT = 100000, MAX_LINE_LEN = 64 * 1024 };

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static char **g_ring;                     /* g_cap slots, g_head = next slot to write */
static size_t g_cap = DEFAULT_MAX_LINES, g_head, g_cnt;
static int g_rfd = -1, g_wfd = -1;

static void ring_free_all(void)
{
    for (size_t i = 0; i < g_cap; i++) { free(g_ring[i]); g_ring[i] = NULL; }
    g_head = g_cnt = 0;
}

/* g_mu held. Takes ownership of `line`. */
static void ring_push(char *line)
{
    free(g_ring[g_head]);                 /* the oldest line when the ring is full */
    g_ring[g_head] = line;
    g_head = (g_head + 1) % g_cap;
    if (g_cnt < g_cap) g_cnt++;
}

static void push_line(const char *s, size_t n)
{
    char *l = malloc(n + 1);
    if (!l) return;
    memcpy(l, s, n); l[n] = 0;
    pthread_mutex_lock(&g_mu);
    ring_push(l);
    pthread_mutex_unlock(&g_mu);
}

static void *reader(void *arg)
{
    (void)arg;
    char buf[8192];
    char *acc = malloc(MAX_LINE_LEN);       /* the line being assembled across read()s */
    size_t alen = 0;
    if (!acc) return NULL;
    for (;;) {
        ssize_t r = read(g_rfd, buf, sizeof buf);
        if (r < 0) { if (errno == EINTR) continue; break; }
        if (r == 0) break;                  /* cannot happen while the service holds the write end */
        for (ssize_t i = 0; i < r; i++) {
            if (buf[i] == '\n') { push_line(acc, alen); alen = 0; }
            else if (alen < MAX_LINE_LEN - 1) acc[alen++] = buf[i];
            else { push_line(acc, alen); alen = 0; acc[alen++] = buf[i]; }   /* an overlong line is split */
        }
    }
    free(acc);
    return NULL;
}

/* Creates the pipe and starts the reader thread. Returns the write end (CLOEXEC; the child's dup2 clears it),
 * or -1. Call once, before anything logs into it. */
int xlog_init(void)
{
    int p[2];
    if (g_wfd >= 0) return g_wfd;
    g_ring = calloc(g_cap, sizeof *g_ring);
    if (!g_ring) return -1;
    if (pipe2(p, O_CLOEXEC) < 0) return -1;
    g_rfd = p[0]; g_wfd = p[1];
    pthread_t th;
    pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &at, reader, NULL) != 0) { close(g_rfd); close(g_wfd); g_rfd = g_wfd = -1; return -1; }
    return g_wfd;
}

void xlog_flush(void)
{
    pthread_mutex_lock(&g_mu);
    if (g_ring) ring_free_all();
    pthread_mutex_unlock(&g_mu);
}

/* Resizes the ring, keeping the newest lines. */
int xlog_set_max(size_t n)
{
    if (n == 0 || n > MAX_LINES_LIMIT) return -EINVAL;
    pthread_mutex_lock(&g_mu);
    if (!g_ring || n == g_cap) { pthread_mutex_unlock(&g_mu); return g_ring ? 0 : -ENODEV; }
    char **nr = calloc(n, sizeof *nr);
    if (!nr) { pthread_mutex_unlock(&g_mu); return -ENOMEM; }
    size_t keep = g_cnt < n ? g_cnt : n;
    for (size_t i = 0; i < g_cnt; i++) {   /* oldest first */
        char *l = g_ring[(g_head + g_cap - g_cnt + i) % g_cap];
        if (i >= g_cnt - keep) nr[i - (g_cnt - keep)] = l; else free(l);
    }
    free(g_ring);
    g_ring = nr; g_cap = n; g_cnt = keep; g_head = keep % n;
    pthread_mutex_unlock(&g_mu);
    return 0;
}

/* Writes the newest `n` buffered lines (0 = all) to `path`, replacing it. The text is copied out of the ring first
 * so the reader thread is never held up by a slow disk. Returns 0 or -errno. */
int xlog_dump(const char *path, size_t n)
{
    pthread_mutex_lock(&g_mu);
    size_t take = (n == 0 || n > g_cnt) ? g_cnt : n, total = 0;
    char *out = NULL;
    if (g_ring && take) {
        for (size_t i = 0; i < take; i++) total += strlen(g_ring[(g_head + g_cap - take + i) % g_cap]) + 1;
        out = malloc(total);
        if (!out) { pthread_mutex_unlock(&g_mu); return -ENOMEM; }
        size_t off = 0;
        for (size_t i = 0; i < take; i++) {
            const char *l = g_ring[(g_head + g_cap - take + i) % g_cap];
            size_t len = strlen(l);
            memcpy(out + off, l, len); out[off + len] = '\n'; off += len + 1;
        }
    }
    pthread_mutex_unlock(&g_mu);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) { int e = errno; free(out); return -e; }
    int rc = 0;
    for (size_t off = 0; off < total; ) {
        ssize_t w = write(fd, out + off, total - off);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) { rc = -errno; break; }
        off += (size_t)w;
    }
    close(fd);
    free(out);
    return rc;
}

/* What xray's last run printed, kept for the UI after a stop / before a restart. */
void xlog_save(const char *path) { (void)xlog_dump(path, 0); }
