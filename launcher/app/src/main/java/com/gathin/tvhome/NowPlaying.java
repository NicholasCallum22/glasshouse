package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Now Playing in the top-left corner (theme key now_playing): while an app plays, the clock and the playing track
 * take turns there like a ticker, one scrolling up and out as the other comes in. The track side is minimal: a
 * small NOW PLAYING line with the app, the title in large light type (scrolling sideways when it is long), the
 * artist, and a hairline timeline; beside it a round play/pause button in the album art's colour, the timeline in
 * that colour's complement. Up from the Home center reaches the button, and while it has focus the track stays
 * put. Android shows Home the playing sessions only once its notification listener (MediaListener) is allowed.
 */
final class NowPlaying {
    interface Host {
        /** Down from the button: the Home center (or the dock). */
        void onNowPlayingDown();

        /** Right from the button: the top bar. */
        void onNowPlayingRight();
    }

    private static final long CLOCK_MS = 6_000, TRACK_MS = 12_000;
    private static final int TEXT = 0xFFF4F1EA, TEXT_DIM = 0xA6F4F1EA;

    private final Context ctx;
    private final Host host;
    private final View clock;
    private final float d;
    private final boolean animations;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The widget, laid over the clock's block in the same corner. */
    final LinearLayout view;
    private final FrameLayout button;
    private final GradientDrawable buttonFill;
    private final ImageView buttonIcon;
    private final TextView label, title, artist;
    private final Timeline timeline;

    private MediaController controller;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private boolean active, showingTrack, resumed;
    private Object artKey;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            update();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            update();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    private final Runnable turn = this::turn;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            timeline.invalidate();
            if (showingTrack && resumed) main.postDelayed(this, 1_000);
        }
    };

    NowPlaying(Context ctx, RelativeLayout corner, View clock, Host host, boolean animations) {
        this.ctx = ctx;
        this.host = host;
        this.clock = clock;
        this.animations = animations;
        d = ctx.getResources().getDisplayMetrics().density;

        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.setVisibility(View.INVISIBLE);
        view.setPadding(dp(2), dp(4), 0, 0);

        button = new FrameLayout(ctx);
        buttonFill = new GradientDrawable();
        buttonFill.setShape(GradientDrawable.OVAL);
        buttonFill.setColor(0xFFA6E8D6);
        button.setBackground(buttonFill);
        button.setFocusable(true);
        button.setClickable(true);
        buttonIcon = new ImageView(ctx);
        buttonIcon.setImageResource(R.drawable.ic_hc_pause);
        button.addView(buttonIcon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        button.setOnClickListener(v -> toggle());
        button.setOnFocusChangeListener((v, focused) -> {
            buttonFill.setStroke(focused ? dp(3) : 0, 0xF2FFFFFF);
            float to = focused ? 1.08f : 1f;
            if (animations) v.animate().scaleX(to).scaleY(to).setDuration(Motion.ms(200, 300))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            else {
                v.setScaleX(to);
                v.setScaleY(to);
            }
            if (focused) {
                main.removeCallbacks(turn);   // the track stays while the button has focus
                if (!showingTrack) show(true);
            } else if (active) {
                schedule();
            }
        });
        button.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
                    && keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) host.onNowPlayingDown();
                else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) host.onNowPlayingRight();
            }
            return true;
        });
        view.addView(button, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout words = new LinearLayout(ctx);
        words.setOrientation(LinearLayout.VERTICAL);
        label = text(11, TEXT_DIM, R.string.font_medium);
        label.setLetterSpacing(0.16f);
        words.addView(label);
        title = text(24, TEXT, R.string.font_light);
        title.setLetterSpacing(-0.01f);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setHorizontalFadingEdgeEnabled(true);
        title.setFadingEdgeLength(dp(24));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(4);
        words.addView(title, titleLp);
        artist = text(13, TEXT_DIM, R.string.font_regular);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(3);
        words.addView(artist, artistLp);
        timeline = new Timeline(ctx);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        lineLp.topMargin = dp(9);
        words.addView(timeline, lineLp);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.leftMargin = dp(16);
        view.addView(words, wordsLp);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_START);
        lp.addRule(RelativeLayout.ALIGN_TOP, clock.getId());
        corner.addView(view, lp);
    }

    /** The button, for Up from the Home center; null while nothing plays. */
    View button() {
        return active && view.getVisibility() == View.VISIBLE ? button : null;
    }

    void resume() {
        resumed = true;
        refresh();
    }

    /** Home is in the background: stop the ticker and the timeline, back to the clock for the return. */
    void pause() {
        resumed = false;
        main.removeCallbacks(turn);
        main.removeCallbacks(tick);
        if (showingTrack) show(false, false);
    }

    void release() {
        pause();
        setController(null);
        if (sessionsListener != null) {
            MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
            sessionsListener = null;
        }
    }

    // ---- sessions -----------------------------------------------------------------------------------

    private void refresh() {
        MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
        if (msm == null) return;
        ComponentName listener = new ComponentName(ctx, MediaListener.class);
        List<MediaController> sessions;
        try {
            sessions = msm.getActiveSessions(listener);
            if (sessionsListener == null) {
                sessionsListener = list -> main.post(this::refresh);
                msm.addOnActiveSessionsChangedListener(sessionsListener, listener, main);
            }
        } catch (SecurityException notAllowed) {
            setController(null);
            update();
            return;
        }
        MediaController best = null;
        for (MediaController c : sessions) {
            PlaybackState state = c.getPlaybackState();
            if (state == null || c.getMetadata() == null) continue;
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null && state.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        setController(best);
        update();
    }

    private void setController(MediaController c) {
        boolean same = controller != null && c != null && controller.getSessionToken().equals(c.getSessionToken());
        if (controller != null && !same) controller.unregisterCallback(callback);
        if (c != null && !same) c.registerCallback(callback, main);
        controller = c;
    }

    private void update() {
        MediaController c = controller;
        PlaybackState state = c == null ? null : c.getPlaybackState();
        MediaMetadata meta = c == null ? null : c.getMetadata();
        CharSequence name = meta == null ? null : meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (name == null && meta != null) name = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
        boolean has = state != null && !TextUtils.isEmpty(name) && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_PAUSED);
        if (!has) {
            boolean was = active;
            active = false;
            main.removeCallbacks(turn);
            if (button.hasFocus()) host.onNowPlayingDown();
            if (was || showingTrack) show(false);
            return;
        }
        boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
        buttonIcon.setImageResource(playing ? R.drawable.ic_hc_pause : R.drawable.ic_hc_play);
        button.setContentDescription(ctx.getString(playing ? R.string.home_center_pause : R.string.home_center_play));

        String app = c.getPackageName();
        Drawable appIcon = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            app = pm.getApplicationInfo(app, 0).loadLabel(pm).toString();
            appIcon = pm.getApplicationIcon(c.getPackageName());
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        label.setText(ctx.getString(R.string.home_center_now_playing) + " · " + app.toUpperCase());
        if (!TextUtils.equals(title.getText(), name)) title.setText(name);
        title.setSelected(true);   // keeps the marquee running without focus
        CharSequence by = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (by == null) by = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (by == null) by = meta.getText(MediaMetadata.METADATA_KEY_ALBUM);
        artist.setText(by == null ? "" : by);
        artist.setVisibility(by == null ? View.GONE : View.VISIBLE);

        // The button in the art's colour; the timeline in its complement.
        Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        Object key = art != null ? art : c.getPackageName();
        if (key != artKey) {
            artKey = key;
            int colour = AppAdapter.dominantColor(art != null ? new BitmapDrawable(ctx.getResources(), art) : appIcon);
            int play = playColour(colour);
            buttonFill.setColor(play);
            buttonIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    luminance(play) > 0.6f ? 0xFF1C1C1E : 0xFFFFFFFF));
            timeline.setColour(complement(play));
        }
        long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        timeline.set(state, duration);
        timeline.setVisibility(duration > 0 ? View.VISIBLE : View.INVISIBLE);

        if (!active) {
            active = true;
            if (resumed) schedule();
        }
    }

    private void toggle() {
        MediaController c = controller;
        PlaybackState state = c == null ? null : c.getPlaybackState();
        if (state == null) return;
        if (state.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    // ---- the ticker ---------------------------------------------------------------------------------

    private void schedule() {
        main.removeCallbacks(turn);
        if (!active || !resumed) return;
        main.postDelayed(turn, showingTrack ? TRACK_MS : CLOCK_MS);
    }

    private void turn() {
        if (!active || !resumed || button.hasFocus()) return;
        show(!showingTrack);
        schedule();
    }

    private void show(boolean track) {
        show(track, animations);
    }

    /** Clock or track: the one going scrolls up and fades, the other rises into its place. */
    private void show(boolean track, boolean animate) {
        showingTrack = track;
        View in = track ? view : clock, out = track ? clock : view;
        in.animate().cancel();
        out.animate().cancel();
        in.setVisibility(View.VISIBLE);
        if (track) {
            main.removeCallbacks(tick);
            main.post(tick);
        }
        if (!animate) {
            in.setAlpha(1f);
            in.setTranslationY(0f);
            out.setAlpha(0f);
            out.setTranslationY(0f);
            if (out == view) view.setVisibility(View.INVISIBLE);
            return;
        }
        float rise = dp(Motion.soft ? 12 : 18);
        int ms = Motion.ms(420, 700);
        out.animate().alpha(0f).translationY(-rise).setDuration(ms).setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    out.setTranslationY(0f);
                    if (out == view) view.setVisibility(View.INVISIBLE);
                }).start();
        in.setAlpha(0f);
        in.setTranslationY(rise);
        in.animate().alpha(1f).translationY(0f).setDuration(ms).setStartDelay(ms / 3)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    // ---- colours ------------------------------------------------------------------------------------

    /** The art's colour as a button: kept rich, never so dark it vanishes or so grey it reads as nothing. */
    private static int playColour(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        if (colour == 0xFF6E6E73 || hsv[1] < 0.12f) return 0xFFF4F1EA;   // grey art: a cream button
        hsv[1] = Math.min(Math.max(hsv[1], 0.45f), 0.85f);
        hsv[2] = Math.max(hsv[2], 0.62f);
        return Color.HSVToColor(hsv);
    }

    /** The colour opposite on the wheel, softened to a light tone a hairline still reads in. */
    private static int complement(int colour) {
        float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        if (hsv[1] < 0.12f) return 0xFFA6E8D6;   // a cream button: the seafoam accent
        hsv[0] = (hsv[0] + 180f) % 360f;
        hsv[1] = Math.min(Math.max(hsv[1] * 0.75f, 0.35f), 0.6f);
        hsv[2] = 0.92f;
        return Color.HSVToColor(hsv);
    }

    private static float luminance(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private TextView text(int sp, int colour, int font) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setTypeface(UiFont.get(ctx, font));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    /** The hairline timeline: a faint track, the played part in the complement, a small dot at the playhead. */
    private static final class Timeline extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), played = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private PlaybackState state;
        private long duration;

        Timeline(Context ctx) {
            super(ctx);
            track.setColor(0x2EFFFFFF);
            played.setColor(0xFFA6E8D6);
        }

        void setColour(int colour) {
            played.setColor(colour);
            invalidate();
        }

        void set(PlaybackState state, long duration) {
            this.state = state;
            this.duration = duration;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), line = Math.max(2f, h * 0.5f), y = h / 2f;
            rect.set(0, y - line / 2f, w, y + line / 2f);
            c.drawRoundRect(rect, line / 2f, line / 2f, track);
            if (state == null || duration <= 0) return;
            long position = state.getPosition();
            if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
                position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            }
            float share = Math.max(0f, Math.min(1f, position / (float) duration));
            rect.right = Math.max(line, w * share);
            c.drawRoundRect(rect, line / 2f, line / 2f, played);
            c.drawCircle(rect.right - line / 2f, y, h / 2f + 1.5f, played);
        }
    }
}
