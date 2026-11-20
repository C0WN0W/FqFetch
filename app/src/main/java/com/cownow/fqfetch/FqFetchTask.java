package com.cownow.fqfetch;

import android.annotation.SuppressLint;
import android.app.Activity;

import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 主体流程：script.java 的 Java 移植。
 * <p>
 * 提速原理：
 * <ul>
 *   <li>宿主自带批量接口 /reading/reader/batch_full/v（App 自己的“自动缓存”就用它），
 *       一次请求可取 20 章密文；</li>
 *   <li>用 RxJava 的 Observable.merge(..., maxConcurrency, 16) 在 io 线程池并发跑多个批次；</li>
 *   <li>本地用 ChapterOriginalContentHelper#c(ChapterInfo) 逐章解密（纯 CPU）。</li>
 * </ul>
 * 批量没取到的章节自动降级为单章接口。并发参数、输出目录、正文清洗开关全部来自设置页（Prefs）。
 * <p>
 * 线程模型：{@link #run()} 在工作线程执行，所有 UI 操作经 {@link L#onMain(Runnable)} 回主线程。
 */
final class FqFetchTask implements Runnable {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final Activity activity;
    private final ClassLoader loader;

    private int okCnt;
    private int failCnt;

    private FqFetchTask(Activity activity, ClassLoader loader) {
        this.activity = activity;
        this.loader = loader != null ? loader : activity.getClass().getClassLoader();
    }

    @SuppressLint("ThreadConstraint")
    static void start(Activity activity, ClassLoader loader) {
        if (activity == null) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            L.toast("正在下载中，请稍候…");
            return;
        }
        L.toast("开始下载（批量并发）…");
        Thread th = new Thread(new FqFetchTask(activity, loader), "FqFetch-download");
        th.setDaemon(true);
        th.start();
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    @SuppressLint("ThreadConstraint")
    @Override
    public void run() {
        long t0 = System.currentTimeMillis();
        // 每次开始下载都重新读设置，改完设置不必重启宿主
        Prefs.Snap cfg = Prefs.refresh();
        L.setFileLog(cfg.fileLog);
        try {
            doDownload(t0, cfg);
        } catch (Throwable t) {
            L.file("下载异常: " + D.err(t));
            L.toast("下载异常: " + D.err(t));
            ReaderUi.setError(activity);
        } finally {
            RUNNING.set(false);
        }
    }

    // ------------------------------------------------------------------

    private void doDownload(long t0, Prefs.Snap cfg) {
        L.file("---------- 开始 v2.1 batch=" + cfg.batchSize
                + " parallel=" + cfg.parallel + " wave=" + cfg.waveSize + " ----------");

        // 1) ReaderClient 在 ReaderActivity 的 K 字段上
        Object client = null;
        try {
            Object c0 = Ref.get(activity, "K");
            if (c0 != null && c0.getClass().getName().endsWith("ReaderClient")) {
                client = c0;
            }
        } catch (Throwable t) {
            L.file("读取 K 失败: " + D.err(t));
        }
        if (client == null) {
            L.file("ReaderClient 为空，终止");
            L.toast("未获取到 ReaderClient");
            ReaderUi.setError(activity);
            return;
        }

        // 2) bookId / 书名 / 作者
        Object proxy = Ref.callOrNull(client, "getBookProviderProxy");
        Object bookIdObj = Ref.get(proxy, "bookId");
        Object book = Ref.get(proxy, "b");
        String bookName = D.s(bookIdObj);
        String author = "";
        if (book != null) {
            String bn = D.s(Ref.callOrNull(book, "getBookName"));
            if (!bn.isEmpty()) {
                bookName = bn;
            } else {
                L.file("getBookName 为空");
            }
            author = D.s(Ref.callOrNull(book, "getAuthorName"));
        }
        L.file("bookId=" + bookIdObj + " 书名=" + bookName + " 作者=" + author);
        if (bookIdObj == null) {
            L.toast("bookId 为空");
            ReaderUi.setError(activity);
            return;
        }
        final String bookId = String.valueOf(bookIdObj);

        // 3) 章节列表
        List<Object> items = collectChapters(book, client);
        if (items == null || items.isEmpty()) {
            L.toast("目录为空，请先打开目录页");
            L.file("目录为空，终止");
            ReaderUi.setError(activity);
            return;
        }
        final int total = items.size();
        L.file("共 " + total + " 章");
        ReaderUi.setProgress(activity, 0, total);

        // 4) 落地目标：自定义目录 → MediaStore(公共下载) → 文件候选
        String baseName = cfg.nameWithAuthor && !author.isEmpty() ? bookName + " - " + author : bookName;
        String fileName = D.safeName(baseName) + ".txt";
        TxtOutput out;
        try {
            out = TxtOutput.open(activity.getApplicationContext(), cfg.outDir, fileName);
        } catch (Throwable t) {
            L.file("打开输出文件失败: " + D.err(t) + " name=" + fileName);
            L.toast("打开输出文件失败: " + D.err(t));
            ReaderUi.setError(activity);
            return;
        }
        BufferedWriter fw = out.writer();
        L.file("输出目标: " + out.describe());

        boolean interrupted = false;
        try {
            fw.write(bookName + "\n");
            if (!author.isEmpty()) {
                fw.write("作者：" + author + "\n");
            }
            fw.write("共 " + total + " 章\n");
            fw.write("bookId：" + bookId + "\n\n");
            fw.flush();

            Hosts host = Hosts.resolve(loader);
            downloadAll(fw, host, items, bookId, total, cfg);
        } catch (Throwable t) {
            interrupted = true;
            L.file("下载中断: " + D.err(t));
            L.toast("下载中断: " + D.err(t));
            ReaderUi.setError(activity);
        } finally {
            out.close();
        }

        long cost = System.currentTimeMillis() - t0;
        L.file("完成：成功 " + okCnt + " 失败 " + failCnt + " 总耗时 " + cost
                + "ms -> " + out.describe());
        if (!interrupted) {
            L.toast("下载完成：成功 " + okCnt + " 失败 " + failCnt + "（" + (cost / 1000) + "s）");
            ReaderUi.setDone(activity, okCnt, failCnt);
        }
    }

    // ------------------------------------------------------------------

    /** book.getChapterLinkedHashMap() ；取不到再走 CatalogProvider 兜底。 */
    private List<Object> collectChapters(Object book, Object client) {
        List<Object> items = null;
        if (book != null) {
            Object m = Ref.callOrNull(book, "getChapterLinkedHashMap");
            if (m instanceof Map) {
                Collection<?> values = ((Map<?, ?>) m).values();
                if (!values.isEmpty()) {
                    items = new ArrayList<>(values.size());
                    items.addAll(values);
                }
            } else {
                L.file("getChapterLinkedHashMap 失败/为空");
            }
        }
        if (items == null || items.isEmpty()) {
            try {
                Object catalog = Ref.callOrNull(client, "getCatalogProvider");
                int cn = D.i(Ref.call(catalog, "b0"), 0);
                if (cn > 0) {
                    items = new ArrayList<>(cn);
                    for (int i = 0; i < cn; i++) {
                        Object it = Ref.call(catalog, "k", Integer.valueOf(i));
                        if (it != null) {
                            items.add(it);
                        }
                    }
                }
            } catch (Throwable t) {
                L.file("CatalogProvider 兜底失败: " + D.err(t));
            }
        }
        return items;
    }

    // ------------------------------------------------------------------

    private void downloadAll(BufferedWriter fw, Hosts host, List<Object> items,
                             String bookId, int total, Prefs.Snap cfg) throws Exception {
        int wStart = 0;
        while (wStart < total) {
            int wEnd = Math.min(wStart + cfg.waveSize, total);
            long wt0 = System.currentTimeMillis();

            // ---- 1) 组批 + 并发请求 ----
            Map<String, Object> fetched = new HashMap<>();
            if (host.batchOk) {
                List<Object> obsList = new ArrayList<>();
                int bStart = wStart;
                while (bStart < wEnd) {
                    int bEnd = Math.min(bStart + cfg.batchSize, wEnd);
                    List<Object> ids = new ArrayList<>(bEnd - bStart);
                    for (int q = bStart; q < bEnd; q++) {
                        Object qid = Ref.read(items.get(q), "chapterId");
                        if (qid != null) {
                            ids.add(String.valueOf(qid));
                        }
                    }
                    if (!ids.isEmpty()) {
                        try {
                            Object req = host.buildBatchRequest(bookId, ids);
                            Object obs = req == null ? null : host.requestBatch(req);
                            if (obs != null) {
                                obsList.add(obs);
                            }
                        } catch (Throwable t) {
                            L.file("组批失败 bStart=" + bStart + " : " + D.err(t));
                        }
                    }
                    bStart = bEnd;
                }

                if (!obsList.isEmpty()) {
                    try {
                        Object merged = host.merge(obsList, cfg.parallel);
                        if (merged != null) {
                            Object listObs = Ref.call(merged, "toList");
                            Object results = Hosts.blockingGet(listObs);
                            if (results instanceof List) {
                                for (Object resp : (List<?>) results) {
                                    Object data = Ref.get(resp, "data");
                                    if (data instanceof Map) {
                                        for (Map.Entry<?, ?> en : ((Map<?, ?>) data).entrySet()) {
                                            fetched.put(String.valueOf(en.getKey()), en.getValue());
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        L.file("本波批量失败，降级单章: " + D.err(t));
                    }
                }
                L.file("波[" + wStart + "-" + (wEnd - 1) + "] 批量取回 " + fetched.size()
                        + " 章，耗时 " + (System.currentTimeMillis() - wt0) + "ms");
            }

            // ---- 2) 解密 + 按顺序写盘（缺失的降级单章）----
            for (int wi = wStart; wi < wEnd; wi++) {
                try {
                    Object item = items.get(wi);
                    Object cid = Ref.read(item, "chapterId");
                    Object cname = Ref.read(item, "chapterName");
                    String title = D.s(cname).isEmpty() ? ("第" + (wi + 1) + "章") : D.s(cname);

                    String content = null;
                    String emsg = null;

                    if (cid == null) {
                        emsg = "chapterId 为空";
                    } else {
                        String cidStr = String.valueOf(cid);
                        Object info = fetched.get(cidStr);
                        if (info != null) {
                            try {
                                Object dec = host.decryptBatch(info);
                                if (dec != null) {
                                    content = D.s(Ref.get(dec, "content"));
                                }
                            } catch (Throwable t) {
                                emsg = "解密失败: " + D.err(t);
                            }
                        }
                        if (content == null || content.isEmpty()) {
                            try {
                                Object info2 = host.fetchSingle(bookId, cidStr);
                                if (info2 != null) {
                                    content = D.s(Ref.get(info2, "content"));
                                }
                                if (content == null || content.isEmpty()) {
                                    if (emsg == null) {
                                        emsg = "批量未返回且单章为空";
                                    }
                                } else {
                                    emsg = null;
                                }
                            } catch (Throwable t) {
                                if (emsg == null) {
                                    emsg = D.err(t);
                                }
                            }
                        }
                    }

                    fw.write(title + "\n");
                    if (content != null && !content.isEmpty()) {
                        String body = content;
                        if (cfg.htmlPlain) {
                            body = D.toPlain(body);
                        }
                        if (cfg.stripTitle) {
                            body = D.stripTitle(body, title);
                        }
                        fw.write(body);
                        fw.write("\n\n");
                        okCnt++;
                    } else {
                        fw.write("【本章获取失败：" + (emsg == null ? "空内容" : emsg) + "】\n\n");
                        failCnt++;
                        if (failCnt <= 20) {
                            L.file("章节失败 #" + (wi + 1) + " : " + emsg);
                        }
                    }
                    fw.flush();
                } catch (Throwable t) {
                    L.file("写章异常 wi=" + wi + " : " + D.err(t));
                    failCnt++;
                }
            }

            L.file("波[" + wStart + "-" + (wEnd - 1) + "] 完成 ok=" + okCnt + " fail=" + failCnt
                    + " 用时 " + (System.currentTimeMillis() - wt0) + "ms");
            ReaderUi.setProgress(activity, wEnd, total);
            L.toast("下载中 " + wEnd + "/" + total + "（成功" + okCnt + " 失败" + failCnt + "）");
            wStart = wEnd;
        }
    }
}
