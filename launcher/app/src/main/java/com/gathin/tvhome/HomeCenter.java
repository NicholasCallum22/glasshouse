package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The Home center (theme key home_center): two glass panels in the space above the dock. The left one has a
 * search pill (the Google assistant, by voice or keyboard) and Inputs, Picture, Sound and Clock; the right one,
 * Continue, has the apps Home opened last. Built in code so the theme can switch it on without a layout change;
 * MainActivity places it ({@link #place}) and sends focus in from the dock and the top bar.
 */
final class HomeCenter {
    interface Host {
        /** Down from the Home center's bottom row: back to the dock. */
        void onHomeCenterDown();

        /** The Inputs button: the launcher's own Inputs menu. */
        void onHomeCenterInputs();

        /** Up from the Home center's top: the top bar. */
        void onHomeCenterUp();
    }

    private static final int RECENT_MAX = 4;
    private static final int TEXT = 0xFFF4F1EA, TEXT_DIM = 0xB3F4F1EA, FOCUSED_TEXT = 0xFF1C1C1E;

    /** The whole band, added to the activity's root and placed by {@link #place}. */
    final LinearLayout view;
    private final Context ctx;
    private final Host host;
    private final float d;
    private final int accent;
    /** The tiles' one colour (icon_tint), 0 for the apps' own. */
    private final int iconTint;
    private final boolean animations;
    private final View search;
    /** The bottom row's focusables, left to right: the four buttons, then the Continue tiles. */
    private final List<View> bottomRow = new ArrayList<>();
    private final List<View> buttons = new ArrayList<>();
    private final LinearLayout recentRow;
    private final TextView recentEmpty;
    private int naturalHeight;

    /** Where {@link #place} put the band, before {@link #followScroll} moves it with the grid. */
    private float baseY;
    /** {@link #place} found room for the band. */
    private boolean placed;
    /** How far the dock has scrolled up from its parking line, in pixels; 0 while the dock is in place. */
    private int scrolled;

    HomeCenter(Context ctx, Host host, int accent, int iconTint, boolean animations) {
        this.ctx = ctx;
        this.host = host;
        this.accent = accent;
        this.iconTint = iconTint;
        this.animations = animations;
        d = ctx.getResources().getDisplayMetrics().density;

        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.setVisibility(View.GONE);

        // Left panel: search pill over four buttons.
        LinearLayout controls = panel(LinearLayout.VERTICAL);
        search = pill(R.drawable.ic_hc_sparkle, ctx.getString(R.string.home_center_search), R.drawable.ic_hc_mic);
        search.setId(View.generateViewId());
        search.setOnClickListener(v -> openSearch());
        search.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) host.onHomeCenterUp();
            return true;
        });
        controls.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        addButton(row, R.drawable.ic_hc_input, ctx.getString(R.string.inputs), v -> host.onHomeCenterInputs());
        addButton(row, R.drawable.ic_hc_picture, ctx.getString(R.string.home_center_picture),
                v -> open(SystemSettings.intent(ctx, Settings.ACTION_DISPLAY_SETTINGS)));
        addButton(row, R.drawable.ic_hc_sound, ctx.getString(R.string.home_center_sound),
                v -> open(SystemSettings.intent(ctx, Settings.ACTION_SOUND_SETTINGS)));
        addButton(row, R.drawable.ic_hc_clock, ctx.getString(R.string.home_center_clock), v -> openScreensaver());
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        rowLp.topMargin = dp(12);
        controls.addView(row, rowLp);
        view.addView(controls, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 58f));

        // Right panel: Continue.
        LinearLayout recent = panel(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(ctx.getString(R.string.home_center_continue), 12, TEXT_DIM, R.string.font_medium);
        title.setLetterSpacing(0.14f);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(text(ctx.getString(R.string.home_center_last_opened), 12, 0x99F4F1EA, R.string.font_regular));
        recent.addView(head);
        FrameLayout tiles = new FrameLayout(ctx);
        tiles.setClipChildren(false);
        recentRow = new LinearLayout(ctx);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setClipChildren(false);
        tiles.addView(recentRow);
        recentEmpty = text(ctx.getString(R.string.home_center_empty), 14, 0x99F4F1EA, R.string.font_regular);
        recentEmpty.setGravity(Gravity.CENTER_VERTICAL);
        tiles.addView(recentEmpty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
        LinearLayout.LayoutParams tilesLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78));
        tilesLp.topMargin = dp(10);
        recent.addView(tiles, tilesLp);
        LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 42f);
        recentLp.leftMargin = dp(18);
        view.addView(recent, recentLp);
    }

    /** The Inputs button, for the Inputs menu to hang under. */
    View inputsButton() {
        return buttons.isEmpty() ? null : buttons.get(0);
    }

    /** The search pill's id, for the top bar's Down. */
    int searchId() {
        return search.getId();
    }

    boolean isShown() {
        return view.getVisibility() == View.VISIBLE;
    }

    /**
     * Puts the band in {@code area} (root coordinates): its width, and bottom-aligned above the dock. If the
     * area is shorter than the band, the band is scaled down to fit, and hidden when that would make it too small.
     */
    void place(Rect area) {
        if (area.width() <= 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (lp.width != area.width()) {
            lp.width = area.width();
            view.setLayoutParams(lp);
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(area.width(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        naturalHeight = view.getMeasuredHeight();
        float scale = naturalHeight <= 0 ? 0f : Math.min(1f, area.height() / (float) naturalHeight);
        if (scale < 0.6f) {
            placed = false;
            view.setVisibility(View.GONE);
            return;
        }
        placed = true;
        view.setPivotX(area.width() / 2f);
        view.setPivotY(naturalHeight);
        view.setScaleX(scale);
        view.setScaleY(scale);
        view.setTranslationX(area.left);
        baseY = area.bottom - naturalHeight;
        followScroll(scrolled);
    }

    /**
     * Moves the band up with the grid as it scrolls down past the dock, fading it out over the first 80 dp, so it
     * never sits over the rows that scroll up under it. Fully faded, it is hidden and takes no focus; back at the
     * dock it returns.
     */
    void followScroll(int dockShift) {
        scrolled = Math.max(0, dockShift);
        if (!placed) return;
        float fade = Math.min(1f, scrolled / (80f * d));
        view.setTranslationY(baseY - scrolled);
        view.setAlpha(1f - fade);
        // The frost is drawn for where a panel sits; moved, it is redrawn for its new place.
        if (Frost.enabled) for (int i = 0; i < view.getChildCount(); i++) view.getChildAt(i).invalidate();
        int want = fade >= 0.999f ? View.INVISIBLE : View.VISIBLE;
        if (view.getVisibility() != want) view.setVisibility(want);
    }

    /** Up from the dock: the bottom-row item nearest above, by the dock tile's place across the row (0 to 1). */
    void focusFromBelow(float across) {
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        float x = across * view.getWidth();
        int[] origin = new int[2], at = new int[2];
        view.getLocationInWindow(origin);
        for (View v : bottomRow) {
            if (v.getVisibility() != View.VISIBLE || !v.isFocusable()) continue;
            v.getLocationInWindow(at);
            float centre = (at[0] - origin[0] + v.getWidth() * v.getScaleX() / 2f) / Math.max(0.01f, view.getScaleX());
            float distance = Math.abs(centre - x);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = v;
            }
        }
        (best != null ? best : search).requestFocus();
    }

    /** Rebuilds Continue from the apps Home opened last; called whenever Home comes back. */
    void refresh() {
        recentRow.removeAllViews();
        bottomRow.removeIf(v -> !buttons.contains(v));
        PackageManager pm = ctx.getPackageManager();
        int size = dp(56);
        for (String pkg : LastOpened.recent(ctx, RECENT_MAX + 4)) {
            if (recentRow.getChildCount() >= RECENT_MAX) break;
            if (pkg.equals(ctx.getPackageName())) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // uninstalled or disabled since
            Drawable icon;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                icon = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            recentRow.addView(recentTile(pkg, launch, icon, label, size));
        }
        recentEmpty.setVisibility(recentRow.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    /** Nothing to let go of: Now Playing moved to the top-left (NowPlaying). */
    void release() {
    }

    // ---- building -----------------------------------------------------------------------------------

    private View recentTile(String pkg, Intent launch, Drawable icon, CharSequence label, int size) {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);

        FrameLayout tile = glassAppTile(ctx, icon, iconTint, size, animations);
        tile.setContentDescription(label);
        tile.setOnClickListener(v -> {
            if (open(launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))) LastOpened.record(ctx, pkg);
        });
        tile.setOnKeyListener(this::bottomRowKey);
        column.addView(tile, new LinearLayout.LayoutParams(size, size));

        TextView name = text(label.toString(), 12, TEXT_DIM, R.string.font_medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(size + dp(18), ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dp(6);
        column.addView(name, nameLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (recentRow.getChildCount() > 0) lp.leftMargin = dp(12);
        column.setLayoutParams(lp);
        bottomRow.add(tile);
        return column;
    }

    /**
     * An app's glass squircle, {@code size} px square, as the dock draws it (AppAdapter.GlassArt): focusable, with a
     * white rim and a lift while focused. The Continue row and the Quick panel's recent apps use it.
     */
    static FrameLayout glassAppTile(Context ctx, Drawable icon, int iconTint, int size, boolean animations) {
        float d = ctx.getResources().getDisplayMetrics().density;
        AppAdapter.GlassArt art = AppAdapter.glassArt(ctx.getResources(), icon, iconTint != 0);
        FrameLayout tile = new FrameLayout(ctx);
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR, art.fill);
        float radius = AppAdapter.cornerRadius(size, d);
        glass.setCornerRadius(radius);
        glass.setStroke(Math.max(1, Math.round(d)), 0x2EFFFFFF);
        tile.setBackground(glass);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);
        ImageView image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inner = Math.round(size * AppAdapter.GLASS_ICON_SHARE);
        tile.addView(image, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        art.apply(image, iconTint);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnFocusChangeListener((v, focused) -> {
            glass.setStroke(Math.max(1, Math.round((focused ? 2.5f : 1f) * d)), focused ? 0xF2FFFFFF : 0x2EFFFFFF);
            float to = focused ? (Motion.soft ? 1.07f : 1.12f) : 1f;
            if (animations) v.animate().scaleX(to).scaleY(to).setDuration(Motion.soft ? (focused ? 300 : 260) : focused ? 220 : 160)
                    .setInterpolator(new DecelerateInterpolator(Motion.soft ? 2f : 1.6f)).start();
            else {
                v.setScaleX(to);
                v.setScaleY(to);
            }
        });
        return tile;
    }

    private LinearLayout panel(int orientation) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(orientation);
        p.setClipChildren(false);
        p.setClipToPadding(false);
        p.setPadding(dp(16), dp(14), dp(16), dp(14));
        if (Frost.enabled) {
            p.setBackground(new FrostedDrawable(p, dp(28), 0x4718181A, d));
        } else {
            GradientDrawable glass = new GradientDrawable();
            glass.setColor(0x61181819);
            glass.setCornerRadius(dp(28));
            glass.setStroke(Math.max(1, Math.round(1.5f * d)), 0x2BFFFFFF);
            p.setBackground(glass);
        }
        return p;
    }

    /** The search pill: an icon, the prompt, and a mic, the whole pill one button. */
    private View pill(int leading, String prompt, int trailing) {
        LinearLayout p = new LinearLayout(ctx);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(22), 0, dp(22), 0);
        p.setBackground(buttonBackground(dp(24), 0x38000000, 0x4DFFFFFF));
        p.setFocusable(true);
        p.setClickable(true);
        p.setContentDescription(prompt);
        p.addView(icon(leading), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(prompt, 16, TEXT, R.string.font_regular);
        t.setTextColor(focusColours(TEXT));
        t.setDuplicateParentStateEnabled(true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.leftMargin = dp(16);
        tLp.rightMargin = dp(12);
        p.addView(t, tLp);
        p.addView(icon(trailing), new LinearLayout.LayoutParams(dp(22), dp(22)));
        p.setOnFocusChangeListener((v, focused) -> lift(v, focused, 1.03f));
        return p;
    }

    private void addButton(LinearLayout row, int iconRes, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(buttonBackground(dp(22), 0x14FFFFFF, 0x1AFFFFFF));
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(label);
        b.addView(icon(iconRes), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(label, 14, TEXT, R.string.font_medium);
        t.setTextColor(focusColours(TEXT));
        t.setDuplicateParentStateEnabled(true);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.leftMargin = dp(10);
        b.addView(t, tLp);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, focused) -> lift(v, focused, 1.06f));
        b.setOnKeyListener(this::bottomRowKey);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = dp(10);
        row.addView(b, lp);
        buttons.add(b);
        bottomRow.add(b);
    }

    /**
     * Down from the bottom row returns to the dock. Up from a Continue tile (nothing above it in the panel) goes on
     * to the top bar; Up from the buttons is the normal focus search, to the search pill.
     */
    private boolean bottomRowKey(View v, int keyCode, KeyEvent event) {
        boolean up = keyCode == KeyEvent.KEYCODE_DPAD_UP && !buttons.contains(v);
        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && !up) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (up) host.onHomeCenterUp();
            else host.onHomeCenterDown();
        }
        return true;
    }

    /** Glass when idle, a bright white slab when focused, like the top bar's pills. */
    private StateListDrawable buttonBackground(int radius, int fill, int stroke) {
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(fill);
        idle.setCornerRadius(radius);
        idle.setStroke(Math.max(1, Math.round(d)), stroke);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xF2FAF8F2);
        focused.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        return states;
    }

    private ImageView icon(int res) {
        ImageView i = new ImageView(ctx);
        i.setImageResource(res);
        i.setImageTintList(focusColours(accent));
        i.setDuplicateParentStateEnabled(true);
        return i;
    }

    /** {@code idle} normally, dark on the focused white slab. */
    private static ColorStateList focusColours(int idle) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{FOCUSED_TEXT, idle});
    }

    private TextView text(String s, int sp, int colour, int font) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(UiFont.get(ctx, font));
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
        if (Motion.soft) to = focused ? 1f + (scale - 1f) * 0.6f : 1f;
        v.animate().scaleX(to).scaleY(to).setDuration(Motion.soft ? (focused ? 300 : 260) : focused ? 220 : 160)
                .setInterpolator(new DecelerateInterpolator(Motion.soft ? 2f : 1.6f)).start();
    }

    // ---- actions ------------------------------------------------------------------------------------

    /** The Google assistant (voice or keyboard), else the system's global search. */
    private void openSearch() {
        PackageManager pm = ctx.getPackageManager();
        for (Intent i : new Intent[]{new Intent(Intent.ACTION_ASSIST), new Intent("android.search.action.GLOBAL_SEARCH"),
                new Intent(Intent.ACTION_WEB_SEARCH)}) {
            if (pm.resolveActivity(i, 0) != null && open(i)) return;
        }
        Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    /** The screensaver chosen in the TV's settings, else the launcher's own clock (as RemoteKeys does). */
    private void openScreensaver() {
        Intent dream = new Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator");
        if (ctx.getPackageManager().resolveActivity(dream, 0) == null) dream = new Intent(ctx, ClockActivity.class);
        open(dream);
    }

    private boolean open(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private int dp(float v) {
        return Math.round(v * d);
    }
}
