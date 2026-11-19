#include <iostream>
#include <string>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <net/if.h>
#include <linux/if_tun.h>
#include <cstring>
#include <cstdlib>

#include "service/privdrop.h"

// Delete the interface if it still exists (e.g. left over from a killed xray,
// or still held by an orphaned child process). `ip link delete` works on tun
// devices even while a fd is attached, which releases the EBUSY state.
static void ensure_iface_closed(const char* name) {
    if (if_nametoindex(name) == 0) return;  // not present, nothing to do

    std::cerr << "[opentun] Interface " << name << " exists, deleting it\n";

    pid_t pid = fork();
    if (pid == 0) {
        // silence "Cannot find device" noise from races
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) {
            dup2(devnull, STDERR_FILENO);
            close(devnull);
        }
        execlp("ip", "ip", "link", "delete", name, (char*)nullptr);
        _exit(127);
    }
    if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
    }

    // Wait briefly for the kernel to finish tearing it down.
    for (int i = 0; i < 50 && if_nametoindex(name) != 0; ++i) {
        usleep(100 * 1000);
    }
    if (if_nametoindex(name) != 0) {
        std::cerr << "[opentun] Warning: " << name << " still exists after delete\n";
    }
}

// Open the tun device and attach to `name`; retries on EBUSY.
static int open_tun(const char* name, struct ifreq& ifr) {
    for (int attempt = 0; attempt < 10; ++attempt) {
        int fd = open("/dev/net/tun", O_RDWR);
        if (fd < 0) {
            perror("Failed to open /dev/net/tun");
            return -1;
        }

        std::memset(&ifr, 0, sizeof(ifr));
        ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
        std::strncpy(ifr.ifr_name, name, IFNAMSIZ - 1);
        ifr.ifr_name[IFNAMSIZ - 1] = '\0';

        if (ioctl(fd, TUNSETIFF, (void*)&ifr) == 0) return fd;

        int err = errno;
        close(fd);
        if (err != EBUSY) {
            errno = err;
            perror("ioctl(TUNSETIFF) failed");
            return -1;
        }

        std::cerr << "[opentun] TUNSETIFF busy, retrying (" << attempt + 1 << "/10)\n";
        ensure_iface_closed(name);
        usleep(200 * 1000);
    }
    errno = EBUSY;
    perror("ioctl(TUNSETIFF) failed");
    return -1;
}

// argv[0] here is expected to be "openxtun" (the subcommand name), then
//   [--uid N] [--gid N] <tun_name> <xray_cmd> [args...]
// The tun is opened as root, then the process drops to uid:gid while KEEPING every Linux capability
// (ambient caps, see service/privdrop.h) and execs <xray_cmd>. Without --uid it stays root.
// With --uid and no --gid, gid defaults to 0.
int run_openxtun(int argc, char* argv[]) {
    long uid = -1, gid = -1;
    int i = 1;
    while (i < argc && argv[i][0] == '-' && argv[i][1] == '-') {
        std::string a = argv[i];
        if ((a == "--uid" || a == "--gid") && i + 1 < argc) {
            char* end = nullptr;
            long v = std::strtol(argv[i + 1], &end, 10);
            if (!end || *end != '\0' || v < 0 || v > 2147483647L) {
                std::cerr << "[opentun] invalid value for " << a << ": " << argv[i + 1] << "\n";
                return 1;
            }
            (a == "--uid" ? uid : gid) = v;
            i += 2;
        } else {
            std::cerr << "[opentun] unknown option: " << a << "\n";
            return 1;
        }
    }
    if (argc - i < 2) {
        std::cerr << "Usage: " << argv[0] << " [--uid N] [--gid N] <tun_name> <xray_cmd> [args...]\n";
        return 1;
    }
    if (uid >= 0 && gid < 0) gid = 0;

    const char* tun_name = argv[i];
    char** cmd = argv + i + 1;
    int cmd_argc = argc - i - 1;

    // Make sure no stale interface is left over before opening.
    ensure_iface_closed(tun_name);

    struct ifreq ifr;
    int tun_fd = open_tun(tun_name, ifr);
    if (tun_fd < 0) return 1;

    std::cout << "[opentun] Successfully opened /dev/net/tun with FD: " << tun_fd
              << " (Interface: " << ifr.ifr_name << ")\n";

    std::string fd_str = std::to_string(tun_fd);
    if (setenv("XRAY_TUN_FD", fd_str.c_str(), 1) != 0) {
        perror("Failed to setenv XRAY_TUN_FD");
        close(tun_fd);
        return 1;
    }

    if (uid >= 0) {
        mv_priv pv;
        if (mv_priv_prepare(&pv, (uid_t)uid, (gid_t)gid) != 0) {
            perror("[opentun] ambient capabilities are not supported by this kernel");
            close(tun_fd);
            return 1;
        }
        int step = mv_priv_drop(&pv);
        if (step != MV_PRIV_OK) {
            std::cerr << "[opentun] " << mv_priv_step_name(step) << " failed: " << std::strerror(errno) << "\n";
            close(tun_fd);
            return 1;
        }
        std::cout << "[opentun] Running as uid " << uid << " gid " << gid << " with all capabilities kept\n";
    }

    char** exec_args = new char*[cmd_argc + 1];
    for (int k = 0; k < cmd_argc; ++k) exec_args[k] = cmd[k];
    exec_args[cmd_argc] = nullptr;

    execvp(exec_args[0], exec_args);

    perror("execvp failed");
    delete[] exec_args;
    close(tun_fd);
    return 1;
}
