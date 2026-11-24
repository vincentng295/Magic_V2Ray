/* Host test of the subscription updater: md5, subscription body decoding and a full round against a fake
 * curl applet (a shell script standing in for g_self).
 *   gcc -D_GNU_SOURCE -DMV_SUBUPDATE_TEST -DMV_DATADIR='"/tmp/mvt/data"' -Wall -Wextra -Ijni/service \
 *       -o /tmp/test_subupdate tests/test_subupdate.c jni/service/subupdate.c jni/service/util.c jni/service/worker.c -lpthread
 *   ./tests/test_subupdate.sh /tmp/test_subupdate                                                                    */
#include "service.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>

char g_moddir[256], g_self[256]; bool g_debug; pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
void log_msg(const char *fmt, ...) { va_list ap; va_start(ap, fmt); fputs("[log] ", stderr); vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); }
int log_fd(void) { return -1; }

size_t mv_test_nodes(const char *b, size_t n, char **out);
void mv_test_md5(const void *d, size_t n, char o[33]);
void mv_test_update_all(int fd);

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)

int main(int argc, char **argv)
{
    char h[33];
    mv_test_md5("", 0, h);            CHECK(!strcmp(h, "d41d8cd98f00b204e9800998ecf8427e"));
    mv_test_md5("abc", 3, h);         CHECK(!strcmp(h, "900150983cd24fb0d6963f7d28e17f72"));
    char big[200]; memset(big, 'a', sizeof big);
    mv_test_md5(big, 200, h);         CHECK(!strcmp(h, "887f30b43b2867f4a9accceee7d16e6c"));      /* 200 x 'a': two blocks, checked with md5sum */

    char *o; size_t n;
    const char *plain = "vless://a#x\r\nsome junk\r\ntrojan://b#y\r\n";
    n = mv_test_nodes(plain, strlen(plain), &o);
    CHECK(n == strlen("vless://a#x\ntrojan://b#y\n") && !strcmp(o, "vless://a#x\ntrojan://b#y\n")); free(o);
    /* base64("ss://one\nvmess://two\n") std, unpadded and url-safe variants */
    const char *b64 = "c3M6Ly9vbmUKdm1lc3M6Ly90d28K";
    n = mv_test_nodes(b64, strlen(b64), &o); CHECK(!strcmp(o, "ss://one\nvmess://two\n")); free(o);
    const char *b64n = "c3M6Ly9vbmUKdm1lc3M6Ly90d28K\n";
    n = mv_test_nodes(b64n, strlen(b64n), &o); CHECK(n == 21); free(o);
    const char *nop = "<html>nope</html>";
    n = mv_test_nodes(nop, strlen(nop), &o); CHECK(n == 0); free(o);

    if (argc > 1) {                                   /* round against the fake curl */
        snprintf(g_self, sizeof g_self, "%s", argv[1]);
        int fd = eventfd(0, EFD_CLOEXEC);
        mv_test_update_all(fd);
    }
    puts("ok");
    return 0;
}
