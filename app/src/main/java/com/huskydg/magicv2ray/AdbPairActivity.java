package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-time adb pairing for "Share proxy" without Shizuku. On the phone: Settings > Developer
 * options > Wireless debugging > Pair device with pairing code, then type the port and the 6-digit
 * code here (split screen / floating window keeps both visible; the dialog disappears when its
 * window loses focus on some versions).
 */
public final class AdbPairActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText port;
    private EditText code;
    private Button pair;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.TOP);

        TextView help = new TextView(this);
        help.setText("1. Connect to Wi-Fi and turn on Developer options > Wireless debugging.\n"
                + "2. Tap \"Pair device with pairing code\".\n"
                + "3. Type the port and the 6-digit code shown there below.\n\n"
                + "This is done once. Wireless debugging is only needed while the hotspot helper starts.");
        root.addView(help);

        Button open = new Button(this);
        open.setText("Open Developer options");
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        root.addView(open);

        Button viaNotif = new Button(this);
        viaNotif.setText("Pair with a notification (recommended)");
        viaNotif.setOnClickListener(v -> startNotificationPairing());
        root.addView(viaNotif);

        TextView manual = new TextView(this);
        manual.setText("\nOr enter the port and code by hand (the code is void if you leave the Settings dialog):");
        root.addView(manual);

        port = new EditText(this);
        port.setHint("Pairing port (e.g. 37215)");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(port);

        code = new EditText(this);
        code.setHint("Pairing code (6 digits)");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(code);

        pair = new Button(this);
        pair.setText("Pair");
        pair.setOnClickListener(v -> doPair());
        root.addView(pair);

        status = new TextView(this);
        status.setText(AdbHelper.isPaired(this) ? "Already paired." : "Not paired.");
        root.addView(status);

        setContentView(root);
    }

    private void startNotificationPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            status.setText("Allow notifications, then tap again.");
            return;
        }
        AdbPairingService.start(this);
        status.setText("Now open \"Pair device with pairing code\" in Settings: a notification with a code field appears there.");
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void doPair() {
        final int p;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            status.setText("Enter the pairing port.");
            return;
        }
        final String c = code.getText().toString().trim();
        if (c.isEmpty()) {
            status.setText("Enter the pairing code.");
            return;
        }
        pair.setEnabled(false);
        status.setText("Pairing...");
        exec.execute(() -> {
            String msg;
            try {
                AdbHelper.pair(getApplicationContext(), p, c);
                msg = "Paired. You can start sharing now.";
            } catch (Throwable t) {
                msg = "Pairing failed: " + t.getMessage();
            }
            final String shown = msg;
            runOnUiThread(() -> {
                status.setText(shown);
                pair.setEnabled(true);
            });
        });
    }

    @Override
    protected void onDestroy() {
        exec.shutdown();
        super.onDestroy();
    }
}
