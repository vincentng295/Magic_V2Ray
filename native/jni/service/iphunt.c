/* Mobile IP hunter (service.sh section 5). */
#include "service.h"
#include "netlink.h"
#include <dirent.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_ATTEMPTS 10
static int g_attempts; static long g_next_allowed;

void ip_hunt_reset(void) { g_attempts = 0; g_next_allowed = 0; }

/* Restart only the telephony process: airplane mode would drop Wi-Fi/BT too.
 * Matched on uid 1001 + exact cmdline so lookalikes are never touched. */
void network_reset(void)
{
    DIR *d = opendir("/proc"); struct dirent *e;
    log_msg("resetting the mobile data stack");
    if (!d) return;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char p[300], cmd[256]; struct stat st;
        snprintf(p, sizeof p, "/proc/%s", e->d_name);
        if (stat(p, &st) < 0 || st.st_uid != 1001) continue;
        snprintf(p, sizeof p, "/proc/%s/cmdline", e->d_name);
        int n = u_read(p, cmd, sizeof cmd);
        if (n <= 0) continue;
        for (int i = 0; i < n - 1; i++) if (!cmd[i]) cmd[i] = ' ';
        static const char want[] = "com.android.phone";
        if (!strncmp(cmd, want, sizeof want - 1) && (cmd[sizeof want - 1] == 0 || cmd[sizeof want - 1] == ' ')) {
            log_msg("killing com.android.phone (pid %s)", e->d_name);
            kill(atoi(e->d_name), SIGKILL);
        }
    }
    closedir(d);
}

static bool is_mobile(const char *i)
{
    return !strncmp(i, "rmnet", 5) || !strncmp(i, "ccmni", 5) || !strncmp(i, "pdp", 3) || !strncmp(i, "wwan", 4);
}

int check_ip_hunter(const char *iface)
{
    char list[2048], ip[64];
    if (!is_mobile(iface) || u_read(IP_HUNT_FILE, list, sizeof list) < 0) return 0;
    for (char *c; (c = strchr(list, '\r'));) memmove(c, c + 1, strlen(c));
    u_trim(list);
    if (!list[0]) return 0;
    if (!nl_addr_first_v4(iface, ip, sizeof ip)) return 0;

    char cur[80]; snprintf(cur, sizeof cur, "%s.", ip);
    char *save = NULL;
    for (char *tok = strtok_r(list, ";", &save); tok; tok = strtok_r(NULL, ";", &save)) {
        char t[80]; snprintf(t, sizeof t, "%s", tok);
        if (!t[0]) continue;
        size_t l = strlen(t);
        if (t[l - 1] != '.' && l + 1 < sizeof t) strcat(t, ".");
        if (!strncmp(cur, t, strlen(t))) { log_msg("IP hunter: matched %s", ip); ip_hunt_reset(); return 0; }
    }
    long now = u_now_s();
    if (g_attempts >= MAX_ATTEMPTS) {
        log_msg("IP hunter: giving up after %d attempts (got %s); disabling", g_attempts, ip);
        unlink(IP_HUNT_FILE); ip_hunt_reset();
        return 1;
    }
    if (now < g_next_allowed) { log_msg("IP hunter: backing off, next attempt in %lds", g_next_allowed - now); return 1; }
    g_attempts++;
    long backoff = 3L * (1L << (g_attempts - 1));          /* 3,6,12,... capped at 300 */
    if (backoff > 300) backoff = 300;
    g_next_allowed = now + backoff;
    log_msg("IP hunter: %s does not match (attempt %d/%d), retry in %lds", ip, g_attempts, MAX_ATTEMPTS, backoff);
    network_reset();
    return 1;
}
