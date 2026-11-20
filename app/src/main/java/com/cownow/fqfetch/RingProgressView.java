package com.cownow.fqfetch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 悬浮窗里的圆形进度环：轨道 + 进度弧 + 中心文字，运行时有呼吸的进度头圆点。
 * <p>
 * 纯 Canvas 自绘，不依赖宿主 App 的 Material 主题，因此在任何被 hook 的 App 里都能正常渲染。
 */
final class RingProgressView extends View {

    static final int MODE_IDLE = 0;
    static final int MODE_RUNNING = 1;
    static final int MODE_DONE = 2;
    static final int MODE_ERROR = 3;

    private static final int COLOR_TRACK = 0x33FFFFFF;
    private static final int COLOR_ARC = 0xFFD0BCFF;
    private static final int COLOR_TEXT = 0xFFF2EDF7;
    private static final int COLOR_DONE = 0xFFB8F2C2;
    private static final int COLOR_ERROR = 0xFFFFB4AB;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Rect bounds = new Rect();
    private final Path path = new Path();

    private float stroke;
    private float progress;
    private float pulse;
    private int mode = MODE_IDLE;
    private String label = "TXT";

    private ValueAnimator progressAnim;
    private ValueAnimator pulseAnim;

    RingProgressView(Context context) {
        super(context);
        stroke = dp(4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    // ---------------- 状态 ----------------

    void setMode(int mode) {
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        if (mode == MODE_RUNNING) {
            startPulse();
        } else {
            stopPulse();
        }
        invalidate();
    }

    void setLabel(String text) {
        String t = text == null ? "" : text;
        if (!t.equals(label)) {
            label = t;
            invalidate();
        }
    }

    void setProgress(float value, boolean animate) {
        float target = Math.max(0f, Math.min(1f, value));
        if (progressAnim != null) {
            progressAnim.cancel();
            progressAnim = null;
        }
        if (!animate) {
            progress = target;
            invalidate();
            return;
        }
        progressAnim = ValueAnimator.ofFloat(progress, target);
        progressAnim.setDuration(200L);
        progressAnim.setInterpolator(new DecelerateInterpolator());
        progressAnim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        progressAnim.start();
    }

    // ---------------- 动画 ----------------

    private void startPulse() {
        if (pulseAnim != null) {
            return;
        }
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(780L);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    private void stopPulse() {
        if (pulseAnim != null) {
            pulseAnim.cancel();
            pulseAnim = null;
        }
        pulse = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        if (progressAnim != null) {
            progressAnim.cancel();
            progressAnim = null;
        }
        super.onDetachedFromWindow();
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        // 环往里收 1.5 个线宽，避免描边贴着圆角底边
        float radius = Math.min(w, h) / 2f - stroke * 1.5f;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 轨道
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(COLOR_TRACK);
        paint.setAlpha(255);
        canvas.drawArc(oval, 0f, 360f, false, paint);

        // 进度弧 / 完成 / 错误
        if (mode == MODE_RUNNING || mode == MODE_DONE) {
            paint.setColor(mode == MODE_DONE ? COLOR_DONE : COLOR_ARC);
            float sweep = mode == MODE_DONE ? 360f : 360f * Math.max(progress, 0.015f);
            canvas.drawArc(oval, -90f, sweep, false, paint);
        } else if (mode == MODE_ERROR) {
            paint.setColor(COLOR_ERROR);
            canvas.drawArc(oval, -90f, 360f, false, paint);
        }

        // 运行中：进度头小圆点 + 呼吸
        if (mode == MODE_RUNNING) {
            double ang = Math.toRadians(-90d + 360d * progress);
            float dotX = (float) (cx + radius * Math.cos(ang));
            float dotY = (float) (cy + radius * Math.sin(ang));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_ARC);
            paint.setAlpha(255);
            canvas.drawCircle(dotX, dotY, stroke * (0.55f + 0.45f * pulse), paint);
        }

        // 中心文字 / 完成对勾
        if (mode == MODE_DONE) {
            drawTick(canvas, cx, cy, radius);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(mode == MODE_ERROR ? COLOR_ERROR : COLOR_TEXT);
        paint.setAlpha(255);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(fitTextSize(label, radius));
        // 按字形墨迹框居中（"!" 这类短字符用 FontMetrics 居中会明显偏上）
        bounds.setEmpty();
        paint.getTextBounds(label, 0, label.length(), bounds);
        float baseline = cy - (bounds.top + bounds.bottom) / 2f;
        canvas.drawText(label, cx, baseline, paint);
    }

    /** 对勾用路径画，避免不同设备上 ✓ 字形的字体回落导致偏心。 */
    private void drawTick(Canvas canvas, float cx, float cy, float radius) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke * 0.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(COLOR_DONE);
        paint.setAlpha(255);
        path.reset();
        path.moveTo(cx - radius * 0.34f, cy + radius * 0.02f);
        path.lineTo(cx - radius * 0.08f, cy + radius * 0.30f);
        path.lineTo(cx + radius * 0.40f, cy - radius * 0.28f);
        canvas.drawPath(path, paint);
        paint.setStrokeJoin(Paint.Join.MITER);
    }

    /** 1 个字符（!）大一点，TXT/百分比小一号，再按可用宽度自动缩。 */
    private float fitTextSize(String text, float radius) {
        float base = Math.min(getWidth(), getHeight());
        int n = text == null ? 0 : text.length();
        float size = n <= 1 ? base * 0.32f : n <= 3 ? base * 0.22f : base * 0.17f;
        paint.setTextSize(size);
        float maxWidth = radius * 1.5f;
        float width = paint.measureText(text);
        if (width > maxWidth && width > 0f) {
            size = size * (maxWidth / width);
        }
        return size;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
