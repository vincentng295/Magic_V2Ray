/* Filesystem layout shared by the service (C) and the proxy_control applet (C++). */
#ifndef MV_PATHS_H
#define MV_PATHS_H

#ifndef MV_DATADIR
#define MV_DATADIR "/data/adb/magic_v2ray"
#endif
/* Runtime state (control pipe, log pipes, time_res, ...). A plain directory on Android's /mnt tmpfs:
 * no extra mount, gone at reboot. Recreated empty by every service start. */
#ifndef MV_STUB_DIR
#define MV_STUB_DIR "/mnt/runtime/magicv2ray"
#endif
/* Where older builds kept it (a private tmpfs mounted there). Only used to stop / clean up a service
 * that was started by an older module version. */
#ifndef MV_LEGACY_STUB_DIR
#define MV_LEGACY_STUB_DIR "/dev/sysctl_stubs"
#endif

/* xray is started as this uid:gid (after the tun is opened, keeping every Linux capability as ambient caps,
 * see privdrop.h). 9999 is AID_NOBODY: below the app range (10000+), so the "hide proxy ports from apps"
 * REJECT and the per-app exclude logic never touch it; it is always bypassed from the tun (UID_EXCLUDE).
 * gid 0 keeps access to the group-0 files of DATADIR. */
#ifndef MV_XRAY_UID
#define MV_XRAY_UID 9999
#endif
#ifndef MV_XRAY_GID
#define MV_XRAY_GID 0
#endif

#define MV_RUN_SUBDIR  "/run"
#define MV_LOG_SUBDIR  "/logs"

#endif
