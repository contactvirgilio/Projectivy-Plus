package io.lite.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A simple flat cog, drawn as vectors in a 100x100 box. Used for the Lite Home settings tile. */
final class CogIcon extends Drawable {

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final int color, hole;

    CogIcon(int color, int holeColor) { this.color = color; this.hole = holeColor; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.centerX() - size / 2f, b.centerY() - size / 2f);
        c.scale(size / 100f, size / 100f);

        p.setColor(color);
        for (int i = 0; i < 8; i++) {                 // teeth
            c.save();
            c.rotate(i * 45, 50, 50);
            r.set(41.5f, 5, 58.5f, 30);
            c.drawRoundRect(r, 3.5f, 3.5f, p);
            c.restore();
        }
        c.drawCircle(50, 50, 33, p);                  // body
        p.setColor(hole);
        c.drawCircle(50, 50, 13.5f, p);               // centre hole
        c.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
