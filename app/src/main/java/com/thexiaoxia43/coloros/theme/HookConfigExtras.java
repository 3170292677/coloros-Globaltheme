package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>模块自有配置</b>在主题包里的行李：导出时把 SystemUI prefs 里除底图以外的键值
 * 记成一张 extras 表，导入时原样倒回去。
 *
 * <p><b>为什么需要它</b>：ColorOS 主题包的标准格式（{@code assets/colors.xml} +
 * {@code res/drawable-*}）只装得下 5 条颜色和一批底图，而模块的「修改项」远不止这些
 * ——每个形状的圆角 / 可见度、图标底色与四种着色的不透明度、控制中心背景色、
 * 编辑模式 / 压掉模糊 / ★ 按钮等开关。这些项没法塞进标准颜色表，所以导出时另写一张
 * extras 表（{@link ThemePackImporter#EXTRAS_ENTRY}），导入时靠它把「所有修改项」补齐。</p>
 *
 * <p><b>底图不进这张表</b>：它们已经走图片字节通道（{@code ACTION_SHAPE_BG_IMAGE}），
 * 再放一份会让包和广播体积翻几倍（底图 Base64 动辄几百 KB）。</p>
 *
 * <p><b>格式</b>：扁平的一层 XML，每条一个 {@code <item type="…" key="…">值</item>}。
 * 值一律做转义（{@code & < >} 与 CR / LF 用字符引用），保证往返字节一致。</p>
 *
 * <p>纯工具类，不碰 Xposed API；导出（{@link ThemePackExporter}）与导入
 * （{@link TileImageChannel}）都在 SystemUI 进程里用它。</p>
 */
public final class HookConfigExtras {

    private static final String TAG = "SystemUIHook/Extras";

    /** 表根节点名。 */
    private static final String ROOT = "extras";
    /** 每一条的节点名。 */
    private static final String ITEM = "item";

    private HookConfigExtras() {
    }

    /**
     * 把 SystemUI prefs 里除底图以外的键值编成 extras 表。
     *
     * @return XML 文本；没有任何配置时返回空串（调用方据此不写这一条）
     */
    public static String build(final Context ctx) {
        Map<String, Object> values = TileLongPressConfig.snapshotNonImage(ctx);
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(values.size() * 48 + 96);
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<").append(ROOT).append(">\n");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            String type;
            if (v instanceof String) {
                type = "string";
            } else if (v instanceof Integer) {
                type = "int";
            } else if (v instanceof Long) {
                type = "long";
            } else if (v instanceof Float) {
                type = "float";
            } else if (v instanceof Boolean) {
                type = "boolean";
            } else {
                // 本模块不写其它类型；真遇到就跳过，别让整张表废掉
                de.robv.android.xposed.XposedBridge.log(TAG + ": skip " + e.getKey()
                        + " (" + v.getClass() + ")");
                continue;
            }
            sb.append("  <").append(ITEM)
                    .append(" type=\"").append(type).append('"')
                    .append(" key=\"").append(escape(e.getKey())).append("\">")
                    .append(escape(String.valueOf(v)))
                    .append("</").append(ITEM).append(">\n");
        }
        sb.append("</").append(ROOT).append(">\n");
        return sb.toString();
    }

    /**
     * 解析 extras 表并把全部项写回本模块的 prefs。
     *
     * <p>解析出来的值按类型还原（int / long / float / boolean / string），
     * 交给 {@link TileLongPressConfig#applySnapshot} 统一落盘。</p>
     *
     * @param xml {@link #build(Context)} 产出的文本；空串直接跳过
     * @return 写进去几条
     */
    public static int apply(final Context ctx, final String xml) {
        if (ctx == null || xml == null || xml.length() == 0) {
            return 0;
        }
        Map<String, Object> values;
        try {
            values = parse(xml);
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log(TAG + ": parse failed - " + t);
            return 0;
        }
        int written = TileLongPressConfig.applySnapshot(ctx, values);
        de.robv.android.xposed.XposedBridge.log(TAG + ": applied " + written + " / "
                + values.size() + " item(s)");
        return written;
    }

    /** 解析表：{@code <item type key>值</item>} 扁平列表。 */
    private static Map<String, Object> parse(final String xml) throws Exception {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        String type = null;
        String key = null;
        StringBuilder text = null;
        for (int event = parser.getEventType();
                event != XmlPullParser.END_DOCUMENT;
                event = parser.next()) {
            if (event == XmlPullParser.START_TAG && ITEM.equals(parser.getName())) {
                type = parser.getAttributeValue(null, "type");
                key = parser.getAttributeValue(null, "key");
                text = new StringBuilder();
            } else if (event == XmlPullParser.TEXT && text != null) {
                text.append(parser.getText());
            } else if (event == XmlPullParser.END_TAG && ITEM.equals(parser.getName())) {
                if (key != null && key.length() > 0 && text != null) {
                    Object value = coerce(type, text.toString());
                    if (value != null) {
                        out.put(key, value);
                    }
                }
                type = null;
                key = null;
                text = null;
            }
        }
        return out;
    }

    /** 按类型把文本还原成对应的包装类型；解析失败返回 null。 */
    private static Object coerce(final String type, final String text) {
        if (type == null) {
            return null;
        }
        try {
            if ("string".equals(type)) {
                return text;
            }
            if ("int".equals(type)) {
                return Integer.valueOf(text.trim());
            }
            if ("long".equals(type)) {
                return Long.valueOf(text.trim());
            }
            if ("float".equals(type)) {
                return Float.valueOf(text.trim());
            }
            if ("boolean".equals(type)) {
                return Boolean.valueOf(text.trim());
            }
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log(TAG + ": bad value '" + text
                    + "' for type " + type);
        }
        return null;
    }

    /**
     * XML 文本转义。
     *
     * <p>CR / LF 用字符引用而不是原样写：XML 解析器会把文档里的裸换行统一成 LF，
     * 原样写会让 CR 悄悄变成 LF，往返就不一致了。字符引用不受这条规则影响。</p>
     */
    private static String escape(final String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '\r':
                    sb.append("&#13;");
                    break;
                case '\n':
                    sb.append("&#10;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}