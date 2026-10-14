package com.thexiaoxia43.coloros.theme;

/**
 * 跨进程共用的 SharedPreferences 键与默认值。
 * 这个类只放纯常量，不 import 任何 Xposed API——
 * SettingsActivity（主进程）和 hook 侧（SystemUI 进程）都可以安全加载。
 */
public final class PrefKeys {

    public static final String PREFS_NAME = "SystemUIHookPrefs";

    // ---- 用户可配置的 hook 参数 ----
    public static final String KEY_REPLACE_TILE_BG = "replaceTileBackground";
    public static final String KEY_TILE_BG_DARK_ONLY = "tileBackgroundDarkOnly";
    public static final String KEY_TILE_BG_FORCE_ALL = "tileBackgroundForceAll";
    public static final String KEY_TILE_BG_COLOR = "tileBackgroundColor";
    public static final String KEY_TILE_BG_CORNER = "tileBackgroundCornerRadius";
    public static final String KEY_TILE_BG_IMAGE = "tileBackgroundImagePath";
    public static final String KEY_PER_TILE_JSON = "perTileConfigJson";
    public static final String KEY_LOG_TILE_SPECS = "logTileSpecs";
    /** 是否在控制中心里显示 ★ 快捷入口按钮（关掉之后长按样式面板也进不去）。 */
    public static final String KEY_SHOW_ENTRANCE_BUTTON = "showEntranceButton";

    // ---- 按形状设置的自定义底图（Base64 JPEG，键为 前缀+形状标识）----
    /** 例如 {@code shapeBgImage_1x1}。存 Base64 是为了绕开跨进程读文件/URI 权限。 */
    public static final String KEY_SHAPE_BG_IMAGE_PREFIX = "shapeBgImage_";

    /**
     * 控制中心<b>面板背景</b>在底图通道里占用的“形状”标识。
     *
     * <p>背景图和磁贴底图走完全同一套链路（选图 → 广播 → SystemUI prefs → Base64），
     * 只是键不同，所以借形状位占一个不会与真实形状（1x1/2x1/three）冲突的标识。</p>
     */
    public static final String SHAPE_QS_BACKGROUND = "qsbg";

    /**
     * 亮度 / 音量胶囊的<b>进度块</b>在底图通道里占用的“形状”标识。
     *
     * <p>胶囊底图（{@code TileLongPressConfig.SHAPE_SLIDER}）铺的是整根胶囊、把进度那块
     * 挖空；进度块本身是系统在 {@code drawActiveTrack} 里直接画的，没有 drawable 可以换，
     * 想给它单独贴图只能再借一个形状位存这张图。和 {@link #SHAPE_QS_BACKGROUND} 一样，
     * 它不是磁贴、没有开 / 关之分，键里也就没有 {@code _on/_off} 后缀。</p>
     */
    public static final String SHAPE_SLIDER_PROGRESS = "sliderProg";

    // ---- 模块设置页 → SystemUI 的底图广播（图片通道）----
    /**
     * 模块设置页选好底图后发给 SystemUI 的广播 action。
     *
     * <p>实测 SystemUI 侧用 {@code XSharedPreferences} 读不到模块 App 的 prefs，
     * 所以图片不走跨进程 prefs，改由广播把 JPEG 字节送进 SystemUI，
     * 由 SystemUI 写进自己的 prefs（{@code systemui_hook_ui}）后再读。</p>
     */
    public static final String ACTION_SHAPE_BG_IMAGE = "com.thexiaoxia43.coloros.theme.SHAPE_BG_IMAGE";
    /** 模块 App 包名（SystemUI 侧回包用）。 */
    public static final String MODULE_PACKAGE = "com.thexiaoxia43.coloros.theme";
    /**
     * 模块设置页 → SystemUI 的「在不在」探测。
     *
     * <p>模块 App 自己没法判断 LSPosed 有没有把模块注入 SystemUI，所以主页的
     * 「模块已激活」是这么来的：发一条 {@link #ACTION_PING} 给 SystemUI，
     * {@code TileImageChannel} 收到后回一条 {@link #ACTION_PONG}；收得到才算激活。</p>
     */
    public static final String ACTION_PING = "com.thexiaoxia43.coloros.theme.PING";
    /** SystemUI → 模块设置页的回包，见 {@link #ACTION_PING}。 */
    public static final String ACTION_PONG = "com.thexiaoxia43.coloros.theme.PONG";
    /**
     * 模块设置页 → SystemUI 的「重启系统界面」请求。
     *
     * <p>重启就是杀掉 SystemUI 进程（{@link TileStylePopup#restartSystemUi}），
     * 但那个方法杀的是<b>调用方所在进程</b>——在模块 App 里直接调只会把模块自己杀掉。
     * 所以设置页那颗按钮发这条广播，由 SystemUI 进程内的接收器代它执行。</p>
     */
    public static final String ACTION_RESTART_SYSTEMUI = "com.thexiaoxia43.coloros.theme.RESTART_SYSTEMUI";
    /**
     * 模块设置页 → SystemUI 的「重置所有配置」请求。
     *
     * <p>圆角 / 可见度 / 长按开关 / 按形状的底图这些真正的配置存在
     * <b>SystemUI 自己的</b> prefs（{@code systemui_hook_ui}）里，和模块 App 的
     * {@code SystemUIHookPrefs} 是两个文件。设置页清不掉那一份，必须让 SystemUI
     * 自己清，见 {@code TileLongPressConfig#clearAll}。</p>
     */
    public static final String ACTION_RESET_CONFIG = "com.thexiaoxia43.coloros.theme.RESET_CONFIG";
    /**
     * 模块设置页 → SystemUI 的「★ 按钮可见性变了」通知。
     *
     * <p>不通知的话，已经画出来的那颗 ★ 要等到下次控制中心排列变化才会被摘掉，
     * 看起来就是「开关关了但按钮还在」。</p>
     */
    public static final String ACTION_ENTRANCE_CHANGED = "com.thexiaoxia43.coloros.theme.ENTRANCE_CHANGED";
    /**
     * {@link #ACTION_ENTRANCE_CHANGED} 附带：★ 按钮是否可见。
     *
     * <p>值必须随广播带过来：SystemUI 侧读不到模块 App 的 prefs，只能由设置页
     * 把开关的新状态送过去，SystemUI 再写进自己的 prefs（见
     * {@code TileLongPressConfig#setEntranceVisible}）。</p>
     */
    public static final String EXTRA_ENTRANCE_VISIBLE = "systemuihook.entranceVisible";
    /** SystemUI 包名：广播定向发过去，避免其它应用也收到这张图。 */
    public static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /**
     * LSPosed 管理器包名。
     *
     * <p>模块设置页拿它的 versionName / versionCode 显示「LSPosed 2.2.0 (7854)」——
     * 这就是 LSPosed 这个 Magisk 模块对外暴露的版本号。清单里的
     * {@code <queries>} 必须声明它，否则 Android 11+ 的包可见性会直接抛
     * {@code NameNotFoundException}。</p>
     */
    public static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    /** 广播附带：形状标识（1x1/2x1/three）。 */
    public static final String EXTRA_BG_SHAPE = "systemuihook.bgShape";
    /** 广播附带：图片字节（相册选图是 JPEG，主题包导入是 PNG）。 */
    public static final String EXTRA_BG_BYTES = "systemuihook.bgBytes";
    /** 广播附带：底图属于哪个状态（{@link #STATE_ON} / {@link #STATE_OFF}）。 */
    public static final String EXTRA_BG_STATE = "systemuihook.bgState";
    /**
     * 广播附带：静默写盘（批量导入用）。
     *
     * <p>主题包导入一次要写九个形状，逐条重启的话第一条之后进程就没了，所以那批广播
     * 带上这一位：SystemUI 侧只写盘不重启，由设置页再发一条
     * {@link #ACTION_RESTART_SYSTEMUI} 统一重启并落盘。</p>
     */
    public static final String EXTRA_BG_SILENT = "systemuihook.bgSilent";

    /**
     * 模块设置页 → SystemUI 的「这个形状的圆角按素材比例算」通知（主题包导入用）。
     *
     * <p>主题包自带的界面底图本身就画好了圆角（比如 2×1 那张 520px 宽的图圆角 43px），
     * 导入时如果还按模块的 20dp 画，底图自己的圆角就会被切掉一条边、看着和主题设计
     * 的不一样。所以导入时把「素材圆角 ÷ 素材宽」这个比例一并送进 SystemUI，绘制时
     * 再按磁贴实际宽度换算成像素。</p>
     *
     * <p>和底图广播一样只能走广播：SystemUI 侧读不到模块 App 的 prefs。收到后写进
     * SystemUI 自己的 prefs，见 {@code TileLongPressConfig#setCornerRatio}。</p>
     */
    public static final String ACTION_SHAPE_CORNER = "com.thexiaoxia43.coloros.theme.SHAPE_CORNER";
    /**
     * {@link #ACTION_SHAPE_CORNER} 附带：圆角比例（圆角像素 ÷ 素材宽度）。
     *
     * <p>存比例而不是像素，是因为素材尺寸和磁贴尺寸不保证 1:1（同一个形状在不同机型、
     * 不同排列下宽度都不一样），只有比例才是尺度无关的。</p>
     */
    public static final String EXTRA_CORNER_RATIO = "systemuihook.cornerRatio";

    /**
     * {@link #ACTION_SHAPE_BG_IMAGE} 可选附带：控制中心背景的纯色 ARGB（主题包导入用）。
     *
     * <p>主题包的颜色表里有 {@code notification_material_background_color}
     * （「通知材质背景色」，就是通知 / 控制中心那一层的背景）与
     * {@code notification_scrim_bg_mask_color}。它是<b>整个背景</b>一份，
     * 不分形状，所以每趟底图广播都带同一个值，谁先到谁先写——写的是同一个值，
     * 重复落盘不会有影响。</p>
     *
     * <p>为什么不单开一条 action：新 action 要等 SystemUI 用新代码重启过、
     * 接收器重新注册之后才接得住，第一次导入必然丢（同 {@link #EXTRA_ICON_COLOR}）。</p>
     */
    public static final String EXTRA_QS_BG_COLOR = "systemuihook.qsBgColor";

    /**
     * {@link #ACTION_SHAPE_BG_IMAGE} 可选附带：图标底色的 ARGB（主题包导入用）。
     *
     * <p>主题包的颜色表里有 {@code status_bar_qs_tile_bg_color_active}
     * （「状态栏快捷设置图标激活时的背景颜色」），那正是 2×1 宽卡片与三阶段卡片图标
     * 底下那块小色块的底色。它<b>搭底图那条广播一起来</b>，不另开 action：
     * 那条通道本来就在用（底图能到就说明它通），而新开一个 action 要等 SystemUI
     * 用新代码重启过、接收器重新注册之后才接得住，第一次导入必然丢。</p>
     *
     * <p>带 alpha 一起送：模块侧的底色是「纯色值 + 不透明度」两段存的
     * （见 {@code TileLongPressConfig.Style#iconArgb}），alpha 得拆出来单独写。</p>
     */
    public static final String EXTRA_ICON_COLOR = "systemuihook.iconColor";
    /**
     * 有「图标底色」这一项的形状：2×1 宽卡片与 2×1 三阶段卡片。
     *
     * <p>这两个形状的图标底下压着一块小色块，其余形状（1×1 圆形磁贴等）没有，
     * 所以主题包颜色表里的图标底色只往这两个形状上套。</p>
     *
     * <p>放在这里而不是 {@code TileLongPressConfig} 里：模块 App 侧也要用到这份名单
     * （导入主题包时按形状逐个发广播），而那个类带着 Xposed API，App 进程碰不得。</p>
     */
    public static final String[] ICON_COLOR_SHAPES = {"2x1", "three"};

    /**
     * {@link #ACTION_SHAPE_BG_IMAGE} 可选附带：<b>磁贴图标着色</b>（激活 / 关）。
     *
     * <p>同样的搭车理由，见 {@link #EXTRA_ICON_COLOR}。三项各自带一份 ARGB：模块侧
     * 存的是「纯色值 + 不透明度」两段，alpha 由调用方折进 ARGB 再送过来。</p>
     *
     * <p>名字对得上系统资源 {@code status_bar_qs_tile_icon_color_active} /
     * {@code status_bar_qs_tile_icon_color_inactive}——主题包颜色表里有的键就直接填进这两项，
     * 没有的话用户到样式面板里自己挑（见 {@code TileLongPressConfig.TINT_ICON_ON}）。</p>
     */
    public static final String EXTRA_ICON_TINT_ON = "systemuihook.iconTintOn";
    /** {@link #EXTRA_ICON_TINT_ON} 的「关」那一路。 */
    public static final String EXTRA_ICON_TINT_OFF = "systemuihook.iconTintOff";
    /**
     * {@link #ACTION_SHAPE_BG_IMAGE} 可选附带：<b>磁贴文字颜色</b>的 ARGB。
     *
     * <p>对应系统资源 {@code status_bar_qs_tile_label_color}。</p>
     */
    public static final String EXTRA_LABEL_COLOR = "systemuihook.labelColor";

    /**
     * {@link #ACTION_SHAPE_BG_IMAGE} 可选附带：<b>模块自有配置的整份快照</b>
     * （{@link #ACTION_THEME_EXPORT} 导出的包里那张 extras 表）。
     *
     * <p>主题包标准格式只装得下 5 条颜色 + 若干张底图，装不下模块的全部修改项
     * （每个形状的圆角 / 可见度、图标底色与四种着色的不透明度、控制中心背景色、
     * 各式开关）。这些项在「导入」时没法用固定的几个字段逐个表达，所以导出时把
     * SystemUI prefs 里<b>除图片以外</b>的键整份写进 extras 表，导入时原样倒回去。</p>
     *
     * <p>底图不放进这张表：它们已经走 {@link #ACTION_SHAPE_BG_IMAGE} 这条字节通道，
     * 再塞一份会让广播体积翻倍（底图 Base64 动辄几百 KB，必然顶爆 Binder 事务）。</p>
     *
     * <p>搭车而不是新开 action 的理由同 {@link #EXTRA_ICON_COLOR}：新 action 要等
     * SystemUI 用新代码重启过之后才接得住。</p>
     */
    public static final String EXTRA_EXTRAS_XML = "systemuihook.extrasXml";

    /**
     * 有「图标着色 / 文字颜色」这一项的<b>主控制中心磁贴</b>形状。
     *
     * <p>覆盖 1×1 圆形磁贴、2×1 宽卡片、2×1 三阶段卡片、1×1 方形卡片
     * （设备卡片，走 deviceplugin SDK 那条独立路径）、1×2 设备卡片（与方形卡片
     * <b>共用同一格颜色</b>，面板要能出这一项，读写仍落在方形卡片那格）、2×2 大卡片
     * （{@code OplusQSResizeableTileViewTwoXTwo}，图标同样是 {@code OplusQSIconView}）、
     * 媒体播放卡片（{@code OplusQs*MediaPanelView}，那条链路重涂的是播放 / 上一首 /
     * 下一首 / 投屏四颗按钮，见 {@code TileColorHook#applyMediaBtnColor}）、
     * 亮度 / 音量胶囊（图标是 {@code ClipBrightnessView} 的 Drawable 与
     * {@code OplusQsVolumeIconView} 自己，各有一条独立的着色入口），
     * 以及控制中心右上角那排小圆钮（★ / ✎ / ⚙ / ⋮，见 {@code QuickEntranceButtonHook}——
     * 它们的图标色不经过 {@code TileColorHook}，由那边自己套在按钮视图上）。</p>
     *
     * <p>放这里的原因同 {@link #ICON_COLOR_SHAPES}：模块 App 侧导入主题包时也要用，
     * 而 {@code TileLongPressConfig} 带着 Xposed API，App 进程碰不得。</p>
     */
    public static final String[] TINT_SHAPES = {
            "1x1", "entrance", "card", "cardWide", "2x1", "2x2", "three", "slider"};

    /** 设备卡片（{@code SHAPE_CARD}）侧文字颜色的资源名。 */
    public static final String DEVICE_LABEL_COLOR_NAME = "status_bar_qs_tile_label_color";

    /** 磁贴「开」状态。 */
    public static final String STATE_ON = "on";
    /** 磁贴「关」状态。 */
    public static final String STATE_OFF = "off";

    // ---- SystemUI 长按样式面板 → 模块设置页的跳转参数 ----
    /** 模块设置页类名（SystemUI 侧用它拼 Intent）。 */
    public static final String ACTIVITY_CLASS = "com.thexiaoxia43.coloros.theme.SettingsActivity";
    /**
     * 选图专用入口：{@code SettingsActivity} 的 {@code activity-alias}，
     * 在清单里配了透明主题 + 不进最近任务。
     *
     * <p>选图必须由 Activity 来拿 {@code onActivityResult}（SystemUI 不是 Activity），
     * 但用户不该看见模块 App 的界面从后台弹出来，所以这条链路走别名：
     * 窗口全透明、不设任何内容视图，用户看到的就是「相册直接打开了」。</p>
     */
    public static final String ACTIVITY_PICK_CLASS = "com.thexiaoxia43.coloros.theme.PickImageAlias";
    /** 要改底图的形状标识。 */
    public static final String EXTRA_PICK_SHAPE = "systemuihook.pickShape";
    /** 要改底图的状态（{@link #STATE_ON} / {@link #STATE_OFF}）。 */
    public static final String EXTRA_PICK_STATE = "systemuihook.pickState";
    /**
     * 走同一条透明入口，但目标是<b>导入主题包</b>而不是选底图。
     *
     * <p>★ 弹窗里的「导入主题包」也需要一个 Activity 来拿选文件的结果（SystemUI 不是
     * Activity），而且用户不该看见模块 App 的界面，所以复用
     * {@link #ACTIVITY_PICK_CLASS} 那个透明别名，用这一位区分该起相册还是起文件选择器。</p>
     */
    public static final String EXTRA_PICK_THEME = "systemuihook.pickTheme";

    /**
     * 走同一条透明入口，但目标是<b>导出主题包</b>。
     *
     * <p>导出得由 Activity 来拿「保存到哪里」的结果（SystemUI 不是 Activity），
     * 所以复用 {@link #ACTIVITY_PICK_CLASS} 那个透明别名，用这一位区分该起
     * 「新建文档」而不是文件选择器。</p>
     */
    public static final String EXTRA_PICK_THEME_EXPORT = "systemuihook.pickThemeExport";

    /**
     * 模块设置页 → SystemUI 的<b>导出主题包</b>请求。
     *
     * <p>和导入刚好反向：形状底图 / 颜色这些真正的配置都存在 <b>SystemUI 自己的</b>
     * prefs 里（模块 App 读不到，见 {@link #ACTION_SHAPE_BG_IMAGE}），所以打包这件事
     * 只能由 SystemUI 做。SAF 的目标地址由模块 App 那边的透明 Activity 选好后，
     * 通过这条广播带过去（{@link #EXTRA_THEME_EXPORT_URI}），SystemUI 直接写进去。</p>
     *
     * <p>只传一个 Uri 字符串而不是把图塞进广播：图片全在 SystemUI 进程里，
     * 没必要绕一圈过 Binder（几 MB 的底图必然顶爆事务缓冲区）。</p>
     */
    public static final String ACTION_THEME_EXPORT = "com.thexiaoxia43.coloros.theme.THEME_EXPORT";
    /** {@link #ACTION_THEME_EXPORT} 附带：要写进去的 {@code content://} 地址。 */
    public static final String EXTRA_THEME_EXPORT_URI = "systemuihook.themeExportUri";

    public static final boolean DEF_REPLACE_TILE_BG = false;
    public static final boolean DEF_DARK_ONLY = true;
    public static final boolean DEF_FORCE_ALL = true;
    public static final boolean DEF_SHOW_ENTRANCE_BUTTON = true;
    public static final int DEF_TILE_BG_COLOR = 0x33FFFFFF;
    public static final int DEF_TILE_BG_CORNER = 11;  // 真实 status_bar_qs_tile_bg_* 圆角
    public static final String DEF_TILE_BG_IMAGE = "";

    // ---- SystemUI 真实资源（由 SystemResourceHarvester 在 hook 侧采集） ----
    /** 采集标记：harvester 是否跑过 */
    public static final String REAL_HARVESTED = "real_resources_harvested_v1";
    /** 控制中心磁贴真实背景色（从 SystemUI R.color 读） */
    public static final String REAL_TILE_BG_COLOR = "real_tile_bg_color";
    /** 控制中心磁贴真实圆角 dp（从 SystemUI R.dimen 读） */
    public static final String REAL_TILE_CORNER_DP = "real_tile_corner_dp";
    /** 磁贴文字颜色 */
    public static final String REAL_TEXT_COLOR = "real_text_color";
    /** 磁贴图标 tint */
    public static final String REAL_ICON_TINT = "real_icon_tint";
    /** icon base64 前缀：real_icon_ 后面跟 spec（wifi/bt/...） */
    public static final String REAL_ICON_PREFIX = "real_icon_";

    private PrefKeys() {}
}
