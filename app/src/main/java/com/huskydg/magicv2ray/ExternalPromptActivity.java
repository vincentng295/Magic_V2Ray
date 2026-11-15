package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Entry point of the external control API (magicv2ray://connect | disconnect, the CONNECT / DISCONNECT
 * actions, and a tap on the "connection request" notification).
 *
 * <p>It is a translucent activity: the permission dialog is drawn right over the app that asked, like
 * Shizuku's, and Magic V2Ray itself is never brought to the front. "Deny" and a tap outside just close it.
 * "Allow" parks the request as approved and lets the WebUI (which owns config generation) do the work in the
 * background, reporting only with a toast; see external.js (req.approved).
 *
 * <p>Nothing is connected without this dialog, except for an app the user chose to "always allow" when
 * the request goes no further than what that app is (same rule as before: no warning, no exclusion
 * other than the caller itself).
 */
public class ExternalPromptActivity extends Activity {

    private JSONObject req;
    private boolean done;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppPrefs.wrap(base));       // the user's light / dark choice
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        // recreated after process death, or replayed from history: the same request must not ask twice
        if (savedInstanceState != null || in == null
                || (in.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            finish();
            return;
        }
        final Context app = getApplicationContext();

        if (ExternalSession.ACTION_REVIEW.equals(in.getAction())) {        // a request that arrived by broadcast
            ExternalSession.clearPendingNotification(app);
            String parked = ExternalSession.take(app);
            try {
                req = parked.isEmpty() ? null : new JSONObject(parked);
            } catch (JSONException e) {
                req = null;
            }
        } else {
            req = ExternalSession.request(this, in, ExternalSession.callerOf(this, in), false);
        }
        if (req == null) { finish(); return; }

        if ("disconnect".equals(req.optString("action"))) {
            new Thread(() -> {
                final boolean had = ExternalSession.isActive(app);
                ExternalSession.end(app);
                runOnUiThread(() -> {
                    Toast.makeText(app, had ? R.string.ext_ended : R.string.ext_no_session, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }, "mv-ext-end").start();
            return;
        }
        if (!"connect".equals(req.optString("action"))) { finish(); return; }

        final String caller = req.optString("caller", "");
        final boolean nativeCaller = !caller.isEmpty() && !req.optBoolean("browser");
        final String uri = req.optString("uri", "");

        // ---- what the request asks for, to warn about (same rules as external.js _extHandleConnect)
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (isPlain(uri, "vless")) warnings.add(getString(R.string.ext_prompt_warn_vless));
        if (isPlain(uri, "trojan")) warnings.add(getString(R.string.ext_prompt_warn_trojan));
        java.util.List<String> excludes = new java.util.ArrayList<>();
        JSONArray ex = req.optJSONArray("exclude");
        for (int i = 0; ex != null && i < ex.length(); i++) excludes.add(ex.optString(i));
        excludes.remove(getPackageName());
        String host = hostOf(uri);
        if (nativeCaller && isLocalTarget(host) && !excludes.contains(caller)) excludes.add(caller);
        if (isLocalTarget(host) && excludes.isEmpty()) warnings.add(getString(R.string.ext_prompt_warn_loop));

        // temporary setting overrides: listed in the dialog, and confirmed unless the app is one the user always trusts
        final String ovSummary = overridesSummary(req.optJSONObject("overrides"), uri.isEmpty());
        if (!ovSummary.isEmpty() && !req.optBoolean("trusted")) warnings.add(getString(R.string.ext_prompt_warn_overrides));

        boolean onlyCaller = true;
        for (String p : excludes) if (!p.equals(caller)) onlyCaller = false;

        // an app the user always allows connects without a prompt when nothing goes beyond what that app is
        if (req.optBoolean("trusted") && warnings.isEmpty() && onlyCaller) {
            approve(false);
            return;
        }
        buildDialog(nativeCaller, caller, host, uri, excludes, warnings, !warnings.isEmpty() || !onlyCaller, ovSummary);
    }

    // ------------------------------------------------------------------ result

    private void approve(boolean trust) {
        if (done) return;
        done = true;
        final Context app = getApplicationContext();
        try {
            if (trust) ExternalSession.trustApproved(app, req);
            MainActivity m = MainActivity.current;
            req.put("approved", true);              // external.js: no dialog, no loading overlay, result as a toast
            req.put("cold", m == null);             // the WebUI is not running: it starts now, and goes back afterwards
            ExternalSession.queue(app, req);
            if (m != null) {
                m.pokeWeb("window.checkExternalRequest&&window.checkExternalRequest()");
            } else {
                startActivity(new Intent(this, MainActivity.class).setAction(ExternalSession.ACTION_REVIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
        } catch (JSONException ignored) {
        }
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ request helpers

    /** "Traffic settings: 3 / Routing rules: 12 ..." for the overrides of a request, "" when there are none. */
    private String overridesSummary(JSONObject ov, boolean keepsNode) {
        if (ov == null || ov.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        String[][] sec = {{"traffic", "ext_ov_traffic"}, {"network", "ext_ov_network"}, {"exclude", "ext_ov_exclude"},
                {"routing", "ext_ov_routing"}, {"hosts", "ext_ov_hosts"}};
        for (String[] e : sec) {
            JSONObject o = ov.optJSONObject(e[0]);
            if (o == null) continue;
            int n;
            if (e[0].equals("exclude")) n = o.optJSONArray("apps") == null ? 0 : o.optJSONArray("apps").length();
            else if (e[0].equals("routing")) n = o.optJSONArray("rules") == null ? o.length() : o.optJSONArray("rules").length();
            else if (e[0].equals("hosts")) n = o.optJSONObject("entries") == null ? 0 : o.optJSONObject("entries").length();
            else n = o.length();
            if (sb.length() > 0) sb.append('\n');
            sb.append(getString(R.string.ext_ov_item, getString(getResources().getIdentifier(e[1], "string", getPackageName())), n));
        }
        if (keepsNode) sb.append('\n').append(getString(R.string.ext_ov_selected_node));
        return sb.toString();
    }

    private static boolean isPlain(String uri, String scheme) {
        if (uri == null) return false;
        uri = uri.trim();
        try {
            if (uri.regionMatches(true, 0, "chain://", 0, 8)) {
                Uri u = Uri.parse(uri);
                return isPlain(u.getQueryParameter("hop1"), scheme) || isPlain(u.getQueryParameter("hop2"), scheme);
            }
            if (!uri.regionMatches(true, 0, scheme + "://", 0, scheme.length() + 3)) return false;
            Uri u = Uri.parse(uri);
            String sec = u.getQueryParameter("security");
            boolean plainSec = sec == null || sec.isEmpty() || "none".equals(sec);
            if (!"vless".equals(scheme)) return plainSec;
            String enc = u.getQueryParameter("encryption");
            return plainSec && (enc == null || enc.isEmpty() || "none".equals(enc));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String hostOf(String uri) {
        try {
            String h = Uri.parse(uri).getHost();
            return h == null ? "" : h;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String serverOf(String uri) {
        try {
            Uri u = Uri.parse(uri);
            String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null) return sch;
            return sch + " · " + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Loopback, or one of this device's own addresses: the app that serves it must stay out of the VPN. */
    private static boolean isLocalTarget(String host) {
        String a = host == null ? "" : host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
        if (a.isEmpty()) return false;
        if (a.equals("localhost") || a.equals("::1") || a.equals("::") || a.equals("0.0.0.0") || a.startsWith("127.")) return true;
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                Enumeration<InetAddress> as = ifs.nextElement().getInetAddresses();
                while (as.hasMoreElements()) {
                    String s = as.nextElement().getHostAddress();
                    int pct = s == null ? -1 : s.indexOf('%');
                    if (pct > 0) s = s.substring(0, pct);
                    if (a.equalsIgnoreCase(s)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ the dialog

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(CharSequence s, float sp, int color, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private GradientDrawable shape(int color, float tl, float tr, float br, float bl) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float a = dp(tl), b = dp(tr), c = dp(br), d = dp(bl);
        g.setCornerRadii(new float[]{a, a, b, b, c, c, d, d});
        return g;
    }

    private Button actionButton(int label, float top, float bottom, final Runnable onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(getColor(R.color.mv_on_secondary_container));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(52));
        b.setMinimumHeight(dp(52));
        Drawable bg = shape(getColor(R.color.mv_secondary_container), top, top, bottom, bottom);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(getColor(R.color.mv_ripple)), bg, null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private void buildDialog(boolean nativeCaller, String caller, String host, String uri,
                             java.util.List<String> excludes, java.util.List<String> warnings, boolean openDetails, String ovSummary) {
        final int onSurface = getColor(R.color.mv_on_surface);
        final int variant = getColor(R.color.mv_on_surface_variant);
        final int primary = getColor(R.color.mv_primary);

        String label = req.optString("callerLabel", "");
        String who;
        if (nativeCaller) who = label.isEmpty() ? caller : label;
        else if (!caller.isEmpty()) who = getString(R.string.ext_prompt_who_web);
        else who = getString(R.string.ext_prompt_who_unknown);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x8C000000);
        root.setOnClickListener(v -> finish());                 // outside the dialog = Deny

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        slp.topMargin = slp.bottomMargin = dp(16);
        scroll.setLayoutParams(slp);
        scroll.setBackground(shape(getColor(R.color.mv_surface_container_high), 28, 28, 28, 28));
        scroll.setClipToOutline(true);
        scroll.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        scroll.setOnClickListener(v -> { /* swallow taps on the dialog */ });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));
        scroll.addView(card, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // icon: the real one for a verified app, a generic one otherwise
        ImageView icon = new ImageView(this);
        Drawable d = null;
        if (nativeCaller) {
            try { d = getPackageManager().getApplicationIcon(caller); } catch (Exception ignored) { }
        }
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            icon.setImageResource(R.drawable.ic_mode_vpn);
            icon.setColorFilter(primary);
        }
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        // "Allow <b>app</b> to connect through Magic V2Ray?"
        String q = getString(R.string.ext_prompt_question, "\u0001");
        int at = q.indexOf('\u0001');
        SpannableStringBuilder sb = new SpannableStringBuilder(q.replace("\u0001", who));
        if (at >= 0) sb.setSpan(new StyleSpan(Typeface.BOLD), at, at + who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView title = text(sb, 22, onSurface, false);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        card.addView(title, tlp);

        if (nativeCaller) {
            TextView pkg = text(caller, 12, variant, true);
            pkg.setGravity(Gravity.CENTER);
            card.addView(pkg);
        }
        String server = serverOf(uri);
        if (!server.isEmpty()) {
            TextView srv = text(server, 12, variant, true);
            srv.setGravity(Gravity.CENTER);
            card.addView(srv);
        }

        if (!warnings.isEmpty()) {
            TextView w = text(android.text.TextUtils.join("\n\n", warnings), 13, getColor(R.color.mv_on_surface), false);
            w.setPadding(dp(12), dp(10), dp(12), dp(10));
            w.setBackground(shape(getColor(R.color.mv_error_container), 8, 8, 8, 8));
            if (!AppPrefs.isNight(this)) w.setTextColor(0xFF410002);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(14);
            card.addView(w, wlp);
        }

        // details: who is asking, the full uri, what is kept out of the VPN
        final LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(openDetails ? View.VISIBLE : View.GONE);
        details.setPadding(0, dp(4), 0, 0);
        String callerFull = nativeCaller ? (label.isEmpty() ? caller : label + " (" + caller + ")") : who;
        addFact(details, R.string.ext_prompt_lbl_caller, callerFull, false);
        addFact(details, R.string.ext_prompt_lbl_uri, uri, true);
        StringBuilder ex = new StringBuilder();
        for (String p : excludes) {
            if (ex.length() > 0) ex.append('\n');
            ex.append(labelWithPkg(p));
        }
        addFact(details, R.string.ext_prompt_lbl_exclude, excludes.isEmpty() ? getString(R.string.ext_prompt_none) : ex.toString(), false);

        if (!ovSummary.isEmpty()) addFact(details, R.string.ext_prompt_lbl_overrides, ovSummary, false);

        final TextView toggle = text("", 14, primary, false);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(0, dp(10), 0, dp(6));
        final String dtl = getString(R.string.ext_prompt_details);
        toggle.setText(dtl + (openDetails ? " ▴" : " ▾"));
        toggle.setOnClickListener(v -> {
            boolean show = details.getVisibility() != View.VISIBLE;
            details.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(dtl + (show ? " ▴" : " ▾"));
        });
        card.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(details, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // buttons: Always allow (only when it can be trusted) / Allow once / Deny
        boolean canTrust = req.optBoolean("trustable") && nativeCaller;
        View spacer = new View(this);
        card.addView(spacer, new LinearLayout.LayoutParams(1, dp(16)));
        if (canTrust) card.addView(actionButton(R.string.ext_prompt_always, 16, 4, () -> approve(true)));
        card.addView(actionButton(R.string.ext_prompt_once, canTrust ? 4 : 16, 4, () -> approve(false)));
        card.addView(actionButton(R.string.ext_prompt_deny, 4, 16, this::finish));

        root.addView(scroll);
        setContentView(root);
    }

    private String labelWithPkg(String pkg) {
        String l = ExternalSession.labelOf(this, pkg);
        return (l == null || l.equals(pkg)) ? pkg : l + " (" + pkg + ")";
    }

    private void addFact(LinearLayout parent, int labelRes, String value, boolean mono) {
        TextView l = text(getString(labelRes).toUpperCase(Locale.getDefault()), 11, getColor(R.color.mv_on_surface_variant), false);
        l.setLetterSpacing(0.04f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(10);
        parent.addView(l, llp);
        TextView v = text(value, mono ? 12 : 14, getColor(R.color.mv_on_surface), mono);
        v.setTextIsSelectable(true);
        if (mono) {
            v.setPadding(dp(10), dp(8), dp(10), dp(8));
            v.setBackground(shape(getColor(R.color.mv_surface_container), 8, 8, 8, 8));
        }
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(2);
        parent.addView(v, vlp);
    }
}
