package com.fable.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/** Эффекты на экране трека — в цвет обложки.
 *  Режимы: огоньки (всплывают), звёзды (падают вниз), кометы (летят слева направо). */
public class ParticleView extends View {

    public static final int MODE_SPARKS = 0;
    public static final int MODE_STARS = 1;
    public static final int MODE_COMETS = 2;

    private static class P {
        float x, y, r, speed, phase, drift, twinkle;
    }

    private final ArrayList<P> particles = new ArrayList<>();
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private int color = 0xFF8C6FFF;
    private int coreColor = 0xFFC5B7FF;
    private int mode = MODE_SPARKS;
    private boolean running;

    public ParticleView(Context c) { super(c); }
    public ParticleView(Context c, AttributeSet a) { super(c, a); }

    public void setColorTint(int c) {
        color = c;
        // ядро частицы — тот же цвет, осветлённый к белому
        coreColor = Color.rgb(
                Color.red(c) + (255 - Color.red(c)) * 6 / 10,
                Color.green(c) + (255 - Color.green(c)) * 6 / 10,
                Color.blue(c) + (255 - Color.blue(c)) * 6 / 10);
    }

    public void setMode(int m) {
        if (mode == m) return;
        mode = m;
        particles.clear();
        if (getWidth() > 0 && getHeight() > 0) fill(getWidth(), getHeight());
        if (running) postInvalidateOnAnimation();
    }

    public void setRunning(boolean run) {
        running = run;
        animate().alpha(run ? 1f : 0f).setDuration(900).start();
        if (run) postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (particles.isEmpty() && w > 0 && h > 0) fill(w, h);
    }

    private void fill(int w, int h) {
        int n = mode == MODE_COMETS ? 7 : 26;
        for (int i = 0; i < n; i++) {
            P p = new P();
            spawn(p, w, h, true);
            particles.add(p);
        }
    }

    private void spawn(P p, int w, int h, boolean anywhere) {
        float d = getResources().getDisplayMetrics().density;
        p.phase = rnd.nextFloat() * 6.2832f;
        p.twinkle = 0.6f + rnd.nextFloat() * 1.6f;
        switch (mode) {
            case MODE_STARS:
                p.x = rnd.nextFloat() * w;
                p.y = anywhere ? rnd.nextFloat() * h : -20 * d;
                p.r = (0.9f + rnd.nextFloat() * 1.9f) * d;
                p.speed = (0.25f + rnd.nextFloat() * 0.7f) * d;
                p.drift = (1.5f + rnd.nextFloat() * 5f) * d;
                break;
            case MODE_COMETS:
                p.x = anywhere ? rnd.nextFloat() * w : -(20 + rnd.nextFloat() * 120) * d;
                p.y = (0.05f + rnd.nextFloat() * 0.85f) * h;
                p.r = (1.3f + rnd.nextFloat() * 1.4f) * d;
                p.speed = (1.6f + rnd.nextFloat() * 2.4f) * d;
                p.drift = (rnd.nextFloat() - 0.35f) * 0.35f * d;
                break;
            default: // огоньки
                p.x = rnd.nextFloat() * w;
                p.y = anywhere ? rnd.nextFloat() * h : h + 30 * d;
                p.r = (1.1f + rnd.nextFloat() * 2.4f) * d;
                p.speed = (0.15f + rnd.nextFloat() * 0.45f) * d;
                p.drift = (2f + rnd.nextFloat() * 9f) * d;
                break;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (particles.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float t = (System.nanoTime() % 100000000000L) / 1e9f;
        for (P p : particles) {
            switch (mode) {
                case MODE_STARS: drawStar(canvas, p, w, h, t); break;
                case MODE_COMETS: drawComet(canvas, p, w, h, t); break;
                default: drawSpark(canvas, p, w, h, t); break;
            }
        }
        if (running || getAlpha() > 0.03f) postInvalidateOnAnimation();
    }

    private void drawSpark(Canvas c, P p, int w, int h, float t) {
        p.y -= p.speed;
        if (p.y < -40) spawn(p, w, h, false);
        float x = p.x + (float) Math.sin(t * 0.6f + p.phase) * p.drift;
        float a = 0.30f + 0.70f * (0.5f + 0.5f * (float) Math.sin(t * p.twinkle * 2f + p.phase));
        halo.setColor(color);
        halo.setAlpha((int) (34 * a));
        c.drawCircle(x, p.y, p.r * 3.4f, halo);
        core.setColor(coreColor);
        core.setAlpha((int) (165 * a));
        c.drawCircle(x, p.y, p.r, core);
    }

    private void drawStar(Canvas c, P p, int w, int h, float t) {
        p.y += p.speed;
        if (p.y > h + 40) spawn(p, w, h, false);
        float x = p.x + (float) Math.sin(t * 0.4f + p.phase) * p.drift;
        float a = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(t * p.twinkle * 2.4f + p.phase));
        halo.setColor(color);
        halo.setAlpha((int) (30 * a));
        c.drawCircle(x, p.y, p.r * 3f, halo);
        core.setColor(coreColor);
        core.setAlpha((int) (200 * a));
        c.drawCircle(x, p.y, p.r, core);
        // лучики-крестик, чтобы читалось как звезда
        core.setAlpha((int) (110 * a));
        float l = p.r * 2.6f;
        c.drawRect(x - l, p.y - p.r * 0.22f, x + l, p.y + p.r * 0.22f, core);
        c.drawRect(x - p.r * 0.22f, p.y - l, x + p.r * 0.22f, p.y + l, core);
    }

    private void drawComet(Canvas c, P p, int w, int h, float t) {
        p.x += p.speed;
        p.y += p.drift;
        float d = getResources().getDisplayMetrics().density;
        if (p.x > w + 80 * d) spawn(p, w, h, false);
        float a = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(t * p.twinkle + p.phase));
        // хвост — в цвет обложки, тает позади головы
        int segs = 10;
        float seg = p.r * 2.4f;
        for (int k = 1; k <= segs; k++) {
            float ta = a * (1f - k / (float) (segs + 1));
            halo.setColor(color);
            halo.setAlpha((int) (80 * ta));
            c.drawCircle(p.x - k * seg, p.y - k * p.drift * 2.2f,
                    p.r * (1f - k * 0.06f), halo);
        }
        // голова — белая, с мягким ореолом
        halo.setColor(0xFFFFFFFF);
        halo.setAlpha((int) (36 * a));
        c.drawCircle(p.x, p.y, p.r * 2.6f, halo);
        core.setColor(0xFFFFFFFF);
        core.setAlpha((int) (235 * a));
        c.drawCircle(p.x, p.y, p.r, core);
    }
}
