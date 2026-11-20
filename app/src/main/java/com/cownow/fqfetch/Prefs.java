package com.cownow.fqfetch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 设置项的存取。
 * <p>
 * 模块 UI 进程写自己的 SharedPreferences；宿主进程（被 hook 的番茄）通过
 * {@link XSharedPreferences} 读取同一份文件，因此改完设置不必重装模块，
 * 只在宿主下次 onResume / 开始下载时生效。
 */
final class Prefs {

    static final String NAME = "fqfetch";
    static final String MODULE_PKG = "com.cownow.fqfetch";

    // ---- key ----
    static final String K_BATCH = "batch_size";
    static final String K_PARALLEL = "parallel";
    static final String K_WAVE = "wave_size";
    static final String K_OUT_DIR = "out_dir";
    static final String K_NAME_AUTHOR = "name_with_author";
    static final String K_STRIP_TITLE = "strip_title";
    static final String K_HTML_PLAIN = "html_plain";
    static final String K_FILE_LOG = "file_log";
    static final String K_ALPHA = "button_alpha";

    // ---- 默认值 / 取值范围 ----
    static final int DEF_BATCH = 15;
    static final int DEF_PARALLEL = 4;
    static final int DEF_WAVE = 30;
    static final boolean DEF_NAME_AUTHOR = false;
    static final boolean DEF_STRIP_TITLE = true;
    static final boolean DEF_HTML_PLAIN = true;
    static final boolean DEF_FILE_LOG = true;
    static final float DEF_ALPHA = 0.7f;
    static final String DEF_OUT_DIR = "";

    static final int MIN_BATCH = 5;
    static final int MAX_BATCH = 20;
    static final int MIN_PARALLEL = 1;
    static final int MAX_PARALLEL = 8;
    static final int MIN_WAVE = 10;
    static final int MAX_WAVE = 100;
    static final float MIN_ALPHA = 0.1f;
    static final float MAX_ALPHA = 1.0f;

    private static volatile Snap cached;

    private Prefs() {
    }

    /** 一次下载/一次注入用到的全部配置。 */
    static final class Snap {
        int batchSize = DEF_BATCH;
        int parallel = DEF_PARALLEL;
        int waveSize = DEF_WAVE;
        String outDir = DEF_OUT_DIR;
        boolean nameWithAuthor = DEF_NAME_AUTHOR;
        boolean stripTitle = DEF_STRIP_TITLE;
        boolean htmlPlain = DEF_HTML_PLAIN;
        boolean fileLog = DEF_FILE_LOG;
        float buttonAlpha = DEF_ALPHA;
    }

    // ---------------- 宿主进程侧 ----------------

    /** 有缓存就用缓存，否则读一次。 */
    static Snap get() {
        Snap s = cached;
        return s != null ? s : refresh();
    }

    /** 重新读取（宿主每次 onResume / 开始下载时调用）。 */
    static Snap refresh() {
        Snap s = readHost();
        cached = s;
        return s;
    }

    static Snap readHost() {
        Snap s = new Snap();
        SharedPreferences p = openHost();
        if (p == null) {
            L.i("未读到模块配置，使用默认值");
            return s;
        }
        fill(s, p);
        return s;
    }

    @SuppressLint("SdCardPath")
    private static SharedPreferences openHost() {
        try {
            XSharedPreferences x = new XSharedPreferences(MODULE_PKG, NAME);
            x.reload();
            return x;
        } catch (Throwable t) {
            L.i("XSharedPreferences 不可用，尝试直接读文件: " + D.err(t));
        }
        String[] paths = {
                "/data/user_de/0/" + MODULE_PKG + "/shared_prefs/" + NAME + ".xml",
                "/data/data/" + MODULE_PKG + "/shared_prefs/" + NAME + ".xml",
        };
        for (String path : paths) {
            try {
                File f = new File(path);
                if (!f.exists()) {
                    continue;
                }
                XSharedPreferences x = new XSharedPreferences(f);
                x.reload();
                return x;
            } catch (Throwable ignored) {
                // 下一个路径
            }
        }
        return null;
    }

    // ---------------- 模块进程侧 ----------------

    static Snap readLocal(Context ctx) {
        Snap s = new Snap();
        try {
            fill(s, ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE));
        } catch (Throwable t) {
            L.i("读取本地配置失败: " + D.err(t));
        }
        return s;
    }

    // 必须 commit：写完立刻要 chmod，宿主可能马上读取
    @SuppressLint("ApplySharedPref")
    static void save(Context ctx, Snap s) {
        try {
            SharedPreferences.Editor e = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit();
            apply(e, s);
            e.commit();
        } catch (Throwable t) {
            L.i("保存配置失败: " + D.err(t));
            return;
        }
        makeWorldReadable(ctx);
        cached = null;
    }

    /** 写入键值（与 {@link #fill} 一一对应，抽出来便于做读写往返测试）。 */
    static void apply(SharedPreferences.Editor e, Snap s) {
        e.putInt(K_BATCH, clamp(s.batchSize, MIN_BATCH, MAX_BATCH));
        e.putInt(K_PARALLEL, clamp(s.parallel, MIN_PARALLEL, MAX_PARALLEL));
        e.putInt(K_WAVE, clamp(s.waveSize, MIN_WAVE, MAX_WAVE));
        e.putString(K_OUT_DIR, s.outDir == null ? "" : s.outDir.trim());
        e.putBoolean(K_NAME_AUTHOR, s.nameWithAuthor);
        e.putBoolean(K_STRIP_TITLE, s.stripTitle);
        e.putBoolean(K_HTML_PLAIN, s.htmlPlain);
        e.putBoolean(K_FILE_LOG, s.fileLog);
        e.putFloat(K_ALPHA, clampF(s.buttonAlpha, MIN_ALPHA, MAX_ALPHA));
    }

    /** 从一个 SharedPreferences 快照出 Snap（宿主侧 XSharedPreferences 也走这里）。 */
    static Snap from(SharedPreferences p) {
        Snap s = new Snap();
        fill(s, p);
        return s;
    }

    /** 让宿主进程可读：prefs 文件 chmod 644，并调用框架的 makeWorldReadable。 */
    @SuppressLint("SetWorldReadable")
    static void makeWorldReadable(Context ctx) {
        try {
            File f = new File(ctx.getApplicationInfo().dataDir, "shared_prefs/" + NAME + ".xml");
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            // 模块进程里 Xposed API 不存在，这里的失败是预期行为
            new XSharedPreferences(MODULE_PKG, NAME).makeWorldReadable();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ---------------- 内部 ----------------

    private static void fill(Snap s, SharedPreferences p) {
        if (p == null) {
            return;
        }
        try {
            s.batchSize = clamp(p.getInt(K_BATCH, DEF_BATCH), MIN_BATCH, MAX_BATCH);
            s.parallel = clamp(p.getInt(K_PARALLEL, DEF_PARALLEL), MIN_PARALLEL, MAX_PARALLEL);
            s.waveSize = clamp(p.getInt(K_WAVE, DEF_WAVE), MIN_WAVE, MAX_WAVE);
            String dir = p.getString(K_OUT_DIR, DEF_OUT_DIR);
            s.outDir = dir == null ? "" : dir.trim();
            s.nameWithAuthor = p.getBoolean(K_NAME_AUTHOR, DEF_NAME_AUTHOR);
            s.stripTitle = p.getBoolean(K_STRIP_TITLE, DEF_STRIP_TITLE);
            s.htmlPlain = p.getBoolean(K_HTML_PLAIN, DEF_HTML_PLAIN);
            s.fileLog = p.getBoolean(K_FILE_LOG, DEF_FILE_LOG);
            s.buttonAlpha = clampF(p.getFloat(K_ALPHA, DEF_ALPHA), MIN_ALPHA, MAX_ALPHA);
        } catch (Throwable t) {
            L.i("解析配置失败，保留默认值: " + D.err(t));
        }
    }

    static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    static float clampF(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
