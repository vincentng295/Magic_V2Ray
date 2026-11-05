#include "service.h"
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <sys/eventfd.h>
#include <unistd.h>

static void *trampoline(void *a)
{
    worker_t *w = a;
    proc_track_self_thread(w->name);
    w->fn(w->stop_fd);
    return NULL;
}

int worker_start(worker_t *w, const char *name, void (*fn)(int))
{
    worker_stop(w);
    w->stop_fd = eventfd(0, EFD_CLOEXEC);
    if (w->stop_fd < 0) return -1;
    w->name = name; w->fn = fn;
    if (pthread_create(&w->th, NULL, trampoline, w) != 0) { close(w->stop_fd); return -1; }
    w->started = true;
    return 0;
}

void worker_stop(worker_t *w)
{
    if (!w->started) return;
    uint64_t one = 1;
    (void)!write(w->stop_fd, &one, sizeof one);
    pthread_join(w->th, NULL);             /* never call with g_lock held */
    close(w->stop_fd);
    proc_untrack(w->name);
    w->started = false;
}

bool worker_wait(int stop_fd, unsigned ms)
{
    struct pollfd p = { stop_fd, POLLIN, 0 };
    int r;
    while ((r = poll(&p, 1, ms)) < 0 && errno == EINTR) {}
    return r > 0;
}
