package com.cownow.fqfetch;

import android.app.Activity;
import android.app.AndroidAppHelper;
import android.content.ContextWrapper;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（由 assets/xposed_init 指定）。
 * <p>
 * 只处理 {@code com.dragon.read}：读配置 → 挂长篇阅读页（ReaderActivity）与短篇流 fragment，
 * 命中后在宿主 Activity 上注入同一个悬浮窗；点击由 {@link Tasks} 按页面类型分派。
 * <p>
 * 长篇在继承链上挂所有 {@code onResume}，一个都没挂到时退回 {@code Activity#onResume} 并按目标类过滤；
 * 短篇只挂 fragment 自己声明的方法（不往基类上挂）。onDestroy 用来回收注入的视图。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) {
            return;
        }
        if (!Hosts.TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        try {
            L.attach(AndroidAppHelper.currentApplication());
        } catch (Throwable ignored) {
            // 早期加载时 Application 可能还没建好，后续从 Activity 再补
        }
        L.i("加载 " + lp.packageName + " process=" + lp.processName);
        try {
            Prefs.Snap cfg = Prefs.refresh();
            L.setFileLog(cfg.fileLog);
            L.i("配置 batch=" + cfg.batchSize + " parallel=" + cfg.parallel + " wave=" + cfg.waveSize
                    + " alpha=" + cfg.buttonAlpha + " outDir=" + cfg.outDir);
        } catch (Throwable t) {
            L.i("读取设置失败: " + D.err(t));
        }

        final ClassLoader hostLoader = lp.classLoader;
        final Class<?> readerCls = Ref.findClass(hostLoader, Hosts.NAME_READER_ACTIVITY);
        if (readerCls == null) {
            L.i("未找到 ReaderActivity，宿主版本不匹配");
            return;
        }

        final XC_MethodHook resumeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onReaderResume(param.thisObject, hostLoader);
            }
        };

        final XC_MethodHook destroyHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onReaderDestroy(param.thisObject);
            }
        };

        int resumeCount = hookHierarchy(readerCls, "onResume", resumeHook);
        int destroyCount = hookHierarchy(readerCls, "onDestroy", destroyHook);

        if (resumeCount == 0) {
            // ReaderActivity 自己没有声明 onResume：退回框架层，按类型过滤
            L.i("ReaderActivity 未声明 onResume，回退 hook Activity#onResume");
            try {
                XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (readerCls.isInstance(param.thisObject)) {
                            onReaderResume(param.thisObject, hostLoader);
                        }
                    }
                });
                resumeCount = 1;
            } catch (Throwable t) {
                L.file("回退 hook Activity#onResume 失败: " + D.err(t));
            }
        }

        if (destroyCount == 0) {
            try {
                XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (readerCls.isInstance(param.thisObject)) {
                            onReaderDestroy(param.thisObject);
                        }
                    }
                });
            } catch (Throwable t) {
                L.file("回退 hook Activity#onDestroy 失败: " + D.err(t));
            }
        }

        if (resumeCount == 0) {
            L.i("Hook ReaderActivity#onResume 失败，按钮不会出现");
        } else {
            L.i("已 Hook ReaderActivity#onResume（" + resumeCount + " 处）");
        }

        hookStoryFeeds(hostLoader);
    }

    // ---------------- 短篇流 ----------------

    /**
     * 挂短篇流 fragment：只挂该类自己声明的方法（不往 Fragment 基类上挂，否则会波及所有 Fragment）。
     * 触发点优先 initView，其次 onResume，命中后把 fragment 记进 {@link Pages} 并注入同一套悬浮窗。
     */
    private static void hookStoryFeeds(final ClassLoader hostLoader) {
        int count = 0;
        for (String name : Hosts.NAME_STORY_FEEDS) {
            Class<?> cls = Ref.findClass(hostLoader, name);
            if (cls == null) {
                continue;
            }
            count += hookDeclared(cls, "initView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onStoryFragment(param.thisObject, hostLoader);
                }
            });
            count += hookDeclared(cls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onStoryFragment(param.thisObject, hostLoader);
                }
            });
        }
        if (count == 0) {
            L.i("未找到短篇流 fragment（短篇页不会有悬浮窗）");
        } else {
            L.i("已 Hook 短篇流 fragment（" + count + " 处）");
        }
    }

    private static void onStoryFragment(Object frag, ClassLoader hostLoader) {
        Activity act = storyActivity(frag);
        if (act == null) {
            return;
        }
        Pages.rememberStoryFragment(act, frag);
        L.attach(act.getApplicationContext());
        try {
            ReaderUi.ensure(act, hostLoader);
        } catch (Throwable t) {
            L.file("短篇悬浮窗注入失败: " + D.err(t));
        }
    }

    /** 短篇 fragment 的宿主 Activity：getHostActivity() / getActivity() / getContext() 逐级回退。 */
    private static Activity storyActivity(Object frag) {
        if (frag == null) {
            return null;
        }
        Object a = Ref.callOrNull(frag, "getHostActivity");
        if (!(a instanceof Activity)) {
            a = Ref.callOrNull(frag, "getActivity");
        }
        if (!(a instanceof Activity)) {
            Object ctx = Ref.callOrNull(frag, "getContext");
            int guard = 0;
            while (ctx instanceof ContextWrapper && !(ctx instanceof Activity) && guard++ < 8) {
                ctx = ((ContextWrapper) ctx).getBaseContext();
            }
            a = ctx;
        }
        return a instanceof Activity ? (Activity) a : null;
    }

    /** 只挂本类声明的方法（fragment 场景必须这样，避免把基类方法全 hook 了）。 */
    private static int hookDeclared(Class<?> cls, String methodName, XC_MethodHook hook) {
        int count = 0;
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, hook);
                count++;
            } catch (Throwable t) {
                L.file("hook " + cls.getName() + "#" + methodName + " 失败: " + D.err(t));
            }
        }
        return count;
    }

    private static void onReaderResume(Object self, ClassLoader hostLoader) {
        if (!(self instanceof Activity)) {
            return;
        }
        Activity act = (Activity) self;
        L.attach(act.getApplicationContext());
        try {
            ReaderUi.ensure(act, hostLoader);
        } catch (Throwable t) {
            L.file("ensureButton error: " + D.err(t));
        }
    }

    private static void onReaderDestroy(Object self) {
        if (self instanceof Activity) {
            ReaderUi.detach((Activity) self);
        }
    }

    /** 在类继承链（不含 android.app.Activity 及以上）挂某名字的全部方法。 */
    private static int hookHierarchy(Class<?> cls, String methodName, XC_MethodHook hook) {
        int count = 0;
        for (Class<?> k = cls; k != null && k != Activity.class && k != Object.class; k = k.getSuperclass()) {
            Method[] methods;
            try {
                methods = k.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : methods) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, hook);
                    count++;
                } catch (Throwable t) {
                    L.file("hook " + k.getName() + "#" + methodName + " 失败: " + D.err(t));
                }
            }
        }
        return count;
    }
}
