# Magic V2Ray v3.1 Release Notes

Magic V2Ray v3.1 is an under-the-hood release for root mode: the service is simpler, more exact about what it runs, and easier to update, and the permission popup is more compact.

The service no longer mounts anything of its own. Runtime state (the control pipe, log pipes and temporary override sessions) now lives in a plain directory, `/mnt/runtime/magicv2ray`, instead of a private tmpfs at `/dev/sysctl_stubs`. It is recreated empty every time the service starts and removed when it stops, and it is still gone after a reboot. Anything an older version left behind is cleaned up automatically, unless an older service is still running there.

The service also stopped bind-mounting `/proc/<pid>` entries to see whether xray is alive. Because the service starts xray itself, it now remembers the exact process id and waits on it. If xray is killed, by the system or by anyone else, it is collected at once and never lingers as a zombie process, and the status is exact: running, stopped, or crashed when xray exited without being asked to. A normal stop or restart is never reported as a crash, a process that is already gone is never signalled again, and a leftover process id from a previous run is only killed if it really is this module's xray.

`proxy_control.sh` is gone. Its job is done by a new command, `xhuskydg_helper proxy_control <start|stop|restart|reload|status|reapply|...>`, which talks to the service through its control pipe the same way the log service does. The status check is answered immediately even while a start or restart is still running, and its output and exit codes are unchanged, so the app, the Quick Settings tile and other apps that read the status keep working. Diagnostics still go to `proxy_control.log`. The app removes the old script when it updates the module.

Updating and stopping are safer. `xhuskydg_helper service --stop` now stops a service running at either the new or the old runtime location, so a live update works no matter which version was running before it. The log service also no longer deletes the pipes of a newer service when an older one exits late.

The permission popup keeps the details of a request (who is asking, the full address, the apps kept out of the VPN and any changed settings) collapsed by default, so it stays short. Tap Details to see them.

The service documentation was updated for the new runtime directory, process tracking and control protocol, the tests for the control command were rewritten for the native version, and the READMEs in all languages point to the new location of temporary sessions.

---

[Click here for older release notes](https://github.com/vincentng295/Magic_V2Ray/releases)
