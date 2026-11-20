package com.cownow.fqfetch;

import android.app.Activity;
import android.app.AndroidAppHelper;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（由 assets/xposed_init 指定）。
 * <p>
 * 脚本里的 {@code hookMethodAfter(readerCls, "onResume", ...)} 移植为：
 * 先在 ReaderActivity 的继承链上挂所有 {@code onResume}，一个都没挂到时退回
 * {@code Activity#onResume} 并按目标类过滤。onDestroy 用来回收注入的视图。
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
