package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 「图标激活 / 图标着色 / 文字颜色」三项的落点。
 *
 * <p>和 {@link IconColorHook}（图标<b>底下</b>那块小色块的底色）是两回事：这里改的是
 * 图标<b>本身</b>的颜色与标签文字的颜色。三条着色链路最后都落到
 * {@code ImageView#setImageTintList}，但入口各不相同：</p>
 * <ul>
 *   <li><b>主控制中心磁贴</b>（1×1 / 2×1 宽卡片 / 2×2 大卡片）：{@code OplusQSIconView#tintColor()}
 *       自己按 {@code tileState} 分路算色，算完调 {@code setImageTintList}。
 *       所以在它<b>之后</b>盖一层。</li>
 *   <li><b>状态更可靠的另一条入口</b>：{@code OplusQSIconView#setIcon(QSTile.State)} 收到
 *       的状态是崭新的（{@code state} 字段，2 = 激活），
 *       {@code OplusQSIconViewImpl#updateIconTintColor(ImageView, int, boolean)} 更是把状态当
 *       参数递进来。{@code tintColor()} 触发时机很少，只挂它就会出现「关态变了、
 *       激活态没变」这种只认一半的现象，所以这两条也一起接上。</li>
 *   <li><b>2×1 三阶段卡片</b>：{@code OplusQSThreeStageLayout#tintIconColor(index, active)}
 *       逐个图标上色，第二个参数就是「是不是当前选中那一段」，正好当激活态用。</li>
 *   <li><b>标签文字</b>：主面板是 {@code OplusQSTileViewImpl#updateTextColor(int)} 之后
 *       系统把标签染成它自己的色，这里再盖回去；另一条支线是
 *       {@code QsLabelView#updateColor(int, boolean, boolean)}。</li>
 * </ul>
 *
 * <p><b>方形设备卡片</b>（耳机 / 设备空间）走不了上面任何一条：那些卡片是
 * deviceplugin SDK 画出来的，颜色统一由静态方法
 * {@code PlatformUtils.getSystemUIColor(String, float)} 按资源名取。所以那一族按
 * <b>名字</b>命中——{@code status_bar_qs_tile_icon_color_active} 等四条目直接换值。</p>
 *
 * <p>三条 View 链路都在<b>原方法之后</b>落笔：系统算它自己的（含混色 / 深浅色主题分支），
 * 我们最后盖，既不用重写整套配色，也不会被随后的状态刷新冲掉。</p>
 */
public final class TileColorHook {

    private static final String TAG = "SystemUIHook/TileColor";

    /** 主控制中心磁贴的图标视图（1×1 圆形磁贴、2×1 宽卡片、2×2 大卡片）。 */
    private static final String CLASS_ICON_VIEW =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView";
    /**
     * AOSP 那条链路的图标视图（{@code QSIconViewImpl} 的 Oplus 子类）。
     *
     * <p>它把状态当<b>参数</b>递进来（{@code updateIconTintColor(ImageView, int, boolean)}），
     * 比读字段还准；两族视图哪个在用取决于磁贴实现，都接上才不会有「某几块磁贴没反应」。</p>
     */
    private static final String CLASS_ICON_VIEW_IMPL =
            "com.oplus.systemui.qs.tileimpl.OplusQSIconViewImpl";
    /** 2×1 三阶段卡片的容器（三个图标在它里面）。 */
    private static final String CLASS_THREE_STAGE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout";
    /** 主面板磁贴视图：标签文字与磁贴状态的落点。 */
    private static final String CLASS_TILE_VIEW =
            "com.oplus.systemui.qs.tileimpl.OplusQSTileViewImpl";
    /** 另一条支线的标签视图（2×1 高亮 / 可缩放卡片用）。 */
    private static final String CLASS_LABEL_VIEW =
            "com.oplus.systemui.plugins.qs.customize.view.tile.QsLabelView";
    /** 设备卡片唯一的取色入口（静态方法，按资源名取色）。 */
    private static final String CLASS_PLATFORM_UTILS =
            "com.oplus.deviceplugin.sdk.utils.PlatformUtils";
    /**
     * 设备卡片的共同父类：5 个卡片视图（{@code Square/RectangleDeviceCardView}、
     * {@code Square/Rectangle/NoDeviceEntranceCardView}）都继承它。
     *
     * <p>它是设备卡片那条「要不要跟随全局主题」的<b>开关落点</b>：卡片的刷新方法里会调
     * {@code setGlobalTheme(PlatformUtils.isGlobalThemeApplied(resources))}，把结果存进
     * 自己的一个布尔字段；之后卡片里所有取色 / 上色 / 铺底图都先看那个字段——
     * 为 {@code false} 时<b>整段跳过</b>，既不调 {@link #CLASS_PLATFORM_UTILS} 的
     * {@code getSystemUIColor}，也不碰 {@code ImageView#setColorFilter}，
     * 模块挂在两处的钩子一次都收不到，表现就是「形状认得出来、颜色却怎么都改不动」。
     * 所以这条链路只能绕开它自己涂，见 {@link #hookDeviceCardIconDirect}。</p>
     */
    private static final String CLASS_DEVICE_CARD_BASE =
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.d";
    /**
     * deviceplugin 那 5 个卡片视图。
     *
     * <p>它们的图标颜色本该由系统在「跟随全局主题」时按资源名取，但本机那个开关恒为
     * {@code false}，整段取色被跳过。所以除了等系统来调（见 {@link #hookDeviceCard} /
     * {@link #hookDeviceCardIconFilter}），还得自己上手涂——落点就是这几个类自己的刷新方法，
     * 见 {@link #hookDeviceCardIconDirect}。</p>
     */
    private static final String[] DEVICE_CARD_VIEW_CLASSES = {
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.NoDeviceEntranceCardView",
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.RectangleEntranceCardView",
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.SquareEntranceCardView",
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.RectangleDeviceCardView",
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.SquareDeviceCardView",
    };
    /**
     * 声明了 {@link #METHOD_LAST_ICON_TOUCH}（{@code j()}）的卡片类——只有这两个有，
     * 也只有这两个会把图标 colorFilter 清掉，所以补涂必须多挂它们一道。
     */
    private static final String[] DEVICE_CARD_LAST_TOUCH_CLASSES = {
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.NoDeviceEntranceCardView",
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.RectangleDeviceCardView",
    };
    /** 亮度图标：图标色走 {@code setIconColorFilter(int)}（图标是 Drawable，不是 ImageView）。 */
    private static final String CLASS_BRIGHTNESS_ICON =
            "com.oplus.systemui.qs.base.seek.ClipBrightnessView";
    /** 音量图标（系统那份）：静音 / 非静音两个取色 getter 都在它自己身上。 */
    private static final String CLASS_VOLUME_ICON =
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeIconView";
    /** 音量图标（插件那份）：只覆写了非静音那一个取色 getter，所以也得单独挂。 */
    private static final String CLASS_VOLUME_ICON_PLUGIN =
            "com.oplus.systemui.plugins.qs.widget.OplusQsVolumeIconView";
    /** 媒体播放卡片（2×2 那块大面板）：图标不是 {@code OplusQSIconView}，配色在 {@code setBtnColor()} 里发。 */
    private static final String CLASS_MEDIA_PANEL =
            "com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView";
    /** 兜底用的 ImageView 上色入口，见 {@link #hookDeviceCardIconFilter}。 */
    private static final String CLASS_IMAGE_VIEW = "android.widget.ImageView";

    private static final String METHOD_TINT_COLOR = "tintColor";
    private static final String METHOD_UPDATE_COLOR_IMMEDIATELY = "updateIconColorImmediately";
    /** {@code OplusQSIconView#setIcon(QSTile.State)}：状态刚写进 tileState 的那一刻。 */
    private static final String METHOD_SET_ICON = "setIcon";
    /** {@code OplusQSIconViewImpl#updateIconTintColor(ImageView, int, boolean)}。 */
    private static final String METHOD_UPDATE_ICON_TINT = "updateIconTintColor";
    private static final String METHOD_TINT_ICON_COLOR = "tintIconColor";
    private static final String METHOD_UPDATE_TEXT_COLOR = "updateTextColor";
    private static final String METHOD_UPDATE_LABEL_COLOR = "updateColor";
    private static final String METHOD_GET_SYSTEM_UI_COLOR = "getSystemUIColor";
    /**
     * 三个 {@code *EntranceCardView} 自己的取色方法（{@code NoDeviceEntranceCardView.i()}、
     * {@code RectangleEntranceCardView.i()}、{@code SquareEntranceCardView.i()}）。
     *
     * <p>系统「跟随全局主题」时它就是给图标上色的那一步；本机那个开关为 {@code false}，
     * 它会跳过整段上色，但<b>方法本身仍会被调</b>，所以我们挂在它后面的钩子能补一笔。</p>
     */
    private static final String METHOD_APPLY_CARD_COLOR = "i";
    /**
     * 卡片刷新里<b>最后</b>动图标颜色的那一步（{@code NoDeviceEntranceCardView.j()}、
     * {@code RectangleDeviceCardView.j()}）。
     *
     * <p>它是"反面教材"：{@code g()} 里紧挨着 {@code i()} 调它，而它开头就把四颗图标的
     * colorFilter <b>整片清成 {@code null}</b>（{@code setColorFilter((ColorFilter) null)} ×4），
     * 然后再 {@code setBackground} 铺自己的图标底色。所以任何挂在 {@code i()} 之后、
     * 挂在 {@code h()} 上的补涂，都会被它一手抹掉——这正是前几版"钩子明明装了却没色"的原因。</p>
     *
     * <p>补涂必须落在它<b>之后</b>：见 {@link #METHOD_ON_ATTACHED} 与
     * {@link #tintDeviceCard} 里那记 {@code post}。</p>
     */
    private static final String METHOD_LAST_ICON_TOUCH = "j";
    /**
     * {@code separatecardview.d#onAttachedToWindow()}：
     * 卡片挂上窗口时它<b>同步</b>跑完 {@code a$2() → g() → h() → i() → j()}，
     * 所以挂它的后置钩子正好落在整个刷新流程结束之后，是唯一不会被 {@code j()} 抹掉的时机。
     */
    private static final String METHOD_ON_ATTACHED = "onAttachedToWindow";
    /** {@code OplusQSThreeStageLayout#getActiveIconColor(boolean)}：三段里选中那段该用什么色。 */
    private static final String METHOD_GET_ACTIVE_ICON_COLOR = "getActiveIconColor";
    /** {@code ClipBrightnessView#setIconColorFilter(int)}：亮度图标的颜色就是它的参数。 */
    private static final String METHOD_SET_ICON_COLOR_FILTER = "setIconColorFilter";
    /** 亮度图标重刷入口。 */
    private static final String METHOD_UPDATE_ICON_COLOR = "updateIconColor";
    /** {@code ClipBrightnessView#setIconDrawable()}：两个 Drawable 在这里重建，颜色会被重置。 */
    private static final String METHOD_SET_ICON_DRAWABLE = "setIconDrawable";
    /** {@code ClipBrightnessView#setManualModeColorFilter()}：手动模式那条支线的上色入口。 */
    private static final String METHOD_SET_MANUAL_COLOR_FILTER = "setManualModeColorFilter";
    /** 音量图标的两个取色 getter，以及拿缓存状态重刷的入口。 */
    private static final String METHOD_GET_MUTE_ICON_COLOR = "getMuteIconColor";
    private static final String METHOD_GET_UNMUTE_ICON_COLOR = "getUnMuteIconColor";
    private static final String METHOD_UPDATE_ICON_STATE = "updateIconState";
    /** 2×2 媒体卡片给自己的按钮算色的入口。 */
    private static final String METHOD_SET_BTN_COLOR = "setBtnColor";
    /** {@code ImageView#setColorFilter(int)}：设备卡片把图标色直接涂上去的那一步。 */
    private static final String METHOD_SET_COLOR_FILTER = "setColorFilter";
    /** {@code ImageView#setImageDrawable(Drawable)}：设备卡片换图那一步。 */
    private static final String METHOD_SET_IMAGE_DRAWABLE = "setImageDrawable";

    /** {@code OplusQSIconView.tileState}：QSTile.State.state 的缓存，2 = 激活。 */
    private static final String FIELD_TILE_STATE = "tileState";
    /** 磁贴视图上的状态字段（{@code OplusQSTileViewImpl} 声明的是 int）。 */
    private static final String FIELD_STATE = "mState";
    /** {@code OplusQSThreeStageLayout} 里三个图标的列表。 */
    private static final String FIELD_ICON_VIEWS = "iconViews";
    /** 主面板磁贴视图上的标签。 */
    private static final String FIELD_LABEL = "mLabel";
    private static final String FIELD_SECOND_LINE = "mSecondLine";
    /** {@code QsLabelView} 里真正画字的那个 TextView。 */
    private static final String FIELD_LABEL_TEXT_VIEW = "textView";
    /** 音量图标上缓存的那份 {@code QSVolume.VolumeState}（重刷时拿它重跑一遍）。 */
    private static final String FIELD_QS_VOLUME_STATE = "qsVolumeState";
    /** 亮度图标的两张 Drawable（普通态 / 自动亮度态），颜色是靠它们的 filter 上的。 */
    private static final String FIELD_BRIGHTNESS_ICON = "brightnessIcon";
    private static final String FIELD_BRIGHTNESS_ICON_MODE = "brightnessIconMode";
    /** 媒体卡片四颗按钮（上一首 / 播放 / 下一首 / 投屏）与它们各自的色块属性。 */
    private static final String[] MEDIA_BUTTONS = {
            "preBtn", "playOrPauseBtn", "nextBtn", "mediaOutputBtn",
    };
    private static final String[] MEDIA_BUTTON_PROPS = {
            "preBtnColorProperty", "playOrPauseBtnColorProperty",
            "nextBtnColorProperty", "mediaOutputBtnColorProperty",
    };
    /** 按钮上色方法（{@code OplusLottieButton} 自己那颗）。 */
    private static final String METHOD_SET_LOTTIE_COLOR_FILTER = "setLottieColorFilter";

    /** AOSP 的激活态取值，见 {@code QSTile.State#state}。 */
    private static final int STATE_ACTIVE = 2;

    /** 见过的图标视图 → 它所属的形状（弱键，视图销毁后自动回收）。 */
    private static final Map<View, String> sIconViews =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 见过的三阶段容器 → 形状（恒为「三阶段」，留着是为了 refresh 时能筛）。 */
    private static final Map<View, String> sThreeStageViews =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 见过的磁贴视图 → 形状（文字颜色刷新用）。 */
    private static final Map<View, String> sTileViews =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 见过的亮度 / 音量图标（胶囊那两根，刷新用）。 */
    private static final Map<View, String> sSliderIcons =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 见过的媒体卡片面板（刷新时重跑一次它的 {@code setBtnColor()}）。 */
    private static final Map<View, String> sMediaPanels =
            Collections.synchronizedMap(new WeakHashMap<View, String>());

    /** 三项合计覆盖过多少次（样式面板显示，用来判断 hook 有没有跑起来）。 */
    private static volatile int sApplyCount;
    /** 文字颜色单独计数，诊断行里分开显示。 */
    private static volatile int sLabelCount;
    /**
     * 「这个形状到底配没配过着色」的懒加载缓存。
     *
     * <p>几个钩子挂在会被频繁调用的方法上——{@code ImageView#setColorFilter}、
     * 亮度图标的 {@code setIconDrawable()}（拖亮度条就会走）、媒体卡片的
     * {@code setBtnColor()}（每次切歌都会走）。这些钩子必须<b>先</b>用一次极便宜的
     * 判断把绝大多数调用挡掉，不能每次都去解析一遍 JSON。配置改动后由
     * {@link #refresh} 整个清空、下次调用重新量。</p>
     */
    private static final Map<String, Boolean> sTintConfigured = new ConcurrentHashMap<String, Boolean>();
    /**
     * 用户给设备卡片配没配过东西（图标颜色或底图）的懒加载缓存。
     *
     * <p>{@link #tintDeviceCard} 每次卡片刷新都会被叫一次，所以这里必须是一句最便宜的读，
     * 不能每次都去解析 JSON。配置改动后由 {@link #refresh} 置空、下次调用重新量。</p>
     */
    private static volatile Boolean sCardThemed = null;
    /** 各设备卡片类里 {@code ImageView} 字段的反射清单，见 {@link #cardImageFields}。 */
    private static final Map<Class<?>, List<java.lang.reflect.Field>> sCardImageFields =
            new ConcurrentHashMap<Class<?>, List<java.lang.reflect.Field>>();
    /** 各设备卡片类里 {@code TextView} 字段的反射清单，见 {@link #cardTextFields}。 */
    private static final Map<Class<?>, List<java.lang.reflect.Field>> sCardTextFields =
            new ConcurrentHashMap<Class<?>, List<java.lang.reflect.Field>>();
    /** {@link #hookDeviceCardIconDirect} 实际装上的钩子数，诊断用。 */
    private static volatile int sCardDirectHooks = 0;
    /**
     * 用户给设备卡片配的「文字颜色」缓存，见 {@link #cardLabelArgb}。
     *
     * <p>{@code null} = 还没量过；{@code ICON_COLOR_FOLLOW_SYSTEM} = 没配过（跟随系统）。
     * 这两个必须分得开，不然后者会被当成「还没量」反复去解 JSON。</p>
     */
    private static volatile Integer sCardLabelArgb = null;
    /** 已经弹过的诊断条目，按 {@code key} 去重（见 {@link #toastOnce}）。 */
    private static final java.util.Set<String> sDiagShownKeys =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 最近一次拿到的磁贴状态（{@code QSTile.State.state}）。
     *
     * <p>样式面板的诊断行里显示它：图标激活色不生效时，看这个数就能立刻分清是
     * 「状态没读到」还是「颜色本身没写上」。</p>
     */
    private static volatile int sLastState = -1;

    /**
     * 本进程的 Context（设备卡片那条静态入口用得上）。
     *
     * <p>它没有 View 可以拿 {@code getContext()}，只能外部塞一个进来——
     * {@link TileImageChannel#ensureReceiver} 注册广播时手上有现成的 Application。
     * 万一那一步还没跑到，再退回 {@code ActivityThread.currentApplication()}。</p>
     */
    private static volatile Context sAppContext;

    private TileColorHook() {
    }

    /** 供 {@link TileImageChannel} 注册广播时顺手把 Context 存下来。 */
    public static void setAppContext(final Context context) {
        if (context == null) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            sAppContext = app == null ? context : app;
        } catch (Throwable t) {
            sAppContext = context;
        }
    }

    public static void install(final LoadPackageParam lpparam) {
        int n = 0;
        n += hookIconView(lpparam);
        n += hookIconViewState(lpparam);
        n += hookIconViewImpl(lpparam);
        n += hookThreeStage(lpparam);
        n += hookThreeStageActiveColor(lpparam);
        n += hookTileText(lpparam);
        n += hookLabelView(lpparam);
        n += hookDeviceCard(lpparam);
        n += hookDeviceCardIconDirect(lpparam);
        n += hookDeviceCardIconFilter();
        n += hookBrightnessIcon(lpparam);
        n += hookVolumeIcon(lpparam);
        n += hookMediaPanel(lpparam);
        XposedBridge.log(TAG + ": installed (hooks=" + n + ")");
    }

    // ------------------------------------------------------------ 诊断探针

    /**
     * 各条入口的名字（{@link #probeText()} 的输出顺序，也是排查时的对照表）。
     *
     * <p>加这组探针是因为「哪条链路没生效」光看静态代码已经判断不动了：
     * 计数为 0 说明钩子没挂上或系统压根没调；有计数但后面写的是「形状不认」
     * 说明是形状判定挡的。样式面板底部会把这行打出来，长按一次就能看清。</p>
     */
    private static final String[] HOOK_ENTRIES = {
            "icon", "setIcon", "impl", "three", "threeSel", "text", "label",
            "card", "cardImg", "cardImg2", "cardDraw", "bright", "vol", "media",
    };

    private static final Map<String, Integer> sProbeCount = Collections.synchronizedMap(
            new java.util.HashMap<String, Integer>());
    private static volatile String sLastProbe = "（还没走过）";

    /** 记一笔「这条入口被走到过」以及走到哪一步。 */
    private static void probe(final String entry, final String detail) {
        Integer old = sProbeCount.get(entry);
        sProbeCount.put(entry, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        sLastProbe = entry + " → " + detail;
    }

    /** 各入口的命中次数，形如 {@code icon=12 setIcon=3 ...}。 */
    public static String probeText() {
        StringBuilder sb = new StringBuilder();
        for (String key : HOOK_ENTRIES) {
            Integer v = sProbeCount.get(key);
            sb.append(key).append('=').append(v == null ? 0 : v.intValue()).append(' ');
        }
        return sb.toString().trim();
    }

    /** 最近一次走到哪一步（进不去时也会留下原因）。 */
    public static String lastProbe() {
        return sLastProbe;
    }

    /** {@code #AARRGGBB}，诊断行里看着比十进制直观。 */
    private static String hex(final int argb) {
        return "#" + Integer.toHexString(argb).toUpperCase();
    }

    // ------------------------------------------------ 主控制中心磁贴：图标

    private static int hookIconView(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_ICON_VIEW, lpparam);
        if (cls == null) {
            return 0;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyIconView(param.thisObject, "icon");
            }
        };
        int n = 0;
        // tintColor 是常规刷新路径，updateIconColorImmediately 是「立刻变色」那条
        // （它内部先 setColorFilter/clearColorFilter，最后也会走一遍 tintColor）
        n += hookOnClass(cls, METHOD_TINT_COLOR, hook);
        n += hookOnClass(cls, METHOD_UPDATE_COLOR_IMMEDIATELY, hook);
        return n;
    }

    /**
     * {@code OplusQSIconView#setIcon(QSTile.State, boolean)}：状态刚落到 {@code tileState} 的那一刻。
     *
     * <p>这一段是给「激活色不生效」兜底的。{@code tintColor()} 只在少数几个时机被调，
     * 而 {@code setIcon} 每次磁贴状态变化都会收到一个崭新的 {@code QSTile.State}——
     * 直接读它的 {@code state}，比回头读控件上缓存的 {@code tileState} 更没有悬念。
     * 开着 / 关着都一样重新上色，不依赖系统接下来会不会再调 tintColor。</p>
     *
     * <p>实测签名是 {@code setIcon(QSTile$State, boolean)}（两个参数，第二个是动画开关），
     * 所以这里按 <b>2 个参数</b>枚举重载，别再按 1 个找——那样一个都挂不上。</p>
     */
    private static int hookIconViewState(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_ICON_VIEW, lpparam);
        if (cls == null) {
            return 0;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                int state = stateOfArg(param.args);
                if (state >= 0) {
                    applyIconView(param.thisObject, state, "setIcon");
                } else {
                    probe("setIcon", "state 取不到");
                }
            }
        };
        return hookByArity(cls, METHOD_SET_ICON, 2, hook);
    }

    /**
     * AOSP 那条链路：{@code OplusQSIconViewImpl#updateIconTintColor(ImageView, int, boolean)}。
     *
     * <p>状态是<b>参数</b>递进来的，连读字段都省了；图标 ImageView 也是现成的。</p>
     *
     * <p>注意 {@code OplusQSIconViewImpl} 本身<b>不是 View</b>，它只是个配色小工具，
     * 用它去判形状永远判不出来（{@code shapeOfView} 认不得）。所以这里拿
     * <b>参数里那个 ImageView</b> 当形状来源——它挂在磁贴视图里，父链能一路找到磁贴。</p>
     */
    private static int hookIconViewImpl(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_ICON_VIEW_IMPL, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_UPDATE_ICON_TINT, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args == null || args.length < 2 || !(args[0] instanceof ImageView)) {
                    probe("impl", "参数异常");
                    return;
                }
                int state = args[1] instanceof Integer
                        ? ((Integer) args[1]).intValue() : -1;
                if (state < 0) {
                    probe("impl", "state 取不到");
                    return;
                }
                ImageView icon = (ImageView) args[0];
                applyIconTint(icon, state == STATE_ACTIVE, icon, state, "impl");
            }
        }, ImageView.class, int.class, boolean.class);
    }

    /** 把配置的图标颜色盖到 {@code OplusQSIconView} 的图标 ImageView 上。 */
    private static void applyIconView(final Object iconView, final String entry) {
        if (!(iconView instanceof View)) {
            probe(entry, "不是 View");
            return;
        }
        final View view = (View) iconView;
        // 激活态直接读 tileState 字段：它就是这个控件缓存下来的 QSTile.State.state
        int state = intField(view, FIELD_TILE_STATE, -1);
        if (state < 0) {
            // 还没收到过 state（刚构造完）：往上找磁贴视图兜一层
            state = stateOfTile(view);
        }
        applyIconTint(view, state == STATE_ACTIVE, iconOf(view), state, entry);
    }

    /**
     * 已经拿到权威状态时的上色入口（状态直接来自系统递进来的 {@code QSTile.State}）。
     *
     * <p>比读控件字段可靠：{@code tintColor()} 那条路径读的是视图上缓存的
     * {@code tileState}，而这个字段只在 {@code setIcon} 时才被刷新——若系统在
     * 别处重绘、缓存还是旧值，激活色就会看着「没生效」。</p>
     */
    private static void applyIconView(final Object iconView, final int state, final String entry) {
        if (!(iconView instanceof View)) {
            probe(entry, "不是 View");
            return;
        }
        final View view = (View) iconView;
        applyIconTint(view, state == STATE_ACTIVE, iconOf(view), state, entry);
    }

    /** 从 {@code QSTile.State} 这类参数里取 {@code state} 字段；取不到返回 -1。 */
    private static int stateOfArg(final Object[] args) {
        if (args == null || args.length == 0) {
            return -1;
        }
        Object first = args[0];
        if (first == null) {
            return -1;
        }
        if (first instanceof Integer) {
            return ((Integer) first).intValue();
        }
        return intField(first, "state", -1);
    }

    /**
     * 取出图标视图里真正画图标那个 {@code ImageView}。
     *
     * <p>{@code OplusQSIconView} 里 {@code getIconView()} 有两个重载（一个返回
     * {@code ImageView}、一个返回 {@code View}），反射取到哪个不保证，所以这里
     * 只看结果是不是 ImageView——不是就交给调用方换别的办法找，绝不乱猜。</p>
     */
    private static ImageView iconOf(final View view) {
        try {
            Object got = XposedHelpers.callMethod(view, "getIconView");
            return got instanceof ImageView ? (ImageView) got : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 在视图树里找第一个 ImageView（深度优先，含自身）。 */
    private static ImageView firstImageView(final View view) {
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        if (!(view instanceof android.view.ViewGroup)) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = firstImageView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // --------------------------------------------- 2×1 三阶段卡片：图标

    private static int hookThreeStage(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_THREE_STAGE, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_TINT_ICON_COLOR, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyThreeStage(param.thisObject, param.args);
            }
        }, int.class, boolean.class);
    }

    /**
     * 三阶段卡片逐个图标上色，这里按同样的「第几段 / 是不是选中」盖回去。
     *
     * <p><b>踩过的坑</b>：{@code iconViews} 里装的<b>不是 ImageView</b>，是每段自己的
     * {@code OplusQSThreeStageIconView}（整个段容器）。之前直接
     * {@code instanceof ImageView} 判一下就返回，等于三阶段的着色从来没落过笔。
     * 现在按「先问段要 getIconView()，问不到就在段里找第一个 ImageView」取真正的图标。</p>
     */
    private static void applyThreeStage(final Object layout, final Object[] args) {
        if (!(layout instanceof View) || args == null || args.length < 2) {
            probe("three", "参数异常");
            return;
        }
        final View view = (View) layout;
        int index = args[0] instanceof Integer ? ((Integer) args[0]).intValue() : -1;
        boolean active = Boolean.TRUE.equals(args[1]);
        if (index < 0 || view.getContext() == null) {
            probe("three", "index=" + index + " ctx=" + (view.getContext() != null));
            return;
        }
        String shape = TileLongPressConfig.SHAPE_THREE;
        if (!TileLongPressConfig.supportsTint(shape)) {
            probe("three", "形状未启用");
            return;
        }
        int kind = active ? TileLongPressConfig.TINT_ICON_ON : TileLongPressConfig.TINT_ICON_OFF;
        int argb = TileLongPressConfig.tintColorOf(view.getContext(), shape, kind);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            probe("three", "该段没设颜色 kind=" + kind);
            return;
        }
        // 三个图标在 iconViews 里，按下标取；系统刚刚就是给这个下标上的色
        Object list = objectField(view, FIELD_ICON_VIEWS);
        if (!(list instanceof List)) {
            probe("three", "iconViews 取不到：" + (list == null ? "null" : list.getClass().getName()));
            return;
        }
        List<?> icons = (List<?>) list;
        if (index >= icons.size()) {
            probe("three", "下标越界 " + index + "/" + icons.size());
            return;
        }
        Object seg = icons.get(index);
        if (!(seg instanceof View)) {
            probe("three", "段不是 View：" + (seg == null ? "null" : seg.getClass().getName()));
            return;
        }
        ImageView icon = iconOf((View) seg);
        if (icon == null) {
            icon = firstImageView((View) seg);
        }
        if (icon == null) {
            probe("three", "段里找不到 ImageView：" + seg.getClass().getSimpleName());
            return;
        }
        try {
            icon.setImageTintList(ColorStateList.valueOf(argb));
            note(sThreeStageViews, view, shape);
            sApplyCount++;
            probe("three", "ok 段" + index + " kind=" + kind + " " + hex(argb));
        } catch (Throwable t) {
            probe("three", "上色失败 " + t);
            logOnce("three stage tint failed - " + t);
        }
    }

    /**
     * 三阶段卡片里<b>「当前选中那一段」</b>的图标色。
     *
     * <p>这一段必须从 {@code getActiveIconColor(boolean)} 下手，不能靠上一条 afterHook：
     * 它的返回值决定「这段该是什么色」，然后 {@code tintIconColor} 拿去跟图标现有的
     * tint 比对，<b>不一样才写</b>。我们若只在 {@code tintIconColor} 之后盖一层，
     * 系统下一次重算时还是会按自己的色再写一遍；而这里直接把「系统认为该用的色」
     * 换掉，选中段与未选中段就都跟着走 {@code active} 参数（true = 选中）。</p>
     */
    private static int hookThreeStageActiveColor(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_THREE_STAGE, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_GET_ACTIVE_ICON_COLOR, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View) || param.args.length < 1) {
                    return;
                }
                View view = (View) param.thisObject;
                Context ctx = view.getContext();
                if (ctx == null) {
                    return;
                }
                boolean active = Boolean.TRUE.equals(param.args[0]);
                int kind = active
                        ? TileLongPressConfig.TINT_ICON_ON : TileLongPressConfig.TINT_ICON_OFF;
                int argb = TileLongPressConfig.tintColorOf(ctx,
                        TileLongPressConfig.SHAPE_THREE, kind);
                if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                    probe("threeSel", "跟随系统 active=" + active);
                    return;
                }
                param.setResult(Integer.valueOf(argb));
                note(sThreeStageViews, view, TileLongPressConfig.SHAPE_THREE);
                sApplyCount++;
                probe("threeSel", "ok active=" + active + " " + hex(argb));
            }
        }, boolean.class);
    }

    // ------------------------------------------------ 标签文字：主面板磁贴

    private static int hookTileText(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_TILE_VIEW, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_UPDATE_TEXT_COLOR, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                View tile = (View) param.thisObject;
                applyLabel(tile, "text");
            }
        }, int.class);
    }

    // ------------------------------------------------ 标签文字：另一条支线

    private static int hookLabelView(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_LABEL_VIEW, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_UPDATE_LABEL_COLOR, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                applyLabel((View) param.thisObject, "label");
            }
        }, int.class, boolean.class, boolean.class);
    }

    /**
     * 把配置的文字颜色盖到磁贴标签上。
     *
     * <p>入参可以是主面板的磁贴视图，也可以是 {@code QsLabelView} 本身——后者自己就是
     * 标签，直接从它的 {@code textView} 字段取字。两条支线共用这一段。</p>
     */
    private static void applyLabel(final View view, final String entry) {
        String shape = shapeOf(view);
        if (!TileLongPressConfig.supportsTint(shape)) {
            probe(entry, "形状不认: " + shape);
            return;
        }
        Context ctx = view.getContext();
        if (ctx == null) {
            probe(entry, "没有 Context");
            return;
        }
        int argb = TileLongPressConfig.tintColorOf(ctx, shape, TileLongPressConfig.TINT_LABEL);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return;
        }
        List<TextView> labels = labelTextViews(view);
        if (labels.isEmpty()) {
            probe(entry, "找不到标签 TextView shape=" + shape);
            return;
        }
        try {
            for (TextView label : labels) {
                label.setTextColor(argb);
            }
            note(sTileViews, view, shape);
            sLabelCount++;
            sApplyCount++;
            probe(entry, "ok " + shape + " " + hex(argb));
        } catch (Throwable t) {
            probe(entry, "上色失败 " + t);
            logOnce("label color failed - " + t);
        }
    }

    /** 找出这一块视图里真正画标签的那几个 TextView。 */
    private static List<TextView> labelTextViews(final View view) {
        List<TextView> out = new ArrayList<TextView>();
        // QsLabelView：字在它自己的 textView 字段里
        if (CLASS_LABEL_VIEW.equals(view.getClass().getName())) {
            Object tv = objectField(view, FIELD_LABEL_TEXT_VIEW);
            if (tv instanceof TextView) {
                out.add((TextView) tv);
            }
            return out;
        }
        // 主面板磁贴视图：主标签 + 第二行（副标签）
        Object label = objectField(view, FIELD_LABEL);
        if (label instanceof TextView) {
            out.add((TextView) label);
        }
        Object second = objectField(view, FIELD_SECOND_LINE);
        if (second instanceof TextView) {
            out.add((TextView) second);
        }
        return out;
    }

    // ------------------------------------------------------ 方形设备卡片

    /**
     * 设备卡片的取色入口。
     *
     * <p>这是个<b>静态</b>方法，拿不到任何 View，所以形状恒按方形卡片算。返回值的 alpha
     * 由调用方传进来的系数决定（原实现是 {@code Color.argb((int)(255*f), r, g, b)}），
     * 这里保留那个系数、再乘上用户设的不透明度，卡片的淡入淡出不会被压平。</p>
     */
    private static int hookDeviceCard(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_PLATFORM_UTILS, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_GET_SYSTEM_UI_COLOR, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyDeviceColor(param);
            }
        }, String.class, float.class);
    }

    /**
     * 绕开系统的「跟随全局主题」开关，直接给设备卡片的图标上色。
     *
     * <p>本机（ColorOS 16 / PLR110）没套 OPPO 全局主题，
     * {@code PlatformUtils.isGlobalThemeApplied()} 恒为 {@code false}，卡片里
     * 「取图标色 → {@code setColorFilter}」整段被跳过。光靠
     * {@link #hookDeviceCard} / {@link #hookDeviceCardIconFilter} 等系统来调是等不到的
     * ——那两个钩子<b>一次都不会被调到</b>。</p>
     *
     * <p>补涂时机（都指向 {@link #tintDeviceCard}，多涂几遍无害，涂的是同一份配置）：
     * <ul>
     *   <li>父类的 {@code onAttachedToWindow()}——卡片挂上窗口时它<b>同步</b>跑完
     *       {@code g() → h() → i() → j()}，挂它的后置钩子正好落在整轮刷新之后，
     *       是时间上最靠后的那个点；</li>
     *   <li>三个 {@code *EntranceCardView} 的 {@code i()}</li>、
     *       两个卡的 {@code j()}——这两处贴着系统自己的上色步骤，
     *       面板复用时若没重新 attach 也还能补上。</li>
     * </ul></p>
     *
     * <p>但光排在这些点还不够：{@code j()} 会把 colorFilter 清成 {@code null}，
     * 而且 {@code g()} 末尾还 {@code post} 了一个自己的 lambda。所以
     * {@link #tintDeviceCard} 里涂完之后还会再 {@code post} 一次，
     * 保证<b>最后</b>留在屏幕上的那笔是我们的颜色。</p>
     *
     * <p><b>只在用户确实给卡片配过图标颜色或底图时才动手</b>（见 {@link #cardThemed}），
     * 没配过就直接放行。</p>
     */
    private static int hookDeviceCardIconDirect(final LoadPackageParam lpparam) {
        int n = 0;
        for (String name : DEVICE_CARD_VIEW_CLASSES) {
            Class<?> cls = findClassIfExists(name, lpparam);
            if (cls == null) {
                continue;
            }
            n += hookOnClass(cls, METHOD_APPLY_CARD_COLOR, CARD_TINT_HOOK);
        }
        for (String name : DEVICE_CARD_LAST_TOUCH_CLASSES) {
            Class<?> cls = findClassIfExists(name, lpparam);
            if (cls == null) {
                continue;
            }
            n += hookOnClass(cls, METHOD_LAST_ICON_TOUCH, CARD_TINT_HOOK);
        }
        Class<?> base = findClassIfExists(CLASS_DEVICE_CARD_BASE, lpparam);
        if (base != null) {
            n += hookOnClass(base, METHOD_ON_ATTACHED, CARD_TINT_HOOK);
        }
        sCardDirectHooks = n;
        if (n == 0) {
            XposedBridge.log(TAG + ": no device card direct-tint hook installed");
        }
        return n;
    }

    /** {@link #hookDeviceCardIconDirect} 两处共用的补涂回调。 */
    private static final XC_MethodHook CARD_TINT_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.thisObject instanceof View) {
                tintDeviceCard((View) param.thisObject);
            }
        }
    };

    /**
     * 把这张设备卡片里所有图标 {@code ImageView} 涂成用户挑的颜色。
     *
     * <p>不去猜那些 {@code n} / {@code r} / {@code s} 之类的混淆字段名（换个 ROM 就错），
     * 而是直接把这个类（含父类）里<b>所有 {@code ImageView} 类型的字段</b>都涂上——
     * 设备卡片里 {@code ImageView} 字段就是那几颗图标，正是系统自己会涂的那一组。</p>
     */
    private static void tintDeviceCard(final View card) {
        try {
            Context ctx = card.getContext();
            boolean themed = cardThemed(ctx);
            final int iconArgb = themed
                    ? deviceIconColor(ctx, TileLongPressConfig.TINT_ICON_OFF)
                    : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
            // 文字颜色跟图标色是两格独立配置，得各判各的：只配了文字色没配图标色时也得刷字
            final int labelArgb = cardLabelArgb(ctx);
            showCardDiag(card, themed, iconArgb, labelArgb);
            if (iconArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                    && labelArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                return;
            }
            // 一次性还不够：卡片刷新的最后一步（{@code j()}）会把图标滤镜和文字色都刷回系统值，
            // {@code g()} 末尾自己还 post 了一个 lambda。补涂之后统一再排一记，
            // 等这轮消息跑完再刷一遍，这样不管我们落在刷新的哪一段，最后留在屏幕上的都是我们的颜色。
            final View target = card;
            if (iconArgb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                applyCardTint(card, iconArgb);
            }
            if (labelArgb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                applyCardLabel(card, labelArgb);
            }
            card.post(new Runnable() {
                @Override
                public void run() {
                    if (iconArgb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        applyCardTint(target, iconArgb);
                    }
                    if (labelArgb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        applyCardLabel(target, labelArgb);
                    }
                }
            });
        } catch (Throwable t) {
            logOnce("tint device card failed - " + t);
        }
    }

    /**
     * 设备卡片那条链路的一次性自检，直接把结论打到屏幕上。
     *
     * <p>这条链路前面几轮全是靠静态分析猜的，猜了四轮都没上机证据。这张卡片的取色被
     * 「跟随全局主题」开关整段跳过（{@code isGlobalTheme == false}），补涂又会被刷新
     * 末尾的 {@code setColorFilter((ColorFilter) null)} 抹掉，所以「钩子装没装上 / 有没有被
     * 调到 / 用户到底配没配过色」这三件事必须一次问清楚。整个进程只弹一次，
     * 好让用户一眼就能把那行字念出来。</p>
     */
    private static void showCardDiag(final View card, final boolean themed, final int argb,
            final int labelArgb) {
        toastOnce("card:" + card.getClass().getSimpleName(),
                "刷新 @" + card.getClass().getSimpleName()
                + " 钩子" + sCardDirectHooks
                + " 配过色=" + themed
                + " 色=" + (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "跟随系统" : hex(argb))
                + " 字=" + (labelArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "跟随系统" : hex(labelArgb))
                + " 图标" + cardIcons(card).size() + "颗"
                + " 文字" + cardLabels(card).size() + "行");
    }

    /**
     * 把一条诊断结论弹到屏幕上（同时写日志），<b>每条各弹一次</b>。
     *
     * <p>用户不查日志，所以结论必须直接出现在屏幕上。早先这里是「全进程只弹一条」，结果
     * 第一张刷新的卡片（比如 1×1 方形卡 {@code SquareEntranceCardView}）就把名额吃掉了，
     * 真正要看的那张卡片的结论永远看不见——所以改成按 {@code key} 各弹一次，
     * 每张卡片、每条链路都有自己的那一行。</p>
     *
     * @param key 去重用的标识，同一条键只弹一次
     */
    private static void toastOnce(final String key, final String msg) {
        XposedBridge.log(TAG + ": DIAG " + msg);
        if (!sDiagShownKeys.add(key)) {
            return;
        }
        Context ctx = appContext();
        if (ctx == null) {
            XposedBridge.log(TAG + ": DIAG toast skipped (no context)");
            return;
        }
        try {
            android.widget.Toast.makeText(ctx, "卡片诊断 " + msg,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DIAG toast failed - " + t);
        }
    }

    /** 真正动手那一步：把卡片里所有图标 {@code ImageView} 涂成 {@code argb}。 */
    private static void applyCardTint(final View card, final int argb) {
        try {
            List<ImageView> icons = cardIcons(card);
            if (icons.isEmpty()) {
                probe("card", "卡片里没找到图标 ImageView");
                return;
            }
            for (ImageView icon : icons) {
                icon.setColorFilter(argb, PorterDuff.Mode.SRC_IN);
                // 设备卡片的图标是「背景」而不是 src（见 hookCardImageBackground），
                // 所以背景那份也得涂，两边都涂才不管它画的是哪一层
                android.graphics.drawable.Drawable bg = icon.getBackground();
                if (bg != null) {
                    bg.mutate();
                    bg.setColorFilter(argb, PorterDuff.Mode.SRC_IN);
                }
            }
            sApplyCount++;
            probe("card", "ok direct " + hex(argb) + " x" + icons.size());
        } catch (Throwable t) {
            logOnce("apply card tint failed - " + t);
        }
    }

    /**
     * 把设备卡片上那两行字涂成用户挑的颜色。
     *
     * <p>系统给这两行字上色走的是 {@code TextView.setTextColor(int)}：
     * {@code NoDeviceEntranceCardView.i()} 刷一遍，末尾的 {@code j()} 再刷一遍，
     * {@code j()} 才是留在屏幕上的那份。想在那两笔之间插队是插不进去的（谁后写谁说了算），
     * 所以这里不去抢 {@code setTextColor}，而是跟图标色一样<b>排到刷新链之后补涂</b>
     * ——{@link #tintDeviceCard} 挂在 {@code i()} / {@code j()} / {@code onAttachedToWindow()}
     * 的<b>后置</b>钩子上，那三个点都在系统自己刷完之后，补上去的颜色不会有人再改。</p>
     *
     * <p>磁贴标签那两条链路（{@code OplusQSTileViewImpl#updateTextColor} /
     * {@code QsLabelView#updateColor}）压根收不到设备卡片这条分支，这就是卡片图标能改、
     * 文字却一直不动的全部原因。</p>
     */
    private static void applyCardLabel(final View card, final int argb) {
        try {
            List<TextView> labels = cardLabels(card);
            // 这条链路的结论直接打到屏幕上：文字色到底有没有被刷、刷成了什么、
            // 找到几行，一眼就能看出来卡在哪一步
            toastOnce("txt:" + card.getClass().getSimpleName(),
                    "上文字 @" + card.getClass().getSimpleName()
                            + " 色=" + hex(argb) + " 找到" + labels.size() + "行");
            if (labels.isEmpty()) {
                logOnce("cardTxt " + card.getClass().getSimpleName()
                        + " 找不到文字（字段0 子树也没捞到）");
                return;
            }
            for (TextView tv : labels) {
                tv.setTextColor(argb);
            }
            // 把刷完之后的实际颜色读回来记一笔：写进去的是我们的色、读出来不是，
            // 说明有别的东西在这之后又改了一手
            logOnce("cardTxt " + card.getClass().getSimpleName()
                    + " argb=" + hex(argb)
                    + " 行数=" + labels.size()
                    + " 回读=" + hex(labels.get(0).getCurrentTextColor())
                    + " 类型=" + labels.get(0).getClass().getSimpleName());
            sLabelCount++;
            sApplyCount++;
            probe("cardTxt", "ok " + hex(argb) + " x" + labels.size());
        } catch (Throwable t) {
            logOnce("apply card label failed - " + t);
        }
    }

    /**
     * 卡片上那两行字（标题 / 副标题）。
     *
     * <p>跟 {@link #cardIcons} 一个路子：先按<b>字段</b>找——{@code NoDeviceEntranceCardView}
     * 的两行字挂在 {@code o} / {@code p} 两个字段上，正是系统自己会刷的那两个。</p>
     *
     * <p>字段找完之后<b>再</b>补一遍子树遍历，两份取并集（去重）。两个都做是有原因的：
     * 字段可能只挂了一行、另一行在子布局里；也可能字段全空（比如卡片还没绑数据就刷新了）。
     * 卡片里的 {@code TextView} 只有那两行字，多捞一次不会误伤。</p>
     */
    private static List<TextView> cardLabels(final View card) {
        List<TextView> out = new ArrayList<TextView>();
        for (java.lang.reflect.Field field : cardTextFields(card.getClass())) {
            try {
                Object value = field.get(card);
                if (value instanceof TextView && !out.contains(value)) {
                    out.add((TextView) value);
                }
            } catch (Throwable ignored) {
                // 字段读不到就跳过这一行，不该因为一行读不到就整张卡片不刷
            }
        }
        int before = out.size();
        List<TextView> fromTree = new ArrayList<TextView>();
        collectTextViews(card, fromTree);
        for (TextView tv : fromTree) {
            if (!out.contains(tv)) {
                out.add(tv);
            }
        }
        if (out.size() != before) {
            logOnce("cardTxt " + card.getClass().getSimpleName()
                    + " 字段" + before + "行 子树补" + (out.size() - before) + "行");
        }
        return out;
    }

    private static void collectTextViews(final View root, final List<TextView> out) {
        if (root instanceof TextView) {
            out.add((TextView) root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectTextViews(group.getChildAt(i), out);
        }
    }

    /**
     * 卡片类（含父类）里所有 {@code TextView} 字段，按类缓存。
     *
     * <p>这里必须用 {@code isAssignableFrom} 而不是 {@code ==}：卡片的字段类型是
     * {@code AppCompatTextView}（{@code TextView} 的子类），拿 {@code ==} 比会一颗都捞不到。</p>
     */
    private static List<java.lang.reflect.Field> cardTextFields(final Class<?> cls) {
        List<java.lang.reflect.Field> cached = sCardTextFields.get(cls);
        if (cached != null) {
            return cached;
        }
        List<java.lang.reflect.Field> out = new ArrayList<java.lang.reflect.Field>();
        for (Class<?> c = cls; c != null && View.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!TextView.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                out.add(field);
            }
        }
        sCardTextFields.put(cls, out);
        return out;
    }

    /**
     * 卡片上该涂色的那几颗图标。
     *
     * <p>先按<b>字段</b>找——设备卡片的图标都挂在自己类里那一串 {@code ImageView} 字段上
     * （{@code NoDeviceEntranceCardView} 是 {@code n/q/r/s} 四颗，正是系统自己会涂的那一组）。
     * 不去猜那些混淆名（换个 ROM 就错），只认类型。</p>
     *
     * <p>字段一颗都没有时才退化成遍历子树（{@code SquareDeviceCardView} 这种图标压在
     * {@code BatteryCircularLayout} 里的就得靠它）。</p>
     */
    private static List<ImageView> cardIcons(final View card) {
        List<ImageView> out = new ArrayList<ImageView>();
        for (java.lang.reflect.Field field : cardImageFields(card.getClass())) {
            try {
                Object value = field.get(card);
                if (value instanceof ImageView) {
                    out.add((ImageView) value);
                }
            } catch (Throwable ignored) {
                // 字段读不到就跳过这一颗，不该因为一颗读不到就整张卡片不涂
            }
        }
        if (out.isEmpty()) {
            collectImageViews(card, out);
        }
        return out;
    }

    private static void collectImageViews(final View root, final List<ImageView> out) {
        if (root instanceof ImageView) {
            out.add((ImageView) root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) {
            return;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectImageViews(group.getChildAt(i), out);
        }
    }

    /** 卡片类（含父类）里所有 {@code ImageView} 字段，按类缓存。 */
    private static List<java.lang.reflect.Field> cardImageFields(final Class<?> cls) {
        List<java.lang.reflect.Field> cached = sCardImageFields.get(cls);
        if (cached != null) {
            return cached;
        }
        List<java.lang.reflect.Field> out = new ArrayList<java.lang.reflect.Field>();
        for (Class<?> c = cls; c != null && View.class.isAssignableFrom(c); c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (field.getType() != ImageView.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                out.add(field);
            }
        }
        sCardImageFields.put(cls, out);
        return out;
    }

    /**
     * 用户有没有给设备卡片配过东西（图标颜色或底图）。
     *
     * <p>{@link #tintDeviceCard} 每次卡片刷新都会问一次，所以量完缓存住；
     * 配置改动由 {@link #refresh} 置空重算。还没拿到 Context 时按「没配过」处理，
     * 但<b>不</b>置缓存——等 Context 到手了再量一次。</p>
     */
    private static boolean cardThemed(final Context ctx) {
        Boolean cached = sCardThemed;
        if (cached != null) {
            return cached.booleanValue();
        }
        if (ctx == null) {
            return false;
        }
        boolean wanted = false;
        try {
            wanted = TileLongPressConfig.tintConfigured(ctx, TileLongPressConfig.SHAPE_CARD)
                    || TileLongPressConfig.tileBgImage(
                            ctx, TileLongPressConfig.SHAPE_CARD, true).length() > 0
                    || TileLongPressConfig.tileBgImage(
                            ctx, TileLongPressConfig.SHAPE_CARD, false).length() > 0;
        } catch (Throwable t) {
            logOnce("card themed check failed - " + t);
        }
        sCardThemed = Boolean.valueOf(wanted);
        return wanted;
    }

    private static void applyDeviceColor(final MethodHookParam param) {
        String name = param.args != null && param.args.length > 0
                && param.args[0] instanceof String ? (String) param.args[0] : null;
        final int kind = deviceKindOf(name);
        if (kind < 0) {
            return;
        }
        probe("card", "取色 " + name);
        final String shape = TileLongPressConfig.SHAPE_CARD;
        if (!TileLongPressConfig.supportsTint(shape)) {
            probe("card", "形状未启用");
            return;
        }
        Context ctx = appContext();
        if (ctx == null) {
            probe("card", "拿不到 Context");
            logOnce("device card: no context yet");
            return;
        }
        int argb = deviceIconColor(ctx, kind);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return;
        }
        // 调用方给的系数（一般是 1.0，淡入淡出时会更小）只作用在 alpha 上，
        // RGB 完全按用户挑的颜色来
        float factor = 1f;
        if (param.args.length > 1 && param.args[1] instanceof Float) {
            factor = ((Float) param.args[1]).floatValue();
        }
        if (factor < 0f) {
            factor = 0f;
        } else if (factor > 1f) {
            factor = 1f;
        }
        int alpha = Math.round((argb >>> 24) * factor);
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 255) {
            alpha = 255;
        }
        param.setResult(Integer.valueOf((argb & 0x00FFFFFF) | (alpha << 24)));
        sApplyCount++;
        probe("card", "ok " + kind + " " + hex(argb));
    }

    /** 系统那条资源名 → 模块里的着色对象；不相关返回 -1。 */
    private static int deviceKindOf(final String name) {
        if (name == null) {
            return -1;
        }
        if (ThemePackImporter.KEY_TILE_ICON_ACTIVE.equals(name)) {
            return TileLongPressConfig.TINT_ICON_ON;
        }
        if (ThemePackImporter.KEY_TILE_ICON_INACTIVE.equals(name)) {
            return TileLongPressConfig.TINT_ICON_OFF;
        }
        // 高亮卡片那条「激活时的文字色」默认 #FFFFFFFF、普通文字 #E6FFFFFF，同属文字这一项
        if (PrefKeys.DEVICE_LABEL_COLOR_NAME.equals(name)
                || "status_bar_qs_highlight_tile_label_color_active".equals(name)) {
            return TileLongPressConfig.TINT_LABEL;
        }
        return -1;
    }

    /**
     * 方形设备卡片的图标色。
     *
     * <p>这张卡片的图标<b>永远</b>按「未激活」那条颜色画——{@code SquareEntranceCardView#i()}、
     * {@code RectangleEntranceCardView#i()} 都只读 {@code status_bar_qs_tile_icon_color_inactive}，
     * 系统里压根没有「激活」那条资源。所以两项<b>互为兜底</b>（见 {@link #shapeIconColor}）：
     * 用户挑哪一项，卡片的图标就跟哪一项走，免得挑了「图标激活」却什么也没发生。</p>
     *
     * <p>文字颜色那一项不在这里兜底：拿图标色去染标签文字会把文字当成图标。</p>
     */
    private static int deviceIconColor(final Context ctx, final int kind) {
        return shapeIconColor(ctx, TileLongPressConfig.SHAPE_CARD, kind);
    }

    /**
     * 方形设备卡片的<b>兜底</b>落点：{@code ImageView} 自己的三个上色 / 换图方法。
     *
     * <p>为什么要兜底：卡片的颜色是<b>按资源名直读</b>的。以 1×1 方形卡片为例，图标在
     * {@code BatteryCircularProgressLayout} 里，上色那步是
     * {@code Resources.getIdentifier("status_bar_qs_tile_icon_color_active", "color", "com.android.systemui")}
     * 取色，压根不走 {@link #hookDeviceCard} 那条 {@code PlatformUtils.getSystemUIColor}；
     * {@code SquareEntranceCardView#i()} 同理。所以真正稳的落点是 {@code ImageView}，
     * 任何取色方式最后都得落到它身上。</p>
     *
     * <p>三个落点各管一段（都是 dex 里实证过的调用）：
     * <ul>
     *   <li>{@code setColorFilter(int)}——{@code SquareEntranceCardView} / {@code RectangleEntranceCardView}
     *       用的就是这个单参数重载；</li>
     *   <li>{@code setColorFilter(int, PorterDuff.Mode)}——{@code BatteryCircularProgressLayout#a(int, int)}
     *       用的是这个<b>两参数</b>重载（就是 1×1 方形卡片那颗图标）。上一轮只兜了单参数那个，
     *       所以这张卡片的钩子一次都没触发过；</li>
     *   <li>{@code setImageDrawable(Drawable)}——紧跟着上色的往往是一次
     *       {@code setColorFilter((ColorFilter) null)} 再换新 Drawable（暗色主题那条分支就是如此），
     *       滤镜会被清掉；在换图之后补一笔才能保证最终是我们挑的颜色。</li>
     * </ul></p>
     *
     * <p>三个钩子都挂在全系统的 ImageView 上，所以守卫必须够便宜：<b>先看用户到底有没有
     * 给方形卡片配过色</b>（一次 {@code ConcurrentHashMap.get}，没配直接返回），配过才沿父链
     * 认一次「是不是设备卡片里的图标」。常态下每条调用只多一次布尔判断。</p>
     */
    private static int hookDeviceCardIconFilter() {
        int n = 0;
        n += hookCardImageMethod(METHOD_SET_COLOR_FILTER, "cardImg", int.class);
        n += hookCardImageMethod(METHOD_SET_COLOR_FILTER, "cardImg2",
                int.class, PorterDuff.Mode.class);
        n += hookCardImageMethod(METHOD_SET_IMAGE_DRAWABLE, "cardDraw",
                android.graphics.drawable.Drawable.class);
        n += hookCardImageFilter();
        n += hookCardImageBackground();
        if (n == 0) {
            XposedBridge.log(TAG + ": no ImageView fallback hook installed");
        }
        return n;
    }

    /**
     * {@code View#setBackground(Drawable)}——设备卡片图标真正的那一笔。
     *
     * <p><b>这才是五轮改色都没反应的根本原因。</b>看 {@code no_device_card_layout.xml}：
     * 卡片里那 4 颗图标 {@code ImageView} 只有 {@code android:background="…icon_separate_default"}，
     * <b>没有</b> {@code android:src}；{@code NoDeviceEntranceCardView.i()/j()} 也是用
     * {@code setBackground(...)} 换那 4 张 {@code icon_separate_default*} 图。
     * 所以 ImageView 的 {@code src} 是空的，往它身上 {@code setColorFilter} 涂多少遍
     * 都是涂在空气上——这就是前面几轮「钩子全中、颜色全无」的原因。</p>
     *
     * <p>真正要涂的是<b>背景 drawable</b>。这里挂在 {@code setBackground} 之后：
     * 系统刚把图标铺上去，我们立刻在同一个 drawable 上盖上颜色滤镜。先
     * {@code mutate()} 一份自己的状态，免得把共享的 ConstantState 染到别的卡片上。</p>
     */
    private static int hookCardImageBackground() {
        try {
            java.lang.reflect.Method target = XposedHelpers.findMethodExactIfExists(
                    android.view.View.class, "setBackground",
                    android.graphics.drawable.Drawable.class);
            if (target == null) {
                return 0;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof ImageView)
                            || !(param.args[0] instanceof android.graphics.drawable.Drawable)) {
                        return;
                    }
                    ImageView view = (ImageView) param.thisObject;
                    View card = deviceCardOf(view);
                    if (card == null) {
                        return;
                    }
                    // 文字是另一格配置，跟图标色互不依赖：先补一遍文字（没配过文字色时是空操作），
                    // 再走图标那条路。这里必须先于下面那条「配过图标色」的守卫，
                    // 否则「只配了文字色」的用户永远走不到这一步。
                    tintCardLabels(card);
                    if (!cardTintConfigured()) {
                        return;
                    }
                    android.graphics.drawable.Drawable bg =
                            (android.graphics.drawable.Drawable) param.args[0];
                    String owner = card.getClass().getSimpleName();
                    int argb = cardIconArgb(view, "cardBg");
                    toastOnce("bg:" + owner, "上底色 @" + owner
                            + " 配过色=true"
                            + " 色=" + (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                                    ? "跟随系统" : hex(argb)));
                    if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        return;
                    }
                    bg.mutate();
                    bg.setColorFilter(argb, PorterDuff.Mode.SRC_IN);
                    sApplyCount++;
                    probe("cardBg", "ok " + hex(argb));
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook View.setBackground failed - " + t);
            return 0;
        }
    }

    /**
     * 设备卡片文字该用的颜色（带缓存）；没配过或还没拿到 Context 时返回
     * {@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM}。
     *
     * <p>这一读要解一遍 prefs 里的 JSON，而下面那几笔 ImageView 钩子每次刷新都要问一次，
     * 所以量完缓存住。配置改动由 {@link #refresh} 置空重算；Context 还没到手时<b>不</b>缓存，
     * 等拿到了再量。</p>
     */
    private static int cardLabelArgb(final Context ctx) {
        Integer cached = sCardLabelArgb;
        if (cached != null) {
            return cached.intValue();
        }
        if (ctx == null) {
            return TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        }
        int argb = TileLongPressConfig.tintColorOf(ctx, TileLongPressConfig.SHAPE_CARD,
                TileLongPressConfig.TINT_LABEL);
        sCardLabelArgb = Integer.valueOf(argb);
        return argb;
    }

    /**
     * 给设备卡片补文字色（只在真配过文字色时才动手）。
     *
     * <p>挂在那几笔必然会被调到的 {@code ImageView} 钩子上，给「卡片刷新链侧」那条补涂当
     * <b>兜底</b>：图标色就是靠这几笔改成功的，所以这条路的触发时机已经是实证过的；
     * 两条路谁先跑到都正常，涂的是同一份配置，多涂一遍无害。</p>
     */
    private static void tintCardLabels(final View card) {
        try {
            int argb = cardLabelArgb(card.getContext());
            if (argb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                applyCardLabel(card, argb);
            }
        } catch (Throwable t) {
            logOnce("tint card labels failed - " + t);
        }
    }

    /**
     * {@code ImageView#setColorFilter(ColorFilter)}——设备卡片那一记「清场」的落点。
     *
     * <p>这条<b>才是</b>「颜色怎么都涂不上」的真正元凶。卡片在「没套全局主题」分支里，
     * 刷新链 {@code i()} / {@code j()} 会把四颗图标的滤镜整片清成 {@code null}
     * （{@code setColorFilter((ColorFilter) null)} ×4）。前面无论怎么补涂，后面这一记清场
     * 都会把它抹掉——这也是为什么钩子明明装上了、探针一次都不打。</p>
     *
     * <p>办法很简单：把这一记「清成 null」偷换成「涂成我们的颜色」。
     * 只在<b>参数确实为 null</b>（就是清场那一笔）且这张图确实是它自己的图标时才动手，
     * 别的调用一律放行。这样不管刷新链走到哪一段，最后留下的一定是我们的颜色。</p>
     */
    private static int hookCardImageFilter() {
        try {
            java.lang.reflect.Method target = XposedHelpers.findMethodExactIfExists(
                    ImageView.class, METHOD_SET_COLOR_FILTER,
                    android.graphics.ColorFilter.class);
            if (target == null) {
                return 0;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0
                            || param.args[0] != null
                            || !(param.thisObject instanceof ImageView)) {
                        // 不是「清场」那一笔，放行（这一句挡掉全屏绝大多数调用）
                        return;
                    }
                    ImageView view = (ImageView) param.thisObject;
                    // 先只按「是不是设备卡片里的图」筛一遍（不设配置守卫），
                    // 只要这条链路真的跑到过设备卡片上，就先把结论弹到屏幕上
                    View card = deviceCardOf(view);
                    if (card == null) {
                        return;
                    }
                    // 文字那一格跟图标色互不依赖，先补一遍（没配过文字色时是空操作）
                    tintCardLabels(card);
                    String owner = card.getClass().getSimpleName();
                    boolean themed = cardTintConfigured();
                    int argb = themed
                            ? cardIconArgb(view, "cardClr")
                            : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
                    toastOnce("clr:" + owner, "清滤镜 @" + owner
                            + " 钩子" + sCardDirectHooks
                            + " 配过色=" + themed
                            + " 色=" + (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                                    ? "跟随系统" : hex(argb)));
                    if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        return;
                    }
                    param.args[0] = new android.graphics.PorterDuffColorFilter(
                            argb, PorterDuff.Mode.SRC_IN);
                    sApplyCount++;
                    probe("cardClr", "ok " + hex(argb));
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ImageView.setColorFilter(ColorFilter) failed - " + t);
            return 0;
        }
    }

    /**
     * 挂一个 ImageView 的兜底钩子。
     *
     * <p>换图那条（{@code setImageDrawable}）必须挂在<b>之后</b>：它本身就是「把 Drawable 换掉」
     * 的那一步，换完之前的滤镜可能已经被清了。改颜色那两条挂之前（改参数，让系统自己走完流程）。</p>
     */
    private static int hookCardImageMethod(final String method, final String entry,
            final Class<?>... params) {
        try {
            java.lang.reflect.Method target =
                    XposedHelpers.findMethodExactIfExists(ImageView.class, method, params);
            if (target == null) {
                return 0;
            }
            final boolean after = METHOD_SET_IMAGE_DRAWABLE.equals(method);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (after) {
                        return;
                    }
                    ImageView view = cardIcon(param.thisObject);
                    if (view == null) {
                        return;
                    }
                    int argb = cardIconArgb(view, entry);
                    if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        return;
                    }
                    param.args[0] = Integer.valueOf(argb);
                    sApplyCount++;
                    probe(entry, "ok " + hex(argb));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!after) {
                        return;
                    }
                    ImageView view = cardIcon(param.thisObject);
                    if (view == null) {
                        return;
                    }
                    int argb = cardIconArgb(view, entry);
                    if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                        return;
                    }
                    view.setColorFilter(argb, PorterDuff.Mode.SRC_IN);
                    sApplyCount++;
                    probe(entry, "ok " + hex(argb));
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ImageView." + method + " failed - " + t);
            return 0;
        }
    }

    /**
     * 这块 ImageView 值不值得管：先过「配过色」那道便宜守卫，再沿父链认设备卡片。
     *
     * <p>不是设备卡片里的图标一律返回 null（探针只在<b>配过色</b>时才记一笔，
     * 免得把整屏 ImageView 的调用都刷进诊断行）。</p>
     */
    private static ImageView cardIcon(final Object target) {
        if (!(target instanceof ImageView) || !cardTintConfigured()) {
            return null;
        }
        ImageView view = (ImageView) target;
        if (!insideDeviceCard(view)) {
            return null;
        }
        return view;
    }

    /** 方形卡片图标该用的颜色；跟随系统时返回 {@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM}。 */
    private static int cardIconArgb(final ImageView view, final String entry) {
        Context ctx = view.getContext();
        if (ctx == null) {
            probe(entry, "没有 Context");
            return TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        }
        int argb = deviceIconColor(ctx, TileLongPressConfig.TINT_ICON_OFF);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            probe(entry, "跟随系统");
        }
        return argb;
    }

    /**
     * 沿父链找出包裹这块视图的那张 deviceplugin 卡片，返回它的简单类名；没找到返回 null。
     *
     * <p>只用于诊断：把「这张图到底在不在设备卡片里、是哪张卡」直接显示出来。</p>
     */
    private static String deviceCardAncestorName(final View view) {
        View card = deviceCardOf(view);
        return card == null ? null : card.getClass().getSimpleName();
    }

    /**
     * 沿父链找出包裹 {@code view} 的那张设备卡片（找不到返回 {@code null}）。
     *
     * <p>口径与 {@link TileLongPressInterceptor} 判形状时一致：认
     * {@code …separatecardview} 包里那几个卡片类。卡片内部是 卡片 → 若干层
     * {@code ConstraintLayout} → 图标，比默认那 6 层深，所以走到 12 层。</p>
     */
    private static View deviceCardOf(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (TileLongPressInterceptor.isDevicePluginCard(current)) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 这块 ImageView 是不是设备卡片里的图标。
     *
     * <p>沿父链认 {@code …separatecardview} 包里那几个卡片类（口径与
     * {@link TileLongPressInterceptor} 判形状时一致）。卡片内部是从卡片 → 若干层
     * {@code ConstraintLayout} → {@code BatteryCircularProgressLayout} → 图标，
     * 比 {@code devicePluginCardOf} 默认那 6 层深，所以这里自己走到 12 层：
     * 走得浅就会出现「形状认得出来、颜色却怎么都改不动」这种假死。</p>
     */
    private static boolean insideDeviceCard(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (TileLongPressInterceptor.isDevicePluginCard(current)) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * 用户有没有给方形卡片配过色（热路径守卫，见 {@link #tintConfigured}）。
     */
    private static boolean cardTintConfigured() {
        return tintConfigured(TileLongPressConfig.SHAPE_CARD, appContext());
    }

    /**
     * 这个形状配没配过「图标激活 / 图标着色」（{@link #sTintConfigured} 的懒加载）。
     *
     * <p>量一次就缓存；配置写完之后由 {@link #refresh} 整表打回未知、下次再量。
     * 还没有 Context 时按「没配过」处理，但<b>不</b>置缓存——等 Context 到手了再量一次。</p>
     */
    private static boolean tintConfigured(final String shape, final Context ctx) {
        Boolean cached = sTintConfigured.get(shape);
        if (cached != null) {
            return cached.booleanValue();
        }
        if (ctx == null) {
            return false;
        }
        boolean configured = TileLongPressConfig.tintConfigured(ctx, shape);
        sTintConfigured.put(shape, Boolean.valueOf(configured));
        return configured;
    }

    /**
     * 某个形状某个着色对象的颜色；这一项没设时用<b>另一项</b>兜底。
     *
     * <p>不分开 / 关两套的形状（方形设备卡片、媒体播放卡片、亮度 / 音量胶囊）用它：
     * 这几类的图标只有一副面孔，用户挑「图标激活」还是「图标着色」其实都是一个意思，
     * 互为兜底才不会出现「挑了颜色却什么也没变」。</p>
     */
    private static int shapeIconColor(final Context ctx, final String shape, final int kind) {
        int argb = TileLongPressConfig.tintColorOf(ctx, shape, kind);
        if (argb != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return argb;
        }
        if (kind == TileLongPressConfig.TINT_LABEL) {
            // 文字颜色没有「另一项」可兜底：拿图标色去染字会把文字当成图标
            return TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        }
        return TileLongPressConfig.tintColorOf(ctx, shape,
                kind == TileLongPressConfig.TINT_ICON_ON
                        ? TileLongPressConfig.TINT_ICON_OFF
                        : TileLongPressConfig.TINT_ICON_ON);
    }

    /** 设备卡片那条入口没有 View，只能这样取 Context（见 {@link #sAppContext}）。 */
    private static Context appContext() {
        Context cached = sAppContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) {
                setAppContext((Context) app);
                return sAppContext;
            }
        } catch (Throwable t) {
            logOnce("currentApplication failed - " + t);
        }
        return null;
    }

    // ------------------------------------------------- 亮度 / 音量胶囊：图标

    /**
     * 亮度图标（{@code ClipBrightnessView}）的着色。
     *
     * <p>它的图标<b>不是 ImageView</b>，是两块自己画的 {@code Drawable}
     * （{@code brightnessIcon} 与自动亮度态的 {@code brightnessIconMode}），颜色靠
     * {@code Drawable#setColorFilter} 上。系统有两条上色支线（自动 / 手动）、外加一条
     * 「重建 Drawable」的 {@code setIconDrawable()}，只在其中一条插手都可能被随后那步
     * 覆盖掉，所以三条<b>都</b>在返回后重涂一遍——涂的是同一份配置，重复涂没有副作用。</p>
     *
     * <p>这三条里 {@code setIconDrawable()} 会被拖亮度条这种高频操作走到，所以每个回调
     * 第一件事就是走 {@link #tintConfigured} 那个近乎免费的守卫；用户没配过色时，
     * 热路径上只多一次 {@code ConcurrentHashMap.get}。</p>
     */
    private static int hookBrightnessIcon(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_BRIGHTNESS_ICON, lpparam);
        if (cls == null) {
            return 0;
        }
        XC_MethodHook paint = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                paintBrightnessIcon(param.thisObject);
            }
        };
        int n = 0;
        n += hookOnClass(cls, METHOD_UPDATE_ICON_COLOR, paint);
        n += hookOnClass(cls, METHOD_SET_ICON_DRAWABLE, paint);
        n += hookOnClass(cls, METHOD_SET_MANUAL_COLOR_FILTER, paint);
        n += hookOnClass(cls, METHOD_SET_ICON_COLOR_FILTER, paint, int.class);
        return n;
    }

    /** 把用户挑的颜色涂到亮度图标那两块 Drawable 上。 */
    private static void paintBrightnessIcon(final Object target) {
        if (!(target instanceof View)) {
            probe("bright", "不是 View");
            return;
        }
        View view = (View) target;
        Context ctx = view.getContext();
        if (ctx == null) {
            probe("bright", "没有 Context");
            return;
        }
        if (!tintConfigured(TileLongPressConfig.SHAPE_SLIDER, ctx)) {
            probe("bright", "没配过色");
            return;
        }
        int argb = shapeIconColor(ctx, TileLongPressConfig.SHAPE_SLIDER,
                TileLongPressConfig.TINT_ICON_ON);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            probe("bright", "跟随系统");
            return;
        }
        int painted = 0;
        painted += applyDrawableFilter(objectField(view, FIELD_BRIGHTNESS_ICON), argb);
        painted += applyDrawableFilter(objectField(view, FIELD_BRIGHTNESS_ICON_MODE), argb);
        note(sSliderIcons, view, TileLongPressConfig.SHAPE_SLIDER);
        if (painted == 0) {
            probe("bright", "Drawable 还没建出来");
            return;
        }
        sApplyCount++;
        probe("bright", "ok " + painted + " 块 " + hex(argb));
    }

    /** 给一块 Drawable 套上 SRC_IN 的纯色滤镜；不是 Drawable 返回 0。 */
    private static int applyDrawableFilter(final Object drawable, final int argb) {
        if (!(drawable instanceof Drawable)) {
            return 0;
        }
        try {
            ((Drawable) drawable).setColorFilter(argb, PorterDuff.Mode.SRC_IN);
            return 1;
        } catch (Throwable t) {
            logOnce("drawable filter failed - " + t);
            return 0;
        }
    }

    /**
     * 音量图标（{@code OplusQsVolumeIconView}）的着色。
     *
     * <p>它自己就是一块 {@code ImageView}，颜色由 {@code updateIconColor()} 里从
     * {@code getMuteIconColor()} / {@code getUnMuteIconColor()} 取到之后
     * {@code setColorFilter(color, SRC_IN)} 涂上去的（取到 0 则 clear）。所以改这两个
     * <b>取色 getter 的返回值</b>就是最正的落点——不用碰它的绘制，也不会被重刷覆盖。</p>
     *
     * <p>两个包都挂：系统那份（{@code com.oplus.systemui.qs.base.seek}）两个 getter 都声明了，
     * 插件那份（{@code com.oplus.systemui.plugins.qs.widget}）只覆写了非静音那一个。
     * 两个 getter 与「图标激活 / 图标着色」一一对应，且互为兜底，用户只挑一项也看得见变化。</p>
     */
    private static int hookVolumeIcon(final LoadPackageParam lpparam) {
        int n = 0;
        n += hookVolumeGetters(lpparam, CLASS_VOLUME_ICON);
        n += hookVolumeGetters(lpparam, CLASS_VOLUME_ICON_PLUGIN);
        return n;
    }

    private static int hookVolumeGetters(final LoadPackageParam lpparam, final String className) {
        Class<?> cls = findClassIfExists(className, lpparam);
        if (cls == null) {
            return 0;
        }
        int n = 0;
        n += hookOnClass(cls, METHOD_GET_UNMUTE_ICON_COLOR,
                volumeColorGetter(TileLongPressConfig.TINT_ICON_ON, "非静音"));
        n += hookOnClass(cls, METHOD_GET_MUTE_ICON_COLOR,
                volumeColorGetter(TileLongPressConfig.TINT_ICON_OFF, "静音"));
        return n;
    }

    /** 音量图标取色 getter 的钩子：直接换掉返回值。 */
    private static XC_MethodHook volumeColorGetter(final int kind, final String what) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                View view = (View) param.thisObject;
                Context ctx = view.getContext();
                if (ctx == null) {
                    return;
                }
                if (!tintConfigured(TileLongPressConfig.SHAPE_SLIDER, ctx)) {
                    return;
                }
                int argb = shapeIconColor(ctx, TileLongPressConfig.SHAPE_SLIDER, kind);
                if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
                    probe("vol", what + " 跟随系统");
                    return;
                }
                param.setResult(Integer.valueOf(argb));
                note(sSliderIcons, view, TileLongPressConfig.SHAPE_SLIDER);
                sApplyCount++;
                probe("vol", what + " " + hex(argb));
            }
        };
    }

    // ------------------------------------------- 媒体播放卡片（2×2）：四个按钮

    /**
     * 媒体播放卡片的图标着色。
     *
     * <p>这张卡片的按钮不是 {@code OplusQSIconView}，配色在
     * {@code OplusQsBaseMediaPanelView#setBtnColor()} 里一次性算出来：一个
     * {@code int[2]{可用色, 不可用色}} 分别赋给四颗按钮各自的 colorProperty，
     * 播放键那块 Lottie 还要再走一次 {@code setLottieColorFilter(int)}。</p>
     *
     * <p>所以这里挂在它<b>之后</b>，把四颗按钮重涂成用户挑的颜色。按钮自己的
     * colorProperty 也要一起写：只改 ImageView 的 filter 的话，系统下一次按属性
     * 重绘就会把颜色抹回去（拖进度、切歌都会触发）。</p>
     */
    private static int hookMediaPanel(final LoadPackageParam lpparam) {
        Class<?> cls = findClassIfExists(CLASS_MEDIA_PANEL, lpparam);
        if (cls == null) {
            return 0;
        }
        return hookOnClass(cls, METHOD_SET_BTN_COLOR, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyMediaBtnColor(param.thisObject);
            }
        });
    }

    private static void applyMediaBtnColor(final Object panel) {
        if (!(panel instanceof View)) {
            probe("media", "不是 View");
            return;
        }
        View view = (View) panel;
        // 先记下来：即使这会儿用户还没配色，刷新时也要能找到这块面板重跑 setBtnColor
        note(sMediaPanels, view, TileLongPressConfig.SHAPE_2X2);
        Context ctx = view.getContext();
        if (ctx == null) {
            probe("media", "没有 Context");
            return;
        }
        if (!tintConfigured(TileLongPressConfig.SHAPE_2X2, ctx)) {
            probe("media", "没配过色");
            return;
        }
        int argb = shapeIconColor(ctx, TileLongPressConfig.SHAPE_2X2,
                TileLongPressConfig.TINT_ICON_ON);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            probe("media", "跟随系统");
            return;
        }
        int painted = 0;
        for (int i = 0; i < MEDIA_BUTTONS.length; i++) {
            Object prop = objectField(panel, MEDIA_BUTTON_PROPS[i]);
            if (prop != null && invokeIfExists(prop, "set", int.class,
                    Integer.valueOf(argb))) {
                painted++;
            }
            Object btn = objectField(panel, MEDIA_BUTTONS[i]);
            if (btn != null && paintMediaButton(btn, argb)) {
                painted++;
            }
        }
        if (painted == 0) {
            probe("media", "四颗按钮都没取到");
            return;
        }
        sApplyCount++;
        probe("media", "ok " + painted + " " + hex(argb));
    }

    /**
     * 给一颗媒体按钮上色。
     *
     * <p>播放键是 Lottie 动画按钮，得走它自己的 {@code setLottieColorFilter(int)}；
     * 其余三颗（上一首 / 下一首 / 投屏）本来就是 ImageView，直接改滤镜即可。
     * 两条都试，谁的类上声明了方法就走谁。</p>
     */
    private static boolean paintMediaButton(final Object button, final int argb) {
        java.lang.reflect.Method lottie = findMethod(
                button.getClass(), METHOD_SET_LOTTIE_COLOR_FILTER, int.class);
        if (lottie != null) {
            try {
                lottie.setAccessible(true);
                lottie.invoke(button, Integer.valueOf(argb));
                return true;
            } catch (Throwable t) {
                logOnce("media lottie filter failed - " + t);
            }
        }
        if (button instanceof ImageView) {
            try {
                ((ImageView) button).setColorFilter(argb, PorterDuff.Mode.SRC_IN);
                return true;
            } catch (Throwable t) {
                logOnce("media button filter failed - " + t);
            }
        }
        return false;
    }

    /**
     * 找一个带单个参数的方法。
     *
     * <p>先 {@code getMethod}（会把实现的接口一起算上——媒体卡片那四个
     * colorProperty 的 {@code set(int)} 就是声明在 {@code ImageViewColorFilterTarget}
     * 接口上的），不中再退回 Xposed 的「只按类层次找」。</p>
     */
    private static java.lang.reflect.Method findMethod(final Class<?> cls,
            final String name, final Class<?> paramType) {
        try {
            return cls.getMethod(name, paramType);
        } catch (NoSuchMethodException ignored) {
            return XposedHelpers.findMethodExactIfExists(cls, name, paramType);
        }
    }

    /** 调一个指定参数类型的方法；方法不存在或调用失败返回 false。 */
    private static boolean invokeIfExists(final Object target, final String method,
            final Class<?> paramType, final Object arg) {
        java.lang.reflect.Method m = findMethod(target.getClass(), method, paramType);
        if (m == null) {
            return false;
        }
        try {
            m.setAccessible(true);
            m.invoke(target, arg);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 样式面板改完这三项之后，把已经画出来的磁贴重刷一遍（主线程调用）。
     *
     * <p>图标走「再跑一次系统自己的着色方法」：{@code tintColor} 会把视图上缓存的
     * 状态重新算一遍，我们挂在它后面的 hook 顺势把新颜色盖上，比直接照抄一个颜色
     * 更不容易和真实状态脱节。文字直接落笔即可。</p>
     *
     * <p>方形设备卡片刷不了：那张卡片的颜色是构建时按资源名取的，没有可以重新触发的
     * 方法，改完得等卡片重建（重启系统界面）。这里如实返回 0。</p>
     *
     * @return 实际刷到的视图数量
     */
    public static int refresh(final String shape) {
        if (shape == null || !TileLongPressConfig.supportsTint(shape)) {
            return 0;
        }
        // 配置刚变过：那层「这个形状配没配过色」的缓存必须先作废，
        // 否则下面重跑系统方法的钩子会被自己的守卫挡住（等于刷了个寂寞）
        sTintConfigured.clear();
        sCardThemed = null;
        sCardLabelArgb = null;
        sDiagShownKeys.clear();
        int n = 0;
        for (View view : snapshot(sIconViews)) {
            if (!shape.equals(TileLongPressInterceptor.shapeOfView(view))) {
                continue;
            }
            n += retint(view);
        }
        if (TileLongPressConfig.SHAPE_THREE.equals(shape)) {
            // 三阶段容器恒是三阶段，不用再判形状
            for (View view : snapshot(sThreeStageViews)) {
                n += retint(view);
            }
        }
        if (TileLongPressConfig.SHAPE_SLIDER.equals(shape)) {
            // 亮度 / 音量图标不在磁贴那一族里：让它们各自按缓存的状态重跑一遍
            // （亮度 updateIconColor 会重刷 Drawable，音量 updateIconState 会重过取色 getter）
            for (View view : snapshot(sSliderIcons)) {
                n += retintSlider(view);
            }
        }
        if (TileLongPressConfig.SHAPE_2X2.equals(shape)) {
            for (View view : snapshot(sMediaPanels)) {
                n += retintMediaPanel(view);
            }
        }
        for (View view : snapshot(sTileViews)) {
            if (!shape.equals(TileLongPressInterceptor.shapeOfView(view))) {
                continue;
            }
            applyLabel(view, "refresh");
            n++;
        }
        XposedBridge.log(TAG + ": refreshed shape=" + shape + ", views=" + n
                + ", applied=" + sApplyCount + ", labels=" + sLabelCount);
        return n;
    }

    /**
     * 重刷一颗亮度 / 音量图标。
     *
     * <p>音量图标上有缓存的音量状态，交给 {@code updateIconState(state, ...)} 重跑最准：
     * 它会自己判断该用静音那张图还是非静音那张图，再经过我们改过返回值的取色 getter。
     * 亮度图标没有状态，直接 {@code updateIconColor()} 重建并重涂两块 Drawable。</p>
     */
    private static int retintSlider(final View view) {
        try {
            Object state = objectField(view, FIELD_QS_VOLUME_STATE);
            if (state != null) {
                java.lang.reflect.Method two = methodByArity(
                        view.getClass(), METHOD_UPDATE_ICON_STATE, 2);
                java.lang.reflect.Method one = methodByArity(
                        view.getClass(), METHOD_UPDATE_ICON_STATE, 1);
                if (two != null) {
                    two.setAccessible(true);
                    two.invoke(view, state, Boolean.FALSE);
                    return 1;
                }
                if (one != null) {
                    one.setAccessible(true);
                    one.invoke(view, state);
                    return 1;
                }
            }
            XposedHelpers.callMethod(view, METHOD_UPDATE_ICON_COLOR);
            return 1;
        } catch (Throwable t) {
            logOnce("retint slider failed - " + t);
            return 0;
        }
    }

    /** 让媒体卡片按用户挑的颜色把四颗按钮重涂一遍（重跑它自己的 setBtnColor）。 */
    private static int retintMediaPanel(final View view) {
        try {
            XposedHelpers.callMethod(view, METHOD_SET_BTN_COLOR);
            return 1;
        } catch (Throwable t) {
            logOnce("retint media panel failed - " + t);
            return 0;
        }
    }

    /** 在类（含父类）自己声明的方法里按「名字 + 参数个数」找一个；找不到返回 null。 */
    private static java.lang.reflect.Method methodByArity(final Class<?> cls,
            final String name, final int count) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            java.lang.reflect.Method[] declared = current.getDeclaredMethods();
            for (java.lang.reflect.Method m : declared) {
                if (m.getName().equals(name)
                        && m.getParameterTypes().length == count) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 对记下来的图标视图重跑一次系统的着色方法。
     *
     * <p>不直接把颜色抄上去：{@code tintColor()} 会按视图上<b>当前</b>缓存的状态重算一遍
     * （磁贴可能已经开 / 关过了），挂在他后面的 hook 顺势把新颜色盖上，比照抄更不容易
     * 和真实状态脱节。</p>
     */
    private static int retint(final View view) {
        try {
            XposedHelpers.callMethod(view, METHOD_TINT_COLOR);
            return 1;
        } catch (Throwable t) {
            logOnce("retint failed - " + t);
            return 0;
        }
    }

    /** 三项合计覆盖过多少次。 */
    public static int applyCount() {
        return sApplyCount;
    }

    /** 文字颜色覆盖过多少次。 */
    public static int labelCount() {
        return sLabelCount;
    }

    /**
     * 最近一次拿到的磁贴状态（{@code QSTile.State.state}，2 = 激活、1 = 关闭）。
     *
     * <p>样式面板诊断行显示它：图标激活色不生效时，看这个数就能分清是
     * 「状态压根没读到（-1）」还是「状态读到了、颜色没写上」。</p>
     */
    public static int lastState() {
        return sLastState;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 统一的图标着色落地：算出颜色 → 设到图标 ImageView 上。
     *
     * @param source 用来判形状、取 Context 的视图
     * @param active 是不是激活态
     * @param icon   已知的图标 ImageView；传 null 就现找
     * @param state  权威状态值，仅用于诊断
     * @param entry  哪条入口进来的（样式面板诊断行会显示）
     */
    private static void applyIconTint(final View source, final boolean active,
            final ImageView icon, final int state, final String entry) {
        sLastState = state;
        String shape = shapeOf(source);
        if (!TileLongPressConfig.supportsTint(shape)) {
            probe(entry, "形状不认: " + shape);
            return;
        }
        if (TileLongPressConfig.SHAPE_THREE.equals(shape)) {
            // 三阶段卡片里有自己的三个图标，走 tintIconColor 那条；这里再盖一次只会打架
            probe(entry, "三阶段交给 tintIconColor");
            return;
        }
        Context ctx = source.getContext();
        if (ctx == null) {
            probe(entry, "没有 Context");
            return;
        }
        // 走到这里说明不是三阶段：面板里只有一项「图标颜色」（存在 OFF 那一格里），
        // 开 / 关两态都用它——不然磁贴开着时的图标色又回到系统色，看着就是「改了没用」
        int argb = shapeIconColor(ctx, shape, TileLongPressConfig.TINT_ICON_OFF);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            probe(entry, "跟随系统");
            return;
        }
        ImageView target = icon != null ? icon : iconOf(source);
        if (target == null) {
            probe(entry, "找不到图标 ImageView shape=" + shape);
            return;
        }
        try {
            target.setImageTintList(ColorStateList.valueOf(argb));
            note(sIconViews, source, shape);
            sApplyCount++;
            probe(entry, "ok " + shape + " st=" + state + " " + hex(argb));
        } catch (Throwable t) {
            probe(entry, "上色失败 " + t);
            logOnce("icon tint failed - " + t);
        }
    }

    /**
     * 形状判定统一交回拦截器（它认得设备卡片 / 媒体面板 / 三种预览）。
     *
     * <p>刻意<b>不</b>用 {@link #sIconViews} 里记下的值：编辑面板的预览会在同一个视图上
     * 换 span（1×1 → 2×1 → 三阶段），照旧值走就会给错形状着色。那几张表只用来
     * 「记住有哪些视图要刷新」，形状每次都现场判。</p>
     */
    private static String shapeOf(final View view) {
        return TileLongPressInterceptor.shapeOfView(view);
    }

    /** 视图上缓存的磁贴状态；找不到返回 -1。 */
    private static int stateOfTile(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 8; depth++) {
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
            if (current == null) {
                break;
            }
            int state = intField(current, FIELD_STATE, -1);
            if (state >= 0) {
                return state;
            }
            // 有的实现把这个字段存成 QSTile$State 对象而不是 int
            Object holder = objectField(current, FIELD_STATE);
            if (holder != null) {
                int inner = intField(holder, "state", -1);
                if (inner >= 0) {
                    return inner;
                }
            }
        }
        return -1;
    }

    private static void note(final Map<View, String> seen, final View view, final String shape) {
        if (view != null && shape != null) {
            seen.put(view, shape);
        }
    }

    private static List<View> snapshot(final Map<View, String> seen) {
        synchronized (seen) {
            return new ArrayList<View>(seen.keySet());
        }
    }

    private static int intField(final Object target, final String name, final int def) {
        try {
            return XposedHelpers.getIntField(target, name);
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Object objectField(final Object target, final String name) {
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> findClassIfExists(final String name, final LoadPackageParam lpparam) {
        Class<?> cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader);
        if (cls == null) {
            // 换个实现试试：这条链路的类名对不上时，样式面板里的着色看着就是「没反应」
            XposedBridge.log(TAG + ": " + name + " not found");
        }
        return cls;
    }

    /**
     * 在<b>指定类自己声明</b>的方法上挂钩子。
     *
     * <p>不能用 {@code findAndHookMethod}：它找不到时会往父类走，一旦走到
     * {@code QSIconView} / {@code QSTileView} 这种公共父类，就等于把整套视图都钩了。</p>
     */
    private static int hookOnClass(Class<?> cls, String method, XC_MethodHook hook,
            Class<?>... params) {
        try {
            java.lang.reflect.Method target = XposedHelpers.findMethodExactIfExists(
                    cls, method, params);
            if (target == null) {
                XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + method
                        + " not declared, skip");
                return 0;
            }
            XposedBridge.hookMethod(target, hook);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + cls.getSimpleName() + "." + method
                    + " failed - " + t);
            return 0;
        }
    }

    /**
     * 按<b>参数个数</b>在类自己声明的方法里挂钩子。
     *
     * <p>给 {@code OplusQSIconView#setIcon} 用：它的重载不止一个，而确切签名（
     * {@code QSTile.State} 还是别的包装）在不同 ColorOS 版本上有差异，写死参数类型
     * 一旦对不上就会静默跳过。这里只认「方法名 + 参数个数」，逐个挂上去，
     * 回调里再自己判断第一个参数里有没有状态。</p>
     *
     * @return 实际挂上的重载个数
     */
    private static int hookByArity(Class<?> cls, String method, int paramCount,
            XC_MethodHook hook) {
        int n = 0;
        try {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterTypes().length != paramCount) {
                    continue;
                }
                XposedBridge.hookMethod(m, hook);
                n++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookByArity " + cls.getSimpleName() + "." + method
                    + " failed - " + t);
        }
        if (n == 0) {
            XposedBridge.log(TAG + ": " + cls.getSimpleName() + "." + method
                    + "/" + paramCount + " not declared, skip");
        }
        return n;
    }

    /** 同一条日志只打一次，避免每帧刷屏。 */
    private static final java.util.Set<String> sLogged =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void logOnce(final String message) {
        if (sLogged.add(message)) {
            XposedBridge.log(TAG + ": " + message);
        }
    }
}
