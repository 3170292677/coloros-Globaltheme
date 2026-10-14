package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
 * 拦截<b>控制中心空白背景</b>的长按，并负责把「自定义背景图 + 可见度」套到控制中心面板上。
 *
 * <p>ColorOS 16 的长按链路（反编译 {@code classes6.dex} 得到）：</p>
 * <pre>
 * OplusQSRootView.setOnTouchListener(OplusQSRootViewComponent$TouchHandler)
 * OplusQSRootViewComponent.&lt;init&gt;
 *   └─ hostViewGroup.setOnTouchListener(OplusQSRootViewComponent$initEmptyTouchListener$2)
 *        └─ EmptyAreaGestureProxy.onTouch(View, MotionEvent)   ← 只是个 GestureDetector
 *             └─ EmptyAreaGestureProxy$1.onLongPress(MotionEvent)
 *                  ├─ hostViewGroup.findChildViewUnder(x, y) != null → return（按到磁贴就放过）
 *                  └─ $onEmptySpaceClick.invoke(TRUE, event)
 *                       └─ OplusQSRootViewComponent$$ExternalSyntheticLambda2.invoke(Boolean)
 *                            └─ OplusQSRootViewComponent.longClickBlock()
 *                                 └─ qsMoreButtonPopupWindowController.showEdit()
 *                                      ← 弹的就是「⋯」按钮那个编辑弹窗
 * </pre>
 *
 * <p>拦截点选 {@code EmptyAreaGestureProxy$1#onLongPress}：它是最上游的手势入口，
 * 吞掉之后 {@code longClickBlock} 根本不会被调用，编辑弹窗不会出现，也没有统计副作用。
 * 而且它天然只在「触摸点下方没有子视图」时才回调，所以和磁贴长按互不干扰——
 * 按到磁贴时 {@code findChildViewUnder} 会返回子视图，这里压根不会触发。</p>
 *
 * <p>背景套用：控制中心没有现成的「底板视图」（{@code OplusQSContainerImpl} 与
 * {@code QSPanel} 的 {@code android:background} 都是 {@code @android:color/transparent}，
 * 深色观感来自底层模糊/蒙层），所以直接给面板根视图 {@code OplusQSRootView}
 * 设一块自己的底图：有自定义图片就画图片（居中裁剪），否则画底色；
 * 可见度滑杆控制这块底图的 alpha。</p>
 *
 * <p><b>退出时的残留问题</b>：面板关闭动画挪走的是内部内容（磁贴区），
 * 根视图要等动画走完才被隐藏，而我们的底图挂在根视图上，于是会比面板多留约 500ms。
 * 与其继续猜撤图时机，这里改成<b>把底图绑到系统自己的背景层上</b>。</p>
 *
 * <p>ColorOS 16 控制中心面板内部<b>没有</b>任何背景视图（{@code oplus_separate_qs_root_layout.xml}
 * 里只有磁贴区），深色蒙层是系统在窗口层画的 scrim。反编译得到的驱动链是：</p>
 * <pre>
 * OplusQSRootViewComponent$qsPanelExpandFractionListener$1.onBlurFractionChange(F)
 *   └─ OplusSeparateQSPlugin$IStub.updateSeparateBlurExpansion(F, Z, Z, F)
 *        └─ OplusSeparateQSManager$SeparateQSHost.updateSeparateBlurExpansion
 *             └─ ScrimControllerEx.setQsPosition(blurFraction, expandedHeight, PanelType.QS_PANEL, …)
 * </pre>
 * <p>即 {@code ScrimControllerEx#setQsPosition} 的第一个 float 参数就是系统给 QS 蒙层设的
 * 进度（0 = 蒙层完全消失，1 = 完全盖住）。这里 hook 它，把底图 alpha 乘上同一个进度：
 * 系统蒙层淡到多少，底图就淡到多少，收尾时一并撤掉，退场时间与系统完全一致。</p>
 *
 * <p>其余几路信号（{@code collapseQSPanel}、{@code setClosing}、{@code setQsVisible}、
 * 根视图 {@code setVisibility}/{@code setAlpha}、{@code updateExpandedHeight}）都会触发
 * <b>退场线性淡出</b>：按固定时长逐帧把底图 alpha 递减到 0 再撤下。之所以不直接摘掉，
 * 是立刻摘会让画面跳一下，而等系统动画走完又会比面板多留一截；自己控制节奏最稳。</p>
 *
 * <p>另外这些 QS 类在 ColorOS 上是由<b>插件类加载器</b>加载的，模块启动时用
 * {@code lpparam.classLoader} 查类可能直接查不到（钩子会静默失败，这正是之前
 * {@code setClosing}/{@code setQsVisible} 看着「没生效」的原因）。所以状态钩子改成
 * 面板第一次建出来时按<b>运行时类</b>懒安装，启动时那次只是尽力而为。</p>
 */
public final class QsBackgroundHook {

    private static final String TAG = "SystemUIHook/QsBg";

    /** 空白区手势监听器，长按空白的唯一入口。 */
    private static final String CLASS_EMPTY_AREA_LISTENER =
            "com.oplus.systemui.plugins.qs.proxy.EmptyAreaGestureProxy$1";
    /** 控制中心面板根视图。 */
    private static final String CLASS_QS_ROOT_VIEW =
            "com.oplus.systemui.plugins.qs.OplusQSRootView";
    /** 面板组件：关闭状态与可见状态的入口都在它身上。 */
    private static final String CLASS_QS_ROOT_COMPONENT =
            "com.oplus.systemui.plugins.qs.OplusQSRootViewComponent";
    /** 系统给控制中心画背景蒙层（scrim）的地方，底图进度就跟它走。 */
    private static final String CLASS_SCRIM_CONTROLLER_EX =
            "com.android.systemui.statusbar.phone.ScrimControllerEx";
    /** 蒙层进度入口：第一个 float 参数 = QS 蒙层进度（0–1）。 */
    private static final String METHOD_QS_POSITION = "setQsPosition";

    /**
     * 磁贴列表动画代理：系统收起面板时真正逐帧改 alpha 的地方。
     *
     * <p>反编译 {@code PullAnimationProxy#updateState(F,F,I,I)} 得到系统原话：</p>
     * <pre>
     * recyclerView.setTransitionAlpha(f1);
     * Drawable bg = recyclerView.getBackground();
     * if (bg != null) bg.setAlpha((int) (255 * f1));
     * </pre>
     * <p>也就是说<b>面板的淡出 = 磁贴列表的 transitionAlpha</b>，而且它还会顺手把列表
     * 背景 drawable 的 alpha 一起改。我们的底图只要跟住这个值，就是逐帧完全同步。</p>
     */
    private static final String CLASS_PULL_ANIMATION_PROXY =
            "com.oplus.systemui.plugins.qs.customize.view.animation.pull.PullAnimationProxy";
    /** 动画代理上持有的磁贴列表，从这里拿到的实例最权威。 */
    private static final String FIELD_ANIM_RECYCLER = "recyclerView";
    /** 动画入口方法名。 */
    private static final String METHOD_UPDATE_STATE = "updateState";
    /** 面板动画改的就是这个属性（不是 {@code setAlpha}）。 */
    private static final String METHOD_SET_TRANSITION_ALPHA = "setTransitionAlpha";

    /**
     * 系统自带背景层：{@code com.android.systemui.scrim.ScrimView}。
     *
     * <p>它本身就是一块<b>铺满全屏</b>的 View，而且系统早就给它留了「背景图」的位置：</p>
     * <pre>
     * ScrimView.mDrawable / setDrawable(Drawable)   ← 系统自己往上放背景图
     * ScrimView.mViewAlpha / setViewAlpha(float)    ← 系统自己驱动它的淡入淡出
     * ScrimView.onDraw(Canvas)                      ← 在 mDrawableBounds 里画这张图
     * </pre>
     * <p>反编译确认 ColorOS 就是用它当控制中心底板的：</p>
     * <pre>
     * ScrimControllerExImp.refreshBehindDrawable()  → mScrimBehind.setDrawable(...)
     * ScrimControllerExImp.scrimViewsInitted()      → setDrawable(...)
     * ScrimControllerExImp.handleGaussBlur(...)     → scrimView.setViewAlpha(f)
     * </pre>
     * <p>所以正确的做法不是自己往根视图上糊一层，而是<b>直接把图片交给系统这一层</b>：
     * 系统怎么淡它，图片就怎么淡，一行同步代码都不用写。</p>
     */
    private static final String CLASS_SCRIM_VIEW = "com.android.systemui.scrim.ScrimView";
    /** 系统往背景层放图 / 放名字的入口。 */
    private static final String METHOD_SET_DRAWABLE = "setDrawable";
    private static final String METHOD_SET_SCRIM_NAME = "setScrimName";
    /** 背景层上的名字，用来认出「behind」那块（真正当底板的那个）。 */
    private static final String FIELD_SCRIM_NAME = "mScrimName";
    /** 给背景层挂 / 摘 {@code RenderEffect} 的入口。 */
    private static final String METHOD_SET_BLUR_RADIUS = "setBlurRadius";
    /** 编译期看不到的 {@code RenderEffect}，反射时按名字找。 */
    private static final String CLASS_RENDER_EFFECT = "android.graphics.RenderEffect";

    /**
     * Oplus 模糊引擎的每 View 代理。
     *
     * <p>背景层上的图会被 {@code ScrimControllerExImp} 用
     * {@code new AutoBlurDrawable(viewBlurProxy, drawable)} 包一层，
     * 而 {@code AutoBlurDrawable#draw} 画的是
     * {@code viewBlurProxy.getBlurDrawable(defaultDrawable)}——也就是把<b>图本身</b>糊一遍。</p>
     *
     * <p>系统自己的壁纸需要这个模糊，我们放上去的自定义底图不需要，
     * 所以只在被包的是 {@link TileBackgroundDrawable} 时把结果换成原图。</p>
     */
    private static final String CLASS_VIEW_BLUR_PROXY =
            "com.oplusos.systemui.common.blurability.ViewBlurProxy";
    /** 取模糊 drawable 的入口。 */
    private static final String METHOD_GET_BLUR_DRAWABLE = "getBlurDrawable";
    /** 系统包我们那张图的壳，用来判断「这一张其实是我们的图」。 */
    private static final String CLASS_AUTO_BLUR_DRAWABLE =
            "com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable";

    /** 监听器上持有的宿主 ViewGroup（实际是磁贴列表 RecyclerView）。 */
    private static final String FIELD_HOST_VIEW_GROUP = "$hostViewGroup";
    /** 根视图上持有的触摸处理器，其外部类实例就是面板组件。 */
    private static final String FIELD_TOUCH_HANDLER = "touchHandler";
    /** Kotlin 内部类指向外部类实例的字段。 */
    private static final String FIELD_OUTER = "this$0";

    /** alpha / 展开比例低于这个值就认为面板已经退场。 */
    private static final float ALPHA_GONE = 0.02f;

    /** 退场淡出时长：线性逐帧把底图 alpha 降到 0。 */
    private static final long FADE_OUT_MS = 80L;
    /** 淡出帧间隔，约一帧一次。 */
    private static final long FADE_FRAME_MS = 16L;

    /**
     * 控制中心面板根视图。
     *
     * <p>控制中心会按需建出<b>多个</b>根视图实例（主面板、独立窗口重建等），
     * 底图会套到每一个上面，撤下时也必须每个都清，否则漏掉的那个会残留到面板被隐藏。</p>
     */
    private static final List<WeakReference<View>> sRoots = new ArrayList<WeakReference<View>>();

    /** 已按运行时类装过钩子的类，避免重复安装。 */
    private static final Set<Class<?>> sRootClasses = new HashSet<Class<?>>();
    private static final Set<Class<?>> sComponentClasses = new HashSet<Class<?>>();

    /**
     * 面板组件（{@code OplusQSRootViewComponent}）实例。
     *
     * <p>拉起模块 App 前需要先收起控制中心，否则新 Activity 会开在控制中心后面，
     * 看起来就是「点了按钮没反应」；收起的入口（{@code collapseQSPanel}）在组件身上，
     * 所以从状态钩子和根视图的 {@code touchHandler} 两条路把它记住。</p>
     */
    private static volatile WeakReference<Object> sComponent = new WeakReference<Object>(null);

    /** 面板正在关闭/不可见：此时不套底图，避免比面板多留一截。 */
    private static volatile boolean sHidden;

    /** 已解码的背景图缓存，用 Base64 串做身份，避免每次刷新都解码。 */
    private static volatile String sBitmapKey = "";
    private static volatile Bitmap sBitmap;

    private static volatile int sHookHits;
    private static volatile int sClosingHits;
    private static volatile int sQsVisibleHits;
    private static volatile int sCollapseHits;
    private static volatile int sAlphaHits;
    private static volatile int sFractionHits;
    private static volatile float sLastAlpha = 1f;
    private static volatile float sLastFraction = 1f;
    private static volatile String sLastApply = "尚未套用";

    /** scrim 钩子是否已装上（装不上就退回那几路兜底信号）。 */
    private static volatile boolean sScrimHooked;
    /** {@code setQsPosition} 命中次数：> 0 说明底图已经在跟系统蒙层走。 */
    private static volatile int sScrimHits;
    /** 系统最后一次给出的 QS 蒙层进度；取不到时按 1（不衰减）走，保持旧行为。 */
    private static volatile float sLastScrimFraction = 1f;
    /** 可见度滑杆换算出的底图基准可见度（0–200），乘上蒙层进度才是实际 alpha。 */
    private static volatile int sBaseAlphaPct = 100;
    /** 底图当前实际 alpha（0–255），淡出动画的起点。 */
    private static volatile int sCurrentAlpha;
    /** 防止「按蒙层进度更新」过程中再次进入（applyTo 会读回当前进度）。 */
    private static volatile boolean sUpdating;

    /** 是否正在执行退场线性淡出；期间不再理会蒙层进度。 */
    private static volatile boolean sFading;
    private static volatile int sFadeHits;
    private static Handler sFadeHandler;
    private static Runnable sFadeRunnable;
    private static long sFadeStartAt;
    private static int sFadeFromAlpha;

    /** 磁贴列表：退场时真正往下滑走的是它，底图淡出时机跟它的位移对齐。 */
    private static volatile WeakReference<View> sTileList = new WeakReference<View>(null);
    private static final Set<Class<?>> sTileListClasses = new HashSet<Class<?>>();
    private static volatile int sTileMoveHits;
    private static volatile float sLastTileTranslationY;

    /** 看门狗：直接盯磁贴列表的真实可见度，不依赖任何一路钩子是否命中。 */
    private static final long WATCH_INTERVAL_MS = 16L;
    private static volatile boolean sWatching;
    private static volatile int sWatchTicks;
    private static volatile int sLastTileTop = Integer.MIN_VALUE;
    private static volatile int sRestingTileTop = Integer.MIN_VALUE;
    /** 磁贴可见度（含祖先 alpha），底图直接跟它走。 */
    private static volatile float sTileVisibility = 1f;
    private static volatile int sTileSyncHits;
    private static Handler sWatchHandler;
    private static Runnable sWatchRunnable;
    private static final int[] sLocation = new int[2];

    /** 是否已挂上「跟住系统动画」的钩子（只挂一次）。 */
    private static volatile boolean sAlphaHooked;
    /** 系统改磁贴列表 transitionAlpha 的命中次数：> 0 说明底图已在逐帧跟系统动画。 */
    private static volatile int sAlphaHookHits;
    /** 磁贴列表是不是从系统动画代理那里拿到的权威实例。 */
    private static volatile boolean sTileListFromAnimator;

    /**
     * 系统自带背景层（{@code ScrimView}）实例。
     *
     * <p>底图优先放这里：位置是系统自己的、淡出也是系统自己在驱动，
     * 不需要我们再补一层，也就不会出现「比面板多留一截」的问题。</p>
     */
    private static final List<WeakReference<View>> sScrimViews = new ArrayList<WeakReference<View>>();
    private static final Set<Class<?>> sScrimViewClasses = new HashSet<Class<?>>();
    /** 正在把我们的图设进背景层，防止 setDrawable 钩子自我递归。 */
    private static volatile boolean sSettingScrimDrawable;
    /** 系统往背景层放图的次数。 */
    private static volatile int sScrimDrawableHits;
    /** 已接管的背景层名字，诊断用。 */
    private static volatile String sScrimNames = "-";
    /** 已经套到系统底板上的可见度与图片身份，用来判断设置有没有变。 */
    private static volatile int sScrimAppliedAlpha = -1;
    private static volatile String sScrimAppliedKey = "";
    /** {@code ScrimView#setDrawable(Drawable)}，用来精确传 null（XposedHelpers 传 null 会解析不出方法）。 */
    private static volatile Method sSetDrawableMethod;
    private static volatile Method sGetDrawableMethod;
    /** 被我们清空掉自带背景的小尺寸 scrim 次数。 */
    private static volatile int sScrimBlankHits;
    /**
     * 每块背景层上我们放的那张图。
     *
     * <p>留着引用是为了<b>就地改可见度</b>：{@link TileBackgroundDrawable} 把用户可见度
     * 单独存着（见它的类注释），所以拖动滑杆时只要调 {@code setUserAlphaPct} 再重绘即可，
     * 不用重新 {@code setDrawable}——那条路是系统异步执行的，重新放图反而会跟系统抢。</p>
     */
    private static final Map<View, TileBackgroundDrawable> sScrimDrawables =
            new WeakHashMap<View, TileBackgroundDrawable>();

    /**
     * 接管<b>之前</b>系统自己放在背景层上的那张图。
     *
     * <p>没设自定义底图时必须把它原样还回去：系统那张图是「壁纸 + 模糊」，
     * 用别的颜色去顶替就等于把系统背景和它的模糊一起盖掉了。</p>
     */
    private static final Map<View, Drawable> sScrimOriginalDrawables =
            new WeakHashMap<View, Drawable>();

    /**
     * 我们放上去的「什么都不画」占位图。
     *
     * <p>全进程共用一个实例，是为了在 {@code setDrawable} 钩子里能<b>按引用</b>把它认出来——
     * 它是我们设的，不是系统自己放上来的，不能当成「系统原图」存起来。</p>
     */
    private static final Drawable sBlankDrawable = new ColorDrawable(Color.TRANSPARENT);

    /** 模糊引擎钩子是否已装上。 */
    private static volatile boolean sViewBlurHooked;
    /** {@code AutoBlurDrawable#draw} 钩子是否已装上（「保留模糊」时靠它补画我们的颜色/图）。 */
    private static volatile boolean sBlurDrawHooked;
    /**
     * 背景层自己的 {@code onDraw} 钩子是否已装上。
     *
     * <p>装上了就不再走 {@code AutoBlurDrawable#draw} 那条补画：两条都画就是叠两层。
     * 优先用这条，见 {@link #onScrimDrawn}。</p>
     */
    private static volatile boolean sScrimOnDrawHooked;
    /** {@code ScrimView#setBlurRadius} 钩子是否已装上。 */
    private static volatile boolean sScrimBlurHooked;
    /** {@code View#setRenderEffect}，反射拿一次就缓存。 */
    private static volatile Method sSetRenderEffectMethod;
    /** 「保留模糊」时在系统模糊层上补画的次数，诊断用。 */
    private static volatile int sBlurOverlayHits;
    /** 「保留模糊」时被护住、没被清掉的模糊层数量，诊断用。 */
    private static volatile int sBlurLayersKept;
    /** 读到 {@code ScrimView#getViewAlpha()} 失败的次数，诊断用。 */
    private static volatile int sViewAlphaMisses;

    /**
     * 每个模糊代理服务的是哪块背景层（{@code ScrimView}）；不是背景层的存 null。
     *
     * <p>判断要反射读 {@code ViewBlurProxy#view}，而 {@code getBlurDrawable} <b>每帧</b>都跑，
     * 所以结果缓存住：只有第一次碰到某个代理时才反射一次。
     * 磁贴、通知行也各有自己的代理，靠这个把它们和背景层区分开。</p>
     */
    private static final Map<Object, View> sProxyScrimView = new WeakHashMap<Object, View>();

    private QsBackgroundHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        hookEmptyAreaLongPress(lpparam);
        hookRootInflate(lpparam);
        hookPanelState(lpparam);
        ensureScrimHooks(lpparam.classLoader);
        ensureAlphaSyncHooks(lpparam.classLoader);
        ensureScrimViewHooks(lpparam.classLoader);
        ensureViewBlurHooks(lpparam.classLoader);
        ensureBlurDrawHooks(lpparam.classLoader);
        ensureScrimBlurHooks(lpparam.classLoader);
    }

    /**
     * 挂「跟住系统动画」的钩子——这是底图与淡出动画完全同步的关键。
     *
     * <p>两件事：</p>
     * <ol>
     *   <li>hook {@code android.view.View#setTransitionAlpha(float)}。系统收起面板时
     *       {@code PullAnimationProxy.updateState} 会调 {@code recyclerView.setTransitionAlpha(f)}，
     *       每次调用我们都把底图 alpha 设成同一个 {@code f}——不是轮询近似，是逐帧同一个值。</li>
     *   <li>hook {@code PullAnimationProxy#updateState}，从它身上拿到系统动画真正操作的
     *       那个 RecyclerView 实例（比自己遍历视图树找可靠）。</li>
     * </ol>
     *
     * <p>{@code setTransitionAlpha} 调用频率很低（只有转场动画会调），钩子里第一件事就是
     * 拿引用比一下是不是我们的磁贴列表，不是就直接返回，开销可以忽略。</p>
     */
    private static void ensureAlphaSyncHooks(final ClassLoader loader) {
        if (sAlphaHooked || loader == null) {
            return;
        }
        synchronized (QsBackgroundHook.class) {
            if (sAlphaHooked) {
                return;
            }
            try {
                XposedHelpers.findAndHookMethod("android.view.View", loader,
                        METHOD_SET_TRANSITION_ALPHA, float.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param)
                                    throws Throwable {
                                Object arg = param.args.length > 0 ? param.args[0] : null;
                                if (!(arg instanceof Float)
                                        || !(param.thisObject instanceof View)) {
                                    return;
                                }
                                onTileTransitionAlpha((View) param.thisObject,
                                        ((Float) arg).floatValue());
                            }
                        });
                sAlphaHooked = true;
                XposedBridge.log(TAG + ": hooked View#" + METHOD_SET_TRANSITION_ALPHA);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + METHOD_SET_TRANSITION_ALPHA
                        + " failed - " + t);
            }
            Class<?> proxy = XposedHelpers.findClassIfExists(
                    CLASS_PULL_ANIMATION_PROXY, loader);
            if (proxy == null) {
                XposedBridge.log(TAG + ": " + CLASS_PULL_ANIMATION_PROXY + " not found");
                return;
            }
            try {
                XposedBridge.hookAllMethods(proxy, METHOD_UPDATE_STATE, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object recycler;
                        try {
                            recycler = XposedHelpers.getObjectField(
                                    param.thisObject, FIELD_ANIM_RECYCLER);
                        } catch (Throwable t) {
                            return;
                        }
                        if (recycler instanceof View) {
                            adoptTileList((View) recycler, true);
                        }
                    }
                });
                XposedBridge.log(TAG + ": hooked " + CLASS_PULL_ANIMATION_PROXY
                        + "#" + METHOD_UPDATE_STATE);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + METHOD_UPDATE_STATE + " failed - " + t);
            }
        }
    }

    /**
     * 接管系统自带背景层 {@code ScrimView}。
     *
     * <p>挂 {@code setDrawable(Drawable)} 和 {@code setScrimName(String)}：
     * 系统自己往背景层放图时（{@code refreshBehindDrawable}、{@code scrimViewsInitted}），
     * 我们顺势把图换成我们的。之后它的淡入淡出完全由系统驱动
     * （{@code setViewAlpha}），我们不再插手。</p>
     */
    private static void ensureScrimViewHooks(final ClassLoader loader) {
        if (loader == null) {
            return;
        }
        Class<?> scrimView = XposedHelpers.findClassIfExists(CLASS_SCRIM_VIEW, loader);
        if (scrimView == null || !sScrimViewClasses.add(scrimView)) {
            return;
        }
        try {
            sSetDrawableMethod = XposedHelpers.findMethodExact(scrimView,
                    METHOD_SET_DRAWABLE, Drawable.class);
            sGetDrawableMethod = XposedHelpers.findMethodExact(scrimView, "getDrawable");
            XposedHelpers.findAndHookMethod(scrimView, METHOD_SET_DRAWABLE,
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param)
                                throws Throwable {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            adoptScrimView(view);
                            sScrimDrawableHits++;
                            if (sSettingScrimDrawable) {
                                return;
                            }
                            Object arg = param.args.length > 0 ? param.args[0] : null;
                            if (arg instanceof Drawable
                                    && arg != sBlankDrawable
                                    && !(arg instanceof TileBackgroundDrawable)
                                    && !wrapsOurDrawable((Drawable) arg)) {
                                // 系统自己放上来的图（底板上那张默认是「壁纸 + 模糊」）。
                                // 记下来：没设自定义底图时要原样还回去
                                sScrimOriginalDrawables.put(view, (Drawable) arg);
                            }
                            if (isFullScreenScrim(view)) {
                                if (isVeilScrim(view)) {
                                    // 盖在底板上面的整屏蒙层（通知层 / 最前层 / oplus 蒙层）：
                                    // 它跟着亮暗模式变色，再给它套一遍我们的颜色就是自己叠出
                                    // 「第二层」，亮色下就明显偏亮。底板已经有我们那层，这里清空
                                    blankVeilScrim(view);
                                } else {
                                    applyToScrimView(view);
                                }
                                return;
                            }
                            // 小尺寸的 scrim（底部区域那几块）不是底板。它自己也有一张图，
                            // 会盖在底板上面、又比底板小一圈，看着就是「第二层」——
                            // 只有在我们确实接管了底板时才清它，否则那是系统自己的层次
                            if (hasCustomBackground(view.getContext())) {
                                blankScrimDrawable(view);
                            }
                        }
                    });
            // 「保留模糊」时的补画挂在这一层自己的 onDraw 上：
            // 它的第一句就是 mDrawable.getAlpha()，画的就是那块底板，
            // 我们接在它后面画，稳在自己这块底板之上、且不挑系统那一刻用的是什么图
            // （挂在 AutoBlurDrawable#draw 上要先反查「模糊图 → 它服务的背景层」，
            // 那一步对不上就一点都画不出来，暗色下背景层走的就是模糊图这条路）。
            XposedHelpers.findAndHookMethod(scrimView, "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param)
                                throws Throwable {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            Object arg = param.args.length > 0 ? param.args[0] : null;
                            onScrimDrawn((View) param.thisObject,
                                    arg instanceof Canvas ? (Canvas) arg : null);
                        }
                    });
            sScrimOnDrawHooked = true;
            XposedHelpers.findAndHookMethod(scrimView, METHOD_SET_SCRIM_NAME,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param)
                                throws Throwable {
                            // 只用来把名字记进诊断，接管与否看「系统有没有往它上面放图」
                            sScrimNames = describeScrimViews();
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + CLASS_SCRIM_VIEW);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + CLASS_SCRIM_VIEW + " failed - " + t);
        }
    }

    /**
     * 让「压掉背景模糊」那颗开关真正说了算。
     *
     * <p>系统把背景层上的图交给 {@code ScrimControllerExImp#refreshBehindDrawable} 时，
     * 会用 {@code new AutoBlurDrawable(viewBlurProxy, drawable)} 包一层；而
     * {@code AutoBlurDrawable#draw} 画的是 {@code viewBlurProxy.getBlurDrawable(defaultDrawable)}
     * ——也就是把这层图先过一遍模糊再画。所以这里拦不拦，直接决定模糊留不留得住。</p>
     *
     * <p><b>背景层</b>（控制中心整块底板）：</p>
     * <ul>
     *   <li>开关<b>关</b>（默认，副标题写的「关闭则保留系统模糊」）：<b>不拦</b>，
     *       这层照旧走系统的模糊。之前这里无条件替换结果，导致不管开关什么状态、
     *       只要设过底图就等于把模糊压掉了，开关形同虚设。</li>
     *   <li>开关<b>开</b>：换成我们自己那张<b>不糊</b>的图。注意不能只是「不糊」就算了——
     *       这层的 defaultDrawable 是一张透明遮罩，整个背景本来全靠模糊层撑起来，
     *       抽掉模糊又不补图就会透光，所以这里换上我们自己的底图。</li>
     * </ul>
     *
     * <p><b>磁贴 / 通知</b>那条路不受开关影响：那些底图是我们自己贴的，
     * 任何时候都不该被系统再糊一遍。</p>
     *
     * <p>开销：{@code draw} 每帧都会走到这里，所以开关读的是进程内缓存
     * （{@link TileLongPressConfig#bgBlurOff()}），不碰 prefs；第一句是一次
     * {@code instanceof}，不是我们的图立刻放行。</p>
     */
    private static void ensureViewBlurHooks(final ClassLoader loader) {
        if (sViewBlurHooked || loader == null) {
            return;
        }
        synchronized (QsBackgroundHook.class) {
            if (sViewBlurHooked) {
                return;
            }
            Class<?> proxy = XposedHelpers.findClassIfExists(CLASS_VIEW_BLUR_PROXY, loader);
            if (proxy == null) {
                XposedBridge.log(TAG + ": " + CLASS_VIEW_BLUR_PROXY + " not found");
                return;
            }
            try {
                XposedHelpers.findAndHookMethod(proxy, METHOD_GET_BLUR_DRAWABLE,
                        Drawable.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param)
                                    throws Throwable {
                                Object arg = param.args.length > 0 ? param.args[0] : null;
                                View scrim = scrimViewOfProxy(param.thisObject);
                                if (scrim != null) {
                                    if (!TileLongPressConfig.bgBlurOff()) {
                                        // 开关关着：这层就交给系统去糊，模糊才留得住
                                        return;
                                    }
                                    // 开关打开：不能只把模糊抽掉——这层的 defaultDrawable
                                    // 是一张透明遮罩，整个背景本来全靠模糊层撑起来，
                                    // 抽掉不补图就透光了，所以换成我们的底图
                                    TileBackgroundDrawable mine = sScrimDrawables.get(scrim);
                                    if (mine != null) {
                                        param.setResult(mine);
                                        return;
                                    }
                                    if (arg instanceof TileBackgroundDrawable) {
                                        param.setResult(arg);
                                    }
                                    return;
                                }
                                if (arg instanceof Drawable
                                        && NotificationHook.isOwnBackground((Drawable) arg)) {
                                    // 磁贴 / 通知行上的底图：那些是我们自己贴的，别被糊掉。
                                    // 通知分组那一路的底图外面还包了 NotificationHook 的逐角
                                    // 裁剪层，只认 TileBackgroundDrawable 会漏掉它；一旦漏掉，
                                    // OPPO 的 ext 就会拿模糊图顶掉我们的底图、再叠一层堆叠色，
                                    // 分组头会莫名变深、堆叠行还是系统色。
                                    param.setResult(arg);
                                }
                            }
                        });
                sViewBlurHooked = true;
                XposedBridge.log(TAG + ": hooked " + CLASS_VIEW_BLUR_PROXY
                        + '#' + METHOD_GET_BLUR_DRAWABLE);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + METHOD_GET_BLUR_DRAWABLE + " failed - " + t);
            }
        }
    }

    /**
     * 「压掉背景模糊」在 {@code ScrimView} 那条路上的入口。
     *
     * <p>反编译 {@code ScrimView#setBlurRadius(F)} 的原话就是：</p>
     * <pre>
     * if (radius &gt; 0) setRenderEffect(RenderEffect.createBlurEffect(radius, radius, CLAMP));
     * else            setRenderEffect(null);
     * </pre>
     * <p>开关打开时整个方法跳过（不再往 View 上挂 RenderEffect），
     * 并且把之前已经挂上去的那个擦掉——否则关掉之后它还会一直生效。</p>
     *
     * <p><b>注意</b>：这个方法在当前 ROM 里没有任何调用者（全 dex 扫过），
     * 真正生效的模糊走的是 {@code ViewBlurProxy#getBlurDrawable}，
     * 见 {@link #ensureViewBlurHooks}。这里挂上是为了兜住调用它的版本。</p>
     */
    private static void ensureScrimBlurHooks(final ClassLoader loader) {
        if (sScrimBlurHooked || loader == null) {
            return;
        }
        synchronized (QsBackgroundHook.class) {
            if (sScrimBlurHooked) {
                return;
            }
            Class<?> scrimView = XposedHelpers.findClassIfExists(CLASS_SCRIM_VIEW, loader);
            if (scrimView == null) {
                return;
            }
            try {
                XposedHelpers.findAndHookMethod(scrimView, METHOD_SET_BLUR_RADIUS,
                        float.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param)
                                    throws Throwable {
                                if (!(param.thisObject instanceof View)) {
                                    return;
                                }
                                View view = (View) param.thisObject;
                                if (!TileLongPressConfig.isBgBlurOff(view.getContext())) {
                                    return;
                                }
                                Object arg = param.args.length > 0 ? param.args[0] : null;
                                if (arg instanceof Float && ((Float) arg).floatValue() > 0f) {
                                    clearRenderEffect(view);
                                }
                                // 跳过原方法：既不挂新的模糊，也不去动已有的
                                param.setResult(null);
                            }
                        });
                sScrimBlurHooked = true;
                XposedBridge.log(TAG + ": hooked " + CLASS_SCRIM_VIEW
                        + '#' + METHOD_SET_BLUR_RADIUS);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + METHOD_SET_BLUR_RADIUS + " failed - " + t);
            }
        }
    }

    /**
     * 把已经挂在 View 上的 {@code RenderEffect} 清掉。
     *
     * <p>编译用的 android.jar 比设备旧，{@code View#setRenderEffect} 和
     * {@code RenderEffect} 都不在编译期可见，所以走反射；
     * 缓存住 {@link Method}，避免每次重新查找。</p>
     */
    private static void clearRenderEffect(final View view) {
        try {
            Method method = sSetRenderEffectMethod;
            if (method == null) {
                Class<?> effect = XposedHelpers.findClassIfExists(
                        CLASS_RENDER_EFFECT, view.getClass().getClassLoader());
                if (effect == null) {
                    return;
                }
                method = view.getClass().getMethod("setRenderEffect", effect);
                sSetRenderEffectMethod = method;
            }
            method.invoke(view, new Object[]{null});
            view.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这个模糊代理服务的是哪块背景层；不是背景层就返回 {@code null}。
     *
     * <p>{@code ViewBlurProxy} 构造时就存了它服务的那个 View，读出来比一下类名即可。</p>
     */
    private static View scrimViewOfProxy(final Object proxy) {
        if (proxy == null) {
            return null;
        }
        if (sProxyScrimView.containsKey(proxy)) {
            return sProxyScrimView.get(proxy);
        }
        View scrim = null;
        try {
            Object view = XposedHelpers.getObjectField(proxy, "view");
            if (isScrimView(view)) {
                scrim = (View) view;
            }
        } catch (Throwable ignored) {
        }
        sProxyScrimView.put(proxy, scrim);
        return scrim;
    }

    /**
     * 这个对象是不是一块背景层（{@code ScrimView} 或它的子类）。
     *
     * <p>原来这里写的是「类名完全相等」。ColorOS 上背景层落到子类时反查就整个失效：
     * {@code getBlurDrawable} 那条分支不认这层，「保留模糊」时的补画也找不到要画的视图。
     * 按继承链认才稳。</p>
     */
    private static boolean isScrimView(final Object view) {
        if (!(view instanceof View)) {
            return false;
        }
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            if (CLASS_SCRIM_VIEW.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一块「什么都不画」的占位图。
     *
     * <p><b>绝对不要给 {@code ScrimView} 传 null。</b>反编译它的 {@code onDraw}：
     * 第一句就是 {@code mDrawable.getAlpha()}，没有 null 判断，
     * 传 null 会让 SystemUI 在绘制时空指针崩溃——表现就是「一恢复背景就一直黑屏」。
     * 用全透明的 {@link ColorDrawable} 代替：它的 {@code getAlpha()} 返回 0，
     * {@code onDraw} 会直接跳过绘制，效果一样但不会崩。</p>
     *
     * <p>全进程共用 {@link #sBlankDrawable} 这一个实例，{@code setDrawable} 钩子才能按引用认出它。</p>
     */
    private static Drawable blankDrawable() {
        return sBlankDrawable;
    }

    /** 记下这块系统背景层，并把当前设置套上去。 */
    private static void adoptScrimView(final View view) {
        boolean known = false;
        for (int i = 0; i < sScrimViews.size(); i++) {
            if (sScrimViews.get(i).get() == view) {
                known = true;
                break;
            }
        }
        if (!known) {
            sScrimViews.add(new WeakReference<View>(view));
            XposedBridge.log(TAG + ": adopted " + CLASS_SCRIM_VIEW
                    + " name=" + scrimNameOf(view));
        }
        sScrimNames = describeScrimViews();
    }

    /** 背景层上的名字（{@code mScrimName}），用来确认接管的是哪一块。 */
    private static String scrimNameOf(final View view) {
        try {
            Object name = XposedHelpers.getObjectField(view, FIELD_SCRIM_NAME);
            return name == null ? "null" : String.valueOf(name);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String describeScrimViews() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sScrimViews.size(); i++) {
            View view = sScrimViews.get(i).get();
            if (view == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(scrimNameOf(view))
                    .append('[').append(view.getWidth()).append('×')
                    .append(view.getHeight()).append(']')
                    .append(isVeilScrim(view) ? "·第二层" : (isBaseScrim(view) ? "·底板" : ""));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /**
     * 是不是铺满全屏的那块底板。
     *
     * <p>系统里有好几块 {@code ScrimView}，只有底板是全屏的；底部区域那几块又小又晚出现，
     * 往上面放图就会多出一层小一圈的背景。这里按实际尺寸把底板认出来。</p>
     */
    private static boolean isFullScreenScrim(final View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            // 还没量过尺寸，等看门狗下一轮再看
            return false;
        }
        DisplayMetrics metrics = view.getResources().getDisplayMetrics();
        return view.getWidth() >= metrics.widthPixels * 0.9f
                && view.getHeight() >= metrics.heightPixels * 0.9f;
    }

    /**
     * 系统真正那块「底板」。
     *
     * <p>{@code ScrimView#mScrimName} 里带 {@code behind} 的那块才是控制中心整块底板
     * （系统也就往它上面放「壁纸 + 模糊」）。其余整屏 scrim 全盖在它上面、各自跟着
     * 亮暗模式变色，见 {@link #isVeilScrim}。</p>
     */
    private static boolean isBaseScrim(final View view) {
        return scrimNameOf(view).toLowerCase().contains("behind");
    }

    /** 当前这些背景层里有没有认出底板；认不出就退回老行为，免得整块背景不显示。 */
    private static boolean hasBaseScrim() {
        List<View> views = liveScrimViews();
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            if (isFullScreenScrim(view) && isBaseScrim(view)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这块整屏 scrim 是不是「盖在底板上面的那一层」——不该被我们套样式，而该清掉。
     *
     * <p>它们是跟着亮暗模式变色的蒙层（通知层 / 最前层 / oplus 自己的 mask）。
     * 上一版给<b>每一块</b>整屏 scrim 都套了同一个颜色 / 同一张图，这些蒙层于是也各画
     * 一遍：亮色下垫的是浅色、暗色下垫的是深色，同一份颜色就显出两种亮度，看着就是
     * 「和主题包叠了两层、亮色过亮」。控制中心背景只该有一层，所以这些一律按
     * 「第二层」处理。</p>
     *
     * <p>锁屏那层（名字带 {@code keyguard}）不动，免得把解锁界面一起清掉。</p>
     */
    private static boolean isVeilScrim(final View view) {
        if (view == null || !isFullScreenScrim(view)) {
            return false;
        }
        if (isBaseScrim(view) || !hasBaseScrim()) {
            return false;   // 它自己就是底板，或者压根没认出底板（退回老行为）
        }
        return !scrimNameOf(view).toLowerCase().contains("keyguard");
    }

    /** 清掉一块「第二层」整屏蒙层，并且不再往它上面补画我们那层。 */
    private static void blankVeilScrim(final View view) {
        if (view == null) {
            return;
        }
        Context context = view.getContext();
        if (context == null || !hasCustomBackground(context)) {
            return;
        }
        sScrimDrawables.remove(view);   // 画钩子那边也就不会再补一层
        blankScrimDrawable(view, true);
    }

    /** 把所有「第二层」整屏蒙层清一遍（看门狗每轮叫一次）。 */
    private static void blankVeilScrims() {
        List<View> views = liveScrimViews();
        for (int i = 0; i < views.size(); i++) {
            if (isVeilScrim(views.get(i))) {
                blankVeilScrim(views.get(i));
            }
        }
    }

    /**
     * 自愈：底板的尺寸/设置是后来才确定的，这里定期补一次。
     *
     * <p>覆盖两种情况——面板刚打开时底板还没量出尺寸；以及用户改了可见度或换了图。
     * 只在值真的变了才重新套，平时这一路什么都不做。</p>
     */
    private static void refreshScrimLayer() {
        List<View> views = liveScrimViews();
        sScrimNames = describeScrimViews();
        Context context = null;
        for (int i = 0; i < views.size(); i++) {
            if (isFullScreenScrim(views.get(i))) {
                context = views.get(i).getContext();
                break;
            }
        }
        if (context == null) {
            return;
        }
        if (hasCustomBackground(context)) {
            // 小尺寸 scrim 出现得晚（打开面板约 300ms 后），这里定期补清一次
            blankSmallScrims();
            // 整屏蒙层同理：系统时不时会自己再放一次图上来，每轮都确认清干净，
            // 保证整块背景只有底板那一层（见 isVeilScrim）
            blankVeilScrims();
        } else {
            // 没设自定义底图：把之前换掉/清掉的那些图都还给系统
            for (int i = 0; i < views.size(); i++) {
                releaseScrimView(views.get(i));
            }
        }
        int alpha = 100 - TileLongPressConfig.bgTransparencyPct(context);
        String key = settingKey(context);
        if (alpha == sScrimAppliedAlpha && key.equals(sScrimAppliedKey)) {
            return;
        }
        applyToAll();
    }

    /** 背景图的身份串，用来判断设置有没有变。 */
    private static String imageKey(final Context context) {
        String base64 = TileLongPressConfig.shapeBgImage(context, PrefKeys.SHAPE_QS_BACKGROUND);
        return base64 == null ? "" : base64;
    }

    /**
     * 用户有没有设自定义背景（底图<b>或</b>纯色）。
     *
     * <p>两样都没设的时候我们<b>完全不碰</b>系统背景层：那块底板上的图是「壁纸 + 模糊」，
     * 是我们没法自己造出来的，只能原样留给系统。</p>
     */
    private static boolean hasCustomBackground(final Context context) {
        return context != null
                && (imageKey(context).length() > 0
                || bgColor(context) != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM);
    }

    /** 背景的纯色；没设返回 {@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM}。 */
    private static int bgColor(final Context context) {
        return TileLongPressConfig.qsBackgroundColor(context);
    }

    /**
     * 背景设置的身份串（颜色 + 底图），用来判断设置有没有变。
     *
     * <p>必须把颜色也算进来：只改颜色时底图那句 base64 是不变的，
     * 光比图片会让「改了颜色」被判成「没变」，于是画面上什么都不动。</p>
     */
    private static String settingKey(final Context context) {
        // 模糊开关也得算进来：它决定背景层是「系统模糊图 + 我们的图」还是只有我们的图，
        // 少了这一段，拨开关时会被判成「设置没变」而什么都不做
        return Integer.toHexString(bgColor(context)) + "|" + imageKey(context)
                + (TileLongPressConfig.isBgBlurOff(context) ? "|blurOff" : "|blurOn");
    }

    /**
     * 这张图是不是「系统把<b>我们的</b>底图包了一层」的产物。
     *
     * <p>接管之后系统会用 {@code new AutoBlurDrawable(proxy, 我们的图)} 再放一次，
     * 那一张也是 {@code setDrawable} 进来的、又不在我们的已知列表里。
     * 如果不认出来，它就会被当成「系统原图」存下来——用户取消底图时
     * 还回去的会是自己的图，而不是系统壁纸。</p>
     */
    private static boolean wrapsOurDrawable(final Drawable drawable) {
        if (drawable == null) {
            return false;
        }
        if (containsOurDrawable(drawable)) {
            // 我们自己包出来的那层（「保留模糊」时是 [系统模糊图, 我们的图]）
            return true;
        }
        if (!CLASS_AUTO_BLUR_DRAWABLE.equals(drawable.getClass().getName())) {
            return false;
        }
        try {
            return containsOurDrawable((Drawable) XposedHelpers.callMethod(
                    drawable, "getDefaultDrawable"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这棵树里有没有我们自己的底图（含被系统包一层 AutoBlurDrawable 的情况）。 */
    private static boolean containsOurDrawable(final Drawable drawable) {
        if (drawable instanceof TileBackgroundDrawable) {
            return true;
        }
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (containsOurDrawable(layers.getDrawable(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 这张图是不是「系统用它来画模糊」的那张。
     *
     * <p>反编译得到的两种：{@code AutoBlurDrawable}（常规，内部画
     * {@code ViewBlurProxy#getBlurDrawable} 的返回值）与 {@code WallpaperBlurDrawable}
     * （锁屏壁纸模糊那条分支）。只有它们垫在底下才有意义——
     * 别的东西（占位色之类）垫了只是白搭。</p>
     */
    private static boolean isBlurDrawable(final Drawable drawable) {
        if (drawable == null || drawable == sBlankDrawable) {
            return false;
        }
        String name = drawable.getClass().getName();
        return CLASS_AUTO_BLUR_DRAWABLE.equals(name) || name.endsWith("WallpaperBlurDrawable");
    }

    /**
     * 「保留模糊」时，在背景层画完它自己那张图之后，把我们的颜色 / 底图补画上去。
     *
     * <p><b>为什么不在 {@code AutoBlurDrawable#draw} 里画</b>：那条路要先反查
     * 「这张模糊图服务的背景层是哪块」，而反查靠的是模糊代理里那个 {@code view} 字段，
     * 还要求它的类名<b>完全等于</b> {@code com.android.systemui.scrim.ScrimView}——
     * 背景层只要是个子类就查不到，返回 null，补画当场放弃。表现就是：亮色下背景层挂的
     * 不是模糊图（走「整层换掉」那条路，见 {@link #applyToScrimView}）于是看着一切正常；
     * 一切到暗色、背景层换成模糊图，我们的颜色 / 底图就一点都看不见，
     * 整块面板只剩系统壁纸的模糊。</p>
     *
     * <p>挂在这块视图自己的 {@code onDraw} 后面就没有这个问题：{@code onDraw} 画的
     * 就是那块底板，我们接在它后面画，一定在它之上，也不用管系统那一刻用的是哪张图、
     * 属于哪个代理。alpha 仍然取 {@code ScrimView#getViewAlpha()}，所以面板进出场的
     * 淡入淡出照旧跟着系统走。</p>
     */
    private static void onScrimDrawn(final View view, final Canvas canvas) {
        if (view == null || canvas == null) {
            return;
        }
        if (TileLongPressConfig.bgBlurOff()) {
            // 压掉模糊时这层已经被换成我们的图了，它自己会画，别叠第二遍
            return;
        }
        if (!isFullScreenScrim(view)) {
            return;
        }
        TileBackgroundDrawable ours = sScrimDrawables.get(view);
        if (ours == null) {
            return;   // 这块底板不是我们接管的
        }
        if (currentScrimDrawable(view) == ours) {
            return;   // 这一层就是我们的图在画，别叠第二遍
        }
        // 把系统的动画进度转给我们那张图（它把系统 alpha 和用户可见度分开存）
        ours.setAlpha(viewAlphaOf(view));
        ours.draw(canvas);
        sBlurOverlayHits++;
    }

    /**
     * 「保留模糊」时，把我们的颜色 / 图补画在系统模糊层之上。
     *
     * <p><b>为什么不直接 {@code setDrawable} 换掉那一层</b>：控制中心背景的模糊不是一层独立的
     * 效果，而是系统那张 {@code AutoBlurDrawable} <b>自己画出来的</b>——它的 {@code draw}
     * 画的是 {@code ViewBlurProxy#getBlurDrawable()} 的返回值。一旦把它换成我们的图，
     * 模糊就跟着没了；上一版试过「把系统原图包一层再放回去」，模糊照样出不来：
     * 模糊管线认的是「{@code ScrimView} 当前那张图就是自己造的那张」，
     * 被包一层之后 {@code getBlurDrawable} 内部会静默失败。</p>
     *
     * <p>所以这里反过来：<b>那张图我们完全不碰</b>，只挂它的 {@code draw}，
     * 系统画完模糊之后，在同一张 canvas 上把我们的颜色 / 图叠上去。
     * 这样三种状态下行为一致、都可控：</p>
     * <ul>
     *   <li>开关<b>关</b>（保留模糊）：系统画模糊 → 我们叠颜色 / 图，模糊从半透明处透出来；</li>
     *   <li>开关<b>开</b>（压掉模糊）：那一层根本不会走到这里（
     *       {@link #ensureViewBlurHooks} 把 {@code getBlurDrawable} 的返回值换成了我们的图，
     *       而 {@link #applyToScrimView} 也会直接把 drawable 换掉），这里再兜一道判断；</li>
     *   <li>没设自定义背景：{@link #applyToScrimView} 里就把我们那张图移出去了，这里什么都不画。</li>
     * </ul>
     *
     * <p>淡入淡出要跟系统一致：动画进度是 {@code ScrimView#setViewAlpha} 驱动的，
     * 读出来塞进我们那张图的 {@code setAlpha}（也就是它的「系统 alpha」），
     * 快照 1:1，不会出现「背景比磁贴多留一会儿」。</p>
     */
    private static void ensureBlurDrawHooks(final ClassLoader loader) {
        if (sBlurDrawHooked || loader == null) {
            return;
        }
        synchronized (QsBackgroundHook.class) {
            if (sBlurDrawHooked) {
                return;
            }
            Class<?> blur = XposedHelpers.findClassIfExists(CLASS_AUTO_BLUR_DRAWABLE, loader);
            if (blur == null) {
                XposedBridge.log(TAG + ": " + CLASS_AUTO_BLUR_DRAWABLE + " not found");
                return;
            }
            try {
                XposedHelpers.findAndHookMethod(blur, "draw", Canvas.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Canvas canvas = param.args.length > 0 && param.args[0] instanceof Canvas
                                ? (Canvas) param.args[0] : null;
                        if (sScrimOnDrawHooked) {
                            // 补画已经挂到背景层自己的 onDraw 上了（见 onScrimDrawn），
                            // 这条老路再画一遍就是叠两层，半透明的颜色会明显变浓
                            return;
                        }
                        if (canvas == null || TileLongPressConfig.bgBlurOff()) {
                            // 压掉模糊时这层已经被换成我们的图了，别叠第二遍
                            return;
                        }
                        View scrim = scrimViewOfProxy(
                                XposedHelpers.getObjectField(param.thisObject, "viewBlurProxy"));
                        if (scrim == null) {
                            return;   // 磁贴 / 通知行自己的模糊代理，不归这里管
                        }
                        TileBackgroundDrawable ours = sScrimDrawables.get(scrim);
                        if (ours == null) {
                            return;   // 这一层没设自定义背景
                        }
                        if (currentScrimDrawable(scrim) == ours) {
                            return;   // 这一层就是我们的图在画，别叠第二遍
                        }
                        // 把系统的动画进度转给我们那张图（它把系统 alpha 和用户可见度分开存）
                        ours.setAlpha(viewAlphaOf(scrim));
                        ours.draw(canvas);
                        sBlurOverlayHits++;
                    }
                });
                sBlurDrawHooked = true;
                XposedBridge.log(TAG + ": hooked " + CLASS_AUTO_BLUR_DRAWABLE + "#draw");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + CLASS_AUTO_BLUR_DRAWABLE + " failed - " + t);
            }
        }
    }

    /**
     * 这一层当前被系统驱动到的 alpha（0–255）。
     *
     * <p>看门狗和 {@code applyToScrimView} 都会调它，所以反射一次就缓存
     * （{@code ScrimView#getViewAlpha} 是板上钉钉存在的，反编译确认过）。</p>
     */
    private static int viewAlphaOf(final View view) {
        try {
            float alpha = ((Number) XposedHelpers.callMethod(view, "getViewAlpha")).floatValue();
            if (alpha < 0f) {
                alpha = 0f;
            } else if (alpha > 1f) {
                alpha = 1f;
            }
            return Math.round(alpha * 255f);
        } catch (Throwable t) {
            sViewAlphaMisses++;
            return 255;
        }
    }

    /** 底图已经在系统底板上：把自己以前糊在根视图上的那层擦掉，避免出现两层。 */
    private static void dropRootBackgrounds() {
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            try {
                roots.get(i).setBackground(null);
                roots.get(i).invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 调用 {@code ScrimView#setDrawable}（走缓存的 Method，避免每次反射查找）。 */
    private static void setScrimDrawable(final View view, final Drawable drawable) {
        Method method = sSetDrawableMethod;
        if (method == null) {
            return;
        }
        sSettingScrimDrawable = true;
        try {
            method.invoke(view, new Object[]{drawable});
            view.invalidate();
        } catch (Throwable ignored) {
        } finally {
            sSettingScrimDrawable = false;
        }
    }

    /**
     * 把小尺寸 scrim 自带的图清掉。
     *
     * <p>它盖在底板上面，尺寸又比底板小一圈，留着就会变成「第二层」。
     * 清掉之后那一块直接透出底板的图，整屏就是一张完整的背景。</p>
     *
     * <p>只在尺寸已经量出来（确认它确实小）时才动，避免底板还没量尺寸就被误清。</p>
     */
    private static void blankScrimDrawable(final View view) {
        blankScrimDrawable(view, false);
    }

    /**
     * 清掉这块 scrim 自带的图。
     *
     * @param allowFullScreen 允许清<b>整屏</b>的蒙层（「第二层」用，见 {@link #isVeilScrim}）。
     *        默认只清确实比底板小的那些，避免底板自己还没量出尺寸就被误清。
     */
    private static void blankScrimDrawable(final View view, final boolean allowFullScreen) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        if (!allowFullScreen && isFullScreenScrim(view)) {
            return;
        }
        Drawable current = currentScrimDrawable(view);
        if (current == null || current instanceof ColorDrawable) {
            return;   // 已经是占位图或本来就空，不用重复动
        }
        if (isBlurDrawable(current) && !TileLongPressConfig.isBgBlurOff(view.getContext())) {
            // 「保留模糊」时这一层是系统拿来画模糊的：清掉就等于自己把模糊抹了。
            // 之前「改了色模糊就没了」有一半出在这里——它由看门狗每约 0.5s 无条件清一次。
            sBlurLayersKept++;
            return;
        }
        // 同样不能传 null：ScrimView#onDraw 会直接空指针
        setScrimDrawable(view, blankDrawable());
        sScrimBlankHits++;
    }

    /** scrim 当前挂着的图，用来判断要不要清。 */
    private static Drawable currentScrimDrawable(final View view) {
        Method method = sGetDrawableMethod;
        if (method == null) {
            return null;
        }
        try {
            return (Drawable) method.invoke(view, new Object[0]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前设置（图片 + 颜色 + 可见度）套到系统背景层上。 */
    private static void applyToScrimView(final View view) {
        if (view == null || !isFullScreenScrim(view)) {
            return;
        }
        Context context = view.getContext();
        if (context == null) {
            return;
        }
        int transparency = TileLongPressConfig.bgTransparencyPct(context);
        int alpha = 100 - transparency;
        int color = bgColor(context);
        boolean hasColor = color != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        String key = settingKey(context);
        if (imageKey(context).length() == 0 && !hasColor) {
            // 既没设底图也没设颜色 → 这一层整个交还给系统，让它继续画「壁纸 + 模糊」。
            // 系统的模糊是我们造不出来的，只能用不透明纯色去顶，那反而把背景盖没了
            releaseScrimView(view);
            return;
        }
        sBaseAlphaPct = alpha;
        // 「保留模糊」时系统那张模糊图<b>我们完全不碰</b>，颜色 / 图由
        // AutoBlurDrawable#draw 的钩子补画（见 ensureBlurDrawHooks）；
        // 「压掉模糊」时才是把这一层整个换成我们的图。
        Drawable current = currentScrimDrawable(view);
        boolean wantBlur = !TileLongPressConfig.isBgBlurOff(context);
        if (wantBlur && !isBlurDrawable(current)) {
            // 想让模糊回来，但这一层现在挂的是我们自己的图（上一次是「压掉模糊」）：
            // 先把系统原图还回去。它一回到 ScrimView 上，模糊管线就通了
            Drawable original = sScrimOriginalDrawables.get(view);
            if (isBlurDrawable(original)) {
                setScrimDrawable(view, original);
                current = original;
            }
        }
        boolean keepBlur = wantBlur && isBlurDrawable(current);
        if (alpha <= 0) {
            // 滑杆拉满 = 完全透明：这一层什么都不画（不能传 null，ScrimView#onDraw 会空指针）
            sScrimDrawables.remove(view);
            if (keepBlur) {
                view.invalidate();       // 交还给系统，画钩子里自然就没有我们那层了
            } else if (current != sBlankDrawable) {
                setScrimDrawable(view, blankDrawable());
            }
            sScrimAppliedAlpha = alpha;
            sScrimAppliedKey = key;
            sLastApply = "可见度 " + alpha + "%（不画背景）";
            return;
        }
        // 图片没变时只调可见度：TileBackgroundDrawable 把用户可见度单独存着，
        // 系统再怎么用 setAlpha 刷动画进度都冲不掉它，所以这里就地改一下即可。
        TileBackgroundDrawable existing = sScrimDrawables.get(view);
        // 还得确认画我们那张图的链路<还在>：系统时不时会自己再 setDrawable 一次
        // （refreshBehindDrawable 看到当前不是 AutoBlurDrawable 就重放一张），
        // 这时候只调 alpha 是白调——画面上的图已经换成系统的了，要重新铺上去
        if (existing != null && key.equals(sScrimAppliedKey)
                && (keepBlur ? isBlurDrawable(current) : containsOurDrawable(current))) {
            existing.setUserAlphaPct(alpha);
            view.invalidate();
            sCurrentAlpha = Math.round(alpha * 255f / 100f);
            sScrimAppliedAlpha = alpha;
            sLastApply = "系统背景层（" + scrimNameOf(view) + "）· 可见度已改为 "
                    + alpha + "%";
            return;
        }
        Bitmap bitmap = backgroundBitmap(context);
        // 尺寸强制按屏幕给：系统会给这个 drawable 设一个比屏幕小的 mDrawableBounds，
        // 照它画就会变成「小一圈的一层」，所以这里直接钉死整屏尺寸。
        // 圆角交给系统那层自己处理（它有 setCornerRadius）。
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // 设了颜色就把它垫在图片下面：图片有透明处透出颜色，没设图时看到的就是这块颜色。
        // 没设颜色时沿用原来的 Color.BLACK 占位（有图时它不会被画出来）
        TileBackgroundDrawable drawable = new TileBackgroundDrawable(
                hasColor ? color : Color.BLACK, 0f, bitmap, alpha,
                metrics.widthPixels, metrics.heightPixels, 0f, hasColor);
        String detail = (bitmap != null
                ? "，图片 " + bitmap.getWidth() + "×" + bitmap.getHeight()
                : (hasColor ? "，纯色" : "，图片解码失败"))
                + (hasColor ? "，底色 " + String.format("#%08X", color) : "");
        sScrimDrawables.put(view, drawable);
        sCurrentAlpha = Math.round(alpha * 255f / 100f);
        sScrimAppliedAlpha = alpha;
        sScrimAppliedKey = key;
        // 接管成功后把自己以前糊在根视图上的那层擦掉，免得两层叠着
        dropRootBackgrounds();
        if (keepBlur) {
            // 不换图，只重绘一次：系统画完模糊，ensureBlurDrawHooks 会把我们这张补上去。
            // 换掉 drawable 就等于把模糊一起换掉了——这正是以前「改了色模糊就没了」的根因
            view.invalidate();
            sLastApply = "颜色 / 底图叠在系统模糊上（" + scrimNameOf(view)
                    + "）· 可见度 " + alpha + "%" + detail;
            return;
        }
        sSettingScrimDrawable = true;
        try {
            setScrimDrawable(view, drawable);
            sLastApply = "底图已放进系统背景层（" + scrimNameOf(view)
                    + "）· 可见度 " + alpha + "%"
                    + "· 模糊 压掉" + detail;
        } catch (Throwable t) {
            sLastApply = "设置系统背景层失败：" + t;
        } finally {
            sSettingScrimDrawable = false;
        }
    }

    /**
     * 把背景层交还给系统：把接管前那张系统原图放回去。
     *
     * <p>只在当前挂着的确实不是系统原图时才动，否则 {@code setDrawable} 钩子会
     * 和这里来回打架（放回去 → 钩子再触发 → 再放回去…）。</p>
     */
    private static void releaseScrimView(final View view) {
        if (view == null) {
            return;
        }
        Drawable original = sScrimOriginalDrawables.get(view);
        if (original != null && currentScrimDrawable(view) != original) {
            setScrimDrawable(view, original);
        }
        sScrimDrawables.remove(view);
        sBaseAlphaPct = 100;
        Context context = view.getContext();
        sScrimAppliedAlpha = context == null
                ? 100 : 100 - TileLongPressConfig.bgTransparencyPct(context);
        sScrimAppliedKey = "";
        sLastApply = "未设自定义底图，背景层交还系统（" + scrimNameOf(view) + "）";
    }

    /** 把每一块小尺寸 scrim 自带的图都清掉，保证整屏只有底板那一层。 */
    private static void blankSmallScrims() {
        List<View> views = liveScrimViews();
        for (int i = 0; i < views.size(); i++) {
            blankScrimDrawable(views.get(i));
        }
    }

    /**
     * 逐层列出「当前挂着底图的东西」，用来定位多出来的那一层。
     *
     * <p>每行给出：层类型、名字、<b>View 尺寸</b>、<b>drawable 实际绘制矩形</b>、
     * drawable 是不是我们的、当前 alpha。尺寸和绘制矩形不一致的那一层，就是画出来小一圈的元凶。</p>
     */
    private static String describeLayers() {
        StringBuilder sb = new StringBuilder();
        List<View> scrims = liveScrimViews();
        for (int i = 0; i < scrims.size(); i++) {
            View view = scrims.get(i);
            Drawable drawable = currentScrimDrawable(view);
            sb.append("\n  [scrim] ").append(scrimNameOf(view))
                    .append(' ').append(view.getWidth()).append('×').append(view.getHeight())
                    .append(" · 绘制区 ").append(drawableBoundsOf(view))
                    .append(" · 图=").append(drawable == null
                            ? "null" : drawable.getClass().getSimpleName())
                    .append(ourDrawableInfo(drawable))
                    .append(" · alpha=").append(round(view.getAlpha()));
        }
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            View root = roots.get(i);
            Drawable drawable = root.getBackground();
            sb.append("\n  [root] ").append(root.getClass().getSimpleName())
                    .append(' ').append(root.getWidth()).append('×').append(root.getHeight())
                    .append(" · 图=").append(drawable == null
                            ? "null" : drawable.getClass().getSimpleName())
                    .append(ourDrawableInfo(drawable))
                    .append(" · alpha=").append(round(root.getAlpha()));
            // 整棵树里还挂着我们底图的其它视图：多出来的那一层往往是它
            sb.append(scanForOurDrawable(root, 0));
        }
        return sb.length() == 0 ? "\n  （还没有任何层）" : sb.toString();
    }

    /**
     * 我们的底图额外报一句「用户可见度 / 系统 alpha」。
     *
     * <p>这两个值是分开存的（见 {@link TileBackgroundDrawable}），分开报出来才能一眼看出
     * 可见度滑杆到底有没有生效：{@code 用户} 不变就是没写进去，{@code 系统} 被刷回 255 是正常的。</p>
     */
    private static String ourDrawableInfo(final Drawable drawable) {
        if (!(drawable instanceof TileBackgroundDrawable)) {
            return "";
        }
        TileBackgroundDrawable ours = (TileBackgroundDrawable) drawable;
        return "(我们的 · 用户 " + ours.getUserAlphaPct() + "% / 系统 " + ours.getAlpha() + ")";
    }

    /** 在视图树里找还挂着 {@link TileBackgroundDrawable} 的视图（排除树根自己）。 */
    private static String scanForOurDrawable(final View view, final int depth) {
        if (depth > 12) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!(view instanceof ViewGroup)) {
            return "";
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Drawable background = child.getBackground();
            if (background instanceof TileBackgroundDrawable) {
                sb.append("\n  [树内] ").append(child.getClass().getSimpleName())
                        .append(' ').append(child.getWidth()).append('×').append(child.getHeight())
                        .append(" · alpha=").append(round(child.getAlpha()))
                        .append(ourDrawableInfo(background));
            }
            sb.append(scanForOurDrawable(child, depth + 1));
        }
        return sb.toString();
    }

    /** scrim 上 drawable 的实际绘制矩形，读不到就返回 "-"。 */
    private static String drawableBoundsOf(final View view) {
        try {
            Object bounds = XposedHelpers.callMethod(view, "getDrawableBounds");
            if (bounds instanceof Rect) {
                Rect rect = (Rect) bounds;
                return rect.width() + "×" + rect.height();
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    /** 已经接管到系统背景层：这时底图完全由系统驱动，不需要我们再同步。 */
    private static boolean scrimLayerActive() {
        for (int i = 0; i < sScrimViews.size(); i++) {
            View view = sScrimViews.get(i).get();
            if (view != null && view.isAttachedToWindow()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 系统改了磁贴列表的 {@code transitionAlpha}：底图用同一个值。
     *
     * <p>列表本身和它的直接子视图（按行拆开的磁贴容器）都算——系统是按元素拆开做动画的，
     * 两种都可能出现。子视图取「最亮的那个」，避免单个磁贴自己的小动画把整块底图带暗。</p>
     */
    private static void onTileTransitionAlpha(final View view, final float value) {
        if (scrimLayerActive()) {
            // 底图已经在系统背景层上，淡出归系统管
            return;
        }
        View tileList = sTileList.get();
        if (tileList == null) {
            return;
        }
        float alpha;
        if (view == tileList) {
            alpha = clampFraction(value);
        } else if (view.getParent() == tileList) {
            alpha = Math.max(clampFraction(value),
                    clampFraction(tileList.getTransitionAlpha()));
        } else {
            return;
        }
        sAlphaHookHits++;
        syncTileAlpha(alpha);
    }

    /**
     * 底图 alpha = 基准 alpha × 磁贴可见度。
     *
     * <p>可见度归零就撤图；面板重新展开时可见度回升，底图也跟着回来。</p>
     */
    private static void syncTileAlpha(final float visibility) {
        sTileVisibility = visibility;
        if (sFading) {
            // 正在线性淡出，以那条动画为准，别把它拉回去
            return;
        }
        if (visibility <= ALPHA_GONE) {
            if (!sHidden) {
                startFadeOut();
            }
            return;
        }
        if (sHidden) {
            sHidden = false;
            applyToAll();
        }
        int target = Math.round(255f * visibility);
        if (Math.abs(target - sCurrentAlpha) >= 1) {
            applyAlphaPx(target);
            sTileSyncHits++;
        }
    }

    /**
     * 挂 {@code ScrimControllerEx#setQsPosition}，把底图绑到系统自己的 QS 蒙层进度上。
     *
     * <p>这是「系统背景层」唯一的进度出口：系统每帧算完蒙层进度就调它一次，
     * 所以只要跟住这个参数，底图的淡入淡出与退场时机就和系统蒙层逐帧一致，
     * 不用再去猜什么时候该撤图。</p>
     *
     * <p>类名可能不在启动期的加载器里，装不上就留待拿到面板实例后用它的加载器再试。</p>
     */
    private static void ensureScrimHooks(final ClassLoader loader) {
        if (sScrimHooked || loader == null) {
            return;
        }
        synchronized (QsBackgroundHook.class) {
            if (sScrimHooked) {
                return;
            }
            Class<?> clazz = XposedHelpers.findClassIfExists(CLASS_SCRIM_CONTROLLER_EX, loader);
            if (clazz == null) {
                return;
            }
            try {
                XposedBridge.hookAllMethods(clazz, METHOD_QS_POSITION, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args.length == 0 || !(param.args[0] instanceof Float)) {
                            return;
                        }
                        // 只跟 QS 面板那一层蒙层；其他面板（通知栏等）不参与
                        if (param.args.length >= 3 && param.args[2] instanceof Enum
                                && !"QS_PANEL".equals(((Enum<?>) param.args[2]).name())) {
                            return;
                        }
                        sScrimHits++;
                        onScrimFraction(((Float) param.args[0]).floatValue());
                    }
                });
                sScrimHooked = true;
                XposedBridge.log(TAG + ": hooked " + CLASS_SCRIM_CONTROLLER_EX
                        + "#" + METHOD_QS_POSITION);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + METHOD_QS_POSITION + " failed - " + t);
            }
        }
    }

    /**
     * 系统蒙层进度变化：底图 alpha 与它同步，进度归零时撤图。
     *
     * <p>展开动画的首帧进度也是 0，此时只是「还看不到」，不能把图撤了，
     * 所以用 {@code isClosing} 区分——真在关闭才撤，否则只把 alpha 压到 0。</p>
     */
    private static void onScrimFraction(final float fraction) {
        float value = clampFraction(fraction);
        float previous = sLastScrimFraction;
        sLastScrimFraction = value;
        if (sUpdating || sFading) {
            // 正在退场淡出时以我们自己的线性动画为准，不再被系统进度拉回去
            return;
        }
        boolean closing = isClosing(component());
        if (value < previous - 0.02f && value < 0.99f) {
            // 系统蒙层开始变淡 = 面板正在收起。不跟着它慢慢淡（它比面板多留 300ms），
            // 立刻切到自己的快速线性淡出
            startFadeOut();
            return;
        }
        sUpdating = true;
        try {
            if (value <= ALPHA_GONE) {
                if (closing) {
                    // 系统蒙层已经淡完，底图跟着收尾
                    startFadeOut();
                } else {
                    applyAlpha(0f);
                }
                return;
            }
            if (sHidden && !closing) {
                // 面板重新展开：按当前进度补回底图，不会先全亮再淡入
                sHidden = false;
                applyToAll();
            }
            applyAlpha(value);
        } finally {
            sUpdating = false;
        }
    }

    /**
     * 面板开始退场：用线性动画把底图可见度逐帧降到 0，归零后再撤下底图。
     *
     * <p>不再直接 {@code detach()}：立刻摘掉会让画面「啪」地一跳，
     * 等系统动画走完又太慢（底图比面板多留一截）。这里按固定时长线性递减，
     * 退场节奏完全可控，也不依赖任何一路系统信号是否命中。</p>
     */
    private static void startFadeOut() {
        if (scrimLayerActive()) {
            // 底图在系统背景层上，淡出完全由系统驱动，这里什么都别做
            return;
        }
        if (sHidden && sCurrentAlpha <= 0) {
            // 已经淡完并撤下，不用每帧再动一次
            return;
        }
        sHidden = true;
        if (sFading) {
            return;
        }
        if (sBaseAlphaPct <= 0 || sCurrentAlpha <= 0) {
            detach();
            return;
        }
        sFading = true;
        sFadeHits++;
        sFadeFromAlpha = sCurrentAlpha;
        sFadeStartAt = SystemClock.uptimeMillis();
        final Handler handler = new Handler(Looper.getMainLooper());
        sFadeHandler = handler;
        sFadeRunnable = new Runnable() {
            @Override
            public void run() {
                if (!sFading) {
                    return;
                }
                long elapsed = SystemClock.uptimeMillis() - sFadeStartAt;
                float progress = elapsed >= FADE_OUT_MS
                        ? 1f : (float) elapsed / (float) FADE_OUT_MS;
                applyAlphaPx(Math.round(sFadeFromAlpha * (1f - progress)));
                if (progress >= 1f) {
                    sFading = false;
                    detach();
                    return;
                }
                handler.postDelayed(this, FADE_FRAME_MS);
            }
        };
        handler.post(sFadeRunnable);
    }

    /** 取消正在进行的淡出（面板又展开了）。 */
    private static void cancelFade() {
        sFading = false;
        Handler handler = sFadeHandler;
        Runnable runnable = sFadeRunnable;
        if (handler != null && runnable != null) {
            handler.removeCallbacks(runnable);
        }
    }

    private static float clampFraction(float value) {
        if (Float.isNaN(value) || value < 0f) {
            return 0f;
        }
        return value > 1f ? 1f : value;
    }

    /**
     * 按系统蒙层进度推进所有根视图上底图的<b>系统 alpha</b>。
     *
     * <p>用户设的可见度已经存在 {@link TileBackgroundDrawable} 里了（画的时候相乘），
     * 所以这里只推进度，不能再乘一遍用户值，否则会被削两次。</p>
     */
    private static void applyAlpha(final float fraction) {
        if (sBaseAlphaPct <= 0) {
            return;
        }
        applyAlphaPx(Math.round(255f * clampFraction(fraction)));
    }

    /** 直接给所有根视图上的底图设一个 0–255 的系统 alpha。 */
    private static void applyAlphaPx(final int alpha) {
        sCurrentAlpha = alpha;
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            Drawable background = roots.get(i).getBackground();
            if (background instanceof TileBackgroundDrawable) {
                background.setAlpha(alpha);
            }
        }
    }

    /** 读组件上的展开比例（0–1）；{@code updateExpandedHeight} 的参数是像素高度，不能用。 */
    private static float expandedFractionOf(Object component) {
        try {
            return XposedHelpers.getFloatField(component, "expandedFraction");
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /**
     * 在面板里找磁贴列表（RecyclerView）。
     *
     * <p>根视图是铺满窗口的，退场时<b>真正往下滑走的是磁贴内容</b>，根视图自己不动、
     * 只是慢慢淡。所以底图的消失时机要跟磁贴的位移对齐，而不是跟根视图的 alpha。</p>
     */
    private static View findTileList(View root) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("RecyclerView")) {
                return child;
            }
            View found = findTileList(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 挂磁贴列表的位移钩子：磁贴一开始往下滑，底图就同步开始淡出。 */
    private static void ensureTileListHooks(final Class<?> clazz) {
        synchronized (sTileListClasses) {
            if (sTileListClasses.contains(clazz)) {
                return;
            }
            sTileListClasses.add(clazz);
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "setTranslationY", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            View view = (View) param.thisObject;
                            if (sTileList.get() != view || param.args.length == 0
                                    || !(param.args[0] instanceof Float)) {
                                return;
                            }
                            float translationY = ((Float) param.args[0]).floatValue();
                            float previous = sLastTileTranslationY;
                            sLastTileTranslationY = translationY;
                            sTileMoveHits++;
                            if (translationY > previous + 0.5f && translationY > 0f
                                    && (isClosing(component()) || sLastFraction >= 0.9f)) {
                                // 磁贴整体下移 = 面板正在收起；此刻就开始淡出，
                                // 不用等 setClosing 那一路状态回调，早半拍的问题就没了
                                startFadeOut();
                            } else if (translationY < previous - 0.5f && sHidden
                                    && !isClosing(component())) {
                                // 磁贴回位 = 面板展开，撤销淡出
                                reattach();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook tile list setTranslationY failed - " + t);
        }
    }

    /**
     * 看门狗：面板建出后每 32ms 看一眼磁贴列表的真实可见度与屏幕位置。
     *
     * <p>钩子能不能命中取决于 ROM 里到底走哪条关闭路径，而磁贴的可见度和位置是<b>结果</b>——
     * 它一开始变淡或下移就说明面板正在收起。这条不依赖任何钩子，底图直接逐帧跟着它走。</p>
     */
    private static void startWatch() {
        if (sWatching) {
            return;
        }
        sWatching = true;
        final Handler handler = new Handler(Looper.getMainLooper());
        sWatchHandler = handler;
        sWatchRunnable = new Runnable() {
            @Override
            public void run() {
                if (!sWatching) {
                    return;
                }
                tick();
                handler.postDelayed(this, WATCH_INTERVAL_MS);
            }
        };
        handler.post(sWatchRunnable);
    }

    /**
     * 视图当前真正参与渲染的 alpha。
     *
     * <p>必须把 {@code transitionAlpha} 也算上：ColorOS 面板的展开/收起动画用的是
     * {@code setTransitionAlpha}（反编译 {@code QSPanelAnimatorManager} 里那几个
     * lambda 读的就是 {@code getTransitionAlpha()}），它只影响绘制、不会改
     * {@code getAlpha()}。之前只读 {@code getAlpha()}，所以系统整块淡掉时
     * 我们这边看到的还是「完全可见」——这就是底图死活不跟着淡的原因。</p>
     */
    private static float viewAlpha(View view) {
        float alpha = view.getAlpha();
        try {
            alpha *= view.getTransitionAlpha();
        } catch (Throwable ignored) {
        }
        return alpha;
    }

    /**
     * 磁贴列表的「实际可见度」：它自己和所有祖先的 alpha 乘积。
     *
     * <p>这就是磁贴在屏幕上看上去的可见度。系统收起面板时不管走的是「整体下移」
     * 还是「逐层淡出」，这个值都会跟着变——直接跟它走，时机一帧都不会差，
     * 也不用去猜是哪一路钩子先命中。</p>
     */
    private static float effectiveAlpha(View view) {
        float alpha = 1f;
        View current = view;
        while (current != null) {
            alpha *= viewAlpha(current);
            if (alpha <= ALPHA_GONE) {
                return 0f;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return alpha;
    }

    /**
     * 磁贴内容的可见度：祖先链 alpha × 最亮的那个磁贴的 alpha。
     *
     * <p>系统也可能是逐个磁贴淡出（{@code QSPanelAnimatorManager} 就是按元素拆开做动画的），
     * 这种情况下列表自己不变，只有子视图在变，所以要额外看一眼子视图。</p>
     */
    private static float contentVisibility(View tileList) {
        float alpha = effectiveAlpha(tileList);
        if (alpha <= ALPHA_GONE) {
            return 0f;
        }
        if (tileList instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) tileList;
            float brightest = 0f;
            for (int i = 0; i < group.getChildCount(); i++) {
                float childAlpha = viewAlpha(group.getChildAt(i));
                if (childAlpha > brightest) {
                    brightest = childAlpha;
                }
            }
            if (group.getChildCount() > 0) {
                alpha *= brightest;
            }
        }
        return alpha;
    }

    /**
     * 每 32ms 同步一次：底图 alpha 直接等于「基准 alpha × 磁贴可见度」。
     *
     * <p>之前是靠各路钩子猜退场时机，实测一条都没命中（底图比磁贴多留 300–600ms）。
     * 这里不再依赖任何信号：磁贴可见度降一点，底图就降一点；可见度归零就撤图。
     * 面板重新展开时可见度回升，底图也跟着回来。</p>
     */
    private static void tick() {
        sWatchTicks++;
        if (scrimLayerActive()) {
            // 底图在系统底板上，淡出归系统管；这里只定期补一次设置/尺寸变化
            if (sWatchTicks % 32 == 0) {
                refreshScrimLayer();
            }
            return;
        }
        View tileList = sTileList.get();
        if (tileList == null || !tileList.isAttachedToWindow()) {
            // 面板可能被整个重建过，每约 0.5s 重新找一次磁贴列表
            if (sWatchTicks % 16 == 0) {
                View root = rootView();
                View found = root == null ? null : findTileList(root);
                if (found != null) {
                    adoptTileList(found, false);
                    tileList = found;
                }
            }
            if (tileList == null || !tileList.isAttachedToWindow()) {
                return;
            }
        }
        float visibility = contentVisibility(tileList);
        float previous = sTileVisibility;
        if (sFading) {
            // 正在线性淡出，以那条动画为准，别把它拉回去
            return;
        }
        if (visibility > previous + 0.005f || visibility < previous - 0.005f
                || visibility < 0.995f || visibility <= ALPHA_GONE) {
            syncTileAlpha(visibility);
            return;
        }
        // 可见度满格且稳定：这时再看位置，面板也可能是整块滑走的
        int top;
        try {
            tileList.getLocationOnScreen(sLocation);
            top = sLocation[1];
        } catch (Throwable t) {
            return;
        }
        int previousTop = sLastTileTop;
        sLastTileTop = top;
        if (previousTop == Integer.MIN_VALUE) {
            sRestingTileTop = top;
            return;
        }
        if (Math.abs(top - previousTop) <= 1) {
            if (!sHidden) {
                sRestingTileTop = top;
            }
            return;
        }
        if (sRestingTileTop == Integer.MIN_VALUE || top <= sRestingTileTop + 3) {
            return;
        }
        // 磁贴整体下移 = 面板正在收起。按「滑走了多少」逐帧压底图 alpha，
        // 滑掉一屏就撤图，节奏和磁贴的下滑完全同步
        float gone = clampFraction((float) (top - sRestingTileTop)
                / Math.max(1, tileList.getHeight()));
        if (gone >= 0.9f) {
            startFadeOut();
            return;
        }
        applyAlphaPx(Math.round(255f * (1f - gone)));
        sTileSyncHits++;
    }

    /** 挂 {@code EmptyAreaGestureProxy$1#onLongPress}，吞掉系统的空白区长按。 */
    private static void hookEmptyAreaLongPress(final LoadPackageParam lpparam) {
        Class<?> listener = XposedHelpers.findClassIfExists(
                CLASS_EMPTY_AREA_LISTENER, lpparam.classLoader);
        if (listener == null) {
            XposedBridge.log(TAG + ": " + CLASS_EMPTY_AREA_LISTENER + " not found");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(listener, "onLongPress", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            sHookHits++;
                            View host = hostViewOf(param.thisObject);
                            if (host == null) {
                                return;
                            }
                            Context ctx = host.getContext();
                            if (ctx == null || !TileLongPressConfig.isBgBlocked(ctx)) {
                                // 关掉屏蔽时放过系统，长按空白恢复弹编辑弹窗
                                return;
                            }
                            // 吞掉系统回调（longClickBlock → showEdit 不会再走）
                            param.setResult(null);

                            // 从宿主列表往上找面板根视图；找不到就退回宿主本身，
                            // 至少让面板能弹出来，背景由 onFinishInflate 那条链路负责
                            View root = findQsRoot(host);
                            if (root == null) {
                                root = host;
                            } else {
                                adoptRoot(root);
                            }
                            QsBackgroundPopup.show(root, ctx);
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + CLASS_EMPTY_AREA_LISTENER + "#onLongPress");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onLongPress failed - " + t);
        }
    }

    /** 启动时尽力而为地挂一次根视图钩子（找不到类也不要紧，面板建出后会按运行时类补挂）。 */
    private static void hookRootInflate(final LoadPackageParam lpparam) {
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                CLASS_QS_ROOT_VIEW, lpparam.classLoader);
        if (rootClass == null) {
            XposedBridge.log(TAG + ": " + CLASS_QS_ROOT_VIEW
                    + " 启动时未找到，改为面板建出后按运行时类安装");
            return;
        }
        ensureRootHooks(rootClass);
    }

    /** 启动时尽力而为地挂一次状态钩子。 */
    private static void hookPanelState(final LoadPackageParam lpparam) {
        Class<?> component = XposedHelpers.findClassIfExists(
                CLASS_QS_ROOT_COMPONENT, lpparam.classLoader);
        if (component == null) {
            XposedBridge.log(TAG + ": " + CLASS_QS_ROOT_COMPONENT
                    + " 启动时未找到，改为面板建出后按运行时类安装");
            return;
        }
        ensureComponentHooks(component);
    }

    // ---------------------------------------------------------- 钩子懒安装

    /**
     * 按面板根视图的<b>运行时类</b>装「视图侧」钩子（每个类只装一次）。
     *
     * <p>不直接用 {@code lpparam.classLoader} 查类：这些 QS 类由插件类加载器加载，
     * 启动阶段查不到的话钩子会静默失败。拿实例的类来装就一定对得上。</p>
     */
    private static void ensureRootHooks(final Class<?> clazz) {
        synchronized (sRootClasses) {
            if (sRootClasses.contains(clazz)) {
                return;
            }
            sRootClasses.add(clazz);
        }
        // 根视图 alpha 跟随展开比例，alpha 归零说明面板已经淡完
        try {
            XposedHelpers.findAndHookMethod(clazz, "setAlpha", float.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object arg = param.args.length > 0 ? param.args[0] : null;
                    if (!(arg instanceof Float)) {
                        return;
                    }
                    float alpha = ((Float) arg).floatValue();
                    float previousAlpha = sLastAlpha;
                    sAlphaHits++;
                    sLastAlpha = alpha;
                    // 根视图从「亮着」开始变淡 = 面板正在收起。这是最早能拿到的信号，
                    // 不能等到 alpha 归零才动，那样底图会比面板多留 300ms
                    if (!sFading && previousAlpha >= 0.9f && alpha < previousAlpha - 0.02f) {
                        startFadeOut();
                        return;
                    }
                    // 兜底：已经在关闭且 alpha 归零
                    if (alpha <= ALPHA_GONE
                            && isClosing(componentOf((View) param.thisObject))) {
                        startFadeOut();
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setAlpha failed - " + t);
        }
        // 根视图被隐藏 = 面板退场
        try {
            XposedHelpers.findAndHookMethod(clazz, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object arg = param.args.length > 0 ? param.args[0] : null;
                    if (!(arg instanceof Integer)) {
                        return;
                    }
                    if (((Integer) arg).intValue() == View.VISIBLE) {
                        reattach();
                    } else {
                        startFadeOut();
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setVisibility failed - " + t);
        }
        // inflate 完成 → 记住根视图并套上底图
        try {
            XposedHelpers.findAndHookMethod(clazz, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    sHidden = false;
                    adoptRoot((View) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onFinishInflate failed - " + t);
        }
        XposedBridge.log(TAG + ": root hooks installed on " + clazz.getName());
    }

    /**
     * 按面板组件的<b>运行时类</b>装「状态侧」钩子（每个类只装一次）。
     *
     * <p>{@code collapseQSPanel*} 是用户主动收起（上滑 / 返回 / 点空白）的最早入口，
     * 一进方法就把底图撤掉，底图因此不会跟着关闭动画慢慢淡出；
     * 其余几路是状态回调与兜底信号，保证任何一条链路退场都能撤干净。</p>
     */
    private static void ensureComponentHooks(final Class<?> clazz) {
        synchronized (sComponentClasses) {
            if (sComponentClasses.contains(clazz)) {
                return;
            }
            sComponentClasses.add(clazz);
        }
        hookBooleanState(clazz, "setClosing", new StateHandler() {
            @Override
            public void onState(boolean value) {
                sClosingHits++;
                if (value) {
                    // 开始关闭：立刻起一段线性淡出
                    startFadeOut();
                } else {
                    reattach();
                }
            }
        });
        hookBooleanState(clazz, "setQsVisible", new StateHandler() {
            @Override
            public void onState(boolean value) {
                sQsVisibleHits++;
                if (value) {
                    reattach();
                } else {
                    startFadeOut();
                }
            }
        });
        final XC_MethodHook collapseHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                sCollapseHits++;
                startFadeOut();
            }
        };
        hookAllNamed(clazz, "collapseQSPanel", collapseHook);
        hookAllNamed(clazz, "collapseQSPanelInner", collapseHook);
        // 展开比例归零同样说明面板已经收完
        try {
            XposedHelpers.findAndHookMethod(clazz, "updateExpandedHeight", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            sFractionHits++;
                            // 参数是像素高度（和 maxPanelHeight 比较），比例要去读字段，
                            // 之前直接拿参数当 0–1 比例判断，导致退场时反复重新套图
                            float fraction = expandedFractionOf(param.thisObject);
                            if (Float.isNaN(fraction)) {
                                return;
                            }
                            float previousFraction = sLastFraction;
                            sLastFraction = fraction;
                            boolean closing = isClosing(param.thisObject);
                            // 从「完全展开」开始收缩 = 面板正在收起，立刻起淡出
                            if (!sFading && previousFraction >= 0.9f
                                    && fraction < previousFraction - 0.02f) {
                                startFadeOut();
                                return;
                            }
                            if (fraction <= ALPHA_GONE) {
                                if (closing) {
                                    startFadeOut();
                                }
                            } else if (!closing && sHidden) {
                                // 展开过程中被误淡出就补回来
                                reattach();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook updateExpandedHeight failed - " + t);
        }
        XposedBridge.log(TAG + ": component hooks installed on " + clazz.getName());
    }

    /** 按方法名挂上所有同名重载，取不到就安静跳过。 */
    private static void hookAllNamed(Class<?> clazz, String name, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(clazz, name, callback);
            XposedBridge.log(TAG + ": hooked " + clazz.getSimpleName() + "#" + name);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + name + " failed - " + t);
        }
    }

    /** 布尔状态入口的统一包装，取不到方法就安静跳过。 */
    private interface StateHandler {
        void onState(boolean value);
    }

    private static void hookBooleanState(Class<?> clazz, final String method,
                                         final StateHandler handler) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // 顺手记住组件实例：拉起模块 App 前要靠它收起控制中心
                    if (param.thisObject != null) {
                        sComponent = new WeakReference<Object>(param.thisObject);
                    }
                    Object arg = param.args[0];
                    if (arg instanceof Boolean) {
                        handler.onState(((Boolean) arg).booleanValue());
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + clazz.getSimpleName() + "#" + method);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + method + " failed - " + t);
        }
    }

    // -------------------------------------------------------------- 面板状态

    /** 面板组件实例，可能为 null（面板还没展开过）。 */
    public static Object component() {
        Object component = sComponent.get();
        if (component != null) {
            return component;
        }
        // 兜底：从面板根视图的 touchHandler 反查外部类（TouchHandler.this$0）
        View root = rootView();
        if (root != null) {
            Object found = componentOf(root);
            if (found != null) {
                sComponent = new WeakReference<Object>(found);
                return found;
            }
        }
        return null;
    }

    /** 根视图 → 触摸处理器 → 面板组件。 */
    private static Object componentOf(View root) {
        try {
            Object handler = XposedHelpers.getObjectField(root, FIELD_TOUCH_HANDLER);
            if (handler == null) {
                return null;
            }
            return XposedHelpers.getObjectField(handler, FIELD_OUTER);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 面板组件是否处于「正在关闭」状态。
     *
     * <p>{@code isClosing} 由 {@code setQsVisible} 同步维护（显示时 false、隐藏时 true），
     * 用它区分「展开动画刚开始」和「关闭动画刚开始」——两者 alpha 都是 0，
     * 只有后者该撤底图。读不到就当作没在关闭，交给其他信号处理。</p>
     */
    private static boolean isClosing(Object component) {
        if (component == null) {
            return false;
        }
        try {
            return XposedHelpers.getBooleanField(component, "isClosing");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 收起控制中心。
     *
     * <p>控制中心是 {@code TYPE_NOTIFICATION_SHADE} 系统窗口，一直盖在普通应用窗口之上。
     * 不收起来就直接 {@code startActivity}，新 Activity 会开在它后面，
     * 用户看到的就是「点了按钮什么都没发生」。这里调用面板组件自己的
     * {@code collapseQSPanel}（系统单击空白处走的就是它）。</p>
     *
     * @return 是否成功发起收起
     */
    public static boolean collapseShade() {
        Object component = component();
        if (component == null) {
            return false;
        }
        try {
            XposedHelpers.callMethod(component, "collapseQSPanel", Boolean.FALSE, "SystemUIHook");
            return true;
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(component, "collapseQSPanel", Boolean.FALSE);
                return true;
            } catch (Throwable t2) {
                XposedBridge.log(TAG + ": collapseQSPanel failed - " + t + " / " + t2);
                return false;
            }
        }
    }

    // -------------------------------------------------------------- 背景套用

    /** 记住面板根视图、按运行时类补挂钩子，并把当前配置的背景套上去。 */
    public static void adoptRoot(final View root) {
        if (root == null) {
            return;
        }
        remember(root);
        ensureRootHooks(root.getClass());
        Object component = componentOf(root);
        if (component != null) {
            sComponent = new WeakReference<Object>(component);
            ensureComponentHooks(component.getClass());
        }
        // QS 类由插件加载器加载，启动期可能查不到 ScrimControllerEx，这里用同一加载器补挂
        ensureScrimHooks(root.getClass().getClassLoader());
        ensureAlphaSyncHooks(root.getClass().getClassLoader());
        ensureScrimViewHooks(root.getClass().getClassLoader());
        ensureViewBlurHooks(root.getClass().getClassLoader());
        ensureBlurDrawHooks(root.getClass().getClassLoader());
        ensureScrimBlurHooks(root.getClass().getClassLoader());
        // 把「压掉背景模糊」开关读进进程内缓存：模糊钩子每帧都跑，不能在那里查 prefs
        TileLongPressConfig.isBgBlurOff(root.getContext());
        // 找到磁贴列表并把淡出时机绑到它的可见度上
        View tileList = findTileList(root);
        if (tileList != null) {
            adoptTileList(tileList, false);
        }
        startWatch();
        if (!sHidden) {
            applyTo(root);
        }
    }

    /**
     * 记住磁贴列表。
     *
     * @param authoritative 是否来自系统动画代理（{@code PullAnimationProxy.recyclerView}）。
     *                      权威实例可以覆盖之前遍历视图树猜出来的那个。
     */
    private static void adoptTileList(final View tileList, final boolean authoritative) {
        if (tileList == null) {
            return;
        }
        if (sTileList.get() == tileList) {
            sTileListFromAnimator = sTileListFromAnimator || authoritative;
            return;
        }
        if (sTileListFromAnimator && !authoritative) {
            // 已经有权威实例了，别被猜出来的覆盖
            return;
        }
        sTileList = new WeakReference<View>(tileList);
        sTileListFromAnimator = authoritative;
        ensureTileListHooks(tileList.getClass());
    }

    /** 把根视图记进跟踪列表（同一个实例只记一次，顺手清掉已被回收的）。 */
    private static void remember(final View root) {
        synchronized (sRoots) {
            for (int i = sRoots.size() - 1; i >= 0; i--) {
                View cached = sRoots.get(i).get();
                if (cached == null) {
                    sRoots.remove(i);
                } else if (cached == root) {
                    return;
                }
            }
            sRoots.add(new WeakReference<View>(root));
        }
    }

    /** 当前还活着的面板根视图，越靠后越新。 */
    private static List<View> liveRoots() {
        List<View> roots = new ArrayList<View>();
        synchronized (sRoots) {
            for (int i = sRoots.size() - 1; i >= 0; i--) {
                View cached = sRoots.get(i).get();
                if (cached == null) {
                    sRoots.remove(i);
                } else {
                    roots.add(cached);
                }
            }
        }
        return roots;
    }

    /** 面板退场完毕：把所有根视图上的底图摘掉。 */
    private static void detach() {
        sHidden = true;
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            clear(roots.get(i));
        }
        sLastApply = "面板退场，底图已线性淡出并撤下（" + roots.size() + " 个根视图）";
    }

    /** 面板重新可见：把配置好的底图套回去。 */
    private static void reattach() {
        cancelFade();
        sHidden = false;
        sLastTileTranslationY = 0f;
        applyToAll();
    }

    private static void applyToAll() {
        if (scrimLayerActive()) {
            List<View> scrims = liveScrimViews();
            for (int i = 0; i < scrims.size(); i++) {
                // 整屏的蒙层当「第二层」清掉，只留底板那一层（见 isVeilScrim）
                if (isVeilScrim(scrims.get(i))) {
                    blankVeilScrim(scrims.get(i));
                    continue;
                }
                applyToScrimView(scrims.get(i));
            }
            return;
        }
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            applyTo(roots.get(i));
        }
    }

    /** 当前还活着的系统背景层。 */
    private static List<View> liveScrimViews() {
        List<View> views = new ArrayList<View>();
        for (int i = sScrimViews.size() - 1; i >= 0; i--) {
            View view = sScrimViews.get(i).get();
            if (view == null) {
                sScrimViews.remove(i);
            } else {
                views.add(view);
            }
        }
        return views;
    }

    private static void clear(View root) {
        sCurrentAlpha = 0;
        try {
            root.setBackground(null);
            root.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** 把当前配置的背景套到控制中心面板根视图上；改完可见度后调用即可即时生效。 */
    public static void applyTo(final View root) {
        if (root == null || sHidden) {
            return;
        }
        if (scrimLayerActive()) {
            // 底图已经在系统背景层上，不用自己再铺一层
            return;
        }
        try {
            Context ctx = root.getContext();
            if (ctx == null) {
                return;
            }
            int transparency = TileLongPressConfig.bgTransparencyPct(ctx);
            // 可见度 0%（滑杆最左）= 完全透明 → alpha 0 → 不遮挡，等于恢复系统原样
            int alpha = 100 - transparency;
            if (alpha <= 0 || !hasCustomBackground(ctx)) {
                // 底图与纯色都没设时同样什么都不铺，让系统自己的背景层露出来
                sBaseAlphaPct = 0;
                clear(root);
                sLastApply = hasCustomBackground(ctx)
                        ? "可见度 " + alpha + "%（不遮挡，保持系统原样）"
                        : "未设自定义背景（保持系统原样）";
                return;
            }
            Bitmap bitmap = backgroundBitmap(ctx);
            int color = bgColor(ctx);
            boolean hasColor = color != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
            // 用户可见度存进 drawable，系统进度走 setAlpha：两者在 draw 时相乘，
            // 套用当下就把进度对齐，避免先全亮再淡入
            sBaseAlphaPct = alpha;
            int systemAlpha = Math.round(255f * clampFraction(sLastScrimFraction));
            root.setBackground(new TileBackgroundDrawable(
                    hasColor ? color : Color.BLACK, 0f, bitmap, alpha, 0, 0, 0f, hasColor));
            applyAlphaPx(systemAlpha);
            root.invalidate();
            sLastApply = "可见度 " + alpha + "% → 底图 alpha "
                    + Math.round(alpha * systemAlpha / 255f) + "%"
                    + (bitmap != null
                    ? "，图片 " + bitmap.getWidth() + "×" + bitmap.getHeight()
                    : (hasColor ? "，纯色" : "，图片解码失败"))
                    + (hasColor ? "，底色 " + String.format("#%08X", color) : "");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": apply failed - " + t);
        }
    }

    /** 用当前配置刷新所有根视图，返回刷新的视图数（0 表示面板还没建出来）。 */
    public static int refresh() {
        cancelFade();
        sHidden = false;
        List<View> scrims = liveScrimViews();
        if (!scrims.isEmpty()) {
            // 底图在系统背景层上：只更新那一层，自己那套淡出逻辑整个不参与
            for (int i = 0; i < scrims.size(); i++) {
                applyToScrimView(scrims.get(i));
            }
            return scrims.size();
        }
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            applyTo(roots.get(i));
        }
        return roots.size();
    }

    /**
     * 当前最该被操作的控制中心根视图：优先挑正在显示的那个，
     * 都不可见时退回最新建出来的那个；面板还没建过则为 null。
     */
    public static View rootView() {
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            View root = roots.get(i);
            if (root.getVisibility() == View.VISIBLE && root.isAttachedToWindow()) {
                return root;
            }
        }
        return roots.isEmpty() ? null : roots.get(0);
    }

    /**
     * 解码自定义背景图（Base64 JPEG，由模块设置页通过 {@link TileImageChannel} 送进来）。
     * 按 Base64 串做身份缓存，避免每次刷新都解码一遍。
     */
    private static Bitmap backgroundBitmap(Context ctx) {
        String base64 = TileLongPressConfig.shapeBgImage(ctx, PrefKeys.SHAPE_QS_BACKGROUND);
        if (base64 == null || base64.length() == 0) {
            sBitmapKey = "";
            sBitmap = null;
            return null;
        }
        if (base64.equals(sBitmapKey) && sBitmap != null && !sBitmap.isRecycled()) {
            return sBitmap;
        }
        try {
            byte[] raw = Base64.decode(base64, Base64.DEFAULT);
            Bitmap decoded = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (decoded == null) {
                XposedBridge.log(TAG + ": background image decode failed, "
                        + raw.length + "B");
                return null;
            }
            sBitmapKey = base64;
            sBitmap = decoded;
            XposedBridge.log(TAG + ": background image decoded "
                    + decoded.getWidth() + "x" + decoded.getHeight());
            return decoded;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": background image decode error - " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 取值

    /** 从手势监听器上取宿主 ViewGroup。 */
    private static View hostViewOf(Object listener) {
        if (listener == null) {
            return null;
        }
        try {
            Object value = XposedHelpers.getObjectField(listener, FIELD_HOST_VIEW_GROUP);
            return value instanceof View ? (View) value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿视图树向上找控制中心面板根视图。 */
    private static View findQsRoot(View view) {
        View current = view;
        while (current != null) {
            if (isQsRoot(current)) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** 类名（含父类）是否为面板根视图，避免漏掉子类。 */
    private static boolean isQsRoot(View view) {
        Class<?> clazz = view.getClass();
        while (clazz != null) {
            if (CLASS_QS_ROOT_VIEW.equals(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /** 随便找一个能读配置的 Context（背景层优先，其次根视图），读不到返回 null。 */
    private static Context anyContext() {
        List<View> scrims = liveScrimViews();
        for (int i = 0; i < scrims.size(); i++) {
            if (scrims.get(i).getContext() != null) {
                return scrims.get(i).getContext();
            }
        }
        List<View> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).getContext() != null) {
                return roots.get(i).getContext();
            }
        }
        return null;
    }

    /** 面板里的诊断串。 */
    public static String probe() {
        List<View> roots = liveRoots();
        StringBuilder sb = new StringBuilder();
        sb.append("长按命中 ").append(sHookHits).append(" 次 · 根视图 ")
                .append(roots.isEmpty() ? "未建出" : roots.size() + " 个")
                .append(sHidden ? " · 面板已关闭" : "");
        sb.append("\n退场信号：收起 ").append(sCollapseHits)
                .append(" · setClosing ").append(sClosingHits)
                .append(" · setQsVisible ").append(sQsVisibleHits)
                .append(" · setAlpha ").append(sAlphaHits)
                .append(" · 展开比例 ").append(sFractionHits);
        sb.append("\n系统背景层（scrim）")
                .append(sScrimHooked ? "已挂钩" : "未挂钩")
                .append(" · 命中 ").append(sScrimHits)
                .append(" 次 · 当前进度 ").append(round(sLastScrimFraction))
                .append(sScrimHits > 0 ? "（底图跟着系统蒙层走）" : "（退回兜底信号）");
        sb.append("\n退场淡出 ").append(sFadeHits).append(" 次")
                .append(sFading ? " · 正在淡出" : "")
                .append(" · 当前系统 alpha ").append(sCurrentAlpha)
                .append(" · 用户可见度 ").append(sBaseAlphaPct).append("%");
        sb.append("\n磁贴绑定 ").append(sTileMoveHits).append(" 次位移")
                .append(" · 当前 translationY ").append(round(sLastTileTranslationY))
                .append(sTileMoveHits > 0 ? "（淡出时机跟着磁贴）" : "（未拿到磁贴位移）");
        sb.append("\n看门狗 ").append(sWatchTicks).append(" 次")
                .append(" · 磁贴 top ").append(sLastTileTop == Integer.MIN_VALUE
                ? "-" : String.valueOf(sLastTileTop))
                .append(" · 基准 ").append(sRestingTileTop == Integer.MIN_VALUE
                ? "-" : String.valueOf(sRestingTileTop));
        sb.append("\n磁贴可见度 ").append(round(sTileVisibility))
                .append(" · 逐帧同步 ").append(sTileSyncHits).append(" 次")
                .append(" · 列表 ").append(tileListFound()
                        ? (sTileListFromAnimator ? "已定位（动画代理）" : "已定位（遍历）")
                        : "未找到");
        sb.append("\n系统背景层 ").append(scrimLayerActive() ? "已接管" : "未接管")
                .append(" · ").append(sScrimNames)
                .append(" · 系统放图 ").append(sScrimDrawableHits).append(" 次")
                .append(" · 清掉小层 ").append(sScrimBlankHits).append(" 次")
                .append(" · 护住模糊层 ").append(sBlurLayersKept).append(" 次")
                .append(" · 模糊上补画 ").append(sBlurOverlayHits).append(" 次")
                .append(sScrimOnDrawHooked ? "（挂在背景层视图上）" : "（挂在模糊图上，未装视图路径）")
                .append(sViewAlphaMisses > 0 ? "（读不到 viewAlpha " + sViewAlphaMisses + " 次）" : "")
                .append(scrimLayerActive() ? "（底图归系统驱动，无需同步）" : "");
        sb.append("\n图层清单：").append(describeLayers());
        sb.append("\n动画同步 ").append(sAlphaHooked ? "已挂钩" : "未挂钩")
                .append(" · 系统改磁贴 alpha ").append(sAlphaHookHits).append(" 次")
                .append(sAlphaHookHits > 0 ? "（底图与系统动画逐帧同值）" : "（尚未触发）");
        sb.append("\n最后 alpha ").append(round(sLastAlpha))
                .append(" · 展开比例 ").append(round(sLastFraction));
        sb.append("\n").append(sLastApply);
        return sb.toString();
    }

    private static String round(float value) {
        return String.valueOf(Math.round(value * 100f) / 100f);
    }

    /** 磁贴列表是否已经拿到（拿不到就只剩兜底信号，底图会慢半拍）。 */
    private static boolean tileListFound() {
        View tileList = sTileList.get();
        return tileList != null && tileList.isAttachedToWindow();
    }
}
