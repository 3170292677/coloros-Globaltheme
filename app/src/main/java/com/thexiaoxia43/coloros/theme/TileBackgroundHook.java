package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 暗色模式下替换控制中心（快捷设置）磁贴底图与图标，支持<b>每磁贴独立配置</b>。
 *
 * <p>实现依据是对 ColorOS 16 / SystemUI 16.00.12 的 {@code classes6.dex} 反编译结果，
 * 相关类都在 {@code com.oplus.systemui.qs.*} 包中：</p>
 *
 * <pre>
 * QsViewBackgroundProxy                         底图代理基类
 *   ├── getQsTileViewBackgroundProxy(QsTileViewInfoProvider)   静态工厂
 *   ├── refreshViewBackground() / onBackgroundAttach()          触发底图刷新
 *   └── 字段 qsView : QsViewInfoProvider  通过 getBackgroundView() 拿到承载底图的 View
 * QsTileViewBackgroundProxyImpl                  磁贴底图代理
 *   ├── $GlobalThemeBaseQsTileViewBackground    全局主题底图基类
 *   │       obtainDrawableForActive/Inactive/Unavailable(Context) -> Drawable
 *   ├── $GlobalThemeBitmapTileViewBackground    全局主题位图底图（主要替换目标）
 *   ── $GlobalThemeColorTileViewBackground     全局主题纯色底图
 * QsHighlightTileViewBackgroundProxyImpl         高亮（大尺寸）磁贴底图代理
 * OplusQSTileBaseView                            磁贴视图
 *   ├── 字段 mTileSpec : String                  磁贴身份（wifi/bt/flashlight...）
 *   ├── 字段 mBackgroundProxy                    底图代理
 *   └── 字段 mBg / mIcon                         底图 ImageView / 图标视图
 * OplusQSIconViewImpl                            磁贴图标视图
 *   ├── setIcon / updateIcon (ImageView, QSTile$State, boolean)  图标绘制入口
 *   └── updateIconTintColor (ImageView, int, boolean)            图标着色入口
 * </pre>
 *
 * <p>磁贴身份解析：从底图/图标 View 沿视图树向上找到 {@code OplusQSTileBaseView}
 * 实例，读 {@code mTileSpec} 字段；底图代理侧则先经 {@code qsView} 字段拿到
 * {@code getBackgroundView()} 再向上走。拿到 spec 后即可查每磁贴配置
 * （{@link TileConfigs}），未配置的磁贴回落到全局设置。</p>
 *
 * <p>底图参数的取值优先级：<b>长按样式面板（按磁贴形状）</b> → 每磁贴配置 →
 * 通配 {@code *} → 全局设置。按形状的样式（圆角 / 可见度 / 底图）由
 * {@link TileStylePopup} 设置，形状标识由 {@link TileLongPressInterceptor} 在磁贴
 * 布局时判定；它不受设置页「启用替换」「仅暗色模式」开关限制。</p>
 *
 * <p>Hook 策略分四层：</p>
 * <ol>
 *   <li><b>底图工厂层</b>：拦截 {@code obtainDrawableFor*} 的返回值，直接换成自定义圆角底图，
 *       主题位图根本不会被应用到磁贴；</li>
 *   <li><b>纯色/混合色层</b>：挂 {@code updateBackground(int, boolean, boolean)}，覆盖
 *       {@code NormalColorTileViewBackground}、{@code MixColorTileViewBackground} 等非位图分支；</li>
 *   <li><b>视图兜底层</b>：在 {@code refreshViewBackground} / {@code onBackgroundAttach} 之后给
 *       {@code getBackgroundView()} 重新设置底图，并在磁贴视图 {@code onConfigurationChanged}
 *       后主动触发刷新，保证深/浅色切换立即生效；</li>
 *   <li><b>图标层</b>：在 {@code setIcon}/{@code updateIcon} 之后换成自定义图标，
 *       清除着色，并拦截 {@code updateIconTintColor} 防止系统把自定义图标重新染色。</li>
 * </ol>
 */
public final class TileBackgroundHook {

    private static final String TAG = "SystemUIHook/TileBg";

    private static final String CLASS_BG_PROXY =
            "com.oplus.systemui.qs.base.widget.QsViewBackgroundProxy";
    private static final String CLASS_TILE_BG_PROXY =
            "com.oplus.systemui.qs.base.widget.QsTileViewBackgroundProxyImpl";
    private static final String CLASS_HIGHLIGHT_BG_PROXY =
            "com.oplus.systemui.qs.base.widget.QsHighlightTileViewBackgroundProxyImpl";
    private static final String CLASS_STATIC_BG_PROXY =
            "com.oplus.systemui.qs.base.widget.QsStaticBackgroundProxy";
    private static final String CLASS_TILE_BASE_VIEW =
            "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView";
    private static final String CLASS_TILE_BASE_VIEW_IMPL =
            "com.oplus.systemui.qs.tileimpl.OplusQSTileBaseViewImpl";
    private static final String CLASS_TILE_VIEW_IMPL =
            "com.oplus.systemui.qs.tileimpl.OplusQSTileViewImpl";
    /** 高亮（大尺寸）磁贴视图。 */
    private static final String CLASS_HIGHLIGHT_TILE_VIEW =
            "com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView";
    /**
     * 编辑/缩放面板里 1×1 / 2×1 / 三阶段预览的基类。
     *
     * <p>反编译确认它直接继承 {@code QSTileView}，<b>不是</b>
     * {@code OplusQSTileBaseView} 那条链，所以拿不到 {@code handleStateChanged}
     * 那个状态钩子；它自己的状态入口是 {@code onStateChanged} /
     * {@code handleTileStateChange}，两个都是 {@code QSTile$State} 参数。</p>
     */
    private static final String CLASS_RESIZEABLE_TILE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView";

    /**
     * {@code updateBackground(Drawable)} 的候选类。
     *
     * <p><b>关键（反编译 {@code classes6.dex} 确认）</b>：这些类上声明的
     * {@code updateBackground(Drawable)} 访问标志全是 {@code 0x1041}
     * （{@code ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC}），也就是 Kotlin 生成的
     * <b>桥接方法</b>，方法体只有一句 {@code invoke-super QsViewInfoProvider.updateBackground}。
     * 真正干活的是接口 {@code QsViewInfoProvider} 上的 default 实现
     * （访问标志 {@code 0x1}，方法体是 {@code getBackgroundView().setBackground(...)}）。</p>
     *
     * <p>所以这里既挂具体类（防桥接被内联），也在 {@link #hookUpdateBackgroundInterface}
     * 里直接挂接口 default 方法——那才是所有底图路径的唯一落点。</p>
     */
    private static final String[] UPDATE_BACKGROUND_CLASSES = {
            CLASS_TILE_BASE_VIEW,
            CLASS_TILE_BASE_VIEW_IMPL,
            CLASS_TILE_VIEW_IMPL,
            CLASS_HIGHLIGHT_TILE_VIEW,
            "com.oplus.systemui.qs.tileimpl.OplusQSHighlightTileViewImpl",
            "com.oplus.systemui.qs.customize.OplusCustomizeTileViewImpl",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableThreeStageView",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewOneXOne",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne",
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXTwo",
            "com.oplus.systemui.qs.base.widget.QsStaticViewInfoProvider",
            "com.oplus.systemui.plugins.qs.widget.OplusPreviewTileViewInfoProvider",
            "com.oplus.systemui.plugins.qs.widget.OplusQsButtonViewInfoProvider",
            "com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView",
            "com.oplus.systemui.qs.media.OplusQsLargeMediaPanelView",
            "com.oplus.systemui.qs.media.OplusQsNormalMediaPanelView",
    };

    /**
     * 声明 {@code updateBackground(Drawable)} default 实现的接口。
     *
     * <p>接口上的 default 方法<b>不是</b> bridge/synthetic，是唯一有真实方法体的实现，
     * 所有具体类（含桥接方法）最终都走到这里。挂它一条，就覆盖了全部底图路径。</p>
     */
    private static final String[] UPDATE_BACKGROUND_INTERFACES = {
            "com.oplus.systemui.qs.base.widget.QsViewInfoProvider",
            "com.oplus.systemui.qs.base.widget.QsTileViewInfoProvider",
    };

    /**
     * 声明 {@code updateBackgroundMask(Drawable)} default 实现的接口。
     *
     * <p>反编译确认它的方法体是 {@code getBackgroundView().setForeground(drawable)}，
     * 也就是给磁贴背景视图盖一层<b>前景遮罩</b>。</p>
     */
    private static final String CLASS_TILE_VIEW_INFO_PROVIDER =
            "com.oplus.systemui.qs.base.widget.QsTileViewInfoProvider";
    private static final String CLASS_ICON_VIEW =
            "com.oplus.systemui.qs.tileimpl.OplusQSIconViewImpl";
    private static final String CLASS_TILE_STATE =
            "com.android.systemui.plugins.qs.QSTile$State";
    /** 磁贴视图上承载磁贴身份的字段名。 */
    private static final String FIELD_TILE_SPEC = "mTileSpec";
    /**
     * 磁贴视图上「当前是不是开着」的布尔字段。
     *
     * <p>反编译确认它声明在 {@code OplusQSTileBaseView} 上，主面板所有形状
     * （{@code OplusQSTileViewImpl} / {@code OplusQSTileBaseViewImpl}）都继承得到，
     * 由 {@code handleStateChanged} 从 {@code QSTile.State} 的 active 值写入。
     * 编辑面板的 {@code OplusQSResizeableTileView} 系列没有这个字段，取不到时按「关」算。</p>
     */
    private static final String FIELD_TILE_ACTIVE = "mTileState";
    /**
     * {@code QSTile$State} 上的 int 状态字段。
     *
     * <p>反编译确认底图代理 {@code GlobalThemeBaseQsTileViewBackground.handleStateChanged}
     * 就是拿它去调 {@code updateBackground$1(int)}，并据此在
     * {@code status_bar_qs_tile_bg_active / _inactive / _unavailable} 之间选底图。
     * 取值同 AOSP：{@code 0=UNAVAILABLE, 1=INACTIVE, 2=ACTIVE}。</p>
     */
    private static final String FIELD_TILE_STATE = "state";

    /** 编辑/缩放面板预览视图上的状态入口，签名均为 {@code (QSTile$State)V}。 */
    private static final String[] RESIZEABLE_STATE_METHODS = {
            "onStateChanged",
            "handleTileStateChange",
    };

    /** 底图工厂方法，签名均为 {@code Drawable obtainDrawableForXxx(Context)}。 */
    private static final String[] DRAWABLE_FACTORY_METHODS = {
            "obtainDrawableForActive",
            "obtainDrawableForInactive",
            "obtainDrawableForUnavailable",
    };
    /** 上面三个工厂里代表「磁贴开着」的那一个。 */
    private static final String FACTORY_ACTIVE = "obtainDrawableForActive";

    /**
     * 真正提供 {@code obtainDrawableFor*} 的“位图/主题底图”类。
     *
     * <p>注意：只有 {@code $GlobalThemeBaseQsTileViewBackground} 声明了这三个方法，
     * 其余三个子类通过继承获得。纯色系（{@code $GlobalThemeColorTileViewBackground}
     * 等）<b>没有</b>这些方法，它们由 {@link #COLOR_UPDATE_CLASSES} 覆盖。</p>
     */
    private static final String[] DRAWABLE_FACTORY_CLASSES = {
            CLASS_TILE_BG_PROXY + "$GlobalThemeBaseQsTileViewBackground",
            CLASS_TILE_BG_PROXY + "$GlobalThemeBitmapTileViewBackground",
            CLASS_TILE_BG_PROXY + "$GlobalThemeHighLightCustomizerViewBackground",
            CLASS_TILE_BG_PROXY + "$GlobalThemePersonalityDisabledViewBackground",
    };

    /**
     * 纯色 / 混合色磁贴底图类，通过 {@code updateBackground(int,boolean,boolean)} 着色。
     *
     * <p>{@code $GlobalThemeColorTileViewBackground} 与 {@code $LightOsColorTileViewBackgroundImpl}
     * 继承自 {@code NormalColorTileViewBackground}，因此只需挂基类；
     * {@code $NormalColorHighLightTileViewBackground} 重写了该方法，需单独挂。</p>
     */
    private static final String[] COLOR_UPDATE_CLASSES = {
            "com.oplus.systemui.qs.base.widget.NormalColorTileViewBackground",
            "com.oplus.systemui.qs.base.widget.MixColorTileViewBackground",
            "com.oplus.systemui.qs.base.widget.QsHighlightTileViewBackgroundProxyImpl"
                    + "$NormalColorHighLightTileViewBackground",
    };

    /** 纯色底图着色方法的参数表：{@code updateBackground(int, boolean, boolean)}。 */
    private static final Class<?>[] COLOR_UPDATE_PARAMS = {int.class, boolean.class, boolean.class};

    /** 底图刷新入口方法（无参），刷新后可直接覆盖目标 View 的背景。 */
    private static final String[] REFRESH_METHODS = {
            "refreshViewBackground",
            "onBackgroundAttach",
    };

    /** 磁贴视图类（install 时解析），用于从任意子 View 向上识别磁贴。 */
    private static volatile Class<?> sTileViewClass;

    /** 图标被替换过的 ImageView（弱引用集合），用于拦截系统重新着色。 */
    private static final Set<ImageView> sOverriddenIconViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<ImageView, Boolean>()));

    /** 已记录过 spec 的磁贴（日志开关打开时每个 spec 只记一次）。 */
    private static final Set<String> sLoggedSpecs = Collections.synchronizedSet(new HashSet<String>());

    /** 位图缓存：路径 → 位图 / 文件 mtime，文件变更时自动重新解码。 */
    private static final Object sBitmapLock = new Object();
    private static final Map<String, Bitmap> sBitmapCache = new HashMap<String, Bitmap>();
    private static final Map<String, Long> sBitmapTimes = new HashMap<String, Long>();
    /**
     * 按形状保存的底图（Base64）解码缓存，键为 {@code 长度:hashCode}。
     *
     * <p>只留最近几张而不是一张：亮度 / 音量胶囊会同时用两张图（胶囊底图 + 进度底图），
     * 每帧各取一次，只留一条的话两张会互相挤掉，等于每帧都要重新解一遍 JPEG，
     * 拖亮度 / 音量时直接卡住。</p>
     */
    private static final Map<String, Bitmap> sShapeImageCache = new HashMap<String, Bitmap>();
    /** 上面那份缓存最多留几张（够胶囊那两张图 + 余量即可）。 */
    private static final int MAX_SHAPE_IMAGE_CACHE = 4;
    /** 已打过“样式生效”日志的形状 → 参数签名，避免刷屏。 */
    private static final Map<String, String> sLoggedStyle =
            Collections.synchronizedMap(new HashMap<String, String>());
    /** 各 hook 点是否已打过“hook hit”日志。 */
    private static final Set<String> sHookHitLogged =
            Collections.synchronizedSet(new HashSet<String>());
    /** 已打过“底图侧 spec → shape”日志的 spec。 */
    private static final Set<String> sLoggedSpecShape =
            Collections.synchronizedSet(new HashSet<String>());
    /** 已按某组样式参数套过底图的背景 View → 参数指纹，避免重复套用。 */
    private static final Map<View, String> sAppliedSignatures =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    /** 底图落点的重入保护：我们自己在回调里 setBackground 时不要再进来一次。 */
    private static final ThreadLocal<Boolean> sInjecting = new ThreadLocal<Boolean>();
    /** 视图类 → {@code mTileState} 字段的反射缓存（取不到时记进 {@link #sTileActiveMissing}）。 */
    private static final Map<Class<?>, java.lang.reflect.Field> sTileActiveFields =
            Collections.synchronizedMap(new WeakHashMap<Class<?>, java.lang.reflect.Field>());
    /** 没有 {@code mTileState} 字段的视图类（编辑面板那套），避免每次重新查找。 */
    private static final Set<Class<?>> sTileActiveMissing =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new WeakHashMap<Class<?>, Boolean>()));
    /**
     * 底图实例 → 它代表「开」还是「关」。
     *
     * <p><b>为什么需要它</b>：反编译 {@code OplusQSTileBaseView.handleStateChanged} 可见，
     * 它先<b>同步</b>调 {@code mBackgroundProxy.handleStateChanged(state)}
     * （→ {@code updateBackground$1(state.state)} → {@code obtainDrawableForXxx}
     * → {@code updateBackground(Drawable)}，也就是我们的落点），
     * 之后才把 {@code QSTile$AdapterState.value} 写进 {@code mTileState}。
     * 所以在落点里读 {@code mTileState} 拿到的是<b>上一次</b>的状态，开 / 关会串。
     * 而 {@code updateBackground$1} 拿到的 drawable 就是工厂方法的返回值，
     * 工厂方法名本身就带状态，于是在工厂侧记下「这个实例是开还是关」，
     * 落点用同一个实例反查即可拿到准确状态。</p>
     */
    private static final Map<Drawable, Boolean> sDrawableActive =
            Collections.synchronizedMap(new WeakHashMap<Drawable, Boolean>());
    /**
     * 磁贴视图 → 最近一次 {@code handleStateChanged} 交过来的开关状态。
     *
     * <p>这是最可靠的一份状态：{@code OplusQSTileBaseView.handleStateChanged} 里
     * 先<b>同步</b>刷底图（走到我们的落点），之后才把状态写进 {@code mTileState}，
     * 所以落点里读字段永远是旧值。在 {@code handleStateChanged} 的<b>前置</b>钩子里
     * 把参数 {@code QSTile$State} 上的开关状态先记下来，落点就能拿到本次切换后的真值。</p>
     */
    private static final Map<View, Boolean> sViewActive =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    /** 状态探针日志去重（spec + 开/关）。 */
    private static final Set<String> sLoggedStateProbe =
            Collections.synchronizedSet(new HashSet<String>());
    /** 上一次 {@link #tileActiveState} 的状态是从哪来的，只用于日志排查。 */
    private static volatile String sActiveSource = "default";
    /** 自定义图片解码上限（单边像素），防止大图拖垮 SystemUI 内存。 */
    private static final int MAX_BITMAP_SIDE = 1024;

    private static long sLastErrorLog;

    private TileBackgroundHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        sTileViewClass = XposedHelpers.findClassIfExists(CLASS_TILE_BASE_VIEW, lpparam.classLoader);
        int factories = hookDrawableFactories(lpparam);
        int colors = hookColorBackgrounds(lpparam);
        int proxies = hookBackgroundProxies(lpparam);
        int views = hookTileViews(lpparam);
        int proxyStates = hookTileBackgroundProxyState(lpparam);
        int icons = hookIconViews(lpparam);
        int landing = hookUpdateBackground(lpparam);
        int masks = hookBackgroundMask(lpparam);
        int cards = hookCardBackgrounds(lpparam);

        // 安装资源采集：在第一次 hook 回调里触发（确保 Context 可用）
        try {
            triggerResourceHarvest(lpparam);
        } catch (Throwable t) {
            logOnce("triggerResourceHarvest: " + t);
        }

        XposedBridge.log(TAG + ": installed (drawableFactories=" + factories
                + ", colorBackgrounds=" + colors
                + ", backgroundProxies=" + proxies
                + ", tileViews=" + views + ", proxyStates=" + proxyStates
                + ", iconViews=" + icons
                + ", landing=" + landing + ", masks=" + masks
                + ", cards=" + cards + ")");
    }

    /** 拦截 Application.onCreate 来触发一次 SystemUI 真实资源采集。 */
    private static void triggerResourceHarvest(final LoadPackageParam lpparam) {
        try {
            Class<?> appClass = XposedHelpers.findClass(
                    "com.android.systemui.SystemUIApplication", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(appClass, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                SystemResourceHarvester.harvest(ctx);
                                // 底图图片靠广播从模块 App 送进来，这里尽早把接收器挂上
                                TileImageChannel.ensureReceiver(ctx);
                            } catch (Throwable t) {
                                XposedBridge.log("SystemResourceHarvest trigger failed: " + t);
                            }
                        }
                    });
            logOnce("hooked SystemUIApplication.onCreate for resource harvest");
        } catch (Throwable t) {
            // SystemUIApplication 可能不存在，兜底：hook 任意 QsView 构造器
            try {
                Class<?> qsViewClass = XposedHelpers.findClassIfExists(
                        CLASS_TILE_BASE_VIEW, lpparam.classLoader);
                if (qsViewClass != null) {
                    XposedHelpers.findAndHookConstructor(qsViewClass, Context.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Context ctx = (Context) param.args[0];
                                        SystemResourceHarvester.harvest(ctx);
                                    } catch (Throwable t2) {
                                        XposedBridge.log("fallback harvest failed: " + t2);
                                    }
                                }
                            });
                    logOnce("hooked OplusQSTileBaseView ctor for resource harvest");
                }
            } catch (Throwable t2) {
                logOnce("triggerResourceHarvest fallback failed: " + t2);
            }
        }
    }

    // ------------------------------------------------------- 第一层：底图工厂拦截

    private static int hookDrawableFactories(final LoadPackageParam lpparam) {
        int hooked = 0;
        for (String className : DRAWABLE_FACTORY_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            for (final String methodName : DRAWABLE_FACTORY_METHODS) {
                try {
                    XposedHelpers.findAndHookMethod(clazz, methodName, Context.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    // 工厂方法名自带状态：Active 就是「开」，其余算「关」
                                    replaceDrawableResult(param, FACTORY_ACTIVE.equals(methodName));
                                }
                            });
                    hooked++;
                } catch (Throwable t) {
                    logOnce("hookDrawableFactories: " + className + "#" + methodName + " - " + t);
                }
            }
        }
        return hooked;
    }

    private static void replaceDrawableResult(final XC_MethodHook.MethodHookParam param,
            final boolean active) {
        try {
            logHookHit("底图工厂 obtainDrawableFor*");
            sHookHits++;
            // 先记下系统原底图的状态：落点若拿到的是同一个实例，就能反查出准确的开 / 关
            Drawable original = asDrawable(param.getResult());
            if (original != null) {
                sDrawableActive.put(original, Boolean.valueOf(active));
            }
            Context context = contextOf(param);
            if (!HookPrefs.replaceTileBackground()
                    && !(context != null && TileLongPressConfig.anyShapeBgImage(context))
                    && !(context != null && TileLongPressConfig.anyStyle(context))) {
                // 全局替换没开、长按样式/自定义底图也没有：直接放过，不做反射解析
                return;
            }
            String spec = specFromBackgroundImpl(param.thisObject);
            logSpecIfEnabled(spec);
            ensureShapeCached(spec, backgroundViewOf(param.thisObject));
            logSpecShapeOnce(spec);
            if (!shouldApplyTile(context, spec)) {
                return;
            }
            TileConfigs.Cfg cfg = TileConfigs.forSpec(spec);
            if (cfg != null && cfg.bgKeep) {
                // 每磁贴配置要求保留系统原背景：不干预主题位图
                return;
            }
            Drawable replacement = createTileBackground(context, spec, active);
            if (replacement != null) {
                param.setResult(replacement);
                // 换成了自定义底图：这个新实例也要带上状态，落点才反查得到
                sDrawableActive.put(replacement, Boolean.valueOf(active));
            } else {
                // 该形状只调了可见度：保留系统底图的形状与配色，仅改 alpha
                applyAlphaOnly(original, context,
                        TileLongPressInterceptor.shapeOf(spec), active);
            }
        } catch (Throwable t) {
            logOnce("replaceDrawableResult: " + t);
        }
    }

    // ------------------------------------------------- 第二层：纯色/混合色底图

    private static int hookColorBackgrounds(final LoadPackageParam lpparam) {
        int hooked = 0;
        for (String className : COLOR_UPDATE_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "updateBackground", COLOR_UPDATE_PARAMS,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // 这些底图实例都持有 qsTileView / qsView（见 viewInfoOf）
                                overrideBackgroundView(param.thisObject);
                            }
                        });
                hooked++;
            } catch (Throwable t) {
                logOnce("hookColorBackgrounds: " + className + " - " + t);
            }
        }
        return hooked;
    }

    // ------------------------------------------------------- 第三层：视图背景覆盖

    private static int hookBackgroundProxies(final LoadPackageParam lpparam) {
        int hooked = hookProxyClass(lpparam, CLASS_BG_PROXY);
        hooked += hookProxyClass(lpparam, CLASS_STATIC_BG_PROXY);
        return hooked;
    }

    private static int hookProxyClass(final LoadPackageParam lpparam, final String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        int hooked = 0;
        for (String methodName : REFRESH_METHODS) {
            try {
                XposedHelpers.findAndHookMethod(clazz, methodName, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        overrideBackgroundView(param.thisObject);
                    }
                });
                hooked++;
            } catch (Throwable t) {
                logOnce("hookProxyClass: " + className + "#" + methodName + " - " + t);
            }
        }
        return hooked;
    }

    private static void overrideBackgroundView(final Object proxy) {
        try {
            Object viewInfo = viewInfoOf(proxy);
            if (viewInfo == null) {
                return;
            }
            Object backgroundObj = callOrNull(viewInfo, "getBackgroundView");
            if (!(backgroundObj instanceof View)) {
                return;
            }
            View backgroundView = (View) backgroundObj;
            logHookHit("底图刷新");
            sHookHits++;
            Context context = backgroundView.getContext();
            if (!HookPrefs.replaceTileBackground()
                    && !TileLongPressConfig.anyShapeBgImage(context)
                    && !TileLongPressConfig.anyStyle(context)) {
                // 全局替换没开、长按样式/自定义底图也没有：直接放过，不做反射解析
                return;
            }
            String spec = specOfViewInfo(viewInfo, backgroundView);
            logSpecIfEnabled(spec);
            ensureShapeCached(spec, backgroundView);
            logSpecShapeOnce(spec);
            if (!shouldApplyTile(context, spec)) {
                return;
            }
            TileConfigs.Cfg cfg = TileConfigs.forSpec(spec);
            if (cfg != null && cfg.bgKeep) {
                // 该磁贴要求保留系统原背景
                return;
            }
            if (!isStyled(context, spec)) {
                // 该形状没设长按样式时，仍按原来的「强制覆盖 / 每磁贴配置」判断
                boolean forceAll = HookPrefs.tileBackgroundForceAll();
                boolean perTileBg = cfg != null && cfg.hasBg() && !cfg.bgKeep;
                if (!forceAll && !perTileBg) {
                    return;
                }
            }
            // 「开 / 关」各一套配置：优先用底图实例反查状态，再回落磁贴视图字段
            boolean active = tileActiveState(asDrawable(backgroundView.getBackground()),
                    viewInfo instanceof View ? (View) viewInfo : backgroundView);
            Drawable replacement = createTileBackground(context, spec, active);
            if (replacement != null) {
                backgroundView.setBackground(replacement);
                // 混色链路会先设遮罩再设底图，这里补一次清理，否则前景那层浅色会盖住底图
                dropSystemMask(viewInfo);
            } else {
                applyAlphaOnly(backgroundView.getBackground(), context,
                        TileLongPressInterceptor.shapeOf(spec), active);
            }
        } catch (Throwable t) {
            logOnce("overrideBackgroundView: " + t);
        }
    }

    // ------------------------------------------------ 深色 / 浅色切换后立即刷新

    private static int hookTileViews(final LoadPackageParam lpparam) {
        Class<?> clazz = XposedHelpers.findClassIfExists(CLASS_TILE_BASE_VIEW, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        int hooked = 0;
        try {
            XposedHelpers.findAndHookMethod(clazz, "onConfigurationChanged", Configuration.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            refreshTileBackground(param.thisObject);
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            logOnce("hookTileViews: " + t);
        }

        // 状态来源：handleStateChanged 是系统把「新状态」交到磁贴视图手上的唯一入口，
        // 而且它内部紧接着就同步刷底图（那时 mTileState 还没写入）。
        // 挂前置钩子先把状态记下来，落点才读得到本次切换后的真值。
        // 主面板的 OplusQSTileViewImpl 覆写了这个方法（第一句就是 invoke-super），
        // 所以挂在基类上即可覆盖全部形状。
        try {
            XposedHelpers.findAndHookMethod(clazz, "handleStateChanged", CLASS_TILE_STATE,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            recordTileActive((View) param.thisObject,
                                    param.args == null || param.args.length == 0
                                            ? null : param.args[0]);
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            logOnce("hookTileViews: handleStateChanged - " + t);
        }

        // 编辑/缩放面板的三种形状预览走另一条继承链（extends QSTileView），
        // 上面两个钩子都挂不到它们，得单独挂它自己的状态入口
        Class<?> resizeable = XposedHelpers.findClassIfExists(CLASS_RESIZEABLE_TILE,
                lpparam.classLoader);
        if (resizeable != null) {
            for (String method : RESIZEABLE_STATE_METHODS) {
                try {
                    XposedHelpers.findAndHookMethod(resizeable, method, CLASS_TILE_STATE,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    recordTileActive((View) param.thisObject,
                                            param.args == null || param.args.length == 0
                                                    ? null : param.args[0]);
                                }
                            });
                    hooked++;
                } catch (Throwable t) {
                    logOnce("hookTileViews: " + method + " - " + t);
                }
            }
        }
        return hooked;
    }

    /**
     * 第二条状态来源：底图代理自己拿到的 {@code QSTile$State}。
     *
     * <p>{@code GlobalThemeBaseQsTileViewBackground.handleStateChanged(QSTile$State)}
     * 的第一句就是 {@code updateBackground$1(state.state)}，也就是「选 active 还是
     * inactive 底图」这个决定本身；紧接着它会同步走到我们的底图落点。挂它的前置钩子，
     * 落点读到的就是本次切换后的真值。</p>
     *
     * <p>视图通过 {@code this$0.qsTileView} 反查（{@code this$0} 是
     * {@code QsTileViewBackgroundProxyImpl}，它持有 {@code QsTileViewInfoProvider}，
     * 主面板上就是磁贴视图本身）。</p>
     */
    private static int hookTileBackgroundProxyState(final LoadPackageParam lpparam) {
        int hooked = 0;
        // 逐个 GlobalTheme 底图类挂：万一某个子类覆写了 handleStateChanged 且没走 super，
        // 只挂基类就会漏掉那一种主题
        for (String className : DRAWABLE_FACTORY_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "handleStateChanged", CLASS_TILE_STATE,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                recordProxyActive(param.thisObject,
                                        param.args == null || param.args.length == 0
                                                ? null : param.args[0]);
                            }
                        });
                hooked++;
            } catch (Throwable t) {
                logOnce("hookTileBackgroundProxyState: " + className + " - " + t);
            }
        }
        return hooked;
    }

    /** 从底图代理这一层记录状态：先把代理反查回磁贴视图，再复用同一套判定。 */
    private static void recordProxyActive(final Object background, final Object state) {
        if (background == null || state == null) {
            return;
        }
        Boolean active = tileStateActive(state);
        if (active == null) {
            return;
        }
        View tileView = tileViewOfBackground(background);
        if (tileView == null) {
            return;
        }
        sViewActive.put(tileView, active);
        logStateProbeOnce(tileView, state.getClass().getSimpleName(),
                active.booleanValue(), "proxy");
    }

    /** {@code background.this$0.qsTileView} → 磁贴视图；取不到返回 null。 */
    private static View tileViewOfBackground(final Object background) {
        try {
            Object proxyImpl = XposedHelpers.getObjectField(background, "this$0");
            Object info = XposedHelpers.getObjectField(proxyImpl, "qsTileView");
            return info instanceof View ? (View) info : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 {@code QSTile$State} 里解析「开着」并缓存到磁贴视图上。
     *
     * <p>判定口径见 {@link #tileStateActive(Object)}。</p>
     */
    private static void recordTileActive(final View tileView, final Object state) {
        if (tileView == null || state == null) {
            return;
        }
        Boolean active = tileStateActive(state);
        if (active == null) {
            return;
        }
        sViewActive.put(tileView, active);
        logStateProbeOnce(tileView, state.getClass().getSimpleName(),
                active.booleanValue(), "view");
    }

    /**
     * 从 {@code QSTile$State} 解析「开着」。
     *
     * <p>优先 {@code state} 这个 int 字段：反编译确认 ColorOS 的底图代理正是拿它去
     * 选 {@code status_bar_qs_tile_bg_active / _inactive}，用它才能和系统自己的
     * 配色判断完全一致（{@code STATE_ACTIVE == 2}）。取不到再退回
     * {@code QSTile$AdapterState.value} 这个布尔值。</p>
     */
    private static Boolean tileStateActive(final Object state) {
        try {
            return Boolean.valueOf(XposedHelpers.getIntField(state, FIELD_TILE_STATE) == 2);
        } catch (Throwable ignored) {
        }
        try {
            return Boolean.valueOf(XposedHelpers.getBooleanField(state, "value"));
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 每个磁贴的「开 / 关」各打一条状态探针，便于上机核对状态判定。 */
    private static void logStateProbeOnce(final View tileView, final String stateName,
            final boolean active, final String source) {
        String spec = specOfTileView(tileView);
        if (!sLoggedStateProbe.add(source + "|" + spec + "|" + active)) {
            return;
        }
        XposedBridge.log(TAG + ": state probe[" + source + "] spec="
                + (spec == null ? "?" : spec)
                + " " + stateName + " → " + (active ? "开" : "关"));
    }

    /**
     * 重新应用该磁贴的底图。
     *
     * <p>两个调用方：磁贴视图 {@code onConfigurationChanged}（深/浅色切换），
     * 以及 {@link TileLongPressInterceptor}（形状刚判定出来、或长按样式面板改了参数）。</p>
     */
    static void refreshTileBackground(final Object tileView) {
        try {
            if (!(tileView instanceof View)) {
                return;
            }
            View view = (View) tileView;
            String spec = specOfTileView(tileView);
            Context context = view.getContext();
            if (!shouldApplyTile(context, spec)) {
                return;
            }
            if (!isStyled(context, spec)
                    && !HookPrefs.tileBackgroundForceAll() && !TileConfigs.any()) {
                return;
            }
            Object proxy = XposedHelpers.getObjectField(tileView, "mBackgroundProxy");
            if (proxy != null) {
                XposedHelpers.callMethod(proxy, "refreshViewBackground");
            }
        } catch (Throwable t) {
            logOnce("refreshTileBackground: " + t);
        }
    }

    // ------------------------------------------- 第五层：底图最终落点（按形状）

    /**
     * 底图真正贴到视图上的落点：{@code QsViewInfoProvider.updateBackground(Drawable)}。
     *
     * <p>反编译确认它的实现就是 {@code getBackgroundView().setBackground(drawable)}，
     * 而 {@code OplusQSTileBaseView} 覆写了它（只做 {@code invoke-super}）。</p>
     *
     * <p>为什么在这一层做按形状的样式：这层 {@code thisObject} 就是<b>磁贴视图本身</b>，
     * {@code mTileSpec} 直接读得到、形状也能现场判定，不用再去工厂层靠
     * {@code qsView} / {@code this$0} 反射找 spec（那条路实测 spec 恒为 null）。
     * 而且系统每次改底图都会经过这里，我们的样式不会被后续刷新冲掉。</p>
     */
    private static int hookUpdateBackground(final LoadPackageParam lpparam) {
        int hooked = 0;
        for (String className : UPDATE_BACKGROUND_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "updateBackground", Drawable.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                applyStyleAtLandingPoint(param);
                            }
                        });
                hooked++;
            } catch (Throwable t) {
                logOnce("hookUpdateBackground: " + className + " - " + t);
            }
        }
        hooked += hookUpdateBackgroundInterface(lpparam);
        return hooked;
    }

    /**
     * 直接挂接口上 {@code updateBackground(Drawable)} 的 default 实现。
     *
     * <p>这是反编译后确认的<b>唯一</b>真实实现：所有具体类（
     * {@code OplusQSTileBaseView} / {@code OplusQSTileViewImpl} / 编辑面板的
     * {@code OplusQSResizeableTileView*} …）上声明的那一个访问标志都是
     * {@code 0x1041}（bridge + synthetic），方法体只有 {@code invoke-super} 转到接口。
     * 只挂具体类时，一旦 ART 把桥接方法直接派发/内联掉，hook 就永远不会被调用——
     * 这正是「圆角生效、底图完全不生效」的原因（圆角挂的是 {@code getOutline}，
     * 那是真实方法；底图挂的是桥接方法）。</p>
     */
    private static int hookUpdateBackgroundInterface(final LoadPackageParam lpparam) {
        int hooked = 0;
        for (String className : UPDATE_BACKGROUND_INTERFACES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(clazz, "updateBackground", Drawable.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                applyStyleAtLandingPoint(param);
                            }
                        });
                hooked++;
                XposedBridge.log(TAG + ": hooked interface default " + className
                        + "#updateBackground(Drawable)");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook interface " + className + " failed - " + t);
            }
        }
        return hooked;
    }

    private static void applyStyleAtLandingPoint(final XC_MethodHook.MethodHookParam param) {
        applyStyleToTileView(param.thisObject,
                asDrawable(param.args != null && param.args.length > 0 ? param.args[0] : null));
    }

    /**
     * 挂 {@code QsTileViewInfoProvider#updateBackgroundMask(Drawable)} 的 default 实现。
     *
     * <p>反编译确认它的方法体是 {@code getBackgroundView().setForeground(drawable)}，
     * 即给磁贴背景视图盖一层<b>前景遮罩</b>。混色（MixColor）那条背景链在「开」时
     * 是这么画的：</p>
     * <pre>
     * qsTileView.updateBackground(autoBlurDrawable)   ← 我们的落点，被换成自定义底图
     * qsTileView.updateBackgroundMask(maskDrawable)   ← 前景再盖一层「激活色 + alpha」
     * </pre>
     * <p>前景画在背景之上，于是自定义底图被这层半透明浅色洗白——这正是 2×1 宽卡片
     * （highlight 磁贴）「开」的时候底图看着没生效的原因。「关」时
     * {@code toInactiveBackground} 会把 mask 的颜色置 0（全透明），所以只有「开」有问题。</p>
     *
     * <p>既然底图由我们自己画，这层系统遮罩就不该留着：贴完自定义底图后把它清掉。</p>
     */
    private static int hookBackgroundMask(final LoadPackageParam lpparam) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                CLASS_TILE_VIEW_INFO_PROVIDER, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "updateBackgroundMask", Drawable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            dropSystemMask(param.thisObject);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook updateBackgroundMask failed - " + t);
            return 0;
        }
    }

    /**
     * 已经贴上自定义底图的磁贴，把系统那层遮罩（前景）清掉。
     *
     * <p>只对背景是本模块 {@link TileBackgroundDrawable} 的视图动手：没自定义底图的
     * 磁贴要保持系统原样。清两次是因为系统设遮罩与设底图的先后顺序不固定——
     * {@code toActiveBackground} 是先底图后遮罩（这里兜住），
     * {@code updateMixColorBackground} 是先遮罩后底图（{@link #applyStyleToTileView} 兜住）。</p>
     */
    private static void dropSystemMask(final Object holder) {
        try {
            if (!(holder instanceof View)) {
                return;
            }
            Object bgObj = callOrNull(holder, "getBackgroundView");
            if (!(bgObj instanceof View)) {
                return;
            }
            View backgroundView = (View) bgObj;
            if (backgroundView.getBackground() instanceof TileBackgroundDrawable
                    && backgroundView.getForeground() != null) {
                backgroundView.setForeground(null);
            }
        } catch (Throwable t) {
            logOnce("dropSystemMask: " + t);
        }
    }

    /**
     * 布局完成后直接从视图侧补一次样式（不依赖 {@code updateBackground} 被调用）。
     *
     * <p>{@link TileLongPressInterceptor} 在 {@code OplusQSTileBaseView.onLayout} 里调用。
     * 系统首次设置底图发生在布局之前（那时磁贴宽度还是 0，判不出形状），
     * 这里补一次，保证首屏就能看到底图；之后每次布局都过一遍，被系统刷掉也能补回来。
     * 参数没变时 {@link #isSignatureApplied} 会直接返回，不会造成重复 setBackground 的布局抖动。</p>
     */
    public static void applyStyleDirect(final View tileView) {
        applyStyleDirect(tileView, null);
    }

    /**
     * 同上，但<b>由调用方指定形状</b>，不再从视图现场判。
     *
     * <p>{@link TileLongPressInterceptor} 记「没有 spec 的视图」时（2×2 媒体面板、
     * deviceplugin 卡片）本来就是按形状记的，刷新时直接把这个形状交回来最稳：
     * 现场判形状要量单元宽度和视图尺寸（{@link TileLongPressInterceptor#shapeOfView}），
     * 而媒体面板恰恰会在<b>还没布局</b>或<b>刚布局完</b>的那一刻被刷新——那时候尺寸还是
     * 0 或单元宽度取不到，判不出形状就直接放弃了，表现就是「换了 2×2 的底图，
     * 得再改一下圆角才看得到」。</p>
     *
     * @param shape null 表示照旧现场判
     */
    public static void applyStyleDirect(final View tileView, final String shape) {
        applyStyleToTileView(tileView, null, shape);
        // 布局完成后磁贴尺寸才有效。系统可能在布局之前就把 outline 算好了（那时宽度是 0，
        // 圆角判定失败、退回系统形状），这里补一次重算，否则「自定义底图 + 切深浅色」时
        // 底图圆角与磁贴裁剪形状会不一致
        try {
            Context ctx = tileView == null ? null : tileView.getContext();
            if (ctx != null && TileLongPressConfig.anyStyle(ctx)) {
                TileCornerHook.refreshView(tileView);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 底图样式的唯一落点逻辑。
     *
     * @param original 系统本次要设置的底图；从 {@code onLayout} 侧补样式时为 null
     *                 （此时回落到视图当前背景，用于只调可见度的情况）
     * @param shape    调用方已经知道的形状；null 表示从视图现场判
     */
    private static void applyStyleToTileView(final Object holder, final Drawable original) {
        applyStyleToTileView(holder, original, null);
    }

    private static void applyStyleToTileView(final Object holder, final Drawable original,
            final String knownShape) {
        try {
            if (Boolean.TRUE.equals(sInjecting.get())) {
                return;
            }
            if (!(holder instanceof View)) {
                return;
            }
            View tileView = (View) holder;
            final Context context = tileView.getContext();
            if (context == null) {
                return;
            }
            // 兜底注册：SystemUIApplication 那条路可能没走到
            TileImageChannel.ensureReceiver(context);

            // 高频路径：先做一次便宜的前置判断，没配任何样式就不做形状判定（要反射量尺寸）
            if (!TileLongPressConfig.anyStyle(context)
                    && !TileLongPressConfig.anyShapeBgImage(context)) {
                sLandingProbe = "该进程还没配过任何样式";
                return;
            }

            final String spec = specOfTileView(tileView);
            sLastSpec = spec;
            // 调用方已经知道形状时（媒体面板 / deviceplugin 卡片这两类没有 spec 的视图）
            // 直接用，不再现场判：它们恰恰会在还没布局、或刚布局完的那一刻被刷新，
            // 那时候量不出尺寸、判不出形状，判不出就直接放弃了
            String shape = knownShape != null
                    ? knownShape : TileLongPressInterceptor.shapeOfView(tileView);
            if (shape == null) {
                // updateBackground 常在布局完成前被调用（此时磁贴视图宽度还是 0，判不出形状），
                // 回落到 onLayout 时按 spec 缓存好的形状
                shape = TileLongPressInterceptor.shapeOf(spec);
            }
            if (shape == null) {
                sLandingProbe = "spec=" + spec + " 形状判不出（"
                        + TileLongPressInterceptor.sizeProbe(tileView) + "）";
                return;
            }
            logSpecShapeOnce(spec);

            // 「开 / 关」各一套配置：优先用底图实例反查状态，再回落磁贴视图字段
            final boolean active = tileActiveState(original, tileView);
            TileLongPressConfig.Style style = TileLongPressConfig.styleOf(context, shape);
            String base64 = TileLongPressConfig.tileBgImage(context, shape, active);
            boolean hasImage = base64.length() > 0;
            boolean touched = style != null && style.touched;
            if (!hasImage && !touched) {
                sLandingProbe = "spec=" + spec + " shape=" + shape + " 该形状没调过样式";
                return;
            }
            sHookHits++;
            logHookHit("底图落点 updateBackground");

            Object bgObj = callOrNull(tileView, "getBackgroundView");
            // 认不出类名的卡片（设备空间 / 耳机这类 deviceplugin 卡片）没有
            // getBackgroundView()，可见底图挂在它内部铺满整卡的那一层上
            View backgroundView = bgObj instanceof View
                    ? (View) bgObj : backgroundCarrier(tileView);

            Drawable base = original != null ? original : asDrawable(backgroundView.getBackground());
            Drawable styled = buildStyledBackground(context, style,
                    hasImage ? decodeShapeImage(base64) : null, active);

            if (styled == null) {
                // 该形状只调了可见度：保留系统底图的形状与配色，仅改 alpha
                applyAlphaOnly(base, context, shape, active);
                sLandingProbe = "spec=" + spec + " shape=" + shape + (active ? " 开" : " 关")
                        + " 已改可见度 " + (style != null ? style.alphaPct(active) : 100) + "%（无底图）";
                return;
            }
            final String signature = styleSignature(shape, style, base64, active);
            if (isSignatureApplied(backgroundView, signature)) {
                // 同一次 updateBackground 被多个 hook 点重复命中，或参数没变
                sLandingProbe = "spec=" + spec + " shape=" + shape + " 已是最新";
                return;
            }
            sInjecting.set(Boolean.TRUE);
            try {
                backgroundView.setBackground(styled);
                // 混色链路会先设遮罩再设底图，这里补一次清理，否则前景那层浅色会盖住底图
                dropSystemMask(tileView);
                sAppliedSignatures.put(backgroundView, signature);
                // 底图换新后磁贴的裁剪形状也要跟着重算：切深浅色时系统会先算一遍 outline，
                // 那时磁贴还没布局、判不出形状，退回系统形状后没人再触发重算，
                // 自定义底图的圆角就和磁贴的裁剪形状对不上了
                TileCornerHook.refreshView(tileView);
                sLandingProbe = "spec=" + spec + " shape=" + shape + (active ? " 开" : " 关")
                        + " 已套用底图";
            } finally {
                sInjecting.remove();
            }
        } catch (Throwable t) {
            logOnce("applyStyleToTileView: " + t);
        }
    }

    /** 该形状当前样式参数的指纹，用来判断底图是不是已经是我们套好的那一版。 */
    private static String styleSignature(final String shape, final TileLongPressConfig.Style style,
            final String base64, final boolean active) {
        int cornerDp = style != null ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        int alphaPct = style != null ? style.alphaPct(active) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        // 底图要按「内容」入指纹，不能只记有没有图：从一张图换成另一张时长度和哈希都变，
        // 指纹才跟着变。只记布尔的话换图前后指纹相同，会被 isSignatureApplied 当成
        // 「已是最新」直接跳过，表现就是 2×1 / 1×1 换底图必须重启系统界面才生效
        // （亮度 / 音量胶囊没这层缓存，每帧现读 prefs，所以看起来是「立即生效」）。
        String image = base64 == null || base64.length() == 0
                ? "" : base64.length() + ":" + base64.hashCode();
        // 素材圆角比例也要入指纹：导入主题包会把圆角从 20dp 换成素材自带的比例，
        // 只记 cornerDp 的话前后都是「跟随系统」，指纹没变、新圆角会被当成
        // 「已是最新」跳过，表现就是导入后圆角不生效
        float ratio = style != null ? style.cornerRatio : 0f;
        return shape + "|" + cornerDp + "|" + alphaPct + "|" + image + "|" + ratio + "|"
                + (TileLongPressConfig.stateOf(shape, active) ? "on" : "off");
    }

    /**
     * 底图是否已经是本模块按当前样式套好的那一版。
     *
     * <p>既看当前背景是不是本模块的 {@link TileBackgroundDrawable}（系统状态一变就会
     * 换成它自己的底图，那时必须重新套），也看参数指纹有没有变。</p>
     */
    private static boolean isSignatureApplied(final View backgroundView, final String signature) {
        if (!(backgroundView.getBackground() instanceof TileBackgroundDrawable)) {
            return false;
        }
        return signature.equals(sAppliedSignatures.get(backgroundView));
    }

    /**
     * 找出真正承载可见底图的那一层。
     *
     * <p>磁贴走 {@code getBackgroundView()} 就够了；但 deviceplugin 卡片
     * （{@code SquareDeviceCardView} 一族）没有这个方法，而且<b>卡片自己身上根本没有底图</b>：
     * 反编译 {@code SquareDeviceCardView.g()}（refreshCard）得到三条分支——
     * 全局主题 {@code q.setBackground(d("status_square_card_bg_active"))}、
     * 模糊 {@code q.setBackground(blurDrawable)}、
     * 浅模糊 {@code d.a(colorRes, q)}（内部 {@code target.setBackground(ShapeDrawable e)}）
     * ——最后都落到 {@code q} 上。{@code q} 是卡片内部那个铺满整卡的
     * {@code ConstraintLayout}（布局 {@code square_device_card_layout.xml} 里
     * id {@code 0x7F0A0CE5} 那一层）。贴到卡片自己身上会被它整个盖住，
     * 这正是「底图选了看不见」的原因。</p>
     *
     * <p>认法用尺寸而不是字段名：{@code q} 这个名字是混淆的、5 个卡片类还不一样，
     * 但承载底图的那层一定是「在卡片内部、铺满卡片、已经有背景」的容器。</p>
     */
    private static View backgroundCarrier(final View card) {
        final int cardArea = card.getWidth() * card.getHeight();
        if (cardArea <= 0) {
            return card;
        }
        View best = null;
        int bestArea = 0;
        List<View> level = new ArrayList<View>();
        level.add(card);
        // 卡片内部就 2 层（布局根 RelativeLayout → 承载底图的 ConstraintLayout），
        // 扫到第 3 层足够，也避免深挖子树误认到卡片里的图标
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            List<View> next = new ArrayList<View>();
            for (int i = 0; i < level.size(); i++) {
                View node = level.get(i);
                if (!(node instanceof ViewGroup)) {
                    continue;
                }
                ViewGroup group = (ViewGroup) node;
                for (int c = 0; c < group.getChildCount(); c++) {
                    View child = group.getChildAt(c);
                    next.add(child);
                    if (!(child instanceof ViewGroup) || child.getBackground() == null) {
                        continue;
                    }
                    int area = child.getWidth() * child.getHeight();
                    if (area * 10 < cardArea * 7) {
                        continue;   // 不够铺满卡片，不是承载底图的那层
                    }
                    if (area > bestArea) {
                        best = child;
                        bestArea = area;
                    }
                }
            }
            level = next;
        }
        return best != null ? best : card;
    }

    // ------------------------------------------------- 第五层：deviceplugin 卡片底图落点

    /** 已经挂过「布局后补一次样式」监听的卡片（弱键，视图销毁后自动回收）。 */
    private static final Set<View> sCardLayoutHooked =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /**
     * deviceplugin 卡片（耳机 / 设备空间）的底图落点钩子。
     *
     * <p>这些卡片不是磁贴视图，{@code QsViewInfoProvider#updateBackground} 那条路
     * 根本不会触发，只能挂在它们自己的刷新链路上：{@code View#setBackgroundDrawable}
     * 是 SDK 把底图设到 {@code q} 上的必经之路（{@code setBackground} 内部也走它）。</p>
     *
     * <p>过滤条件收得很紧——「在 {@code com.oplus.deviceplugin.sdk.ui.view.separatecardview}
     * 这个包底下的卡片内部 + 自己是个 ViewGroup」。用包名前缀而不是通用容器类名，
     * 是上次「连控制中心背景都被改了」的教训：那种过滤依据会把一大片无关视图也算进来。</p>
     */
    private static int hookCardBackgrounds(final LoadPackageParam lpparam) {
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(viewClass, "setBackgroundDrawable",
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onCardBackgroundSet(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            logOnce("hookCardBackgrounds: " + t);
            return 0;
        }
    }

    private static void onCardBackgroundSet(final XC_MethodHook.MethodHookParam param) {
        try {
            if (!(param.thisObject instanceof View)) {
                return;
            }
            View view = (View) param.thisObject;
            // 2×2 媒体面板必须排在 ViewGroup 判断之前：系统把底图设到面板内部
            // 「铺满整卡的那一层」上时，thisObject 是个普通 View，按 ViewGroup 过滤就漏掉了
            View media = TileLongPressInterceptor.findMediaPanel(view);
            if (media != null) {
                applyMediaPanel(media);
                return;
            }
            // ViewGroup 这一条把卡片内部的图标 / 文字（它们也有 android:background）挡在外面
            if (!(view instanceof ViewGroup)) {
                return;
            }
            View card = TileLongPressInterceptor.devicePluginCardOf(view);
            if (card == null) {
                return;
            }
            TileLongPressInterceptor.rememberCardView(card);
            // 方形（1×1）与横向（无设备预连接时的 1×2）是两套底图 / 圆角，
            // 按卡片类名现判，不能一律按方形卡片套
            String cardShape = TileLongPressInterceptor.devicePluginShapeOf(card);
            final String shape = cardShape == null
                    ? TileLongPressConfig.SHAPE_CARD : cardShape;
            applyStyleDirect(card, shape);
            // SDK 是在 onAttachedToWindow 里刷新卡片的，那时卡片还没布局、判不出形状，
            // 这里挂一次布局回调，等尺寸出来再补一遍（同一张卡片只挂一次）
            if (card.getWidth() > 0 && card.getHeight() > 0) {
                return;
            }
            if (!sCardLayoutHooked.add(card)) {
                return;
            }
            card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    // 卡片有可能从方形换成横向（设备接入 / 断开时系统换的是另一个类），
                    // 所以这里按当前类名重判一次，而不是沿用挂监听时那个形状
                    String now = TileLongPressInterceptor.devicePluginShapeOf(v);
                    applyStyleDirect(v, now == null ? TileLongPressConfig.SHAPE_CARD : now);
                }
            });
        } catch (Throwable t) {
            logOnce("onCardBackgroundSet: " + t);
        }
    }

    /** 已经挂过「布局后补一次样式」监听的媒体面板（弱键，视图销毁后自动回收）。 */
    private static final Set<View> sMediaLayoutHooked =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /**
     * 媒体播放卡片（那块大面板）的底图落点，按 {@code SHAPE_2X2} 套样式。
     *
     * <p>媒体面板没有 {@code mTileSpec}，{@code updateBackground} 那条链在<b>布局之前</b>
     * 就会被调用（那时宽度还是 0、形状判不出来，只能放弃）——表现就是
     * 「换了媒体播放卡片底图没反应，再改一下圆角才看得到」
     * （改圆角走长按 → 现场认类名 → 登记进 sExtraViews → refreshShape 才补上）。</p>
     *
     * <p>这里复用「{@code View#setBackgroundDrawable} 全局兜底」这条现成链路：
     * 系统每次设底图都会经过，于是不依赖任何硬编码类名。布局还没完成时挂一次布局回调，
     * 等尺寸出来再补一遍，参数没变时 {@code isSignatureApplied} 会直接跳过。</p>
     */
    private static void applyMediaPanel(final View panel) {
        logHookHit("媒体播放卡片（setBackgroundDrawable 兜底）："
                + panel.getClass().getSimpleName());
        applyStyleDirect(panel, TileLongPressConfig.SHAPE_2X2);
        if (panel.getWidth() > 0 && panel.getHeight() > 0) {
            return;
        }
        if (!sMediaLayoutHooked.add(panel)) {
            return;
        }
        panel.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                applyStyleDirect(v, TileLongPressConfig.SHAPE_2X2);
            }
        });
    }

    // ------------------------------------------------------- 第四层：磁贴图标替换

    private static int hookIconViews(final LoadPackageParam lpparam) {
        Class<?> iconViewClass =
                XposedHelpers.findClassIfExists(CLASS_ICON_VIEW, lpparam.classLoader);
        Class<?> stateClass =
                XposedHelpers.findClassIfExists(CLASS_TILE_STATE, lpparam.classLoader);
        if (iconViewClass == null || stateClass == null) {
            return 0;
        }
        int hooked = 0;
        for (String methodName : new String[]{"setIcon", "updateIcon"}) {
            try {
                XposedHelpers.findAndHookMethod(iconViewClass, methodName,
                        ImageView.class, stateClass, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                overrideTileIcon(param);
                            }
                        });
                hooked++;
            } catch (Throwable t) {
                logOnce("hookIconViews: " + CLASS_ICON_VIEW + "#" + methodName + " - " + t);
            }
        }
        // 自定义图标不做系统着色：命中已替换视图时直接吞掉调用
        try {
            XposedHelpers.findAndHookMethod(iconViewClass, "updateIconTintColor",
                    ImageView.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object view = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            if (view instanceof ImageView && sOverriddenIconViews.contains(view)) {
                                param.setResult(null);
                            }
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            logOnce("hookIconViews: updateIconTintColor - " + t);
        }
        return hooked;
    }

    private static void overrideTileIcon(final XC_MethodHook.MethodHookParam param) {
        try {
            if (!HookPrefs.replaceTileBackground()) {
                return;
            }
            if (param.args == null || param.args.length < 1
                    || !(param.args[0] instanceof ImageView)) {
                return;
            }
            ImageView iconView = (ImageView) param.args[0];
            Context context = iconView.getContext();
            if (!shouldApply(context)) {
                return;
            }
            String spec = resolveTileSpec(iconView);
            logSpecIfEnabled(spec);
            TileConfigs.Cfg cfg = TileConfigs.forSpec(spec);
            if (cfg == null || cfg.iconImage == null) {
                return;
            }
            Bitmap bitmap = bitmapFor(cfg.iconImage);
            if (bitmap == null) {
                return;
            }
            sOverriddenIconViews.add(iconView);
            iconView.setImageDrawable(new BitmapDrawable(context.getResources(), bitmap));
            iconView.setColorFilter(null);
            iconView.setImageTintList(null);
            if (cfg.iconScale != Integer.MIN_VALUE && cfg.iconScale != 100 && cfg.iconScale > 0) {
                float scale = cfg.iconScale / 100f;
                iconView.setScaleX(scale);
                iconView.setScaleY(scale);
            } else {
                iconView.setScaleX(1f);
                iconView.setScaleY(1f);
            }
        } catch (Throwable t) {
            logOnce("overrideTileIcon: " + t);
        }
    }

    // ------------------------------------------------------------ 磁贴身份解析

    /** 从底图/图标 View 沿视图树向上找到磁贴视图，读 {@code mTileSpec}。 */
    private static String resolveTileSpec(final View view) {
        Class<?> tileViewClass = sTileViewClass;
        if (view == null || tileViewClass == null) {
            return null;
        }
        ViewParent node = view.getParent();
        while (node instanceof View) {
            View current = (View) node;
            if (tileViewClass.isInstance(current)) {
                return specOfTileView(current);
            }
            node = current.getParent();
        }
        return null;
    }

    /**
     * 从一个底图对象上取“视图信息提供者”（{@code QsViewInfoProvider} / {@code QsTileViewInfoProvider}）。
     *
     * <p>字段位置在 ColorOS 16 上是这样的（反编译 {@code QsTileViewBackgroundProxyImpl} 得到）：</p>
     * <ul>
     *   <li>内嵌底图类 {@code $GlobalThemeXxxViewBackground} <b>不是</b>代理的子类，只有
     *       {@code this$0} 指回代理，{@code qsTileView} 挂在代理上；</li>
     *   <li>纯色底图 {@code NormalColorTileViewBackground} 自己就持有 {@code qsTileView}；</li>
     *   <li>静态底图代理 {@code QsStaticBackgroundProxy} 用基类的 {@code qsView}。</li>
     * </ul>
     *
     * <p>之前只读 {@code qsView}，在内嵌底图类上必然抛 {@code NoSuchFieldError}，
     * 导致 spec 恒为 null——全局替换（不需要 spec）正常，而按形状的圆角/可见度/底图
     * 全部静默失效。</p>
     */
    private static Object viewInfoOf(final Object holder) {
        if (holder == null) {
            return null;
        }
        Object info = fieldOrNull(holder, "qsTileView");
        if (info == null) {
            info = fieldOrNull(holder, "qsView");
        }
        if (info == null) {
            // 内嵌底图类：顺着 this$0 找到外层代理再取
            Object proxy = fieldOrNull(holder, "this$0");
            if (proxy != null) {
                info = fieldOrNull(proxy, "qsTileView");
                if (info == null) {
                    info = fieldOrNull(proxy, "qsView");
                }
            }
        }
        return info;
    }

    private static Object fieldOrNull(final Object target, final String name) {
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object callOrNull(final Object target, final String method) {
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 底图对象对应的磁贴底图 View（承载底图的那块 View）。 */
    private static View backgroundViewOf(final Object holder) {
        Object viewInfo = viewInfoOf(holder);
        if (viewInfo == null) {
            return null;
        }
        Object view = callOrNull(viewInfo, "getBackgroundView");
        return view instanceof View ? (View) view : null;
    }

    /** 从视图信息提供者解析磁贴 spec：它本身就是磁贴视图时直接读字段。 */
    private static String specOfViewInfo(final Object viewInfo, final View backgroundView) {
        Class<?> tileViewClass = sTileViewClass;
        if (tileViewClass != null && tileViewClass.isInstance(viewInfo)) {
            return specOfTileView(viewInfo);
        }
        return resolveTileSpec(backgroundView);
    }

    /** 从底图代理实例解析磁贴 spec。 */
    private static String specFromBackgroundImpl(final Object backgroundImpl) {
        Object viewInfo = viewInfoOf(backgroundImpl);
        if (viewInfo == null) {
            return null;
        }
        Object backgroundView = callOrNull(viewInfo, "getBackgroundView");
        return specOfViewInfo(viewInfo,
                backgroundView instanceof View ? (View) backgroundView : null);
    }

    /**
     * 形状缓存里没有这个 spec 时，直接用磁贴视图现场判定一次补进缓存。
     *
     * <p>兜底：即使 spec 字符串两边对不上，样式也还能按形状生效。</p>
     */
    private static void ensureShapeCached(final String spec, final View backgroundView) {
        if (spec == null || backgroundView == null) {
            return;
        }
        if (TileLongPressInterceptor.shapeOf(spec) != null) {
            return;
        }
        TileLongPressInterceptor.shapeOfView(backgroundView);
    }

    /** 每个 spec 只打一次：底图侧解析到的 spec 与形状，便于确认两边对得上。 */
    private static void logSpecShapeOnce(final String spec) {
        sLastSpec = spec;
        if (spec == null || !sLoggedSpecShape.add(spec)) {
            return;
        }
        XposedBridge.log(TAG + ": 底图侧 spec=" + spec + " → shape="
                + TileLongPressInterceptor.shapeOf(spec));
    }

    private static String specOfTileView(final Object tileView) {
        try {
            Object spec = XposedHelpers.getObjectField(tileView, FIELD_TILE_SPEC);
            return spec instanceof String ? (String) spec : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 判断磁贴当前是「开」还是「关」。
     *
     * <p>取值顺序（前两个都是「本次切换后」的状态，只有第三个在刚切换时会拿到上一次的）：</p>
     * <ol>
     *   <li>{@code handleStateChanged} 前置钩子缓存的 {@link #sViewActive}；</li>
     *   <li>底图实例反查 {@link #sDrawableActive}（工厂方法名自带状态）；</li>
     *   <li>视图字段 {@code mTileState}（{@link #isTileActive}）。</li>
     * </ol>
     */
    private static boolean tileActiveState(final Drawable drawable, final View fallbackView) {
        Boolean cached = cachedActive(fallbackView);
        if (cached != null) {
            sActiveSource = "cache";
            return cached.booleanValue();
        }
        if (drawable != null) {
            Boolean byDrawable = sDrawableActive.get(drawable);
            if (byDrawable != null) {
                sActiveSource = "drawable";
                return byDrawable.booleanValue();
            }
        }
        sActiveSource = "mTileState";
        return isTileActive(fallbackView);
    }

    /** 沿视图树找 {@link #sViewActive} 里记下的状态；没有返回 null。 */
    private static Boolean cachedActive(final View view) {
        View node = view;
        for (int depth = 0; node != null && depth < 12; depth++) {
            Boolean cached = sViewActive.get(node);
            if (cached != null) {
                return cached;
            }
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 磁贴当前是不是「开着」，用来决定套「开」还是「关」那套样式。
     *
     * <p>落点拿到的常常是磁贴内部的背景 View，本身没有 {@code mTileState}，
     * 所以沿视图树往上找第一个带该字段的视图（也就是 {@code OplusQSTileBaseView}）。
     * 编辑面板的 {@code OplusQSResizeableTileView} 系列没有这个字段，
     * 一路找不到时按「关」算。</p>
     */
    static boolean isTileActive(final View view) {
        View node = view;
        for (int depth = 0; node != null && depth < 12; depth++) {
            // 第一优先：handleStateChanged 前置钩子记下的状态（本次切换后的真值）
            Boolean cached = sViewActive.get(node);
            if (cached != null) {
                return cached.booleanValue();
            }
            Boolean active = tileActiveOf(node);
            if (active != null) {
                return active.booleanValue();
            }
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** 单个视图上的 {@code mTileState}；没有该字段返回 null（继续往上找）。 */
    private static Boolean tileActiveOf(final View view) {
        Class<?> clazz = view.getClass();
        if (sTileActiveMissing.contains(clazz)) {
            return null;
        }
        java.lang.reflect.Field field = sTileActiveFields.get(clazz);
        if (field == null) {
            field = XposedHelpers.findFieldIfExists(clazz, FIELD_TILE_ACTIVE);
            if (field == null) {
                sTileActiveMissing.add(clazz);
                return null;
            }
            field.setAccessible(true);
            sTileActiveFields.put(clazz, field);
        }
        try {
            return Boolean.valueOf(field.getBoolean(view));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 日志开关打开时，把每个新见到的磁贴 spec 记到 Xposed 日志。 */
    private static void logSpecIfEnabled(final String spec) {
        if (!HookPrefs.logTileSpecs() || spec == null) {
            return;
        }
        if (sLoggedSpecs.add(spec)) {
            XposedBridge.log(TAG + ": tile spec = " + spec);
        }
    }

    // -------------------------------------------------------------------- 工具方法

    private static Context contextOf(final XC_MethodHook.MethodHookParam param) {
        if (param.args == null) {
            return null;
        }
        for (Object arg : param.args) {
            if (arg instanceof Context) {
                return (Context) arg;
            }
        }
        return null;
    }

    private static boolean shouldApply(final Context context) {
        // 主开关：设置页的「启用替换」。默认关闭，不打开就完全不动系统磁贴底图，
        // 否则 1x1 的圆形磁贴会被统一替换成圆角矩形（方形）。
        if (!HookPrefs.replaceTileBackground()) {
            return false;
        }
        if (!HookPrefs.tileBackgroundDarkOnly()) {
            return true;
        }
        return isNightMode(context);
    }

    /**
     * 磁贴底图是否该由本模块接管。
     *
     * <p>长按样式面板（圆角/可见度/底图）是用户显式设置的，不受设置页主开关
     * 与「仅暗色模式」限制；其余情况沿用原来的全局判断。</p>
     */
    private static boolean shouldApplyTile(final Context context, final String spec) {
        if (isStyled(context, spec)) {
            return true;
        }
        return shouldApply(context);
    }

    /** 该 spec 所属形状是否设置过样式（圆角/可见度或底图）。开 / 关任一有配置都算。 */
    private static boolean isStyled(final Context context, final String spec) {
        return styledImageBase64(context, spec, true) != null
                || styledImageBase64(context, spec, false) != null
                || touchedStyle(context, spec) != null;
    }

    /** 该 spec 所属形状在长按样式面板里调过圆角/可见度时返回样式，否则 null。 */
    private static TileLongPressConfig.Style touchedStyle(final Context context,
            final String spec) {
        String shape = TileLongPressInterceptor.shapeOf(spec);
        if (shape == null) {
            return null;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(context, shape);
        return style != null && style.touched ? style : null;
    }

    /** 该 spec 所属形状在指定状态下的自定义底图（Base64）；没设置返回 null。 */
    private static String styledImageBase64(final Context context, final String spec,
            final boolean active) {
        String shape = TileLongPressInterceptor.shapeOf(spec);
        if (shape == null || context == null) {
            return null;
        }
        String base64 = TileLongPressConfig.tileBgImage(context, shape, active);
        return base64.length() == 0 ? null : base64;
    }

    /** 最近一次真正生成了自定义底图的形状，样式面板用它显示“有没有套用成功”。 */
    private static volatile String sLastAppliedShape;
    /** 底图 hook 被系统调用的累计次数（样式面板显示，用来判断链路有没有跑起来）。 */
    private static volatile int sHookHits;
    /** 最近一次从底图侧解析到的磁贴 spec。 */
    private static volatile String sLastSpec;
    /** 底图落点最近一次的处理结论，样式面板用来定位“为什么没套上”。 */
    private static volatile String sLandingProbe = "尚未触发";

    /** 底图落点最近一次的处理结论。 */
    public static String landingProbe() {
        return sLandingProbe;
    }

    /** 已经触发过的 hook 层（样式面板用来判断链路跑到哪一层就断了）。 */
    public static String hookPoints() {
        synchronized (sHookHitLogged) {
            if (sHookHitLogged.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (String where : sHookHitLogged) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(where);
            }
            return sb.toString();
        }
    }

    /** 最近一次套用样式的形状；从未套用过返回 null。 */
    public static String lastAppliedShape() {
        return sLastAppliedShape;
    }

    /** 底图 hook 被系统调用的累计次数。 */
    public static int hookHitCount() {
        return sHookHits;
    }

    /** 最近一次从底图侧解析到的 spec；没解析到返回 null。 */
    public static String lastSpec() {
        return sLastSpec;
    }

    /** 长按样式真正生效时打一条日志（同一形状同一组参数只打一次），便于上机排查。 */
    private static void logStyleApplied(final TileLongPressConfig.Style style,
            final int cornerDp, final int alphaPct, final boolean active, final Bitmap image) {
        final String shape = style != null ? style.shape : "?";
        sLastAppliedShape = shape;
        final String signature = shape + " " + (active ? "开" : "关")
                + " corner=" + cornerDp + "dp alpha=" + alphaPct
                + "% image=" + (image != null ? image.getWidth() + "x" + image.getHeight() : "none")
                + " via=" + sActiveSource;
        if (signature.equals(sLoggedStyle.get(shape + (active ? "_on" : "_off")))) {
            return;
        }
        sLoggedStyle.put(shape + (active ? "_on" : "_off"), signature);
        XposedBridge.log(TAG + ": style applied → " + signature);
    }

    /**
     * 每块磁贴都需要独立的 Drawable 实例，避免共享 bounds / alpha 状态。
     *
     * <p>取值优先级：长按样式面板（按形状） → 每磁贴配置 → 通配 {@code *} → 全局设置。</p>
     *
     * <p>返回 null 表示“这块磁贴的底图不要动”：要么该形状没设样式且全局替换没开，
     * 要么该形状只调了可见度（此时由 {@link #applyAlphaOnly} 在系统原底图上改 alpha，
     * 这样 1×1 圆形磁贴不会被画成方块）。</p>
     */
    private static Drawable createTileBackground(final Context context, final String spec,
            final boolean active) {
        if (context == null) {
            return null;
        }

        String shape = TileLongPressInterceptor.shapeOf(spec);
        if (shape != null) {
            TileLongPressConfig.Style style = TileLongPressConfig.styleOf(context, shape);
            String base64 = TileLongPressConfig.tileBgImage(context, shape, active);
            boolean hasImage = base64.length() > 0;
            if (hasImage || (style != null && style.touched)) {
                return buildStyledBackground(context, style,
                        hasImage ? decodeShapeImage(base64) : null, active);
            }
        }

        if (!HookPrefs.replaceTileBackground()) {
            return null;
        }

        TileConfigs.Cfg cfg = TileConfigs.forSpec(spec);
        int color = cfg != null && cfg.bgColor != Integer.MIN_VALUE
                ? cfg.bgColor : HookPrefs.tileBackgroundColor();
        int cornerDp = cfg != null && cfg.corner != Integer.MIN_VALUE
                ? cfg.corner : HookPrefs.tileBackgroundCornerRadiusDp();
        String imagePath = cfg != null && cfg.bgImage != null
                ? cfg.bgImage : HookPrefs.tileBackgroundImagePath();
        float density = context.getResources().getDisplayMetrics().density;
        float radiusPx = Math.max(0f, cornerDp * density);
        Bitmap bitmap = bitmapFor(imagePath);
        return new TileBackgroundDrawable(color, radiusPx, bitmap);
    }

    /**
     * 按长按样式面板的设置生成底图。
     *
     * <p><b>只有选了底图才会换掉系统底图</b>：圆角由 {@link TileCornerHook} 重写
     * outline 实现，可见度由 {@link #applyAlphaOnly} 改系统底图的 alpha。
     * 这样磁贴「激活=浅色 / 未激活=深灰」的系统配色不会被我们的一层纯色盖掉。</p>
     *
     * <p>没选底图时返回 null，表示“这块磁贴的底图不要动”。</p>
     */
    private static Drawable buildStyledBackground(final Context context,
            final TileLongPressConfig.Style style, final Bitmap image, final boolean active) {
        int cornerDp = style != null ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        int alphaPct = style != null ? style.alphaPct(active) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        logStyleApplied(style, cornerDp, alphaPct, active, image);
        if (image == null) {
            return null;
        }
        // 负半径才走「跟随形状」：按短边一半取圆角，1×1 保持圆形。
        // 取同一个值给 TileCornerHook 用，磁贴 outline 与图片圆角才不会对不上
        String shape = style != null ? style.shape : null;
        float radiusPx = TileLongPressConfig.cornerRadiusPx(context, shape);
        // 主题包导入过的形状改吃素材自带圆角（比例 × 绘制宽度，drawable 里现算）
        float ratio = TileLongPressConfig.materialCornerRatio(context, shape);
        return new TileBackgroundDrawable(
                HookPrefs.tileBackgroundColor(), radiusPx, image, alphaPct, 0, 0, ratio);
    }

    /**
     * 只调整可见度：直接改系统原底图的 alpha，形状与配色完全不动。
     *
     * @param shape 已判出的形状；不按 spec 再反查一次，卡片这类读不到 spec 的视图
     *              （spec 为 null）否则会在这里静默返回
     */
    private static void applyAlphaOnly(final Drawable drawable, final Context context,
            final String shape, final boolean active) {
        if (drawable == null || shape == null) {
            return;
        }
        String base64 = TileLongPressConfig.tileBgImage(context, shape, active);
        if (base64.length() > 0) {
            return;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(context, shape);
        if (style == null || !style.touched) {
            return;
        }
        drawable.setAlpha(style.alphaPct(active) * 255 / 100);
    }

    private static Drawable asDrawable(final Object result) {
        return result instanceof Drawable ? (Drawable) result : null;
    }

    /**
     * 解码按形状保存的底图（Base64 JPEG），带缓存避免每次画磁贴都解一遍。
     *
     * <p>包内可见：通知底图（{@link NotificationHook}）复用同一份缓存，
     * 同一条 Base64 不会在两处各解一遍。</p>
     */
    static Bitmap decodeShapeImage(final String base64) {
        if (base64 == null || base64.length() == 0) {
            return null;
        }
        final String key = base64.length() + ":" + base64.hashCode();
        synchronized (sBitmapLock) {
            Bitmap cached = sShapeImageCache.get(key);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }
        try {
            byte[] raw = Base64.decode(base64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bitmap == null) {
                logOnce("decodeShapeImage: decode failed, bytes=" + raw.length);
                return null;
            }
            synchronized (sBitmapLock) {
                if (sShapeImageCache.size() >= MAX_SHAPE_IMAGE_CACHE) {
                    sShapeImageCache.clear();
                }
                sShapeImageCache.put(key, bitmap);
            }
            return bitmap;
        } catch (Throwable t) {
            logOnce("decodeShapeImage: " + t);
            return null;
        }
    }

    /** 判断当前是否处于暗色模式；拿不到 Context 时不替换（更安全）。 */
    private static boolean isNightMode(final Context context) {
        if (context == null || context.getResources() == null) {
            return false;
        }
        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 按路径取位图（带 mtime 校验的缓存；大图自动降采样）。 */
    private static Bitmap bitmapFor(final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile()) {
            logOnce("bitmapFor: file not found -> " + path);
            return null;
        }
        final long mtime = file.lastModified();
        synchronized (sBitmapLock) {
            Bitmap cached = sBitmapCache.get(path);
            Long cachedTime = sBitmapTimes.get(path);
            if (cached != null && cachedTime != null && cachedTime == mtime) {
                return cached;
            }
        }
        Bitmap bitmap = decodeScaled(path, MAX_BITMAP_SIDE);
        if (bitmap != null) {
            synchronized (sBitmapLock) {
                sBitmapCache.put(path, bitmap);
                sBitmapTimes.put(path, mtime);
            }
        }
        return bitmap;
    }

    /** 解码本地图片，单边超过 {@code maxSide} 时按 2 的幂降采样。 */
    private static Bitmap decodeScaled(final String path, final int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample <<= 1;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable t) {
            logOnce("decodeScaled: " + t);
            return null;
        }
    }

    /** hook 处于高频路径，这里做节流避免刷爆日志。 */
    private static void logOnce(final String message) {
        long now = System.currentTimeMillis();
        if (now - sLastErrorLog > 5000L) {
            sLastErrorLog = now;
            XposedBridge.log(TAG + ": " + message);
        }
    }

    /** 每个 hook 点第一次被调用时打一条日志，用来确认整条链路有没有跑起来。 */
    private static void logHookHit(final String where) {
        if (sHookHitLogged.add(where)) {
            XposedBridge.log(TAG + ": hook hit → " + where);
        }
    }
}
