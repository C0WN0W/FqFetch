package com.cownow.fqfetch;

/** 文本/类型小工具，对应脚本里的 errText / safeName / stripTitle / toPlain。 */
final class D {

    private D() {
    }

    static String err(Throwable t) {
        if (t == null) {
            return "";
        }
        String s = String.valueOf(t);
        try {
            Throwable c = t.getCause();
            if (c != null && c != t) {
                s = s + " <- " + c;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return s;
    }

    /** null 安全取值并转字符串。 */
    static String s(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof String) {
            return (String) o;
        }
        return String.valueOf(o);
    }

    static int i(Object o, int def) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    static long l(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        return def;
    }

    static String safeName(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        String sn = s;
        sn = sn.replace("\\", "_").replace("/", "_").replace(":", "_");
        sn = sn.replace("*", "_").replace("?", "_").replace("\"", "_");
        sn = sn.replace("<", "_").replace(">", "_").replace("|", "_");
        sn = sn.replace("\n", "_").replace("\r", "_");
        return sn.trim();
    }

    /** 正文首行如果就是标题（或包含标题），去掉这一行。 */
    static String stripTitle(String body, String title) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String st = body;
        int nl = st.indexOf('\n');
        String first = nl < 0 ? st : st.substring(0, nl);
        String f1 = first.replace(" ", "").replace("\u3000", "").trim();
        String t1 = ("" + title).replace(" ", "").replace("\u3000", "").trim();
        boolean dup = false;
        if (!f1.isEmpty()) {
            if (f1.equals(t1)) {
                dup = true;
            } else if (f1.length() >= 4 && t1.contains(f1)) {
                dup = true;
            } else if (t1.length() >= 4 && f1.contains(t1)) {
                dup = true;
            }
        }
        if (dup) {
            st = nl < 0 ? "" : st.substring(nl + 1);
        }
        return st;
    }

    /** HTML 正文转纯文本。 */
    static String toPlain(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        if (s.indexOf('<') < 0) {
            return s;
        }
        String tp = s;
        tp = tp.replaceAll("(?i)</p\\s*>", "\n");
        tp = tp.replaceAll("(?i)</h[1-6]\\s*>", "\n");
        tp = tp.replaceAll("(?i)<br\\s*/?>", "\n");
        tp = tp.replaceAll("(?i)</div\\s*>", "\n");
        tp = tp.replaceAll("<[^>]*>", "");
        tp = tp.replace("&nbsp;", " ");
        tp = tp.replace("&amp;", "&");
        tp = tp.replace("&lt;", "<");
        tp = tp.replace("&gt;", ">");
        tp = tp.replace("&quot;", "\"");
        tp = tp.replace("&#39;", "'");
        tp = tp.replace("&apos;", "'");
        tp = tp.replaceAll("[ \\t]+\\n", "\n");
        tp = tp.replaceAll("\\n{3,}", "\n\n");
        return tp;
    }
}
