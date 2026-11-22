#include <iostream>
#include <cstring>
#include <string>
#include <vector>

// Implemented in openxtun.cpp / dnsjson.cpp (renamed from their old main()).
int run_openxtun(int argc, char* argv[]);
int run_dnsjson(int argc, char* argv[]);
// Implemented in service/service_main.c (C, magic_v2ray daemon; formerly its own main()).
extern "C" int run_service(int argc, char* argv[]);
// Implemented in vpnrun.cpp (non-root mode: receive the VpnService tun fd, then exec xray).
int run_vpnrun(int argc, char* argv[]);
// Implemented in obsprobe.cpp (reads xray's ObservatoryService over h2c; node "Check HTTP" probe).
int run_obsprobe(int argc, char* argv[]);
// Implemented in proxy_control.cpp (control front-end: talks to the service through its control FIFO).
int run_proxy_control(int argc, char* argv[]);
// Implemented in tcping.cpp (TCP handshake time; node "TCPing" test and the real-delay pre-check).
int run_tcping(int argc, char* argv[]);

// Implemented in curl/src/tool_main.c (the curl tool, built from native/curl; its main() is renamed to run_curl).
extern "C" int run_curl(int argc, char* argv[]);

static void print_usage(const char* prog) {
    std::cerr << "Usage: " << prog << " <service|proxy_control|openxtun|dnsjson|vpnrun|obsprobe|tcping|curl> [args...]\n"
              << "  " << prog << " service -m <MODDIR> [-f]\n"
              << "  " << prog << " proxy_control <start|stop|restart|reload|status|reapply|...>\n"
              << "  " << prog << " openxtun [--uid N] [--gid N] <tun_name> <xray_cmd> [args...]\n"
              << "  " << prog << " dnsjson <domain> [dns_server_ip]\n"
              << "  " << prog << " vpnrun <abstract-socket-name> <program> [args...]\n"
              << "  " << prog << " obsprobe <ipv4:port> <timeout_ms> <tag> [<tag>...]\n"
              << "  " << prog << " tcping <host[,host...]> <port> [timeout_ms]\n"
              << "  " << prog << " curl [curl args...]   (full curl tool: HTTP/1-3, TLS via BoringSSL)\n";
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        print_usage(argv[0]);
        return 1;
    }

    std::string subcmd = argv[1];

    // Build a new argv for the sub-tool where argv[0] is the subcommand
    // name (so its own usage messages still make sense) and the rest of
    // the original args (argv[2..]) follow.
    int sub_argc = argc - 1;
    std::vector<char*> sub_argv(sub_argc + 1, nullptr);
    sub_argv[0] = argv[1];
    for (int i = 2; i < argc; ++i) {
        sub_argv[i - 1] = argv[i];
    }
    sub_argv[sub_argc] = nullptr;

    if (subcmd == "openxtun") {
        return run_openxtun(sub_argc, sub_argv.data());
    } else if (subcmd == "dnsjson") {
        return run_dnsjson(sub_argc, sub_argv.data());
    } else if (subcmd == "vpnrun") {
        return run_vpnrun(sub_argc, sub_argv.data());
    } else if (subcmd == "obsprobe") {
        return run_obsprobe(sub_argc, sub_argv.data());
    } else if (subcmd == "tcping") {
        return run_tcping(sub_argc, sub_argv.data());
    } else if (subcmd == "proxy_control") {
        return run_proxy_control(sub_argc, sub_argv.data());
    } else if (subcmd == "curl") {
        return run_curl(sub_argc, sub_argv.data());
    } else if (subcmd == "service") {
        return run_service(sub_argc, sub_argv.data());
    } else {
        std::cerr << "Unknown subcommand: " << subcmd << "\n";
        print_usage(argv[0]);
        return 1;
    }
}