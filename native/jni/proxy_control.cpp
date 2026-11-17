// proxy_control applet: the control front-end (replaces module/proxy_control.sh).
//
//   xhuskydg_helper proxy_control <verb>
//
// A thin client of the service's control FIFO, the same scheme as `logservice read`: it creates a private reply
// FIFO next to the control pipe, writes   REQ <reply-fifo> <verb>   into the control pipe and prints the answer.
// The service runs the verb and replies   "<exit code>\n" + lines "o <stdout>" / "e <stderr>".
//
// Contract with the WebUI (unchanged from the shell script):
//   * `status` prints exactly one word on stdout (running / crashed / stopped) and encodes the same in its exit
//     code (0 / 2 / 1). Nothing else may write to stdout.
//   * diagnostics go to $DATADIR/proxy_control.log, never to stdout / the caller's stderr (usage excepted).
//   * a request never blocks forever: the service being gone is detected (ENXIO), and the wait is bounded.

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>

#include <fcntl.h>
#include <libgen.h>
#include <poll.h>
#include <sys/stat.h>
#include <unistd.h>

#include "service/paths.h"

namespace {

const char* const kVerbs[] = {
    "start", "stop", "restart", "reload", "status", "reapply",
    "start_monitor", "stop_monitor", "start_monitor_latency", "stop_monitor_latency",
    "latency_heartbeat", "reset_mobile_network",
    "traffic_poll", "traffic_readonly", "traffic_reset", "traffic_stop", "traffic_start",
};

// Verbs that take arguments: traffic_block <mac>, traffic_unblock <mac>, traffic_limit <mac> <bytes>,
// traffic_limit_all <bytes>, traffic_reset_mac <mac>. The service validates them again.
struct ArgVerb { const char* name; int args; };
const ArgVerb kArgVerbs[] = {
    {"traffic_block", 1}, {"traffic_unblock", 1}, {"traffic_limit", 2},
    {"traffic_limit_all", 1}, {"traffic_reset_mac", 1},
};

constexpr const char* kRunDir = MV_STUB_DIR MV_RUN_SUBDIR;
constexpr const char* kLogFile = MV_DATADIR "/proxy_control.log";
constexpr off_t kLogMax = 256 * 1024;
constexpr int kStatusTimeoutMs = 8000;     // answered by the service's reader thread: instant unless it is dead
constexpr int kJobTimeoutMs = 150000;      // start / restart wait for iptables, tun, xray

int g_log = -1;
bool g_debug = false;

void open_log() {
    struct stat st;
    int flags = O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC;
    if (stat(kLogFile, &st) == 0 && st.st_size > kLogMax) flags |= O_TRUNC;   // a plain cap, nobody tails it live
    g_log = open(kLogFile, flags, 0600);
}

void plog(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void plog(const char* fmt, ...) {
    if (g_log < 0) return;
    char msg[600], line[700];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    time_t now = time(nullptr);
    struct tm tm;
    localtime_r(&now, &tm);
    int n = snprintf(line, sizeof line, "%04d-%02d-%02d %02d:%02d:%02d proxy_control[%d]: %s\n",
                     tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec,
                     static_cast<int>(getpid()), msg);
    if (n > 0) (void)!write(g_log, line, static_cast<size_t>(n < static_cast<int>(sizeof line) ? n : sizeof line - 1));
}

// module.prop: debug=1 (module dir = parent of the directory holding this binary)
bool read_debug() {
    char self[256];
    ssize_t n = readlink("/proc/self/exe", self, sizeof self - 1);
    if (n <= 0) return false;
    self[n] = 0;
    std::string bindir = dirname(self);
    std::string prop = bindir.substr(0, bindir.rfind('/')) + "/module.prop";
    FILE* f = fopen(prop.c_str(), "re");
    if (!f) return false;
    char l[256];
    bool dbg = false;
    while (fgets(l, sizeof l, f))
        if (!strncmp(l, "debug=", 6)) { dbg = l[6] == '1'; break; }
    fclose(f);
    return dbg;
}

bool known_verb(const std::string& v) {
    for (const char* k : kVerbs) if (v == k) return true;
    return false;
}

// Builds "<verb> <arg>..." for an argument verb; false when the verb or its arguments are not acceptable
// (only hex digits, ':' and digits may travel through the control pipe).
bool build_arg_verb(int argc, char* argv[], std::string* out) {
    for (const ArgVerb& k : kArgVerbs) {
        if (strcmp(argv[1], k.name) != 0) continue;
        if (argc != 2 + k.args) return false;
        std::string v = k.name;
        for (int i = 2; i < argc; i++) {
            const std::string a = argv[i];
            if (a.empty() || a.size() > 17) return false;
            for (char ch : a)
                if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F') || ch == ':'))
                    return false;
            v += ' ';
            v += a;
        }
        *out = v;
        return true;
    }
    return false;
}

void usage(const char* prog) {
    std::string list;
    for (const char* k : kVerbs) { if (!list.empty()) list += '|'; list += k; }
    list += "|traffic_block <mac>|traffic_unblock <mac>|traffic_limit <mac> <bytes>|traffic_limit_all <bytes>|traffic_reset_mac <mac>";
    fprintf(stderr, "Usage: %s {%s}\n", prog, list.c_str());
}

int fail(const char* what) {
    plog("%s", what);
    return 1;
}

// Returns the service's answer: the exit code; its stdout lines are printed, stderr lines logged.
int talk(const std::string& verb) {
    const bool is_status = verb == "status";
    const std::string pipe = std::string(kRunDir) + "/control.pipe";

    int ctl = open(pipe.c_str(), O_WRONLY | O_NONBLOCK | O_CLOEXEC);          // ENXIO: nobody reads it
    if (ctl < 0) {
        if (is_status) { puts("stopped"); return 1; }                          // no service = no engine
        return fail("service is not running; reboot to recover");
    }

    const std::string reply = pipe + ".r" + std::to_string(getpid());
    unlink(reply.c_str());
    if (mkfifo(reply.c_str(), 0600) != 0) {
        close(ctl);
        plog("mkfifo %s: %s", reply.c_str(), strerror(errno));
        return 1;
    }
    int rfd = open(reply.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (rfd < 0) {
        close(ctl);
        plog("open %s: %s", reply.c_str(), strerror(errno));
        unlink(reply.c_str());
        return 1;
    }

    int rc = 1;
    const std::string req = "REQ " + reply + " " + verb + "\n";                // < PIPE_BUF, so atomic
    if (write(ctl, req.data(), req.size()) != static_cast<ssize_t>(req.size())) {
        plog("timed out sending '%s' to the service", verb.c_str());
    } else {
        std::string data;
        const int timeout = is_status ? kStatusTimeoutMs : kJobTimeoutMs;
        time_t deadline = time(nullptr) + timeout / 1000;
        bool eof = false;
        while (!eof) {
            int left = static_cast<int>((deadline - time(nullptr)) * 1000);
            if (left <= 0) { plog("'%s': no answer from the service within %d s", verb.c_str(), timeout / 1000); break; }
            struct pollfd p = {rfd, POLLIN, 0};
            int r = poll(&p, 1, left);
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) continue;                                                // timeout is re-checked above
            if (p.revents & POLLIN) {
                char tmp[1024];
                ssize_t n = read(rfd, tmp, sizeof tmp);
                if (n > 0) data.append(tmp, static_cast<size_t>(n));
                else if (n == 0) eof = true;
                else if (errno != EAGAIN && errno != EINTR) break;
            } else if (p.revents & (POLLHUP | POLLERR)) {
                eof = true;
            }
        }
        if (eof) {
            // "<rc>\n" then "o <text>" / "e <text>" lines
            size_t nl = data.find('\n');
            char* end = nullptr;
            long code = strtol(data.c_str(), &end, 10);
            if (nl == std::string::npos || end == data.c_str()) {
                plog("'%s': empty or malformed answer", verb.c_str());
            } else {
                rc = static_cast<int>(code);
                size_t pos = nl + 1;
                while (pos < data.size()) {
                    size_t e = data.find('\n', pos);
                    if (e == std::string::npos) e = data.size();
                    if (e - pos >= 2 && data[pos + 1] == ' ') {
                        std::string text = data.substr(pos + 2, e - pos - 2);
                        if (data[pos] == 'o') { fputs(text.c_str(), stdout); fputc('\n', stdout); }
                        else plog("%s: %s", verb.c_str(), text.c_str());
                    }
                    pos = e + 1;
                }
                if (g_debug) plog("'%s' -> %d", verb.c_str(), rc);
            }
        }
    }
    close(rfd);
    close(ctl);
    unlink(reply.c_str());
    fflush(stdout);
    return rc;
}

}  // namespace

int run_proxy_control(int argc, char* argv[]) {
    const char* prog = argc > 0 ? argv[0] : "proxy_control";
    open_log();
    g_debug = read_debug();
    std::string argverb;
    if (argc >= 2 && build_arg_verb(argc, argv, &argverb)) return talk(argverb);
    if (argc < 2 || !known_verb(argv[1])) {
        usage(prog);
        plog("bad usage: %s", argc >= 2 ? argv[1] : "(no verb)");
        return 2;
    }
    return talk(argv[1]);
}
