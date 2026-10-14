package com.thexiaoxia43.coloros.theme;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * 在 SystemUI 进程内读取模块设置。
 *
 * <p>设置界面（{@link SettingsActivity}）写入 {@code SystemUIHookPrefs}，
 * hook 侧通过 {@link XSharedPreferences} 读取。LSPosed 需要在模块
 * AndroidManifest 里声明 {@code xposedsharedprefs} 才能跨进程读取，
 * 该声明已在清单文件中添加。</p>
 */
public final class HookPrefs {

    /** 模块包名，必须与 build.gradle 中的 applicationId 保持一致。 */
    public static final String MODULE_PACKAGE = "com.thexiaoxia43.coloros.theme";
    /** 与设置界面共用的 SharedPreferences 文件名。 */
    public static final String PREFS_NAME = "SystemUIHookPrefs";

    // ---- 磁贴底图相关键值（设置界面与 hook 侧共用）----
    public static final String KEY_REPLACE_TILE_BG = "replaceTileBackground";
    public static final String KEY_TILE_BG_DARK_ONLY = "tileBackgroundDarkOnly";
    public static final String KEY_TILE_BG_FORCE_ALL = "tileBackgroundForceAll";
    public static final String KEY_TILE_BG_COLOR = "tileBackgroundColor";
    public static final String KEY_TILE_BG_CORNER = "tileBackgroundCornerRadius";
    public static final String KEY_TILE_BG_IMAGE = "tileBackgroundImagePath";
    /** 每磁贴独立配置（JSON），键为磁贴 spec（如 wifi/bt/flashlight，"*" 为通配）。 */
    public static final String KEY_PER_TILE_JSON = "perTileConfigJson";
    /** 开启后把遇到的磁贴 spec 记录到 Xposed 日志，便于发现真实 spec 名。 */
    public static final String KEY_LOG_TILE_SPECS = "logTileSpecs";

    // ---- 默认值 ----
    public static final boolean DEF_REPLACE_TILE_BG = false;
    public static final boolean DEF_DARK_ONLY = true;
    public static final boolean DEF_FORCE_ALL = true;
    /** #33FFFFFF：半透明卡片色，接近 ColorOS 暗色磁贴观感。 */
    public static final int DEF_TILE_BG_COLOR = 0x33FFFFFF;
    public static final int DEF_TILE_BG_CORNER = 16;
    public static final String DEF_TILE_BG_IMAGE = "";

    /** 两次 reload 之间的最小间隔，避免频繁读盘。 */
    private static final long RELOAD_INTERVAL_MS = 1000L;

    private static XSharedPreferences sPrefs;
    private static long sLastReload;

    private HookPrefs() {
    }

    private static synchronized XSharedPreferences prefs() {
        final long now = System.currentTimeMillis();
        if (sPrefs == null) {
            sPrefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
            try {
                sPrefs.makeWorldReadable();
            } catch (Throwable ignored) {
                // LSPosed 下不需要，旧框架下才需要
            }
            reload();
            sLastReload = now;
        } else if (now - sLastReload > RELOAD_INTERVAL_MS) {
            reload();
            sLastReload = now;
        }
        return sPrefs;
    }

    private static void reload() {
        try {
            sPrefs.reload();
        } catch (Throwable t) {
            XposedBridge.log("HookPrefs: reload failed - " + t);
        }
    }

    /**
     * 强制立刻重读一遍模块设置。
     *
     * <p>{@link #prefs()} 平时按 {@link #RELOAD_INTERVAL_MS} 节流，最长会有 1 秒
     * 的滞后。设置页刚改完开关就发广播过来时，那一秒里读到的还是旧值，
     * 表现出来就是「改了没反应」——所以要改配置的广播路径先调一次这个。</p>
     */
    public static synchronized void reloadNow() {
        prefs();
        reload();
        sLastReload = System.currentTimeMillis();
    }

    // ------------------------------------------------------------ 磁贴底图设置

    public static boolean replaceTileBackground() {
        return getBoolean(KEY_REPLACE_TILE_BG, DEF_REPLACE_TILE_BG);
    }

    public static boolean tileBackgroundDarkOnly() {
        return getBoolean(KEY_TILE_BG_DARK_ONLY, DEF_DARK_ONLY);
    }

    public static boolean tileBackgroundForceAll() {
        return getBoolean(KEY_TILE_BG_FORCE_ALL, DEF_FORCE_ALL);
    }

    public static int tileBackgroundColor() {
        return getInt(KEY_TILE_BG_COLOR, DEF_TILE_BG_COLOR);
    }

    public static int tileBackgroundCornerRadiusDp() {
        return getInt(KEY_TILE_BG_CORNER, DEF_TILE_BG_CORNER);
    }

    public static String tileBackgroundImagePath() {
        return getString(KEY_TILE_BG_IMAGE, DEF_TILE_BG_IMAGE);
    }

    public static String perTileConfigJson() {
        return getString(KEY_PER_TILE_JSON, "");
    }

    public static boolean logTileSpecs() {
        return getBoolean(KEY_LOG_TILE_SPECS, false);
    }

    // ------------------------------------------------------------------ 通用读取

    public static boolean getBoolean(String key, boolean defValue) {
        try {
            return prefs().getBoolean(key, defValue);
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static int getInt(String key, int defValue) {
        try {
            return prefs().getInt(key, defValue);
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static String getString(String key, String defValue) {
        try {
            String value = prefs().getString(key, defValue);
            return value == null ? defValue : value;
        } catch (Throwable t) {
            return defValue;
        }
    }
}