// logservice applet: a small ring-buffer log daemon fed through a FIFO.
// The buffer always keeps the LAST max_lines lines; older lines are dropped.
//
//   logservice create -c CTL -i IN [-d]            run daemon (-d: daemonize)
//   logservice set    -c CTL -m N                  set max lines kept (default 1000)
//   logservice read   -c CTL [-n N] [-o OUT]       dump buffered log, newest N lines
//                                                  (default: all; OUT default /proc/self/fd/0)
//   logservice flush  -c CTL                       clear buffer
//   logservice stop   -c CTL                       stop daemon
//
// Control protocol (text lines written to the control pipe):
//   SET <n> | READ <n> <reply-fifo> | FLUSH | STOP      (READ n=0 means all)

#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iostream>
#include <string>

#include <fcntl.h>
#include <poll.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr size_t kDefaultMaxLines = 1000;
constexpr size_t kMaxLineLen = 64 * 1024;

volatile sig_atomic_t g_stop = 0;
void on_signal(int) { g_stop = 1; }

struct Args {
    std::string control, input, output = "/proc/self/fd/0";
    size_t max_lines = 0;
    size_t tail_lines = 0;  // 0 = all buffered lines
    bool has_max = false;
    bool daemonize = false;
};

void usage(const char* prog) {
    std::cerr
        << "Usage:\n"
        << "  " << prog << " create -c|--control-pipe CTL -i|--input-pipe IN [-d|--daemonize]\n"
        << "  " << prog << " set    -c CTL -m|--max-lines N\n"
        << "  " << prog << " read   -c CTL [-n|--lines N] [-o|--output PATH]   (default /proc/self/fd/0)\n"
        << "  " << prog << " flush  -c CTL\n"
        << "  " << prog << " stop   -c CTL\n";
}

bool parse_positive(const std::string& s, const char* name, size_t& out) {
    char* end = nullptr;
    long n = strtol(s.c_str(), &end, 10);
    if (!end || end == s.c_str() || *end || n <= 0) {
        std::cerr << "Invalid " << name << ": " << s << "\n";
        return false;
    }
    out = static_cast<size_t>(n);
    return true;
}

// Returns false on error. Supports "--opt value", "--opt=value", "-o value".
bool parse_args(int argc, char* argv[], Args& a) {
    for (int i = 2; i < argc; ++i) {
        std::string k = argv[i], v;
        bool inline_val = false;
        size_t eq = k.find('=');
        if (k.rfind("--", 0) == 0 && eq != std::string::npos) {
            v = k.substr(eq + 1);
            k = k.substr(0, eq);
            inline_val = true;
        }
        auto need = [&](std::string& out) {
            if (inline_val) { out = v; return true; }
            if (i + 1 >= argc) return false;
            out = argv[++i];
            return true;
        };
        std::string val;
        if (k == "-c" || k == "--control-pipe") {
            if (!need(a.control)) return false;
        } else if (k == "-i" || k == "--input-pipe") {
            if (!need(a.input)) return false;
        } else if (k == "-o" || k == "--output") {
            if (!need(a.output)) return false;
        } else if (k == "-m" || k == "--max-lines") {
            if (!need(val)) return false;
            if (!parse_positive(val, "--max-lines", a.max_lines)) return false;
            a.has_max = true;
        } else if (k == "-n" || k == "--lines") {
            if (!need(val)) return false;
            if (!parse_positive(val, "--lines", a.tail_lines)) return false;
        } else if (k == "-d" || k == "--daemonize") {
            a.daemonize = true;
        } else {
            std::cerr << "Unknown option: " << k << "\n";
            return false;
        }
    }
    return true;
}

bool ensure_fifo(const std::string& path) {
    struct stat st;
    if (stat(path.c_str(), &st) == 0) {
        if (S_ISFIFO(st.st_mode)) return true;
        std::cerr << path << " exists and is not a FIFO\n";
        return false;
    }
    if (mkfifo(path.c_str(), 0600) != 0) {
        std::cerr << "mkfifo " << path << ": " << strerror(errno) << "\n";
        return false;
    }
    return true;
}

// ---------------------------------------------------------------- daemon

class LogBuffer {
public:
    void set_max(size_t n) {
        max_ = n;
        trim();
    }
    void clear() { lines_.clear(); partial_.clear(); }

    void feed(const char* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            char c = data[i];
            if (c == '\n') {
                lines_.push_back(std::move(partial_));
                partial_.clear();
                trim();
            } else {
                partial_.push_back(c);
                if (partial_.size() >= kMaxLineLen) {
                    lines_.push_back(std::move(partial_));
                    partial_.clear();
                    trim();
                }
            }
        }
    }

    // Newest `tail` lines (0 = all), never more than max_. The unfinished
    // line (partial_) counts as the newest line.
    std::string dump(size_t tail = 0) const {
        size_t total = lines_.size() + (partial_.empty() ? 0 : 1);
        size_t want = max_;
        if (tail > 0 && tail < want) want = tail;
        size_t skip = total > want ? total - want : 0;

        std::string out;
        for (size_t i = skip; i < lines_.size(); ++i) {
            out += lines_[i];
            out += '\n';
        }
        if (!partial_.empty()) { out += partial_; out += '\n'; }
        return out;
    }

private:
    void trim() {
        while (lines_.size() > max_) lines_.pop_front();
    }
    std::deque<std::string> lines_;
    std::string partial_;
    size_t max_ = kDefaultMaxLines;
};

void send_dump(const std::string& fifo_path, const std::string& data) {
    int fd = open(fifo_path.c_str(), O_WRONLY | O_NONBLOCK);
    if (fd < 0) return;  // client gone
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISFIFO(st.st_mode)) { close(fd); return; }

    size_t off = 0;
    while (off < data.size()) {
        ssize_t n = write(fd, data.data() + off, data.size() - off);
        if (n > 0) { off += static_cast<size_t>(n); continue; }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && errno == EAGAIN) {
            struct pollfd p = {fd, POLLOUT, 0};
            if (poll(&p, 1, 2000) <= 0) break;  // client stalled
            continue;
        }
        break;  // EPIPE etc.
    }
    close(fd);
}

// Returns true if the daemon should stop.
bool handle_command(const std::string& line, LogBuffer& buf) {
    if (line == "STOP") return true;
    if (line == "FLUSH") { buf.clear(); return false; }
    if (line.rfind("SET ", 0) == 0) {
        long n = strtol(line.c_str() + 4, nullptr, 10);
        if (n > 0) buf.set_max(static_cast<size_t>(n));
        return false;
    }
    if (line.rfind("READ ", 0) == 0) {
        // READ <n> <reply-fifo>
        const char* p = line.c_str() + 5;
        char* end = nullptr;
        unsigned long n = strtoul(p, &end, 10);
        if (end == p || *end != ' ' || *(end + 1) == '\0') return false;  // malformed
        send_dump(std::string(end + 1), buf.dump(static_cast<size_t>(n)));
        return false;
    }
    return false;
}

int run_daemon(const Args& a) {
    if (a.control.empty() || a.input.empty()) {
        std::cerr << "create requires --control-pipe and --input-pipe\n";
        return 1;
    }
    if (!ensure_fifo(a.control) || !ensure_fifo(a.input)) return 1;

    // O_RDWR keeps the FIFOs open so writers (xray restarts, clients) never
    // see EOF/ENXIO while the daemon lives.
    int in_fd = open(a.input.c_str(), O_RDWR | O_NONBLOCK);
    int ctl_fd = open(a.control.c_str(), O_RDWR | O_NONBLOCK);
    if (in_fd < 0 || ctl_fd < 0) {
        std::cerr << "open pipes: " << strerror(errno) << "\n";
        return 1;
    }

    if (a.daemonize) {
        pid_t pid = fork();
        if (pid < 0) { std::cerr << "fork: " << strerror(errno) << "\n"; return 1; }
        if (pid > 0) return 0;
        setsid();
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) {
            dup2(nul, 0); dup2(nul, 1); dup2(nul, 2);
            if (nul > 2) close(nul);
        }
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGTERM, &sa, nullptr);
    signal(SIGPIPE, SIG_IGN);

    LogBuffer buf;
    std::string ctl_acc;
    char tmp[8192];
    struct pollfd fds[2] = {{in_fd, POLLIN, 0}, {ctl_fd, POLLIN, 0}};

    while (!g_stop) {
        int r = poll(fds, 2, -1);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[0].revents & POLLIN) {
            ssize_t n = read(in_fd, tmp, sizeof(tmp));
            if (n > 0) buf.feed(tmp, static_cast<size_t>(n));
        }
        if (fds[1].revents & POLLIN) {
            ssize_t n = read(ctl_fd, tmp, sizeof(tmp));
            if (n > 0) {
                ctl_acc.append(tmp, static_cast<size_t>(n));
                size_t pos;
                while ((pos = ctl_acc.find('\n')) != std::string::npos) {
                    std::string line = ctl_acc.substr(0, pos);
                    ctl_acc.erase(0, pos + 1);
                    if (handle_command(line, buf)) { g_stop = 1; break; }
                }
                if (ctl_acc.size() > 4096) ctl_acc.clear();
            }
        }
    }

    close(in_fd);
    close(ctl_fd);
    unlink(a.control.c_str());
    unlink(a.input.c_str());
    return 0;
}

// ---------------------------------------------------------------- client

bool send_control(const std::string& ctl, const std::string& msg) {
    int fd = open(ctl.c_str(), O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        if (errno == ENXIO)
            std::cerr << "logservice daemon is not running (" << ctl << ")\n";
        else
            std::cerr << "open " << ctl << ": " << strerror(errno) << "\n";
        return false;
    }
    std::string line = msg + "\n";  // < PIPE_BUF, so atomic
    ssize_t n = write(fd, line.data(), line.size());
    close(fd);
    if (n != static_cast<ssize_t>(line.size())) {
        std::cerr << "write control pipe failed\n";
        return false;
    }
    return true;
}

int run_read(const Args& a) {
    // Open the destination first so errors surface before touching the daemon.
    // "/proc/self/fd/N", "/dev/fd/N", "-" refer to an fd we already own, so
    // dup() it instead of re-opening (re-open fails with ENXIO on sockets/pipes).
    int out = -1;
    {
        const std::string& p = a.output;
        const char* num = nullptr;
        if (p == "-") num = "1";
        else if (p.rfind("/proc/self/fd/", 0) == 0) num = p.c_str() + 14;
        else if (p.rfind("/dev/fd/", 0) == 0) num = p.c_str() + 8;
        if (num && *num) {
            char* end = nullptr;
            long fdn = strtol(num, &end, 10);
            if (end && !*end && fdn >= 0) out = dup(static_cast<int>(fdn));
        } else {
            out = open(p.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
        }
    }
    if (out < 0) {
        std::cerr << "open " << a.output << ": " << strerror(errno) << "\n";
        return 1;
    }

    std::string reply = a.control + ".r" + std::to_string(getpid());
    unlink(reply.c_str());
    if (mkfifo(reply.c_str(), 0600) != 0) {
        std::cerr << "mkfifo " << reply << ": " << strerror(errno) << "\n";
        close(out);
        return 1;
    }
    int rfd = open(reply.c_str(), O_RDONLY | O_NONBLOCK);
    if (rfd < 0) {
        std::cerr << "open " << reply << ": " << strerror(errno) << "\n";
        unlink(reply.c_str());
        close(out);
        return 1;
    }

    int rc = 0;
    bool fell_back = false;
    if (!send_control(a.control, "READ " + std::to_string(a.tail_lines) + " " + reply)) {
        rc = 1;
    } else {
        char tmp[8192];
        for (;;) {
            struct pollfd p = {rfd, POLLIN, 0};
            int r = poll(&p, 1, 5000);
            if (r < 0 && errno == EINTR) continue;
            if (r == 0) {
                std::cerr << "timeout waiting for daemon\n";
                rc = 1;
                break;
            }
            if (r < 0) { rc = 1; break; }
            ssize_t n = read(rfd, tmp, sizeof(tmp));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(out, tmp + off, static_cast<size_t>(n - off));
                    if (w < 0) {
                        if (errno == EINTR) continue;
                        // e.g. fd 0 is a read-only pipe: fall back to stdout once.
                        if (!fell_back) {
                            fell_back = true;
                            int alt = dup(1);
                            if (alt >= 0) { close(out); out = alt; continue; }
                        }
                        rc = 1;
                        break;
                    }
                    off += w;
                }
                if (rc) break;
            } else if (n == 0) {
                break;  // daemon closed its end: done
            } else if (errno != EAGAIN && errno != EINTR) {
                rc = 1;
                break;
            }
        }
    }
    close(rfd);
    close(out);
    unlink(reply.c_str());
    return rc;
}

}  // namespace

int run_logservice(int argc, char* argv[]) {
    const char* prog = argc > 0 ? argv[0] : "logservice";
    if (argc < 2) { usage(prog); return 1; }

    std::string action = argv[1];
    Args a;
    if (!parse_args(argc, argv, a)) { usage(prog); return 1; }

    if (a.control.empty()) {
        std::cerr << "--control-pipe is required\n";
        usage(prog);
        return 1;
    }

    if (action == "create") return run_daemon(a);
    if (action == "read") return run_read(a);
    if (action == "flush") return send_control(a.control, "FLUSH") ? 0 : 1;
    if (action == "stop") return send_control(a.control, "STOP") ? 0 : 1;
    if (action == "set") {
        if (!a.has_max) {
            std::cerr << "set requires --max-lines\n";
            return 1;
        }
        return send_control(a.control, "SET " + std::to_string(a.max_lines)) ? 0 : 1;
    }

    std::cerr << "Unknown logservice action: " << action << "\n";
    usage(prog);
    return 1;
}