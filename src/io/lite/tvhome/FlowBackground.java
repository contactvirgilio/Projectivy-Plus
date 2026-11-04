package io.lite.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Slowly flowing gradient: a rotating two-colour base with two soft colour blobs drifting over it.
 * Drawn into a tiny 192x108 bitmap and scaled up, so it costs almost nothing even on weak TV sticks.
 */
public class FlowBackground extends View {

    // name, base1, base2, blob1, blob2
    static final String[] NAMES = {"Aurora", "Sunset", "Ocean", "Forest", "Ember", "Lavender", "Midnight"};
    static final int[][] PALETTES = {
            {0xFF0B1026, 0xFF1B2450, 0xFF19C3A6, 0xFF7B4DFF},
            {0xFF1A0B1E, 0xFF3A1230, 0xFFFF7A45, 0xFFE0407B},
            {0xFF04131F, 0xFF0A2A44, 0xFF1FA2FF, 0xFF12D8C4},
            {0xFF07140D, 0xFF10291A, 0xFF3DBE6A, 0xFFB5D334},
            {0xFF160707, 0xFF2B0E08, 0xFFFF5A1F, 0xFFFFB020},
            {0xFF120E1F, 0xFF241B3D, 0xFFB490FF, 0xFFFF8FC7},
            {0xFF07080B, 0xFF12151C, 0xFF3A4458, 0xFF5B6478},
    };
    /** Palette index meaning "drift through all palettes". */
    static final int CYCLE = PALETTES.length;

    static final String[] SPEED_NAMES = {"Still", "Slow", "Normal", "Fast"};
    static final float[] SPEEDS = {0f, 0.5f, 1f, 2f};
    static final String[] BRIGHT_NAMES = {"Dim", "Normal", "Bright"};
    static final int[] SCRIM = {0x99, 0x55, 0x22};

    static final int W = 192, H = 108;
    static final long FRAME_MS = 50; // 20 fps is plenty for something this slow

    final Bitmap small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    final Canvas sc = new Canvas(small);
    final Paint fill = new Paint();
    final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Rect dst = new Rect();
    final int[] pal = new int[4];

    int palette = 0, speed = 1, bright = 1;
    float t;              // animation time, seconds
    long last;
    boolean running;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    public FlowBackground(Context c) {
        super(c);
        t = (SystemClock.elapsedRealtime() % 600_000) / 1000f; // don't always start at the same frame
    }

    void configure(int palette, int speed, int bright) {
        this.palette = Math.max(0, Math.min(CYCLE, palette));
        this.speed = Math.max(0, Math.min(SPEEDS.length - 1, speed));
        this.bright = Math.max(0, Math.min(SCRIM.length - 1, bright));
        if (running) start(); else invalidate();
    }

    void start() {
        running = SPEEDS[speed] > 0;
        removeCallbacks(tick);
        last = SystemClock.elapsedRealtime();
        if (running) post(tick); else invalidate();
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    static int mix(int a, int b, float f) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    void currentPalette() {
        if (palette < CYCLE) {
            System.arraycopy(PALETTES[palette], 0, pal, 0, 4);
            return;
        }
        // Cycle: ~45 s per palette, with a smooth cross-fade between neighbours.
        float pos = (t / 45f) % PALETTES.length;
        int i = (int) pos, j = (i + 1) % PALETTES.length;
        float f = pos - i;
        f = f * f * (3 - 2 * f);
        for (int k = 0; k < 4; k++) pal[k] = mix(PALETTES[i][k], PALETTES[j][k], f);
    }

    /** Bright colour taken from the current theme, for focus highlights. */
    int accent() {
        currentPalette();
        return mix(pal[2], Color.WHITE, 0.2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.elapsedRealtime();
        if (running) t += (now - last) / 1000f * SPEEDS[speed];
        last = now;
        currentPalette();

        // Base: two-colour linear gradient whose angle slowly turns.
        double a = t * 0.04;
        float dx = (float) Math.cos(a) * W * 0.6f, dy = (float) Math.sin(a) * W * 0.6f;
        fill.setShader(new LinearGradient(W / 2f - dx, H / 2f - dy, W / 2f + dx, H / 2f + dy,
                pal[0], pal[1], Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, W, H, fill);

        // Two soft blobs drifting on slow, never-quite-repeating paths.
        float x1 = W * (0.5f + 0.38f * (float) Math.sin(t * 0.13)), y1 = H * (0.5f + 0.42f * (float) Math.cos(t * 0.11));
        float x2 = W * (0.5f + 0.40f * (float) Math.cos(t * 0.09 + 1)), y2 = H * (0.5f + 0.45f * (float) Math.sin(t * 0.15 + 2));
        fill.setShader(new RadialGradient(x1, y1, W * 0.62f, alpha(pal[2], 0xA0), alpha(pal[2], 0), Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, W, H, fill);
        fill.setShader(new RadialGradient(x2, y2, W * 0.55f, alpha(pal[3], 0x80), alpha(pal[3], 0), Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, W, H, fill);
        fill.setShader(null);

        // Darken for readability (clock, captions).
        sc.drawColor(SCRIM[bright] << 24);

        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(small, null, dst, blit);
    }
}
