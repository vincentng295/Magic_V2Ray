#include <iostream>
#include <vector>
#include <string>
#include <cstring>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netdb.h>
#include <unistd.h>
#include <thread>
#include <future>
#include <chrono>
#include <memory>
#include <atomic>

#pragma pack(push, 1)
struct DNSHeader {
    uint16_t id;
    uint16_t flags;
    uint16_t qdcount;
    uint16_t ancount;
    uint16_t nscount;
    uint16_t arcount;
};
#pragma pack(pop)

// Converts "example.com" -> "\x07example\x03com\x00"
void format_domain(const std::string& domain, std::vector<uint8_t>& buf) {
    size_t start = 0;
    size_t end = domain.find('.');
    while (end != std::string::npos) {
        buf.push_back(static_cast<uint8_t>(end - start));
        for (size_t i = start; i < end; ++i) buf.push_back(domain[i]);
        start = end + 1;
        end = domain.find('.', start);
    }
    buf.push_back(static_cast<uint8_t>(domain.length() - start));
    for (size_t i = start; i < domain.length(); ++i) buf.push_back(domain[i]);
    buf.push_back(0x00);
}

// Queries a specific DNS record type (1 = A, 28 = AAAA)
bool perform_dns_query(const std::string& domain, const std::string& server_ip, uint16_t qtype, std::vector<std::string>& out_ips) {
    bool is_ipv6_server = (server_ip.find(':') != std::string::npos);
    int domain_family = is_ipv6_server ? AF_INET6 : AF_INET;

    int sock = socket(domain_family, SOCK_DGRAM, IPPROTO_UDP);
    if (sock < 0) return false;

    struct timeval timeout{2, 0}; // 2-second socket timeout
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));

    // Construct Packet Header
    DNSHeader header{};
    header.id = htons(0x4321);
    header.flags = htons(0x0100); // RD = 1
    header.qdcount = htons(1);

    std::vector<uint8_t> packet;
    uint8_t* h_ptr = reinterpret_cast<uint8_t*>(&header);
    packet.insert(packet.end(), h_ptr, h_ptr + sizeof(DNSHeader));

    format_domain(domain, packet);

    uint16_t type_net = htons(qtype);
    uint16_t class_net = htons(1); // IN Class
    uint8_t* t_ptr = reinterpret_cast<uint8_t*>(&type_net);
    uint8_t* c_ptr = reinterpret_cast<uint8_t*>(&class_net);
    packet.insert(packet.end(), t_ptr, t_ptr + 2);
    packet.insert(packet.end(), c_ptr, c_ptr + 2);

    // Send UDP packet
    bool send_ok = false;
    if (is_ipv6_server) {
        sockaddr_in6 dest6{};
        dest6.sin6_family = AF_INET6;
        dest6.sin6_port = htons(53);
        inet_pton(AF_INET6, server_ip.c_str(), &dest6.sin6_addr);
        send_ok = (sendto(sock, packet.data(), packet.size(), 0, (struct sockaddr*)&dest6, sizeof(dest6)) >= 0);
    } else {
        sockaddr_in dest4{};
        dest4.sin_family = AF_INET;
        dest4.sin_port = htons(53);
        inet_pton(AF_INET, server_ip.c_str(), &dest4.sin_addr);
        send_ok = (sendto(sock, packet.data(), packet.size(), 0, (struct sockaddr*)&dest4, sizeof(dest4)) >= 0);
    }

    if (!send_ok) {
        close(sock);
        return false;
    }

    uint8_t response[512];
    ssize_t res_len = recvfrom(sock, response, sizeof(response), 0, nullptr, nullptr);
    close(sock);

    if (res_len <= (ssize_t)sizeof(DNSHeader)) return false;

    // Check RCODE (Response code) in flags
    DNSHeader* res_header = reinterpret_cast<DNSHeader*>(response);
    uint16_t res_flags = ntohs(res_header->flags);
    if ((res_flags & 0x000F) != 0) return false; // Non-zero RCODE indicates error

    uint16_t ancount = ntohs(res_header->ancount);
    if (ancount == 0) return true; // Valid query, zero records found

    // Advance buffer index past header and Question section
    size_t idx = sizeof(DNSHeader);
    while (idx < (size_t)res_len && response[idx] != 0) {
        if ((response[idx] & 0xC0) == 0xC0) { idx += 2; break; } // Compression pointer
        idx += response[idx] + 1;
    }
    if (response[idx] == 0) idx += 1;
    idx += 4; // Skip QTYPE + QCLASS

    // Parse Answers
    for (int i = 0; i < ancount && idx < (size_t)res_len; ++i) {
        // Skip NAME field
        if ((response[idx] & 0xC0) == 0xC0) {
            idx += 2;
        } else {
            while (idx < (size_t)res_len && response[idx] != 0) idx += response[idx] + 1;
            idx++;
        }

        if (idx + 10 > (size_t)res_len) break;

        uint16_t rtype = ntohs(*reinterpret_cast<uint16_t*>(&response[idx]));
        uint16_t rdlength = ntohs(*reinterpret_cast<uint16_t*>(&response[idx + 8]));
        idx += 10; // Skip TYPE, CLASS, TTL, RDLENGTH

        if (idx + rdlength > (size_t)res_len) break;

        // Parse IPv4 (TYPE 1, length 4)
        if (rtype == 1 && rdlength == 4) {
            char ip_str[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, &response[idx], ip_str, sizeof(ip_str));
            out_ips.push_back(ip_str);
        }
        // Parse IPv6 (TYPE 28, length 16)
        else if (rtype == 28 && rdlength == 16) {
            char ip_str[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, &response[idx], ip_str, sizeof(ip_str));
            out_ips.push_back(ip_str);
        }

        idx += rdlength;
    }

    return true;
}

// Workaround for the WSA getaddrinfo() bug described below: a tiny local DNS
// proxy that catches whatever getaddrinfo()'s query actually lands on and
// forwards it to a real upstream server.
//
// Background: on some environments (observed on WSA) the system resolver
// sends its query to destination 0.0.0.0 instead of a configured DNS
// server, so it never gets an answer and getaddrinfo() just hangs until
// the OS-level timeout. On Linux, the entire 127.0.0.0/8 range is
// loopback, and a UDP socket bound to INADDR_ANY (0.0.0.0) on port 53
// receives locally-generated traffic regardless of which local address it
// was nominally addressed to -- including a literal 0.0.0.0 destination.
// So binding here to 0.0.0.0:53 is what actually catches the otherwise-
// doomed query; "127.8.8.8:53" is just the mnemonic/conceptual address for
// it (chosen to visually suggest "loopback -> 8.8.8.8"), not a separate
// bind.
//
// This needs to bind UDP/53, which normally requires root or
// CAP_NET_BIND_SERVICE. start() returns false if the bind fails (no
// privilege, or something else -- e.g. dnsmasq/systemd-resolved -- already
// listening there); the caller must treat that as "no proxy available" and
// fall through to the existing behavior (getaddrinfo may still time out,
// manual UDP fallback still applies).
class LocalDnsProxy {
public:
    ~LocalDnsProxy() { stop(); }

    // upstream_ip: the real DNS server every caught query is forwarded to
    // verbatim (same wire-format query, just re-sent to a server that will
    // actually answer it).
    bool start(const std::string& upstream_ip) {
        sock_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (sock_ < 0) return false;

        int reuse = 1;
        setsockopt(sock_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY); // catches 0.0.0.0-addressed queries too
        addr.sin_port = htons(53);

        if (bind(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
            close(sock_);
            sock_ = -1;
            return false;
        }

        // Short recv timeout so run() wakes up periodically to check stop_requested_
        // instead of blocking forever in recvfrom().
        struct timeval rcv_timeout{0, 200000}; // 200ms
        setsockopt(sock_, SOL_SOCKET, SO_RCVTIMEO, &rcv_timeout, sizeof(rcv_timeout));

        upstream_ip_ = upstream_ip;
        stop_requested_ = false;
        thread_ = std::thread(&LocalDnsProxy::run, this);
        return true;
    }

    void stop() {
        if (thread_.joinable()) {
            stop_requested_ = true;
            thread_.join();
        }
        if (sock_ >= 0) {
            close(sock_);
            sock_ = -1;
        }
    }

private:
    void run() {
        uint8_t buf[512];
        while (!stop_requested_) {
            sockaddr_in client_addr{};
            socklen_t client_len = sizeof(client_addr);
            ssize_t n = recvfrom(sock_, buf, sizeof(buf), 0,
                                  reinterpret_cast<sockaddr*>(&client_addr), &client_len);
            if (n <= 0) continue; // recv timeout (see rcv_timeout above) or error; re-check stop flag

            // Relay the raw query, byte-for-byte (transaction id included), to
            // the real upstream server.
            int up_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
            if (up_sock < 0) continue;

            struct timeval up_timeout{1, 0};
            setsockopt(up_sock, SOL_SOCKET, SO_RCVTIMEO, &up_timeout, sizeof(up_timeout));

            sockaddr_in up_addr{};
            up_addr.sin_family = AF_INET;
            up_addr.sin_port = htons(53);
            inet_pton(AF_INET, upstream_ip_.c_str(), &up_addr.sin_addr);

            if (sendto(up_sock, buf, n, 0, reinterpret_cast<sockaddr*>(&up_addr), sizeof(up_addr)) >= 0) {
                uint8_t resp[512];
                ssize_t rn = recvfrom(up_sock, resp, sizeof(resp), 0, nullptr, nullptr);
                if (rn > 0) {
                    // Send the upstream's answer back to whoever originally
                    // queried us (getaddrinfo's own resolver socket).
                    sendto(sock_, resp, rn, 0,
                           reinterpret_cast<sockaddr*>(&client_addr), client_len);
                }
            }
            close(up_sock);
        }
    }

    int sock_ = -1;
    std::string upstream_ip_;
    std::thread thread_;
    std::atomic<bool> stop_requested_{false};
};

// Resolves a hostname via the system resolver (getaddrinfo -> netd on Android),
// used when no explicit DNS server is provided on the command line.
//
// getaddrinfo() itself has no timeout parameter, and on some environments
// (observed on WSA) the underlying resolver sends its query to 0.0.0.0 and
// just sits there for many seconds before giving up. To keep the CLI
// responsive, this runs getaddrinfo() on a worker thread and only waits up
// to timeout_ms for it to finish. On timeout this returns false so the
// caller can fall back to a manual UDP query against a known DNS server.
//
// Caveat: if it times out, the worker thread is detached and may keep
// blocking in the kernel resolver in the background until the OS-level
// timeout eventually fires. That's harmless for a short-lived CLI process
// (the whole process exits and the OS reclaims the thread), but would leak
// threads if this were called repeatedly inside a long-running daemon.
bool resolve_hostname_system_timed(const std::string& hostname,
                                    std::vector<std::string>& ipv4_out,
                                    std::vector<std::string>& ipv6_out,
                                    int timeout_ms,
                                    const std::string& proxy_upstream_ip = "8.8.8.8") {
    // Best-effort: catch getaddrinfo()'s query if it ends up going nowhere
    // (see LocalDnsProxy comment above) and hand it a real answer from
    // proxy_upstream_ip. If we can't bind (no privilege, port already in
    // use, etc.) this just silently doesn't help -- behavior falls back to
    // exactly what it was before.
    LocalDnsProxy proxy;
    bool proxy_active = proxy.start(proxy_upstream_ip);

    auto ipv4_ptr = std::make_shared<std::vector<std::string>>();
    auto ipv6_ptr = std::make_shared<std::vector<std::string>>();

    std::packaged_task<bool()> task([hostname, ipv4_ptr, ipv6_ptr]() -> bool {
        struct addrinfo hints{}, *res = nullptr;

        hints.ai_family = AF_UNSPEC;     // Accept both IPv4 and IPv6 results
        hints.ai_socktype = SOCK_STREAM; // TCP, doesn't affect address resolution

        int status = getaddrinfo(hostname.c_str(), nullptr, &hints, &res);
        if (status != 0) {
            std::cerr << "getaddrinfo error: " << gai_strerror(status) << std::endl;
            return false;
        }

        for (struct addrinfo* p = res; p != nullptr; p = p->ai_next) {
            char ipstr[INET6_ADDRSTRLEN];
            void* addr = nullptr;

            if (p->ai_family == AF_INET) {
                struct sockaddr_in* ipv4 = reinterpret_cast<struct sockaddr_in*>(p->ai_addr);
                addr = &(ipv4->sin_addr);
                inet_ntop(AF_INET, addr, ipstr, sizeof(ipstr));
                ipv4_ptr->push_back(ipstr);
            } else if (p->ai_family == AF_INET6) {
                struct sockaddr_in6* ipv6 = reinterpret_cast<struct sockaddr_in6*>(p->ai_addr);
                addr = &(ipv6->sin6_addr);
                inet_ntop(AF_INET6, addr, ipstr, sizeof(ipstr));
                ipv6_ptr->push_back(ipstr);
            }
        }

        freeaddrinfo(res);
        return true;
    });

    std::future<bool> fut = task.get_future();
    std::thread worker(std::move(task));
    worker.detach(); // see caveat in the function comment above

    bool timed_out = (fut.wait_for(std::chrono::milliseconds(timeout_ms)) == std::future_status::timeout);

    if (proxy_active) proxy.stop(); // no longer needed once the worker has finished or we've given up on it

    if (timed_out) {
        std::cerr << "getaddrinfo timed out after " << timeout_ms << "ms" << std::endl;
        return false;
    }

    bool ok = fut.get();
    if (ok) {
        ipv4_out = *ipv4_ptr;
        ipv6_out = *ipv6_ptr;
    }
    return ok;
}

void print_json(bool error, const std::vector<std::string>& ipv4, const std::vector<std::string>& ipv6) {
    std::cout << "{\n";
    std::cout << "  \"error\": " << (error ? "true" : "false") << ",\n";
    
    std::cout << "  \"ipv4\": [";
    for (size_t i = 0; i < ipv4.size(); ++i) {
        std::cout << "\"" << ipv4[i] << "\"" << (i + 1 < ipv4.size() ? ", " : "");
    }
    std::cout << "],\n";

    std::cout << "  \"ipv6\": [";
    for (size_t i = 0; i < ipv6.size(); ++i) {
        std::cout << "\"" << ipv6[i] << "\"" << (i + 1 < ipv6.size() ? ", " : "");
    }
    std::cout << "]\n";
    std::cout << "}\n";
}

// argv[0] here is expected to be "dnsjson" (the subcommand name),
// argv[1] = domain, argv[2] (optional) = dns server IP, or "?<ip>".
//
// argv[2] forms:
//   - "8.8.8.8"   -> explicit server: bypass the system resolver entirely
//                    and query this server directly via manual UDP (old behavior).
//   - "?8.8.8.8"  -> try the system resolver first (1s deadline); if it
//                    times out or fails, fall back to querying "8.8.8.8"
//                    via manual UDP.
//   - "?"         -> same as above but falls back to 8.8.8.8 if unspecified.
//   - (absent)    -> try the system resolver first (1s deadline); if it
//                    times out or fails, fall back to 8.8.8.8.
static const int SYSTEM_RESOLVER_TIMEOUT_MS = 1000;
static const char* DEFAULT_FALLBACK_DNS = "8.8.8.8";

int run_dnsjson(int argc, char* argv[]) {
    if (argc < 2) {
        print_json(true, {}, {});
        return 1;
    }

    std::string domain = argv[1];

    std::vector<std::string> ipv4_list;
    std::vector<std::string> ipv6_list;
    bool has_error;

    bool has_fallback_arg = (argc >= 3 && argv[2][0] == '?');
    bool has_explicit_server = (argc >= 3 && argv[2][0] != '?');

    if (has_explicit_server) {
        // Explicit DNS server given (no '?'): bypass the system resolver,
        // use the manual UDP query path directly.
        std::string dns_server = argv[2];

        bool ok_v4 = perform_dns_query(domain, dns_server, 1, ipv4_list);   // A records
        bool ok_v6 = perform_dns_query(domain, dns_server, 28, ipv6_list); // AAAA records

        has_error = (!ok_v4 && !ok_v6);
    } else {
        // No server, or "?<fallback>": try the system resolver first with a
        // short deadline, since it can hang far longer than desired on some
        // environments (e.g. WSA with no DNS configured).
        //
        // Same "?<fallback>" / default-to-8.8.8.8 rule used for the manual
        // UDP fallback below also decides which real DNS server the
        // LocalDnsProxy forwards getaddrinfo()'s query to, so it's computed
        // once up front and reused in both places.
        std::string fallback_server = DEFAULT_FALLBACK_DNS;
        if (has_fallback_arg && strlen(argv[2]) > 1) {
            fallback_server = argv[2] + 1; // strip leading '?'
        }

        bool ok = resolve_hostname_system_timed(domain, ipv4_list, ipv6_list,
                                                  SYSTEM_RESOLVER_TIMEOUT_MS, fallback_server);

        if (!ok) {
            ipv4_list.clear();
            ipv6_list.clear();
            bool ok_v4 = perform_dns_query(domain, fallback_server, 1, ipv4_list);
            bool ok_v6 = perform_dns_query(domain, fallback_server, 28, ipv6_list);
            has_error = (!ok_v4 && !ok_v6);
        } else {
            has_error = false;
        }
    }

    print_json(has_error, ipv4_list, ipv6_list);

    return has_error ? 1 : 0;
}