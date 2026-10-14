package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 亮度 / 音量竖向胶囊（1 列 × 2 行）的<b>长按</b>与<b>底图替换</b>。
 *
 * <p>反编译 {@code classes*.dex} 得到的结构（布局
 * {@code res/layout/oplus_qs_brightness_layout.xml} /
 * {@code oplus_qs_volume_layout.xml}，两者根节点都是同一个类）：</p>
 * <pre>
 * OplusQsToggleSliderLayout (extends OplusQsBaseToggleSliderLayout extends FrameLayout)
 *   ├─ slider      : OplusQsVerticalSeekBar (extends COUIVerticalSeekBar)   ← 胶囊本体，两层都在这画
 *   └─ toggleView  : 太阳 / 喇叭图标
 * </pre>
 *
 * <p><b>两层都在 {@code onDraw} 里画</b>，没有 {@code View#setBackground} 这个落点：</p>
 * <ol>
 *   <li>背景层：{@code COUIVerticalSeekBar.onDraw} 按 {@code mBackgroundRect} +
 *       {@code mCurBackgroundRadius} 画一个深色圆角胶囊
 *       （{@code mInactiveTrackDrawable} 或 {@code mBackgroundPaint}）；</li>
 *   <li>白色层：紧接着 {@code drawActiveTrack} 按 {@code mProgressRect} +
 *       {@code mCurProgressRadius} 画进度填充。</li>
 * </ol>
 *
 * <p>所以底图的做法是<b>替换</b>而不是覆盖：{@code onDraw} 之前先把系统那层深色胶囊
 * （{@code mInactiveTrackDrawable} / {@code mBackgroundColor}，混色主题下还有
 * {@code BlurDrawable}）压掉，{@code onDraw} 之后再补上底图，并把白色进度区从裁剪路径里
 * 挖掉。压掉这一步是必须的——只「盖」不「换」的话，可见度一拉低，底下那块深色胶囊
 * 就会透出来，看起来像底图没把背景换掉。磁贴那边是直接 {@code setBackground} 换掉
 * 系统底图的，所以没有这个现象。</p>
 *
 * <p><b>长按</b>：系统从来没给这两个滑杆设过长按监听
 * （{@code OplusQsBaseToggleSliderLayout#setLongClickListener} 全工程没有任何调用点），
 * 但触摸链路是通的——{@code OplusQsToggleSliderTouchExt.dispatchTouchEvent} 会把
 * 每个事件都转给 {@code onTouchEvent(...)}，{@code CheckForLongPress.run()} 超时后
 * 会调 {@code internalLongClickProxy.onLongClick(view)}，里面转手就调
 * {@code longClickListener}。</p>
 *
 * <p>不过实测只靠系统那条链路弹不出面板（监听设上了也没反应），所以这里<b>自己计时</b>：
 * 在 {@code onTouchEvent} 上按 ACTION_DOWN 起一个 {@code View#postDelayed}，
 * UP / CANCEL / 移动超过 slop 就撤掉，到点直接弹面板。这条路径不依赖
 * {@code CheckForLongPress} 内部那几个前置判断，最稳。</p>
 *
 * <p>系统那条链路<b>照样把监听设上</b>：它的返回值会让 {@code CheckForLongPress} 记下
 * {@code hasPerformedLongPress}，抬手时就不会再补一次点击——胶囊上那个太阳 / 喇叭图标
 * 是 clickable 的，不设的话长按完还会顺带把亮度 / 音量开关切一下。两条路径都可能到点，
 * 用时间戳去重，保证一个手势只弹一次。开关在 ★ 弹窗里，跟其它形状排在一起
 * （{@code TileLongPressConfig#isBlocked(SHAPE_SLIDER)}），默认开、关掉就不接管长按。</p>
 */
public final class SliderHook {

    private static final String TAG = "SystemUIHook/Slider";

    /** 胶囊容器（亮度 / 音量两个布局的根节点都是它）。 */
    private static final String CLASS_SLIDER_LAYOUT =
            "com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout";
    /** 胶囊本体：两层都在它的 {@code onDraw} 里画。 */
    private static final String CLASS_SLIDER_BAR =
            "com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar";
    /** 触摸扩展：长按计时与回调都在它身上（{@code longClickListener} 字段）。 */
    private static final String CLASS_TOUCH_EXT =
            "com.oplus.systemui.qs.base.seek.OplusQsToggleSliderTouchExt";
    /** 胶囊容器里指向触摸扩展的字段名。 */
    private static final String FIELD_TOUCH_EXT = "touchExt";

    /** 系统几何参数只打一次日志（{@code onDraw} 每帧都走，不能每次都写）。 */
    private static boolean sLoggedGeometry;

    /** 画过的胶囊（弱键：面板收起后自动不在里面），刷新时逐个重画。 */
    private static final Set<View> sViews =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** 只打一次的日志（{@code onDraw} 每帧都会走到，不能每次都写）。 */
    private static final Set<String> sLogged = new HashSet<String>();

    /**
     * 裁剪用的临时对象。
     *
     * <p>{@code onDraw} 只在主线程跑，共用一份即可；每帧新建 Path 会在拖动进度条时
     * 每帧产生垃圾。</p>
     */
    private static final Path sCapsule = new Path();
    private static final Path sProgress = new Path();
    /**
     * 进度区那层的胶囊轮廓（照系统 {@code mClipProgressPath} 的几何）。
     *
     * <p>和 {@link #sCapsule} 不是同一个矩形：底图那层的高度取 {@code mBackgroundRect}
     * （整根胶囊），而系统裁剪进度用的 {@code mClipProgressPath} 是照
     * {@code mClipProgressRect} 四条边画的，这里必须用后者，进度区上下沿才对得上。</p>
     */
    private static final Path sCapsuleFull = new Path();
    /** 胶囊轮廓的边界（{@link #buildCapsulePath} 里算好，底图贴图时当 bounds 用）。 */
    private static final RectF sCapsuleBounds = new RectF();

    /**
     * 本帧的底图（{@code before} 里读一次，{@code after} 里直接用）。
     *
     * <p>空串表示这一帧没有底图，此时胶囊铺 {@link #capsuleColor}（系统自己那块底色），
     * 是否接管由 {@link #sTakeOver} 决定。</p>
     */
    private static String sBase64 = "";

    /**
     * 本帧的<b>进度底图</b>（{@code before} 里读一次，{@code after} 里直接用）。
     *
     * <p>进度块是系统在 {@code drawActiveTrack} 里直接画的、没有 drawable 可以换，
     * 所以这张图只能自己再画一层盖在进度区上，见 {@link #drawProgressImage}。
     * 空串表示没设，此时进度那块原样露系统画的颜色。</p>
     */
    private static String sProgressBase64 = "";

    /**
     * 这一帧要不要把<b>系统画的那条进度</b>也压掉（设了进度底图时为 true）。
     *
     * <p>由 {@code onDraw} 的 before 钩子置位、after 钩子里复位；
     * {@link #SUPPRESS_ACTIVE_TRACK} 在那个窗口内读它。</p>
     */
    private static boolean sSuppressProgress;

    /**
     * 压掉系统深色背景层时保存的现场。
     *
     * <p>{@code onDraw} 只在主线程跑，静态字段够用，不必按 view 存 map。</p>
     */
    private static Object sSavedDrawable;
    private static int sSavedColor;
    private static boolean sSavedMix;
    private static boolean sSuppressed;
    /**
     * 这一帧被我们改过的进度半径；{@code -1} 表示没接管（{@code 0} 是「直角」这个有效值）。
     */
    private static float sSavedProgressRadius = -1f;

    /**
     * 这一帧要不要整个接管胶囊背景。
     *
     * <p>贴了底图、用户调过胶囊的圆角、动过可见度，这三种情况都接管。接管时系统那层
     * 深色胶囊被压掉，改由我们铺一层：有图片用图片，没图片就用颜色
     * （{@link #capsuleColor}）——这样「只改圆角 / 可见度、不贴图」也能生效。</p>
     */
    private static boolean sTakeOver;

    /**
     * 补上去的长按监听。
     *
     * <p>真正的触发靠自建计时器（见 {@link #LONG_PRESS}），这里的返回值主要是为了让
     * 系统的 {@code CheckForLongPress} 记下 {@code hasPerformedLongPress}，
     * 免得抬手时又补一次点击（胶囊上的太阳 / 喇叭图标是 clickable 的）。
     * 开关关掉时返回 false，把系统原本那套原样还回去。</p>
     */
    private static final View.OnLongClickListener LONG_CLICK = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View view) {
            return fireLongPress(view);
        }
    };

    /** 自建计时器按下的那颗胶囊（弱引用：面板收起后自动不在里面）。 */
    private static WeakReference<View> sPressed;
    /** 按下时的屏幕坐标，用来判断手指有没有滑走。 */
    private static float sDownX;
    private static float sDownY;
    /** 上一次真正弹出面板的时刻，给系统链路和自建计时器去重用。 */
    private static long sLastLongPressMs;
    /** 同一手势里两条路径几乎同时到点，这个窗口内的第二次触发直接丢掉。 */
    private static final long LONG_PRESS_DEDUPE_MS = 500L;

    /** 长按到点：弹出胶囊的样式面板。 */
    private static final Runnable LONG_PRESS = new Runnable() {
        @Override
        public void run() {
            View view = sPressed == null ? null : sPressed.get();
            if (view == null || view.getParent() == null) {
                return;
            }
            fireLongPress(view);
        }
    };

    /**
     * 弹出胶囊样式面板。
     *
     * <p>系统链路和自建计时器都会到点，时间戳去重保证一个手势只弹一次。</p>
     *
     * @return 是否接管了这次长按。★ 弹窗里把「亮度 / 音量胶囊」那一项关掉后返回 false，
     *         长按不接管（这颗胶囊系统本来就没有长按行为，等于回到装模块前）
     */
    private static boolean fireLongPress(final View view) {
        Context ctx = view.getContext();
        if (ctx == null
                || !TileLongPressConfig.isBlocked(ctx, TileLongPressConfig.SHAPE_SLIDER)) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - sLastLongPressMs < LONG_PRESS_DEDUPE_MS) {
            return false;
        }
        sLastLongPressMs = now;
        boolean shown = TileStylePopup.show(view, TileLongPressConfig.SHAPE_SLIDER, true, null);
        XposedBridge.log(TAG + ": long press, panel=" + shown);
        return true;
    }

    private SliderHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        SmoothCorners.install(lpparam.classLoader);
        int press = hookLongPress(lpparam);
        int touch = hookTouchTrack(lpparam);
        int background = hookBackground(lpparam);
        int track = hookActiveTrack(lpparam);
        XposedBridge.log(TAG + ": installed (longPress=" + press
                + ", touch=" + touch + ", background=" + background
                + ", activeTrack=" + track + ")");
    }

    /**
     * 压掉系统画的那条进度（设了进度底图时）。
     *
     * <p>进度块是 {@code COUIVerticalSeekBar#drawActiveTrack} 里
     * {@code mProgressPaint} 直接画的，没有 drawable 可以换。不压掉的话自定义进度底图
     * 只是「盖」在上面：图一带透明度、或者可见度没拉满，底下系统那条进度色就透出来了，
     * 看起来就是「进度条没被替换掉」。</p>
     *
     * <p>按<b>方法名</b>在整条继承链上找（{@code OplusQsVerticalSeekBar} 往上到
     * {@code COUIVerticalSeekBar}）：那个方法不在 SystemUI 的 dex 里由我们直接引用，
     * 签名也不好猜，按名字把重载全挂上最稳；一个都没挂到会记进日志。</p>
     */
    private static int hookActiveTrack(final LoadPackageParam lpparam) {
        Class<?> clazz = XposedHelpers.findClassIfExists(CLASS_SLIDER_BAR, lpparam.classLoader);
        if (clazz == null) {
            XposedBridge.log(TAG + ": " + CLASS_SLIDER_BAR + " not found, progress not suppressed");
            return 0;
        }
        int count = 0;
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method method : methods) {
                if (!METHOD_DRAW_ACTIVE_TRACK.equals(method.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, SUPPRESS_ACTIVE_TRACK);
                    count++;
                } catch (Throwable t) {
                    logOnce("hook " + method + " failed - " + t);
                }
            }
        }
        if (count == 0) {
            XposedBridge.log(TAG + ": " + METHOD_DRAW_ACTIVE_TRACK
                    + " not found on the slider chain, progress not suppressed");
        }
        sActiveTrackHooks = count;
        return count;
    }

    /** {@code drawActiveTrack} 挂上了几个（样式面板显示，0 表示进度压不掉）。 */
    private static volatile int sActiveTrackHooks;

    /** 进度压制侧的运行状态（样式面板显示）。 */
    public static String suppressProbe() {
        return sActiveTrackHooks == 0
                ? "未挂上 drawActiveTrack（进度压不掉）" : "已挂 " + sActiveTrackHooks + " 个方法";
    }

    /** 进度绘制的方法名（见 {@link #hookActiveTrack}）。 */
    private static final String METHOD_DRAW_ACTIVE_TRACK = "drawActiveTrack";

    /**
     * 设了进度底图的那一帧直接跳过系统的进度绘制。
     *
     * <p>{@link #sSuppressProgress} 由 {@code onDraw} 的 before 钩子按当帧配置置位——
     * 系统画进度就在那一帧里，静态标记够用。</p>
     */
    private static final XC_MethodHook SUPPRESS_ACTIVE_TRACK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (sSuppressProgress) {
                param.setResult(null);
            }
        }
    };

    // ------------------------------------------------------------------ 长按

    /**
     * 自己按触摸事件计时，不依赖系统那条长按链路。
     *
     * <p>{@code OplusQsToggleSliderTouchExt.dispatchTouchEvent} 会把 DOWN / MOVE /
     * UP / CANCEL 全部转给 {@code onTouchEvent(...)}，所以挂在这上面就能拿到完整手势：
     * DOWN 起计时，UP / CANCEL / 滑走撤掉，到点弹面板。</p>
     */
    private static int hookTouchTrack(final LoadPackageParam lpparam) {
        try {
            Class<?> touchExt = XposedHelpers.findClass(CLASS_TOUCH_EXT, lpparam.classLoader);
            XposedBridge.hookAllMethods(touchExt, "onTouchEvent", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        trackTouch(param);
                    } catch (Throwable t) {
                        logOnce("track touch failed - " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook touch track failed - " + t);
            return 0;
        }
    }

    private static void trackTouch(final XC_MethodHook.MethodHookParam param) {
        if (param.args.length < 2 || !(param.args[0] instanceof View)
                || !(param.args[1] instanceof MotionEvent)) {
            return;
        }
        final View target = (View) param.args[0];
        MotionEvent event = (MotionEvent) param.args[1];
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sPressed = new WeakReference<View>(target);
                sDownX = event.getRawX();
                sDownY = event.getRawY();
                target.removeCallbacks(LONG_PRESS);
                target.postDelayed(LONG_PRESS, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                // 拖动进度条（调亮度 / 音量）不该弹面板，滑过阈值就撤掉计时
                int slop = ViewConfiguration.get(target.getContext()).getScaledTouchSlop();
                if (Math.abs(event.getRawX() - sDownX) > slop
                        || Math.abs(event.getRawY() - sDownY) > slop) {
                    target.removeCallbacks(LONG_PRESS);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                target.removeCallbacks(LONG_PRESS);
                sPressed = null;
                break;
            default:
                break;
        }
    }

    /**
     * 给系统那条长按链路补上监听。
     *
     * <p>触发不靠它（靠 {@link #hookTouchTrack}），它在这里的作用是让
     * {@code CheckForLongPress} 记下 {@code hasPerformedLongPress}，抬手时不再补一次点击。</p>
     *
     * <p>挂点是构造函数而不是 {@code onAttachedToWindow}：监听只存在 {@code touchExt} 的
     * 一个字段里，构造完就能写；挂 attach 要等视图挂上窗口那一刻，中间任何一环出问题都
     * 静默失效。而 {@code onAttach()} 也不会碰这个字段，先写进去就不会被覆盖。</p>
     */
    private static int hookLongPress(final LoadPackageParam lpparam) {
        try {
            final Class<?> layout = XposedHelpers.findClass(CLASS_SLIDER_LAYOUT,
                    lpparam.classLoader);
            final Class<?> touchExt = XposedHelpers.findClass(CLASS_TOUCH_EXT,
                    lpparam.classLoader);
            // 直接取方法引用而不是 XposedHelpers.callMethod：参数是接口类型，
            // 匿名内部类的实际类型跟形参对不上，按声明类型查最稳
            final Method setLongClick = touchExt.getMethod("setOnLongClickListener",
                    View.OnLongClickListener.class);
            // 插件里的类未必是 public，反射调用前先放开访问检查，免得静默失败
            setLongClick.setAccessible(true);

            XposedBridge.hookAllConstructors(layout, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object ext = XposedHelpers.getObjectField(param.thisObject,
                                FIELD_TOUCH_EXT);
                        if (ext == null) {
                            logOnce("touchExt field is null after construct");
                            return;
                        }
                        setLongClick.invoke(ext, LONG_CLICK);
                    } catch (Throwable t) {
                        logOnce("set long click listener failed - " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook long press failed - " + t);
            return 0;
        }
    }

    /**
     * 被按的视图是不是亮度 / 音量胶囊里的一部分。
     *
     * <p>{@link TileLongPressInterceptor} 会在 {@code View.performLongClick} 上按尺寸
     * 兜底认磁贴，这两根胶囊也够大，认不出时它会弹一句诊断提示。胶囊有自己的长按链路
     * （见 {@link #hookLongPress}），这里先把整棵子树摘出去。</p>
     */
    public static boolean isInsideSlider(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 12; depth++) {
            String name = current.getClass().getName();
            if (name.contains("ToggleSliderLayout") || name.contains("OplusQsVerticalSeekBar")) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    // ------------------------------------------------------------------ 底图

    private static int hookBackground(final LoadPackageParam lpparam) {
        try {
            Class<?> bar = XposedHelpers.findClass(CLASS_SLIDER_BAR, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(bar, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) {
                        return;
                    }
                    View view = (View) param.thisObject;
                    remember(view);
                    // 先复位：这一帧没读到配置时也不能留着上一帧的标记，
                    // 否则系统那条进度会被莫名其妙地跳过
                    sSuppressProgress = false;
                    try {
                        Context ctx = view.getContext();
                        if (ctx == null) {
                            return;
                        }
                        sBase64 = readBase64(view);
                        sProgressBase64 = readProgressBase64(view);
                        // 设了进度底图就压掉系统那条进度：这一帧的 drawActiveTrack 直接跳过，
                        // 进度区改由 drawProgressImage 那张图自己填（见 SUPPRESS_ACTIVE_TRACK）
                        sSuppressProgress = sProgressBase64.length() > 0;
                        // 贴了底图、或用户调过胶囊的圆角 / 可见度，这一帧就整个接管胶囊背景
                        sTakeOver = shouldTakeOver(view);
                        suppressSystemLayer(param.thisObject, sTakeOver);
                        // 进度归系统自己画，我们只把它那圈圆角对齐胶囊
                        alignProgressRadius(param.thisObject, view);
                        // 放在压掉系统层之后：诊断日志要读被换下来的那个 drawable
                        logGeometry(view);
                    } catch (Throwable t) {
                        logOnce("suppress failed - " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)
                            || !(param.args[0] instanceof Canvas)) {
                        return;
                    }
                    try {
                        drawCustom((View) param.thisObject, (Canvas) param.args[0]);
                    } catch (Throwable t) {
                        logOnce("draw custom failed - " + t);
                    } finally {
                        // 必须在同一帧还原：这几个字段是系统下一帧、以及别的分支要用的
                        restoreProgressRadius(param.thisObject);
                        restoreSystemLayer(param.thisObject);
                        sSuppressProgress = false;
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook background failed - " + t);
            return 0;
        }
    }

    private static String readBase64(final View view) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return "";
        }
        return TileLongPressConfig.tileBgImage(ctx, TileLongPressConfig.SHAPE_SLIDER, true);
    }

    /** 本帧的进度底图；借用形状位存的那张，没有开 / 关之分，见 {@link PrefKeys#SHAPE_SLIDER_PROGRESS}。 */
    private static String readProgressBase64(final View view) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return "";
        }
        return TileLongPressConfig.shapeBgImage(ctx, PrefKeys.SHAPE_SLIDER_PROGRESS);
    }

    /**
     * 把系统那条进度的圆角对齐胶囊。
     *
     * <p>进度是系统自己画的（{@code drawActiveTrack}：{@code mProgressPaint} 上色 +
     * {@code clipPath(mClipProgressPath)} + 圆角矩形），它读的半径是
     * {@code mCurProgressRadius}。用户给胶囊指定过圆角时，这个字段不改的话进度块的圆角
     * 跟胶囊对不上（胶囊选了直角、进度还是圆的），所以这一帧把它换成同一个值。
     * 颜色、画笔、可见度全归系统，我们一个字都不碰。</p>
     *
     * <p>「跟随系统」不动：系统自己算出来的半径本来就是对的。</p>
     */
    private static void alignProgressRadius(final Object bar, final View view) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_SLIDER);
        int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        if (cornerDp < 0) {
            return;
        }
        try {
            float radiusPx = cornerDp * ctx.getResources().getDisplayMetrics().density;
            sSavedProgressRadius = XposedHelpers.getFloatField(bar, "mCurProgressRadius");
            XposedHelpers.setFloatField(bar, "mCurProgressRadius", radiusPx);
        } catch (Throwable t) {
            logOnce("align progress radius failed - " + t);
            sSavedProgressRadius = -1f;
        }
    }

    private static void restoreProgressRadius(final Object bar) {
        if (sSavedProgressRadius < 0f) {
            return;
        }
        try {
            XposedHelpers.setFloatField(bar, "mCurProgressRadius", sSavedProgressRadius);
        } catch (Throwable t) {
            logOnce("restore progress radius failed - " + t);
        }
        sSavedProgressRadius = -1f;
    }

    /**
     * 把系统那层深色胶囊压掉（只在这一帧的 {@code onDraw} 期间生效）。
     *
     * <p>不压掉的话底图只是「盖」在上面：可见度一拉低，底下那块深色就透出来了，
     * 看起来就像「底图没把背景替换掉」。磁贴那边是直接 {@code setBackground} 换掉
     * 系统底图的，所以没有这个现象；这里对齐磁贴的做法。深色胶囊有两个来源，都要关：</p>
     * <ol>
     *   <li>{@code mInactiveTrackDrawable}（圆角和内缩画在 drawable 自己身上），
     *       没有它时走 {@code mBackgroundPaint} + {@code mBackgroundColor}；</li>
     *   <li>混色分支（{@code isDrawingWithMixColor} 为 true）——那条分支压根不调
     *       {@code super.onDraw}，深色层是 {@code baseMixColorDrawable} 那块模糊，
     *       光清上面两个字段压不住它，所以把开关也临时关掉，让它走标准分支。</li>
     * </ol>
     *
     * @param takeOver 这一帧要不要整个接管胶囊背景（见 {@link #shouldTakeOver}）。
     *                 为 true 时哪怕没贴底图也压掉系统那层，由我们铺「底图颜色」
     */
    private static void suppressSystemLayer(final Object bar, final boolean takeOver) {
        sSuppressed = false;
        if (!takeOver) {
            return;
        }
        sSavedDrawable = XposedHelpers.getObjectField(bar, "mInactiveTrackDrawable");
        sSavedColor = XposedHelpers.getIntField(bar, "mBackgroundColor");
        sSavedMix = XposedHelpers.getBooleanField(bar, "isDrawingWithMixColor");
        XposedHelpers.setObjectField(bar, "mInactiveTrackDrawable", null);
        XposedHelpers.setIntField(bar, "mBackgroundColor", 0);
        XposedHelpers.setBooleanField(bar, "isDrawingWithMixColor", false);
        sSuppressed = true;
    }

    private static void restoreSystemLayer(final Object bar) {
        try {
            if (sSuppressed) {
                sSuppressed = false;
                XposedHelpers.setObjectField(bar, "mInactiveTrackDrawable", sSavedDrawable);
                XposedHelpers.setIntField(bar, "mBackgroundColor", sSavedColor);
                XposedHelpers.setBooleanField(bar, "isDrawingWithMixColor", sSavedMix);
            }
        } catch (Throwable t) {
            logOnce("restore failed - " + t);
        }
    }

    /**
     * 这一帧要不要整个接管胶囊背景。
     *
     * <p>三种情况都接管：贴了底图（用图铺）、用户调过胶囊的圆角（形状归我们画）、
     * 动过可见度（系统那层不压掉，可见度一降就透出深色胶囊）。没贴图时用
     * {@link #capsuleColor} 那块颜色铺，观感和系统自己的胶囊底色一致。</p>
     *
     * <p>三样都没动过时完全不碰，胶囊原样归系统画——等于回到装模块前。</p>
     */
    private static boolean shouldTakeOver(final View view) {
        if (sBase64.length() > 0) {
            return true;
        }
        Context ctx = view.getContext();
        if (ctx == null) {
            return false;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_SLIDER);
        if (style == null) {
            return false;
        }
        return style.cornerDp >= 0
                || style.alphaPct(true) != TileLongPressConfig.DEFAULT_ALPHA_PCT;
    }

    /**
     * 没贴图片时胶囊铺什么色。
     *
     * <p>优先用系统自己那块胶囊底色（{@link #sSavedColor}，压掉之前存的），读不到
     * （混色主题下底色可能就在 {@code BlurDrawable} 里、字段是 0）才退回模块的底图色
     * {@link HookPrefs#tileBackgroundColor()}。</p>
     */
    private static int capsuleColor() {
        return Color.alpha(sSavedColor) > 0
                ? sSavedColor : HookPrefs.tileBackgroundColor();
    }

    /**
     * 把自定义的背景贴到深色胶囊那层上（有底图用底图，没底图用颜色）。
     *
     * <p>跑在系统 {@code onDraw} <b>之后</b>：系统那层深色胶囊已经被
     * {@link #suppressSystemLayer} 压掉了，白色进度已经画完，这里补上底图即可。</p>
     *
     * <p><b>几何不能取 {@code mBackgroundRect}</b>。反编译 {@code COUIVerticalSeekBar}
     * 看到：{@code mBackgroundRect} 是个直角矩形（{@code mCurBackgroundRadius} 常常是 0），
     * 真正看到的圆角胶囊形状和那圈内缩是画在 {@code mInactiveTrackDrawable} 自己身上的。
     * 照 {@code mBackgroundRect} 贴图就会得到一个比胶囊大一圈的直角矩形——可见度一降，
     * 里面的深色胶囊就透出来了。</p>
     *
     * <p>真正和胶囊等宽的几何是 {@code mClipProgressRect}（进度能画到的范围，和深色胶囊
     * 同宽）；圆角走 {@link #sliderRadiusPx}（「跟随系统」时直接吃系统的
     * {@code mCurProgressRadius}），并照系统的「连续圆角」构造路径落笔——
     * 正圆弧和连续圆角在角上永远差一点点，半径怎么调都是一个突一个凹。</p>
     */
    private static void drawCustom(final View view, final Canvas canvas) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return;
        }
        // 既没接管胶囊、也没设进度底图：一帧都不用碰，直接还给系统
        if (!sTakeOver && sProgressBase64.length() == 0) {
            return;
        }
        // 圆角统一走 sliderRadiusPx：跟磁贴那边一个口径；「跟随系统」时直接吃系统自己的半径
        final float radius = sliderRadiusPx(view);
        final float weight = sliderWeight(view);
        // 胶囊几何读不到就什么都不画
        if (!buildCapsulePath(view, radius, weight)) {
            return;
        }
        // 进度区域 =「mProgressRect 那个圆角矩形」∩「胶囊轮廓」。底图把它挖空，
        // 底下系统自己画的进度才露得出来（进度归系统画，见 alignProgressRadius）
        boolean hasProgressRegion = buildProgressRegion(view, radius, weight);

        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_SLIDER);
        // 胶囊底图与进度底图各有各的可见度（样式面板也分两页），互不牵连
        int alphaPct = style != null
                ? style.alphaPct(true) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        int progressAlphaPct = style != null
                ? style.progressAlphaPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;

        if (sTakeOver) {
            // 设了进度底图时这一帧不挖空：进度图多半带透明，挖空后底下就是面板背景，
            // 进度区会透出一块和周围对不上的底。系统那条进度已经被
            // SUPPRESS_ACTIVE_TRACK 压掉了，不挖空也不会再露出系统进度色
            drawCapsuleBackground(canvas, view, radius, weight,
                    hasProgressRegion && !sSuppressProgress, alphaPct);
        }
        // 进度底图跟「接不接管胶囊」无关：只设了这张图时胶囊仍归系统画，
        // 我们只把图盖在进度区上
        if (hasProgressRegion) {
            drawProgressImage(canvas, progressAlphaPct);
        }
    }

    /**
     * 铺胶囊那层底图（有图用图，没图用颜色），并把进度区挖空。
     *
     * <p>跑在系统 {@code onDraw} <b>之后</b>：系统那层深色胶囊已经被
     * {@link #suppressSystemLayer} 压掉了，这里补上底图即可。挖空的那块留给系统画的进度；
     * 设了进度底图的那一帧不挖（{@link #sSuppressProgress}），那块交给
     * {@link #drawProgressImage} 自己填。</p>
     */
    private static void drawCapsuleBackground(final Canvas canvas, final View view,
            final float radius, final float weight, final boolean hasProgressRegion,
            final int alphaPct) {
        // 背景底图：铺满整根胶囊，进度那块挖空——挖空了系统画的进度才露得出来
        if (hasProgressRegion && !sCapsule.op(sProgress, Path.Op.DIFFERENCE)) {
            logOnce("progress subtract failed, drawing full capsule");
            buildCapsulePath(view, radius, weight);
        }
        Bitmap bitmap = TileBackgroundHook.decodeShapeImage(sBase64);
        // 没贴图片时铺颜色（系统自己那块胶囊底色）：底图机制既能贴图也能填色，
        // 所以「只改圆角 / 可见度、不贴图」走的是同一条落笔路径
        // 半径传 0：圆角<b>只</b>由外面那层 clipPath(sCapsule) 负责。drawable 自己再按
        // 正圆弧裁一次的话，会把连续圆角的外圈削掉一条，角上又出现一道缝
        Drawable image = new TileBackgroundDrawable(
                bitmap != null ? Color.TRANSPARENT : capsuleColor(), 0f, bitmap, alphaPct);
        image.setBounds((int) sCapsuleBounds.left, (int) sCapsuleBounds.top,
                (int) sCapsuleBounds.right, (int) sCapsuleBounds.bottom);
        int save = canvas.save();
        try {
            canvas.clipPath(sCapsule);
            image.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * 把「进度底图」画在进度区里。
     *
     * <p>进度块是系统自己画的（{@code drawActiveTrack} 里 {@code mProgressPaint} 上色 +
     * {@code clipPath(mClipProgressPath)}），没有 drawable 可以换，所以想给它贴图只能自己
     * 在它上面再画一层：几何取整根胶囊（跟胶囊底图同一套 bounds 与圆角口径），裁剪到
     * {@link #sProgress}。进度涨到哪图就露到哪——<b>图本身不跟着进度缩放</b>，
     * 拖亮度 / 音量时不会看到图被拉伸。</p>
     *
     * <p>可见度用进度底图自己那一份（{@code progressAlphaPct}），跟胶囊底图的分开。
     * 没设这张图时什么都不做——此时系统那条进度色原样露着
     * （{@link #SUPPRESS_ACTIVE_TRACK} 只在设了图的那一帧生效）。</p>
     */
    private static void drawProgressImage(final Canvas canvas, final int alphaPct) {
        Bitmap bitmap = TileBackgroundHook.decodeShapeImage(sProgressBase64);
        if (bitmap == null) {
            return;
        }
        // 半径同样传 0：圆角由外面那层 clipPath(sProgress) 负责，避免正圆弧削掉连续圆角的外圈
        Drawable image = new TileBackgroundDrawable(Color.TRANSPARENT, 0f, bitmap, alphaPct);
        image.setBounds((int) sCapsuleBounds.left, (int) sCapsuleBounds.top,
                (int) sCapsuleBounds.right, (int) sCapsuleBounds.bottom);
        int save = canvas.save();
        try {
            canvas.clipPath(sProgress);
            image.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * 按底图那套几何把胶囊轮廓装进 {@link #sCapsule}，边界同时记到 {@link #sCapsuleBounds}。
     *
     * <p>几何不能取 {@code mBackgroundRect} 自己：那个是个直角矩形
     * （{@code mCurBackgroundRadius} 常常是 0），真正看到的圆角胶囊形状和那圈内缩是画在
     * {@code mInactiveTrackDrawable} 自己身上的。照 {@code mBackgroundRect} 贴图就会得到
     * 一个比胶囊大一圈的直角矩形——可见度一降，里面的深色胶囊就透出来了。真正和胶囊
     * 等宽的几何是 {@code mClipProgressRect}（进度能画到的范围，和深色胶囊同宽），
     * 高度取 {@code mBackgroundRect}（整根胶囊的高度）。</p>
     *
     * <p>圆角和权重由调用方算好传进来（{@link #sliderRadiusPx} / {@link #sliderWeight}），
     * 走系统同一条「连续圆角」构造路径，角上才能像素级对齐。</p>
     *
     * @return 几何读得到没有（false 时 {@link #sCapsule} 已重置）
     */
    private static boolean buildCapsulePath(final View view, final float radius,
            final float weight) {
        Object rectField = XposedHelpers.getObjectField(view, "mBackgroundRect");
        if (!(rectField instanceof Rect)) {
            sCapsule.reset();
            return false;
        }
        Rect backgroundRect = (Rect) rectField;
        if (backgroundRect.isEmpty()) {
            sCapsule.reset();
            return false;
        }
        float left = backgroundRect.left;
        float top = backgroundRect.top;
        float right = backgroundRect.right;
        float bottom = backgroundRect.bottom;
        Object clipField = XposedHelpers.getObjectField(view, "mClipProgressRect");
        if (clipField instanceof Rect && !((Rect) clipField).isEmpty()) {
            left = ((Rect) clipField).left;
            right = ((Rect) clipField).right;
        }
        sCapsuleBounds.set(left, top, right, bottom);
        // radius < 0 才是「没算出来」（读不到系统半径），兜底按胶囊处理（短边一半），
        // 总比画成直角强；radius == 0 是用户真的选了直角，必须原样落笔成直角矩形
        float corner = radius >= 0f
                ? radius : Math.min(right - left, bottom - top) / 2f;
        SmoothCorners.buildPath(sCapsule, left, top, right, bottom, corner, weight);
        return true;
    }

    /**
     * 把进度区域装进 {@link #sProgress}：「{@code mClipProgressRect} 那个圆角胶囊」
     * ∩「{@code mProgressRect} 那个<b>直角</b>矩形」。
     *
     * <p>几何照系统 {@code drawActiveTrack} 抄：它先
     * {@code clipPath(mClipProgressPath)}（{@code addSmoothRoundRect(mClipProgressRect…)}），
     * 再拿 {@code mProgressRect} 落笔，而<b>不显示滑块</b>时走的是
     * {@code Canvas.drawRoundRect} 之外那条 {@code drawRect} 分支——也就是直角矩形
     * （圆角那两条分支是给滑块本体用的，滑块一藏起来就轮不到）。</p>
     *
     * <p>所以可见的进度区是「胶囊轮廓」裁出来的直角矩形：<b>上沿平直、只有底下两角
     * 跟着胶囊圆</b>。早先这里把 {@code mProgressRect} 自己也当成圆角矩形来画，
     * 上沿的两个直角就变成了圆角——胶囊底图挖出来的洞、进度底图的裁剪形状
     * 都会在顶上多出两个圆角，跟系统原本的样子对不上。</p>
     *
     * @return 有没有装进去（进度为空或字段读不到时为 false，此时 {@link #sCapsule} 仍是完整胶囊）
     */
    private static boolean buildProgressRegion(final View view, final float radius,
            final float weight) {
        Object progressField = XposedHelpers.getObjectField(view, "mProgressRect");
        if (!(progressField instanceof Rect)) {
            return false;
        }
        Rect progressRect = (Rect) progressField;
        if (progressRect.isEmpty()) {
            return false;
        }
        // 胶囊轮廓取 mClipProgressRect 四条边（系统 mClipProgressPath 就是这么画的）；
        // 读不到时退回底图那套边界，总比不画强
        float left = sCapsuleBounds.left;
        float top = sCapsuleBounds.top;
        float right = sCapsuleBounds.right;
        float bottom = sCapsuleBounds.bottom;
        Object clipField = XposedHelpers.getObjectField(view, "mClipProgressRect");
        if (clipField instanceof Rect && !((Rect) clipField).isEmpty()) {
            Rect clip = (Rect) clipField;
            left = clip.left;
            top = clip.top;
            right = clip.right;
            bottom = clip.bottom;
        }
        // radius < 0 是「没算出来」，兜底按胶囊处理；0 是用户真选了直角，必须原样落笔
        float corner = radius >= 0f
                ? radius : Math.min(right - left, bottom - top) / 2f;
        SmoothCorners.buildPath(sCapsuleFull, left, top, right, bottom, corner, weight);
        sProgress.reset();
        sProgress.addRect(progressRect.left, progressRect.top, progressRect.right,
                progressRect.bottom, Path.Direction.CW);
        if (!sProgress.op(sCapsuleFull, Path.Op.INTERSECT)) {
            // 取交失败时退回整块矩形：外面还有一层胶囊裁剪兜着，不会画出胶囊
            logOnce("progress capsule intersect failed");
        }
        return true;
    }

    /**
     * 胶囊这一层用的圆角（像素）。
     *
     * <p>显式设过就用配置值；「跟随系统」<b>直接吃系统自己的 {@code mCurProgressRadius}</b>
     * （读不到才退回 {@link TileLongPressConfig#FOLLOW_SYSTEM_DP}）。系统那个值是
     * {@code getDimension} 出来的像素值、且可能带小数（比如 19.5dp 等效），
     * 我们拿 dp 取整去猜永远差一点点，直接读字段才是像素级对齐。</p>
     *
     * <p>底图与胶囊轮廓都用这个值；进度那块是系统自己画的，它的半径由
     * {@link #alignProgressRadius} 改成同一个值，三处一致圆角才对得齐。</p>
     */
    private static float sliderRadiusPx(final View view) {
        Context ctx = view.getContext();
        if (ctx == null) {
            // -1 = 算不出来，交给 buildCapsulePath 兜底；0 是「直角」这个有效值，不能拿来当哨兵
            return -1f;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_SLIDER);
        int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        if (cornerDp >= 0) {
            return cornerDp * ctx.getResources().getDisplayMetrics().density;
        }
        // 系统的形状是连续圆角画的，正圆弧怎么调都对不齐，所以「跟随系统」直接吃它的半径。
        // 拿不到 OplusPath（没法复刻连续圆角）时退回 20dp 正圆弧：实测那个值离系统的
        // 形状最近，比系统的原始半径用正圆弧画要像
        if (SmoothCorners.available()) {
            float system = readFloat(view, "mCurProgressRadius", 0f);
            if (system <= 0f) {
                system = readFloat(view, "mCurBackgroundRadius", 0f);
            }
            if (system > 0f) {
                return system;
            }
        }
        return TileLongPressConfig.FOLLOW_SYSTEM_DP
                * ctx.getResources().getDisplayMetrics().density;
    }

    /**
     * 胶囊这一层用的「连续圆角」权重（{@code OplusPath#addSmoothRoundRect} 的最后一个参数）。
     *
     * <p>反编译 {@code COUIVerticalSeekBar}：系统只在
     * {@code mIsSupportSmoothRoundCorner && weight != 0} 时才用连续圆角，否则退回正圆弧。
     * 我们跟着同一口径走，两边落笔才完全一样。</p>
     */
    private static float sliderWeight(final View view) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return 0f;
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_SLIDER);
        int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        // 用户自己指定了半径：形状已经不是系统那一个，但圆角风格仍跟系统保持一致
        if (cornerDp < 0 && !readBoolean(view, "mIsSupportSmoothRoundCorner", false)) {
            return 0f;
        }
        float weight = readFloat(view, "mProgressRoundCornerWeight", 0f);
        if (weight == 0f) {
            weight = readFloat(view, "mBackgroundRoundCornerWeight", 0f);
        }
        return weight;
    }

    /**
     * 让所有画过的胶囊重画一遍。
     *
     * <p>底图是 {@code onDraw} 里现读配置的，所以重画就能看到新图，
     * 不用重启 SystemUI。</p>
     *
     * @return 重画的胶囊数量
     */
    public static int refresh() {
        int count = 0;
        synchronized (sViews) {
            for (View view : sViews) {
                if (view != null) {
                    view.invalidate();
                    count++;
                }
            }
        }
        XposedBridge.log(TAG + ": refreshed " + count + " slider capsule(s)");
        return count;
    }

    private static void remember(final View view) {
        synchronized (sViews) {
            sViews.add(view);
        }
    }

    // -------------------------------------------------------------------- 工具

    /**
     * 打一次系统几何参数，用来核对圆角到底对不对得上（没有 adb，只能靠这条日志回读）。
     *
     * <p>{@code onDraw} 每帧都走，所以只打一次；想看最新的值就重启 SystemUI。</p>
     */
    private static void logGeometry(final View view) {
        if (sLoggedGeometry) {
            return;
        }
        sLoggedGeometry = true;
        try {
            XposedBridge.log(TAG + ": geometry smoothAvailable=" + SmoothCorners.available()
                    + " weight=" + SmoothCorners.weight()
                    + " support=" + readBoolean(view, "mIsSupportSmoothRoundCorner", false)
                    + " progressFull=" + readBoolean(view, "mIsProgressFull", false)
                    + " curProgressRadius=" + readFloat(view, "mCurProgressRadius", -1f)
                    + " curBackgroundRadius=" + readFloat(view, "mCurBackgroundRadius", -1f)
                    + " progressWeight=" + readFloat(view, "mProgressRoundCornerWeight", -1f)
                    + " backgroundWeight="
                    + readFloat(view, "mBackgroundRoundCornerWeight", -1f)
                    + " clip=" + rectOf(view, "mClipProgressRect")
                    + " bg=" + rectOf(view, "mBackgroundRect")
                    + " progress=" + rectOf(view, "mProgressRect")
                    + " track=" + describeDrawable(view));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": log geometry failed - " + t);
        }
    }

    /** 被压掉的系统深色胶囊 drawable：类名 + 它自己的圆角 / 权重（拿不到写 -1）。 */
    private static String describeDrawable(final View view) {
        Object drawable = sSavedDrawable;
        if (!sSuppressed) {
            // 这一帧没压（没贴底图）：直接读字段
            try {
                drawable = XposedHelpers.getObjectField(view, "mInactiveTrackDrawable");
            } catch (Throwable ignored) {
                drawable = null;
            }
        }
        if (drawable == null) {
            return "null";
        }
        return drawable.getClass().getSimpleName()
                + "(radius=" + callFloat(drawable, "getRadius")
                + ",weight=" + callFloat(drawable, "getWeight") + ")";
    }

    private static String rectOf(final View view, final String field) {
        try {
            Object value = XposedHelpers.getObjectField(view, field);
            return value instanceof Rect ? value.toString() : "null";
        } catch (Throwable t) {
            return "n/a";
        }
    }

    private static float readFloat(final View view, final String field,
            final float fallback) {
        try {
            return XposedHelpers.getFloatField(view, field);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean readBoolean(final View view, final String field,
            final boolean fallback) {
        try {
            return XposedHelpers.getBooleanField(view, field);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 反射调一个返回 float 的无参方法（系统 drawable 上的 {@code getRadius} 之类）。 */
    private static float callFloat(final Object target, final String method) {
        try {
            Object value = XposedHelpers.callMethod(target, method);
            return value instanceof Number ? ((Number) value).floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    private static void logOnce(final String message) {
        synchronized (sLogged) {
            if (!sLogged.add(message)) {
                return;
            }
        }
        XposedBridge.log(TAG + ": " + message);
    }
}
