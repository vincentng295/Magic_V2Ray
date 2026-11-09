package com.huskydg.magicv2ray;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Quick Settings tile: tap turns the core (xray) on or off, long-press opens the app
 * (the system opens the activity that handles QS_TILE_PREFERENCES, i.e. MainActivity).
 *
 * All work goes through proxy_control.sh, the same front-end the app uses, so the tile and
 * the app always agree. Root calls block, so they run on a worker thread.
 */
public class CoreTileService extends TileService {

    private static final String CTL = ModuleInstaller.MODDIR + "/proxy_control.sh";

    private enum State {ON, OFF, UNAVAILABLE}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        final Tile t = getQsTile();
        // Not usable yet (no root / module / service): the app sets all of that up.
        if (t != null && t.getState() == Tile.STATE_UNAVAILABLE) {
            openApp();
            return;
        }
        if (!busy.compareAndSet(false, true)) return;
        final boolean turnOff = t != null && t.getState() == Tile.STATE_ACTIVE;
        show(turnOff ? State.OFF : State.ON, getString(R.string.tile_busy));
        new Thread(() -> {
            try {
                RootShell.init(getApplicationContext());
                RootShell.Result r = RootShell.run(
                        "sh " + RootShell.sq(CTL) + (turnOff ? " stop" : " start") + " 2>&1", 60_000);
                // "no config": nothing selected yet, let the user pick a node in the app.
                boolean needApp = !turnOff && !r.ok();
                State s = query();
                main.post(() -> {
                    show(s, null);
                    if (needApp && s != State.ON) openApp();
                });
            } finally {
                busy.set(false);
            }
        }, "mv-tile").start();
    }

    private void refresh() {
        if (busy.get()) return;
        new Thread(() -> {
            RootShell.init(getApplicationContext());
            State s = query();
            main.post(() -> {
                if (!busy.get()) show(s, null);
            });
        }, "mv-tile-status").start();
    }

    /** proxy_control.sh status prints exactly one word: running / crashed / stopped. */
    private static State query() {
        RootShell.Result r = RootShell.run(
                "[ -p /dev/sysctl_stubs/run/control.pipe ] || { echo none; exit 0; }\n"
                        + "sh " + RootShell.sq(CTL) + " status 2>/dev/null; exit 0", 15_000);
        String o = r.out.trim();
        if (o.endsWith("running")) return State.ON;
        if (o.endsWith("stopped") || o.endsWith("crashed")) return State.OFF;
        return State.UNAVAILABLE;
    }

    private void show(State s, String subtitle) {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel(getString(R.string.tile_label));
        int res;
        switch (s) {
            case ON:
                t.setState(Tile.STATE_ACTIVE);
                res = R.string.tile_on;
                break;
            case OFF:
                t.setState(Tile.STATE_INACTIVE);
                res = R.string.tile_off;
                break;
            default:
                t.setState(Tile.STATE_UNAVAILABLE);
                res = R.string.tile_unavailable;
        }
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(subtitle != null ? subtitle : getString(res));
        t.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
