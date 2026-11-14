// obsprobe: read xray's ObservatoryService.GetOutboundStatus over plain-text HTTP/2
// (h2c, prior knowledge) with no gRPC library. Replaces the old
// `curl --socks5-hostname ... generate_204` node probe: xray's observatory probes
// each outbound itself (dispatcher -> tagged outbound, routing bypassed), and this
// applet only collects the result.
//
// Usage: obsprobe <ipv4:port> <timeout_ms> <tag> [<tag>...]
//
// Connects to the xray `api.listen` address (retrying until xray is up), polls
// GetOutboundStatus until every <tag> has a probe result or the timeout hits, then
// prints one line per outbound the observatory reported:
//     <tag>\t<alive: 0|1>\t<delay_ms>
// Exit: 0 = every wanted tag reported, 2 = timeout with a partial result,
//       1 = usage error / could not talk to xray at all (nothing printed).
//
// Wire format (Xray-core app/observatory):
//   GetOutboundStatusResponse { ObservationResult status = 1; }
//   ObservationResult         { repeated OutboundStatus status = 1; }
//   OutboundStatus            { bool alive = 1; int64 delay = 2; string last_error_reason = 3;
//                               string outbound_tag = 4; int64 last_seen_time = 5;
//                               int64 last_try_time = 6; ... }
// proto3 omits zero values, so a dead outbound has no `alive` field at all.

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
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

using Bytes = std::vector<uint8_t>;

const char kPath[] = "/xray.core.app.observatory.command.ObservatoryService/GetOutboundStatus";
const size_t kMaxFrame = 1u << 20;     // refuse absurd frames instead of allocating them
const uint32_t kWindow = 1u << 20;     // our receive window (connection and stream)

int64_t now_ms() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}

bool send_all(int fd, const Bytes& b) {
    size_t off = 0;
    while (off < b.size()) {
        ssize_t n = send(fd, b.data() + off, b.size() - off, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        off += (size_t)n;
    }
    return true;
}

bool recv_all(int fd, uint8_t* p, size_t n, int64_t deadline) {
    while (n) {
        int64_t left = deadline - now_ms();
        if (left <= 0) return false;
        pollfd pf = { fd, POLLIN, 0 };
        int r = poll(&pf, 1, (int)left);
        if (r < 0) { if (errno == EINTR) continue; return false; }
        if (r == 0) return false;
        ssize_t k = recv(fd, p, n, 0);
        if (k < 0 && errno == EINTR) continue;
        if (k <= 0) return false;
        p += k;
        n -= (size_t)k;
    }
    return true;
}

void put_frame(Bytes& o, uint8_t type, uint8_t flags, uint32_t sid, const uint8_t* pl, size_t n) {
    o.push_back((uint8_t)(n >> 16)); o.push_back((uint8_t)(n >> 8)); o.push_back((uint8_t)n);
    o.push_back(type); o.push_back(flags);
    o.push_back((uint8_t)((sid >> 24) & 0x7f)); o.push_back((uint8_t)(sid >> 16));
    o.push_back((uint8_t)(sid >> 8)); o.push_back((uint8_t)sid);
    if (n) o.insert(o.end(), pl, pl + n);
}

void put_u32(Bytes& o, uint32_t v) {
    o.push_back((uint8_t)(v >> 24)); o.push_back((uint8_t)(v >> 16));
    o.push_back((uint8_t)(v >> 8));  o.push_back((uint8_t)v);
}

// HPACK "literal header field without indexing". name_idx != 0 -> indexed name (static
// table, < 16 so it fits the 4-bit prefix), otherwise a literal name. Strings are sent
// raw (no Huffman) and are all < 127 bytes, so every length is a single octet.
void hpack_lit(Bytes& o, uint8_t name_idx, const char* name, const std::string& value) {
    if (name_idx) {
        o.push_back(name_idx);
    } else {
        o.push_back(0x00);
        o.push_back((uint8_t)strlen(name));
        o.insert(o.end(), name, name + strlen(name));
    }
    o.push_back((uint8_t)value.size());
    o.insert(o.end(), value.begin(), value.end());
}

bool rd_varint(const uint8_t*& p, const uint8_t* e, uint64_t& v) {
    v = 0;
    for (int shift = 0; shift < 64 && p < e; shift += 7) {
        uint8_t b = *p++;
        v |= (uint64_t)(b & 0x7f) << shift;
        if (!(b & 0x80)) return true;
    }
    return false;
}

// Walks one protobuf message. For every field calls fn(field, wiretype, varint, bytes, len).
template <class F>
bool pb_walk(const uint8_t* p, const uint8_t* e, F fn) {
    while (p < e) {
        uint64_t key;
        if (!rd_varint(p, e, key)) return false;
        uint32_t field = (uint32_t)(key >> 3), wt = (uint32_t)(key & 7);
        uint64_t v = 0;
        const uint8_t* b = nullptr;
        size_t len = 0;
        if (wt == 0) {
            if (!rd_varint(p, e, v)) return false;
        } else if (wt == 2) {
            uint64_t l;
            if (!rd_varint(p, e, l) || l > (uint64_t)(e - p)) return false;
            b = p; len = (size_t)l; p += l;
        } else if (wt == 1) {
            if (e - p < 8) return false;
            p += 8;
        } else if (wt == 5) {
            if (e - p < 4) return false;
            p += 4;
        } else {
            return false;
        }
        fn(field, wt, v, b, len);
    }
    return true;
}

struct Status {
    std::string tag;
    bool alive = false;
    int64_t delay = 0;
    int64_t last_try = 0;
};

bool parse_status(const uint8_t* p, size_t n, Status& s) {
    return pb_walk(p, p + n, [&](uint32_t f, uint32_t wt, uint64_t v, const uint8_t* b, size_t l) {
        if (f == 1 && wt == 0) s.alive = v != 0;
        else if (f == 2 && wt == 0) s.delay = (int64_t)v;
        else if (f == 4 && wt == 2) s.tag.assign((const char*)b, l);
        else if (f == 6 && wt == 0) s.last_try = (int64_t)v;
    });
}

// body = gRPC message(s): 1 byte compressed flag + 4 byte big-endian length + protobuf.
bool parse_response(const Bytes& body, std::vector<Status>& out) {
    out.clear();
    if (body.size() < 5 || body[0] != 0) return false;
    size_t mlen = ((size_t)body[1] << 24) | ((size_t)body[2] << 16) | ((size_t)body[3] << 8) | body[4];
    if (mlen > body.size() - 5) return false;
    // GetOutboundStatusResponse.status (1) -> ObservationResult
    bool ok = pb_walk(body.data() + 5, body.data() + 5 + mlen,
        [&](uint32_t f, uint32_t wt, uint64_t, const uint8_t* b, size_t l) {
            if (f != 1 || wt != 2) return;
            // ObservationResult.status (1, repeated) -> OutboundStatus
            pb_walk(b, b + l, [&](uint32_t f2, uint32_t wt2, uint64_t, const uint8_t* b2, size_t l2) {
                if (f2 != 1 || wt2 != 2) return;
                Status s;
                if (parse_status(b2, l2, s)) out.push_back(s);
            });
        });
    return ok;
}

int open_conn(const sockaddr_in& a, int64_t deadline) {
    while (now_ms() < deadline) {
        int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;
        if (connect(fd, (const sockaddr*)&a, sizeof a) == 0) {
            int one = 1;
            setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
            // connection preface, then SETTINGS(INITIAL_WINDOW_SIZE) and a bigger conn window
            static const char kPreface[] = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
            Bytes o(kPreface, kPreface + sizeof kPreface - 1);
            Bytes st = { 0x00, 0x04 };
            put_u32(st, kWindow);
            put_frame(o, 0x4, 0, 0, st.data(), st.size());
            Bytes wu;
            put_u32(wu, kWindow);
            put_frame(o, 0x8, 0, 0, wu.data(), wu.size());
            if (send_all(fd, o)) return fd;
        }
        close(fd);
        usleep(100 * 1000);   // xray not listening yet
    }
    return -1;
}

// One unary GetOutboundStatus call on stream `sid`.
bool call_status(int fd, uint32_t sid, const std::string& authority, int64_t deadline,
                 std::vector<Status>& out) {
    Bytes hdr;
    hdr.push_back(0x83);                       // :method POST
    hdr.push_back(0x86);                       // :scheme http
    hpack_lit(hdr, 0x04, nullptr, kPath);      // :path
    hpack_lit(hdr, 0x01, nullptr, authority);  // :authority
    hpack_lit(hdr, 0, "content-type", "application/grpc");
    hpack_lit(hdr, 0, "te", "trailers");
    Bytes req;
    put_frame(req, 0x1, 0x4, sid, hdr.data(), hdr.size());   // HEADERS | END_HEADERS
    const uint8_t empty_msg[5] = { 0, 0, 0, 0, 0 };          // empty request message
    put_frame(req, 0x0, 0x1, sid, empty_msg, sizeof empty_msg);  // DATA | END_STREAM
    if (!send_all(fd, req)) return false;

    Bytes body;
    bool end_stream = false, headers_open = false;
    while (!(end_stream && !headers_open)) {
        uint8_t fh[9];
        if (!recv_all(fd, fh, sizeof fh, deadline)) return false;
        size_t len = ((size_t)fh[0] << 16) | ((size_t)fh[1] << 8) | fh[2];
        uint8_t type = fh[3], flags = fh[4];
        uint32_t fsid = (((uint32_t)fh[5] & 0x7f) << 24) | ((uint32_t)fh[6] << 16) | ((uint32_t)fh[7] << 8) | fh[8];
        if (len > kMaxFrame) return false;
        Bytes pl(len);
        if (len && !recv_all(fd, pl.data(), len, deadline)) return false;

        Bytes reply;
        switch (type) {
        case 0x4:   // SETTINGS
            if (!(flags & 0x1)) put_frame(reply, 0x4, 0x1, 0, nullptr, 0);
            break;
        case 0x6:   // PING
            if (!(flags & 0x1) && len == 8) put_frame(reply, 0x6, 0x1, 0, pl.data(), 8);
            break;
        case 0x7:   // GOAWAY
            return false;
        case 0x3:   // RST_STREAM
            if (fsid == sid) return false;
            break;
        case 0x0: { // DATA
            if (len) {  // keep the connection window open for later polls
                Bytes w;
                put_u32(w, (uint32_t)len);
                put_frame(reply, 0x8, 0, 0, w.data(), w.size());
            }
            if (fsid != sid) break;
            size_t off = 0, end = len;
            if (flags & 0x8) {          // PADDED
                if (!len || pl[0] >= len) return false;
                off = 1; end = len - pl[0];
            }
            if (end > off) body.insert(body.end(), pl.begin() + off, pl.begin() + end);
            if (flags & 0x1) end_stream = true;
            break;
        }
        case 0x1:   // HEADERS (response headers or trailers): content is not needed
        case 0x9:   // CONTINUATION
            if (fsid != sid) break;
            if (type == 0x1 && (flags & 0x1)) end_stream = true;
            headers_open = !(flags & 0x4);
            break;
        default:
            break;
        }
        if (!reply.empty() && !send_all(fd, reply)) return false;
    }
    // Trailers-only answer (no DATA): e.g. observatory not configured -> nothing to parse.
    if (body.empty()) { out.clear(); return true; }
    return parse_response(body, out);
}

}  // namespace

int run_obsprobe(int argc, char* argv[]) {
    if (argc < 4) {
        fprintf(stderr, "Usage: obsprobe <ipv4:port> <timeout_ms> <tag> [<tag>...]\n");
        return 1;
    }
    std::string addr = argv[1];
    size_t colon = addr.rfind(':');
    sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    int port = colon == std::string::npos ? 0 : atoi(addr.c_str() + colon + 1);
    if (colon == std::string::npos || port <= 0 || port > 65535 ||
        inet_pton(AF_INET, addr.substr(0, colon).c_str(), &sa.sin_addr) != 1) {
        fprintf(stderr, "obsprobe: bad address '%s' (want ipv4:port)\n", argv[1]);
        return 1;
    }
    sa.sin_port = htons((uint16_t)port);
    long timeout = atol(argv[2]);
    if (timeout < 500) timeout = 500;
    std::vector<std::string> want(argv + 3, argv + argc);
    int64_t deadline = now_ms() + timeout;

    int fd = -1;
    uint32_t sid = 1;
    std::vector<Status> last;
    bool got_any = false, complete = false;
    while (now_ms() < deadline && !complete) {
        if (fd < 0) {
            fd = open_conn(sa, deadline);
            if (fd < 0) break;
            sid = 1;
        }
        std::vector<Status> cur;
        if (!call_status(fd, sid, addr, deadline, cur)) {
            close(fd);
            fd = -1;            // xray may have accepted before its gRPC server was ready
            usleep(100 * 1000);
            continue;
        }
        sid += 2;
        got_any = true;
        last = cur;
        complete = true;
        for (const std::string& t : want) {
            bool seen = false;
            for (const Status& s : last)
                if (s.tag == t && s.last_try > 0) { seen = true; break; }
            if (!seen) { complete = false; break; }
        }
        if (!complete) usleep(150 * 1000);
    }
    if (fd >= 0) close(fd);
    if (!got_any) return 1;
    for (const Status& s : last)
        if (s.last_try > 0) printf("%s\t%d\t%lld\n", s.tag.c_str(), s.alive ? 1 : 0, (long long)s.delay);
    return complete ? 0 : 2;
}
