package com.cownow.fqfetch;

import android.content.Context;
import android.content.SharedPreferences;

/** 悬浮窗位置（存在宿主自己的 prefs 里，模块进程无法写宿主数据目录）。 */
final class FloatPos {

    private static final String NAME = "fqfetch_ui";
    private static final String K_X = "float_x";
    private static final String K_Y = "float_y";

    static final float DEF_X = 1f;
    static final float DEF_Y = 0.35f;

    private FloatPos() {
    }

    static float x(Context c, float def) {
        return prefs(c).getFloat(K_X, def);
    }

    static float y(Context c, float def) {
        return prefs(c).getFloat(K_Y, def);
    }

    static void save(Context c, float x, float y) {
        prefs(c).edit()
                .putFloat(K_X, clamp01(x))
                .putFloat(K_Y, clamp01(y))
                .apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static float clamp01(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, v));
    }
}
