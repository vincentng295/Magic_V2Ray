#include <iostream>
#include <cstring>
#include <string>
#include <vector>

// Implemented in openxtun.cpp / dnsjson.cpp (renamed from their old main()).
int run_openxtun(int argc, char* argv[]);
int run_dnsjson(int argc, char* argv[]);
// Implemented in logservice.cpp.
int run_logservice(int argc, char* argv[]);
// Implemented in service/service_main.c (C, magic_v2ray daemon; formerly its own main()).
extern "C" int run_service(int argc, char* argv[]);

static void print_usage(const char* prog) {
    std::cerr << "Usage: " << prog << " <service|openxtun|dnsjson|logservice> [args...]\n"
              << "  " << prog << " service -m <MODDIR> [-f]\n"
              << "  " << prog << " openxtun <tun_name> <xray_cmd> [args...]\n"
              << "  " << prog << " dnsjson <domain> [dns_server_ip]\n"
              << "  " << prog << " logservice <create|set|read|flush|stop> -c <control.pipe> [...]\n";
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
    } else if (subcmd == "logservice") {
        return run_logservice(sub_argc, sub_argv.data());
    } else if (subcmd == "service") {
        return run_service(sub_argc, sub_argv.data());
    } else {
        std::cerr << "Unknown subcommand: " << subcmd << "\n";
        print_usage(argv[0]);
        return 1;
    }
}