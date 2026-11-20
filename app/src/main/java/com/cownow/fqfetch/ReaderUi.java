package com.cownow.fqfetch;

import android.app.Activity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 阅读页悬浮窗的入口：每个 Activity 一个 {@link FloatingPanel}，用 WeakHashMap 持有避免泄漏。
 * <p>
 * 对应原脚本里 ensureButton + BTN_MAP + PROG_MAP + setProg 的那一套。
 * <p>
 * 线程约定：{@code FqFetchTask} 在工作线程里推进度，而 ValueAnimator 只能有 Looper 的线程启动，
 * 所以所有对外状态更新都经 {@link L#onMain(Runnable)} 回到主线程（FloatingPanel 内部还有第二层兜底）。
 */
final class ReaderUi {

    private static final Map<Activity, FloatingPanel> PANELS =
            Collections.synchronizedMap(new WeakHashMap<Activity, FloatingPanel>());

    private ReaderUi() {
    }

    /** onResume 注入；已注入过就跳过。 */
    static void ensure(Activity activity, ClassLoader loader) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (PANELS.containsKey(activity)) {
            return;
        }
        try {
            FloatingPanel panel = FloatingPanel.attach(activity, loader,
                    () -> hide(activity));
            if (panel != null) {
                PANELS.put(activity, panel);
            }
        } catch (Throwable t) {
            L.file("注入悬浮窗失败: " + D.err(t));
        }
    }

    /** 进度：done/total 直接映射到圆环与百分比。任意线程可调。 */
    static void setProgress(final Activity activity, final int done, final int total) {
        L.onMain(() -> {
            FloatingPanel p = panel(activity);
            if (p != null) {
                p.showRunning(done, total);
            }
        });
    }

    /** 全部写完：整圈 + 对勾（有失败章节时转红）。任意线程可调。 */
    static void setDone(final Activity activity, final int ok, final int fail) {
        L.onMain(() -> {
            FloatingPanel p = panel(activity);
            if (p != null) {
                p.showDone(ok, fail);
            }
        });
    }

    /** 提前失败（ReaderClient 为空 / 目录为空 / 打开文件失败等）。任意线程可调。 */
    static void setError(final Activity activity) {
        L.onMain(() -> {
            FloatingPanel p = panel(activity);
            if (p != null) {
                p.showError();
            }
        });
    }

    /** onDestroy：移除悬浮窗并停掉动画。 */
    static void detach(Activity activity) {
        hide(activity);
    }

    private static void hide(final Activity activity) {
        if (activity == null) {
            return;
        }
        final FloatingPanel p = PANELS.remove(activity);
        if (p == null) {
            return;
        }
        L.onMain(p::destroy);
    }

    private static FloatingPanel panel(Activity activity) {
        return activity == null ? null : PANELS.get(activity);
    }
}
