#include "common.h"
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int g_logfd = -1, g_pipefd = -1;

void log_open(void)
{
    struct stat st;
    if (stat(SERVICE_LOG, &st) == 0 && st.st_size > 1048576)
        rename(SERVICE_LOG, SERVICE_LOG ".1");
    g_logfd = open(SERVICE_LOG, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
}

void log_attach_pipe(const char *fifo)
{
    struct stat st;
    /* O_RDWR on a FIFO never blocks, even with no reader yet. */
    if (stat(fifo, &st) == 0 && S_ISFIFO(st.st_mode))
        g_pipefd = open(fifo, O_RDWR | O_CLOEXEC);
}

int log_fd(void) { return g_pipefd >= 0 ? g_pipefd : g_logfd; }

/* yyyy/mm/dd hh:mm:ss.uuuuuu [service] message */
void log_msg(const char *fmt, ...)
{
    char line[1024], msg[900];
    struct timespec ts; struct tm tm;
    va_list ap;
    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &tm);
    va_start(ap, fmt); vsnprintf(msg, sizeof msg, fmt, ap); va_end(ap);
    int n = snprintf(line, sizeof line, "%04d/%02d/%02d %02d:%02d:%02d.%06ld [service] %s\n",
                     tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min,
                     tm.tm_sec, ts.tv_nsec / 1000, msg);
    if (n >= (int)sizeof line) n = sizeof line - 1;
    if (g_logfd >= 0) (void)!write(g_logfd, line, n);
    else (void)!write(2, line, n);
    if (g_pipefd >= 0) (void)!write(g_pipefd, line, n); /* < PIPE_BUF => atomic */
}
