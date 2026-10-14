package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 「磁贴长按选项替换」的配置存储：<b>形状分组开关</b> + <b>每形状样式</b>。
 *
 * <p>分组口径是磁贴的真实形状（SpanSize），不是逐磁贴：
 * 1×1 圆形磁贴 / 2×1 宽卡片 / 2×1 三阶段卡片。</p>
 *
 * <p>每个形状的可见度与底图都按<b>「开 / 关」两套</b>分开存：磁贴开着时用「开」那套，
 * 关着时用「关」那套，这样系统的「激活=浅色块 / 未激活=深灰块」区分不会被一层底图拍平。
 * 圆角是形状级的，开/关共用。</p>
 *
 * <p><b>两张 2×1 宽卡片与 2×2 大卡片例外</b>：它们没有「开 / 关」两种观感
 * （2×1 卡片与媒体大卡片都是常亮的深色卡），分开 / 关两套没意义，
 * 所以合并成一套（恒用「开」那套），见 {@link #hasStateVariants(String)}。</p>
 *
 * <p>2×2 大卡片（媒体卡片）在 ColorOS 16 里走独立面板，长按不经过
 * {@code QSTileImpl}，由 {@link TileLongPressInterceptor} 在视图层单独兜住。</p>
 *
 * <p>弹窗与长按拦截逻辑都跑在 SystemUI 进程里，所以直接存 SystemUI 自己的
 * SharedPreferences 即可，读写同进程，不涉及跨进程权限问题。
 * 底图图片不同——图片由模块 App 读相册后写入模块自己的 prefs，
 * 见 {@link HookPrefs#shapeBgImage(String)}。</p>
 *
 * <p>存储格式（磁贴底图按「开 / 关」分开存，键是 {@code shapeBgImage_<形状>_on/_off}）：</p>
 * <pre>
 * tileLongPressBlockedShapes = {"1x1":true,"2x1":false}
 * tileLongPressShapeStyle    = {"1x1":{"corner":-1,"alphaOn":100,"alphaOff":100}}
 * </pre>
 */
public final class TileLongPressConfig {

    private static final String TAG = "SystemUIHook/LongPress";
    private static final String PREFS_NAME = "systemui_hook_ui";
    private static final String KEY_BLOCKED = "tileLongPressBlockedShapes";
    private static final String KEY_STYLE = "tileLongPressShapeStyle";
    private static final String KEY_BG_BLOCKED = "qsBackgroundLongPressBlocked";
    private static final String KEY_BG_TRANSPARENCY = "qsBackgroundTransparencyPct";
    private static final String KEY_BG_BLUR_OFF = "qsBackgroundBlurOff";
    /**
     * 「颜色区分」的开关。
     *
     * <p>2×1 宽卡片与三阶段卡片的图标底下那块色块，要和卡片底色拉开一点差别才不显得
     * 「贴上去」。暗色模式下把自定义底色往黑里偏一点（{@link #COLOR_DIFF_PCT_DARK}%）、
     * 亮色模式下往白里偏一点（{@link #COLOR_DIFF_PCT_LIGHT}%），只动 RGB、
     * 色相与不透明度都不变。默认开。</p>
     */
    private static final String KEY_COLOR_DIFF = "iconColorDiff";
    /** 控制中心背景的纯色（ARGB）；没设 = 交还系统，见 {@link #qsBackgroundColor}。 */
    private static final String KEY_BG_COLOR = "qsBackgroundColor";
    /** 每形状样式里「开」「关」两套可见度的字段名，以及旧版的单值字段。 */
    private static final String KEY_ALPHA_ON = "alphaOn";
    private static final String KEY_ALPHA_OFF = "alphaOff";
    private static final String KEY_ALPHA_LEGACY = "alpha";
    /** 每形状样式里「图标底色」与「底色不透明度」的字段名。 */
    private static final String KEY_ICON_COLOR = "iconColor";
    private static final String KEY_ICON_ALPHA = "iconAlpha";
    /**
     * 每形状样式里「图标着色（激活 / 关）」与「文字颜色」的字段名。
     *
     * <p>三项各存「纯色值 + 不透明度」两段，理由同 {@link #KEY_ICON_COLOR}：
     * 拖不透明度滑杆时不该把已经挑好的颜色一起丢掉。</p>
     */
    private static final String KEY_TINT_ON = "tintOn";
    private static final String KEY_TINT_ON_ALPHA = "tintOnAlpha";
    private static final String KEY_TINT_OFF = "tintOff";
    private static final String KEY_TINT_OFF_ALPHA = "tintOffAlpha";
    private static final String KEY_LABEL_COLOR = "labelColor";
    private static final String KEY_LABEL_ALPHA = "labelAlpha";
    /**
     * 亮度 / 音量胶囊「进度块」自己的可见度字段名。
     *
     * <p>胶囊的背景底图和进度底图是两张图、两页配置，可见度也得分开：
     * 共用 {@link #KEY_ALPHA_ON} 的话，调进度图的浓淡会把胶囊底图一起带偏。</p>
     */
    private static final String KEY_PROGRESS_ALPHA = "progAlpha";
    /** 每形状样式里「圆角」与「主题包素材圆角比例」的字段名。 */
    private static final String KEY_CORNER = "corner";
    private static final String KEY_CORNER_RATIO = "cornerRatio";
    /** 每形状底图键的后缀：开 / 关。 */
    private static final String IMAGE_SUFFIX_ON = "_on";
    private static final String IMAGE_SUFFIX_OFF = "_off";

    /** 1×1 圆形磁贴（手电筒/定位服务/飞行模式…）。 */
    public static final String SHAPE_1X1 = "1x1";
    /**
     * 方形卡片：ColorOS 自带的「耳机」「设备空间」这类 deviceplugin 卡片。
     *
     * <p>占位和 1×1 磁贴差不多大，但它是<b>圆角矩形卡片</b>、不是正圆：底图要另一套，
     * 圆角也不能按「短边一半」算，所以单列一组。</p>
     */
    public static final String SHAPE_CARD = "card";
    /**
     * 横向的“1×2 设备卡片”：没有设备预连接时 {@link #SHAPE_CARD} 会变成的那张宽卡。
     *
     * <p>设备卡片是 deviceplugin 的 5 个类，光看包名前缀只能认出「是设备卡片」，
     * 认不出是方形还是横向：{@code Square*} 是 1:1 的方形卡（{@link #SHAPE_CARD}），
     * {@code Rectangle*} 与 {@code NoDeviceEntranceCardView} 是跨两列的宽卡（本形状）。
     * 两者尺寸差一倍，套同一张底图必然有一边被中心裁掉，所以按类名再拆一组。</p>
     *
     * <p>形状语义与 {@link #SHAPE_CARD} 完全一致（圆角矩形卡片、不分开 / 关两套、
     * 走同一条 deviceplugin 卡片长按链路），<b>配置也共用同一格</b>——底图、圆角与
     * 图标着色都由 {@link #sharedShape(String)} 归一到方形卡片，见那里的说明。</p>
     */
    public static final String SHAPE_CARD_WIDE = "cardWide";
    /** 2×1 宽卡片（WLAN/蓝牙这种大卡片）。 */
    public static final String SHAPE_2X1 = "2x1";
    /** 2×2 大卡片（网格里跨两列的方形磁贴）。 */
    public static final String SHAPE_2X2 = "2x2";
    /** 2×1 三阶段卡片（铃铛/震动/静音这种一张卡里三个图标）。 */
    public static final String SHAPE_THREE = "three";
    /**
     * 通知栏的通知卡片。
     *
     * <p>它不是磁贴，背景由 {@code NotificationBackgroundView} 自己画（不走
     * {@code View#setBackground}），所以底图落点也是单独一套，见
     * {@link NotificationHook}。</p>
     */
    public static final String SHAPE_NOTIFICATION = "notification";
    /**
     * 通知栏底部那颗「清除全部」圆形按钮（叉）。
     *
     * <p>它不是磁贴：背景由 {@code ClearAllController.getPlatformBlurDrawable} 自己造，
     * 每次下拉还会被协程重设一遍，所以底图落点单独一套，见 {@link ClearAllButtonHook}。</p>
     */
    public static final String SHAPE_CLEAR = "clear";
    /**
     * 亮度 / 音量竖向胶囊（1 列 × 2 行）。
     *
     * <p>它不是磁贴，是 {@code OplusQsToggleSliderLayout} 里一个自绘的
     * {@code OplusQsVerticalSeekBar}：深色胶囊底和白色进度都是 {@code onDraw} 里
     * 直接画的，没有 {@code View#setBackground} 这个落点，所以底图也单独一套，
     * 见 {@link SliderHook}。</p>
     */
    public static final String SHAPE_SLIDER = "slider";
    /**
     * 控制中心右上角那排小圆钮：注入的 ★ 与系统自带的 ✎ / ⚙ / ⋮。
     *
     * <p>它们和 1×1 磁贴一样是正圆、一样大，但<b>不是磁贴</b>：没有开 / 关两态、
     * 没有标签、屏幕上就固定那几颗，改样式得直接套在按钮视图上
     * （见 {@link QuickEntranceButtonHook}）。所以单列一组，不跟 1×1 磁贴混在一起——
     * 混在一起的话改磁贴的圆角 / 底图会顺带把这排按钮也改了。</p>
     */
    public static final String SHAPE_ENTRANCE = "entrance";

    /** 可替换长按的分组（弹窗里展示的顺序）。 */
    public static final String[] ALL_SHAPES = {
            SHAPE_1X1, SHAPE_ENTRANCE, SHAPE_CARD, SHAPE_CARD_WIDE, SHAPE_2X1, SHAPE_2X2,
            SHAPE_THREE, SHAPE_NOTIFICATION, SHAPE_CLEAR, SHAPE_SLIDER,
    };

    private static final String[][] LABELS = {
            {SHAPE_1X1, "1×1 圆形磁贴"},
            {SHAPE_ENTRANCE, "1×1 圆形小磁贴"},
            {SHAPE_CARD, "1×1 方形卡片"},
            {SHAPE_CARD_WIDE, "1×2 设备卡片"},
            {SHAPE_2X1, "2×1 宽卡片"},
            {SHAPE_2X2, "2×2 磁贴"},
            {SHAPE_THREE, "2×1 三阶段卡片"},
            {SHAPE_NOTIFICATION, "通知卡片"},
            {SHAPE_CLEAR, "清除按钮"},
            {SHAPE_SLIDER, "亮度 / 音量胶囊"},
    };

    /**
     * 支持调「图标底色」的形状。
     *
     * <p>这几类的图标底下都压着一块独立色块：2×1 宽卡片是 {@code OplusQSIconView}
     * 的遮罩块，2×1 三阶段卡片是 {@code OplusQSThreeStageLayout} 里跟着高亮图标滑动的
     * 指示块（见 {@link IconColorHook}）。</p>
     *
     * <p>亮度 / 音量胶囊<b>不在</b>这里：它那块进度是系统自己画的（{@code mProgressPaint}），
     * 颜色连混色主题的取色一起归系统，模块只对齐它的圆角，见
     * {@link SliderHook#alignProgressRadius}。</p>
     */
    public static final String[] ICON_COLOR_SHAPES = PrefKeys.ICON_COLOR_SHAPES;

    /**
     * 支持调「图标着色 / 文字颜色」的形状（主控制中心磁贴 + 方形设备卡片）。
     *
     * <p>着色落点与「图标底色」完全不同：底色改的是图标<b>底下</b>那块色块的
     * {@code GradientDrawable}，着色改的是图标<b>本身</b>的 {@code ImageView}
     * tint（见 {@link TileColorHook}），文字颜色则落在标签 TextView 上。</p>
     */
    public static final String[] TINT_SHAPES = PrefKeys.TINT_SHAPES;

    /** 着色对象：图标底色（就是原来的「图标底色」那一项）。 */
    public static final int TINT_ICON_BG = 0;
    /** 着色对象：磁贴图标（激活时）。 */
    public static final int TINT_ICON_ON = 1;
    /** 着色对象：磁贴图标（关闭时）。 */
    public static final int TINT_ICON_OFF = 2;
    /** 着色对象：磁贴标签文字。 */
    public static final int TINT_LABEL = 3;

    /** 该形状能不能调图标着色 / 文字颜色。 */
    public static boolean supportsTint(String shape) {
        if (shape == null) {
            return false;
        }
        for (String s : TINT_SHAPES) {
            if (s.equals(shape)) {
                return true;
            }
        }
        return false;
    }

    /** 着色对象在各形状里的叫法。 */
    public static String tintLabel(int kind) {
        if (kind == TINT_ICON_ON) {
            return "图标激活";
        }
        if (kind == TINT_ICON_OFF) {
            return "图标着色";
        }
        if (kind == TINT_LABEL) {
            return "文字颜色";
        }
        return "图标底色";
    }

    /**
     * 着色对象在<b>这个形状</b>里的叫法。
     *
     * <p>开 / 关两态真的是两副面孔的只有三阶段卡片（选中那一段、未选中那两段各一色），
     * 所以「图标激活」这一项<b>只</b>在三阶段的面板里出现。其余形状（1×1 圆形、2×1 宽卡片、
     * 1×1 方形卡片、2×2 媒体卡片、亮度 / 音量胶囊）的图标只有一副面孔，面板里只有一项，
     * 名字统一叫「图标颜色」，开 / 关两态都吃它。</p>
     */
    public static String tintLabel(String shape, int kind) {
        if (SHAPE_NOTIFICATION.equals(shape) && kind == TINT_LABEL) {
            // 通知卡片的「文字颜色」落在通知的标题与正文上，叫「字体颜色」更直白
            return "字体颜色";
        }
        if (!SHAPE_THREE.equals(shape)
                && (kind == TINT_ICON_ON || kind == TINT_ICON_OFF)) {
            return "图标颜色";
        }
        return tintLabel(kind);
    }

    /** 着色对象的说明文案。 */
    public static String tintHint(int kind) {
        if (kind == TINT_ICON_ON) {
            return "磁贴开启时图标本身的颜色，选「跟随系统」还原";
        }
        if (kind == TINT_ICON_OFF) {
            return "磁贴关闭时图标本身的颜色，选「跟随系统」还原";
        }
        if (kind == TINT_LABEL) {
            return "磁贴标签文字的颜色，选「跟随系统」还原";
        }
        return "压在图标底下那块色块的底色，选「跟随系统」还原";
    }

    /** 说明文案；口径同 {@link #tintLabel(String, int)}。 */
    public static String tintHint(String shape, int kind) {
        if (SHAPE_NOTIFICATION.equals(shape) && kind == TINT_LABEL) {
            return "通知的标题与正文的颜色，选「跟随系统」还原";
        }
        if (!SHAPE_THREE.equals(shape)
                && (kind == TINT_ICON_ON || kind == TINT_ICON_OFF)) {
            return "图标本身的颜色，开 / 关都用它，选「跟随系统」还原";
        }
        return tintHint(kind);
    }

    /** 图标底色：跟随系统（不覆盖）。刻意避开 {@code 0x00000000}，那个值要留给「透明黑」。 */
    public static final int ICON_COLOR_FOLLOW_SYSTEM = -1;
    /** 图标底色不透明度默认值（%）。 */
    public static final int DEFAULT_ICON_ALPHA_PCT = 100;

    /** 「颜色区分」默认开关（2×1 / 三阶段）。 */
    public static final boolean DEFAULT_COLOR_DIFF = true;
    /** 「颜色区分」暗色模式的幅度（%）：固定值，往黑偏，不提供调节。 */
    public static final int COLOR_DIFF_PCT_DARK = 5;
    /** 「颜色区分」亮色模式的幅度（%）：固定值，往白偏，比暗色那档大一些才看得出。 */
    public static final int COLOR_DIFF_PCT_LIGHT = 10;

    /** 该形状能不能调图标底色。 */
    public static boolean supportsIconColor(String shape) {
        if (shape == null) {
            return false;
        }
        for (String s : ICON_COLOR_SHAPES) {
            if (s.equals(shape)) {
                return true;
            }
        }
        return false;
    }

    /** 圆角取值：跟随系统形状（1×1 保持圆形，其余形状按系统圆角）。 */
    public static final int CORNER_FOLLOW_SYSTEM = -1;
    /** 圆角滑杆上限（dp）。 */
    public static final int CORNER_MAX_DP = 40;
    /**
     * 「跟随系统」时除 1×1 以外所有形状用的圆角（dp）。
     *
     * <p>1×1 保持正圆（返回 {@code -1}，按短边一半画）；方形卡片、2×1 宽卡片、
     * 2×2 媒体卡片、三阶段卡片、亮度 / 音量胶囊、通知卡片都是圆角矩形，统一用这个值
     * ——它们不能跟着 1×1 走「短边一半」那条规则，否则底图会被画成胶囊 / 正圆，
     * 跟磁贴本体对不上。</p>
     *
     * <p>已经存过显式圆角的形状不受影响，要吃到这个新默认值得在样式面板里重置回
     * 「跟随系统」。</p>
     */
    public static final int FOLLOW_SYSTEM_DP = 20;
    /** 背景可见度对应的存储值下限：-100，也就是可见度 200%。 */
    public static final int MIN_BG_TRANSPARENCY_PCT = -100;
    /** 背景可见度对应的存储值上限：100，也就是可见度 0%。 */
    public static final int MAX_BG_TRANSPARENCY_PCT = 100;
    /** 可见度默认值（%）。 */
    public static final int DEFAULT_ALPHA_PCT = 100;

    /** 单个形状的样式。 */
    public static final class Style {
        /** 形状标识。 */
        public final String shape;
        /** 是否在长按样式面板里调过（圆角/可见度）。底图是否设置看 {@link HookPrefs}。 */
        public final boolean touched;
        /** 圆角 dp；{@link #CORNER_FOLLOW_SYSTEM} 表示跟随系统。 */
        public final int cornerDp;
        /** 「开」时的可见度 %（0–{@link TileBackgroundDrawable#MAX_USER_ALPHA_PCT}）。 */
        public final int alphaOnPct;
        /** 「关」时的可见度 %（0–{@link TileBackgroundDrawable#MAX_USER_ALPHA_PCT}）。 */
        public final int alphaOffPct;
        /** 图标底色的纯色值（不带 alpha）；{@link #ICON_COLOR_FOLLOW_SYSTEM} 表示跟随系统。 */
        public final int iconColor;
        /** 图标底色的不透明度 %。 */
        public final int iconAlphaPct;
        /**
         * 主题包素材自带的圆角比例（圆角像素 ÷ 素材宽度）；0 表示没导入过。
         *
         * <p>只在 {@link #cornerDp} 是 {@link #CORNER_FOLLOW_SYSTEM} 时才生效——
         * 用户在样式面板里手动拖过圆角的话，那个显式值优先。</p>
         */
        public final float cornerRatio;
        /**
         * 亮度 / 音量胶囊「进度块」自己的可见度 %。
         *
         * <p>只有 {@link #SHAPE_SLIDER} 用得上；其它形状读到的就是默认值。
         * 胶囊的背景底图与进度底图分两页配置，可见度也各管各的。</p>
         */
        public final int progressAlphaPct;
        /** 图标着色（激活时）的纯色值；{@link #ICON_COLOR_FOLLOW_SYSTEM} 表示跟随系统。 */
        public final int tintOn;
        /** 图标着色（激活时）的不透明度 %。 */
        public final int tintOnAlphaPct;
        /** 图标着色（关闭时）的纯色值；{@link #ICON_COLOR_FOLLOW_SYSTEM} 表示跟随系统。 */
        public final int tintOff;
        /** 图标着色（关闭时）的不透明度 %。 */
        public final int tintOffAlphaPct;
        /** 标签文字颜色的纯色值；{@link #ICON_COLOR_FOLLOW_SYSTEM} 表示跟随系统。 */
        public final int labelColor;
        /** 标签文字颜色的不透明度 %。 */
        public final int labelAlphaPct;

        Style(String shape, boolean touched, int cornerDp, int alphaOnPct, int alphaOffPct) {
            this(shape, touched, cornerDp, alphaOnPct, alphaOffPct,
                    ICON_COLOR_FOLLOW_SYSTEM, DEFAULT_ICON_ALPHA_PCT, 0f);
        }

        Style(String shape, boolean touched, int cornerDp, int alphaOnPct, int alphaOffPct,
                int iconColor, int iconAlphaPct) {
            this(shape, touched, cornerDp, alphaOnPct, alphaOffPct,
                    iconColor, iconAlphaPct, 0f);
        }

        Style(String shape, boolean touched, int cornerDp, int alphaOnPct, int alphaOffPct,
                int iconColor, int iconAlphaPct, float cornerRatio) {
            this(shape, touched, cornerDp, alphaOnPct, alphaOffPct,
                    iconColor, iconAlphaPct, cornerRatio, DEFAULT_ALPHA_PCT);
        }

        Style(String shape, boolean touched, int cornerDp, int alphaOnPct, int alphaOffPct,
                int iconColor, int iconAlphaPct, float cornerRatio, int progressAlphaPct) {
            this(shape, touched, cornerDp, alphaOnPct, alphaOffPct,
                    iconColor, iconAlphaPct, cornerRatio, progressAlphaPct,
                    ICON_COLOR_FOLLOW_SYSTEM, DEFAULT_ICON_ALPHA_PCT,
                    ICON_COLOR_FOLLOW_SYSTEM, DEFAULT_ICON_ALPHA_PCT,
                    ICON_COLOR_FOLLOW_SYSTEM, DEFAULT_ICON_ALPHA_PCT);
        }

        Style(String shape, boolean touched, int cornerDp, int alphaOnPct, int alphaOffPct,
                int iconColor, int iconAlphaPct, float cornerRatio, int progressAlphaPct,
                int tintOn, int tintOnAlphaPct, int tintOff, int tintOffAlphaPct,
                int labelColor, int labelAlphaPct) {
            this.shape = shape;
            this.touched = touched;
            this.cornerDp = cornerDp;
            this.alphaOnPct = alphaOnPct;
            this.alphaOffPct = alphaOffPct;
            this.iconColor = iconColor;
            this.iconAlphaPct = iconAlphaPct;
            this.cornerRatio = cornerRatio > 0f ? cornerRatio : 0f;
            this.progressAlphaPct = progressAlphaPct;
            this.tintOn = tintOn;
            this.tintOnAlphaPct = tintOnAlphaPct;
            this.tintOff = tintOff;
            this.tintOffAlphaPct = tintOffAlphaPct;
            this.labelColor = labelColor;
            this.labelAlphaPct = labelAlphaPct;
        }

        /** 指定状态（开/关）下的可见度 %。 */
        public int alphaPct(boolean active) {
            return stateOf(shape, active) ? alphaOnPct : alphaOffPct;
        }

        /** 是否设了图标底色（跟随系统时为 false）。 */
        public boolean hasIconColor() {
            return iconColor != ICON_COLOR_FOLLOW_SYSTEM;
        }

        /** 图标底色折算不透明度后的 ARGB；跟随系统时返回 {@link #ICON_COLOR_FOLLOW_SYSTEM}。 */
        public int iconArgb() {
            return argbOf(iconColor, iconAlphaPct);
        }

        /**
         * 某个着色对象（{@link #TINT_ICON_BG} / {@link #TINT_ICON_ON} /
         * {@link #TINT_ICON_OFF} / {@link #TINT_LABEL}）的纯色值。
         */
        public int tintColor(int kind) {
            if (kind == TINT_ICON_BG) {
                return iconColor;
            }
            if (kind == TINT_ICON_ON) {
                return tintOn;
            }
            if (kind == TINT_LABEL) {
                return labelColor;
            }
            return tintOff;
        }

        /** 某个着色对象的不透明度 %。 */
        public int tintAlphaPct(int kind) {
            if (kind == TINT_ICON_BG) {
                return iconAlphaPct;
            }
            if (kind == TINT_ICON_ON) {
                return tintOnAlphaPct;
            }
            if (kind == TINT_LABEL) {
                return labelAlphaPct;
            }
            return tintOffAlphaPct;
        }

        /** 是否设置了会改变底图外观的项（圆角或可见度）。 */
        public boolean hasLook() {
            return touched;
        }
    }

    /** SystemUI 进程内单例 prefs（同进程读写，无需跨进程权限）。 */
    private static volatile SharedPreferences sPrefs;

    /** 「压掉背景模糊」的进程内缓存，见 {@link #bgBlurOff()}。 */
    private static volatile boolean sBgBlurOff;
    private static volatile boolean sBgBlurOffLoaded;

    /** 「颜色区分」开关的进程内缓存，见 {@link #colorDiff()}。 */
    private static volatile boolean sColorDiff = DEFAULT_COLOR_DIFF;
    private static volatile boolean sColorDiffLoaded;

    private TileLongPressConfig() {
    }

    private static SharedPreferences prefs(Context ctx) {
        SharedPreferences cached = sPrefs;
        if (cached != null) {
            return cached;
        }
        Context app = ctx.getApplicationContext();
        if (app == null) {
            app = ctx;
        }
        cached = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sPrefs = cached;
        return cached;
    }

    /**
     * 把还没落盘的改动同步写进文件。
     *
     * <p>圆角 / 可见度 / 长按开关这些走的是 {@code apply()}（异步写），紧接着
     * 杀进程重启 SystemUI 的话，那次异步写很可能还没落到文件上，配置就白改了。
     * 空 {@code commit()} 会把整个 map 同步写一遍，正是这里要的。</p>
     */
    public static void flush(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().commit();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 清空本模块在 SystemUI 进程里的全部配置。
     *
     * <p>{@link #PREFS_NAME} 这个文件只有本模块在用（长按开关、按形状的圆角 /
     * 可见度 / 底图、控制中心背景参数都在这），所以整体 {@code clear()} 就是
     * 「重置所有配置」想要的效果。</p>
     *
     * <p>必须由 SystemUI 侧执行：模块设置页写的是另一个文件
     * （{@code SystemUIHookPrefs}），在那边清这个文件既读不到也写不了。</p>
     */
    public static void clearAll(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            // 同步 commit：清完马上要刷新视图，异步写会让刷新读到旧值
            prefs(ctx).edit().clear().commit();
            sBgBlurOff = false;
            sBgBlurOffLoaded = false;
            // 颜色区分缓存也作废，回到默认值
            sColorDiff = DEFAULT_COLOR_DIFF;
            sColorDiffLoaded = false;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": clearAll failed - " + t);
        }
    }

    // ------------------------------------------------------------ 整份配置快照

    /**
     * 本模块 SystemUI prefs 里<b>除底图以外</b>的全部键值，供导出主题包用。
     *
     * <p>为什么要整份快照：主题包的标准格式只装得下 5 条颜色 + 一批底图，而模块的
     * 修改项远不止这些（每个形状的圆角 / 可见度、图标底色与四种着色的不透明度、
     * 控制中心背景色、各式开关）。与其为每一项都定义一段格式，不如把这一层的键值
     * 原样记下来——将来新增配置项也自动带上，不用再改导出代码。</p>
     *
     * <p>底图（{@link PrefKeys#KEY_SHAPE_BG_IMAGE_PREFIX} 那几个键）<b>排除在外</b>：
     * 它们已经走图片字节通道，再塞进快照会让体积翻几倍。</p>
     *
     * @return 键 → 原值（String / Integer / Long / Float / Boolean）；读不到返回空表
     */
    public static java.util.Map<String, Object> snapshotNonImage(Context ctx) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        if (ctx == null) {
            return out;
        }
        try {
            for (java.util.Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
                String key = e.getKey();
                if (key == null || key.startsWith(PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX)) {
                    continue;
                }
                out.put(key, e.getValue());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": snapshot failed - " + t);
        }
        return out;
    }

    /**
     * 把一份快照原样倒回本模块的 prefs（导入主题包用）。
     *
     * <p><b>先清空除底图以外的键，再写快照</b>：快照是导出那一刻的完整状态，用户后来
     * 加过、而包里没有的项应该被抹掉，否则「导入」会变成「合并」，还原出来的样式不是
     * 包里那一份。底图不清——它们由图片通道另送，清掉会把刚写进来的图误删。</p>
     *
     * <p>同步 {@code commit()}：写完马上就要重启 SystemUI，异步写可能还没落盘。</p>
     *
     * @param values 见 {@link #snapshotNonImage(Context)}
     * @return 写进去几条
     */
    public static int applySnapshot(Context ctx, java.util.Map<String, Object> values) {
        if (ctx == null || values == null) {
            return 0;
        }
        try {
            SharedPreferences.Editor editor = prefs(ctx).edit();
            for (String key : prefs(ctx).getAll().keySet()) {
                if (key != null && key.startsWith(PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX)) {
                    continue;
                }
                editor.remove(key);
            }
            int written = 0;
            for (java.util.Map.Entry<String, Object> e : values.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                if (key == null || key.length() == 0 || value == null) {
                    continue;
                }
                if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    editor.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    editor.putFloat(key, (Float) value);
                } else if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else {
                    // 其余类型（StringSet 之类）本模块没用，跳过并说一声
                    XposedBridge.log(TAG + ": snapshot skip " + key + " (" + value.getClass() + ")");
                    continue;
                }
                written++;
            }
            editor.commit();
            // 「压掉背景模糊」那份缓存得作废，否则导入后仍按旧值走
            sBgBlurOff = false;
            sBgBlurOffLoaded = false;
            // 「颜色区分」同理
            sColorDiffLoaded = false;
            return written;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applySnapshot failed - " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------ ★ 入口按钮

    /**
     * 控制中心那颗 ★ 入口按钮是否显示。
     *
     * <p>存这里而不是读模块 App 的 prefs：模块设置页和 SystemUI 是两个进程，
     * 跨进程读 {@code XSharedPreferences} 在这台机器上实测不工作（磁贴底图、
     * 全局参数那几个开关当初没效果就是这个原因）。所以开关的新值由设置页发
     * 广播时作为 {@link PrefKeys#EXTRA_ENTRANCE_VISIBLE} 带过来，写进本进程
     * 自己的 prefs，见 {@link TileImageChannel}。</p>
     */
    private static final String KEY_ENTRANCE_VISIBLE = "entranceButtonVisible";

    /** ★ 入口按钮是否显示。默认显示。 */
    public static boolean isEntranceVisible(Context ctx) {
        if (ctx == null) {
            return PrefKeys.DEF_SHOW_ENTRANCE_BUTTON;
        }
        try {
            return prefs(ctx).getBoolean(KEY_ENTRANCE_VISIBLE, PrefKeys.DEF_SHOW_ENTRANCE_BUTTON);
        } catch (Throwable t) {
            return PrefKeys.DEF_SHOW_ENTRANCE_BUTTON;
        }
    }

    public static void setEntranceVisible(Context ctx, boolean visible) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putBoolean(KEY_ENTRANCE_VISIBLE, visible).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": entrance visible write failed - " + t);
        }
    }

    // ------------------------------------------------------------ 长按拦截开关

    /** 该形状的长按是否已被拦截（即长按不再打开系统设置/应用，而是弹样式面板）。 */
    public static boolean isBlocked(Context ctx, String shape) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return false;
        }
        try {
            JSONObject all = readAll(ctx, KEY_BLOCKED);
            if (SHAPE_CARD.equals(shape) && !all.has(shape)) {
                // 方形卡片是从 1×1 里拆出来的一组：用户还没单独设过就跟着 1×1 走。
                // 不继承的话，升级后这两块卡片的长按会突然又变回打开系统应用
                return all.optBoolean(SHAPE_1X1, false);
            }
            if (SHAPE_CARD_WIDE.equals(shape) && !all.has(shape)) {
                // 1×2 设备卡片又是从方形卡片里拆出来的：没单独设过就跟着方形卡片走
                // （方形卡片自己没设过时还会再往上继承 1×1），保证升级后长按行为不变
                return all.has(SHAPE_CARD)
                        ? all.optBoolean(SHAPE_CARD, false)
                        : all.optBoolean(SHAPE_1X1, false);
            }
            if (SHAPE_SLIDER.equals(shape) && !all.has(shape)) {
                // 亮度 / 音量胶囊原本写死「长按弹样式面板」，加开关后默认保持原行为，
                // 关掉才是「不接管长按」
                return true;
            }
            if (SHAPE_ENTRANCE.equals(shape) && !all.has(shape)) {
                // 右上角那排小圆钮的长按跟随「编辑模式」总开关（它们在 ★ 弹窗里没有
                // 单独的开关项）。老配置里没有这一格，就看其它形状是不是全开——
                // 全开就是编辑模式打开着，跟着接管；否则保持系统原本的长按行为
                for (String other : ALL_SHAPES) {
                    if (!SHAPE_ENTRANCE.equals(other) && !all.optBoolean(other, false)) {
                        return false;
                    }
                }
                return true;
            }
            if (SHAPE_CLEAR.equals(shape) && !all.has(shape)) {
                // 「清除全部」按钮同理：它的底图就是在这里改的，长按必须开箱可用，
                // 默认接管；不想要这颗按钮长按弹面板的，到 ★ 弹窗里关掉
                return true;
            }
            return all.optBoolean(shape, false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": read failed - " + t);
            return false;
        }
    }

    /** 设置某个形状的长按拦截开关。 */
    public static void setBlocked(Context ctx, String shape, boolean on) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return;
        }
        try {
            JSONObject all = readAll(ctx, KEY_BLOCKED);
            all.put(shape, on);
            prefs(ctx).edit().putString(KEY_BLOCKED, all.toString()).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": write failed - " + t);
        }
    }

    /**
     * 「编辑模式」是否打开——也就是<b>所有</b>形状的长按都已拦截。
     *
     * <p>★ 弹窗里那一项是全体开关，所以这里就是 {@link #ALL_SHAPES} 全与：
     * 只要有一个形状没被拦（用户以前单独设过，或从没设过），这一项就显示为关。
     * 不单独存一个总开关字段，免得和逐形状的配置打架。</p>
     */
    public static boolean isEditMode(Context ctx) {
        if (ctx == null) {
            return false;
        }
        for (String shape : ALL_SHAPES) {
            if (!isBlocked(ctx, shape)) {
                return false;
            }
        }
        return true;
    }

    /** 一次把 {@link #ALL_SHAPES} 的长按拦截全开 / 全关（★ 弹窗里的「编辑模式」）。 */
    public static void setEditMode(Context ctx, boolean on) {
        if (ctx == null) {
            return;
        }
        try {
            JSONObject all = readAll(ctx, KEY_BLOCKED);
            for (String shape : ALL_SHAPES) {
                all.put(shape, on);
            }
            prefs(ctx).edit().putString(KEY_BLOCKED, all.toString()).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": edit mode write failed - " + t);
        }
    }

    // -------------------------------------------------------------- 每形状样式

    /** 读某个形状的样式；shape 非法时返回 null，未设置过时返回默认值（touched=false）。 */
    public static Style styleOf(Context ctx, String shape) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return null;
        }
        // 1×2 设备卡片读的是 1×1 方形卡片那一格，见 sharedShape
        shape = sharedShape(shape);
        try {
            JSONObject all = readAll(ctx, KEY_STYLE);
            JSONObject obj = all.optJSONObject(shape);
            if (obj == null && SHAPE_CARD.equals(shape)) {
                // 两张卡片合并成同一格之前，长按 1×2 卡片设下的那份样式落在 cardWide 键上。
                // 归一之后只按 card 读会把那份读成「没配过」，看着就是「以前设的色全丢了」，
                // 这里顺手把旧键兜回来。
                obj = all.optJSONObject(SHAPE_CARD_WIDE);
            }
            if (obj == null) {
                return new Style(shape, false, CORNER_FOLLOW_SYSTEM,
                        DEFAULT_ALPHA_PCT, DEFAULT_ALPHA_PCT);
            }
            // 旧配置只存了一个 "alpha"：开/关都按它算，升级后观感不会突变
            int legacy = clampPct(obj.optInt(KEY_ALPHA_LEGACY, DEFAULT_ALPHA_PCT));
            return new Style(shape, true,
                    obj.optInt(KEY_CORNER, CORNER_FOLLOW_SYSTEM),
                    clampPct(obj.optInt(KEY_ALPHA_ON, legacy)),
                    clampPct(obj.optInt(KEY_ALPHA_OFF, legacy)),
                    obj.optInt(KEY_ICON_COLOR, ICON_COLOR_FOLLOW_SYSTEM),
                    clampPct(obj.optInt(KEY_ICON_ALPHA, DEFAULT_ICON_ALPHA_PCT)),
                    (float) obj.optDouble(KEY_CORNER_RATIO, 0d),
                    clampPct(obj.optInt(KEY_PROGRESS_ALPHA, DEFAULT_ALPHA_PCT)),
                    obj.optInt(KEY_TINT_ON, ICON_COLOR_FOLLOW_SYSTEM),
                    clampPct(obj.optInt(KEY_TINT_ON_ALPHA, DEFAULT_ICON_ALPHA_PCT)),
                    obj.optInt(KEY_TINT_OFF, ICON_COLOR_FOLLOW_SYSTEM),
                    clampPct(obj.optInt(KEY_TINT_OFF_ALPHA, DEFAULT_ICON_ALPHA_PCT)),
                    obj.optInt(KEY_LABEL_COLOR, ICON_COLOR_FOLLOW_SYSTEM),
                    clampPct(obj.optInt(KEY_LABEL_ALPHA, DEFAULT_ICON_ALPHA_PCT)));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": style read failed - " + t);
            return null;
        }
    }

    /** 是否任意形状被调过样式（用于决定要不要跑额外的底图刷新逻辑）。 */
    public static boolean anyStyle(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            return readAll(ctx, KEY_STYLE).length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 形状配置的圆角<b>像素</b>半径；真的不用改形状时返回 {@code -1}。
     *
     * <p>磁贴 outline（{@link TileCornerHook}）和自定义底图（{@link TileBackgroundDrawable}）
     * 必须用同一个值：outline 决定磁贴本体的裁剪形状，drawable 决定图片自己的圆角。
     * 两边各算一遍的话，只要有一处口径不同，就会看到「图片圆角」和「磁贴圆角」对不上。</p>
     *
     * <p>除 1×1 以外的形状在「跟随系统」时返回 {@link #FOLLOW_SYSTEM_DP}，
     * 而不是 {@code -1}：它们都是圆角矩形，返回 -1 的话底图会按「短边一半」
     * 画成胶囊形 / 正圆，两者对不上，看着就是跟随系统丢了。</p>
     *
     * <p><b>主题包导入过的形状要先问 {@link #materialCornerRatio}</b>：那里存着素材
     * 自带的圆角比例，得等知道视图多宽了才能折算成像素，本方法给不了那个值。</p>
     *
     * <p>「跟随系统」时优先用 {@link #systemCornerPx} 记下的<b>系统真实半径</b>：
     * 系统给不同形状的圆角并不一样（2×1 宽卡片比 20dp 小一截），统一拿
     * {@link #FOLLOW_SYSTEM_DP} 硬顶会明显比系统的圆。只有还没量到系统值时
     * 才退回那个常量兜底。</p>
     */
    public static float cornerRadiusPx(Context ctx, String shape) {
        if (ctx == null) {
            return -1f;
        }
        Style style = styleOf(ctx, shape);
        if (style == null) {
            return -1f;
        }
        int cornerDp = style.cornerDp;
        if (cornerDp < 0) {
            if (SHAPE_1X1.equals(shape) || SHAPE_CLEAR.equals(shape)
                    || SHAPE_ENTRANCE.equals(shape)) {
                // 1×1 圆形磁贴、「清除全部」圆形按钮、右上角那排小圆钮都保持正圆：
                // -1 让底图按短边一半画
                return -1f;
            }
            float system = systemCornerPx(shape);
            if (system > 0f) {
                return system;
            }
            cornerDp = FOLLOW_SYSTEM_DP;
        }
        return cornerDp * ctx.getResources().getDisplayMetrics().density;
    }

    public static void setCorner(Context ctx, String shape, int cornerDp) {
        writeStyle(ctx, shape, KEY_CORNER, cornerDp);
    }

    /**
     * 系统自己给某形状算的圆角（像素），由 {@code TileCornerHook} 从
     * {@code QsViewOutlineProvider#getRoundParams(View)} 的返回值里截下来。
     *
     * <p>「跟随系统」要真的跟着系统走：系统对 1×1 / 2×1 / 2×2 给的半径并不相同，
     * 而模块侧只有一个 {@link #FOLLOW_SYSTEM_DP} 常量，套到宽卡片上会明显偏圆。
     * 这个值只有系统算过一次之后才有，所以是「先到先用、拿不到再兜底」。</p>
     */
    private static final Map<String, Float> sSystemCornerPx =
            Collections.synchronizedMap(new HashMap<String, Float>());

    /** 记下系统给某形状的圆角像素（见 {@link #systemCornerPx}）。 */
    public static void recordSystemCornerPx(String shape, float radiusPx) {
        if (shape == null || shape.length() == 0 || radiusPx <= 0f) {
            return;
        }
        Float cached = sSystemCornerPx.get(shape);
        if (cached != null && cached.floatValue() == radiusPx) {
            return;
        }
        sSystemCornerPx.put(shape, Float.valueOf(radiusPx));
        XposedBridge.log(TAG + ": system corner for " + shape + " = " + radiusPx + "px");
    }

    /** 系统给某形状的圆角像素；还没量到返回 0。 */
    public static float systemCornerPx(String shape) {
        Float cached = shape == null ? null : sSystemCornerPx.get(shape);
        return cached == null ? 0f : cached.floatValue();
    }

    /**
     * 该形状要用的<b>素材圆角比例</b>（圆角像素 ÷ 素材宽度）；不用按素材走时返回 0。
     *
     * <p>主题包导入时由 {@code ThemePackImporter#cornerRatioOf} 从底图第一行第一个
     * 不透明像素量出来，见 {@link PrefKeys#ACTION_SHAPE_CORNER}。调用方拿到非 0 值后
     * 应该用 {@code 比例 × 视图宽度} 代替 {@link #cornerRadiusPx}——
     * 像素值只有到绘制那一刻、知道磁贴多宽了才算得出来，这里给不了。</p>
     *
     * <p>用户在样式面板里手动拖过圆角的形状<b>不</b>走这条：显式值优先，
     * 只有停在「跟随系统」时才吃素材比例。</p>
     */
    public static float materialCornerRatio(Context ctx, String shape) {
        Style style = styleOf(ctx, shape);
        if (style == null || style.cornerDp != CORNER_FOLLOW_SYSTEM) {
            return 0f;
        }
        return style.cornerRatio;
    }

    /**
     * 写入 / 清除某个形状的素材圆角比例（{@code ratio <= 0} 表示清除）。
     *
     * <p>同时把圆角重置回「跟随系统」：不清的话用户之前拖过的显式值会把刚导入的
     * 素材比例压住，看起来就是「主题包圆角没生效」。一次写两个字段，
     * 免得分两次改 JSON 时中间那次被别的线程读到半套配置。</p>
     */
    public static void setCornerRatio(Context ctx, String shape, float ratio) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return;
        }
        // 1×2 设备卡片写的是 1×1 方形卡片那一格，见 sharedShape
        shape = sharedShape(shape);
        try {
            JSONObject all = readAll(ctx, KEY_STYLE);
            JSONObject obj = all.optJSONObject(shape);
            if (obj == null) {
                obj = new JSONObject();
            }
            if (ratio > 0f) {
                obj.put(KEY_CORNER_RATIO, (double) ratio);
            } else {
                obj.remove(KEY_CORNER_RATIO);
            }
            obj.put(KEY_CORNER, CORNER_FOLLOW_SYSTEM);
            all.put(shape, obj);
            // 同步落盘：导入流程紧接着就要重启 SystemUI，异步写会丢
            prefs(ctx).edit().putString(KEY_STYLE, all.toString()).commit();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": corner ratio write failed - " + t);
        }
    }

    /**
     * 只清掉素材圆角比例，<b>不动</b>用户设的圆角。
     *
     * <p>手动换底图时用：比例是照着上一张素材量出来的，图都换了还留着就会把新图裁成
     * 旧主题的圆角。不能顺手调 {@link #setCornerRatio} 那种「连圆角一起重置」的写法，
     * 那会把用户在样式面板里拖好的值也抹掉。</p>
     */
    public static void clearCornerRatio(Context ctx, String shape) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return;
        }
        try {
            JSONObject all = readAll(ctx, KEY_STYLE);
            JSONObject obj = all.optJSONObject(shape);
            if (obj == null || !obj.has(KEY_CORNER_RATIO)) {
                return;
            }
            obj.remove(KEY_CORNER_RATIO);
            all.put(shape, obj);
            prefs(ctx).edit().putString(KEY_STYLE, all.toString()).commit();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": corner ratio clear failed - " + t);
        }
    }

    /** 写某个形状在指定状态（开/关）下的可见度。 */
    public static void setAlpha(Context ctx, String shape, boolean active, int alphaPct) {
        writeStyle(ctx, shape, stateOf(shape, active) ? KEY_ALPHA_ON : KEY_ALPHA_OFF,
                clampPct(alphaPct));
    }

    /**
     * 写亮度 / 音量胶囊<b>进度块</b>自己的可见度（%）。
     *
     * <p>跟胶囊底图的可见度分开存：两张图、两页配置，混用一个值会让调进度图的
     * 浓淡把胶囊底图一起带偏。</p>
     */
    public static void setProgressAlpha(Context ctx, String shape, int alphaPct) {
        writeStyle(ctx, shape, KEY_PROGRESS_ALPHA, clampPct(alphaPct));
    }

    /**
     * 写某个形状的图标底色。
     *
     * <p>只存纯色值（不含 alpha）：不透明度是同一段里的滑杆，单独存在
     * {@link #KEY_ICON_ALPHA}，这样调不透明度不用重挑颜色。</p>
     */
    public static void setIconColor(Context ctx, String shape, int color) {
        writeStyle(ctx, shape, KEY_ICON_COLOR, color);
    }

    /** 写某个形状的图标底色不透明度（%）。 */
    public static void setIconAlpha(Context ctx, String shape, int alphaPct) {
        writeStyle(ctx, shape, KEY_ICON_ALPHA, clampPct(alphaPct));
    }

    /**
     * 该形状的图标底色（已折算不透明度）；没设置（跟随系统）返回
     * {@link #ICON_COLOR_FOLLOW_SYSTEM}。
     */
    public static int iconColorOf(Context ctx, String shape) {
        Style style = styleOf(ctx, shape);
        return style == null ? ICON_COLOR_FOLLOW_SYSTEM : style.iconArgb();
    }

    /**
     * 该形状某个着色对象的颜色（已折算不透明度）；没设置（跟随系统）返回
     * {@link #ICON_COLOR_FOLLOW_SYSTEM}。
     *
     * <p>{@link #TINT_ICON_BG} 是原来那一项「图标底色」，走 {@link #iconColorOf}
     * 那套字段；其余三项各自一对独立字段。</p>
     */
    public static int tintColorOf(Context ctx, String shape, int kind) {
        if (kind == TINT_ICON_BG) {
            return iconColorOf(ctx, shape);
        }
        Style style = styleOf(ctx, shape);
        return style == null ? ICON_COLOR_FOLLOW_SYSTEM
                : argbOf(style.tintColor(kind), style.tintAlphaPct(kind));
    }

    /** 该形状某个着色对象的不透明度 %（没设过时给默认值）。 */
    public static int tintAlphaOf(Context ctx, String shape, int kind) {
        Style style = styleOf(ctx, shape);
        if (style == null) {
            return DEFAULT_ICON_ALPHA_PCT;
        }
        return kind == TINT_ICON_BG ? style.iconAlphaPct : style.tintAlphaPct(kind);
    }

    /**
     * 某个形状有没有设过「图标激活 / 图标着色」。
     *
     * <p>给 {@code TileColorHook} 里那些挂在<b>高频方法</b>上的钩子当热路径守卫：
     * 方形设备卡片那条 {@code ImageView#setColorFilter} 挂在全系统的 ImageView 上、
     * 亮度图标的 {@code setIconDrawable()} 拖一次亮度条就会走一遍、媒体卡片的
     * {@code setBtnColor()} 每次切歌都会走。这些位置必须先用一句最便宜的判断挡掉绝大多数
     * 调用——没配过色的形状一律一行代码返回，绝不在那里解析 JSON。</p>
     */
    public static boolean tintConfigured(Context ctx, String shape) {
        Style style = styleOf(ctx, shape);
        return style != null && (style.tintOn != ICON_COLOR_FOLLOW_SYSTEM
                || style.tintOff != ICON_COLOR_FOLLOW_SYSTEM);
    }

    /**
     * 写某个形状的着色颜色。
     *
     * <p>只存纯色值（不含 alpha）：不透明度是同一段里的滑杆，单独存一份，
     * 这样调不透明度不用重挑颜色。</p>
     */
    public static void setTintColor(Context ctx, String shape, int kind, int color) {
        if (kind == TINT_ICON_BG) {
            setIconColor(ctx, shape, color);
            return;
        }
        if (sharesOneTint(shape, kind)) {
            writeStyle(ctx, shape, KEY_TINT_ON, color);
            writeStyle(ctx, shape, KEY_TINT_OFF, color);
            return;
        }
        writeStyle(ctx, shape, tintColorKey(kind), color);
    }

    /** 写某个形状的着色不透明度（%）。 */
    public static void setTintAlpha(Context ctx, String shape, int kind, int alphaPct) {
        if (kind == TINT_ICON_BG) {
            setIconAlpha(ctx, shape, alphaPct);
            return;
        }
        if (sharesOneTint(shape, kind)) {
            int pct = clampPct(alphaPct);
            writeStyle(ctx, shape, KEY_TINT_ON_ALPHA, pct);
            writeStyle(ctx, shape, KEY_TINT_OFF_ALPHA, pct);
            return;
        }
        writeStyle(ctx, shape, tintAlphaKey(kind), clampPct(alphaPct));
    }

    /**
     * 「图标激活」与「图标着色」这一项在<b>这个形状</b>里是不是同一格。
     *
     * <p>三阶段以外的形状的图标只有一副面孔，样式面板里只给一项「图标颜色」，
     * 但读取侧有一半链路走的是「激活」那一格（亮度 / 音量图标、2×2 媒体卡片、
     * 方形设备卡片里直读 {@code ..._icon_color_active} 的那颗图标）。写入侧只写一格的话，
     * 另一格里的旧值就会把新颜色顶掉——主题包导入会把两格一起写，于是导入过主题包之后
     * 这几种磁贴就「改不了颜色」了。所以这两种形状两格恒同值：一起写、一起清。</p>
     */
    private static boolean sharesOneTint(String shape, int kind) {
        return !SHAPE_THREE.equals(shape) && (kind == TINT_ICON_ON || kind == TINT_ICON_OFF);
    }

    private static String tintColorKey(int kind) {
        if (kind == TINT_ICON_ON) {
            return KEY_TINT_ON;
        }
        return kind == TINT_LABEL ? KEY_LABEL_COLOR : KEY_TINT_OFF;
    }

    private static String tintAlphaKey(int kind) {
        if (kind == TINT_ICON_ON) {
            return KEY_TINT_ON_ALPHA;
        }
        return kind == TINT_LABEL ? KEY_LABEL_ALPHA : KEY_TINT_OFF_ALPHA;
    }

    /** 纯色值 + 不透明度 % → ARGB；跟随系统时原样返回 {@link #ICON_COLOR_FOLLOW_SYSTEM}。 */
    private static int argbOf(int color, int alphaPct) {
        if (color == ICON_COLOR_FOLLOW_SYSTEM) {
            return ICON_COLOR_FOLLOW_SYSTEM;
        }
        int alpha = Math.round(clampPct(alphaPct) * 255f / 100f);
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 255) {
            alpha = 255;
        }
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static void writeStyle(Context ctx, String shape, String field, int value) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return;
        }
        // 1×2 设备卡片写的是 1×1 方形卡片那一格，见 sharedShape
        shape = sharedShape(shape);
        try {
            JSONObject all = readAll(ctx, KEY_STYLE);
            JSONObject obj = all.optJSONObject(shape);
            if (obj == null) {
                obj = new JSONObject();
            }
            obj.put(field, value);
            all.put(shape, obj);
            prefs(ctx).edit().putString(KEY_STYLE, all.toString()).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": style write failed - " + t);
        }
    }

    // ------------------------------------------------------------ 每形状底图

    /**
     * 该形状的底图 / 可见度是否分「开 / 关」两套。
     *
     * <p>两张 2×1 宽卡片（{@link #SHAPE_2X1} / {@link #SHAPE_THREE}）、
     * {@link #SHAPE_2X2} 磁贴（含媒体播放卡片，它按 2×2 那一套走）、
     * {@link #SHAPE_CARD} 方形卡片与 {@link #SHAPE_CARD_WIDE} 横向设备卡片
     * 都合并成了一套：
     * 它们整张卡没有开 / 关两种观感（设备卡片更是读不到状态），分两套只会搞混。
     * {@link #SHAPE_NOTIFICATION} 通知卡片、{@link #SHAPE_SLIDER} 亮度 / 音量胶囊
     * （只有一根进度条）与 {@link #SHAPE_ENTRANCE} 右上角那排小圆钮（固定就那几颗、
     * 没有状态可切）同理。只有 1×1 圆形磁贴还留着两套。</p>
     */
    public static boolean hasStateVariants(String shape) {
        return !SHAPE_2X1.equals(shape) && !SHAPE_THREE.equals(shape)
                && !SHAPE_2X2.equals(shape)
                && !SHAPE_CARD.equals(shape) && !SHAPE_CARD_WIDE.equals(shape)
                && !SHAPE_NOTIFICATION.equals(shape) && !SHAPE_CLEAR.equals(shape)
                && !SHAPE_SLIDER.equals(shape) && !SHAPE_ENTRANCE.equals(shape);
    }

    /**
     * 查 / 写配置时<b>实际</b>使用的状态。
     *
     * <p>不分套的形状（两张 2×1 宽卡片）恒按「开」那一套读 / 写，
     * 这样调用方不用自己判断形状，传真实开 / 关进来也不会读错键。</p>
     */
    public static boolean stateOf(String shape, boolean active) {
        return hasStateVariants(shape) ? active : true;
    }

    /**
     * 把某个形状的「开 / 关」两套配置并成一套（两张 2×1 宽卡片需要）。
     *
     * <p>「开」那套为空时用「关」的补上，然后把多余的键与字段删掉——
     * 留着不但没用，下次看配置还会以为分套还在生效。</p>
     */
    public static void collapseStates(Context ctx, String shape) {
        if (ctx == null || shape == null || shape.length() == 0 || hasStateVariants(shape)) {
            return;
        }
        try {
            SharedPreferences.Editor editor = prefs(ctx).edit();
            boolean changed = false;

            String onKey = PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX + shape + IMAGE_SUFFIX_ON;
            String offKey = PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX + shape + IMAGE_SUFFIX_OFF;
            String onValue = prefs(ctx).getString(onKey, "");
            String offValue = prefs(ctx).getString(offKey, "");
            boolean hasOffImage = offValue != null && offValue.length() > 0;
            if (hasOffImage) {
                if (onValue == null || onValue.length() == 0) {
                    editor.putString(onKey, offValue);
                }
                editor.remove(offKey);
                changed = true;
            }

            JSONObject all = readAll(ctx, KEY_STYLE);
            JSONObject obj = all.optJSONObject(shape);
            if (obj != null && obj.has(KEY_ALPHA_OFF)) {
                if (!obj.has(KEY_ALPHA_ON)) {
                    int legacy = clampPct(obj.optInt(KEY_ALPHA_LEGACY, DEFAULT_ALPHA_PCT));
                    obj.put(KEY_ALPHA_ON, clampPct(obj.optInt(KEY_ALPHA_OFF, legacy)));
                }
                obj.remove(KEY_ALPHA_OFF);
                all.put(shape, obj);
                editor.putString(KEY_STYLE, all.toString());
                changed = true;
            }

            if (changed) {
                editor.commit();
                XposedBridge.log(TAG + ": collapsed on/off config for " + shape);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": collapse states failed - " + t);
        }
    }

    /**
     * 取底图：控制中心背景与胶囊进度块走不带状态的老键，磁贴形状按「开 / 关」分开存。
     *
     * <p>{@link PrefKeys#SHAPE_QS_BACKGROUND} 控制中心背景、{@link PrefKeys#SHAPE_SLIDER_PROGRESS}
     * 胶囊进度块都不是磁贴、没有开 / 关之分，借用的是同一条图片通道，这里替调用方把这个
     * 差异吃掉。</p>
     */
    public static String bgImage(Context ctx, String shape, boolean active) {
        if (stateless(shape)) {
            return shapeBgImage(ctx, shape);
        }
        return tileBgImage(ctx, shape, active);
    }

    /** 写 / 清底图，键的选择同 {@link #bgImage(Context, String, boolean)}。 */
    public static boolean setBgImage(Context ctx, String shape, boolean active, String base64) {
        if (stateless(shape)) {
            return setShapeBgImage(ctx, shape, base64);
        }
        return setTileBgImage(ctx, shape, active, base64);
    }

    /**
     * 该标识是不是「借用形状位的伪形状」——不是磁贴、底图也不分开 / 关两套。
     *
     * <p>这类标识的键是 {@code shapeBgImage_<标识>}，没有 {@code _on/_off} 后缀，
     * 见 {@link PrefKeys#SHAPE_QS_BACKGROUND} 与 {@link PrefKeys#SHAPE_SLIDER_PROGRESS}。</p>
     */
    private static boolean stateless(String shape) {
        return PrefKeys.SHAPE_QS_BACKGROUND.equals(shape)
                || PrefKeys.SHAPE_SLIDER_PROGRESS.equals(shape);
    }

    /**
     * 1×2 设备卡片（{@link #SHAPE_CARD_WIDE}）与 1×1 方形卡片（{@link #SHAPE_CARD}）
     * 共用同一格配置。
     *
     * <p>两者其实是同一格卡片位：没预连接设备时方形卡片就变成那张宽卡（见
     * {@code TileLongPressInterceptor#devicePluginShapeOf}）。所以底图与图标着色
     * 不分成两套——在哪张卡上设过，另一张立刻跟着走。各自的形状标识仍然保留，
     * 面板标题照旧按实际长按到的那张卡显示。</p>
     */
    private static String sharedShape(String shape) {
        return SHAPE_CARD_WIDE.equals(shape) ? SHAPE_CARD : shape;
    }

    /** 磁贴某形状某个状态的底图 prefs 键。 */
    private static String tileImageKey(String shape, boolean active) {
        String key = sharedShape(shape);
        return PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX + key
                + (stateOf(key, active) ? IMAGE_SUFFIX_ON : IMAGE_SUFFIX_OFF);
    }

    /** 磁贴某形状在指定状态下的自定义底图（Base64 JPEG）；没设置返回空串。 */
    public static String tileBgImage(Context ctx, String shape, boolean active) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return "";
        }
        try {
            String value = prefs(ctx).getString(tileImageKey(shape, active), "");
            if ((value == null || value.length() == 0) && SHAPE_CARD.equals(shape)) {
                // 同 styleOf：合并成同一格之前长按 1×2 卡片设下的底图落在 cardWide 键上
                value = prefs(ctx).getString(tileImageKey(SHAPE_CARD_WIDE, active), "");
            }
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写入 / 清除磁贴某形状某个状态的底图（空串表示清除）。 */
    public static boolean setTileBgImage(Context ctx, String shape, boolean active, String base64) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return false;
        }
        try {
            String key = tileImageKey(shape, active);
            SharedPreferences.Editor editor = prefs(ctx).edit();
            if (base64 == null || base64.length() == 0) {
                editor.remove(key);
            } else {
                editor.putString(key, base64);
            }
            // 同步落盘：写完马上就要刷新磁贴，用 apply 可能还没落盘
            return editor.commit();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": shape image write failed - " + t);
            return false;
        }
    }

    /**
     * 某个形状的自定义底图（Base64 JPEG）；没设置返回空串。
     *
     * <p>只给控制中心背景这类没有开/关之分的场景用；磁贴底图请走
     * {@link #tileBgImage(Context, String, boolean)}。</p>
     *
     * <p>存在 SystemUI 自己的 prefs 里：图片由模块设置页发广播送进来，
     * {@link TileImageChannel} 收到后写在这里，同进程读，不依赖跨进程 prefs。</p>
     */
    public static String shapeBgImage(Context ctx, String shape) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return "";
        }
        try {
            String value = prefs(ctx).getString(PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX + shape, "");
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 写入 / 清除某个形状的自定义底图（空串表示清除）。 */
    public static boolean setShapeBgImage(Context ctx, String shape, String base64) {
        if (ctx == null || shape == null || shape.length() == 0) {
            return false;
        }
        try {
            String key = PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX + shape;
            SharedPreferences.Editor editor = prefs(ctx).edit();
            if (base64 == null || base64.length() == 0) {
                editor.remove(key);
            } else {
                editor.putString(key, base64);
            }
            // 同步落盘：写完马上就要刷新磁贴，用 apply 可能还没落盘
            return editor.commit();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": shape image write failed - " + t);
            return false;
        }
    }

    /** 是否有任意<b>磁贴形状</b>设置过自定义底图（只扫键名，不读大字符串）。 */
    public static boolean anyShapeBgImage(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            // 控制中心背景图与胶囊进度图借用同一个键前缀，但它们都不是磁贴形状，
            // 混进来会让每个磁贴都白跑一遍形状判定
            String backgroundKey = PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX
                    + PrefKeys.SHAPE_QS_BACKGROUND;
            String progressKey = PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX
                    + PrefKeys.SHAPE_SLIDER_PROGRESS;
            for (java.util.Map.Entry<String, ?> entry : prefs(ctx).getAll().entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.startsWith(PrefKeys.KEY_SHAPE_BG_IMAGE_PREFIX)) {
                    continue;
                }
                if (backgroundKey.equals(key) || progressKey.equals(key)) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof String && ((String) value).length() > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------- 控制中心背景

    /** 背景可见度默认值对应的存储值（%）：0 = 可见度 100% = 完全不透明。 */
    public static final int DEFAULT_BG_TRANSPARENCY_PCT = 0;

    /**
     * 长按控制中心<b>空白背景</b>是否已被拦截。
     *
     * <p>ColorOS 原本的长按空白区行为是 {@code OplusQSRootViewComponent.longClickBlock()}
     * → {@code qsMoreButtonPopupWindowController.showEdit()}，也就是弹「⋯」按钮那个编辑弹窗。
     * 打开后换成模块自己的背景面板，关掉即恢复系统行为。默认开启。</p>
     */
    public static boolean isBgBlocked(Context ctx) {
        if (ctx == null) {
            return true;
        }
        try {
            return prefs(ctx).getBoolean(KEY_BG_BLOCKED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setBgBlocked(Context ctx, boolean on) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putBoolean(KEY_BG_BLOCKED, on).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bg block write failed - " + t);
        }
    }

    /**
     * 控制中心背景可见度：0 = 可见度 100%（完全遮住），100 = 可见度 0%（完全透明，不遮挡）。
     *
     * <p>这里返回的是<b>反向的存储值</b>：0 对应可见度 100%，100 对应可见度 0%。
     * 没设过时用 {@link #DEFAULT_BG_TRANSPARENCY_PCT}（0），也就是默认可见度 100%。
     * 可以是<b>负数</b>，最低 {@link #MIN_BG_TRANSPARENCY_PCT}，也就是可见度
     * 最高 200%：系统的背景层本身是半透明的，光靠 100% 可见度压不满，
     * 负值让 {@link TileBackgroundDrawable} 多叠一遍把不透明度补上去。</p>
     */
    public static int bgTransparencyPct(Context ctx) {
        if (ctx == null) {
            return DEFAULT_BG_TRANSPARENCY_PCT;
        }
        try {
            return clampBgTransparency(prefs(ctx).getInt(
                    KEY_BG_TRANSPARENCY, DEFAULT_BG_TRANSPARENCY_PCT));
        } catch (Throwable t) {
            return DEFAULT_BG_TRANSPARENCY_PCT;
        }
    }

    private static int clampBgTransparency(int value) {
        if (value < MIN_BG_TRANSPARENCY_PCT) {
            return MIN_BG_TRANSPARENCY_PCT;
        }
        return value > MAX_BG_TRANSPARENCY_PCT ? MAX_BG_TRANSPARENCY_PCT : value;
    }

    public static void setBgTransparencyPct(Context ctx, int pct) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putInt(KEY_BG_TRANSPARENCY, clampBgTransparency(pct)).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bg transparency write failed - " + t);
        }
    }

    // ------------------------------------------------- 控制中心背景纯色

    /**
     * 控制中心背景的纯色（ARGB）。
     *
     * <p>它<b>垫在背景图下面</b>：没设图时看到的就是这个颜色，设了图则图的透明处透出它。
     * 和图片共用同一条可见度滑杆（见 {@link #bgTransparencyPct}），所以整层的不透明度
     * 只有一处可调，不会出现「颜色和图片各有一套透明度」的糊涂账。</p>
     *
     * @return ARGB；{@link #ICON_COLOR_FOLLOW_SYSTEM} 表示没设过（交还系统）
     */
    public static int qsBackgroundColor(Context ctx) {
        if (ctx == null) {
            return ICON_COLOR_FOLLOW_SYSTEM;
        }
        try {
            return prefs(ctx).getInt(KEY_BG_COLOR, ICON_COLOR_FOLLOW_SYSTEM);
        } catch (Throwable t) {
            return ICON_COLOR_FOLLOW_SYSTEM;
        }
    }

    /** 写控制中心背景的纯色；传 {@link #ICON_COLOR_FOLLOW_SYSTEM} 表示清除。 */
    public static void setQsBackgroundColor(Context ctx, int argb) {
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putInt(KEY_BG_COLOR, argb).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": qs bg color write failed - " + t);
        }
    }

    // ------------------------------------------------------- 压掉背景模糊

    /**
     * 「压掉背景模糊」是否打开。
     *
     * <p>开关是 opt-in 的：默认关，也就是<b>保留系统自己的模糊</b>。
     * 打开后 {@link QsBackgroundHook} 会把控制中心背景层上的模糊整个让过去。</p>
     */
    public static boolean isBgBlurOff(Context ctx) {
        if (!sBgBlurOffLoaded && ctx != null) {
            try {
                sBgBlurOff = prefs(ctx).getBoolean(KEY_BG_BLUR_OFF, false);
                sBgBlurOffLoaded = true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": blur switch read failed - " + t);
            }
        }
        return sBgBlurOff;
    }

    /**
     * 热路径用：只读进程内缓存，不碰 prefs。
     *
     * <p>模糊钩子（{@code ViewBlurProxy#getBlurDrawable}）<b>每帧</b>都会被调到，
     * 在那里查 SharedPreferences 会直接把帧率拖下去，所以值必须在开关动的时候同步进来。</p>
     */
    public static boolean bgBlurOff() {
        return sBgBlurOff;
    }

    public static void setBgBlurOff(Context ctx, boolean off) {
        sBgBlurOff = off;
        sBgBlurOffLoaded = true;
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putBoolean(KEY_BG_BLUR_OFF, off).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": blur switch write failed - " + t);
        }
    }

    // ------------------------------------------------------- 颜色区分

    /**
     * 「颜色区分」是否打开（2×1 宽卡片与三阶段卡片）。
     *
     * <p>自定义图标底色时，暗色模式往黑里偏、亮色模式往白里偏（幅度分别见
     * {@link #COLOR_DIFF_PCT_DARK} / {@link #COLOR_DIFF_PCT_LIGHT}），好让它和卡片底色
     * 拉开一点差别。默认开。</p>
     */
    public static boolean isColorDiff(Context ctx) {
        if (!sColorDiffLoaded && ctx != null) {
            try {
                sColorDiff = prefs(ctx).getBoolean(KEY_COLOR_DIFF, DEFAULT_COLOR_DIFF);
                sColorDiffLoaded = true;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": color diff read failed - " + t);
            }
        }
        return sColorDiff;
    }

    /**
     * 热路径用：只读进程内缓存，不碰 prefs。
     *
     * <p>{@code IconColorHook} 里那条混色动画链路（{@code updateMixColorBackground}）
     * 每个动画帧都会被调到，在那里查 SharedPreferences 会拖慢下拉动画，所以值必须在
     * 开关动的时候同步进来（见 {@link #setColorDiff}）。调用前请先过一次
     * {@link #isColorDiff(Context)} 把缓存填好。</p>
     */
    public static boolean colorDiff() {
        return sColorDiff;
    }

    public static void setColorDiff(Context ctx, boolean on) {
        sColorDiff = on;
        sColorDiffLoaded = true;
        if (ctx == null) {
            return;
        }
        try {
            prefs(ctx).edit().putBoolean(KEY_COLOR_DIFF, on).apply();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": color diff write failed - " + t);
        }
    }

    /**
     * 按「颜色区分」把图标底色稍稍偏离卡片底色；关着或跟随系统时原样返回。
     *
     * <p>暗色模式往黑偏（{@code c * (1 - p)}，{@code p = }{@link #COLOR_DIFF_PCT_DARK}%），
     * 亮色模式往白偏（{@code c + (255 - c) * p}，{@code p = }{@link #COLOR_DIFF_PCT_LIGHT}%）。
     * 只动 RGB，<b>alpha 不动</b>，可见度滑杆仍是用户调的那个值。</p>
     */
    public static int differentiatedIconColor(Context ctx, int argb) {
        if (argb == ICON_COLOR_FOLLOW_SYSTEM || !isColorDiff(ctx)) {
            return argb;
        }
        boolean dark;
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            // 判不出亮暗就按暗色处理：控制中心默认就是暗色的
            dark = true;
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (dark) {
            int factor = 100 - COLOR_DIFF_PCT_DARK;
            r = r * factor / 100;
            g = g * factor / 100;
            b = b * factor / 100;
        } else {
            r = r + (255 - r) * COLOR_DIFF_PCT_LIGHT / 100;
            g = g + (255 - g) * COLOR_DIFF_PCT_LIGHT / 100;
            b = b + (255 - b) * COLOR_DIFF_PCT_LIGHT / 100;
        }
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ 通用

    private static JSONObject readAll(Context ctx, String key) {
        String raw = prefs(ctx).getString(key, "");
        if (raw == null || raw.length() == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static int clampPct(int value) {
        if (value < 0) {
            return 0;
        }
        return value > 100 ? 100 : value;
    }

    /** 形状标识 → 弹窗里的显示名，未知标识原样返回。 */
    public static String label(String shape) {
        if (shape == null) {
            return "";
        }
        for (String[] pair : LABELS) {
            if (pair[0].equals(shape)) {
                return pair[1];
            }
        }
        return shape;
    }

    /** 调试用：把当前所有开关与样式状态打到日志。 */
    public static void dump(Context ctx) {
        try {
            JSONObject blocked = readAll(ctx, KEY_BLOCKED);
            Iterator<String> keys = blocked.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                XposedBridge.log(TAG + ": " + k + " = " + blocked.optBoolean(k, false));
            }
            XposedBridge.log(TAG + ": style = " + readAll(ctx, KEY_STYLE));
        } catch (Throwable ignored) {
        }
    }
}
