package com.cownow.fqfetch;

import java.util.List;

/**
 * 宿主（番茄免费小说 com.dragon.read）侧类/接口的解析与调用。
 * <p>
 * 脚本里散落的 findClass("fu8.a$a") 之类全部收敛到这里，版本升级只需改 {@code NAME_*}。
 * 混淆名会随版本变化，所以每个都写成候选数组，第一个找到的生效。
 */
final class Hosts {

    static final String TARGET_PKG = "com.dragon.read";

    static final String[] NAME_READER_ACTIVITY = {
            "com.dragon.read.reader.ui.ReaderActivity",
    };
    /** 章节解密工具，脚本里的 ChapterOriginalContentHelper（实例方法 c(ChapterInfo) / g(请求)）。 */
    static final String[] NAME_CONTENT_HELPER = {
            "com.dragon.read.reader.utils.ChapterOriginalContentHelper",
    };
    /** 单章正文请求 bean，字段 a=bookId b=chapterId c=cursor。 */
    static final String[] NAME_CHAPTER_REQ = {
            "gu6.d",
    };
    /** 批量接口实现类，静态 a() 取实例。 */
    static final String[] NAME_BATCH_API_IMPL = {
            "fu8.a",
    };
    /** 批量接口声明，含 getBatchFullRxJava(BatchFullRequest)。 */
    static final String[] NAME_BATCH_API_IFACE = {
            "fu8.a$a",
            "fu8.a$b",
    };
    static final String[] NAME_BATCH_REQ = {
            "readersaas.com.dragon.read.saas.rpc.model.BatchFullRequest",
    };
    static final String[] NAME_BATCH_REQ_TYPE = {
            "readersaas.com.dragon.read.saas.rpc.model.BatchFullReqType",
    };
    static final String[] NAME_LIST_UTILS = {
            "com.dragon.read.base.util.ListUtils",
    };
    /** 设备信息单例持有者：静态字段 b -> 实例，实例 b() -> deviceId。 */
    static final String[] NAME_DEVICE_HOLDER = {
            "os6.d1",
    };
    /** 静态 g(String deviceId) -> keyRegisterTs。 */
    static final String[] NAME_KEY_REGISTER = {
            "ip7.i",
    };
    static final String NAME_RX_OBSERVABLE = "io.reactivex.Observable";

    // ---------------- 短篇（UGC 故事） ----------------

    /** 短篇流页面：只有短篇才会创建它，hook 到就等于「当前页是短篇」。 */
    static final String[] NAME_STORY_FEEDS = {
            "com.dragon.read.story.impl.feeds.UgcStoryFeedsFragment",
            "com.dragon.read.story.impl.feeds.KmpStoryFeedsFragment",
    };
    /** 短篇官方取数 API 宿主类：getStoryClientCurrentPostData(Activity)。 */
    static final String[] NAME_STORY_IMPL = {
            "com.dragon.read.component.biz.impl.NsStoryImpl",
    };

    final ClassLoader cl;

    Class<?> contentHelperCls;
    Class<?> chapterReqCls;
    Class<?> batchReqCls;
    Class<?> batchReqTypeCls;
    Class<?> listUtilsCls;
    Class<?> observableCls;

    Object contentHelper;
    Object decryptHelper;
    Object batchApi;
    Object preloadType;

    String deviceId = "";
    int keyRegisterTs;
    boolean batchOk;

    private Hosts(ClassLoader cl) {
        this.cl = cl;
    }

    static Hosts resolve(ClassLoader cl) {
        Hosts h = new Hosts(cl);

        h.contentHelperCls = Ref.findClass(cl, NAME_CONTENT_HELPER);
        h.chapterReqCls = Ref.findClass(cl, NAME_CHAPTER_REQ);
        h.batchReqCls = Ref.findClass(cl, NAME_BATCH_REQ);
        h.batchReqTypeCls = Ref.findClass(cl, NAME_BATCH_REQ_TYPE);
        h.listUtilsCls = Ref.findClass(cl, NAME_LIST_UTILS);
        h.observableCls = Ref.findClass(cl, NAME_RX_OBSERVABLE);

        Class<?> apiImpl = Ref.findClass(cl, NAME_BATCH_API_IMPL);
        Class<?> apiIface = Ref.findClass(cl, NAME_BATCH_API_IFACE);

        // 两个独立实例：解密 helper 会被并发调用，脚本里也是 new 两份
        h.contentHelper = Ref.newInstance(h.contentHelperCls);
        h.decryptHelper = Ref.newInstance(h.contentHelperCls);

        if (apiImpl != null) {
            try {
                h.batchApi = Ref.callStatic(apiImpl, "a");
            } catch (Throwable t) {
                L.file("批量接口实例失败: " + D.err(t));
            }
        }

        // keyRegisterTs = ip7.i.g(os6.d1.b.b())
        try {
            Class<?> holder = Ref.findClass(cl, NAME_DEVICE_HOLDER);
            Object inst = Ref.getStatic(holder, "b");
            if (inst != null) {
                h.deviceId = D.s(Ref.call(inst, "b"));
            }
            Class<?> keyCls = Ref.findClass(cl, NAME_KEY_REGISTER);
            if (keyCls != null) {
                h.keyRegisterTs = D.i(Ref.callStatic(keyCls, "g", h.deviceId), 0);
            }
        } catch (Throwable t) {
            L.file("keyRegisterTs 计算失败: " + D.err(t));
        }

        // BatchFullReqType.Preload
        if (h.batchReqTypeCls != null) {
            try {
                Object[] consts = h.batchReqTypeCls.getEnumConstants();
                if (consts != null) {
                    for (Object c : consts) {
                        boolean match = "Preload".equals(String.valueOf(c));
                        if (!match && c instanceof Enum) {
                            match = "Preload".equals(((Enum<?>) c).name());
                        }
                        if (match) {
                            h.preloadType = c;
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                L.file("枚举获取失败: " + D.err(t));
            }
        }

        h.batchOk = h.batchApi != null
                && apiIface != null
                && h.batchReqCls != null
                && h.observableCls != null
                && h.decryptHelper != null;

        L.file("devId=" + h.deviceId + " keyRegisterTs=" + h.keyRegisterTs
                + " 批量接口可用=" + h.batchOk
                + " helper=" + (h.contentHelperCls != null)
                + " batchReq=" + (h.batchReqCls != null)
                + " preload=" + (h.preloadType != null)
                + " listUtils=" + (h.listUtilsCls != null));
        return h;
    }

    // ---------------- 批量 ----------------

    /** 组一个 BatchFullRequest。 */
    Object buildBatchRequest(String bookId, List<Object> chapterIds) {
        Object req = Ref.newInstance(batchReqCls);
        if (req == null) {
            return null;
        }
        Ref.put(req, "bookId", bookId);
        Ref.put(req, "itemIds", queryList(chapterIds));
        Ref.put(req, "keyRegisterTs", Integer.valueOf(keyRegisterTs));
        if (preloadType != null) {
            Ref.put(req, "reqType", preloadType);
        }
        return req;
    }

    /** BatchFullRequest 的 itemIds 是宿主自定义集合类型，优先用宿主 ListUtils.getQueryList 转。 */
    Object queryList(List<Object> ids) {
        if (listUtilsCls != null) {
            try {
                Object r = Ref.callStatic(listUtilsCls, "getQueryList", ids);
                if (r != null) {
                    return r;
                }
            } catch (Throwable t) {
                L.file("getQueryList 失败: " + D.err(t));
            }
        }
        return ids;
    }

    /** 发起批量请求，返回 Observable。 */
    Object requestBatch(Object req) {
        return Ref.call(batchApi, "getBatchFullRxJava", req);
    }

    /** Observable.merge(list, maxConcurrency, bufferSize)，失败退回 merge(list)。 */
    Object merge(List<Object> observables, int maxConcurrency) {
        if (observableCls == null) {
            return null;
        }
        try {
            Object merged = Ref.callStatic(observableCls, "merge", observables,
                    Integer.valueOf(maxConcurrency), Integer.valueOf(16));
            if (merged != null) {
                return merged;
            }
        } catch (Throwable t) {
            L.file("merge(concurrency) 失败: " + D.err(t));
        }
        try {
            return Ref.callStatic(observableCls, "merge", observables);
        } catch (Throwable t) {
            L.file("merge 失败: " + D.err(t));
            return null;
        }
    }

    // ---------------- 解密 ----------------

    /** 批量回来的密文 -> 明文对象（其 content 字段即正文）。 */
    Object decryptBatch(Object chapterInfo) {
        if (decryptHelper == null || chapterInfo == null) {
            return null;
        }
        Object obs = Ref.call(decryptHelper, "c", chapterInfo);
        return blockingGet(obs);
    }

    /** 单章兜底请求。 */
    Object fetchSingle(String bookId, String chapterId) {
        if (chapterReqCls == null || contentHelper == null) {
            return null;
        }
        Object req = Ref.newInstance(chapterReqCls);
        if (req == null) {
            return null;
        }
        Ref.put(req, "a", bookId);
        Ref.put(req, "b", chapterId);
        Ref.put(req, "c", null);
        Object obs = Ref.call(contentHelper, "g", req);
        return blockingGet(obs);
    }

    static Object blockingGet(Object observable) {
        if (observable == null) {
            return null;
        }
        try {
            return Ref.call(observable, "blockingGet");
        } catch (Throwable t) {
            L.file("blockingGet 失败: " + D.err(t));
            return null;
        }
    }
}
