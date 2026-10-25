package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * volume_hud: the remote's volume keys, handled by HomeKeyService, show a slim glass capsule at the top of the
 * screen instead of the system's volume bar: a speaker, a level bar filled with Home's wash colour (HomeWash), and
 * the number. It is an accessibility overlay, so it shows over any app without another permission. A held key
 * repeats at about Android's rate; the capsule fades out a moment after the last press.
 */
final class VolumeHud {
    private static final long HIDE_AFTER_MS = 1800, REPEAT_DELAY_MS = 450, REPEAT_EVERY_MS = 70;

    private final Context ctx;
    private final WindowManager wm;
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float d;
    private HudView view;
    private boolean attached;
    private Runnable repeat;
    private final Runnable hide = this::hide;

    VolumeHud(Context ctx) {
        this.ctx = ctx;
        wm = ctx.getSystemService(WindowManager.class);
        audio = ctx.getSystemService(AudioManager.class);
        d = ctx.getResources().getDisplayMetrics().density;
    }

    /** Takes Volume Up, Down and Mute (true); leaves every other key alone (false). */
    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        int direction = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER
                : code == KeyEvent.KEYCODE_VOLUME_MUTE ? AudioManager.ADJUST_TOGGLE_MUTE : 0;
        if (direction == 0 || audio == null || wm == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                stopRepeat();
                step(direction);
                if (direction != AudioManager.ADJUST_TOGGLE_MUTE) startRepeat(direction);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            stopRepeat();
        }
        return true;
    }

    /** Removes the capsule and stops a repeat; the service is going away. */
    void release() {
        stopRepeat();
        handler.removeCallbacks(hide);
        if (attached && view != null) {
            try {
                wm.removeViewImmediate(view);
            } catch (IllegalArgumentException ignored) {
            }
        }
        attached = false;
    }

    private void step(int direction) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        show();
    }

    private void startRepeat(int direction) {
        repeat = new Runnable() {
            @Override
            public void run() {
                step(direction);
                handler.postDelayed(this, REPEAT_EVERY_MS);
            }
        };
        handler.postDelayed(repeat, REPEAT_DELAY_MS);
    }

    private void stopRepeat() {
        if (repeat != null) handler.removeCallbacks(repeat);
        repeat = null;
    }

    private void show() {
        if (view == null) view = new HudView(ctx);
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        int blush = HomeWash.get(ctx);
        view.set(muted ? 0f : Math.min(1f, level / (float) max), muted, blush != 0 ? blush : 0xFFA6E8D6);
        if (!attached) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(380 * d), Math.round(60 * d),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(34 * d);
            try {
                wm.addView(view, lp);
                attached = true;
                view.setAlpha(0f);
                view.setTranslationY(-12 * d);
            } catch (RuntimeException unavailable) {
                return;   // the service is not connected yet; the press still changed the volume
            }
        }
        view.animate().cancel();
        view.animate().alpha(1f).translationY(0f).setDuration(160).start();
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, HIDE_AFTER_MS);
    }

    private void hide() {
        if (!attached || view == null) return;
        view.animate().alpha(0f).translationY(-8 * d).setDuration(260).withEndAction(() -> {
            if (!attached) return;
            try {
                wm.removeView(view);
            } catch (IllegalArgumentException ignored) {
            }
            attached = false;
        }).start();
    }

    /** The capsule: glass washed with the blush from the left, the speaker, the bar and the number. */
    private static final class HudView extends View {
        private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable speaker;
        private final RectF rect = new RectF();
        private final float d;
        private float share;
        private boolean muted;
        private int blush;

        HudView(Context ctx) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            speaker = ctx.getDrawable(R.drawable.ic_hc_sound);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, 1.2f * d));
            track.setColor(0x33FFFFFF);
            number.setColor(0xFFF4F1EA);
            number.setTypeface(UiFont.get(ctx, R.string.font_medium));
            number.setTextSize(17 * ctx.getResources().getDisplayMetrics().scaledDensity);
            number.setTextAlign(Paint.Align.RIGHT);
        }

        void set(float share, boolean muted, int blush) {
            this.share = share;
            this.muted = muted;
            if (blush != this.blush || glass.getShader() == null) {
                this.blush = blush;
                glass.setShader(null);
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            if (glass.getShader() == null) {
                glass.setShader(new LinearGradient(0, 0, w, 0, (blush & 0x00FFFFFF) | 0x8C000000, 0xE61C1C1F,
                        Shader.TileMode.CLAMP));
                rim.setShader(new LinearGradient(0, 0, 0, h, 0x80FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
            }
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, glass);
            rect.inset(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f);
            c.drawRoundRect(rect, r, r, rim);
            int icon = Math.round(24 * d);
            int left = Math.round(r * 0.75f), top = Math.round((h - icon) / 2f);
            speaker.setBounds(left, top, left + icon, top + icon);
            speaker.setTint(0xFFF4F1EA);
            speaker.setAlpha(muted ? 110 : 255);
            speaker.draw(c);
            float barLeft = left + icon + 16 * d, barRight = w - r * 0.75f - 40 * d, barH = 6 * d;
            rect.set(barLeft, (h - barH) / 2f, barRight, (h + barH) / 2f);
            c.drawRoundRect(rect, barH / 2f, barH / 2f, track);
            if (share > 0f) {
                rect.right = barLeft + Math.max(barH, (barRight - barLeft) * share);
                fill.setColor(0xFF000000 | blush);
                c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
            }
            c.drawText(muted ? "—" : String.valueOf(Math.round(share * 100)), w - r * 0.75f,
                    h / 2f - (number.descent() + number.ascent()) / 2f, number);
        }
    }
}
