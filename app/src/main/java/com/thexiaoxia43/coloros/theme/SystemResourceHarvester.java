package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import de.robv.android.xposed.XposedBridge;

import java.io.ByteArrayOutputStream;

/**
 * 在 SystemUI 进程里运行，把控制中心磁贴的真实视觉参数（颜色、圆角、图标 drawable）
 * 写进 SharedPreferences。模块的 SettingsActivity 读这些值来做 1:1 预览。
 *
 * <p>使用方式：在 hook 回调第一次拿到 SystemUI Context 后调
 * {@link #harvest(Context)}。本方法会用 {@code volatile boolean} 做单次执行保护。</p>
 */
public final class SystemResourceHarvester {

    private static volatile boolean sDone = false;

    public static synchronized void harvest(Context ctx) {
        if (sDone) return;
        try {
            Context systemUiCtx = ctx.getApplicationContext();
            Resources res = systemUiCtx.getResources();
            SharedPreferences sp = systemUiCtx.getSharedPreferences(
                    PrefKeys.PREFS_NAME, Context.MODE_WORLD_READABLE);
            SharedPreferences.Editor ed = sp.edit();

            // 1) 颜色
            int tileBgColor = findColor(res, systemUiCtx, "oplus_qs_tile_background_color",
                    "oplus_qs_tile_bg_color", "qs_tile_background_color");
            if (tileBgColor != 0) {
                ed.putInt(PrefKeys.REAL_TILE_BG_COLOR, tileBgColor);
                XposedBridge.log("SystemResourceHarvester: tileBgColor = #"
                        + Integer.toHexString(tileBgColor));
            }

            // 2) 圆角
            float cornerDp = findDimen(res, systemUiCtx, "oplus_qs_tile_corner_radius",
                    "oplus_qs_tile_corner", "qs_tile_corner_radius");
            if (cornerDp > 0) {
                ed.putFloat(PrefKeys.REAL_TILE_CORNER_DP, cornerDp);
                XposedBridge.log("SystemResourceHarvester: tileCorner = " + cornerDp + "dp");
            }

            // 3) 文字颜色
            int textColor = findColor(res, systemUiCtx, "oplus_qs_tile_text_color",
                    "qs_tile_text_color", "qs_tile_primary_text");
            if (textColor != 0) {
                ed.putInt(PrefKeys.REAL_TEXT_COLOR, textColor);
            }

            // 4) 图标颜色（tint）
            int iconTint = findColor(res, systemUiCtx, "oplus_qs_tile_icon_color",
                    "qs_tile_icon_color", "qs_icon_color");
            if (iconTint != 0) {
                ed.putInt(PrefKeys.REAL_ICON_TINT, iconTint);
            }

            // 5) Drawable → base64（把几个常见的 icon 存下来）
            String[] iconSpecs = {"wifi", "bt", "flashlight", "location",
                    "airplane", "rotation", "darkmode", "volume", "brightness"};
            for (String spec : iconSpecs) {
                String key = "oplus_qs_" + spec + "_icon";
                int id = res.getIdentifier(key, "drawable",
                        systemUiCtx.getPackageName());
                if (id == 0) {
                    // 试试不带 oplus_qs_ 前缀
                    id = res.getIdentifier("ic_" + spec, "drawable",
                            systemUiCtx.getPackageName());
                }
                if (id != 0) {
                    try {
                        Drawable d = res.getDrawable(id);
                        if (d != null) {
                            String b64 = drawableToBase64(d);
                            ed.putString(PrefKeys.REAL_ICON_PREFIX + spec, b64);
                            XposedBridge.log("SystemResourceHarvester: icon " + spec
                                    + " → " + b64.length() + " chars base64");
                        }
                    } catch (Throwable ignore) {}
                }
            }

            ed.apply();
            // 最后标记已采集（只在成功后）
            SharedPreferences sp2 = systemUiCtx.getSharedPreferences(
                    PrefKeys.PREFS_NAME, Context.MODE_WORLD_READABLE);
            sp2.edit().putBoolean(PrefKeys.REAL_HARVESTED, true).apply();
            sDone = true;
            XposedBridge.log("SystemResourceHarvester: done, all values written");
        } catch (Throwable t) {
            XposedBridge.log("SystemResourceHarvester FAILED: " + t);
        }
    }

    // ---------------------------------------------------------- 辅助查找

    private static int findColor(Resources res, Context ctx, String... candidates) {
        String pkg = ctx.getPackageName(); // 动态拿包名，不硬编码
        for (String name : candidates) {
            try {
                int id = res.getIdentifier(name, "color", pkg);
                if (id != 0) return res.getColor(id);
            } catch (Throwable ignore) {}
        }
        return 0;
    }

    private static float findDimen(Resources res, Context ctx, String... candidates) {
        String pkg = ctx.getPackageName();
        for (String name : candidates) {
            try {
                int id = res.getIdentifier(name, "dimen", pkg);
                if (id != 0) {
                    return res.getDimension(id) / res.getDisplayMetrics().density;
                }
            } catch (Throwable ignore) {}
        }
        return 0;
    }

    private static String drawableToBase64(Drawable d) {
        Bitmap bm;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
        } else {
            int w = Math.max(d.getIntrinsicWidth(), 96);
            int h = Math.max(d.getIntrinsicHeight(), 96);
            bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, w, h);
            d.draw(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        byte[] bytes = out.toByteArray();
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private SystemResourceHarvester() {}
}
