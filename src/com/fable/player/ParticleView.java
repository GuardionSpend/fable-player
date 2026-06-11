package com.fable.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/** Мягко светящиеся «огоньки» в цвет обложки: медленно всплывают и мерцают. */
public class ParticleView extends View {

    private static class P {
        float x, y, r, speed, phase, drift, twinkle;
    }

    private static final int COUNT = 26;

    private final ArrayList<P> particles = new ArrayList<>();
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private int color = 0xFF8C6FFF;
    private int coreColor = 0xFFC5B7FF;
    private boolean running;

    public ParticleView(Context c) { super(c); }
    public ParticleView(Context c, AttributeSet a) { super(c, a); }

    public void setColorTint(int c) {
        color = c;
        // ядро огонька — тот же цвет, осветлённый к белому
        coreColor = Color.rgb(
                Color.red(c) + (255 - Color.red(c)) * 6 / 10,
                Color.green(c) + (255 - Color.green(c)) * 6 / 10,
                Color.blue(c) + (255 - Color.blue(c)) * 6 / 10);
    }

    public void setRunning(boolean run) {
        running = run;
        animate().alpha(run ? 1f : 0f).setDuration(900).start();
        if (run) postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (particles.isEmpty() && w > 0 && h > 0) {
            for (int i = 0; i < COUNT; i++) {
                P p = new P();
                spawn(p, w, h, true);
                particles.add(p);
            }
        }
    }

    private void spawn(P p, int w, int h, boolean anywhere) {
        float d = getResources().getDisplayMetrics().density;
        p.x = rnd.nextFloat() * w;
        p.y = anywhere ? rnd.nextFloat() * h : h + 30 * d;
        p.r = (1.1f + rnd.nextFloat() * 2.4f) * d;
        p.speed = (0.15f + rnd.nextFloat() * 0.45f) * d;
        p.phase = rnd.nextFloat() * 6.2832f;
        p.drift = (2f + rnd.nextFloat() * 9f) * d;
        p.twinkle = 0.6f + rnd.nextFloat() * 1.6f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (particles.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float t = (System.nanoTime() % 100000000000L) / 1e9f;
        for (P p : particles) {
            p.y -= p.speed;
            if (p.y < -40) spawn(p, w, h, false);
            float x = p.x + (float) Math.sin(t * 0.6f + p.phase) * p.drift;
            float a = 0.30f + 0.70f * (0.5f + 0.5f * (float) Math.sin(t * p.twinkle * 2f + p.phase));
            halo.setColor(color);
            halo.setAlpha((int) (34 * a));
            canvas.drawCircle(x, p.y, p.r * 3.4f, halo);
            core.setColor(coreColor);
            core.setAlpha((int) (165 * a));
            canvas.drawCircle(x, p.y, p.r, core);
        }
        if (running || getAlpha() > 0.03f) postInvalidateOnAnimation();
    }
}
