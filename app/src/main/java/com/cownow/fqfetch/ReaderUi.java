package com.cownow.fqfetch;

import android.app.Activity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 阅读页悬浮窗的入口：每个 Activity 一个 {@link FloatingPanel}，用 WeakHashMap 持有避免泄漏。
 * <p>
 * 对应原脚本里 ensureButton + BTN_MAP + PROG_MAP + setProg 的那一套，现在收敛成
 * 一个悬浮窗的注入 / 进度 / 完成 / 出错 / 清理。
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

    /** 进度：done/total 直接映射到圆环与百分比。 */
    static void setProgress(Activity activity, int done, int total) {
        FloatingPanel p = panel(activity);
        if (p != null) {
            p.showRunning(done, total);
        }
    }

    /** 全部写完：整圈 + 对勾（有失败章节时转红）。 */
    static void setDone(Activity activity, int ok, int fail) {
        FloatingPanel p = panel(activity);
        if (p != null) {
            p.showDone(ok, fail);
        }
    }

    /** 提前失败（ReaderClient 为空 / 目录为空 / 打开文件失败等）。 */
    static void setError(Activity activity) {
        FloatingPanel p = panel(activity);
        if (p != null) {
            p.showError();
        }
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
