package com.huskydg.magicv2ray;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * "Allow <app> to read your nodes?" (API level 2). Started by an app with {@code startActivityForResult}: the caller
 * is the package Android reports for that call ({@link #getCallingPackage}), so it can not be a website, a link or a
 * broadcast, and it can not be faked. Started any other way it just ends.
 *
 * <p>The user picks what the app sees:
 * <ul>
 *   <li><b>Anonymous info</b> (default): name, protocol and an id; a node is {@code nodeid://<id>}.</li>
 *   <li><b>Full node info</b>: the node links themselves ({@code vless://...}).</li>
 * </ul>
 * and for how long: <b>Allow once</b> (10 minutes, memory only), <b>Always allow</b> (until revoked in the Access
 * permissions tab), or <b>Cancel</b>. Tapping outside the dialog is Cancel.
 *
 * <p>Result: RESULT_OK with {@code granted=true, level, persistent}, or RESULT_CANCELED with {@code granted=false}.
 */
public class ExternalNodeAccessActivity extends Activity {

    private String caller;
    private boolean done;
    private RadioButton fullRadio;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppPrefs.wrap(base));       // the user's light / dark choice
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // recreated after process death: the same request must not ask twice
        if (savedInstanceState != null) {
            finish();
            return;
        }
        Intent in = getIntent();
        caller = getCallingPackage();           // null unless started with startActivityForResult
        if (in == null || !ExternalNodes.ACTION_REQUEST_ACCESS.equals(in.getAction())
                || caller == null || caller.isEmpty() || caller.equals(getPackageName())) {
            reply(false, ExternalNodes.LEVEL_NONE, false, caller == null ? "unknown_caller" : "bad_request");
            return;
        }
        final boolean wantsFull = ExternalNodes.LEVEL_FULL.equals(in.getStringExtra(ExternalNodes.EXTRA_LEVEL));
        final Context app = getApplicationContext();

        // already allowed: nothing to ask, unless the app wants more than it has
        String have = ExternalNodes.accessLevel(app, caller);
        if (!ExternalNodes.LEVEL_NONE.equals(have) && !(wantsFull && ExternalNodes.LEVEL_ANONYMOUS.equals(have))) {
            reply(true, have, ExternalNodes.isPermanent(app, caller), null);
            return;
        }
        buildDialog(wantsFull);
    }

    private void reply(boolean granted, String level, boolean persistent, String error) {
        if (done) return;
        done = true;
        if (!granted && error == null && caller != null) {
            ExternalAppLog.add(getApplicationContext(), caller, ExternalAppLog.K_NODES_DENIED, "");   // Cancel / tap outside
        }
        Intent r = new Intent()
                .putExtra(ExternalNodes.RESULT_GRANTED, granted)
                .putExtra(ExternalNodes.RESULT_LEVEL, level)
                .putExtra(ExternalNodes.RESULT_PERSISTENT, persistent);
        if (error != null) r.putExtra(ExternalNodes.RESULT_ERROR, error);
        setResult(granted ? RESULT_OK : RESULT_CANCELED, r);
        finish();
    }

    private void choose(boolean always) {
        String level = fullRadio != null && fullRadio.isChecked() ? ExternalNodes.LEVEL_FULL : ExternalNodes.LEVEL_ANONYMOUS;
        boolean ok = ExternalNodes.grant(getApplicationContext(), caller, level, always);
        if (ok) ExternalAppLog.add(getApplicationContext(), caller, always ? ExternalAppLog.K_NODES_ALWAYS : ExternalAppLog.K_NODES_ONCE, level);
        reply(ok, ok ? level : ExternalNodes.LEVEL_NONE, ok && always, ok ? null : "grant_failed");
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
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
        b.setFilterTouchesWhenObscured(true);       // an overlay can not click "Allow" for the user
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** One radio option: a title and a smaller description under it. */
    private RadioButton option(RadioGroup group, int title, int desc, int onSurface, int variant, int primary) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        SpannableStringBuilder sb = new SpannableStringBuilder(getString(title));
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append(getString(desc));
        rb.setText(sb);
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rb.setTextColor(onSurface);
        rb.setButtonTintList(ColorStateList.valueOf(primary));
        rb.setPadding(dp(8), dp(10), dp(8), dp(10));
        rb.setBackground(shape(getColor(R.color.mv_surface_container), 12, 12, 12, 12));
        RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        group.addView(rb, lp);
        return rb;
    }

    private void buildDialog(boolean wantsFull) {
        final int onSurface = getColor(R.color.mv_on_surface);
        final int variant = getColor(R.color.mv_on_surface_variant);
        final int primary = getColor(R.color.mv_primary);

        String label = ExternalSession.labelOf(this, caller);
        String who = label == null || label.isEmpty() ? caller : label;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x8C000000);
        root.setFilterTouchesWhenObscured(true);
        root.setOnClickListener(v -> reply(false, ExternalNodes.LEVEL_NONE, false, null));     // outside the dialog = Cancel

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

        ImageView icon = new ImageView(this);
        Drawable d = null;
        try {
            d = getPackageManager().getApplicationIcon(caller);
        } catch (Exception ignored) {
        }
        if (d != null) {
            icon.setImageDrawable(d);
        } else {
            icon.setImageResource(R.drawable.ic_mode_vpn);
            icon.setColorFilter(primary);
        }
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        // "Allow <b>app</b> to read your nodes?"
        String q = getString(R.string.ext_nodes_question, "\u0001");
        int at = q.indexOf('\u0001');
        SpannableStringBuilder sb = new SpannableStringBuilder(q.replace("\u0001", who));
        if (at >= 0) sb.setSpan(new StyleSpan(Typeface.BOLD), at, at + who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView title = text(sb, 22, onSurface, false);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(16);
        card.addView(title, tlp);

        TextView pkg = text(caller, 12, variant, true);
        pkg.setGravity(Gravity.CENTER);
        card.addView(pkg);

        if (wantsFull) {
            TextView w = text(getString(R.string.ext_nodes_wants_full), 13, variant, false);
            w.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(10);
            card.addView(w, wlp);
        }

        // what the app may see: anonymous (default) or full
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        RadioButton anon = option(group, R.string.ext_nodes_level_anon, R.string.ext_nodes_level_anon_desc, onSurface, variant, primary);
        fullRadio = option(group, R.string.ext_nodes_level_full, R.string.ext_nodes_level_full_desc, onSurface, variant, primary);
        anon.setChecked(true);                              // the safe choice is the default, even when the app asked for more
        card.addView(group, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView warn = text(getString(R.string.ext_nodes_warn_full), 13, getColor(R.color.mv_on_surface), false);
        warn.setPadding(dp(12), dp(10), dp(12), dp(10));
        warn.setBackground(shape(getColor(R.color.mv_error_container), 8, 8, 8, 8));
        if (!AppPrefs.isNight(this)) warn.setTextColor(0xFF410002);
        warn.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp2.topMargin = dp(10);
        card.addView(warn, wlp2);
        group.setOnCheckedChangeListener((g, id) -> warn.setVisibility(fullRadio.isChecked() ? View.VISIBLE : View.GONE));

        TextView hint = text(getString(R.string.ext_nodes_once_hint), 12, variant, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(14);
        card.addView(hint, hlp);

        // buttons: Allow once / Always allow / Cancel
        card.addView(actionButton(R.string.ext_prompt_once, 16, 4, () -> choose(false)));
        card.addView(actionButton(R.string.ext_prompt_always, 4, 4, () -> choose(true)));
        card.addView(actionButton(R.string.ext_prompt_cancel, 4, 16, () -> reply(false, ExternalNodes.LEVEL_NONE, false, null)));

        root.addView(scroll);
        setContentView(root);
    }
}
