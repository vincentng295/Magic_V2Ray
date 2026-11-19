/* Run a process as an unprivileged uid:gid while keeping every Linux capability root had.
 *
 * Used by the service (spawn_xray) and by the `openxtun --uid N --gid N` applet, so it is header-only and
 * valid C and C++. Typical use, in a fork()ed child right before execve():
 *
 *     mv_priv pv;
 *     if (mv_priv_prepare(&pv, 9999, 0) == 0) { ... fork ...; child: mv_priv_drop(&pv) ... execve }
 *
 * Why ambient caps: execve() of a normal (non-setuid, no file caps) binary by a non-root uid clears the
 * permitted/effective sets, unless the caps are in the AMBIENT set (Linux >= 4.3). xray needs
 * CAP_NET_ADMIN (SO_MARK on its own sockets, which is how it bypasses the tun) and CAP_NET_RAW, and
 * CAP_DAC_OVERRIDE / CAP_DAC_READ_SEARCH to still read root-only paths (/data/adb, /mnt/runtime).
 *
 * mv_priv_prepare() does everything that is not async-signal-safe and must run in the parent.
 * mv_priv_drop() only issues system calls, so it is safe between fork() and execve(). */
#ifndef MV_PRIVDROP_H
#define MV_PRIVDROP_H

#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <stdint.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <linux/capability.h>

#ifndef PR_SET_KEEPCAPS
#define PR_SET_KEEPCAPS 8
#endif
#ifndef PR_CAPBSET_READ
#define PR_CAPBSET_READ 23
#endif
#ifndef PR_CAP_AMBIENT
#define PR_CAP_AMBIENT 47
#define PR_CAP_AMBIENT_IS_SET 1
#define PR_CAP_AMBIENT_RAISE 2
#endif
#ifndef _LINUX_CAPABILITY_VERSION_3
#define _LINUX_CAPABILITY_VERSION_3 0x20080522
#endif

/* Supplementary groups an Android process needs to open sockets on every ROM (AID_INET, AID_NET_RAW,
 * AID_NET_ADMIN): capabilities alone are not enough on some older kernels / vendor patches. */
#define MV_PRIV_MAX_GROUPS 4

typedef struct {
    uid_t uid;
    gid_t gid;
    uint64_t caps;                         /* every capability still in the bounding set */
    gid_t groups[MV_PRIV_MAX_GROUPS];
    int ngroups;
} mv_priv;

/* Steps reported by mv_priv_drop() on failure (0 = ok), with errno set. */
enum { MV_PRIV_OK = 0, MV_PRIV_KEEPCAPS = 1, MV_PRIV_GROUPS, MV_PRIV_SETGID, MV_PRIV_SETUID, MV_PRIV_CAPSET, MV_PRIV_AMBIENT };

static inline const char *mv_priv_step_name(int step)
{
    switch (step) {
    case MV_PRIV_KEEPCAPS: return "prctl(PR_SET_KEEPCAPS)";
    case MV_PRIV_GROUPS:   return "setgroups";
    case MV_PRIV_SETGID:   return "setresgid";
    case MV_PRIV_SETUID:   return "setresuid";
    case MV_PRIV_CAPSET:   return "capset";
    case MV_PRIV_AMBIENT:  return "prctl(PR_CAP_AMBIENT_RAISE)";
    default:               return "exec";
    }
}

/* Parent side. Returns 0 when the drop is possible, -1 (errno set) when this kernel has no ambient
 * capabilities (the caller then keeps running as root and should say so in the log). */
static inline int mv_priv_prepare(mv_priv *p, uid_t uid, gid_t gid)
{
    memset(p, 0, sizeof *p);
    p->uid = uid;
    p->gid = gid;
    /* IS_SET fails with EINVAL on a kernel without ambient capabilities */
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_IS_SET, 0 /* CAP_CHOWN */, 0, 0) < 0) return -1;
    for (int c = 0; c < 64; c++) {
        int r = prctl(PR_CAPBSET_READ, c, 0, 0, 0);
        if (r < 0) break;                  /* EINVAL: past the last capability of this kernel */
        if (r > 0) p->caps |= (uint64_t)1 << c;
    }
    if (!p->caps) { errno = EPERM; return -1; }
    p->groups[p->ngroups++] = 3003;        /* AID_INET */
    p->groups[p->ngroups++] = 3004;        /* AID_NET_RAW */
    p->groups[p->ngroups++] = 3005;        /* AID_NET_ADMIN */
    return 0;
}

/* Child side, async-signal-safe (system calls only). Returns MV_PRIV_OK or the failing step. */
static inline int mv_priv_drop(const mv_priv *p)
{
    struct __user_cap_header_struct hdr;
    struct __user_cap_data_struct data[2];

    if (prctl(PR_SET_KEEPCAPS, 1, 0, 0, 0) < 0) return MV_PRIV_KEEPCAPS;
    if (setgroups((size_t)p->ngroups, p->groups) < 0) return MV_PRIV_GROUPS;
    if (setresgid(p->gid, p->gid, p->gid) < 0) return MV_PRIV_SETGID;
    if (setresuid(p->uid, p->uid, p->uid) < 0) return MV_PRIV_SETUID;

    /* changing the uid cleared the effective set: bring everything back, and make it inheritable so it
     * may enter the ambient set */
    memset(&hdr, 0, sizeof hdr);
    memset(data, 0, sizeof data);
    hdr.version = _LINUX_CAPABILITY_VERSION_3;
    hdr.pid = 0;
    data[0].effective = data[0].permitted = data[0].inheritable = (uint32_t)(p->caps & 0xffffffffu);
    data[1].effective = data[1].permitted = data[1].inheritable = (uint32_t)(p->caps >> 32);
    if (syscall(SYS_capset, &hdr, data) < 0) return MV_PRIV_CAPSET;

    for (int c = 0; c < 64; c++)
        if (p->caps & ((uint64_t)1 << c))
            if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_RAISE, c, 0, 0) < 0) return MV_PRIV_AMBIENT;
    return MV_PRIV_OK;
}

/* Protects the calling process (or, with pid > 0, that process) from the kernel OOM killer and from Android's
 * lmkd: oom_score_adj = -1000 means "never pick me". Lowering it needs CAP_SYS_RESOURCE, so do it while still
 * root (before mv_priv_drop). System calls only, so it is async-signal-safe. Returns 0 or -1 (errno set). */
static inline int mv_oom_protect(pid_t pid)
{
    char path[48];
    int n = 0;
    const char *a = "/proc/";
    while (*a) path[n++] = *a++;
    if (pid > 0) {
        char d[12]; int k = 0; unsigned v = (unsigned)pid;
        while (v) { d[k++] = (char)('0' + v % 10); v /= 10; }
        while (k) path[n++] = d[--k];
    } else {
        const char *m = "self";
        while (*m) path[n++] = *m++;
    }
    const char *b = "/oom_score_adj";
    while (*b) path[n++] = *b++;
    path[n] = 0;
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t w = write(fd, "-1000", 5);
    int e = errno;
    close(fd);
    if (w != 5) { errno = e; return -1; }
    return 0;
}

#endif
