/* Cross-module interfaces of the service. */
#ifndef MV_SERVICE_H
#define MV_SERVICE_H
#include "common.h"
#include <stddef.h>

/* worker.c - a monitor thread with a stop eventfd and a /proc tracking name */
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

/* routing.c */
int  apply_routing_rules(void);
void clear_routing_rules(void);
int  configure_tun_iface(void);
void install_tun_blackhole(void);
void hold_traffic(void);
void release_traffic(void);

/* latency.c */
void latency_monitor_start(void);
void latency_monitor_stop(void);

/* xray.c */
int  start_xray(void);
int  stop_xray(void);
int  restart_xray(void);
void xray_helper_logservice(const char *verb, const char *extra_flag, const char *extra_val);

/* control.c */
int  control_run_prepare(void);        /* create + open the FIFO */
int  control_run(void);                /* blocks: FIFO command loop */
int  control_submit(const char *cmd);  /* write a command into the FIFO */

/* service_main.c */
void service_shutdown(void);           /* `stop_svc`: tear everything down; the caller then exits */
int  service_stop_request(void);       /* `service --stop`: ask the running service to stop */
#endif
