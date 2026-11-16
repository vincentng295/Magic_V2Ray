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

#define MV_RUN_SUBDIR  "/run"
#define MV_LOG_SUBDIR  "/logs"

#endif
