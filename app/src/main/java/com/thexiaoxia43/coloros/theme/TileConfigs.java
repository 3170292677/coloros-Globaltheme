package com.thexiaoxia43.coloros.theme;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每磁贴独立配置：解析与查找。
 *
 * <p>设置界面把所有磁贴的配置序列化成一个 JSON 字符串存进
 * {@link HookPrefs#KEY_PER_TILE_JSON}，形如：</p>
 *
 * <pre>
 * {
 *   "wifi":       { "bgColor": "#FF112233", "bgImage": "/sdcard/wifi.png",
 *                   "corner": 20, "bgKeep": false,
 *                   "iconImage": "/sdcard/wifi_icon.png", "iconScale": 120 },
 *   "flashlight": { "bgKeep": true },
 *   "*":          { "corner": 24 }
 * }
 * </pre>
 *
 * <p>键为磁贴 spec（ColorOS 下如 {@code wifi}、{@code bt}、{@code flashlight}），
 * {@code "*"} 为通配项：没有专属配置的磁贴回落到它。{@link HookPrefs} 里的全局
 * 设置是最后一级回落。单个字段未设置时逐字段回落。</p>
 *
 * <p>hook 侧高频调用，这里对解析结果做 1 秒缓存（与 HookPrefs 的重载节拍一致）。</p>
 */
public final class TileConfigs {

    /** 通配 spec：没有专属配置的磁贴回落到它。 */
    public static final String SPEC_STAR = "*";

    /** 单个磁贴的配置。字段缺省表示“未设置，回落到上一级配置”。 */
    public static final class Cfg {
        /** 背景颜色 #AARRGGBB；{@code Integer.MIN_VALUE} 表示未设置。 */
        public int bgColor = Integer.MIN_VALUE;
        /** 背景图片路径；null 表示未设置。 */
        public String bgImage;
        /** 圆角半径（dp）；{@code Integer.MIN_VALUE} 表示未设置。 */
        public int corner = Integer.MIN_VALUE;
        /** 保留系统原背景（不替换该磁贴的底图）。 */
        public boolean bgKeep;
        /** 自定义图标图片路径；null 表示未设置。 */
        public String iconImage;
        /** 图标缩放（百分比，100 = 原大小）；{@code Integer.MIN_VALUE} 表示未设置。 */
        public int iconScale = Integer.MIN_VALUE;

        /** 是否配置了背景相关项（颜色/图片/圆角任一）。 */
        public boolean hasBg() {
            return bgColor != Integer.MIN_VALUE || bgImage != null || corner != Integer.MIN_VALUE;
        }

        public boolean isEmpty() {
            return bgColor == Integer.MIN_VALUE && bgImage == null
                    && corner == Integer.MIN_VALUE && !bgKeep
                    && iconImage == null && iconScale == Integer.MIN_VALUE;
        }
    }

    /** 解析结果缓存（含空结果缓存，避免空串反复解析）。 */
    private static volatile Map<String, Cfg> sCache;
    private static volatile boolean sCacheEmpty;
    private static volatile long sCacheAt;
    private static final long CACHE_TTL_MS = 1000L;

    private TileConfigs() {
    }

    /** 取全部配置；JSON 为空或解析失败时返回空 Map。 */
    public static Map<String, Cfg> all() {
        final long now = System.currentTimeMillis();
        Map<String, Cfg> cache = sCache;
        if ((cache != null || sCacheEmpty) && now - sCacheAt < CACHE_TTL_MS) {
            return cache != null ? cache : Collections.<String, Cfg>emptyMap();
        }
        synchronized (TileConfigs.class) {
            if ((sCache != null || sCacheEmpty) && System.currentTimeMillis() - sCacheAt < CACHE_TTL_MS) {
                return sCache != null ? sCache : Collections.<String, Cfg>emptyMap();
            }
            String json = HookPrefs.perTileConfigJson();
            Map<String, Cfg> parsed = parse(json);
            sCacheEmpty = parsed.isEmpty();
            sCache = parsed.isEmpty() ? null : parsed;
            sCacheAt = System.currentTimeMillis();
            return sCache != null ? sCache : Collections.<String, Cfg>emptyMap();
        }
    }

    /** 按磁贴 spec 查配置：精确匹配 → 通配 {@code *} → null。 */
    public static Cfg forSpec(String spec) {
        if (spec == null) {
            return null;
        }
        Map<String, Cfg> all = all();
        Cfg cfg = all.get(spec);
        if (cfg == null) {
            cfg = all.get(SPEC_STAR);
        }
        return cfg;
    }

    /** 是否存在任意每磁贴配置（用于决定是否启用额外 hook 路径）。 */
    public static boolean any() {
        return !all().isEmpty();
    }

    /** 清空缓存（设置界面保存后调用以立即生效，主要面向同进程调试）。 */
    public static void invalidate() {
        synchronized (TileConfigs.class) {
            sCache = null;
            sCacheEmpty = false;
            sCacheAt = 0L;
        }
    }

    // ------------------------------------------------------------------ JSON

    /** 解析整个配置 JSON；出错时返回空 Map（不影响系统运行）。 */
    public static Map<String, Cfg> parse(String json) {
        Map<String, Cfg> result = new LinkedHashMap<String, Cfg>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(json);
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String spec = keys.next();
                JSONObject obj = root.optJSONObject(spec);
                if (obj == null) {
                    continue;
                }
                Cfg cfg = parseCfg(obj);
                if (!cfg.isEmpty()) {
                    result.put(spec, cfg);
                }
            }
        } catch (JSONException e) {
            // 配置写错时静默忽略，回退全局设置
        }
        return result;
    }

    /** 把一个磁贴的配置对象转成 JSON 片段（设置界面保存用）。 */
    public static JSONObject cfgToJson(Cfg cfg) throws JSONException {
        JSONObject obj = new JSONObject();
        if (cfg.bgColor != Integer.MIN_VALUE) {
            obj.put("bgColor", String.format("#%08X", cfg.bgColor));
        }
        if (cfg.bgImage != null) {
            obj.put("bgImage", cfg.bgImage);
        }
        if (cfg.corner != Integer.MIN_VALUE) {
            obj.put("corner", cfg.corner);
        }
        if (cfg.bgKeep) {
            obj.put("bgKeep", true);
        }
        if (cfg.iconImage != null) {
            obj.put("iconImage", cfg.iconImage);
        }
        if (cfg.iconScale != Integer.MIN_VALUE) {
            obj.put("iconScale", cfg.iconScale);
        }
        return obj;
    }

    /** 把一个磁贴的 JSON 对象解析成 {@link Cfg}；未知字段忽略。 */
    public static Cfg parseCfg(JSONObject obj) {
        Cfg cfg = new Cfg();
        if (obj == null) {
            return cfg;
        }
        String color = obj.optString("bgColor", null);
        if (color != null && !color.trim().isEmpty()) {
            int parsed = parseColor(color);
            if (parsed != Integer.MIN_VALUE) {
                cfg.bgColor = parsed;
            }
        }
        String bgImage = obj.optString("bgImage", null);
        if (bgImage != null && !bgImage.trim().isEmpty()) {
            cfg.bgImage = bgImage.trim();
        }
        if (obj.has("corner")) {
            int corner = obj.optInt("corner", Integer.MIN_VALUE);
            if (corner != Integer.MIN_VALUE) {
                cfg.corner = corner;
            }
        }
        cfg.bgKeep = obj.optBoolean("bgKeep", false);
        String iconImage = obj.optString("iconImage", null);
        if (iconImage != null && !iconImage.trim().isEmpty()) {
            cfg.iconImage = iconImage.trim();
        }
        if (obj.has("iconScale")) {
            int scale = obj.optInt("iconScale", Integer.MIN_VALUE);
            if (scale != Integer.MIN_VALUE) {
                cfg.iconScale = scale;
            }
        }
        return cfg;
    }

    /** 解析 #AARRGGBB / #RRGGBB / 0xAARRGGBB；失败返回 {@code Integer.MIN_VALUE}。 */
    public static int parseColor(String text) {
        if (text == null) {
            return Integer.MIN_VALUE;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return Integer.MIN_VALUE;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        try {
            long parsed = Long.parseLong(value, 16);
            if (value.length() == 6) {
                parsed |= 0xFF000000L;
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
