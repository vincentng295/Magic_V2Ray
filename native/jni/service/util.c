#include "common.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <dirent.h>
#include <time.h>
#include <unistd.h>

int u_read(const char *path, char *buf, size_t cap)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t got = 0; ssize_t r;
    while (got + 1 < cap && (r = read(fd, buf + got, cap - 1 - got)) > 0) got += r;
    close(fd);
    buf[got] = 0;
    return (int)got;
}

int u_write(const char *path, const char *s)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    size_t l = strlen(s);
    int rc = write(fd, s, l) == (ssize_t)l ? 0 : -1;
    close(fd);
    return rc;
}

int u_writef(const char *path, const char *fmt, ...)
{
    char b[512]; va_list ap;
    va_start(ap, fmt); vsnprintf(b, sizeof b, fmt, ap); va_end(ap);
    return u_write(path, b);
}

bool u_exists(const char *p) { return access(p, F_OK) == 0; }

/* strip leading/trailing whitespace (incl. CR) in place */
void u_trim(char *s)
{
    size_t n = strlen(s), i = 0;
    while (n && isspace((unsigned char)s[n - 1])) s[--n] = 0;
    while (s[i] && isspace((unsigned char)s[i])) i++;
    if (i) memmove(s, s + i, strlen(s + i) + 1);
}

void u_msleep(unsigned ms)
{
    struct timespec t = { ms / 1000, (ms % 1000) * 1000000L };
    while (nanosleep(&t, &t) < 0 && errno == EINTR) {}
}

int u_mkdir_p(const char *path, mode_t mode)
{
    char tmp[512]; snprintf(tmp, sizeof tmp, "%s", path);
    for (char *p = tmp + 1; *p; p++)
        if (*p == '/') { *p = 0; mkdir(tmp, mode); *p = '/'; }
    return mkdir(tmp, mode) == 0 || errno == EEXIST ? 0 : -1;
}

void u_clear_dir(const char *path)
{
    DIR *d = opendir(path); struct dirent *e; char p[512];
    if (!d) return;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        snprintf(p, sizeof p, "%s/%s", path, e->d_name);
        unlink(p);
    }
    closedir(d);
}

size_t u_b64_decode(const char *in, size_t n, uint8_t *out)
{
    static const char T[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    uint32_t acc = 0; int bits = 0; size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        if (in[i] == '=') break;
        const char *p = in[i] ? strchr(T, in[i]) : NULL;
        if (!p) continue;                       /* skip newlines/garbage */
        acc = (acc << 6) | (uint32_t)(p - T); bits += 6;
        if (bits >= 8) { bits -= 8; out[o++] = (acc >> bits) & 0xff; }
    }
    return o;
}

/* Run a helper binary (our own, never busybox) and wait. Output goes to the log. */
int u_run(char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        int nul = open("/dev/null", O_RDONLY);
        int lf = open(SERVICE_LOG, O_WRONLY | O_APPEND);
        if (nul >= 0) dup2(nul, 0);
        if (lf >= 0) { dup2(lf, 1); dup2(lf, 2); }
        execv(argv[0], argv);
        _exit(127);
    }
    int st;
    while (waitpid(pid, &st, 0) < 0 && errno == EINTR) {}
    return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
}

long u_now_s(void) { return (long)time(NULL); }

int64_t u_mono_ms(void)
{
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
