/* Magic V2Ray service - shared constants and tiny utilities.
 * Port of service.sh sections 1 and 4. */
#ifndef MV_COMMON_H
#define MV_COMMON_H
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include "paths.h"

#define DATADIR        MV_DATADIR
#define STUB_DIR       MV_STUB_DIR
#define RUN_DIR        STUB_DIR "/run"
#define LOG_DIR        STUB_DIR "/logs"
#define CONFIG_NAME    "config.v2.json"

#define LOG_CTRL       LOG_DIR "/control.pipe"
#define XRAY_LOG       LOG_DIR "/input.pipe"
#define XRAY_LOG_FILE  DATADIR "/xray.log"
#define SERVICE_LOG    DATADIR "/service.log"
#define IP_HUNT_FILE   DATADIR "/ip_hunt.list"
#define BYPASS_IFACE_FILE DATADIR "/bypassIface.txt"
#define ENABLED_FLAG   DATADIR "/enabled"
#define EXCLUDE_LIST_FILE DATADIR "/excludelist.txt"
/* Temporary override session (magicv2ray://connect with node / settings overrides). PRIVACY: everything of a
 * session lives ONLY here, in the runtime dir (/mnt tmpfs), so it is gone after a reboot and never touches DATADIR. Files:
 *   config.v2.json, settings.base64, excludelist.txt, bypassIface.txt  -> replace the user's file of that name
 *   excludelist.session.txt -> apps excluded whether or not the Exclude apps switch is on (e.g. the app that
 *                              serves the SOCKS5 node the engine now dials). */
#define OVERRIDE_DIR   RUN_DIR "/override"
#define EXCLUDE_SESSION_FILE OVERRIDE_DIR "/excludelist.session.txt"
#define SETTINGS_FILE  DATADIR "/settings.base64"
#define SUB_LOOP_PID_FILE DATADIR "/.sub_loop.pid"   /* service.sh subscription updater */

#define PIDFILE        RUN_DIR "/xray.pid"
#define TIME_RES_FILE  RUN_DIR "/time_res"
#define ADDR_INFO_FILE RUN_DIR "/addr_info"
#define LATENCY_HB_FILE RUN_DIR "/latency.hb"
#define PIPE_FILE      RUN_DIR "/control.pipe"
#define EXCLUDE_MAP_DIR RUN_DIR "/exclude_uids"
#define ULA_FLAG       RUN_DIR "/ula_enabled"
#define ULA_IFACE_FILE RUN_DIR "/ula_iface"
#define ULA_SUFFIX_FILE RUN_DIR "/ula_suffix"
#define CONTROL_LOOP_PID_FILE RUN_DIR "/control_loop.pid"
#define APP_DATA_DIR   "/data/data"
#define RT_TABLES_PATH "/data/misc/net/rt_tables"

#define RULE_PRIORITY  1000
#define FWMARK         255
#define TUN_NAME       "xraytun0"
#define TUN_ADDR       "127.17.1.3"
#define TUN_PORT       808
#define PLAIN_POOL_NET "127.18.0.0/16"
#define PLAIN_POOL_PORT 80
#define LATENCY_INTERVAL 2
#define LATENCY_HB_TIMEOUT 15

#define ULA_PREFIX     "2001:db8:6875:736b::"
#define ULA_TABLE      31999
#define ULA_RULE_PREF  31999
#define ULA_BLOCK_PREF 31998

extern char g_moddir[256];       /* directory holding bin/ and module.prop */
extern char g_self[256];         /* path of this binary (xhuskydg_helper); its applets are re-exec'd */
extern bool g_debug;
extern pthread_mutex_t g_lock;   /* serialises network/xray state changes */

/* log.c */
void log_open(void);                       /* service.log + rotation */
void log_attach_pipe(const char *fifo);    /* tee into the xray log pipe */
void log_msg(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#define LOGD(...) do { if (g_debug) log_msg(__VA_ARGS__); } while (0)
int  log_fd(void);                         /* fd to give child stdout/err */

/* util.c */
int  u_read(const char *path, char *buf, size_t cap);   /* len or -1 */
int  u_write(const char *path, const char *s);
int  u_writef(const char *path, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
bool u_exists(const char *path);
void u_trim(char *s);
void u_msleep(unsigned ms);
int  u_mkdir_p(const char *path, mode_t mode);
void u_clear_dir(const char *path);                    /* unlink files, not recursive */
void u_rm_rf(const char *path);                        /* remove a whole tree (no symlink / mount crossing) */
size_t u_b64_decode(const char *in, size_t n, uint8_t *out);
int  u_run(char *const argv[]);                        /* fork/exec/wait, output to log */
long u_now_s(void);
int64_t u_mono_ms(void);

/* Override-aware path: OVERRIDE_DIR/<name> when it exists, else `normal`. Writes the result into buf. */
const char *u_ov_path(char *buf, size_t cap, const char *name, const char *normal);
/* True while an override session owns the config (its config.v2.json exists). */
bool u_ov_active(void);

/* settings.c */
bool settings_query(const char *key, char *out, size_t cap);
bool settings_present(const char *key);
bool settings_is_true(const char *key);

/* proc_track.c - pids of the processes this service spawned (no /proc bind mounts).
 * Every tracked child gets a reaper thread that waitpid()s it, so a child that dies - killed by us,
 * by the OOM killer or by anyone else - never stays a zombie, and its state is known exactly. */
typedef void (*proc_exit_cb)(pid_t pid, int status);
int   proc_track_pid(pid_t pid, const char *name, proc_exit_cb cb);   /* remember + start reaping; replaces `name` */
void  proc_untrack(const char *name);                                  /* forget (we stopped it on purpose) */
bool  proc_running(const char *name);                                  /* tracked and not yet reaped */
bool  proc_crashed(const char *name);                                  /* tracked, reaped, but never untracked */
pid_t proc_pid(const char *name);                                      /* 0 when not tracked */
bool  proc_wait_gone(pid_t pid, unsigned ms);                          /* true once pid no longer exists */
#endif
