package com.cownow.fqfetch;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;
import android.widget.FrameLayout;

/**
 * 阅读页悬浮窗：直径 68dp 的圆形进度环，替代原先塞在 decorView 里的 Button + ProgressBar。
 * <ul>
 *   <li>单击：开始导出（下载中单击只提示）</li>
 *   <li>拖动：自由移动，松手吸附到最近的左右边缘，位置按比例存进宿主 prefs</li>
 *   <li>长按：隐藏（重新进入阅读页恢复）</li>
 * </ul>
 * 全部用框架 view + 自绘，不依赖宿主主题，也不需要悬浮窗权限。
 */
final class FloatingPanel {

    private static final int SIZE_DP = 68;
    private static final int MARGIN_DP = 10;
    private static final long LONG_PRESS_MS = 480L;
    private static final long DONE_HOLD_MS = 2600L;
    private static final long ERROR_HOLD_MS = 3200L;

    private static final int BG_COLOR = 0xF21B1B1F;
    private static final int BORDER_COLOR = 0x40FFFFFF;

    private final Activity activity;
    private final ClassLoader loader;
    private final FrameLayout host;
    private final TouchFrame container;
    private final RingProgressView ring;
    private final Runnable onHide;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable longPressTask = this::handleLongPress;
    private final Runnable resetTask = this::toIdle;
    private final int touchSlop;

    private final int sizePx;
    private final int marginPx;

    private float downRawX;
    private float downRawY;
    private float downX;
    private float downY;
    private boolean dragging;
    private boolean longPressed;
    private volatile boolean destroyed;

    private ValueAnimator snapAnim;

    private FloatingPanel(Activity activity, ClassLoader loader, FrameLayout host, Runnable onHide) {
        this.activity = activity;
        this.loader = loader;
        this.host = host;
        this.onHide = onHide;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.sizePx = dp(SIZE_DP);
        this.marginPx = dp(MARGIN_DP);

        ring = new RingProgressView(activity);
        ring.setLabel("TXT");

        container = new TouchFrame(activity);
        container.setBackground(buildBackground());
        container.setElevation(dp(10));
        container.setForeground(buildRipple());
        container.addView(ring, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        container.setOnTouchListener(this::onTouch);
    }

    /** 自绘触摸容器：单击走 performClick，保证无障碍语义（lint ClickableViewAccessibility）。 */
    private final class TouchFrame extends FrameLayout {

        TouchFrame(Context context) {
            super(context);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            FqFetchTask.start(activity, loader);
            return true;
        }
    }

    static FloatingPanel attach(Activity activity, ClassLoader loader, Runnable onHide) {
        if (activity == null || activity.getWindow() == null) {
            return null;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            L.file("decorView 不是 FrameLayout，跳过悬浮窗");
            return null;
        }
        FloatingPanel panel = new FloatingPanel(activity, loader, (FrameLayout) decor, onHide);
        panel.show();
        return panel;
    }

    private void show() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(sizePx, sizePx);
        host.addView(container, lp);
        container.setAlpha(Prefs.get().buttonAlpha);
        container.post(this::restorePosition);
        host.addOnLayoutChangeListener(layoutListener);
        L.file("悬浮窗已注入 activity=" + activity.getClass().getName());
    }

    private final View.OnLayoutChangeListener layoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                    clampIntoBounds();
                }
            };

    void destroy() {
        if (!onUiThread()) {
            main.post(this::destroy);
            return;
        }
        destroyed = true;
        main.removeCallbacks(longPressTask);
        main.removeCallbacks(resetTask);
        cancelSnap();
        ring.setMode(RingProgressView.MODE_IDLE);
        try {
            host.removeOnLayoutChangeListener(layoutListener);
            host.removeView(container);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 对外状态（任意线程可调） ----------------

    /**
     * 下载进度来自 {@code FqFetchTask} 的工作线程，而 ValueAnimator 只能在有 Looper 的线程启动，
     * 所以这里统一转主线程再碰视图。
     */
    private boolean onUiThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    /** 面板已随 Activity 销毁：丢弃排队中的进度更新，别再碰视图。 */
    private boolean gone() {
        return destroyed;
    }

    /** 下载中：done/total 驱动圆环与百分比。 */
    void showRunning(final int done, final int total) {
        if (!onUiThread()) {
            main.post(() -> showRunning(done, total));
            return;
        }
        if (gone()) {
            return;
        }
        main.removeCallbacks(resetTask);
        ring.setMode(RingProgressView.MODE_RUNNING);
        float p = total > 0 ? (float) done / (float) total : 0f;
        ring.setProgress(p, true);
        ring.setLabel(total > 0 ? Math.round(p * 100f) + "%" : "…");
    }

    /** 完成：全绿对勾；有失败章节则转成红圈感叹号。停留后回到 TXT。 */
    void showDone(final int ok, final int fail) {
        if (!onUiThread()) {
            main.post(() -> showDone(ok, fail));
            return;
        }
        if (gone()) {
            return;
        }
        main.removeCallbacks(resetTask);
        ring.setProgress(1f, true);
        if (fail > 0) {
            ring.setLabel("!");
            ring.setMode(RingProgressView.MODE_ERROR);
        } else {
            ring.setLabel("✓");
            ring.setMode(RingProgressView.MODE_DONE);
        }
        main.postDelayed(resetTask, DONE_HOLD_MS);
    }

    /** 出错：红圈感叹号。 */
    void showError() {
        if (!onUiThread()) {
            main.post(this::showError);
            return;
        }
        if (gone()) {
            return;
        }
        main.removeCallbacks(resetTask);
        ring.setMode(RingProgressView.MODE_ERROR);
        ring.setLabel("!");
        main.postDelayed(resetTask, ERROR_HOLD_MS);
    }

    void toIdle() {
        if (!onUiThread()) {
            main.post(this::toIdle);
            return;
        }
        if (gone()) {
            return;
        }
        main.removeCallbacks(resetTask);
        ring.setMode(RingProgressView.MODE_IDLE);
        ring.setProgress(0f, false);
        ring.setLabel("TXT");
    }

    // ---------------- 交互 ----------------

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = container.getX();
                downY = container.getY();
                dragging = false;
                longPressed = false;
                cancelSnap();
                container.setPressed(true);
                main.postDelayed(longPressTask, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    container.setPressed(false);
                    main.removeCallbacks(longPressTask);
                }
                if (dragging) {
                    container.setX(clamp(downX + dx, 0, maxX()));
                    container.setY(clamp(downY + dy, 0, maxY()));
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                main.removeCallbacks(longPressTask);
                container.setPressed(false);
                if (dragging) {
                    snapToEdge();
                } else if (!longPressed) {
                    container.performClick();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPressTask);
                container.setPressed(false);
                if (dragging) {
                    snapToEdge();
                }
                return true;

            default:
                return false;
        }
    }

    private void handleLongPress() {
        longPressed = true;
        container.setPressed(false);
        container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        L.toast("悬浮窗已隐藏，重新进入阅读页恢复");
        if (onHide != null) {
            onHide.run();
        }
    }

    private void snapToEdge() {
        float target = container.getX() + sizePx / 2f < hostW() / 2f ? marginPx : maxX() - marginPx;
        cancelSnap();
        snapAnim = ValueAnimator.ofFloat(container.getX(), clamp(target, 0, maxX()));
        snapAnim.setDuration(180L);
        snapAnim.setInterpolator(new DecelerateInterpolator());
        snapAnim.addUpdateListener(a -> container.setX((float) a.getAnimatedValue()));
        snapAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                persistPosition();
            }
        });
        snapAnim.start();
        container.setY(clamp(container.getY(), 0, maxY()));
    }

    private void cancelSnap() {
        if (snapAnim != null) {
            snapAnim.cancel();
            snapAnim = null;
        }
    }

    // ---------------- 位置持久化 ----------------

    private void restorePosition() {
        float fx = FloatPos.clamp01(FloatPos.x(activity, FloatPos.DEF_X));
        float fy = FloatPos.clamp01(FloatPos.y(activity, FloatPos.DEF_Y));
        container.setX(clamp(fx * maxX(), 0, maxX()));
        container.setY(clamp(fy * maxY(), 0, maxY()));
    }

    private void clampIntoBounds() {
        container.setX(clamp(container.getX(), 0, maxX()));
        container.setY(clamp(container.getY(), 0, maxY()));
    }

    private void persistPosition() {
        float fx = maxX() <= 0 ? FloatPos.DEF_X : container.getX() / (float) maxX();
        float fy = maxY() <= 0 ? FloatPos.DEF_Y : container.getY() / (float) maxY();
        FloatPos.save(activity, fx, fy);
    }

    private int hostW() {
        int w = host.getWidth();
        if (w <= 0) {
            w = host.getMeasuredWidth();
        }
        return w > 0 ? w : sizePx;
    }

    private int hostH() {
        int h = host.getHeight();
        if (h <= 0) {
            h = host.getMeasuredHeight();
        }
        return h > 0 ? h : sizePx;
    }

    private int maxX() {
        return Math.max(0, hostW() - sizePx);
    }

    private int maxY() {
        return Math.max(0, hostH() - sizePx);
    }

    static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ---------------- 外观 ----------------

    private GradientDrawable buildBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(BG_COLOR);
        bg.setStroke(dp(1), BORDER_COLOR);
        return bg;
    }

    private RippleDrawable buildRipple() {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, mask);
    }

    private int dp(float value) {
        Context c = activity;
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
