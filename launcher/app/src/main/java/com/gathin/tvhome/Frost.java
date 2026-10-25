package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/**
 * Frosted glass (theme key frosted_glass): a small, heavily blurred copy of the wallpaper, made once when the
 * wallpaper is decoded. A glass surface draws its own patch of it, mapped to where it sits on the screen, under
 * its tint, so the panel reads as glass the wallpaper shows through. Nothing is blurred per frame: the copy is
 * about 100 by 56 pixels and is only scaled up and clipped.
 */
final class Frost {
    /** Wallpaper pixels per blurred pixel, before the blur passes. */
    private static final int SHRINK = 16;

    static volatile boolean enabled;
    private static volatile Bitmap blurred;
    private static volatile int screenW, screenH;
    private static BitmapShader shader;
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Matrix matrix = new Matrix();
    private static final int[] loc = new int[2];

    private Frost() {
    }

    /**
     * Makes the blurred copy from a freshly decoded (software) wallpaper, cropped to the screen's shape the way the
     * wallpaper view crops it. Called off the UI thread; drawing picks it up on the next frame.
     */
    static void make(Bitmap wallpaper, int screenWidth, int screenHeight) {
        if (!enabled || wallpaper == null || wallpaper.getConfig() == Bitmap.Config.HARDWARE
                || screenWidth <= 0 || screenHeight <= 0) return;
        try {
            int w = wallpaper.getWidth(), h = wallpaper.getHeight();
            float screenAspect = screenWidth / (float) screenHeight;
            Rect crop = new Rect(0, 0, w, h);
            if (w / (float) h > screenAspect) {
                int cw = Math.round(h * screenAspect);
                crop.left = (w - cw) / 2;
                crop.right = crop.left + cw;
            } else {
                int ch = Math.round(w / screenAspect);
                crop.top = (h - ch) / 2;
                crop.bottom = crop.top + ch;
            }
            int bw = Math.max(8, screenWidth / SHRINK), bh = Math.max(8, screenHeight / SHRINK);
            Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(wallpaper, crop, new Rect(0, 0, bw, bh),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] px = new int[bw * bh];
            small.getPixels(px, 0, bw, 0, 0, bw, bh);
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(px, bw, bh, 3, true);
                boxBlur(px, bw, bh, 3, false);
            }
            // Glass lifts what is behind it a little: 7% towards white.
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                px[i] = 0xFF000000 | ((r + (255 - r) * 7 / 100) << 16) | ((g + (255 - g) * 7 / 100) << 8)
                        | (b + (255 - b) * 7 / 100);
            }
            small.setPixels(px, 0, bw, 0, 0, bw, bh);
            synchronized (Frost.class) {
                blurred = small;
                shader = null;
                screenW = screenWidth;
                screenH = screenHeight;
            }
        } catch (OutOfMemoryError ignored) {
            // No frost; the glass keeps its plain tint.
        }
    }

    static boolean ready() {
        return enabled && blurred != null;
    }

    /**
     * Draws the frost under {@code rect} (in {@code owner}'s coordinates, as its canvas has them) with rounded
     * corners, at {@code alpha} (0 to 255).
     */
    static void draw(Canvas c, RectF rect, float radius, View owner, int alpha) {
        Bitmap b = blurred;
        if (!enabled || b == null) return;
        synchronized (Frost.class) {
            if (shader == null) shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // On screen, not in the window: a popup (the App library) is a window of its own.
            owner.getLocationOnScreen(loc);
            matrix.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
            matrix.postTranslate(-loc[0], -loc[1]);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            c.drawRoundRect(rect, radius, radius, paint);
        }
    }

    /** Redraws every view under {@code v}, so glass surfaces pick up a new frost. */
    static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }

    /** One pass of a box blur of the given radius, along rows or columns, in place. */
    private static void boxBlur(int[] px, int w, int h, int radius, boolean rows) {
        int lines = rows ? h : w, length = rows ? w : h;
        int[] r = new int[length], g = new int[length], b = new int[length];
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < length; i++) {
                int p = px[rows ? line * w + i : i * w + line];
                r[i] = (p >> 16) & 0xFF;
                g[i] = (p >> 8) & 0xFF;
                b[i] = p & 0xFF;
            }
            int sr = 0, sg = 0, sb = 0, n = 2 * radius + 1;
            for (int k = -radius; k <= radius; k++) {
                int j = Math.max(0, Math.min(length - 1, k));
                sr += r[j];
                sg += g[j];
                sb += b[j];
            }
            for (int i = 0; i < length; i++) {
                px[rows ? line * w + i : i * w + line] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int out = Math.max(0, Math.min(length - 1, i - radius));
                int in = Math.max(0, Math.min(length - 1, i + radius + 1));
                sr += r[in] - r[out];
                sg += g[in] - g[out];
                sb += b[in] - b[out];
            }
        }
    }
}
