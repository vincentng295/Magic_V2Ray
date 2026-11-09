// `xhuskydg_helper vpnrun <socket-name> <program> [args...]`
//
// Non-root mode glue. A normal Android process cannot hand a file descriptor to a child
// (java.lang.ProcessBuilder closes everything above stderr), but the VpnService tun fd is
// exactly what xray needs. The app listens on an abstract unix socket, this applet connects,
// receives the tun fd over SCM_RIGHTS, publishes it as XRAY_TUN_FD (the same contract the
// root service uses) and exec()s the program, which keeps the fd.
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int run_vpnrun(int argc, char* argv[]) {
    if (argc < 3) {
        fprintf(stderr, "usage: vpnrun <abstract-socket-name> <program> [args...]\n");
        return 2;
    }
    const char* name = argv[1];
    size_t nl = strlen(name);
    sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    if (nl == 0 || nl + 1 > sizeof addr.sun_path) {
        fprintf(stderr, "vpnrun: bad socket name\n");
        return 2;
    }
    memcpy(addr.sun_path + 1, name, nl);                       // leading NUL = abstract namespace
    socklen_t alen = (socklen_t)(offsetof(sockaddr_un, sun_path) + 1 + nl);

    int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) { perror("vpnrun: socket"); return 1; }
    int tries = 0;
    while (connect(s, reinterpret_cast<sockaddr*>(&addr), alen) < 0) {
        if (++tries > 50 || (errno != ECONNREFUSED && errno != ENOENT && errno != EINTR)) {
            perror("vpnrun: connect");
            return 1;
        }
        usleep(100 * 1000);
    }

    char byte;
    iovec iov = { &byte, 1 };
    alignas(cmsghdr) char ctl[CMSG_SPACE(sizeof(int))];
    msghdr msg;
    memset(&msg, 0, sizeof msg);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctl;
    msg.msg_controllen = sizeof ctl;
    ssize_t r;
    while ((r = recvmsg(s, &msg, MSG_CMSG_CLOEXEC)) < 0 && errno == EINTR) {}
    if (r < 0) { perror("vpnrun: recvmsg"); return 1; }

    int fd = -1;
    for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS && c->cmsg_len >= CMSG_LEN(sizeof(int))) {
            memcpy(&fd, CMSG_DATA(c), sizeof(int));
            break;
        }
    }
    if (fd < 0) { fprintf(stderr, "vpnrun: no tun fd received\n"); return 1; }
    close(s);

    int fl = fcntl(fd, F_GETFD);
    if (fl >= 0) fcntl(fd, F_SETFD, fl & ~FD_CLOEXEC);          // the one fd the program must inherit
    char env[32];
    snprintf(env, sizeof env, "%d", fd);
    setenv("XRAY_TUN_FD", env, 1);
    execv(argv[2], argv + 2);
    perror("vpnrun: exec");
    return 127;
}
