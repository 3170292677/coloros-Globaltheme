package com.thexiaoxia43.coloros.theme;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 通知卡片<b>侧滑露出的图标</b>（齿轮「设置」/ 垃圾桶「删除」）那层底的淡出修正。
 *
 * <p><b>现象</b>：卡片调成半透明之后，侧滑露出的这两颗圆图标底下的那圈灰底
 * 会在卡片淡出之后还赖着不走（「延迟消失」）。卡片不透明时它被卡片挡着看不见，
 * 一旦底图半透明就露出来了。</p>
 *
 * <p><b>根因</b>（反编译 {@code probe_menu.txt} / {@code probe_blur_alpha.txt} 取证）：
 * 这两颗图标的底不是普通 drawable，而是平台模糊造出来的一层玻璃：</p>
 * <pre>
 * NotificationMenuRowExtImpl#updateMenuBlurDrawable(reason)
 *   └─ ViewBlurManager.requireBlurProxyForView(view)   // 给图标 View 建模糊代理
 *        → proxy.getBlurDrawable(null)                 // 平台模糊 drawable
 *        → new MaskBlurDrawable(blurDrawable, ctx.getColor(2131102913))   ← 落点
 *   └─ view.setBackground(MaskBlurDrawable)
 * </pre>
 * <p>菜单淡出时系统只调 {@code View#setAlpha}（{@code NotificationMenuRowExtImpl#setMenuViewAlpha}
 * 里就一句 {@code setAlpha}，没有任何背景 drawable 的 alpha 同步）。但
 * {@code MaskBlurDrawable#draw} 画完模糊之后还有一句 {@code canvas.drawColor(maskColor)}
 * ——<b>那圈灰底是它直接刷上去的，{@code setAlpha} 只转发给内层模糊 drawable，
 * 压根管不到这层色</b>。于是模糊跟着淡、灰底不跟着淡，观感就是「卡片都没了它还留着」。</p>
 *
 * <p><b>修法</b>：给 {@code MaskBlurDrawable#draw} 套一层「按宿主 View 当前 alpha 整体缩放」
 * 的画布图层——模糊和灰底一起按同一个进度淡；宿主已经淡到 0（或整条已经不可见）时
 * 干脆整笔不画，从根上不留残影。</p>
 *
 * <p><b>怎么拿到宿主 View</b>：{@code MaskBlurDrawable} 自己不记宿主，但它包的那张模糊
 * drawable 是 {@code ViewBlurProxy#getBlurDrawable} 的返回值，而代理身上存着宿主 View。
 * 所以在 {@code getBlurDrawable} 的返回值上记一笔「这张图是谁的」，绘制时按内层 drawable 反查即可。</p>
 *
 * <p>这一条同时覆盖侧滑菜单的两套实现（{@code NotificationMenuRowExtImpl} 与
 * {@code OplusCustomMenuRow}），它们造底的调用链是同一套。</p>
 */
public final class NotificationMenuHook {

    private static final String TAG = "SystemUIHook/NotificationMenu";

    /** 造模糊代理的类，代理身上存着宿主 View。 */
    private static final String CLASS_VIEW_BLUR_PROXY =
            "com.oplusos.systemui.common.blurability.ViewBlurProxy";
    /** 取模糊 drawable 的入口（返回值就是被 MaskBlurDrawable 包住的那张）。 */
    private static final String METHOD_GET_BLUR_DRAWABLE = "getBlurDrawable";
    /** 代理上指向宿主 View 的字段。 */
    private static final String FIELD_PROXY_VIEW = "view";

    /** 侧滑图标那层「模糊 + 灰底」的壳，灰底就是它刷的。 */
    private static final String CLASS_MASK_BLUR_DRAWABLE =
            "com.oplusos.systemui.common.blurability.drawable.MaskBlurDrawable";
    /** 壳里包着的那张模糊 drawable，用它反查宿主 View。 */
    private static final String FIELD_INNER_DRAWABLE = "drawable";

    /** 淡到多少以下就当「已经没了」，整笔不画。 */
    private static final float ALPHA_GONE = 0.004f;
    /** 淡到多少以上就不必套图层，交给系统原样画（避免每帧多开一层）。 */
    private static final float ALPHA_OPAQUE = 0.996f;

    /** 被模糊代理造出来的 drawable → 它的宿主 View（弱引用，通知划掉后自动松手）。 */
    private static final Map<Drawable, WeakReference<View>> sHostOfBlur =
            Collections.synchronizedMap(new WeakHashMap<Drawable, WeakReference<View>>());

    /** 只打一次的日志（draw 每帧都可能走到，不能每次都写）。 */
    private static final java.util.Set<String> sLogged = new java.util.HashSet<String>();

    /**
     * 本次绘制压进去的图层句柄，{@code draw} 前压、{@code draw} 后弹。
     *
     * <p>用 {@link ThreadLocal} 存是因为 {@code Drawable#draw} 不会给我们留夹带参数的地方，
     * 而绘制就发生在记录显示列表的那条线程上，前后两钩必然同线程；堆栈是为了防
     * 万一出现嵌套绘制时把外层的句柄冲掉。</p>
     */
    private static final ThreadLocal<java.util.ArrayDeque<Integer>> savedLayers =
            new ThreadLocal<java.util.ArrayDeque<Integer>>() {
                @Override
                protected java.util.ArrayDeque<Integer> initialValue() {
                    return new java.util.ArrayDeque<Integer>();
                }
            };

    private NotificationMenuHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        int n = hookBlurDrawable(lpparam);
        n += hookMaskDraw(lpparam);
        XposedBridge.log(TAG + ": installed (hooks=" + n + ")");
    }

    /**
     * 记下「这张模糊图是给哪个 View 造的」。
     *
     * <p>钩返回值而不是别处：不管上层怎么包（{@code MaskBlurDrawable} 也好、
     * 别的壳也好），只要是这个代理造出来的模糊，都能对上号。</p>
     */
    private static int hookBlurDrawable(final LoadPackageParam lpparam) {
        Class<?> proxy = XposedHelpers.findClassIfExists(
                CLASS_VIEW_BLUR_PROXY, lpparam.classLoader);
        if (proxy == null) {
            XposedBridge.log(TAG + ": " + CLASS_VIEW_BLUR_PROXY + " not found");
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(proxy, METHOD_GET_BLUR_DRAWABLE, Drawable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!(param.getResult() instanceof Drawable)) {
                                    return;
                                }
                                Object host = XposedHelpers.getObjectField(
                                        param.thisObject, FIELD_PROXY_VIEW);
                                if (host instanceof View) {
                                    sHostOfBlur.put((Drawable) param.getResult(),
                                            new WeakReference<View>((View) host));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + METHOD_GET_BLUR_DRAWABLE + " failed - " + t);
            return 0;
        }
    }

    /**
     * 把「模糊 + 灰底」那层壳按宿主 View 的当前 alpha 一起淡。
     *
     * <p>宿主完全淡完（或整条已经不可见）时直接跳过绘制——否则灰底那笔
     * {@code canvas.drawColor} 会一直留在屏幕上，这就是用户看到的「按键延迟消失」。</p>
     */
    private static int hookMaskDraw(final LoadPackageParam lpparam) {
        Class<?> mask = XposedHelpers.findClassIfExists(
                CLASS_MASK_BLUR_DRAWABLE, lpparam.classLoader);
        if (mask == null) {
            XposedBridge.log(TAG + ": " + CLASS_MASK_BLUR_DRAWABLE + " not found");
            return 0;
        }
        try {
            XposedHelpers.findAndHookMethod(mask, "draw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args.length > 0 && param.args[0] instanceof Canvas)) {
                        return;
                    }
                    try {
                        View host = hostOf(param.thisObject);
                        if (host == null) {
                            return;   // 不是我们认得的那层（比如磁贴自己的底图），一概不碰
                        }
                        float alpha = host.getAlpha();
                        if (!host.isShown() || alpha <= ALPHA_GONE) {
                            // 宿主已经不显示了：这一笔画了也是残影
                            param.setResult(null);
                            return;
                        }
                        if (alpha >= ALPHA_OPAQUE) {
                            return;   // 全亮，交给系统原样画
                        }
                        Canvas canvas = (Canvas) param.args[0];
                        Rect bounds = ((Drawable) param.thisObject).getBounds();
                        int save = canvas.saveLayerAlpha(bounds.left, bounds.top,
                                bounds.right, bounds.bottom, Math.round(alpha * 255f));
                        savedLayers.get().push(save);
                    } catch (Throwable t) {
                        logOnce("mask draw scale failed - " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    java.util.ArrayDeque<Integer> stack = savedLayers.get();
                    if (stack.isEmpty()) {
                        return;
                    }
                    try {
                        ((Canvas) param.args[0]).restoreToCount(stack.pop());
                    } catch (Throwable t) {
                        logOnce("mask draw restore failed - " + t);
                    }
                }
            });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook MaskBlurDrawable#draw failed - " + t);
            return 0;
        }
    }

    /** 这层壳包着的那张模糊图是谁造的 → 宿主 View。 */
    private static View hostOf(final Object maskDrawable) {
        Object inner = XposedHelpers.getObjectField(maskDrawable, FIELD_INNER_DRAWABLE);
        if (!(inner instanceof Drawable)) {
            return null;
        }
        WeakReference<View> ref = sHostOfBlur.get(inner);
        View host = ref != null ? ref.get() : null;
        return host != null && host.isAttachedToWindow() ? host : null;
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
