package com.cownow.fqfetch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import android.app.AndroidAppHelper;

import de.robv.android.xposed.XposedBridge;

/**
 * 日志 / Toast 桥。
 * <p>
 * BeanShell 里的 log() / toast() 由宿主脚本引擎提供，移植后分别落到
 * XposedBridge.log（logcat: Xposed tag）和主线程 Toast（宿主 App 上下文）。
 */
/** 日志 / Toast 桥，只持有宿主 Application，不会泄漏 Activity。 */
@SuppressLint("StaticFieldLeak")
final class L {

    static final String TAG = "FqFetch";

    private static volatile Context app;
    private static volatile Handler main;

    private L() {
    }

    /** 宿主（被 hook 的 App）Application，可能为空。 */
    static Context app() {
        Context c = app;
        if (c != null) {
            return c;
        }
        try {
            c = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
            c = null;
        }
        if (c != null) {
            app = c;
        }
        return c;
    }

    static void attach(Context c) {
        if (c != null) {
            app = c;
        }
    }

    private static Handler main() {
        Handler h = main;
        if (h == null) {
            synchronized (L.class) {
                h = main;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    main = h;
                }
            }
        }
        return h;
    }

    // ---------------- logcat ----------------

    static void i(String msg) {
        try {
            XposedBridge.log(TAG + " | " + msg);
        } catch (Throwable ignored) {
            // 模块被非 Xposed 环境加载时静默
        }
    }

    static void i(String msg, Throwable t) {
        i(msg + " : " + D.err(t));
        if (t != null) {
            try {
                XposedBridge.log(t);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    // ---------------- 文件日志 ----------------

    /** 设置项「写调试日志文件」。 */
    private static volatile boolean fileLog = true;

    static void setFileLog(boolean enabled) {
        fileLog = enabled;
    }

    /** 同时写 logcat 和 TXT 目录下的 _fqfetch_debug.log。 */
    static void file(String msg) {
        i(msg);
        if (!fileLog) {
            return;
        }
        try {
            File log = Cfg.logFile();
            BufferedWriter w = new BufferedWriter(new FileWriter(log, true));
            try {
                w.write(System.currentTimeMillis() + " " + msg + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
            // 磁盘不可写时只保留 logcat
        }
    }

    // ---------------- Toast ----------------

    static void toast(final String msg) {
        final Context c = app();
        if (c == null) {
            i("[toast] " + msg);
            return;
        }
        Runnable r = () -> {
            try {
                Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                i("[toast-fail] " + msg + " : " + D.err(t));
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main().post(r);
        }
    }

    /** 任意 UI 操作都拉回主线程。 */
    static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main().post(r);
        }
    }
}
