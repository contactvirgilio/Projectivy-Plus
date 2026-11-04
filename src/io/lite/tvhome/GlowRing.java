package io.lite.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Focus ring drawn entirely OUTSIDE the tile: a solid ring with a small gap from the tile edge,
 * then a soft glow fading outwards. Built from concentric strokes, so it needs no blur filter and
 * stays smooth on hardware-accelerated TV GPUs. {@link #setPulse} breathes the glow.
 */
final class GlowRing extends Drawable {

    /** How far the drawable extends past the tile on every side. */
    final float margin;
    final float radius, gap, stroke, step;
    final int layers = 12;
    static final float MAX_REACH = 1.15f;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    int color = 0xFFFFFFFF;
    float pulse = 1f; // 0..1

    GlowRing(float density, float tileRadiusDp) {
        radius = tileRadiusDp * density;
        gap = 3f * density;
        stroke = 4f * density;
        step = 2f * density;
        margin = gap + stroke + layers * step * MAX_REACH + step * 2 + 2 * density;
        p.setStyle(Paint.Style.STROKE);
    }

    void setColor(int c) { color = c; invalidateSelf(); }

    void setPulse(float v) { pulse = v; invalidateSelf(); }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float l = b.left + margin, t = b.top + margin, rt = b.right - margin, bt = b.bottom - margin;
        int rgb = color & 0x00FFFFFF;

        // Soft glow: widest and faintest outside, reaching further at the top of each pulse.
        float reach = 0.6f + (MAX_REACH - 0.6f) * pulse;
        for (int i = layers; i >= 1; i--) {
            float f = 1f - (i - 0.5f) / (layers + 1);         // 1 near the ring, 0 far out
            int a = (int) (95 * Math.pow(f, 1.7) * (0.4f + 0.6f * pulse));
            if (a <= 0) continue;
            float out = gap + stroke + i * step * reach;
            p.setStrokeWidth(step * reach * 2.2f);             // overlapping strokes = no banding
            p.setColor((a << 24) | rgb);
            r.set(l - out, t - out, rt + out, bt + out);
            c.drawRoundRect(r, radius + out, radius + out, p);
        }

        // Solid ring, never touching the tile itself.
        float mid = gap + stroke / 2f;
        p.setStrokeWidth(stroke);
        p.setColor(0xFF000000 | rgb);
        r.set(l - mid, t - mid, rt + mid, bt + mid);
        c.drawRoundRect(r, radius + mid, radius + mid, p);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
