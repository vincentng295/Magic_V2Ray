// tcping: time a bare TCP handshake to host:port. Port of v2rayNG's
// SpeedtestManager.socketConnectTime() (Socket.connect with a timeout) for the
// "TCPing" node test and for the pre-check of the "Check HTTP" (real delay) test.
//
// Usage: tcping <host[,host...]> <port> [timeout_ms]
//
// A comma-separated list (the UI passes the IPs it already resolved) is tried in order within
// the one timeout.
//
// Prints a single line: the connect time in milliseconds (>= 1), or -1 when the
// host does not resolve / the port is closed / the timeout hit.
// Exit: 0 = connected, 1 = failed (-1 printed), 2 = usage error.
//
// Differences from the Java original:
//  * the name is resolved BEFORE the clock starts, so the number is the TCP
//    handshake alone (Java's InetSocketAddress(host, port) hides the DNS time in it);
//  * the socket carries fwmark 255 (the mark xray's own outbound sockets use, see
//    FWMARK in service/common.h), so in root mode the probe leaves through the real
//    interface instead of being looped into the proxy by the tproxy/tun rules and
//    measuring the tunnel. Without CAP_NET_ADMIN (non-root mode, where the app is
//    already outside the VPN) the setsockopt fails and is ignored.

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

const int kProbeMark = 255;
const int kDefaultTimeoutMs = 1000;   // v2rayNG: socketConnectTime(url, port, 1000)

int64_t now_us() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

// One non-blocking connect with an absolute deadline (CLOCK_MONOTONIC, microseconds).
// Returns elapsed microseconds, or -1.
int64_t connect_once(const addrinfo* ai, int64_t deadline_us) {
    int fd = socket(ai->ai_family, SOCK_STREAM | SOCK_CLOEXEC, ai->ai_protocol);
    if (fd < 0) return -1;
    int mark = kProbeMark;
    setsockopt(fd, SOL_SOCKET, SO_MARK, &mark, sizeof(mark));   // best effort
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl < 0 || fcntl(fd, F_SETFL, fl | O_NONBLOCK) < 0) { close(fd); return -1; }

    int64_t start = now_us();
    int r = connect(fd, ai->ai_addr, ai->ai_addrlen);
    if (r < 0 && errno != EINPROGRESS && errno != EINTR) { close(fd); return -1; }
    if (r < 0) {
        for (;;) {
            int64_t left_ms = (deadline_us - now_us() + 999) / 1000;
            if (left_ms <= 0) { close(fd); return -1; }
            pollfd pf = { fd, POLLOUT, 0 };
            int pr = poll(&pf, 1, (int)left_ms);
            if (pr < 0 && errno == EINTR) continue;
            if (pr <= 0) { close(fd); return -1; }
            break;
        }
        int err = 0;
        socklen_t len = sizeof(err);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len) < 0 || err != 0) { close(fd); return -1; }
    }
    int64_t elapsed = now_us() - start;
    close(fd);
    return elapsed;
}

}  // namespace

int run_tcping(int argc, char* argv[]) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <host> <port> [timeout_ms]\n", argc > 0 ? argv[0] : "tcping");
        return 2;
    }
    std::string hostArg = argv[1];
    char* end = nullptr;
    long port = strtol(argv[2], &end, 10);
    if (!end || *end || port < 1 || port > 65535) { fprintf(stderr, "bad port\n"); return 2; }
    long timeout_ms = kDefaultTimeoutMs;
    if (argc > 3) {
        timeout_ms = strtol(argv[3], &end, 10);
        if (!end || *end || timeout_ms < 1) timeout_ms = kDefaultTimeoutMs;
        if (timeout_ms > 60000) timeout_ms = 60000;
    }

    addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_NUMERICSERV;
    char portstr[8];
    snprintf(portstr, sizeof(portstr), "%ld", port);

    std::vector<addrinfo*> lists;
    for (size_t pos = 0; pos <= hostArg.size();) {
        size_t comma = hostArg.find(',', pos);
        if (comma == std::string::npos) comma = hostArg.size();
        std::string host = hostArg.substr(pos, comma - pos);
        pos = comma + 1;
        if (host.size() > 2 && host.front() == '[' && host.back() == ']') host = host.substr(1, host.size() - 2);
        if (host.empty()) continue;
        addrinfo* res = nullptr;
        if (getaddrinfo(host.c_str(), portstr, &hints, &res) == 0 && res) lists.push_back(res);
    }

    // The timeout budgets the connect phase only (DNS is already done).
    int64_t deadline = now_us() + (int64_t)timeout_ms * 1000;
    int64_t best = -1;
    for (addrinfo* res : lists) {
        for (const addrinfo* ai = res; ai && best < 0; ai = ai->ai_next) {
            if (ai->ai_family != AF_INET && ai->ai_family != AF_INET6) continue;
            if (now_us() >= deadline) break;
            best = connect_once(ai, deadline);
        }
        if (best >= 0 || now_us() >= deadline) break;
    }
    for (addrinfo* res : lists) freeaddrinfo(res);

    if (best < 0) {
        puts("-1");
        return 1;
    }
    long long ms = (best + 999) / 1000;     // round up: a loopback connect must not read "0ms"
    if (ms < 1) ms = 1;
    printf("%lld\n", ms);
    return 0;
}
