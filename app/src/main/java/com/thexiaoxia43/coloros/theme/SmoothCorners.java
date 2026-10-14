package com.thexiaoxia43.coloros.theme;

import android.graphics.Path;
import android.graphics.RectF;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ColorOS 的「连续圆角」（smooth round corner）落笔工具。
 *
 * <p><b>为什么必须用它。</b>系统的圆角不是正圆弧：</p>
 * <pre>
 * // COUIVerticalSeekBar#onDraw / SmoothCornersGradientDrawable#draw
 * if (weight != 0) new OplusPath(path).addSmoothRoundRect(l, t, r, b, rx, ry, weight, CCW);
 * else             path.addRoundRect(rect, rx, ry, CCW);
 * </pre>
 * <p>连续圆角和正圆弧在角上<b>不是同一条曲线</b>：同一个半径下两条线一个鼓一个凹，
 * 半径调小就突出来、调大就凹进去，只在某个「等效半径」处相交。所以只要用
 * {@link Path#addRoundRect}，无论把半径调成多少，圆角都跟系统差一点点——
 * 这就是「怎么改圆角都突出来 / 凹进去」的根因。</p>
 *
 * <p><b>权重从哪来。</b>{@code QsViewOutlineProvider#refreshSmoothRound} 最终会把
 * {@code OplusQsSmoothRoundUtil#getRoundParams} 里的半径和权重落到磁贴 drawable 上：</p>
 * <pre>
 * SmoothCornersGradientDrawable#setSmoothRound(radius, weight)
 * </pre>
 * <p>这里就 hook 那个 setter 把系统真正在用的权重截下来，我们的底图用同一个值落笔，
 * 角上才能像素级重合。拿不到时用 {@link #DEFAULT_WEIGHT}。</p>
 *
 * <p>{@code com.oplus.graphics.OplusPath} <b>不在 SystemUI 的 dex 里</b>（平台 framework），
 * 只能反射拿；拿不到就退回正圆弧。</p>
 */
final class SmoothCorners {

    private static final String TAG = "SystemUIHook/Smooth";

    /** 系统的连续圆角工具类；平台 framework 类，不在 APK 里。 */
    static final String CLASS_OPLUS_PATH = "com.oplus.graphics.OplusPath";

    /** 系统磁贴背景 drawable，权重就是从它的 {@code setSmoothRound} 上截的。 */
    static final String CLASS_SMOOTH_DRAWABLE =
            "com.oplusos.systemui.common.view.SmoothCornersGradientDrawable";

    /**
     * 还没抓到系统权重时的兜底值。
     *
     * <p>{@code SmoothCornersGradientDrawable#draw} 自己在动画态写死的就是 1.1f
     * （{@code const v0, 1066192077}），用同一个值不会跑偏。</p>
     */
    private static final float DEFAULT_WEIGHT = 1.1f;

    private static final RectF sRect = new RectF();

    private static ClassLoader sLoader;
    private static Constructor<?> sCtor;
    private static Method sAddSmoothRoundRect;
    private static boolean sResolved;
    private static boolean sWeightHooked;
    private static boolean sLogged;

    /** 系统真正传给磁贴 drawable 的连续圆角权重；0 = 还没抓到。 */
    private static volatile float sWeight;

    private SmoothCorners() {
    }

    /** 记下 SystemUI 的类加载器（解析 {@code OplusPath} 用），并挂上权重探针。 */
    static void install(final ClassLoader loader) {
        sLoader = loader;
        hookWeight(loader);
    }

    /**
     * 系统磁贴背景用的连续圆角权重。
     *
     * <p>拿不到就用 {@link #DEFAULT_WEIGHT}：权重为 0 会退回正圆弧，那又回到
     * 「怎么调都对不齐」的老问题上了。</p>
     */
    static float weight() {
        float weight = sWeight;
        return weight > 0f ? weight : DEFAULT_WEIGHT;
    }

    /** {@code OplusPath} 解析得到没有（解析不到就画不出连续圆角）。 */
    static boolean available() {
        return resolve();
    }

    /**
     * 把一块矩形装成系统同款连续圆角（{@link Path.Direction#CW}）。
     *
     * <p>半径 {@code <= 0} 时按直角矩形处理，跟系统
     * （{@code radius <= 0 -> Path#addRect}）一致。</p>
     *
     * @return 是否用上了连续圆角；false 表示已退回正圆弧
     */
    static boolean buildPath(final Path out, final float left, final float top,
            final float right, final float bottom, final float radius,
            final float weight) {
        out.reset();
        if (radius <= 0f) {
            sRect.set(left, top, right, bottom);
            out.addRect(sRect, Path.Direction.CW);
            return false;
        }
        if (weight > 0f && resolve()) {
            try {
                Object smooth = sCtor.newInstance(out);
                sAddSmoothRoundRect.invoke(smooth, left, top, right, bottom,
                        radius, radius, weight, Path.Direction.CW);
                return true;
            } catch (Throwable t) {
                logOnce("smooth round rect failed - " + t);
            }
        }
        sRect.set(left, top, right, bottom);
        out.addRoundRect(sRect, radius, radius, Path.Direction.CW);
        return false;
    }

    /** 解析 {@code com.oplus.graphics.OplusPath}，只试一次。 */
    private static boolean resolve() {
        if (sAddSmoothRoundRect != null) {
            return true;
        }
        if (sResolved) {
            return false;
        }
        sResolved = true;
        try {
            Class<?> clazz = null;
            if (sLoader != null) {
                try {
                    clazz = XposedHelpers.findClass(CLASS_OPLUS_PATH, sLoader);
                } catch (Throwable ignored) {
                    // 换 Class.forName 再试一次
                }
            }
            if (clazz == null) {
                clazz = Class.forName(CLASS_OPLUS_PATH);
            }
            sCtor = clazz.getConstructor(Path.class);
            sCtor.setAccessible(true);
            sAddSmoothRoundRect = clazz.getMethod("addSmoothRoundRect",
                    float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, Path.Direction.class);
            sAddSmoothRoundRect.setAccessible(true);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": OplusPath unavailable, fallback to arc - " + t);
            sCtor = null;
            sAddSmoothRoundRect = null;
            return false;
        }
    }

    /**
     * 把系统给磁贴 drawable 设的连续圆角权重截下来。
     *
     * <p>只记 {@code weight > 0} 的调用：1×1 正圆走的是
     * {@code setSmoothRound(高度/2, 0)}、直角走 {@code setSmoothRound(0, 0)}，
     * 那两种的权重是 0，记下来会把真正的权重冲掉。</p>
     */
    private static void hookWeight(final ClassLoader loader) {
        if (sWeightHooked) {
            return;
        }
        sWeightHooked = true;
        try {
            Class<?> clazz = XposedHelpers.findClass(CLASS_SMOOTH_DRAWABLE, loader);
            XposedHelpers.findAndHookMethod(clazz, "setSmoothRound",
                    float.class, float.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            float weight = (Float) param.args[1];
                            if (weight > 0f && weight != sWeight) {
                                sWeight = weight;
                                XposedBridge.log(TAG + ": system smooth weight = " + weight);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setSmoothRound failed - " + t);
        }
    }

    private static void logOnce(final String message) {
        if (sLogged) {
            return;
        }
        sLogged = true;
        XposedBridge.log(TAG + ": " + message);
    }
}
