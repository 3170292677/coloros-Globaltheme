package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 磁贴圆角。
 *
 * <p>ColorOS 16 的磁贴圆角<b>不在背景 drawable 上</b>，所以之前改 drawable 的圆角
 * 完全没有效果。真实链路（反编译 SystemUI 16.00.12 {@code classes6.dex} 得到）：</p>
 *
 * <pre>
 * OplusQSTileBaseView.mBg（背景 ImageView）的 ViewOutlineProvider
 *   = QsViewOutlineProviderKt.getTileViewOutlineProvider(mBg)
 *       → QsViewOutlineProvider$QsViewPathOutlineProvider      ← 主面板走这个
 *            getOutline(View, Outline):
 *               super.getOutline() → view.setClipToOutline(true)
 *               params = getRoundParams(hostView)   // 1×1 在这里被换成“圆形参数”
 *               if (params == null) → outline.setRect(0,0,w,h)   // 方角
 *               else → PathParser.createPathFromPathData(outlineShapeReference 里的
 *                      路径字符串) 缩放后 outline.setPath(path)   // 圆角来自“路径资源”
 * </pre>
 *
 * <p>关键点：主面板的圆角是<b>一条路径</b>（squircle 形状资源），不是数值，
 * 所以想改成任意圆角只能<b>在系统算完之后重写 Outline</b>。
 * {@code getOutline(View, Outline)} 自带 host view 参数，形状可以现场判定，
 * 重写 {@code setPath} 会整体替换系统刚写进去的那条路径，这是最稳的一层。</p>
 *
 * <p>写进去的圆角用 {@link SmoothCorners}（连续圆角）构造，跟系统同一条曲线；
 * 否则外轮廓是正圆弧、底图是连续圆角，两层叠起来还是会差一点点。</p>
 *
 * <p>另外 {@code OplusQSResizeableTileView.getRadius()}（编辑面板，1×1 返回高度一半
 * = 正圆）也一并接管，这样编辑面板和主面板观感一致。</p>
 *
 * <p>形状判定沿用 {@link TileLongPressInterceptor#shapeOfView(View)}：
 * 视图类名优先，主面板按背景视图实测宽度推算。</p>
 */
public final class TileCornerHook {

    private static final String TAG = "SystemUIHook/Corner";

    /** 主面板磁贴背景用的 outline provider。 */
    private static final String CLASS_PATH_PROVIDER =
            "com.oplus.systemui.qs.base.widget.QsViewOutlineProvider$QsViewPathOutlineProvider";
    /** 编辑面板预览 / 编辑按钮用的 outline provider。 */
    private static final String CLASS_ROUND_RECT_PROVIDER =
            "com.oplus.systemui.qs.base.widget.QsViewOutlineProvider$QsViewRoundRectOutlineProvider";
    /** 上面两个的基类（自带 hostView 字段与 getHostView()）。 */
    private static final String CLASS_BASE_PROVIDER =
            "com.oplus.systemui.qs.base.widget.QsViewOutlineProvider$QsViewBaseOutlineProvider";

    /** 编辑面板磁贴视图：getRadius() 决定卡片圆角。 */
    private static final String CLASS_RESIZEABLE_TILE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView";
    private static final String CLASS_RESIZEABLE_TILE_ONE_X_ONE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewOneXOne";

    private TileCornerHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        int n = 0;
        n += hookOutlineProvider(lpparam, CLASS_PATH_PROVIDER);
        n += hookOutlineProvider(lpparam, CLASS_ROUND_RECT_PROVIDER);
        n += hookOutlineProvider(lpparam, CLASS_BASE_PROVIDER);
        n += hookSystemRoundParams(lpparam, CLASS_PATH_PROVIDER);
        n += hookSystemRoundParams(lpparam, CLASS_ROUND_RECT_PROVIDER);
        n += hookRadius(lpparam, CLASS_RESIZEABLE_TILE);
        n += hookRadius(lpparam, CLASS_RESIZEABLE_TILE_ONE_X_ONE);
        sInstalledHooks = n;
        XposedBridge.log(TAG + ": installed (hooks=" + n + ")");
    }

    // ------------------------------------------------------------ outline

    /**
     * 装 outline 用的临时路径。
     *
     * <p>{@code getOutline} 只在主线程跑，共用一份即可；{@code Outline#setPath}
     * 会把路径复制走，不会留下引用。</p>
     */
    private static final Path sOutlinePath = new Path();

    /**
     * 系统算完 outline 之后按配置的圆角重写它。
     *
     * <p>{@code Outline.setPath} 会整体替换掉之前 {@code setPath} 写进去的路径，
     * 所以哪怕系统用的是 squircle 路径资源，也能被我们改成任意圆角。</p>
     */
    private static int hookOutlineProvider(final LoadPackageParam lpparam, final String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            XposedBridge.log(TAG + ": class not found - " + className);
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "getOutline",
                    View.class, Outline.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            applyOutline((View) param.args[0], (Outline) param.args[1]);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + className + ".getOutline failed - " + t);
            return 0;
        }
    }

    private static void applyOutline(final View hostView, final Outline outline) {
        if (hostView == null || outline == null) {
            return;
        }
        try {
            sOutlineCalls++;
            String shape = TileLongPressInterceptor.shapeOfView(hostView);
            if (shape == null) {
                sLastProbe = "形状判不出（"
                        + TileLongPressInterceptor.sizeProbe(hostView) + "）";
                return;
            }
            // 和自定义底图共用同一个半径，保证「磁贴圆角」和「底图圆角」始终一致
            int w = hostView.getWidth();
            int h = hostView.getHeight();
            float radiusPx = cornerPxFor(hostView, shape, w);
            if (radiusPx < 0f) {
                sLastProbe = "shape=" + shape + " 跟随系统";
                return;
            }
            if (w <= 0 || h <= 0) {
                sLastProbe = "shape=" + shape + " 尺寸为 0";
                return;
            }
            // Skia 对超过短边一半的圆角会自己截断，这里显式截断一次，
            // 让 outline 与 TileBackgroundDrawable 的可见圆角完全一致
            float radius = Math.min(radiusPx, Math.min(w, h) / 2f);
            // 圆角必须跟系统走同一条「连续圆角」曲线，否则外轮廓是正圆弧、
            // 自定义底图是连续圆角，两层叠起来又是「突出来一点 / 凹进去一点」。
            // 解析不到 OplusPath 时退回正圆弧（跟系统 weight==0 分支一致）。
            if (!SmoothCorners.buildPath(sOutlinePath, 0f, 0f, w, h, radius,
                    SmoothCorners.weight())) {
                outline.setRoundRect(0, 0, w, h, radius);
            } else {
                // Outline#setPath 内部会把路径复制一份，静态 Path 复用是安全的
                outline.setPath(sOutlinePath);
            }
            // 光有 outline 不会裁剪：还得让这块视图按 outline 裁剪，
            // 否则自定义底图自己的圆角与磁贴的可见形状会是两套（切深浅色后尤其明显）
            hostView.setClipToOutline(true);
            sOutlineApplied++;
            sLastProbe = "shape=" + shape + " corner=" + Math.round(radiusPx / density(hostView.getContext()))
                    + "dp view=" + hostView.getClass().getSimpleName()
                    + " size=" + w + "x" + h;
            logAppliedOnce(hostView, shape, radiusPx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyOutline failed - " + t);
        }
    }

    /**
     * 该形状该用的圆角像素。
     *
     * <p>主题包导入时记下的「素材圆角 ÷ 素材宽」比例优先：素材尺寸和磁贴尺寸不保证
     * 1:1，只有拿到视图宽度才折算出像素值，所以这个换算只能在这里做。没导入过主题包
     * （比例 0）才走样式面板那套 {@link TileLongPressConfig#cornerRadiusPx}。</p>
     *
     * <p>两处口径必须一致：outline 决定磁贴本体裁剪成什么形状，底图决定图片自己的圆角，
     * 各算一遍就会看到「图片圆角」和「磁贴圆角」对不上。</p>
     */
    private static float cornerPxFor(final View view, final String shape, final int width) {
        Context ctx = view.getContext();
        float ratio = TileLongPressConfig.materialCornerRatio(ctx, shape);
        if (ratio > 0f && width > 0) {
            return ratio * width;
        }
        return TileLongPressConfig.cornerRadiusPx(ctx, shape);
    }

    /** 只在第一次真正改到某块磁贴时打一条日志，避免每帧刷屏。 */
    private static void logAppliedOnce(final View hostView, final String shape, final float radiusPx) {
        try {
            String key = shape + "@" + hostView.getClass().getSimpleName();
            if (!sLogged.add(key)) {
                return;
            }
            XposedBridge.log(TAG + ": outline applied shape=" + shape
                    + " corner=" + Math.round(radiusPx / density(hostView.getContext())) + "dp"
                    + " view=" + hostView.getClass().getSimpleName()
                    + " size=" + hostView.getWidth() + "x" + hostView.getHeight());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ getRadius

    /** 编辑面板磁贴自己算圆角的方法（1×1 返回高度一半 = 正圆）。 */
    private static int hookRadius(final LoadPackageParam lpparam, final String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "getRadius", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    String shape = TileLongPressInterceptor.shapeOfView(view);
                    if (shape == null) {
                        return;
                    }
                    float radiusPx = cornerPxFor(view, shape, view.getWidth());
                    if (radiusPx >= 0f) {
                        param.setResult(radiusPx);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + className + ".getRadius failed - " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------ 系统那层圆角

    /** 系统每块磁贴的圆角参数工厂。 */
    private static final String METHOD_GET_ROUND_PARAMS = "getRoundParams";

    /**
     * 把系统自己那层圆角也换成样式面板里的值。
     *
     * <p>系统把「这块视图该用多大圆角、多大权重」统一收在
     * {@code QsViewOutlineProvider#getRoundParams(View)} 里，三个下游全是从它拿的参数：</p>
     * <pre>
     * getRoundParams(view) → OplusRoundParams(radius, weight)
     *   ├─ QsViewPathOutlineProvider.getOutline()          → outline（我们已经整条重写）
     *   ├─ BaseQsViewBackground.createOrUpdateGradientDrawable()
     *   │     → SmoothCornersGradientDrawable.setSmoothRound(radius, weight)
     *   └─ refreshSmoothRound$vendor…() → BlurConfig.setCornerRadius/setRadiusWeight
     * </pre>
     *
     * <p>只重写 outline 的话，磁贴不贴自定义底图时看到的仍是系统原来那圈圆角
     * （外轮廓被我们改了、里面那层没改，角上就会露一条缝）。改这一个方法的返回值，
     * 下游三层一起跟着走。</p>
     *
     * <p>返回值<b>不就地改</b>：{@code OplusQsSmoothRoundUtil} 内部按 dp 把这些实例
     * 缓存在静态 map 里，就地改会污染缓存；用 {@code copy(...)} 复制一份新的返回。</p>
     */
    private static int hookSystemRoundParams(final LoadPackageParam lpparam, final String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (clazz == null) {
            return 0;
        }
        // 只在类自己声明的方法上挂钩：基类那份是抽象的，挂上去会误伤别的子类
        Method method = XposedHelpers.findMethodExactIfExists(
                clazz, METHOD_GET_ROUND_PARAMS, View.class);
        if (method == null) {
            XposedBridge.log(TAG + ": " + className + "." + METHOD_GET_ROUND_PARAMS
                    + " not declared, system corner untouched");
            return 0;
        }
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    overrideRoundParams(param);
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + className + "." + METHOD_GET_ROUND_PARAMS
                    + " failed - " + t);
            return 0;
        }
    }

    private static void overrideRoundParams(final XC_MethodHook.MethodHookParam param) {
        try {
            if (!(param.args[0] instanceof View)) {
                return;
            }
            Object params = param.getResult();
            if (params == null) {
                return;
            }
            View hostView = (View) param.args[0];
            String shape = TileLongPressInterceptor.shapeOfView(hostView);
            if (shape == null) {
                return;
            }
            // 先把系统自己算的半径记下来：「跟随系统」要靠它。系统对 1×1 / 2×1 / 2×2
            // 给的半径并不一样，模块侧只有一个 20dp 常量，套到宽卡片上会明显偏圆
            // （见 TileLongPressConfig#systemCornerPx）。
            TileLongPressConfig.recordSystemCornerPx(shape,
                    XposedHelpers.getFloatField(params, "radius"));
            // 正圆（1×1 圆形磁贴 / 清除按钮）不碰：那是形状语义，不是圆角值。
            // 权重为 0 只说明系统按正圆弧画（不是「直角」），换半径照样有意义，
            // 所以不能在这里提前返回，否则那些形状的圆角就永远改不动了
            if (XposedHelpers.getBooleanField(params, "isCircle")) {
                return;
            }
            float radiusPx = cornerPxFor(hostView, shape, hostView.getWidth());
            if (radiusPx < 0f || radiusPx == XposedHelpers.getFloatField(params, "radius")) {
                return;
            }
            param.setResult(XposedHelpers.callMethod(params, "copy",
                    XposedHelpers.getFloatField(params, "radiusDp"), radiusPx,
                    XposedHelpers.getFloatField(params, "weight"), Boolean.FALSE));
            sSystemRoundApplied++;
            logSystemRoundOnce(shape, radiusPx, hostView);
        } catch (Throwable t) {
            if (!sRoundParamsFailed) {
                sRoundParamsFailed = true;
                XposedBridge.log(TAG + ": override round params failed - " + t);
            }
        }
    }

    /** 只在第一次改到某个形状的系统圆角时打日志。 */
    private static void logSystemRoundOnce(final String shape, final float radiusPx,
            final View hostView) {
        try {
            if (!sLogged.add("sys:" + shape)) {
                return;
            }
            XposedBridge.log(TAG + ": system round overridden shape=" + shape
                    + " corner=" + Math.round(radiusPx / density(hostView.getContext())) + "dp"
                    + " view=" + hostView.getClass().getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 排查用

    /** 已调用过多少次 {@code getOutline}。 */
    private static volatile int sOutlineCalls;
    /** 其中有多少次被我们真正重写。 */
    private static volatile int sOutlineApplied;
    /** 系统那层圆角（{@code getRoundParams}）被改写过多少次。 */
    private static volatile int sSystemRoundApplied;
    /** 改写系统圆角参数失败只打一条日志。 */
    private static volatile boolean sRoundParamsFailed;
    /** 最近一次的判定结论，样式面板直接显示。 */
    private static volatile String sLastProbe = "尚未触发";
    /** 安装时找到并挂上的 provider 数。 */
    private static volatile int sInstalledHooks;

    /** 圆角侧的运行状态（样式面板显示，用来判断磁贴圆角到底有没有被改到）。 */
    public static String probe() {
        return "已挂 " + sInstalledHooks + " 个 · getOutline 调用 " + sOutlineCalls
                + " 次 · 重写 " + sOutlineApplied + " 次 · 系统圆角 " + sSystemRoundApplied
                + " 次 · " + sLastProbe;
    }

    // ------------------------------------------------------------ 对外刷新

    /**
     * 圆角改完后让该磁贴重新计算 outline（主线程调用）。
     *
     * <p>outline 挂在背景 ImageView（{@code mBg}）上，所以要单独把它 invalidate，
     * 只 invalidate 磁贴本身不会重新触发它的 outline provider。取不到背景视图时
     * （主面板的 2×1 / 三阶段是 {@code OplusQSResizeableTileView*}，没有
     * {@code getBackgroundView()}）改成整棵树都刷一遍，保证 outline 一定重算。</p>
     */
    public static void refreshView(final View tileView) {
        if (tileView == null) {
            return;
        }
        try {
            Object bg = XposedHelpers.callMethod(tileView, "getBackgroundView");
            if (bg instanceof View) {
                ((View) bg).invalidateOutline();
            }
        } catch (Throwable ignored) {
            // 没有这个方法：靠下面的整树刷新兜底
        }
        invalidateOutlineTree(tileView);
    }

    /** 把这棵子树里每个视图的 outline 都作废（圆角改了必须让 outline provider 重跑）。 */
    private static void invalidateOutlineTree(final View view) {
        if (view == null) {
            return;
        }
        try {
            view.invalidateOutline();
            view.invalidate();
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                invalidateOutlineTree(group.getChildAt(i));
            }
        }
    }

    // ------------------------------------------------------------ 工具

    private static float density(final Context context) {
        return context == null ? 1f : context.getResources().getDisplayMetrics().density;
    }

    /** 已打过日志的 “形状@视图类名”，用于去重。 */
    private static final java.util.Set<String> sLogged =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
}
