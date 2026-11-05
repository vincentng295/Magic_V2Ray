/* Liveness tracking by bind-mounting /proc/<pid> (or a thread dir) to a stable
 * name under PROC_DIR - same on-disk contract the WebUI already relies on. */
#include "common.h"
#include <stdio.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static void path_of(char *b, size_t n, const char *name) { snprintf(b, n, "%s/%s", PROC_DIR, name); }

void proc_untrack(const char *name)
{
    char p[256]; path_of(p, sizeof p, name);
    umount2(p, MNT_DETACH);
    rmdir(p);   /* rmdir only: never recurse into a mount that failed to detach */
}

int proc_track_path(const char *proc_path, const char *name)
{
    char p[256]; path_of(p, sizeof p, name);
    if (access(proc_path, F_OK) != 0) return -1;
    proc_untrack(name);                      /* always start clean */
    mkdir(p, 0700);
    if (mount(proc_path, p, NULL, MS_BIND, NULL) == 0) { log_msg("tracking %s as %s", proc_path, name); return 0; }
    log_msg("warning: could not bind-mount %s for %s", proc_path, name);
    return -1;
}

int proc_track_pid(pid_t pid, const char *name)
{
    char s[64]; snprintf(s, sizeof s, "/proc/%d", (int)pid);
    return proc_track_path(s, name);
}

/* Threads replace the shell's background subshells: bind /proc/<pid>/task/<tid>,
 * which has an exe link while the thread lives and goes empty when it exits. */
int proc_track_self_thread(const char *name)
{
    char s[96]; snprintf(s, sizeof s, "/proc/%d/task/%ld", (int)getpid(), (long)syscall(SYS_gettid));
    return proc_track_path(s, name);
}

bool proc_running(const char *name)
{
    char p[300]; snprintf(p, sizeof p, "%s/%s/exe", PROC_DIR, name);
    return access(p, F_OK) == 0;
}
