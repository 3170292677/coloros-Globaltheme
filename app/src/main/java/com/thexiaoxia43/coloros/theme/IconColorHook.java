package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 「图标底色」：给压在图标底下那块色块换颜色。
 *
 * <p>要改的是两块独立的色块，都在图标视图自己身上，跟磁贴底图（{@link TileBackgroundHook}
 * 替换的那层整卡背景）不是一回事：</p>
 * <ul>
 *   <li><b>2×1 宽卡片</b>（WLAN / 蓝牙这类）：色块是 {@code OplusQSIconView} 的遮罩
 *       {@code maskView}，底色存在它自己的 {@code maskViewBackground: GradientDrawable} 上。
 *       磁贴状态一变（{@code OplusQSResizeableTileViewTwoXOne.handleTileStateChange}）
 *       就会走 {@code QSIconView.updateIconBackground(Z,I)} →
 *       {@code tintBgColor(Z,I)} → {@code setIconBackgroundColor(I)} 把颜色重新算一遍。
 *       <p>「关 → 开」还多一路：{@code doAnimation} 会拉起弹簧动画
 *       {@code OplusQSHighlightTileViewAnimatorIconBg}，每帧走
 *       {@code updateMixColorBackground(Context,I,I)}，动画结束时再补一次。这条链路<b>不经过</b>
 *       上面两个方法，混色模式下会把遮罩换成一整块系统混色模糊图，
 *       所以要单独再挂一个钩子把底色盖回去。</p></li>
 *   <li><b>2×1 三阶段卡片</b>（铃铛 / 震动 / 静音）：色块是
 *       {@code OplusQSThreeStageLayout} 里跟着高亮图标滑动的 {@code indicatorView}，
 *       底色存在 {@code indicatorBackground: GradientDrawable} 上。</li>
 * </ul>
 *
 * <p>两处都在<b>原方法之后</b>把颜色盖回去：系统算它自己的（含混色 / 主题分支），
 * 我们最后落笔，既不用重写整套配色，也不会被随后的状态刷新冲掉。</p>
 *
 * <p>回到「跟随系统」时把第一次覆盖前记下的系统色写回去，不用重启系统界面。</p>
 */
public final class IconColorHook {

    private static final String TAG = "SystemUIHook/IconColor";

    /** 2×1 / 2×2 磁贴的图标视图。 */
    private static final String CLASS_ICON_VIEW =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSIconView";
    /** 三阶段卡片的容器（指示块在它里面）。 */
    private static final String CLASS_THREE_STAGE_LAYOUT =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout";

    private static final String METHOD_SET_ICON_BG = "setIconBackgroundColor";
    private static final String METHOD_UPDATE_ICON_BG = "updateIconBackground";
    private static final String METHOD_MIX_ICON_BG = "updateMixColorBackground";
    private static final String METHOD_GET_MASK_BG = "getMaskViewBackground";
    private static final String METHOD_GET_INDICATOR_BG = "getIndicatorBackground";
    private static final String METHOD_SET_BACKGROUND = "setBackground";
    private static final String FIELD_INDICATOR_VIEW = "indicatorView";

    /** 见过的图标视图 → 它最后一次生效的「开 / 关」状态（弱键，视图销毁后自动回收）。 */
    private static final Map<View, Boolean> sViews =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /**
     * 图标视图 → 我们第一次覆盖<b>之前</b>的系统色。
     *
     * <p>「跟随系统」要还原就得有原值：色块上的 drawable 是系统自己的实例，
     * 被我们改过色之后就再也读不回原来那个颜色了，只能提前记一份。</p>
     */
    private static final Map<View, Integer> sOriginalColors =
            Collections.synchronizedMap(new WeakHashMap<View, Integer>());

    /** 覆盖过色块的次数（样式面板显示，用来判断 hook 有没有跑起来）。 */
    private static volatile int sApplyCount;

    private IconColorHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        int n = 0;
        n += hookIconView(lpparam);
        n += hookThreeStage(lpparam);
        XposedBridge.log(TAG + ": installed (hooks=" + n + ")");
    }

    // ------------------------------------------------------------- 2×1 宽卡片

    private static int hookIconView(final LoadPackageParam lpparam) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLASS_ICON_VIEW, lpparam.classLoader);
        if (cls == null) {
            XposedBridge.log(TAG + ": " + CLASS_ICON_VIEW + " not found");
            return 0;
        }
        int n = 0;
        // 状态变化时系统自己算一遍色块颜色，这里在原方法之后落笔
        n += hookOnClass(cls, METHOD_SET_ICON_BG, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyIconView(param.thisObject, true);
            }
        }, int.class);
        // 「关」的时候系统会把色块整个撤掉（setBackground(null)），
        // 所以这个重载只在「开」那一路覆盖
        n += hookOnClass(cls, METHOD_UPDATE_ICON_BG, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean active = param.args != null && param.args.length > 0
                        && Boolean.TRUE.equals(param.args[0]);
                applyIconView(param.thisObject, active);
            }
        }, boolean.class, int.class);
        // 「关 → 开」的弹簧动画（OplusQSHighlightTileViewAnimatorIconBg）走这条：
        // 混色模式下它每帧/收尾都把遮罩换成系统混色模糊图，不经过上面两个方法，
        // 不在这里补一笔，磁贴重新亮起来时底色就变回系统的了
        n += hookOnClass(cls, METHOD_MIX_ICON_BG, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // 这条链路是混色自己的画法，「跟随系统」时不要插手
                applyIconView(param.thisObject, true, false);
            }
        }, Context.class, int.class, int.class);
        return n;
    }

    /** 把配置的图标底色套到 {@code OplusQSIconView} 的遮罩块上。 */
    private static void applyIconView(final Object iconView, final boolean active) {
        applyIconView(iconView, active, true);
    }

    /**
     * @param allowRestore 「跟随系统」时是否要把记下的系统色写回去。
     *                     混色动画那条链路传 false：那块是系统自己按混色规则画的模糊底图，
     *                     写回一个纯色反而是破坏。
     */
    private static void applyIconView(final Object iconView, final boolean active,
            final boolean allowRestore) {
        if (!(iconView instanceof View)) {
            return;
        }
        final View view = (View) iconView;
        if (!active) {
            return;
        }
        String shape = TileLongPressInterceptor.shapeOfView(view);
        if (!TileLongPressConfig.supportsIconColor(shape)) {
            return;
        }
        Context ctx = view.getContext();
        int argb = TileLongPressConfig.iconColorOf(ctx, shape);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            if (allowRestore) {
                restore(view, shape, ctx);
            }
            return;
        }
        // 「颜色区分」：暗色往黑偏、亮色往白偏，补上系统那层和卡片底色的差别
        argb = TileLongPressConfig.differentiatedIconColor(ctx, argb);
        try {
            Object mask = XposedHelpers.callMethod(view, METHOD_GET_MASK_BG);
            if (!(mask instanceof GradientDrawable)) {
                return;
            }
            GradientDrawable drawable = (GradientDrawable) mask;
            rememberOriginal(view, drawable.getColor());
            drawable.setColor(argb);
            // 系统设过一次背景，这里把同一个 drawable 再挂一次：
            // 混色分支走的是 updateMixColorBackground，换的是另一份 drawable
            XposedHelpers.callMethod(view, METHOD_SET_BACKGROUND, drawable);
            note(view, true);
            sApplyCount++;
        } catch (Throwable t) {
            logOnce("applyIconView failed: " + t);
        }
    }

    // --------------------------------------------------------- 2×1 三阶段卡片

    private static int hookThreeStage(final LoadPackageParam lpparam) {
        Class<?> cls = XposedHelpers.findClassIfExists(
                CLASS_THREE_STAGE_LAYOUT, lpparam.classLoader);
        if (cls == null) {
            XposedBridge.log(TAG + ": " + CLASS_THREE_STAGE_LAYOUT + " not found");
            return 0;
        }
        return hookOnClass(cls, METHOD_SET_ICON_BG, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyThreeStage(param.thisObject, true);
            }
        }, int.class);
    }

    /** 把配置的图标底色套到三阶段卡片的指示块上。 */
    private static void applyThreeStage(final Object layout, final boolean active) {
        if (!(layout instanceof View)) {
            return;
        }
        final View view = (View) layout;
        if (!active) {
            return;
        }
        // 挂在 OplusQSThreeStageLayout 上，形状必然是「三阶段」，不用现场判
        // （现场判会在视图布局前量到宽度 0，一路回落成 1×1）
        String shape = TileLongPressConfig.SHAPE_THREE;
        if (!TileLongPressConfig.supportsIconColor(shape)) {
            return;
        }
        Context ctx = view.getContext();
        int argb = TileLongPressConfig.iconColorOf(ctx, shape);
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            restore(view, shape, ctx);
            return;
        }
        // 「颜色区分」：暗色往黑偏、亮色往白偏，把底色和卡片底图拉开点差别
        argb = TileLongPressConfig.differentiatedIconColor(ctx, argb);
        try {
            Object bg = XposedHelpers.callMethod(view, METHOD_GET_INDICATOR_BG);
            if (!(bg instanceof GradientDrawable)) {
                return;
            }
            GradientDrawable drawable = (GradientDrawable) bg;
            rememberOriginal(view, drawable.getColor());
            drawable.setColor(argb);
            // 指示块就是 indicatorView 本身，底色直接挂在它身上
            Object indicator = XposedHelpers.getObjectField(view, FIELD_INDICATOR_VIEW);
            if (indicator instanceof View) {
                ((View) indicator).setBackground((Drawable) drawable);
            }
            note(view, true);
            sApplyCount++;
        } catch (Throwable t) {
            logOnce("applyThreeStage failed: " + t);
        }
    }

    // ------------------------------------------------------------------ 还原

    /** 回到「跟随系统」：把第一次覆盖前记下的系统色写回色块。 */
    private static void restore(final View view, final String shape, final Context ctx) {
        Integer original = sOriginalColors.get(view);
        if (original == null) {
            return;
        }
        try {
            Object drawable = isThreeStage(shape)
                    ? XposedHelpers.callMethod(view, METHOD_GET_INDICATOR_BG)
                    : XposedHelpers.callMethod(view, METHOD_GET_MASK_BG);
            if (!(drawable instanceof GradientDrawable)) {
                return;
            }
            GradientDrawable gradient = (GradientDrawable) drawable;
            if (gradient.getColor() != null
                    && gradient.getColor().getDefaultColor() == original.intValue()) {
                return;
            }
            gradient.setColor(original.intValue());
            if (isThreeStage(shape)) {
                Object indicator = XposedHelpers.getObjectField(view, FIELD_INDICATOR_VIEW);
                if (indicator instanceof View) {
                    ((View) indicator).setBackground((Drawable) gradient);
                }
            } else {
                XposedHelpers.callMethod(view, METHOD_SET_BACKGROUND, gradient);
            }
            XposedBridge.log(TAG + ": restored system icon color for " + shape);
        } catch (Throwable t) {
            logOnce("restore failed: " + t);
        }
    }

    private static boolean isThreeStage(final String shape) {
        return TileLongPressConfig.SHAPE_THREE.equals(shape);
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 样式面板改完图标底色后，把已经画出来的色块重刷一遍（主线程调用）。
     *
     * @return 实际刷到的色块数量
     */
    public static int refresh(final String shape) {
        if (shape == null || !TileLongPressConfig.supportsIconColor(shape)) {
            return 0;
        }
        int n = 0;
        java.util.List<View> targets = new java.util.ArrayList<View>();
        synchronized (sViews) {
            targets.addAll(sViews.keySet());
        }
        for (View view : targets) {
            Boolean active = sViews.get(view);
            if (view == null || active == null) {
                continue;
            }
            // 形状现场重判一次：同一块视图换过形状（编辑面板预览）时不会刷错
            if (!shape.equals(TileLongPressInterceptor.shapeOfView(view))) {
                continue;
            }
            if (isThreeStage(shape)) {
                applyThreeStage(view, active);
            } else {
                applyIconView(view, active);
            }
            n++;
        }
        XposedBridge.log(TAG + ": refreshed shape=" + shape + ", iconViews=" + n);
        return n;
    }

    /** 调试用：覆盖过多少次。 */
    public static int applyCount() {
        return sApplyCount;
    }

    // ------------------------------------------------------------------ 工具

    private static void rememberOriginal(final View view, final ColorStateList csl) {
        if (sOriginalColors.containsKey(view) || csl == null) {
            return;
        }
        sOriginalColors.put(view, Integer.valueOf(csl.getDefaultColor()));
    }

    private static void note(final View view, final boolean active) {
        sViews.put(view, Boolean.valueOf(active));
    }

    /**
     * 在<b>指定类自己声明</b>的方法上挂钩子。
     *
     * <p>不能用 {@code findAndHookMethod} 直接找：它找不到时会往父类走，
     * 一旦走到 {@code QSIconView} 这种公共父类，就等于把整套图标视图都钩了。</p>
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

    /** 同一条日志只打一次，避免每帧刷屏。 */
    private static final java.util.Set<String> sLogged =
            Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void logOnce(final String message) {
        if (sLogged.add(message)) {
            XposedBridge.log(TAG + ": " + message);
        }
    }
}
