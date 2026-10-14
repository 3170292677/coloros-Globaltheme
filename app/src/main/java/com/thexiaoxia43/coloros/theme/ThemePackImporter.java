package com.thexiaoxia43.coloros.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <p>ColorOS「全局主题」包里 <b>SystemUI 子模块</b>的解析工具。</p>
 *
 * <p>主题包的这一层就是一个以包名命名的压缩包，里面只有 {@code assets/colors.xml}
 * 与 {@code res/drawable-*} 两样东西——没有清单、没有 dex、没有 resources.arsc，
 * 所以它既不是 APK 也不是反编译产物，而是主题引擎运行时读的资源目录。</p>
 *
 * <p>用户实际选的那个 {@code *.theme}（本机实测 57MB）是<b>外面再套一层</b>：外层是
 * 各模块的压缩包（{@code com.android.systemui}、{@code com.android.settings}…）+
 * {@code icons} + {@code wallpaper} + {@code themeInfo.xml}，SystemUI 那一层就嵌在
 * 里面。两种形态都认，见 {@link #readEntries}。</p>
 *
 * <p>这里只负责「从包里把界面底图抠出来」这一件事：按 {@link #TARGETS} 里的映射把
 * {@code res/drawable-xxhdpi/} 下的 png 解出来、必要时裁掉 nine-patch 的黑边、
 * 再编码成 PNG 字节交给调用方去发广播。颜色表（{@code colors.xml}）只取其中一条
 * ——图标底色，见 {@link #tileIconBgColor}；那九十多张指纹动画帧不处理。</p>
 *
 * <p>纯工具类，不碰 Xposed API：既在模块 App 进程里跑（导入），
 * 也在 SystemUI 进程里跑（导出打包，见 {@link ThemePackExporter}）。</p>
 */
public final class ThemePackImporter {

    /** 主题包里界面底图所在的目录（xxhdpi，也就是 3x 那一档）。 */
    private static final String DRAWABLE_DIR = "res/drawable-xxhdpi/";

    /**
     * 完整 {@code *.theme} 里 SystemUI 那一层子包的名字。
     *
     * <p>{@code themeInfo.xml} 的 {@code packageInfo} 里声明的就是不带后缀的
     * {@code com.android.systemui version="001"}；包里同时躺着 {@code _002} 之类的
     * 变体，那些当兜底，见 {@link #scan}。</p>
     */
    private static final String SUB_PACKAGE = "com.android.systemui";

    /** 主题包的颜色表：磁贴图标底色就写在里面，见 {@link #tileIconBgColor}。 */
    public static final String COLORS_ENTRY = "assets/colors.xml";

    /**
     * <b>模块自有</b>的整份配置快照（导出时才写，导入靠它还原全部修改项）。
     *
     * <p>ColorOS 的主题包格式只有 {@code assets/colors.xml} 与
     * {@code res/drawable-*} 两样，装得下 5 条颜色和一批底图，装不下模块的全部修改项
     * （每个形状的圆角 / 可见度、图标底色与四种着色的不透明度、控制中心背景色、各式开关）。
     * 所以导出时在包里多写这一张表，把 SystemUI prefs 里除图片以外的键整份记下来。</p>
     *
     * <p><b>不影响格式兼容</b>：标准导入器按条目名精确匹配（见 {@link #wantedEntries}），
     * 不认识这张表就自然跳过，所以带 extras 的包仍然是合法主题包；而模块自己的导入
     * 链路看到它就把全部修改项一并还原。</p>
     */
    public static final String EXTRAS_ENTRY = "assets/systemui_hook_extras.xml";

    /**
     * 控制中心<b>面板背景图</b>在导出包里的条目名。
     *
     * <p>标准主题包没有这个落点（它由主题引擎自己画），但模块允许用户换一张，属于
     * 「修改项」，所以导出时另起一个条目名带上；导入时再送回
     * {@link PrefKeys#SHAPE_QS_BACKGROUND} 那个形状位。</p>
     */
    public static final String QS_BG_ENTRY = DRAWABLE_DIR + "systemui_hook_qsbg.png";

    /**
     * 亮度 / 音量胶囊<b>进度底图</b>在导出包里的条目名；理由同 {@link #QS_BG_ENTRY}。
     */
    public static final String SLIDER_PROGRESS_ENTRY =
            DRAWABLE_DIR + "systemui_hook_slider_progress.png";

    /**
     * 颜色表里「状态栏快捷设置图标<b>激活时的背景颜色</b>」。
     *
     * <p>就是 2×1 宽卡片与三阶段卡片图标底下那块小色块的底色
     * （模块里叫「图标底色」，见 {@code TileLongPressConfig#ICON_COLOR_SHAPES}）。
     * 同一段里还有 {@code status_bar_qs_icon_bg_drawable_switch_enable}
     * （图标背景总开关）与 {@code status_bar_qs_tile_icon_color_inactive}
     * （图标本身的着色，不是底色），那两个不归「底色」管。</p>
     */
    public static final String KEY_TILE_ICON_BG = "status_bar_qs_tile_bg_color_active";

    /**
     * 颜色表里「磁贴图标<b>本身</b>的颜色」两条。
     *
     * <p>这两个才是模块里「图标着色（激活）/ 图标着色」用的键：底色改的是图标底下那块
     * 小色块，这两条改的是图标笔画本身（见 {@code TileColorHook}）。</p>
     */
    public static final String KEY_TILE_ICON_ACTIVE = "status_bar_qs_tile_icon_color_active";
    /** 见 {@link #KEY_TILE_ICON_ACTIVE}。 */
    public static final String KEY_TILE_ICON_INACTIVE = "status_bar_qs_tile_icon_color_inactive";
    /** 颜色表里「磁贴标签文字颜色」，对应模块里的「文字颜色」。 */
    public static final String KEY_TILE_LABEL = "status_bar_qs_tile_label_color";

    /**
     * 颜色表里「通知背景遮罩色」——主题作者给控制中心那一层铺的<b>底</b>。
     *
     * <p>它是不透明的实色（本机主题是 {@code #FFF3F3F3}）。模块拿它当控制中心
     * 背景色：底一铺成不透明，面板就不再透出系统那层跟着日夜模式变的 scrim，
     * 主题包里那批半透明磁贴素材（{@code status_bar_qs_tile_bg_inactive.png}
     * 是 80% 白、方形卡 75% 白、2×2 媒体卡 70% 白）也才不会被底下的深色染灰。</p>
     */
    public static final String KEY_QS_BG = "notification_scrim_bg_mask_color";
    /**
     * {@link #KEY_QS_BG} 没有时的兜底：颜色表里的「通知材质背景色」。
     *
     * <p>这条普遍是<b>半透明</b>的（{@code #19FFFFFF}，10% 白），压不住底下的
     * 系统 scrim，只能当兜底用。</p>
     */
    public static final String KEY_QS_BG_FALLBACK = "notification_material_background_color";

    /**
     * 控制中心背景色最亮通道的上限（{@code #8C}，中灰一档）。
     *
     * <p>主题作者那层遮罩色普遍是接近白的浅灰（本机主题 {@code #F3F3F3}）。100% 可见度
     * 整块铺满时，浅色模式还凑合，深色模式就是一块刺眼的白——实测直接反馈「太亮了」。
     * 所以超过这个上限的一律等比压暗到 {@code #8C8C8C} 这一档，两种模式都不扎眼。</p>
     *
     * <p>想调明暗改这一个数即可。</p>
     */
    private static final int QS_BG_MAX_CHANNEL = 0x8C;

    /** 一个形状要套哪张图。 */
    public static final class Target {
        /** 压缩包里的完整条目路径。 */
        public final String entry;
        /** 模块的形状标识，见 {@link TileLongPressConfig}。 */
        public final String shape;
        /** 写「开」还是「关」那一套；不分套的形状恒为 true。 */
        public final boolean active;
        /** 汇总提示里用的名字。 */
        public final String label;

        Target(String entry, String shape, boolean active, String label) {
            this.entry = entry;
            this.shape = shape;
            this.active = active;
            this.label = label;
        }
    }

    /**
     * 形状映射表。
     *
     * <p>两点值得说明：</p>
     * <ul>
     *   <li>主题包只给了一张 2×1 的图（{@code highlighttile}），而模块里
     *       「2×1 宽卡片」与「2×1 三阶段卡片」是两个形状，所以同一张图发两条。
     *       只有 1×1 分开 / 关两套（见 {@code TileLongPressConfig#hasStateVariants}），
     *       其余形状恒用「开」那套；</li>
     *   <li>{@code status_nodevice_card_bg_inactive} 是 934×200 的宽卡图，现在发两条：
     *       一条给「1×2 设备卡片」（尺寸对得上，就是它本来的样子），一条仍给
     *       「1×1 方形卡片」（1:1 套宽图会被中心裁掉两侧，保持旧行为不变，见下）。</li>
     * </ul>
     *
     * <p>包里的 {@code tile_bg_unavailable}、{@code highlighttile_bg_inactive}、
     * {@code notification_material_bg_dim}、{@code clear_all_icon}、
     * {@code stat_battery_pb} 在模块里没有对应落点，一律跳过。</p>
     */
    public static final Target[] TARGETS = {
            new Target(DRAWABLE_DIR + "status_bar_qs_tile_bg_active.png",
                    TileLongPressConfig.SHAPE_1X1, true, "1×1 圆形磁贴 · 开"),
            new Target(DRAWABLE_DIR + "status_bar_qs_tile_bg_inactive.png",
                    TileLongPressConfig.SHAPE_1X1, false, "1×1 圆形磁贴 · 关"),
            // 控制中心右上角那排小圆钮（★ / ✎ / ⚙ / ⋮）和 1×1 磁贴一样是正圆，
            // 长得就是磁贴「关」的样子（深色圆底 + 浅色图标），所以吃同一张「关」图。
            // 它和 1×1 是两套配置，所以这里单独发一条（条目名相同，读包时会被去重）
            new Target(DRAWABLE_DIR + "status_bar_qs_tile_bg_inactive.png",
                    TileLongPressConfig.SHAPE_ENTRANCE, true, "1×1 圆形小磁贴"),
            new Target(DRAWABLE_DIR + "status_bar_qs_highlighttile_bg_active.png",
                    TileLongPressConfig.SHAPE_2X1, true, "2×1 宽卡片"),
            new Target(DRAWABLE_DIR + "status_bar_qs_highlighttile_bg_active.png",
                    TileLongPressConfig.SHAPE_THREE, true, "2×1 三阶段卡片"),
            new Target(DRAWABLE_DIR + "status_bar_qs_media_bg_active.png",
                    TileLongPressConfig.SHAPE_2X2, true, "2×2 磁贴"),
            new Target(DRAWABLE_DIR + "status_nodevice_card_bg_inactive.png",
                    TileLongPressConfig.SHAPE_CARD, true, "1×1 方形卡片"),
            new Target(DRAWABLE_DIR + "status_nodevice_card_bg_inactive.png",
                    TileLongPressConfig.SHAPE_CARD_WIDE, true, "1×2 设备卡片"),
            new Target(DRAWABLE_DIR + "notification_material_bg.png",
                    TileLongPressConfig.SHAPE_NOTIFICATION, true, "通知卡片"),
            new Target(DRAWABLE_DIR + "clear_all_bg.png",
                    TileLongPressConfig.SHAPE_CLEAR, true, "清除按钮"),
            new Target(DRAWABLE_DIR + "systemui_icon_volume_bg.9.png",
                    TileLongPressConfig.SHAPE_SLIDER, true, "亮度 / 音量胶囊"),
    };

    private ThemePackImporter() {
    }

    /**
     * 标准格式装不下、但属于「用户改过的项」的两张底图。
     *
     * <p>单独一张表：{@link #TARGETS} 是「标准主题包给哪些形状供图」的映射，数它几项
     * 就是导入提示里的「已导入 N / 11 项」；把控制中心背景图与胶囊进度图混进去，会让
     * 这个计数与标准包对不上。导出时这两张按这张表另起条目名，导入时送回对应的形状位。</p>
     */
    public static final Target[] EXTRA_TARGETS = {
            new Target(QS_BG_ENTRY, PrefKeys.SHAPE_QS_BACKGROUND, true, "控制中心背景图"),
            new Target(SLIDER_PROGRESS_ENTRY, PrefKeys.SHAPE_SLIDER_PROGRESS, true, "胶囊进度底图"),
    };

    /** 映射表里出现过的所有条目名（去重）——读包时只认这些。 */
    public static Set<String> wantedEntries() {
        Set<String> names = new LinkedHashSet<String>();
        for (Target target : TARGETS) {
            names.add(target.entry);
        }
        for (Target target : EXTRA_TARGETS) {
            names.add(target.entry);
        }
        // 颜色表也一起取出来：图标底色写在里面，见 tileIconBgColor
        names.add(COLORS_ENTRY);
        // 模块自有的整份配置快照，见 EXTRAS_ENTRY
        names.add(EXTRAS_ENTRY);
        return names;
    }

    /**
     * 从颜色表里抠出「图标激活时的背景颜色」。
     *
     * <p>这份 xml 是一张扁平资源表（只有 {@code <bool>/<color>/<dimen>/<integer>}），
     * 没有嵌套也没有转义，所以按名字定位、再取后面第一个 {@code #} 开头的十六进制就够了，
     * 不值得为它引一个 XML 解析器。</p>
     *
     * @param raw {@code assets/colors.xml} 的字节；没这张表传 null
     * @return ARGB；没有这一项 / 值不合法时返回 0（调用方据此保持「跟随系统」）
     */
    public static int tileIconBgColor(byte[] raw) {
        return colorValue(raw, KEY_TILE_ICON_BG);
    }

    /**
     * 从颜色表里抠出控制中心背景的纯色。
     *
     * <p>优先「通知背景遮罩色」——主题作者给面板铺的那层底，它是实色；没有这一条
     * 时退回「通知材质背景色」（半透明）。两个都没有返回 0，调用方据此不带这一项，
     * 让用户原来设的颜色留着。</p>
     *
     * <p><b>alpha 一律顶成 FF</b>：面板底必须是实的。留半透明的话，底下系统那层
     * scrim 会透上来，而它是跟着日夜模式换的（浅色 ≈ 不透明浅灰、深色 ≈ 深灰），
     * 于是贴在面板上、本身就是 70–80% 白的主题磁贴素材会被染成两种颜色——
     * 同一份主题包，浅色模式看是白的、深色模式看是灰的。</p>
     *
     * <p><b>同时把亮度压到 {@link #QS_BG_MAX_CHANNEL}</b>：见那个常量的说明。</p>
     *
     * @return 不透明 ARGB；两条都没有时返回 0
     */
    public static int qsBackgroundColor(byte[] raw) {
        int argb = colorValue(raw, KEY_QS_BG);
        if (argb == 0) {
            argb = colorValue(raw, KEY_QS_BG_FALLBACK);
        }
        return argb == 0 ? 0 : dimTo(argb, QS_BG_MAX_CHANNEL);
    }

    /**
     * 把颜色等比压暗到「最亮通道 ≤ {@code max}」，并顶成不透明。
     *
     * <p>等比缩 RGB 而不是取固定灰：主题的遮罩色带色相时也留得住色相。本来就暗于
     * {@code max} 的颜色原样返回——它已经够暗，再压就成黑块了。</p>
     */
    private static int dimTo(int argb, int max) {
        int top = Math.max(Color.red(argb), Math.max(Color.green(argb), Color.blue(argb)));
        if (top <= max || top == 0) {
            return argb | 0xFF000000;
        }
        float scale = (float) max / top;
        return Color.argb(255,
                Math.round(Color.red(argb) * scale),
                Math.round(Color.green(argb) * scale),
                Math.round(Color.blue(argb) * scale));
    }

    /**
     * 从颜色表里抠出任意一条颜色。
     *
     * <p>同一份 xml 里还有「图标本身的颜色」（激活 / 未激活）与「标签文字颜色」，
     * 解析方式完全一样，只是换个名字，所以做成通用的。</p>
     *
     * @param raw  {@code assets/colors.xml} 的字节；没这张表传 null
     * @param name 资源名，见 {@link #KEY_TILE_ICON_BG} / {@link #KEY_TILE_ICON_ACTIVE} 等
     * @return ARGB；没有这一项 / 值不合法时返回 0
     */
    public static int colorValue(byte[] raw, String name) {
        if (raw == null || raw.length == 0 || name == null || name.length() == 0) {
            return 0;
        }
        String xml = new String(raw, StandardCharsets.UTF_8);
        int at = xml.indexOf(name);
        if (at < 0) {
            return 0;
        }
        int hash = xml.indexOf('#', at);
        if (hash < 0) {
            return 0;
        }
        int end = hash + 1;
        while (end < xml.length() && Character.digit(xml.charAt(end), 16) >= 0) {
            end++;
        }
        String hex = xml.substring(hash + 1, end);
        try {
            if (hex.length() == 6) {
                // 只写 RGB 时补上不透明
                return (int) (0xFF000000L | Long.parseLong(hex, 16));
            }
            if (hex.length() == 8) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 该条目是不是 nine-patch（{@code .9.png}），需要裁边。 */
    public static boolean isNinePatch(String entry) {
        return entry != null && entry.endsWith(".9.png");
    }

    /**
     * 这个形状要不要吃素材自带的圆角。
     *
     * <p>{@code true} 的都是<b>圆角矩形</b>卡片：方形卡片、横向设备卡片、2×1 宽卡片、
     * 2×1 三阶段卡片、2×2 磁贴、通知卡片。它们各有各的比例，硬套模块统一的
     * 20dp 会把素材自己画好的圆角切掉一条边。</p>
     *
     * <p>不吃的三类是形状语义决定的：1×1 圆形磁贴与「清除全部」按钮<b>恒为正圆</b>
     * （圆角不是配置项，是形状本身），亮度 / 音量胶囊是<b>胶囊形</b>
     * （圆角 = 短边一半，跟着素材比例走反而画不成胶囊）。</p>
     */
    public static boolean adaptsCorner(String shape) {
        return TileLongPressConfig.SHAPE_CARD.equals(shape)
                || TileLongPressConfig.SHAPE_CARD_WIDE.equals(shape)
                || TileLongPressConfig.SHAPE_2X1.equals(shape)
                || TileLongPressConfig.SHAPE_2X2.equals(shape)
                || TileLongPressConfig.SHAPE_THREE.equals(shape)
                || TileLongPressConfig.SHAPE_NOTIFICATION.equals(shape);
    }

    /**
     * 量出素材自带的圆角比例：<b>圆角像素 ÷ 素材宽度</b>。
     *
     * <p>这些底图是圆角矩形，最上面一行里第一个不透明像素的 x 就是圆角半径——圆角
     * 左上角那一段正是「从透明渐变到不透明」，所以第一行第一个画上去的像素必然落在
     * 半径处。完全透明的行（素材四周留白）跳过，一直找不到就当作直角返回 0。</p>
     *
     * <p>返回<b>比例</b>而不是像素：素材尺寸和磁贴尺寸不保证 1:1，只有比例是尺度无关的，
     * 绘制时再按磁贴实际宽度换算，见 {@link TileBackgroundDrawable}。</p>
     */
    public static float cornerRatioOf(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return 0f;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return 0f;
        }
        // 只需要最上面几行：这些素材的圆角都从第一行就开始了。全透明的图扫完这几行
        // 直接当直角，免得为一张空图把整张图逐像素读一遍
        int rows = Math.min(height, 64);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                    float ratio = (float) x / width;
                    // 超过一半就不是圆角而是胶囊 / 正圆了，截断掉
                    return ratio > 0.5f ? 0.5f : ratio;
                }
            }
        }
        return 0f;
    }

    /**
     * 顺序遍历一次压缩包，把需要的条目整条读进内存。
     *
     * <p>{@link ZipInputStream} 只能顺序读，而且<b>不能</b>把它的流直接交给
     * {@code BitmapFactory.decodeStream}——解码器会多读一段，流位置一乱，下一次
     * {@code getNextEntry()} 就报错。所以命中的条目一律先整条读出来再解码。</p>
     *
     * <p>找齐就提前 break：包里还躺着九十多张指纹动画帧，没必要读完。</p>
     *
     * <p><b>两种包都要认</b>：</p>
     * <ul>
     *   <li>完整的 {@code *.theme}（几千万字节）：外层是各模块的压缩包 + icons +
     *       wallpaper，SystemUI 的资源在<b>嵌套</b>的 {@code com.android.systemui}
     *       里。这一层扫下来一个想要的条目都没有，于是再往里下一层；</li>
     *   <li>直接就是 SystemUI 那一层（几兆字节，顶层就是 {@code assets/} 与
     *       {@code res/}）：第一层就能扫到，不用下钻。</li>
     * </ul>
     *
     * @return 条目名（已归一化）→ 字节。缺哪张就是哪张没找到
     */
    public static Map<String, byte[]> readEntries(InputStream in, Set<String> wanted)
            throws Exception {
        Map<String, byte[]> found = new LinkedHashMap<String, byte[]>();
        byte[] subPackage = scan(in, wanted, found);
        if (!found.isEmpty() || subPackage == null) {
            return found;
        }
        // 外层什么都没扫到：拿刚抠出来的 SystemUI 子包再扫一遍
        found.clear();
        scan(new ByteArrayInputStream(subPackage), wanted, found);
        return found;
    }

    /**
     * 扫一层压缩包：把 {@code wanted} 命中的条目写进 {@code found}，
     * 并返回 SystemUI 子包的字节（没有就是 null）。
     *
     * <p>子包只在外层为空时才需要，所以第一层扫完发现有命中就不再往里看——
     * 但子包还是照抠，代价只是那一次读取。</p>
     */
    private static byte[] scan(InputStream in, Set<String> wanted, Map<String, byte[]> found)
            throws Exception {
        byte[] subPackage = null;
        boolean exactSub = false;
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(in));
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = normalize(entry.getName());
                    if (wanted.contains(name)) {
                        found.put(name, readAll(zip));
                    } else if (!exactSub && isSystemUiPackage(name)) {
                        // 主题包自己那层：com.android.systemui（themeInfo.xml 里声明的
                        // 就是不带后缀的 version 001）。带后缀的变体（_002）只在
                        // 正主没出现时兜底，所以真读到正主时直接覆盖
                        subPackage = readAll(zip);
                        exactSub = SUB_PACKAGE.equals(baseName(name));
                    }
                }
                zip.closeEntry();
                if (found.size() >= wanted.size()) {
                    break;
                }
            }
        } finally {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
        return subPackage;
    }

    /** 条目名是不是 SystemUI 那层子包（{@code com.android.systemui} 或它的 {@code _002} 变体）。 */
    private static boolean isSystemUiPackage(String name) {
        return baseName(name).startsWith(SUB_PACKAGE);
    }

    /** 去掉目录前缀，只留最后一段名字。 */
    private static String baseName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /**
     * 把一个条目解成位图。
     *
     * <p>nine-patch 要先把四周 1px 的拉伸标记边框裁掉：那圈黑边会被
     * {@code BitmapFactory} 当成普通像素解出来，直接用会在底图上看到一圈黑线。
     * 裁边必须在降采样<b>之前</b>做——{@code inSampleSize} 是 2 的幂，先缩的话这 1px
     * 会变成半像素糊进图里。好在 .9 素材本身很小（音量胶囊那张才 134×134），
     * 按原尺寸解出来再裁不会有什么代价。</p>
     *
     * @param maxSide 最长边上限；素材都在 1020px 以内，正常不会真的缩
     * @return 解码失败返回 null
     */
    public static Bitmap decode(byte[] raw, int maxSide, boolean ninePatch) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = ninePatch
                ? 1 : sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide);
        Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        if (bitmap == null || !ninePatch) {
            return bitmap;
        }
        if (bitmap.getWidth() <= 2 || bitmap.getHeight() <= 2) {
            return bitmap;
        }
        Bitmap cropped = Bitmap.createBitmap(bitmap, 1, 1,
                bitmap.getWidth() - 2, bitmap.getHeight() - 2);
        if (cropped != bitmap) {
            bitmap.recycle();
        }
        return cropped;
    }

    /**
     * 编码成 PNG。
     *
     * <p>不能用相册那条链路的 JPEG：这批主题底图大片透明（1×1 是正圆 + 透明四角、
     * 清除按钮是圆环、通知卡是白底），JPEG 没有 alpha 通道，透明处会变成<b>黑块</b>。</p>
     */
    public static byte[] toPng(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    /** 2 的幂降采样倍率，与 {@code SettingsActivity#decodeScaled} 同一套算法。 */
    private static int sampleSizeFor(int width, int height, int maxSide) {
        int sample = 1;
        while (width / sample > maxSide || height / sample > maxSide) {
            sample <<= 1;
        }
        return sample;
    }

    /** 条目名归一化：统一小写、去掉 {@code ./} 与前导 {@code /}，兼容不同打包器。 */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String result = name.replace('\\', '/').toLowerCase(Locale.US);
        while (result.startsWith("./")) {
            result = result.substring(2);
        }
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    /** 把一个条目的内容读干（读到条目末尾为止）。 */
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
