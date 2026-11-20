package com.cownow.fqfetch;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 输出目录与日志路径。
 * <p>
 * 脚本里写死 {@code /storage/emulated/0/Download/FqFetch/TXT}；模块跑在宿主进程里，
 * 分区存储可能直接拒绝该路径，因此：设置项里的自定义目录优先，
 * 否则按 {@link #candidates()} 顺序探测第一个可写目录并缓存。
 */
final class Cfg {

    static final String DIR_NAME = "FqFetch";
    /** 长篇（章节体系）子目录。 */
    static final String SUB_DIR = "TXT";
    /** 短篇（UGC 故事）子目录。 */
    static final String SUB_DIR_STORY = "Story";
    static final String LOG_NAME = "_fqfetch_debug.log";

    private static volatile File activeOutDir;
    private static volatile File logFile;

    private Cfg() {
    }

    /** 候选输出目录（长篇口径）。 */
    static List<File> candidates() {
        return candidates(SUB_DIR);
    }

    /** 候选输出目录，按优先级排列。 */
    static List<File> candidates(String subDir) {
        List<File> list = new ArrayList<>();
        String sub = subDir == null || subDir.isEmpty() ? SUB_DIR : subDir;
        try {
            list.add(new File(Environment.getExternalStorageDirectory(),
                    "Download/" + DIR_NAME + "/" + sub));
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            list.add(new File(dl, DIR_NAME + "/" + sub));
        } catch (Throwable ignored) {
            // ignore
        }
        Context c = L.app();
        if (c != null) {
            try {
                File ext = c.getExternalFilesDir(null);
                if (ext != null) {
                    list.add(new File(ext, DIR_NAME + "/" + sub));
                }
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                list.add(new File(c.getFilesDir(), DIR_NAME + "/" + sub));
            } catch (Throwable ignored) {
                // ignore
            }
        }
        list.add(new File("/data/local/tmp/" + DIR_NAME + "/" + sub));
        return list;
    }

    /**
     * 解析本次输出目录：设置项里的自定义目录优先，不可写则回退自动探测。
     *
     * @param override 设置项 out_dir，空字符串表示不指定
     */
    static synchronized File resolveOutDir(String override) {
        String o = override == null ? "" : override.trim();
        if (!o.isEmpty()) {
            File custom = new File(o);
            if (probe(custom)) {
                activeOutDir = custom;
                logFile = null;
                L.i("输出目录(自定义): " + custom.getAbsolutePath());
                return custom;
            }
            L.i("自定义目录不可写，回退自动探测: " + o);
        }
        for (File cand : candidates()) {
            if (probe(cand)) {
                activeOutDir = cand;
                logFile = null;
                L.i("输出目录: " + cand.getAbsolutePath());
                return cand;
            }
        }
        File last = candidates().get(candidates().size() - 1);
        activeOutDir = last;
        logFile = null;
        L.i("无可用输出目录，回退: " + last.getAbsolutePath());
        return last;
    }

    static File outDir() {
        File d = activeOutDir;
        return d != null ? d : resolveOutDir(null);
    }

    static synchronized File logFile() {
        File f = logFile;
        if (f == null) {
            f = new File(logDir(), LOG_NAME);
            logFile = f;
        }
        return f;
    }

    /**
     * 日志目录优先放宿主的外部私有目录：Android 10+ 分区存储下公共 Download 往往不可写，
     * 而 {@code /sdcard/Android/data/<host>/files} 永远可写，也方便 adb pull。
     */
    private static File logDir() {
        Context c = L.app();
        if (c != null) {
            try {
                File ext = c.getExternalFilesDir(null);
                if (ext != null) {
                    File d = new File(ext, DIR_NAME);
                    if (writable(d)) {
                        return d;
                    }
                }
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                File d = new File(c.getFilesDir(), DIR_NAME);
                if (writable(d)) {
                    return d;
                }
            } catch (Throwable ignored) {
                // ignore
            }
        }
        return outDir();
    }

    /** 目录存在且真的能写文件才算通过。 */
    static boolean writable(File dir) {
        return probe(dir);
    }

    /** 目录存在且真的能写文件才算通过。 */
    private static boolean probe(File dir) {
        FileOutputStream fo = null;
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            if (!dir.isDirectory()) {
                return false;
            }
            File p = new File(dir, ".fqfetch_probe");
            fo = new FileOutputStream(p, false);
            fo.write('x');
            fo.close();
            fo = null;
            //noinspection ResultOfMethodCallIgnored
            p.delete();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (fo != null) {
                try {
                    fo.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    static String describeCandidates() {
        StringBuilder sb = new StringBuilder();
        for (File f : candidates()) {
            sb.append("  ").append(f.getAbsolutePath()).append('\n');
        }
        return sb.toString();
    }
}
