package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 通知卡片的<b>底图替换</b>、<b>分组连体卡</b>与<b>长按拦截</b>。
 *
 * <p>通知和磁贴不是一套东西，两条链路都得单独接：</p>
 * <ul>
 *   <li><b>长按</b>：唯一漏斗是
 *       {@code ExpandableNotificationRow#doLongClickCallback(int, int)}，
 *       在它前面吞掉就等于通知的自定义菜单不会弹。</li>
 *   <li><b>底图</b>：通知行的背景不走 {@code View#setBackground}，而是
 *       {@code NotificationBackgroundView#onDraw} 里自己画。所以底图改成在
 *       {@code onDraw} 里把「画什么」换掉，裁剪范围、点击效果、图标叠加这些步骤
 *       全部复用系统自己的 {@code draw(Canvas, Drawable)}，只换 drawable 一个参数。</li>
 *   <li><b>分组头那层深色</b>：ColorOS 的分组容器走
 *       {@code NotificationChildrenContainerExtImp#getHeaderBgDrawable} 拿一条头部的
 *       底图，返回的 {@code NotificationHeaderMaskColorDrawable} 会先画背景、
 *       再叠一层深色蒙版。它不经 {@code View#setBackground}，所以清背景抓不到它——
 *       直接把这个方法的返回值换成我们自己的底图才是正解。</li>
 * </ul>
 *
 * <p>圆角：普通卡四角统一跟随配置；分组卡是一张<b>连体卡</b>——首行顶角、末行底角
 * 才是整卡外缘，中间的角必须是直角，否则子卡之间全是豁口。逐角半径直接交给
 * {@link TileBackgroundDrawable}（原生 {@code float[8]} 支持，不依赖
 * {@code canvas.clipPath}，硬件加速下也正确）。</p>
 */
public final class NotificationHook {

    private static final String TAG = "SystemUIHook/Notification";

    /** 通知行。 */
    private static final String CLASS_NOTIFICATION_ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    /** 通知行画底图的视图。 */
    private static final String CLASS_NOTIFICATION_BG =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";
    /** 圆角半径的最终出处：接口 default 方法 {@code getUpdatedRadii} 在这里。 */
    private static final String CLASS_ROUNDABLE =
            "com.android.systemui.statusbar.notification.Roundable";
    /** ColorOS 分组容器扩展：分组头那条底图的来源。 */
    private static final String CLASS_CHILDREN_EXT_IMP =
            "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp";
    /** 分组子容器本体：整张分组卡底图铺在它的 {@code dispatchDraw} 上。 */
    private static final String CLASS_CHILDREN_CONTAINER =
            "com.oplus.systemui.notification.row.oplusgroup.OplusNotificationChildrenContainer";

    /** 已画过的通知底图视图（弱键：通知划掉后自动不在里面），刷新时逐个重画。 */
    private static final Set<View> sViews =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** 只打一次的日志（每帧都会走到的地方，不能每次都写）。 */
    private static final Set<String> sLogged = new HashSet<String>();

    /**
     * 系统给背景 drawable 单独设过 alpha 的视图（{@code setDrawableAlpha} 被调过）。
     *
     * <p>字段默认值不可知，没被设过就一概不碰，免得把底图整层弄透明。</p>
     */
    private static final Set<View> sAlphaTracked =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    /** {@link #CLASS_NOTIFICATION_ROW} 的类对象，装钩子时记下来供 instanceof 用。 */
    private static Class<?> sRowClass;

    /** {@link #CLASS_CHILDREN_CONTAINER} 的类对象，同样只用于 instanceof 判定。 */
    private static Class<?> sContainerClass;

    /** 不是分组行。 */
    private static final int KIND_NONE = 0;
    /** 分组头所在的 summary 行。 */
    private static final int KIND_HEADER = 1;
    /** 分组里的子行。 */
    private static final int KIND_CHILD = 2;

    private NotificationHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        sRowClass = XposedHelpers.findClassIfExists(CLASS_NOTIFICATION_ROW, lpparam.classLoader);
        int press = hookLongPress(lpparam);
        int background = hookBackground(lpparam);
        int alpha = hookDrawableAlpha(lpparam);
        int headerBg = hookGroupHeaderBg(lpparam);
        int container = hookGroupContainer(lpparam);
        int radii = hookUpdatedRadii(lpparam);
        XposedBridge.log(TAG + ": installed (longPress=" + press
                + ", background=" + background + ", drawableAlpha=" + alpha
                + ", groupHeaderBg=" + headerBg + ", groupContainer=" + container
                + ", updatedRadii=" + radii + ")");
    }

    // ---------------------------------------------------------------- 长按拦截

    /**
     * 通知长按：弹模块自己的样式面板。
     *
     * <p>唯一漏斗是 {@code ExpandableNotificationRow#doLongClickCallback(int, int)}，在它前面
     * 吞掉就等于通知的自定义菜单不会弹。</p>
     *
     * <p>注意这里和系统「长按后拖动」是同一个手势：ColorOS 长按通知后拖动会进拖动模式
     * （整栏变暗 + 底部 ✕ + 右侧操作面板），而我们一旦拦截长按，那个模式就进不去。
     * 需要系统那套效果时，把模块设置里「通知卡片」的长按拦截关掉即可。</p>
     */
    private static int hookLongPress(final LoadPackageParam lpparam) {
        try {
            Class<?> row = XposedHelpers.findClass(CLASS_NOTIFICATION_ROW, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(row, "doLongClickCallback", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            View rowView = (View) param.thisObject;
                            Context ctx = rowView.getContext();
                            if (ctx == null || !TileLongPressConfig.isBlocked(
                                    ctx, TileLongPressConfig.SHAPE_NOTIFICATION)) {
                                return;
                            }
                            // 面板真的弹出来了才吞掉长按：吞了又没弹面板，
                            // 用户看到的就是「长按通知没反应」
                            if (TileStylePopup.show(rowView,
                                    TileLongPressConfig.SHAPE_NOTIFICATION, true, null)) {
                                param.setResult(null);
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
     * 被按的视图是不是通知行里的一部分。
     *
     * <p>{@link TileLongPressInterceptor} 会在 {@code View.performLongClick} 上按尺寸
     * 兜底认磁贴，通知行也够大，认不出时它会弹一句「长按没拦到」的诊断提示。
     * 通知有自己的长按链路（见 {@link #hookLongPress}），这里先把通知整棵子树摘出去。</p>
     */
    public static boolean isInsideNotification(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current.getClass().getName().contains("ExpandableNotificationRow")) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    // ---------------------------------------------------------------- 底图替换

    /**
     * 盯住 {@code NotificationBackgroundView#setDrawableAlpha(int)}。
     *
     * <p>系统的 {@code draw(Canvas, Drawable)} 里不对传入的 drawable 调 {@code setAlpha}，
     * 透明度是系统在 {@code setDrawableAlpha} 里直接推到 {@code mBackground} 字段上的
     * （通知划掉 / 收起的淡出走这里）。我们换上去的是另一个 drawable，不接这份 alpha
     * 的话，卡片淡出时底图会多赖一会儿。这里只登记「这个视图的 alpha 由系统管」，
     * 具体值在绘制时现读 {@code mDrawableAlpha} 字段（见 {@link #drawCustom}）。</p>
     */
    private static int hookDrawableAlpha(final LoadPackageParam lpparam) {
        try {
            Class<?> background = XposedHelpers.findClass(CLASS_NOTIFICATION_BG,
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(background, "setDrawableAlpha", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            synchronized (sAlphaTracked) {
                                sAlphaTracked.add((View) param.thisObject);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setDrawableAlpha failed - " + t);
            return 0;
        }
    }

    private static int hookBackground(final LoadPackageParam lpparam) {
        try {
            Class<?> background = XposedHelpers.findClass(CLASS_NOTIFICATION_BG,
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(background, "onDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || !(param.args[0] instanceof Canvas)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            remember(view);
                            try {
                                if (drawCustom(view, (Canvas) param.args[0])) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                logOnce("draw custom failed - " + t);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook background failed - " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------ 分组头那层深色

    /**
     * 把 ColorOS 给<b>分组头</b>画的那层底图换成我们自己的。
     *
     * <p>反编译取证：{@code NotificationChildrenContainerExtImp#getHeaderBgDrawable(
     * View, boolean, ExpandableNotificationRow)} 返回的是
     * {@code NotificationHeaderMaskColorDrawable}——它 {@code draw()} 里先画一层
     * {@code ColorDrawable}，再 {@code canvas.drawColor(maskColor)} 盖一层深色蒙版。
     * 这层蒙版<b>不是</b>经 {@code View#setBackground} 设的，所以按「清背景」的思路
     * 永远抓不到它。直接换掉返回值即可：声明返回类型是 {@code Drawable}，不涉强转。</p>
     *
     * <p>圆角：展开态头部是整卡顶边 → 只有顶角圆；收起态头部就是整张卡 → 四角都圆。
     * 「头部比整行矮」只在它下面还叠着子行时成立，正好拿来区分两种形态。</p>
     */
    private static int hookGroupHeaderBg(final LoadPackageParam lpparam) {
        Class<?> imp = XposedHelpers.findClassIfExists(CLASS_CHILDREN_EXT_IMP,
                lpparam.classLoader);
        if (imp == null) {
            XposedBridge.log(TAG + ": " + CLASS_CHILDREN_EXT_IMP
                    + " not found, group header bg untouched");
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(imp, "getHeaderBgDrawable",
                    View.class, boolean.class, CLASS_NOTIFICATION_ROW,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Drawable ours = buildHeaderBg(param);
                                if (ours != null) {
                                    param.setResult(ours);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getHeaderBgDrawable failed - " + t);
            return 0;
        }
    }

    /** 见 {@link #hookGroupHeaderBg}；没配底图返回 {@code null}（不动系统原值）。 */
    private static Drawable buildHeaderBg(final XC_MethodHook.MethodHookParam param) {
        if (!(param.args[0] instanceof View)) {
            return null;
        }
        View header = (View) param.args[0];
        Context ctx = header.getContext();
        if (ctx == null) {
            return null;
        }
        String base64 = TileLongPressConfig.tileBgImage(
                ctx, TileLongPressConfig.SHAPE_NOTIFICATION, true);
        if (base64.length() == 0) {
            return null;
        }
        // 分组头的底图由每一位行自己那一格负责（见 drawCustom）。系统给头部的这条底图
        // 再画一层的话，就会在头部位置多叠一层（用户看到的「多余的一层」），所以换成透明：
        // 既去掉了系统那层深色蒙版，又不在头部多叠。
        return new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
    }

    // ------------------------------------------------------------ 裁剪路径对齐

    /**
     * 钩住 {@code Roundable#getUpdatedRadii()} —— 系统裁剪路径消费的<b>真正圆角来源</b>。
     *
     * <p>反编译 {@code NotificationChildrenContainer#drawChild()}：头部是按
     * {@code mGroupHeaderWrapper.getUpdatedRadii()} 生成的路径裁剪的。只在底图上画圆角
     * 不够——<b>可见圆角 = 我们的底图 ∩ 系统裁剪路径，取小</b>，裁剪不抬上去的话，
     * 用户把圆角调大也看不出来（「改不了圆角」就是这么来的）。</p>
     *
     * <p>只在通知行 / 分组容器上生效，其它 {@code Roundable} 实现一概不碰。</p>
     */
    private static int hookUpdatedRadii(final LoadPackageParam lpparam) {
        Class<?> roundable = XposedHelpers.findClassIfExists(CLASS_ROUNDABLE, lpparam.classLoader);
        if (roundable == null) {
            XposedBridge.log(TAG + ": " + CLASS_ROUNDABLE + " not found, clip radii untouched");
            return 0;
        }
        try {
            java.lang.reflect.Method method =
                    XposedHelpers.findMethodExactIfExists(roundable, "getUpdatedRadii");
            if (method == null) {
                return 0;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof View)) {
                            return;
                        }
                        View view = (View) param.thisObject;
                        boolean isRow = sRowClass != null && sRowClass.isInstance(view);
                        boolean isContainer = view.getClass().getName().contains("ChildrenContainer");
                        if (!isRow && !isContainer) {
                            return;
                        }
                        float radius = configuredRadiusPx(view);
                        if (radius < 0f) {
                            return;
                        }
                        float[] radii;
                        if (isContainer) {
                            // 展开时容器只管整卡上半截（顶角圆、底角直角），下半截由末行
                            // 子通知自己的裁剪负责；收起时它就是整张卡 → 四角都圆
                            radii = containerExpanded(view)
                                    ? new float[]{radius, radius, radius, radius,
                                            0f, 0f, 0f, 0f}
                                    : new float[]{radius, radius, radius, radius,
                                            radius, radius, radius, radius};
                        } else if (isGroupRowSelf(view)) {
                            radii = groupRadiiForRow(view, radius);
                        } else {
                            radii = new float[]{radius, radius, radius, radius,
                                    radius, radius, radius, radius};
                        }
                        param.setResult(radii);
                    } catch (Throwable ignored) {
                        // 判不出来就按系统原本的值走
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook getUpdatedRadii failed - " + t);
            return 0;
        }
    }

    // ---------------------------------------------------------- 分组整卡底图

    /**
     * 在分组子容器的 {@code dispatchDraw} 之前，把<b>整张分组卡底图</b>铺上去。
     *
     * <p>铺在「容器开始画孩子」之前，就等于铺在所有子行内容<b>下面</b>：分组头、每行
     * 子通知的文字图标都会画在它上面，既压不住内容，又保证整张卡是完整的一块图。</p>
     *
     * <p>判定用 {@code sContainerClass.isInstance} 而不是类名字符串：Oplus 的容器类名
     * 带 {@code oplusgroup} 包名，用类对象比对最稳。钩 {@code ViewGroup#dispatchDraw}
     * 是因为容器自己没声明这个方法，用基类钩上再判断。</p>
     */
    private static int hookGroupContainer(final LoadPackageParam lpparam) {
        sContainerClass = XposedHelpers.findClassIfExists(CLASS_CHILDREN_CONTAINER,
                lpparam.classLoader);
        if (sContainerClass == null) {
            XposedBridge.log(TAG + ": " + CLASS_CHILDREN_CONTAINER + " not found");
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", Canvas.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (sContainerClass.isInstance(param.thisObject)
                                        && param.args[0] instanceof Canvas) {
                                    drawGroupBg((View) param.thisObject,
                                            (Canvas) param.args[0]);
                                }
                            } catch (Throwable t) {
                                logOnce("group container draw failed - " + t);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook group container failed - " + t);
            return 0;
        }
    }

    /**
     * 见 {@link #hookGroupContainer}。<b>只在展开时铺</b>：收起态容器视图高度还是展开
     * 时的高度，铺满会把下面没露出来的部分也糊上，那一态由分组头自己那一行负责。
     */
    private static void drawGroupBg(final View container, final Canvas canvas) {
        Context ctx = container.getContext();
        if (ctx == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return;
        }
        if (!containerExpanded(container)) {
            return;
        }
        String base64 = TileLongPressConfig.tileBgImage(
                ctx, TileLongPressConfig.SHAPE_NOTIFICATION, true);
        if (base64.length() == 0) {
            return;
        }
        Bitmap bitmap = TileBackgroundHook.decodeShapeImage(base64);
        if (bitmap == null) {
            return;
        }
        float radius = configuredRadiusPx(container);
        if (radius < 0f) {
            radius = TileLongPressConfig.FOLLOW_SYSTEM_DP
                    * ctx.getResources().getDisplayMetrics().density;
        }
        float[] radii = new float[]{radius, radius, radius, radius,
                radius, radius, radius, radius};
        Drawable bg = new TileBackgroundDrawable(Color.TRANSPARENT, 0f, bitmap,
                alphaPctOf(ctx), 0, 0, 0f, false, radii);
        bg.setBounds(0, 0, container.getWidth(), container.getHeight());
        bg.draw(canvas);
        logOnce("group container bg drawn: " + container.getWidth()
                + "x" + container.getHeight() + " radius=" + (int) radius);
    }

    /** 分组行的逐角圆角：交给 {@link #groupRadiiForRow}，按它在分组里的位置算。 */
    private static float[] groupRadii(final View view, final float radius) {
        View row = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (row == null) {
            return new float[]{radius, radius, radius, radius,
                    radius, radius, radius, radius};
        }
        return groupRadiiForRow(row, radius);
    }

    /**
     * 找到这个视图所属的分组子容器：先看宿主机的父级，再退回宿主机的子 view 里找。
     */
    private static View enclosingContainer(final View view) {
        if (sContainerClass == null) {
            return null;
        }
        View row = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (row == null) {
            return null;
        }
        ViewParent up = row.getParent();
        if (up instanceof View && sContainerClass.isInstance(up)) {
            return (View) up;
        }
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null && sContainerClass.isInstance(child)) {
                    return child;
                }
            }
        }
        return null;
    }

    /**
     * 分组是不是真展开了。
     *
     * <p>不能只看 {@code getHeight()}：收起时容器视图高度还是展开时的高度（实测
     * 850/956），可见部分只有 290。拿宿主行的 {@code getActualHeight()} 跟容器高度比，
     * 撑起来了才算展开（0.75 留动画余量）。</p>
     */
    private static boolean containerExpanded(final Object obj) {
        try {
            View container = (View) obj;
            View row = container.getParent() instanceof View
                    ? (View) container.getParent() : null;
            if (row != null && container.getHeight() > 0) {
                // 0.7 是动画余量：展开过程中高度是渐变上来的，卡太死会晚一拍才铺
                return actualHeight(row) > container.getHeight() * 0.7f;
            }
        } catch (Throwable ignored) {
            // 判不出来就按没展开处理
        }
        return false;
    }

    /** 整组高度：取分组容器那条子容器的底边（容器自己可能没撑到那么高）。 */
    private static int fullGroupHeight(final View view) {
        int height = view.getHeight();
        View row = view.getParent() instanceof View ? (View) view.getParent() : null;
        if (row instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) row;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null
                        && child.getClass().getName().contains("ChildrenContainer")) {
                    height = Math.max(height, child.getBottom());
                }
            }
        }
        return height;
    }

    /** 这一行所在的分组是不是展开着。 */
    private static boolean groupIsExpanded(final View view) {
        View container = enclosingContainer(view);
        return container != null && containerExpanded(container);
    }

    /** 分组是不是展开着（子容器可见且有高度）。 */
    private static boolean groupExpanded(final View row) {
        if (!(row instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            if (child.getClass().getName().contains("ChildrenContainer")) {
                return child.getVisibility() == View.VISIBLE && child.getHeight() > 0;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 用自定义底图替掉系统那一层。
     *
     * @return true 表示这一帧已经画完，系统的 {@code onDraw} 不用再跑
     */
    private static boolean drawCustom(final View view, final Canvas canvas) {
        Context ctx = view.getContext();
        if (ctx == null) {
            return false;
        }
        // 系统自己的两个提前返回，原样复刻：整行被裁没了 / 展开动画进行中都不画底图
        if (intField(view, "mClipTopAmount") + intField(view, "mClipBottomAmount")
                >= actualHeight(view)) {
            return false;
        }
        if (booleanField(view, "mExpandAnimationRunning")) {
            return false;
        }
        String base64 = TileLongPressConfig.tileBgImage(
                ctx, TileLongPressConfig.SHAPE_NOTIFICATION, true);
        if (base64.length() == 0) {
            return false;
        }
        Bitmap bitmap = TileBackgroundHook.decodeShapeImage(base64);
        if (bitmap == null) {
            return false;
        }

        int alphaPct = alphaPctOf(ctx);
        float radius = configuredRadiusPx(view);
        float[] systemRadii = systemRadii(view);
        int kind = groupKind(view);

        float[] perCorner = null;
        if (kind == KIND_HEADER) {
            // 分组头这一行本身就是整卡：四角都在外缘
            perCorner = new float[]{radius, radius, radius, radius,
                    radius, radius, radius, radius};
        } else if (kind == KIND_CHILD && radius >= 0f) {
            // 子行按自己在分组里的位置算：外侧跟配置走、内侧一律直角
            perCorner = groupRadii(view, radius);
        } else if (radius < 0f) {
            if (systemRadii != null && !uniform(systemRadii)) {
                perCorner = systemRadii;   // 没配过圆角：原样跟随系统的逐角半径
            } else {
                radius = systemRadii != null ? systemRadii[0]
                        : TileLongPressConfig.FOLLOW_SYSTEM_DP
                                * ctx.getResources().getDisplayMetrics().density;
            }
        }
        Drawable target = perCorner != null
                ? new TileBackgroundDrawable(Color.TRANSPARENT, 0f, bitmap, alphaPct,
                        0, 0, 0f, false, perCorner)
                : new TileBackgroundDrawable(Color.TRANSPARENT, radius, bitmap, alphaPct);

        if (isAlphaTracked(view)) {
            target.setAlpha(intField(view, "mDrawableAlpha"));
        }

        Object ext = null;
        try {
            ext = XposedHelpers.getObjectField(view, "mExt");
        } catch (Throwable ignored) {
        }

        if (kind == KIND_HEADER) {
            // 展开后分组头只占最上面一条，底图得按整组高度铺满，否则底下那些子行
            // 露出来的部分没有底图；收起时它就是一整张卡，走系统那条绘制路即可。
            if (groupIsExpanded(view)) {
                target.setBounds(0, 0, view.getWidth(), fullGroupHeight(view));
                target.draw(canvas);
            } else {
                XposedHelpers.callMethod(view, "draw", canvas, target);
            }
            // 小球（应用图标）由 ext 画，跟系统那条路一样补一次
            if (ext != null) {
                try {
                    XposedHelpers.callMethod(ext, "calculateAndDrawIcon", view, canvas);
                } catch (Throwable t) {
                    logOnce("group icon draw failed - " + t);
                }
            }
            return true;
        }

        canvas.save();
        try {
            boolean oplus = ext != null && Boolean.TRUE.equals(
                    XposedHelpers.callMethod(ext, "getOplusStyle"));
            if (oplus && drawWithExt(ext, canvas, target)) {
                try {
                    XposedHelpers.callMethod(ext, "calculateAndDrawIcon", view, canvas);
                } catch (Throwable t) {
                    logOnce("oplus icon draw failed - " + t);
                }
            } else {
                // 系统自己的绘制路径：算 bounds、画底图、点击效果、图标叠加都在里面
                XposedHelpers.callMethod(view, "draw", canvas, target);
            }
        } finally {
            canvas.restore();
        }
        return true;
    }

    /** OPPO 那条绘制路径（{@code mExt.getOplusStyle()} 为真时走它）。 */
    private static boolean drawWithExt(final Object ext, final Canvas canvas,
            final Drawable custom) {
        try {
            XposedHelpers.callMethod(ext, "draw", canvas, custom);
            return true;
        } catch (Throwable t) {
            logOnce("oplus draw failed, fall back to AOSP path - " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 分组逐角圆角

    /**
     * 分组行的<b>逐角</b>圆角：<b>外侧</b>用配置值，<b>内侧</b>一律直角。
     *
     * <p>分组是一张连体卡：首行顶角、末行底角才是整卡的外缘，中间的角必须是直角
     * 才连得起来。分组头收起时本身就是整卡 → 四角都在外缘；展开时它下面还接着子行，
     * 底角就成了内侧，必须直角。</p>
     */
    private static float[] groupRadiiForRow(final View row, final float outer) {
        float top;
        float bottom;
        if (isChildRowInGroup(row)) {
            int[] position = childPosition(row);
            if (position == null) {
                top = outer;
                bottom = outer;
            } else {
                top = position[0] == 0 ? outer : 0f;
                bottom = position[0] == position[1] - 1 ? outer : 0f;
            }
        } else {
            top = outer;
            bottom = groupExpanded(row) ? 0f : outer;
        }
        float[] radii = new float[8];
        radii[0] = top; radii[1] = top; radii[2] = top; radii[3] = top;
        radii[4] = bottom; radii[5] = bottom; radii[6] = bottom; radii[7] = bottom;
        return radii;
    }

    /**
     * 这个子行在分组里是第几个可见行、一共几个可见行。
     *
     * <p>只数「行」：容器里还夹着分隔线，把它们算进去首/末行就判错了。</p>
     *
     * @return {@code {可见序号, 可见行数}}；数不出来返回 null
     */
    private static int[] childPosition(final View row) {
        ViewParent p = row.getParent();
        if (!(p instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) p;
        int index = -1;
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (!child.getClass().getName().contains("NotificationRow")) {
                continue;
            }
            if (child == row) {
                index = count;
            }
            count++;
        }
        return index < 0 ? null : new int[]{index, count};
    }

    /**
     * 这个底图视图所在的行是不是<b>分组通知</b>（分组头 / 分组子行）——全实时，不缓存。
     *
     * <p>行是复用的：一条行先按普通通知画、后来变成分组头 / 子行，按 view 记的缓存
     * 会一直是旧值。这里每帧现算，单次只是几次类名 / 字段比较，不拖帧。</p>
     */
    private static int groupKind(final View bgView) {
        View row = bgView.getParent() instanceof View ? (View) bgView.getParent() : null;
        if (row == null) {
            return KIND_NONE;
        }
        if (isChildRowInGroup(row)) {
            return KIND_CHILD;
        }
        if (sRowClass != null && sRowClass.isInstance(row)) {
            try {
                if (XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")) {
                    return KIND_HEADER;
                }
            } catch (Throwable ignored) {
                // 没这个字段：不是分组头
            }
        }
        return KIND_NONE;
    }

    /** 行本体是不是分组头 / 子行（钩子拿到的是行本身，判定入口不一样）。 */
    private static boolean isGroupRowSelf(final View row) {
        if (row == null) {
            return false;
        }
        try {
            if (XposedHelpers.getBooleanField(row, "mIsSummaryWithChildren")) {
                return true;
            }
        } catch (Throwable ignored) {
            // 没这个字段：不是分组头
        }
        return isChildRowInGroup(row);
    }

    /**
     * 这个行是不是<b>挂在分组子容器里的子行</b>（纯结构判断，零反射）。
     *
     * <p>子行的父级链上一定有 {@code NotificationChildrenContainer}（Oplus 版类名同含
     * {@code ChildrenContainer}）。往上找三级：有的版本子行外面还套一层模板 wrapper。</p>
     */
    private static boolean isChildRowInGroup(final View row) {
        ViewParent p = row.getParent();
        for (int i = 0; i < 3 && p instanceof View; i++) {
            if (p.getClass().getName().contains("ChildrenContainer")) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具

    /** 主题配置里的圆角（像素）：素材比例 > 用户 dp > 跟系统（返回 -1）。 */
    private static float configuredRadiusPx(final View view) {
        try {
            Context ctx = view.getContext();
            if (ctx == null) {
                return -1f;
            }
            TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                    ctx, TileLongPressConfig.SHAPE_NOTIFICATION);
            float density = ctx.getResources().getDisplayMetrics().density;
            int cornerDp = style != null
                    ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
            float ratio = TileLongPressConfig.materialCornerRatio(
                    ctx, TileLongPressConfig.SHAPE_NOTIFICATION);
            if (ratio > 0f && view.getWidth() > 0) {
                return ratio * view.getWidth();
            }
            if (cornerDp >= 0) {
                return cornerDp * density;
            }
        } catch (Throwable ignored) {
            // 拿不到就按 -1（跟系统）走
        }
        return -1f;
    }

    private static int alphaPctOf(final Context ctx) {
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                ctx, TileLongPressConfig.SHAPE_NOTIFICATION);
        return style != null ? style.alphaPct(true) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
    }

    /** 这一行的 {@code mCornerRadii}（左上 / 右上 / 右下 / 左下）；拿不到返回 null。 */
    private static float[] systemRadii(final View view) {
        try {
            Object field = XposedHelpers.getObjectField(view, "mCornerRadii");
            return field instanceof float[] && ((float[]) field).length == 8
                    ? (float[]) field : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean uniform(final float[] radii) {
        for (int i = 1; i < radii.length; i++) {
            if (radii[i] != radii[0]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这个 drawable 是不是本模块给通知行自造的底图。
     *
     * <p>{@code QsBackgroundHook} 拦 {@code ViewBlurProxy#getBlurDrawable} 时要靠它认出来
     * 原样放行：OPPO 的 {@code NotificationBackgroundViewExtImp#draw} 在「非 noColorized +
     * 有模糊代理」时会拿 {@code getBlurDrawable} 的返回值顶掉我们传进去的底图，
     * 随后叠系统色——放行之后等值分支成立，它才直接用我们的底图。</p>
     */
    public static boolean isOwnBackground(final Drawable drawable) {
        return drawable instanceof TileBackgroundDrawable;
    }

    /**
     * 让所有画过的通知底图重画一遍。
     *
     * <p>底图是 {@code onDraw} 里现读配置的，所以只要重画就能看到新图，
     * 不用重启 SystemUI。</p>
     *
     * @return 重画的通知数量
     */
    public static int refresh() {
        int count = 0;
        synchronized (sViews) {
            for (View view : sViews) {
                if (view != null) {
                    view.invalidate();
                    // 父行一起刷：裁剪路径是 drawChild 现算的，只刷底图视图不保证那层重跑
                    ViewParent parent = view.getParent();
                    if (parent instanceof View) {
                        ((View) parent).invalidate();
                    }
                    count++;
                }
            }
        }
        XposedBridge.log(TAG + ": refreshed " + count + " notification background(s)");
        return count;
    }

    private static void remember(final View view) {
        synchronized (sViews) {
            sViews.add(view);
        }
    }

    /**
     * 已经画过的通知底图视图快照。
     *
     * <p>{@link NotificationTextHook} 改完文字颜色后要重贴已经显示出来的通知文字，
     * 但它自己不知道屏幕上有哪些通知，就从这里借一份。</p>
     */
    static List<View> trackedViews() {
        synchronized (sViews) {
            return new ArrayList<View>(sViews);
        }
    }

    private static int intField(final View view, final String name) {
        try {
            Object value = XposedHelpers.getObjectField(view, name);
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean booleanField(final View view, final String name) {
        try {
            Object value = XposedHelpers.getObjectField(view, name);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 这个视图的 drawable alpha 是不是由系统管（{@code setDrawableAlpha} 被调过）。 */
    private static boolean isAlphaTracked(final View view) {
        synchronized (sAlphaTracked) {
            return sAlphaTracked.contains(view);
        }
    }

    private static int actualHeight(final View view) {
        try {
            Object value = XposedHelpers.callMethod(view, "getActualHeight");
            return value instanceof Integer ? (Integer) value : view.getHeight();
        } catch (Throwable ignored) {
            return view.getHeight();
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
