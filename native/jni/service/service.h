/* Cross-module interfaces of the service. */
#ifndef MV_SERVICE_H
#define MV_SERVICE_H
#include "common.h"
#include <stddef.h>

/* worker.c - a monitor thread with a stop eventfd (the name only labels the thread) */
typedef struct { pthread_t th; bool started; int stop_fd; const char *name; void (*fn)(int stop_fd); } worker_t;
int  worker_start(worker_t *w, const char *track_name, void (*fn)(int stop_fd));
void worker_stop(worker_t *w);
/* waits up to ms; returns true when asked to stop */
bool worker_wait(int stop_fd, unsigned ms);

/* iface.c */
int  get_active_interface(char *out, size_t cap);     /* 0 ok, -1 none */
int  get_upstream_interface(char *out, size_t cap);
int  read_table_index(const char *iface);             /* -1 if absent */
int  apply_mark_rule(const char *iface);
int  refresh_mark_rule(void);
int  get_marked_interface(char *out, size_t cap);
void remove_mark_rule(void);                           /* fwmark rule, v4 + v6 */
void print_netinfo(void);
void iface_monitor_start(void);
void iface_monitor_stop(void);

/* ula.c */
void ula_apply(const char *iface);
void ula_remove(void);

/* iphunt.c */
void ip_hunt_reset(void);
int  check_ip_hunter(const char *iface);
void network_reset(void);

/* exclude.c */
void ensure_exclude_app_chain(void);
void sync_exclude_apps(void);
void apps_monitor_start(void);
void apps_monitor_stop(void);
long self_app_uid(int tries);          /* uid of the Magic V2Ray app, -1 if unknown */

/* routing.c */
int  apply_routing_rules(void);
void clear_routing_rules(void);
int  configure_tun_iface(void);
void install_tun_blackhole(void);
void hold_traffic(void);
void release_traffic(void);
void proxy_allow_self(bool remove); /* let the app itself reach the proxy inbound (see routing.c) */
void root_direct_window(bool on);   /* soft restart: let uid 0 bypass the tun blackhole */

/* latency.c */
void latency_monitor_start(void);
void latency_monitor_stop(void);

/* traffic.c - Traffic Monitor tab: per-client counters at the hotspot interface (mangle PREROUTING / POSTROUTING),
 * independent of xraytun0 */
int  traffic_poll(bool counting);   /* writes TRAFFIC_FILE; counting=false only reads (nothing is touched). 0 / -errno */
int  traffic_set(bool on);          /* per-device counting on / off (persistent); off removes the rules */
void traffic_autostart(void);       /* service start: reinstall the rules when counting is on */
int  traffic_reset(void);           /* zero the counters */
void traffic_remove(void);          /* remove hook, chain and rules (v4 + v6) */
/* device control (block / limit by MAC), see traffic.c */
int  traffic_ctl_cmd(const char *cmd);   /* "block <mac>" | "unblock <mac>" | "limit <mac> <bytes>" | "limit_all <bytes>" | "reset_mac <mac>" */
void traffic_ctl_apply(void);            /* re-install what the user set (service start) */
void traffic_ctl_remove(void);           /* remove hook + chain (service shutdown); the saved settings stay */

/* xray.c */
int  start_xray(void);
int  stop_xray(void);
void notify_app_state(void);                /* "xray started / stopped": wakes the app, see xray.c */
int  restart_xray(void);

/* xraylog.c - xray's output: anonymous pipe + reader thread + ring buffer in RAM (replaces the logservice daemon) */
int  xlog_init(void);                       /* creates the pipe, starts the thread; returns the write end */
void xlog_flush(void);
int  xlog_set_max(size_t n);                /* lines kept (default 1000) */
int  xlog_dump(const char *path, size_t n); /* newest n lines (0 = all) into path; 0 or -errno */
void xlog_save(const char *path);           /* xlog_dump(path, 0), errors ignored */

/* control.c */
int  control_run_prepare(void);        /* create + open the FIFO */
int  control_run(void);                /* blocks: FIFO command loop */
int  control_submit(const char *cmd);  /* write a command into the FIFO */

/* service_main.c */
void service_shutdown(void);           /* `stop_svc`: tear everything down; the caller then exits */
int  service_stop_request(void);       /* `service --stop`: ask the running service to stop */
#endif
