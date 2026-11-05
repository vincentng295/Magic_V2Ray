/* settings.base64 reader: same flat-scalar lookup the shell did with
 * base64|tr|sed, without any of those binaries. */
#include "common.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

static char *load(void)
{
    struct stat st;
    int fd = open(SETTINGS_FILE, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    if (fstat(fd, &st) < 0 || st.st_size <= 0 || st.st_size > (4 << 20)) { close(fd); return NULL; }
    char *raw = malloc(st.st_size + 1);
    uint8_t *dec = malloc(st.st_size + 1);
    if (!raw || !dec) { close(fd); free(raw); free(dec); return NULL; }
    ssize_t n = read(fd, raw, st.st_size);
    close(fd);
    if (n < 0) n = 0;
    size_t o = u_b64_decode(raw, n, dec);
    dec[o] = 0;
    free(raw);
    return (char *)dec;
}

static bool is_sep(char c) { return c == ',' || c == '{' || c == '}' || c == '\n'; }

/* Returns pointer just after the ':' following "key". strict = the key must
 * start a segment (what the sed anchored on); loose = grep semantics. */
static const char *find_key(const char *s, const char *key, bool strict)
{
    char needle[128];
    snprintf(needle, sizeof needle, "\"%s\"", key);
    for (const char *p = s; (p = strstr(p, needle)); p++) {
        if (strict) {
            const char *b = p;
            while (b > s && (b[-1] == ' ' || b[-1] == '\t' || b[-1] == '\r')) b--;
            if (b > s && !is_sep(b[-1])) continue;
        }
        const char *q = p + strlen(needle);
        while (*q == ' ' || *q == '\t') q++;
        if (*q == ':') return q + 1;
    }
    return NULL;
}

bool settings_present(const char *key)
{
    char *s = load();
    if (!s) return false;
    bool r = find_key(s, key, false) != NULL;
    free(s);
    return r;
}

bool settings_query(const char *key, char *out, size_t cap)
{
    char *s = load();
    out[0] = 0;
    if (!s) return false;
    const char *v = find_key(s, key, true);
    if (!v) { free(s); return false; }
    while (*v == ' ' || *v == '\t') v++;
    if (*v == '"') v++;
    size_t n = 0;
    while (v[n] && v[n] != '"' && !is_sep(v[n])) n++;
    while (n && (v[n - 1] == ' ' || v[n - 1] == '\t' || v[n - 1] == '\r')) n--;
    if (n >= cap) n = cap - 1;
    memcpy(out, v, n); out[n] = 0;
    free(s);
    return true;
}

bool settings_is_true(const char *key)   /* absent/unreadable => false (fail closed) */
{
    char v[32];
    return settings_query(key, v, sizeof v) && strcmp(v, "true") == 0;
}
