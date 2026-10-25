package com.gathin.tvhome;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Quick panel, laid out like a Control Center (top_capsule's Settings button, and the remote's Settings button
 * through HomeKeyService): a glass sheet that slides in from the right with rounded modules. Connectivity is four
 * round toggles (Wi-Fi, Bluetooth, Inputs, the screensaver); Picture and Sound are module buttons; the volume is a
 * slider Left and Right move and OK mutes; then the apps Home opened last, and Launcher and TV settings. Translucent
 * and in a task of its own, so over Netflix the app keeps playing behind it and Back returns to it; pressing
 * Settings again closes it. Inputs and Launcher settings bring Home forward, where they live.
 */
public final class QuickPanelActivity extends Activity {

    /** The panel for this theme, from anywhere: Home's gear, a remote button, HomeKeyService. */
    static Intent intent(Context ctx, Config cfg) {
        return new Intent(ctx, QuickPanelActivity.class)
                .putExtra(EXTRA_ACCENT, cfg.uiAccent != 0 ? cfg.uiAccent : 0xFFA6E8D6)
                .putExtra(EXTRA_ICON_TINT, cfg.iconTint)
                .putExtra(EXTRA_ANIMATIONS, cfg.animations && !cfg.reducedMotion);
    }

    static final String EXTRA_NETWORK = "network", EXTRA_ACCENT = "accent", EXTRA_ICON_TINT = "iconTint",
            EXTRA_ANIMATIONS = "animations", EXTRA_FROST = "frost";

    private static final int TEXT = 0xFFF4F1EA, TEXT_DIM = 0xA6F4F1EA, FOCUSED_TEXT = 0xFF1C1C1E;
    private static final int MODULE = 0x17FFFFFF;

    private float d;
    private int accent;
    private boolean animations;
    /** The sheet and the dimmed screen behind it, faded together on open and on close. */
    private View sheetView, scrimView;
    /** Closing: the fade-out is running, or the panel has gone. */
    private boolean closing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        d = getResources().getDisplayMetrics().density;
        Intent in = getIntent();
        accent = in.getIntExtra(EXTRA_ACCENT, 0xFFA6E8D6);
        int iconTint = in.getIntExtra(EXTRA_ICON_TINT, 0);
        animations = in.getBooleanExtra(EXTRA_ANIMATIONS, true);

        FrameLayout root = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> finish());
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setPadding(dp(22), dp(20), dp(22), dp(20));
        // Dark glass with a faint tint of Home's backdrop, so the panel belongs to the room it opens over. Over Home it
        // is frosted (the wallpaper, blurred); over an app the TV may blur what is behind it (Android 12+, where the
        // firmware supports it), else the glass is near opaque so the picture behind does not show through the text.
        int blush = HomeWash.get(this);
        boolean blurBehind = Build.VERSION.SDK_INT >= 31 && getWindowManager().isCrossWindowBlurEnabled();
        if (blurBehind) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.setBlurBehindRadius(dp(40));
            getWindow().setAttributes(attrs);
        }
        if (in.getBooleanExtra(EXTRA_FROST, false) && Frost.ready()) {
            int tint = blush == 0 ? 0xB01C1C1F : (mix(blush, 0xFF1C1C1F, 0.14f) & 0x00FFFFFF) | 0xB0000000;
            sheet.setBackground(new FrostedDrawable(sheet, dp(32), tint, d).frostAlpha(255));
        } else {
            int alpha = blurBehind ? 0xC7000000 : 0xF5000000;
            int top = (blush == 0 ? 0xFF2A2A2D : mix(blush, 0xFF242427, 0.16f)) & 0x00FFFFFF | alpha;
            GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{top, (0x1A1A1D | alpha)});
            glass.setCornerRadius(dp(32));
            glass.setStroke(Math.max(1, Math.round(1.5f * d)), 0x38FFFFFF);
            sheet.setBackground(glass);
        }
        sheet.setElevation(dp(18));

        // Header: the date, small, like a status line.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.qp_title), 20, TEXT, R.string.font_medium),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(DateUtils.formatDateTime(this, System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR), 13, TEXT_DIM, R.string.font_regular));
        sheet.addView(head);

        // Row 1: connectivity module (four round toggles) and a stack of two module buttons.
        String network = in.getStringExtra(EXTRA_NETWORK);
        if (network == null) network = ssid();
        boolean online = online();
        boolean bluetooth = bluetoothOn();
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setClipChildren(false);

        LinearLayout connectivity = module(LinearLayout.VERTICAL);
        connectivity.setPadding(dp(12), dp(16), dp(12), dp(14));
        LinearLayout c1 = hrow(), c2 = hrow();
        View wifi = toggle(c1, R.drawable.ic_line_wifi, online ? (TextUtils.isEmpty(network) ? getString(R.string.qp_connected) : network)
                : getString(R.string.offline), online, v -> open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS)));
        toggle(c1, R.drawable.ic_line_bluetooth, getString(R.string.qp_bluetooth), bluetooth,
                v -> open(SystemSettings.intent(this, Settings.ACTION_BLUETOOTH_SETTINGS)));
        toggle(c2, R.drawable.ic_hc_input, getString(R.string.inputs), false,
                v -> open(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_VIEW_INPUTS)));
        toggle(c2, R.drawable.ic_hc_clock, getString(R.string.qp_screensaver), false, v -> openScreensaver());
        connectivity.addView(c1);
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        c2Lp.topMargin = dp(16);
        connectivity.addView(c2, c2Lp);
        row1.addView(connectivity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setClipChildren(false);
        moduleButton(stack, R.drawable.ic_hc_picture, getString(R.string.home_center_picture), getString(R.string.qp_display),
                v -> open(SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS)));
        moduleButton(stack, R.drawable.ic_hc_sound, getString(R.string.home_center_sound), getString(R.string.qp_volume),
                v -> open(SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS)));
        // Sleep timer: OK steps it through off, 15, 30, 60 and 90 minutes; the line under it says how long is left.
        final TextView[] sleepLine = new TextView[1];
        sleepLine[0] = moduleButton(stack, R.drawable.ic_hc_clock, getString(R.string.qp_sleep), sleepText(HomeKeyService.sleepMinutesLeft()), v -> {
            int minutes = HomeKeyService.nextSleepChoice();
            if (minutes < 0) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            else sleepLine[0].setText(sleepText(minutes));
        });
        LinearLayout.LayoutParams stackLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        stackLp.leftMargin = dp(12);
        row1.addView(stack, stackLp);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = dp(16);
        sheet.addView(row1, row1Lp);

        // Row 2: volume.
        VolumeSlider volume = new VolumeSlider(this, accent, blush);
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        volLp.topMargin = dp(12);
        sheet.addView(volume, volLp);

        // Row 3: recent apps, in a module of their own.
        LinearLayout recent = hrow();
        PackageManager pm = getPackageManager();
        int size = dp(50);
        for (String pkg : LastOpened.recent(this, 9)) {
            if (recent.getChildCount() >= 5) break;
            if (pkg.equals(getPackageName())) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            Drawable icon;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                icon = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setClipChildren(false);
            FrameLayout appTile = HomeCenter.glassAppTile(this, icon, iconTint, size, animations);
            appTile.setContentDescription(label);
            Intent target = launch;
            appTile.setOnClickListener(v -> {
                if (open(target.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))) LastOpened.record(this, pkg);
            });
            column.addView(appTile, new LinearLayout.LayoutParams(size, size));
            TextView name = text(label.toString(), 11, TEXT_DIM, R.string.font_medium);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(size + dp(14), ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dp(6);
            column.addView(name, nameLp);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            recent.addView(column, colLp);
        }
        if (recent.getChildCount() > 0) {
            LinearLayout recentModule = module(LinearLayout.VERTICAL);
            recentModule.setPadding(dp(12), dp(12), dp(12), dp(10));
            TextView label = text(getString(R.string.qp_recent), 11, TEXT_DIM, R.string.font_medium);
            label.setLetterSpacing(0.14f);
            recentModule.addView(label);
            LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            recentLp.topMargin = dp(10);
            recentModule.addView(recent, recentLp);
            LinearLayout.LayoutParams moduleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            moduleLp.topMargin = dp(12);
            sheet.addView(recentModule, moduleLp);
        }

        // Footer: Launcher settings, TV settings.
        sheet.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        LinearLayout foot = hrow();
        footButton(foot, R.drawable.ic_home_settings, getString(R.string.launcher_settings),
                v -> open(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)));
        footButton(foot, R.drawable.ic_line_settings, getString(R.string.qp_tv_settings),
                v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        footLp.topMargin = dp(12);
        sheet.addView(foot, footLp);

        int width = Math.min(dp(420), Math.round(getResources().getDisplayMetrics().widthPixels * 0.46f));
        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        sheetLp.setMargins(0, dp(18), dp(18), dp(18));
        root.addView(sheet, sheetLp);
        setContentView(root);

        wifi.requestFocus();
        sheetView = sheet;
        scrimView = scrim;
        if (animations) {
            // soft_motion: a slow fade with the faintest drift; otherwise the sheet slides in from the right.
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(Motion.ms(220, 340)).start();
            sheet.setTranslationX(dp(Motion.soft ? 16 : 48));
            sheet.setAlpha(0f);
            sheet.animate().translationX(0f).alpha(1f).setDuration(Motion.ms(260, 380))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    /** Back, Settings again, or a click outside: with soft_motion the panel fades out before it goes. */
    @Override
    public void finish() {
        if (closing) return;
        closing = true;
        if (!animations || !Motion.soft || sheetView == null) {
            super.finish();
            overridePendingTransition(0, 0);
            return;
        }
        scrimView.animate().cancel();
        sheetView.animate().cancel();
        scrimView.animate().alpha(0f).setDuration(240).start();
        sheetView.animate().alpha(0f).translationX(dp(10)).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(() -> {
                    QuickPanelActivity.super.finish();
                    overridePendingTransition(0, 0);
                }).start();
    }

    /** Gone at once: another screen is taking over, so there is nothing to fade over. */
    private void finishNow() {
        if (sheetView != null) sheetView.animate().cancel();
        if (scrimView != null) scrimView.animate().cancel();
        closing = true;
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen closes the panel, so returning to Home never finds it still open. */
    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) finishNow();
        overridePendingTransition(0, 0);
    }

    // ---- building -----------------------------------------------------------------------------------

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        return r;
    }

    /** A Control Center module: a rounded, lighter pane on the sheet. */
    private LinearLayout module(int orientation) {
        LinearLayout m = new LinearLayout(this);
        m.setOrientation(orientation);
        m.setClipChildren(false);
        m.setClipToPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MODULE);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, Math.round(d)), 0x14FFFFFF);
        m.setBackground(bg);
        return m;
    }

    /**
     * A round toggle with its name under it: filled with the accent while on, glass while off, white while focused.
     * The circle is the focusable; the label follows its state.
     */
    private View toggle(LinearLayout row, int icon, String label, boolean on, View.OnClickListener click) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(on ? accent : 0x26FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(0xFFFAF8F2);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        circle.setBackground(states);
        circle.setFocusable(true);
        circle.setClickable(true);
        circle.setContentDescription(label);
        circle.setOnClickListener(click);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        // Dark on the accent and on white, the accent on glass.
        i.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{FOCUSED_TEXT, on ? FOCUSED_TEXT : accent}));
        i.setDuplicateParentStateEnabled(true);
        circle.addView(i, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        circle.setOnFocusChangeListener((v, f) -> lift(v, f, 1.1f));
        column.addView(circle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = text(label, 11, TEXT_DIM, R.string.font_medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(7);
        column.addView(name, nameLp);
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return circle;
    }

    private String sleepText(int minutes) {
        return minutes <= 0 ? getString(R.string.qp_sleep_off) : getString(R.string.qp_sleep_in, minutes);
    }

    /** A module that is one button: icon, title and a line under it, which it returns. */
    private TextView moduleButton(LinearLayout stack, int icon, String title, String status, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), 0, dp(14), 0);
        b.setBackground(states(dp(24), MODULE, 0x14FFFFFF));
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(title);
        b.setOnClickListener(click);
        FrameLayout dot = new FrameLayout(this);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x26FFFFFF);
        dot.setBackground(circle);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(focusColours(accent));
        i.setDuplicateParentStateEnabled(true);
        dot.setDuplicateParentStateEnabled(true);
        dot.addView(i, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        b.addView(dot, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        TextView t = text(title, 14, TEXT, R.string.font_medium);
        t.setTextColor(focusColours(TEXT));
        t.setDuplicateParentStateEnabled(true);
        words.addView(t);
        TextView s = text(status, 11, TEXT_DIM, R.string.font_regular);
        s.setTextColor(focusColours(TEXT_DIM));
        s.setDuplicateParentStateEnabled(true);
        s.setSingleLine(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2);
        words.addView(s, sLp);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wLp.leftMargin = dp(12);
        b.addView(words, wLp);
        b.setOnFocusChangeListener((v, f) -> lift(v, f, 1.04f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (stack.getChildCount() > 0) lp.topMargin = dp(10);
        stack.addView(b, lp);
        return s;
    }

    private void footButton(LinearLayout row, int icon, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(states(dp(21), 0x12FFFFFF, 0x1AFFFFFF));
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setImageTintList(focusColours(accent));
        i.setDuplicateParentStateEnabled(true);
        b.addView(i, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView t = text(label, 13, TEXT, R.string.font_medium);
        t.setTextColor(focusColours(TEXT));
        t.setDuplicateParentStateEnabled(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(8);
        b.addView(t, tLp);
        b.setOnFocusChangeListener((v, f) -> lift(v, f, 1.04f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = dp(10);
        row.addView(b, lp);
    }

    private StateListDrawable states(int radius, int fill, int stroke) {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(fill);
        idle.setCornerRadius(radius);
        idle.setStroke(Math.max(1, Math.round(d)), stroke);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xF2FAF8F2);
        focused.setCornerRadius(radius);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, idle);
        return s;
    }

    private static ColorStateList focusColours(int idle) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUSED_TEXT, idle});
    }

    private TextView text(String s, int sp, int colour, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(UiFont.get(this, font));
        t.setIncludeFontPadding(false);
        return t;
    }

    private void lift(View v, boolean focused, float scale) {
        float to = focused ? scale : 1f;
        if (!animations) {
            v.setScaleX(to);
            v.setScaleY(to);
            return;
        }
        v.animate().scaleX(to).scaleY(to).setDuration(focused ? 200 : 150)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private static int mix(int a, int b, float share) {
        float k = 1f - share;
        return 0xFF000000 | (Math.round(((a >> 16) & 0xFF) * share + ((b >> 16) & 0xFF) * k) << 16)
                | (Math.round(((a >> 8) & 0xFF) * share + ((b >> 8) & 0xFF) * k) << 8)
                | Math.round((a & 0xFF) * share + (b & 0xFF) * k);
    }

    /**
     * The volume slider: a capsule that fills from the left with the media volume. Left and Right step it (no system
     * volume bar), OK mutes and unmutes. White while focused, like the other controls.
     */
    private static final class VolumeSlider extends View {
        private final AudioManager audio;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable speaker;
        private final RectF rect = new RectF();
        private final float d;
        private final int accent;
        /** The level's fill: Home's backdrop colour (HomeWash), so the volume matches the room; cream without one. */
        private final int level;

        VolumeSlider(Context ctx, int accent, int blush) {
            super(ctx);
            this.accent = accent;
            this.level = blush != 0 ? 0xFF000000 | blush : 0xE6F4F1EA;
            d = ctx.getResources().getDisplayMetrics().density;
            audio = ctx.getSystemService(AudioManager.class);
            speaker = ctx.getDrawable(R.drawable.ic_hc_sound);
            track.setColor(0x24FFFFFF);
            label.setTypeface(UiFont.get(ctx, R.string.font_medium));
            label.setTextSize(13 * ctx.getResources().getDisplayMetrics().scaledDensity);
            label.setTextAlign(Paint.Align.RIGHT);
            setFocusable(true);
            setClickable(true);
            setContentDescription(ctx.getString(R.string.home_center_sound));
            setOnFocusChangeListener((v, f) -> {
                invalidate();
                v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(180).start();
            });
            setOnClickListener(v -> {
                if (audio != null) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
                invalidate();
            });
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (audio != null && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                invalidate();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), r = h / 2f;
            boolean focused = isFocused();
            int max = audio == null ? 1 : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            int level = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            boolean muted = audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
            float share = muted ? 0f : Math.min(1f, level / (float) max);
            rect.set(0, 0, w, h);
            track.setColor(focused ? 0x40FFFFFF : 0x24FFFFFF);
            c.drawRoundRect(rect, r, r, track);
            float fillW = Math.max(h, w * share);
            rect.set(0, 0, fillW, h);
            fill.setColor(focused ? 0xFFFAF8F2 : level);
            if (share > 0f) c.drawRoundRect(rect, r, r, fill);
            int icon = Math.round(22 * d);
            int left = Math.round(r - icon / 2f), top = Math.round((h - icon) / 2f);
            speaker.setBounds(left, top, left + icon, top + icon);
            speaker.setTint(share > 0f ? FOCUSED_TEXT : accent);
            speaker.setAlpha(muted ? 110 : 255);
            speaker.draw(c);
            label.setColor(share > 0.88f ? FOCUSED_TEXT : TEXT);
            c.drawText(muted ? "—" : String.valueOf(Math.round(share * 100)), w - r * 0.8f,
                    h / 2f - (label.descent() + label.ascent()) / 2f, label);
        }
    }

    // ---- state and actions --------------------------------------------------------------------------

    private boolean online() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean bluetoothOn() {
        try {
            BluetoothManager bm = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The Wi-Fi network's name, read here when the panel is opened from outside Home; null when unknown. */
    private String ssid() {
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            String s = info == null ? null : info.getSSID();
            if (s == null || s.isEmpty() || s.equals(WifiManager.UNKNOWN_SSID) || s.equals("<unknown ssid>")) return null;
            return s.startsWith("\"") && s.endsWith("\"") && s.length() > 1 ? s.substring(1, s.length() - 1) : s;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void openScreensaver() {
        Intent dream = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
        if (getPackageManager().resolveActivity(dream, 0) == null) dream = new Intent(this, ClockActivity.class);
        open(dream);
    }

    private boolean open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finishNow();
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
