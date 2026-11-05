package io.lite.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The Android robot (CC BY 3.0, Google), drawn as vectors in a 100x100 box. */
final class AndroidRobot extends Drawable {

    static final int GREEN = 0xFF3DDC84;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    final Path body = new Path();
    final int eye;

    AndroidRobot(int eyeColor) { eye = eyeColor; }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        c.save();
        c.translate(b.centerX() - size / 2f, b.centerY() - size / 2f);
        c.scale(size / 100f, size / 100f);

        p.setColor(GREEN);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3.2f);
        c.drawLine(37, 18, 30, 7, p);      // antennae
        c.drawLine(63, 18, 70, 7, p);

        p.setStyle(Paint.Style.FILL);
        r.set(23, 13, 77, 67);
        c.drawArc(r, 180, 180, true, p);   // head

        body.reset();                      // body: square top, rounded bottom
        r.set(23, 44, 77, 82);
        body.addRoundRect(r, new float[]{2, 2, 2, 2, 9, 9, 9, 9}, Path.Direction.CW);
        c.drawPath(body, p);

        r.set(11, 45, 19.5f, 71); c.drawRoundRect(r, 4.25f, 4.25f, p);   // arms
        r.set(80.5f, 45, 89, 71); c.drawRoundRect(r, 4.25f, 4.25f, p);
        r.set(33, 76, 41.5f, 95); c.drawRoundRect(r, 4.25f, 4.25f, p);   // legs
        r.set(58.5f, 76, 67, 95); c.drawRoundRect(r, 4.25f, 4.25f, p);

        p.setColor(eye);
        c.drawCircle(39, 30, 3.3f, p);     // eyes
        c.drawCircle(61, 30, 3.3f, p);
        c.restore();
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
