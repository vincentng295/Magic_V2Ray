package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-time adb pairing for "Share proxy" without Shizuku. Built from the same Material 3 tokens
 * (mv_* colours, rounded cards, pill buttons) as the setup and welcome screens, so it follows the
 * system light / dark mode like them. The recommended path is the notification (AdbPairingService):
 * the code is typed there while the Settings dialog stays open, because the code changes as soon as
 * that dialog closes. Typing port and code here is the fallback.
 */
public final class AdbPairActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private TextView statusBadge;
    private TextView statusTitle;
    private TextView statusDesc;
    private TextView message;
    private View manualBox;
    private EditText portField;
    private EditText codeField;
    private Button pairButton;
    private Switch tcpSwitch;
    private View tcpPortRow;
    private EditText tcpPortField;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_adb_pair);
        applySystemBars();

        statusBadge = findViewById(R.id.adb_status_badge);
        statusTitle = findViewById(R.id.adb_status_title);
        statusDesc = findViewById(R.id.adb_status_desc);
        message = findViewById(R.id.adb_message);
        manualBox = findViewById(R.id.adb_manual_box);
        portField = findViewById(R.id.adb_port);
        codeField = findViewById(R.id.adb_code);
        pairButton = findViewById(R.id.adb_btn_pair);
        tcpSwitch = findViewById(R.id.adb_tcp_switch);
        tcpSwitch.setChecked(AdbBridge.isTcpMode(this));
        tcpSwitch.setOnCheckedChangeListener((v, on) -> {
            AdbBridge.setTcpMode(this, on);
            tcpPortRow.setVisibility(on ? View.VISIBLE : View.GONE);
            refreshStatus();
        });

        // TCP port: random by default (kept once picked), or any port 1024-65535 typed or re-rolled.
        tcpPortRow = findViewById(R.id.adb_tcp_port_row);
        tcpPortField = findViewById(R.id.adb_tcp_port);
        tcpPortRow.setVisibility(tcpSwitch.isChecked() ? View.VISIBLE : View.GONE);
        tcpPortField.setText(String.valueOf(AdbBridge.tcpPort(this)));
        tcpPortField.setOnEditorActionListener((v, actionId, e) -> {
            saveTcpPort();
            return false;
        });
        tcpPortField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveTcpPort();
        });
        findViewById(R.id.adb_tcp_random).setOnClickListener(v -> {
            int p = AdbBridge.randomTcpPort();
            tcpPortField.setText(String.valueOf(p));
            saveTcpPort();
        });

        findViewById(R.id.adb_btn_notification).setOnClickListener(v -> startNotificationPairing());
        findViewById(R.id.adb_btn_open_dev).setOnClickListener(v -> openDeveloperOptions());
        findViewById(R.id.adb_btn_manual_toggle).setOnClickListener(v ->
                manualBox.setVisibility(manualBox.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        pairButton.setOnClickListener(v -> pairManually());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();                   // the notification may have paired while we were away
    }

    @Override
    protected void onPause() {
        saveTcpPort();                     // a typed port is kept even if the user leaves without confirming
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        exec.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void applySystemBars() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = getColor(R.color.mv_bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (night) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void refreshStatus() {
        boolean paired = AdbHelper.isPaired(this);
        statusBadge.setText(paired ? "✓" : "!");
        statusBadge.setBackgroundResource(paired ? R.drawable.bg_status_ok : R.drawable.bg_status_off);
        statusBadge.setTextColor(getColor(paired ? R.color.mv_on_primary_container : R.color.mv_error));
        statusTitle.setText(paired ? R.string.adb_status_paired : R.string.adb_status_unpaired);
        statusDesc.setText(!paired ? R.string.adb_status_unpaired_desc
                : AdbBridge.isTcpMode(this) ? R.string.adb_status_paired_desc_tcp : R.string.adb_status_paired_desc);
    }

    /** Saves the port in the field if it is 1024-65535; otherwise shows an error and puts the saved one back. */
    private void saveTcpPort() {
        String t = tcpPortField.getText().toString().trim();
        int port = -1;
        try {
            port = Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
        }
        if (AdbBridge.setTcpPort(this, port)) {
            if (!t.equals(String.valueOf(port))) tcpPortField.setText(String.valueOf(port));
            return;
        }
        if (!t.isEmpty()) showMessage(getString(R.string.adb_tcp_port_invalid), true);
        tcpPortField.setText(String.valueOf(AdbBridge.tcpPort(this)));
    }

    private void showMessage(CharSequence text, boolean error) {
        message.setText(text);
        message.setTextColor(getColor(error ? R.color.mv_error : R.color.mv_primary));
        message.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ actions

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    private void startNotificationPairing() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            showMessage(getString(R.string.adb_allow_notifications), true);
            return;
        }
        AdbPairingService.start(this);
        showMessage(getString(R.string.adb_waiting_dialog), false);
        openDeveloperOptions();
    }

    private void pairManually() {
        final int port;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException e) {
            showMessage(getString(R.string.adb_enter_port), true);
            return;
        }
        final String code = codeField.getText().toString().trim();
        if (code.isEmpty()) {
            showMessage(getString(R.string.adb_enter_code), true);
            return;
        }
        pairButton.setEnabled(false);
        showMessage(getString(R.string.adb_pairing), false);
        final android.content.Context app = getApplicationContext();
        exec.execute(() -> {
            String text;
            boolean error;
            try {
                AdbHelper.pair(app, port, code);
                text = app.getString(R.string.adb_paired_ok);
                error = false;
            } catch (Throwable t) {
                text = app.getString(R.string.adb_pair_failed, String.valueOf(t.getMessage()));
                error = true;
            }
            final String shown = text;
            final boolean err = error;
            runOnUiThread(() -> {
                showMessage(shown, err);
                pairButton.setEnabled(true);
                refreshStatus();
            });
        });
    }
}
