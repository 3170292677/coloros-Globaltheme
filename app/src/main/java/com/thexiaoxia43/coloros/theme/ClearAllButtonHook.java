package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 通知栏底部那颗「清除全部」圆形按钮（叉）的底图替换。
 *
 * <p>它的背景不是随便 {@code setBackground} 一次就完事：{@code ClearAllController}
 * 每次下拉都会重设一遍，而且中间还夹着一层平台模糊代理：</p>
 * <pre>
 * ClearAllController.updateClearAllBackground()
 *   └─ getPlatformBlurDrawable(drawable)          ← 我们的落点
 *        ├─ GaussBlurUtils.isGaussBlurDisabled()  为真 → 直接返回原 drawable
 *        ├─ ScrimUtil.isLowGaussianLevel(ctx)     为真 → 直接返回原 drawable
 *        ├─ ScrimControllerEx.isThirdTheme        为真 → 直接返回原 drawable
 *        └─ viewBlurManager.requireBlurProxyForView(clearAll, DECORATORS, 0.1f)
 *             → applyBlurConfig() → getBlurDrawable(null)
 *             → new MaskBlurDrawable(blurDrawable, ctx.getColor(...))
 *   └─ clearAll.setBackground(结果)
 * </pre>
 *
 * <p>所以钩 {@code getPlatformBlurDrawable} 的返回值一处即可覆盖<b>全部</b>分支，
 * 包括每次下拉的那次重设；同时完全绕开模糊代理层——历史教训是给这颗按钮
 * {@code requireBlurProxyForView} 加代理层会把它整个弄没。</p>
 *
 * <p>长按入口也在这里挂：按钮是 {@code androidx.appcompat.widget.AppCompatImageView}
 * 子类，系统只给它设了点击监听，长按位是空的，我们补一个（开关默认关，
 * 关着时返回 false，等于没接管）。</p>
 */
public final class ClearAllButtonHook {

    private static final String TAG = "SystemUIHook/ClearAll";

    private static final String CLASS_CONTROLLER =
            "com.oplus.systemui.notification.clearall.ClearAllController";
    private static final String CLASS_BUTTON =
            "com.oplus.systemui.notification.clearall.OplusClearAllButton";

    private static final String METHOD_GET_BLUR_DRAWABLE = "getPlatformBlurDrawable";
    private static final String METHOD_UPDATE_BACKGROUND = "updateClearAllBackground";
    private static final String FIELD_CLEAR_ALL = "clearAll";

    /** 最近的控制器（弱引用）：刷新时靠它重跑一遍系统那条设背景的链路。 */
    private static volatile WeakReference<Object> sController = new WeakReference<Object>(null);

    private static Class<?> sButtonClass;

    private ClearAllButtonHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        sButtonClass = XposedHelpers.findClassIfExists(CLASS_BUTTON, lpparam.classLoader);
        int n = hookBlurDrawable(lpparam);
        n += hookUpdateBackground(lpparam);
        n += hookButtonConstructors();
        XposedBridge.log(TAG + ": installed (hooks=" + n + ")");
    }

    // ------------------------------------------------------------ 底图落点

    private static int hookBlurDrawable(final LoadPackageParam lpparam) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLASS_CONTROLLER, lpparam.classLoader);
        if (cls == null) {
            XposedBridge.log(TAG + ": " + CLASS_CONTROLLER + " not found");
            return 0;
        }
        try {
            // 在原方法之后改返回值：系统的模糊 / 低高斯 / 三方主题三个分支各有各的
            // 返回结果，钩在返回值上就一处全覆盖；顺带能对系统 drawable 改可见度
            XposedHelpers.findAndHookMethod(cls, METHOD_GET_BLUR_DRAWABLE,
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            overrideDrawable(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + METHOD_GET_BLUR_DRAWABLE + " failed - " + t);
            return 0;
        }
    }

    private static void overrideDrawable(final XC_MethodHook.MethodHookParam param) {
        try {
            Context ctx = contextOf(param.thisObject);
            if (ctx == null) {
                return;
            }
            Drawable result = param.getResult() instanceof Drawable
                    ? (Drawable) param.getResult() : null;
            TileLongPressConfig.Style style = TileLongPressConfig.styleOf(
                    ctx, TileLongPressConfig.SHAPE_CLEAR);
            int alphaPct = style != null
                    ? style.alphaPct(true) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
            String base64 = TileLongPressConfig.tileBgImage(
                    ctx, TileLongPressConfig.SHAPE_CLEAR, true);
            Bitmap bitmap = base64.length() > 0
                    ? TileBackgroundHook.decodeShapeImage(base64) : null;

            if (bitmap != null) {
                // 负半径 = 按短边一半画正圆，跟按钮本体的圆形裁剪对齐
                float radiusPx = TileLongPressConfig.cornerRadiusPx(
                        ctx, TileLongPressConfig.SHAPE_CLEAR);
                param.setResult(new TileBackgroundDrawable(
                        HookPrefs.tileBackgroundColor(), radiusPx, bitmap, alphaPct));
                XposedBridge.log(TAG + ": clear-all background replaced, alpha=" + alphaPct
                        + "% image=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                return;
            }
            // 没选底图：只把可见度落在系统自己的 drawable 上，形状与模糊都不动
            if (result != null && alphaPct != TileLongPressConfig.DEFAULT_ALPHA_PCT) {
                result.setAlpha(alphaPct * 255 / 100);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": override drawable failed - " + t);
        }
    }

    // -------------------------------------------------------------- 长按入口

    private static int hookUpdateBackground(final LoadPackageParam lpparam) {
        Class<?> cls = XposedHelpers.findClassIfExists(CLASS_CONTROLLER, lpparam.classLoader);
        if (cls == null) {
            return 0;
        }
        try {
            // 每次下拉都会走到这里（协程也会重设），顺手把长按监听补上，
            // 免得系统哪次把监听清掉后就再也弹不出样式面板
            XposedHelpers.findAndHookMethod(cls, METHOD_UPDATE_BACKGROUND,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sController = new WeakReference<Object>(param.thisObject);
                            attachFromController(param.thisObject);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + METHOD_UPDATE_BACKGROUND + " failed - " + t);
            return 0;
        }
    }

    /**
     * 在按钮<b>构造</b>时就挂上长按监听。
     *
     * <p>{@code updateClearAllBackground()} 只在有通知、按钮真被画出来时才会走到，
     * 只靠那一条路的话，用户第一次长按很可能落在「监听还没挂上」的空档里
     * （表现为长按没反应）。构造钩子保证监听一定挂上，后面那条路只是兜底重挂。</p>
     */
    private static int hookButtonConstructors() {
        Class<?> cls = sButtonClass;
        if (cls == null) {
            XposedBridge.log(TAG + ": " + CLASS_BUTTON + " not found, longPress unavailable");
            return 0;
        }
        try {
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        attachLongPress((View) param.thisObject);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook button constructors failed - " + t);
            return 0;
        }
    }

    /** 从控制器里取出按钮再挂长按监听。 */
    private static void attachFromController(final Object controller) {
        try {
            Object button = XposedHelpers.getObjectField(controller, FIELD_CLEAR_ALL);
            if (button instanceof View) {
                attachLongPress((View) button);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": attach longPress failed - " + t);
        }
    }

    private static void attachLongPress(final View view) {
        try {
            view.setLongClickable(true);
            view.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Context ctx = v.getContext();
                    if (!TileLongPressConfig.isBlocked(ctx, TileLongPressConfig.SHAPE_CLEAR)) {
                        // 开关关着：不接管，长按保持系统默认（什么都不做）
                        return false;
                    }
                    boolean shown = TileStylePopup.show(v, TileLongPressConfig.SHAPE_CLEAR,
                            true, null);
                    XposedBridge.log(TAG + ": clear-all longPress → "
                            + (shown ? "BLOCKED" : "panel failed, pass"));
                    return shown;
                }
            });
            XposedBridge.log(TAG + ": longPress attached to " + view.getClass().getSimpleName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": attach longPress failed - " + t);
        }
    }

    // ------------------------------------------------------------------ 刷新

    /**
     * 样式面板改完清除按钮的底图后重刷一遍（主线程调用）。
     *
     * <p>重跑系统的 {@code updateClearAllBackground()} 而不是自己设背景：
     * 清除底图时要能<b>还原</b>系统原本的模糊底，只有让系统那条链路再走一遍
     * 才拿得到（{@link #overrideDrawable} 会在返回值上按当前配置决定改不改）。</p>
     *
     * @return 刷到的按钮数量
     */
    public static int refresh() {
        Object controller = sController.get();
        if (controller == null) {
            return 0;
        }
        try {
            XposedHelpers.callMethod(controller, METHOD_UPDATE_BACKGROUND);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": refresh failed - " + t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 被按视图是不是「清除全部」按钮（或其内部视图）。 */
    public static boolean isInsideClearAll(final View view) {
        Class<?> cls = sButtonClass;
        if (view == null || cls == null) {
            return false;
        }
        View current = view;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (cls.isInstance(current)) {
                return true;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** 从控制器拿上下文：它自己的 {@code context} 字段是私有的，借按钮的上下文更稳。 */
    private static Context contextOf(final Object controller) {
        try {
            Object button = XposedHelpers.getObjectField(controller, FIELD_CLEAR_ALL);
            if (button instanceof View) {
                return ((View) button).getContext();
            }
        } catch (Throwable ignored) {
        }
        try {
            Object ctx = XposedHelpers.getObjectField(controller, "context");
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
