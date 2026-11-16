/* Liveness tracking of the child processes this service spawns (xray).
 *
 * The service forks them itself, so their pid is known exactly: it is remembered here instead of
 * bind-mounting /proc/<pid> somewhere. A reaper thread per child blocks in waitpid(), which
 *   - clears the zombie as soon as the child dies, however it died (our SIGKILL, the OOM killer, a crash),
 *   - tells us it is gone (state EXITED) without polling /proc.
 * "Crashed" = the child exited while still tracked; proc_untrack() is what a deliberate stop does. */
#include "common.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAXTRACK 8

typedef struct { bool used, exited; char name[32]; pid_t pid; } slot_t;
static slot_t g_slot[MAXTRACK];
static pthread_mutex_t g_pt = PTHREAD_MUTEX_INITIALIZER;

typedef struct { pid_t pid; char name[32]; proc_exit_cb cb; } reap_arg;

static slot_t *find_name(const char *name)
{
    for (int i = 0; i < MAXTRACK; i++) if (g_slot[i].used && !strcmp(g_slot[i].name, name)) return &g_slot[i];
    return NULL;
}

static slot_t *find_pid(pid_t pid)
{
    for (int i = 0; i < MAXTRACK; i++) if (g_slot[i].used && g_slot[i].pid == pid) return &g_slot[i];
    return NULL;
}

static void *reaper(void *p)
{
    reap_arg a = *(reap_arg *)p; free(p);
    int st = 0; pid_t r;
    while ((r = waitpid(a.pid, &st, 0)) < 0 && errno == EINTR) {}
    if (r < 0) st = -1;                              /* ECHILD: somebody else collected it; status unknown */
    pthread_mutex_lock(&g_pt);
    slot_t *s = find_pid(a.pid);
    if (s && !strcmp(s->name, a.name)) s->exited = true;   /* an untracked / replaced entry no longer matches */
    pthread_mutex_unlock(&g_pt);
    if (a.cb) a.cb(a.pid, st);
    return NULL;
}

int proc_track_pid(pid_t pid, const char *name, proc_exit_cb cb)
{
    if (pid <= 1) return -1;
    reap_arg *a = malloc(sizeof *a);
    if (!a) return -1;
    a->pid = pid; a->cb = cb; snprintf(a->name, sizeof a->name, "%s", name);

    pthread_mutex_lock(&g_pt);
    slot_t *s = find_name(name);
    if (!s) for (int i = 0; i < MAXTRACK && !s; i++) if (!g_slot[i].used) s = &g_slot[i];
    if (!s) { pthread_mutex_unlock(&g_pt); free(a); return -1; }
    s->used = true; s->exited = false; s->pid = pid;
    snprintf(s->name, sizeof s->name, "%s", name);
    pthread_mutex_unlock(&g_pt);

    pthread_t th;
    if (pthread_create(&th, NULL, reaper, a) != 0) {
        /* no reaper: the child cannot be waited for, so do not pretend to track it */
        free(a); proc_untrack(name);
        return -1;
    }
    pthread_detach(th);
    return 0;
}

void proc_untrack(const char *name)
{
    pthread_mutex_lock(&g_pt);
    slot_t *s = find_name(name);
    if (s) memset(s, 0, sizeof *s);                  /* a running reaper still reaps; it just no longer finds the slot */
    pthread_mutex_unlock(&g_pt);
}

bool proc_running(const char *name)
{
    pthread_mutex_lock(&g_pt);
    slot_t *s = find_name(name);
    bool r = s && !s->exited;
    pthread_mutex_unlock(&g_pt);
    return r;
}

bool proc_crashed(const char *name)
{
    pthread_mutex_lock(&g_pt);
    slot_t *s = find_name(name);
    bool r = s && s->exited;
    pthread_mutex_unlock(&g_pt);
    return r;
}

pid_t proc_pid(const char *name)
{
    pthread_mutex_lock(&g_pt);
    slot_t *s = find_name(name);
    pid_t p = s ? s->pid : 0;
    pthread_mutex_unlock(&g_pt);
    return p;
}

/* A zombie still answers kill(pid, 0), so a tracked child is judged by its reaper, an unknown pid by kill(). */
bool proc_wait_gone(pid_t pid, unsigned ms)
{
    if (pid <= 1) return true;
    for (unsigned t = 0; ; t += 10) {
        pthread_mutex_lock(&g_pt);
        slot_t *s = find_pid(pid);
        bool tracked = s != NULL, gone = s && s->exited;
        pthread_mutex_unlock(&g_pt);
        if (tracked ? gone : (kill(pid, 0) < 0 && errno == ESRCH)) return true;
        if (t >= ms) return false;
        u_msleep(10);
    }
}
