/* Latency monitor (service.sh section 6). Probes through xray's SOCKS5 inbound
 * with a built-in client; the UI heartbeat bounds how long it keeps running.
 * NOTE: curl did an HTTPS probe; without a TLS stack we use plain HTTP to the
 * same generate_204 endpoint, so the number excludes the TLS handshake. */
#include "service.h"
#include "netlink.h"
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define HOST "gstatic.com"
static worker_t g_mon;

static bool xfer(int fd, const void *out, size_t on, void *in, size_t inn)
{
    if (on && send(fd, out, on, MSG_NOSIGNAL) != (ssize_t)on) return false;
    size_t got = 0;
    while (got < inn) {
        ssize_t r = recv(fd, (char *)in + got, inn - got, 0);
        if (r <= 0) return false;
        got += r;
    }
    return true;
}

/* seconds until the first response byte, or <0 on failure */
static double probe(void)
{
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct timeval tv = { 3, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    struct sockaddr_in a = { .sin_family = AF_INET, .sin_port = htons(TUN_PORT) };
    inet_pton(AF_INET, TUN_ADDR, &a.sin_addr);
    int64_t t0 = u_mono_ms();
    struct timespec s; clock_gettime(CLOCK_MONOTONIC, &s);
    double r = -1;
    uint8_t buf[300], rep[4];
    if (connect(fd, (struct sockaddr *)&a, sizeof a) < 0) goto out;
    if (!xfer(fd, "\x05\x01\x00", 3, buf, 2) || buf[1] != 0) goto out;
    size_t n = 0; buf[n++] = 5; buf[n++] = 1; buf[n++] = 0; buf[n++] = 3;
    buf[n++] = strlen(HOST); memcpy(buf + n, HOST, strlen(HOST)); n += strlen(HOST);
    buf[n++] = 0; buf[n++] = 80;
    if (!xfer(fd, buf, n, rep, 4) || rep[1] != 0) goto out;
    size_t rest = rep[3] == 1 ? 6 : rep[3] == 4 ? 18 : 0;           /* addr + port */
    if (rep[3] == 3) { uint8_t l; if (!xfer(fd, NULL, 0, &l, 1)) goto out; rest = l + 2; }
    if (rest && !xfer(fd, NULL, 0, buf, rest)) goto out;
    static const char req[] = "GET /generate_204 HTTP/1.1\r\nHost: " HOST "\r\nConnection: close\r\n\r\n";
    if (!xfer(fd, req, sizeof req - 1, buf, 1)) goto out;
    struct timespec e; clock_gettime(CLOCK_MONOTONIC, &e);
    r = (e.tv_sec - s.tv_sec) + (e.tv_nsec - s.tv_nsec) / 1e9;
    (void)t0;
out:
    close(fd);
    return r;
}

static void monitor_main(int stop_fd)
{
    char ifn[64];
    u_write(TIME_RES_FILE, "");
    u_writef(LATENCY_HB_FILE, "%ld\n", u_now_s());
    if (get_active_interface(ifn, sizeof ifn) == 0) { char *t = nl_addr_show(ifn); if (t) { u_write(ADDR_INFO_FILE, t); free(t); } }
    while (u_exists(TIME_RES_FILE)) {
        char hb[32] = "0"; u_read(LATENCY_HB_FILE, hb, sizeof hb);
        long age = u_now_s() - atol(hb);
        if (age > LATENCY_HB_TIMEOUT) { log_msg("latency monitor: no UI heartbeat for %lds, stopping", age); break; }
        double r = probe();
        u_writef(TIME_RES_FILE, "%.6f", r < 0 ? 0.0 : r);
        if (worker_wait(stop_fd, LATENCY_INTERVAL * 1000)) break;
    }
    unlink(TIME_RES_FILE); unlink(LATENCY_HB_FILE);
    log_msg("latency monitor exiting");
}

void latency_monitor_stop(void)
{
    worker_stop(&g_mon);
    unlink(TIME_RES_FILE); unlink(LATENCY_HB_FILE);
}
void latency_monitor_start(void)
{
    latency_monitor_stop();
    if (worker_start(&g_mon, "monitor_network_latency", monitor_main) == 0) log_msg("latency monitor running");
}
