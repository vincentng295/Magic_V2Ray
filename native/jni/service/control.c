/* Command loop (service.sh section 8). Two kinds of clients write one line per command into the control FIFO,
 * which is opened O_RDWR so a reader is always present and read() never sees EOF:
 *
 *   plain   "<verb>\n"                      fire and forget (boot sequencer, older callers, `service --stop`)
 *   request "REQ <reply-fifo> <verb>\n"     `xhuskydg_helper proxy_control <verb>`: the answer comes back through
 *                                           a FIFO the client created, same scheme as `logservice read`.
 *                                           Reply = "<exit code>\n" then output lines "o <stdout>" / "e <stderr>".
 *
 * The reader thread only parses. `status` and `latency_heartbeat` are answered right there (they never wait for a
 * long job); every other verb goes through a queue to ONE executor thread, so jobs still run strictly in order
 * (the old `wait` barrier is just the reply to a request). */
#include "service.h"
#include "netlink.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int g_fifo = -1;

int control_submit(const char *cmd)
{
    char b[128]; int n = snprintf(b, sizeof b, "%s\n", cmd);
    return g_fifo >= 0 && write(g_fifo, b, n) == n ? 0 : -1;
}

#define LOCKED(stmt) do { pthread_mutex_lock(&g_lock); stmt; pthread_mutex_unlock(&g_lock); } while (0)

/* ------------------------------------------------------------- results --- */

typedef struct { int rc; char buf[480]; size_t n; } out_t;

static void out_line(out_t *o, char kind, const char *fmt, ...) __attribute__((format(printf, 3, 4)));
static void out_line(out_t *o, char kind, const char *fmt, ...)
{
    if (o->n + 4 >= sizeof o->buf) return;
    o->buf[o->n++] = kind; o->buf[o->n++] = ' ';
    va_list ap; va_start(ap, fmt);
    int w = vsnprintf(o->buf + o->n, sizeof o->buf - o->n - 2, fmt, ap);
    va_end(ap);
    if (w < 0) w = 0;
    if ((size_t)w > sizeof o->buf - o->n - 3) w = (int)(sizeof o->buf - o->n - 3);
    o->n += w;
    o->buf[o->n++] = '\n';
    o->buf[o->n] = 0;
}
#define OUT(o, ...) out_line((o), 'o', __VA_ARGS__)
#define ERR(o, ...) out_line((o), 'e', __VA_ARGS__)

/* Writes the reply into the client's FIFO (it opened the read end before sending the request). */
static void reply_send(const char *path, const out_t *o)
{
    if (!path || !*path) return;
    if (strncmp(path, RUN_DIR "/", strlen(RUN_DIR "/")) != 0 || strstr(path, "..")) {   /* only FIFOs inside our run dir */
        log_msg("control: refusing reply path %s", path);
        return;
    }
    int fd = open(path, O_WRONLY | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);          /* ENXIO: client already gone */
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) != 0 || !S_ISFIFO(st.st_mode)) { close(fd); return; }
    char msg[sizeof ((out_t *)0)->buf + 16];
    int n = snprintf(msg, sizeof msg, "%d\n%s", o->rc, o->n ? o->buf : "");
    for (int off = 0; off < n;) {
        ssize_t w = write(fd, msg + off, n - off);
        if (w > 0) { off += (int)w; continue; }
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && errno == EAGAIN) {
            struct pollfd p = { fd, POLLOUT, 0 };
            if (poll(&p, 1, 2000) <= 0) break;       /* client stalled */
            continue;
        }
        break;                                       /* EPIPE */
    }
    close(fd);
}

/* --------------------------------------------------------------- verbs --- */

static bool config_present(void)
{
    struct stat st;
    return u_ov_active() || (stat(DATADIR "/" CONFIG_NAME, &st) == 0 && st.st_size > 0);
}

/* Contract with the WebUI: one word + exit code: running 0 / stopped 1 / crashed 2. */
static void verb_status(out_t *o)
{
    if (proc_running("xray")) { o->rc = 0; OUT(o, "running"); }
    else if (proc_crashed("xray")) { o->rc = 2; OUT(o, "crashed"); }   /* exited without being asked to */
    else { o->rc = 1; OUT(o, "stopped"); }
}

static bool is_fast(const char *v) { return !strcmp(v, "status") || !strcmp(v, "latency_heartbeat"); }

static void do_fast(const char *c, out_t *o)
{
    if (!strcmp(c, "status")) { verb_status(o); return; }
    /* refreshed by the UI while its Latency tab is open */
    if (u_exists(LATENCY_HB_FILE)) u_writef(LATENCY_HB_FILE, "%ld\n", u_now_s());
}

/* o->rc: 0 = done, 1 = failed, 2 = unknown verb. Names on the right are what older
 * callers and the boot sequencer write; the proxy_control verbs map onto the same code. stop_svc is not here:
 * the reader handles it itself. */
static void do_job(const char *c, out_t *o)
{
    if (!strcmp(c, "wait")) return;
    if (!strcmp(c, "apply_cur_iface") || !strcmp(c, "reapply")) { log_msg("re-applying mark rule"); LOCKED(refresh_mark_rule()); return; }
    if (!strcmp(c, "start")) {
        if (proc_running("xray")) { OUT(o, "running"); return; }
        /* a temporary override session (README "Control from other apps") may be the only config there is */
        if (!config_present()) { o->rc = 1; ERR(o, "no config"); return; }
        LOCKED(start_xray());
        if (proc_running("xray")) OUT(o, "started"); else { o->rc = 1; ERR(o, "start failed, see service.log"); }
        return;
    }
    if (!strcmp(c, "stop")) { LOCKED(stop_xray()); OUT(o, "stopped"); return; }
    if (!strcmp(c, "reload_config") || !strcmp(c, "reload")) {
        /* swap xray for one reading the fresh config without touching iptables / ip rules */
        int r; LOCKED(r = restart_xray());
        if (r < 0) { o->rc = 1; ERR(o, "reload failed, see service.log"); } else OUT(o, "reloaded");
        return;
    }
    if (!strcmp(c, "hard_restart") || !strcmp(c, "restart")) {
        /* full stop+start with all non-xray traffic held so nothing leaks */
        pthread_mutex_lock(&g_lock);
        hold_traffic();
        stop_xray();
        int r = start_xray();
        if (r < 0) log_msg("hard_restart: start failed");
        release_traffic();
        pthread_mutex_unlock(&g_lock);
        if (r < 0) { o->rc = 1; ERR(o, "restart failed, see service.log"); } else OUT(o, "restarted");
        return;
    }
    if (!strcmp(c, "start_monitor")) { iface_monitor_start(); return; }
    if (!strcmp(c, "stop_monitor")) { iface_monitor_stop(); return; }
    if (!strcmp(c, "start_monitor_apps")) { apps_monitor_start(); return; }
    if (!strcmp(c, "stop_monitor_apps")) { apps_monitor_stop(); log_msg("apps monitor stopped"); return; }
    if (!strcmp(c, "start_monitor_latency")) { latency_monitor_start(); return; }
    if (!strcmp(c, "stop_monitor_latency")) { latency_monitor_stop(); log_msg("latency monitor stopped"); return; }
    if (!strcmp(c, "traffic_poll") || !strcmp(c, "traffic_readonly")) {
        /* answer goes into TRAFFIC_FILE (the reply buffer is too small for a per-IP list) */
        int r = traffic_poll(!strcmp(c, "traffic_poll"));
        if (r < 0) { o->rc = 1; ERR(o, "traffic: %s", strerror(-r)); } else OUT(o, "ok");
        return;
    }
    if (!strncmp(c, "traffic_", 8) && strchr(c, ' ')) {          /* block / unblock / limit / limit_all / reset_mac */
        int r = traffic_ctl_cmd(c + 8);
        if (r < 0) { o->rc = 1; ERR(o, "traffic: %s", strerror(-r)); } else OUT(o, "ok");
        return;
    }
    if (!strcmp(c, "traffic_reset")) { traffic_reset(); OUT(o, "ok"); return; }
    if (!strcmp(c, "traffic_stop")) { traffic_remove(); OUT(o, "ok"); return; }
    if (!strcmp(c, "reset_mobile_network")) {
        char ifn[64];
        pthread_mutex_lock(&g_lock);
        if (get_active_interface(ifn, sizeof ifn) == 0) { ip_hunt_reset(); check_ip_hunter(ifn); }
        pthread_mutex_unlock(&g_lock);
        return;
    }
    o->rc = 2;
    ERR(o, "unknown command: %s", c);
}

/* -------------------------------------------------------------- queue --- */

typedef struct { char verb[64]; char reply[200]; } job_t;
enum { QCAP = 64 };
static job_t g_q[QCAP];
static int g_qhead, g_qlen;
static bool g_quit;
static pthread_mutex_t g_qm = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_qc = PTHREAD_COND_INITIALIZER;
static pthread_t g_exec;
static bool g_exec_started;

static void *executor(void *arg)
{
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&g_qm);
        while (!g_quit && g_qlen == 0) pthread_cond_wait(&g_qc, &g_qm);
        if (g_quit) { pthread_mutex_unlock(&g_qm); return NULL; }   /* what is still queued is answered by the reader */
        job_t j = g_q[g_qhead];
        g_qhead = (g_qhead + 1) % QCAP; g_qlen--;
        pthread_mutex_unlock(&g_qm);

        out_t o = { 0, "", 0 };
        do_job(j.verb, &o);
        if (o.rc == 2) log_msg("unknown command: %s", j.verb);
        LOGD("control: %s -> %d", j.verb, o.rc);
        reply_send(j.reply, &o);
    }
}

static bool enqueue(const char *verb, const char *reply)
{
    pthread_mutex_lock(&g_qm);
    bool ok = g_qlen < QCAP;
    if (ok) {
        job_t *j = &g_q[(g_qhead + g_qlen++) % QCAP];
        snprintf(j->verb, sizeof j->verb, "%s", verb);
        snprintf(j->reply, sizeof j->reply, "%s", reply ? reply : "");
        pthread_cond_signal(&g_qc);
    }
    pthread_mutex_unlock(&g_qm);
    return ok;
}

/* Stops the executor (after the job it is running) and answers whatever is still queued. */
static void executor_stop(void)
{
    pthread_mutex_lock(&g_qm);
    g_quit = true;
    pthread_cond_broadcast(&g_qc);
    pthread_mutex_unlock(&g_qm);
    if (g_exec_started) pthread_join(g_exec, NULL);               /* never with g_lock held */
    for (; g_qlen > 0; g_qhead = (g_qhead + 1) % QCAP, g_qlen--) {
        out_t o = { 1, "", 0 }; ERR(&o, "service is stopping");
        reply_send(g_q[g_qhead].reply, &o);
    }
}

/* -------------------------------------------------------------- reader --- */

/* 0 = keep going, 1 = stop the service */
static int handle_line(char *line)
{
    const char *reply = NULL, *verb = line;
    if (!strncmp(line, "REQ ", 4)) {
        char *sp = strchr(line + 4, ' ');
        if (!sp) { log_msg("control: malformed request: %s", line); return 0; }
        *sp = 0; reply = line + 4; verb = sp + 1;
        while (*verb == ' ') verb++;
    }
    out_t o = { 0, "", 0 };
    if (strlen(verb) >= sizeof ((job_t *)0)->verb) { o.rc = 2; ERR(&o, "unknown command"); reply_send(reply, &o); return 0; }
    if (is_fast(verb)) { do_fast(verb, &o); reply_send(reply, &o); return 0; }
    if (!strcmp(verb, "stop_svc")) { reply_send(reply, &o); return 1; }
    if (!enqueue(verb, reply)) {
        log_msg("control: queue full, dropping %s", verb);
        o.rc = 1; ERR(&o, "service busy"); reply_send(reply, &o);
    }
    return 0;
}

int control_run_prepare(void)
{
    unlink(PIPE_FILE);
    if (mkfifo(PIPE_FILE, 0600) < 0 && errno != EEXIST) { log_msg("mkfifo %s: %s", PIPE_FILE, strerror(errno)); return -1; }
    chmod(PIPE_FILE, 0600);
    g_fifo = open(PIPE_FILE, O_RDWR | O_CLOEXEC);
    if (g_fifo < 0) return -1;
    if (pthread_create(&g_exec, NULL, executor, NULL) != 0) { log_msg("control: cannot start executor"); return -1; }
    g_exec_started = true;
    u_writef(CONTROL_LOOP_PID_FILE, "%d\n", (int)getpid());
    return 0;
}

int control_run(void)
{
    char buf[1024]; size_t have = 0;
    for (;;) {
        ssize_t r = read(g_fifo, buf + have, sizeof buf - have - 1);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        have += r; buf[have] = 0;
        char *start = buf, *nl;
        while ((nl = strchr(start, '\n'))) {
            *nl = 0;
            u_trim(start);
            if (*start && handle_line(start)) { executor_stop(); service_shutdown(); return 0; }
            start = nl + 1;
        }
        have = strlen(start);
        memmove(buf, start, have + 1);
        if (have >= sizeof buf - 1) have = 0;          /* discard an overlong line */
    }
}
