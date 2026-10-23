package com.lucas.trippermeteor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Desenha a manobra grande na tela do app (mesma ideia das setas do pod). */
public class ArrowView extends View {

    public static final int MODE_ARROW = 0, MODE_RADAR = 1, MODE_LIMIT = 2;

    private int icon = -1, exit = 0, mode = MODE_ARROW, limit = 0;
    private int color = 0xFFF5A524, bg = 0x00000000;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public ArrowView(Context c) { super(c); }

    public void setIcon(int icon, int exit) {
        this.icon = icon; this.exit = exit; this.mode = MODE_ARROW;
        invalidate();
    }

    public void setRadar() { this.mode = MODE_RADAR; invalidate(); }

    public void setLimit(int kmh) { this.mode = MODE_LIMIT; this.limit = kmh; invalidate(); }

    public void setColor(int c) { color = c; invalidate(); }

    public void setBg(int c) { bg = c; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        c.save();
        c.translate((w - s) / 2, (h - s) / 2);
        if (bg != 0) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            c.drawRoundRect(new RectF(0, 0, s, s), s * 0.18f, s * 0.18f, p);
        }
        if (mode == MODE_LIMIT) drawLimit(c, s);
        else if (mode == MODE_RADAR) drawRadar(c, s);
        else drawIcon(c, s);
        c.restore();
    }

    private void stroke(float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private void fill() { p.setStyle(Paint.Style.FILL); p.setColor(color); }

    /** Seta: haste sobe do meio-baixo e vira "deg" graus (0 = reto, -90 = esquerda, 90 = direita). */
    private void turn(Canvas c, float s, float deg) {
        float sw = s * 0.11f;
        float x0 = s * 0.5f, y0 = s * 0.88f, y1 = s * 0.52f;
        double a = Math.toRadians(deg);
        float len = s * 0.30f;
        float x2 = (float) (x0 + Math.sin(a) * len), y2 = (float) (y1 - Math.cos(a) * len);
        stroke(sw);
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x0, y1);
        path.lineTo(x2, y2);
        c.drawPath(path, p);
        head(c, x2, y2, deg, s * 0.17f);
    }

    private void head(Canvas c, float x, float y, float deg, float size) {
        fill();
        double a = Math.toRadians(deg);
        float fx = (float) Math.sin(a), fy = (float) -Math.cos(a); // frente
        float lx = -fy, ly = fx;                                     // lado
        path.reset();
        path.moveTo(x + fx * size * 0.9f, y + fy * size * 0.9f);
        path.lineTo(x + lx * size - fx * size * 0.2f, y + ly * size - fy * size * 0.2f);
        path.lineTo(x - lx * size - fx * size * 0.2f, y - ly * size - fy * size * 0.2f);
        path.close();
        c.drawPath(path, p);
    }

    private void drawIcon(Canvas c, float s) {
        int i = icon;
        if (i < 0) { text(c, s, "?"); return; }
        if (i == 28) { recalc(c, s); return; }
        if (i == 0 || i == 1 || i == 2) { flag(c, s, i); return; }
        if (i == 26 || i == 61) { uturn(c, s); return; }
        if (i == 10 || (i >= 11 && i <= 19)) { roundabout(c, s, i == 10 ? exit : i - 10, true); return; }
        if (i == 49 || (i >= 50 && i <= 58)) { roundabout(c, s, i == 49 ? exit : i - 49, false); return; }
        if (i == 62 || i == 63) { text(c, s, i == 62 ? "⛴" : "🚆"); return; }
        if (i == 66 || i == 68) { text(c, s, "!"); return; }
        float deg;
        switch (i) {
            case 20: case 30: deg = -90; break;
            case 21: case 29: deg = 90; break;
            case 22: case 34: deg = -135; break;
            case 23: case 33: deg = 135; break;
            case 24: case 32: case 43: case 46: case 6: case 8: case 48: case 4: deg = -45; break;
            case 25: case 31: case 44: case 45: case 5: case 7: case 47: case 3: deg = 45; break;
            default: deg = 0;
        }
        turn(c, s, deg);
    }

    private void uturn(Canvas c, float s) {
        float sw = s * 0.11f;
        stroke(sw);
        float right = s * 0.64f, left = s * 0.36f, top = s * 0.30f;
        path.reset();
        path.moveTo(right, s * 0.88f);
        path.lineTo(right, s * 0.45f);
        path.arcTo(new RectF(left, top, right, top + (right - left)), 0, -180);
        path.lineTo(left, s * 0.60f);
        c.drawPath(path, p);
        head(c, left, s * 0.62f, 180, s * 0.16f);
    }

    /** Rotatória: no Brasil roda anti-horário; saída 1 = direita, 2 = reto, 3 = esquerda. */
    private void roundabout(Canvas c, float s, int ex, boolean clockwise) {
        float cx = s * 0.5f, cy = s * 0.48f, r = s * 0.17f, sw = s * 0.09f;
        stroke(sw);
        c.drawCircle(cx, cy, r, p);
        c.drawLine(cx, s * 0.92f, cx, cy + r, p);
        int e = ex <= 0 ? 2 : ex;
        float deg = clockwise ? -90 + (e - 1) * 90 : 90 - (e - 1) * 90;
        double a = Math.toRadians(deg);
        float sx = (float) (cx + Math.sin(a) * r), sy = (float) (cy - Math.cos(a) * r);
        float ex2 = (float) (cx + Math.sin(a) * (r + s * 0.22f)), ey2 = (float) (cy - Math.cos(a) * (r + s * 0.22f));
        c.drawLine(sx, sy, ex2, ey2, p);
        head(c, ex2, ey2, deg, s * 0.14f);
        if (ex > 0) {
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(s * 0.16f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(String.valueOf(ex), cx, cy + s * 0.055f, p);
        }
    }

    private void flag(Canvas c, float s, int side) {
        float x = side == 1 ? s * 0.30f : side == 2 ? s * 0.42f : s * 0.36f;
        stroke(s * 0.06f);
        c.drawLine(x, s * 0.18f, x, s * 0.86f, p);
        fill();
        path.reset();
        path.moveTo(x, s * 0.18f);
        path.lineTo(x + s * 0.40f, s * 0.30f);
        path.lineTo(x, s * 0.44f);
        path.close();
        c.drawPath(path, p);
        if (side == 1 || side == 2) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(s * 0.14f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(side == 1 ? "◀" : "▶", side == 1 ? s * 0.78f : s * 0.80f, s * 0.80f, p);
        }
    }

    private void recalc(Canvas c, float s) {
        stroke(s * 0.08f);
        RectF o = new RectF(s * 0.24f, s * 0.24f, s * 0.76f, s * 0.76f);
        c.drawArc(o, -60, 150, false, p);
        c.drawArc(o, 120, 150, false, p);
        head(c, (float) (s * 0.5 + Math.cos(Math.toRadians(90)) * s * 0.26), s * 0.76f, -90, s * 0.10f);
        head(c, s * 0.5f, s * 0.24f, 90, s * 0.10f);
    }

    private void drawRadar(Canvas c, float s) {
        fill();
        RectF body = new RectF(s * 0.18f, s * 0.34f, s * 0.70f, s * 0.70f);
        c.drawRoundRect(body, s * 0.06f, s * 0.06f, p);
        path.reset();
        path.moveTo(s * 0.70f, s * 0.42f);
        path.lineTo(s * 0.84f, s * 0.36f);
        path.lineTo(s * 0.84f, s * 0.68f);
        path.lineTo(s * 0.70f, s * 0.62f);
        path.close();
        c.drawPath(path, p);
        p.setColor(0xFF0F1115);
        c.drawCircle(s * 0.44f, s * 0.52f, s * 0.10f, p);
        p.setColor(color);
        c.drawCircle(s * 0.44f, s * 0.52f, s * 0.05f, p);
        stroke(s * 0.05f);
        c.drawLine(s * 0.44f, s * 0.70f, s * 0.44f, s * 0.86f, p);
        c.drawLine(s * 0.30f, s * 0.86f, s * 0.58f, s * 0.86f, p);
        // "flash"
        c.drawLine(s * 0.22f, s * 0.24f, s * 0.28f, s * 0.30f, p);
        c.drawLine(s * 0.44f, s * 0.16f, s * 0.44f, s * 0.26f, p);
        c.drawLine(s * 0.66f, s * 0.24f, s * 0.60f, s * 0.30f, p);
    }

    /** Placa de velocidade máxima (círculo vermelho). */
    private void drawLimit(Canvas c, float s) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(s / 2, s / 2, s * 0.46f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.09f);
        p.setColor(0xFFD32F2F);
        c.drawCircle(s / 2, s / 2, s * 0.415f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF111111);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        String t = limit > 0 ? String.valueOf(limit) : "?";
        p.setTextSize(s * (t.length() >= 3 ? 0.30f : 0.38f));
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, s / 2, s / 2 - (fm.ascent + fm.descent) / 2, p);
    }

    private void text(Canvas c, float s, String t) {
        fill();
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(s * 0.5f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(t, s / 2, s / 2 - (fm.ascent + fm.descent) / 2, p);
    }
}
