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

#ifndef MV_DATADIR
#define MV_DATADIR "/data/adb/magic_v2ray"
#endif
#ifndef MV_STUB_DIR
#define MV_STUB_DIR "/dev/sysctl_stubs"
#endif

#define DATADIR        MV_DATADIR
#define STUB_DIR       MV_STUB_DIR
#define RUN_DIR        STUB_DIR "/run"
#define PROC_DIR       STUB_DIR "/proc"
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
size_t u_b64_decode(const char *in, size_t n, uint8_t *out);
int  u_run(char *const argv[]);                        /* fork/exec/wait, output to log */
long u_now_s(void);
int64_t u_mono_ms(void);

/* settings.c */
bool settings_query(const char *key, char *out, size_t cap);
bool settings_present(const char *key);
bool settings_is_true(const char *key);

/* proc_track.c */
int  proc_track_path(const char *proc_path, const char *name);
int  proc_track_pid(pid_t pid, const char *name);
int  proc_track_self_thread(const char *name);
void proc_untrack(const char *name);
bool proc_running(const char *name);
#endif
