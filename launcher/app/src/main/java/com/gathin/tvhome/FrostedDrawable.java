package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A glass surface: the frost (Frost) under a tint, a specular light across the top, and a rim that is
 * brighter along the top edge than the bottom, as light catches the edge of a pane. Without frost it is the tint,
 * the light and the rim alone.
 */
final class FrostedDrawable extends Drawable {
    private final View owner;
    private final float radius, density;
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int frostAlpha = 160;
    private int shaderHeight = -1;

    FrostedDrawable(View owner, float radius, int tintColor, float density) {
        this.owner = owner;
        this.radius = radius;
        this.density = density;
        tint.setColor(tintColor);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.2f * density));
    }

    /** How much of the frost shows (0 to 255); the rest is whatever lies under the surface. */
    FrostedDrawable frostAlpha(int alpha) {
        frostAlpha = alpha;
        return this;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        rect.set(b);
        Frost.draw(c, rect, radius, owner, frostAlpha);
        c.drawRoundRect(rect, radius, radius, tint);
        if (b.height() != shaderHeight) {
            shaderHeight = b.height();
            light.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f,
                    0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x80FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(rect, radius, radius, light);
        float inset = rim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        c.drawRoundRect(rect, radius - inset, radius - inset, rim);
    }

    @Override
    public void setAlpha(int alpha) {
        tint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        tint.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
