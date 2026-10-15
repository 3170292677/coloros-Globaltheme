package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
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
 * 「磁贴长按选项替换」的执行层：按<b>磁贴形状</b>拦截长按，并弹出该形状的样式面板。
 *
 * <p>ColorOS 16 的磁贴长按链路（反编译 {@code classes3.dex} / {@code classes6.dex} 得到）：</p>
 * <pre>
 * OplusQSResizeableTileView$init$tileLongClick$1.onLongClick(View)   ← 视图层（编辑面板）
 * OplusQSTileBaseView$$ExternalSyntheticLambda5.onLongClick(View)    ← 视图层（主面板）
 *   └─ QSTile.longClick(Expandable)
 *        └─ QSTileImpl.longClick(Expandable)          ← 埋点/统计，然后 sendToTarget
 *             └─ QSTileImpl.handleLongClick(Expandable)
 *                  └─ getLongClickIntent() + mActivityStarter
 *                       .postStartActivityDismissingKeyguard(intent, 0)   ← 真正“打开应用”
 * </pre>
 *
 * <p>所以拦截点选 {@code QSTileImpl.longClick}：它在最上游，吞掉之后既不会启动
 * Activity，也不会留下统计副作用；{@code handleLongClick} 再兜一层，
 * 防止有别的路径直接发消息过来。吞掉的同时弹出 {@link TileStylePopup}
 * （圆角 / 可见度 / 底图），同一形状共用一份配置。</p>
 *
 * <p>形状判定：主面板所有形状共用 {@code OplusQSTileViewImpl}，形状不挂在类上，
 * 只能按背景视图（{@code getBackgroundView()}，即磁贴的圆形/卡片本体）的实测宽度
 * 除以 1×1 单元宽度推算跨列数。编辑面板的 {@code OplusQSResizeableTileView*}
 * 则直接由类名给出形状。</p>
 *
 * <p>视图绑定磁贴时缓存 {@code spec → 形状} 与 {@code spec → 视图}（弱引用，弹窗要用它
 * 做 anchor），长按时按 spec 反查。形状刚确定时会触发一次底图刷新，让
 * {@link TileBackgroundHook} 能按形状套用样式。</p>
 */
public final class TileLongPressInterceptor {

    private static final String TAG = "SystemUIHook/LongPress";

    private static final String CLASS_TILE_BASE_VIEW =
            "com.oplus.systemui.qs.base.tile.OplusQSTileBaseView";
    private static final String CLASS_TILE_IMPL =
            "com.android.systemui.qs.tileimpl.QSTileImpl";
    private static final String CLASS_EXPANDABLE =
            "com.android.systemui.animation.Expandable";
    private static final String CLASS_RESIZEABLE_TILE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView";
    private static final String CLASS_THREE_STAGE_LAYOUT =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout";
    private static final String CLASS_TILE_SIZE_HELPER =
            "com.oplus.systemui.qs.helper.OplusQSTileSizeHelper";

    private static final String FIELD_TILE_SPEC = "mTileSpec";
    private static final String FIELD_CONTEXT = "mContext";
    private static final String FIELD_ACTIVITY_STARTER = "mActivityStarter";

    /**
     * 媒体面板视图的类名特征。
     *
     * <p>2×2 媒体卡片（{@code OplusQsLargeMediaPanelView} 一族）和它下面那排按钮
     * 走的是自己的面板视图，长按<b>不经过</b> {@code QSTileImpl}，
     * 挂在 tile 上的钩子永远收不到，只能在视图层按类名找出来。</p>
     */
    private static final String[] MEDIA_PANEL_CLASSES = {
            "MediaPanel", "OplusQsMedia",
    };

    /**
     * 这个类名算不算「网格里那张 2×2 媒体卡片」。
     *
     * <p>匹配用的是 {@code contains}，会连<b>媒体详情对话框</b>
     * （{@code OplusQsMediaDetailPanelDialog}）一起命中。那个对话框是整屏
     * {@code ConstraintLayout}（{@code LaunchableConstraintLayout}），没有
     * {@code getBackgroundView()}，底图落点只能退回 {@code backgroundCarrier} 的兜底，
     * 而它的子视图里没有「铺满对话框」的一层，兜底就直接返回对话框自己——
     * 于是 2×2 的底图被设到<b>整屏</b>的对话框上、放大铺满整个界面，
     * 表现就是「打开媒体播放时整块背景变成那张底图」。</p>
     *
     * <p>Detail / Dialog 这一类不是网格里的媒体卡片，直接排除。</p>
     */
    private static boolean isMediaPanelClass(final String className) {
        for (String key : MEDIA_PANEL_CLASSES) {
            if (!className.contains(key)) {
                continue;
            }
            return !className.contains("Detail") && !className.contains("Dialog");
        }
        return false;
    }

    /** 视图类名后缀 → 形状。 */
    private static final String[][] CLASS_SHAPE = {
            {"OplusQSResizeableThreeStageView", TileLongPressConfig.SHAPE_THREE},
            {"OplusQSResizeableTileViewOneXOne", TileLongPressConfig.SHAPE_1X1},
            {"OplusQSResizeableTileViewTwoXOne", TileLongPressConfig.SHAPE_2X1},
            {"OplusQSResizeableTileViewTwoXTwo", TileLongPressConfig.SHAPE_2X2},
    };

    /** 磁贴 spec → 形状。视图每次绑定磁贴时刷新。 */
    private static final Map<String, String> sSpecShape =
            Collections.synchronizedMap(new HashMap<String, String>());

    /** 磁贴 spec → 磁贴视图（弱引用）。长按弹样式面板时用它当 anchor。 */
    private static final Map<String, WeakReference<View>> sSpecView =
            Collections.synchronizedMap(new HashMap<String, WeakReference<View>>());

    /** 最近布局过的磁贴视图，spec 反查不到时兜底当 anchor。 */
    private static WeakReference<View> sLastAnchor = new WeakReference<View>(null);

    /** 用户实际长按的那块磁贴视图（见 {@link #hookPressedView}）。 */
    private static volatile WeakReference<View> sPressedView = new WeakReference<View>(null);

    /** {@link #sPressedView} 记下的时刻（{@code uptimeMillis}），用来判断它是不是「这次按下」的。 */
    private static volatile long sPressedAt;

    /** {@link #sPressedView} 超过这么久就当过期：视图层没记到本次按压时不能拿旧的凑。 */
    private static final long PRESS_FRESH_MS = 1200L;

    /** 已解析过形状的 spec，用于避免日志刷屏。 */
    private static final Map<String, String> sLoggedShape =
            Collections.synchronizedMap(new HashMap<String, String>());

    /** 磁贴 spec → 上次生效的样式参数签名，用于判断底图要不要重刷。 */
    private static final Map<String, String> sSpecStyleSignature =
            Collections.synchronizedMap(new HashMap<String, String>());

    /** 判不出形状的 spec（只记一次，避免每帧刷屏）。 */
    private static final Set<String> sLoggedUnresolved =
            Collections.synchronizedSet(new HashSet<String>());

    /** 视图层兜底没认出形状时，上次打过日志的视图类名（同类只记一次，避免刷屏）。 */
    private static volatile String sLoggedPressedClass = "";

    /** 视图层判出形状但放行的日志去重键（视图类 + 形状）。 */
    private static final Set<String> sLoggedViewLevel =
            Collections.synchronizedSet(new HashSet<String>());

    /**
     * 「读不到 spec」的视图 → 形状：2×2 媒体面板，以及认不出类名的控制中心卡片。
     *
     * <p>这两类都进不了 {@link #sSpecView}（媒体面板没有 {@code mTileSpec}，
     * 设备空间 / 耳机这类 deviceplugin 卡片压根不是磁贴视图），样式改完后
     * {@link #refreshShape(String)} 就找不到它们——表现为「底图选了没生效」。
     * 所以按视图单独记一份（弱键，视图销毁后自动回收），刷新时一起处理。</p>
     */
    private static final Map<View, String> sExtraViews =
            Collections.synchronizedMap(new WeakHashMap<View, String>());

    /** 临时排查用：长按没被拦截时弹的提示，已提示过的文案不再重复。 */
    private static final Set<String> sNotified = Collections.synchronizedSet(new HashSet<String>());

    /** 临时排查用：提示条数上限，避免满屏都是。 */
    private static final int MAX_DIAG_TOASTS = 12;
    /** 临时排查用：弹提示要用的上下文与锚点视图（长按时顺手抓一份）。 */
    private static volatile Context sDiagCtx;
    private static volatile WeakReference<View> sDiagAnchor = new WeakReference<View>(null);

    /** 配置自检日志是否已打过。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sProbeLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static Class<?> sThreeStageLayoutClass;
    private static boolean sSizeHelperBroken;

    private TileLongPressInterceptor() {
    }

    public static void install(final LoadPackageParam lpparam) {
        Class<?> tileBaseView = XposedHelpers.findClassIfExists(
                CLASS_TILE_BASE_VIEW, lpparam.classLoader);
        Class<?> tileImpl = XposedHelpers.findClassIfExists(
                CLASS_TILE_IMPL, lpparam.classLoader);
        Class<?> expandable = XposedHelpers.findClassIfExists(
                CLASS_EXPANDABLE, lpparam.classLoader);
        sThreeStageLayoutClass = XposedHelpers.findClassIfExists(
                CLASS_THREE_STAGE_LAYOUT, lpparam.classLoader);

        int cached = hookShapeCache(lpparam, tileBaseView);
        int blocked = hookLongClick(tileImpl, expandable);
        int pressed = hookPressedView(lpparam);

        XposedBridge.log(TAG + ": interceptor installed (shapeCache=" + cached
                + ", blockHooks=" + blocked + ", pressedView=" + pressed + ")");
    }

    // ------------------------------------------------------------ 形状缓存

    /** 在视图布局/绑定磁贴时解析并缓存 spec → 形状。 */
    private static int hookShapeCache(LoadPackageParam lpparam, Class<?> tileBaseView) {
        int n = 0;

        if (tileBaseView != null) {
            // 主面板：布局完成后背景视图才有真实尺寸
            try {
                XposedHelpers.findAndHookMethod(tileBaseView, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // onLayout 每帧都会触发，已缓存过的 spec 直接跳过
                                View tileView = (View) param.thisObject;
                                cacheShape(tileView, null, false);
                                // 系统首次设底图发生在布局之前（那时宽度还是 0、判不出形状），
                                // 这里补一次，保证首屏就有底图；参数没变时内部会直接跳过
                                TileBackgroundHook.applyStyleDirect(tileView);
                            }
                        });
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook tileView.onLayout failed - " + t);
            }

            // 磁贴重新可见时（例如去相册选完底图回到控制中心、面板重新展开）
            // 检查该形状的样式有没有变，变了就立刻刷新底图，不用重启 SystemUI
            try {
                XposedHelpers.findAndHookMethod(tileBaseView, "onVisibilityChanged",
                        View.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object visibility = param.args == null || param.args.length < 2
                                        ? null : param.args[1];
                                if (visibility instanceof Integer
                                        && ((Integer) visibility).intValue() == View.VISIBLE) {
                                    View tileView = (View) param.thisObject;
                                    // 编辑面板退出后主面板磁贴会重新可见，这时要把 anchor 指回这块视图：
                                    // 编辑面板那套视图已经销毁，不刷新的话长按就取不到 anchor、弹不出面板
                                    cacheShape(tileView, null, false);
                                    refreshIfStyleChanged(tileView);
                                }
                            }
                        });
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook tileView.onVisibilityChanged failed - " + t);
            }
        }

        // 编辑面板：绑定磁贴时按视图类名判定
        Class<?> resizeable = XposedHelpers.findClassIfExists(
                CLASS_RESIZEABLE_TILE, lpparam.classLoader);
        if (resizeable != null) {
            try {
                XposedHelpers.findAndHookMethod(resizeable, "init",
                        "com.android.systemui.plugins.qs.QSTile",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                cacheShape((View) param.thisObject, param.args[0], true);
                            }
                        });
                n++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook resizeableTile.init failed - " + t);
            }
        }

        return n;
    }

    /**
     * 把视图与它绑定的磁贴 spec 记到缓存里，供长按拦截时判形状。
     *
     * @param force true 时无条件重新解析（视图绑定磁贴），false 时已缓存就跳过
     *              （视图布局，每帧都会触发）
     */
    private static void cacheShape(View tileView, Object tile, boolean force) {
        try {
            logPrefsProbeOnce(tileView.getContext());
            String spec = null;
            if (tile != null) {
                Object s = XposedHelpers.callMethod(tile, "getTileSpec");
                if (s instanceof String) {
                    spec = (String) s;
                }
            }
            if (spec == null || spec.length() == 0) {
                Object s = XposedHelpers.getObjectField(tileView, FIELD_TILE_SPEC);
                if (s instanceof String) {
                    spec = (String) s;
                }
            }
            if (spec == null || spec.length() == 0) {
                return;
            }

            // 视图每次布局都可能换实例，anchor 始终指向最新的那块
            // （编辑面板的临时视图除外，见 rememberAnchor）
            rememberAnchor(spec, tileView);

            // 编辑面板的三种形状预览共用同一个 spec，而且同时存在：
            // 谁最后 init 谁就把 sSpecShape 覆盖掉，主面板那边又因为
            // 「已缓存就跳过」永远纠不回来，结果 1×1 / 2×1 / 三阶段 全读到同一套底图
            // ——表现为「面板里写着有底图、磁贴上却不显示」（样式被写进了别的形状槽位）。
            // 所以编辑面板不写这个 spec 级缓存，它自己的形状由视图类名现算（见 shapeOfView）。
            if (isEditPanelView(tileView)) {
                return;
            }

            if (!force && sSpecShape.containsKey(spec)) {
                return;
            }

            String shape = resolveShape(tileView);
            if (shape == null) {
                if (tileView.getWidth() > 0 && tileView.getHeight() > 0
                        && sLoggedUnresolved.add(spec)) {
                    View bg = backgroundView(tileView);
                    XposedBridge.log(TAG + ": spec=" + spec + " shape unresolved (view="
                            + tileView.getWidth() + "x" + tileView.getHeight()
                            + ", bg=" + (bg == null ? "null" : bg.getWidth() + "x" + bg.getHeight())
                            + ", unit=" + unitWidthPx(tileView.getContext()) + ")");
                }
                return;
            }
            String previous = sSpecShape.put(spec, shape);
            if (shape.equals(previous)) {
                return;
            }

            String logged = sLoggedShape.get(spec);
            if (!shape.equals(logged)) {
                sLoggedShape.put(spec, shape);
                XposedBridge.log(TAG + ": spec=" + spec + " → shape=" + shape
                        + " (view=" + tileView.getClass().getSimpleName()
                        + ", w=" + tileView.getWidth()
                        + ", h=" + tileView.getHeight() + ")");
            }

            // 形状刚确定：底图需要按该形状的样式重刷一次
            sSpecStyleSignature.put(spec, styleSignature(tileView.getContext(), shape));
            TileBackgroundHook.refreshTileBackground(tileView);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 记住 spec 对应的磁贴视图，长按弹样式面板时用它当 anchor。
     *
     * <p>主面板和编辑面板是两套磁贴视图实例：进编辑面板时
     * {@code OplusQSResizeableTileView#init} 也会走到这里。如果无条件覆盖，保存退出后
     * 缓存里剩下的就是已被销毁的编辑面板视图，长按主面板磁贴取不到 anchor
     * （日志表现为 {@code no attached tile view}）——这正是「开着长按屏蔽、进过编辑面板
     * 再保存，之后长按弹不出面板」的原因。所以主面板视图优先：只有当前缓存的视图
     * 不可用时，编辑面板的视图才顶上。</p>
     */
    private static void rememberAnchor(final String spec, final View tileView) {
        if (spec == null || spec.length() == 0 || tileView == null) {
            return;
        }
        boolean editPanel = isEditPanelView(tileView);
        if (editPanel && isUsable(deref(sSpecView.get(spec)))) {
            // 主面板那块还在，别让编辑面板的临时视图顶掉它
            return;
        }
        sSpecView.put(spec, new WeakReference<View>(tileView));
        if (!editPanel) {
            // 编辑面板的视图退出后必被销毁，不能当兜底 anchor
            sLastAnchor = new WeakReference<View>(tileView);
        }
    }

    /**
     * 编辑面板的磁贴视图。
     *
     * <p>它们都在 {@code plugins.qs.customize} 包下（{@code OplusQSResizeableTileView*}
     * 与三阶段的 {@code OplusQSResizeableThreeStageView}），退出编辑面板后实例会被销毁。</p>
     */
    private static boolean isEditPanelView(final View view) {
        // 只看 Resizeable：编辑/缩放面板的预览视图是 OplusQSResizeableTileViewOneXOne /
        // TwoXOne / ThreeStageView 这一族。不能连 customize 一起匹配 ——
        // 主面板的 OplusCustomizeTileViewImpl 也在 qs.customize 包里，会被误伤。
        return view.getClass().getName().contains("Resizeable");
    }

    private static View deref(final WeakReference<View> ref) {
        return ref == null ? null : ref.get();
    }

    /** 判定磁贴形状，判不出返回 null。 */
    private static String resolveShape(View tileView) {
        String cn = tileView.getClass().getName();
        for (String[] pair : CLASS_SHAPE) {
            if (cn.endsWith(pair[0])) {
                return pair[1];
            }
        }

        // 三阶段卡片：一张卡里三个图标。标记可能出现在两处：
        //  · 编辑面板预览 OplusQSResizeableThreeStageView 内含 OplusQSThreeStageLayout（子树）
        //  · 主面板三阶段卡是 OplusQSTileViewImpl，三阶段布局 OplusQSThreeStageLayout 在它
        //    <b>上层（祖先）</b>——findTileView 命中的是那块磁贴视图本身，只查子树会漏判，
        //    落到尺寸判定被当成 2×1 宽卡片（表现为"长按三阶段被认成 2×1 / 上一次按的形状"）。
        // 所以向上（祖先）和向下（子树）都扫 "ThreeStage" 字样，覆盖两种面板。
        if (isThreeStageTile(tileView)) {
            return TileLongPressConfig.SHAPE_THREE;
        }

        // 主面板：所有形状共用 OplusQSTileViewImpl，形状只能按实测尺寸推算。
        //
        // 必须量<b>磁贴视图本身</b>（网格按跨列数给它布局），不能量
        // getBackgroundView() 即 mBg —— 系统在 updateIconResource() 里把 mBg 的
        // LayoutParams 固定成单格正方形 FrameLayout.LayoutParams(mBgSize, mBgSize, 17)，
        // 量它永远算出 1×1，2×1 宽卡片会被误判。
        int cell = unitWidthPx(tileView.getContext());
        int w = tileView.getWidth();
        int h = tileView.getHeight();
        if (cell <= 0 || w <= 0 || h <= 0) {
            return null;
        }
        int cols = Math.round(w / (float) cell);
        int rows = Math.round(h / (float) cell);
        String shape;
        if (cols >= 2 && rows >= 2) {
            shape = TileLongPressConfig.SHAPE_2X2;
        } else if (cols >= 2) {
            shape = TileLongPressConfig.SHAPE_2X1;
        } else {
            shape = TileLongPressConfig.SHAPE_1X1;
        }
        XposedBridge.log(TAG + ": resolveShape " + cn + " w=" + w + " h=" + h
                + " cell=" + cell + " cols=" + cols + " rows=" + rows + " -> " + shape);
        return shape;
    }

    private static View backgroundView(View tileView) {
        try {
            Object v = XposedHelpers.callMethod(tileView, "getBackgroundView");
            if (v instanceof View) {
                return (View) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 1×1 磁贴背景的像素宽度，作为推算跨列数的单元。 */
    private static int unitWidthPx(Context ctx) {
        if (ctx == null || sSizeHelperBroken) {
            return 0;
        }
        try {
            Class<?> helper = XposedHelpers.findClassIfExists(
                    CLASS_TILE_SIZE_HELPER, ctx.getClassLoader());
            if (helper == null) {
                sSizeHelperBroken = true;
                return 0;
            }
            Object px = XposedHelpers.callStaticMethod(helper, "getQsTileSize",
                    ctx, Integer.valueOf(0));
            if (px instanceof Integer) {
                return ((Integer) px).intValue();
            }
        } catch (Throwable t) {
            sSizeHelperBroken = true;
            XposedBridge.log(TAG + ": OplusQSTileSizeHelper unavailable - " + t);
        }
        return 0;
    }

    private static boolean containsChildOfClass(View v, Class<?> cls) {
        if (cls.isInstance(v)) {
            return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsChildOfClass(g.getChildAt(i), cls)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 这块磁贴视图是不是三阶段卡。
     *
     * <p>标记可能出现在两个方向：</p>
     * <ul>
     *   <li>向下（子树）：编辑面板预览 {@code OplusQSResizeableThreeStageView} 内含
     *       {@code OplusQSThreeStageLayout}；主面板三阶段卡若以
     *       {@code OplusQSThreeStageIconView} 当图标，也在子树里。</li>
     *   <li>向上（祖先）：主面板三阶段卡是 {@code OplusQSTileViewImpl}，三阶段布局
     *       {@code OplusQSThreeStageLayout} 在它<b>上层</b>，而 {@link #findTileView}
     *       命中的是那块磁贴视图本身，只查子树会漏判，所以这里往祖先方向也扫一遍。</li>
     * </ul>
     */
    private static boolean isThreeStageTile(final View tileView) {
        if (tileView == null) {
            return false;
        }
        if (containsChildOfClassNameContaining(tileView, "ThreeStage")) {
            return true;
        }
        View cur = tileView;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.getClass().getName().contains("ThreeStage")) {
                return true;
            }
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return sThreeStageLayoutClass != null
                && containsChildOfClass(tileView, sThreeStageLayoutClass);
    }

    /**
     * 视图树里是否存在<b>类名包含</b>给定子串的视图（宽匹配）。
     * 用于三阶段等按类名识别的形状——主面板三阶段卡的标记不一定是精确类
     * {@code OplusQSThreeStageLayout}（那是编辑面板的），但它的布局 / 图标视图类名里
     * 都带 "ThreeStage"，宽匹配即可覆盖。
     */
    private static boolean containsChildOfClassNameContaining(final View root, final String substr) {
        if (root == null || substr == null || substr.isEmpty()) {
            return false;
        }
        final java.util.Queue<View> queue = new java.util.ArrayDeque<View>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final View v = queue.poll();
            if (v != null) {
                final String cn = v.getClass().getName();
                if (cn != null && cn.contains(substr)) {
                    return true;
                }
                if (v instanceof ViewGroup) {
                    final ViewGroup g = (ViewGroup) v;
                    final int n = g.getChildCount();
                    for (int i = 0; i < n; i++) {
                        queue.add(g.getChildAt(i));
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 对外查询

    /**
     * 上机排查用：第一次拿到 Context 时，把关键配置打一条日志。
     *
     * <p>一次就能看出跨进程 prefs 读不读得到、底图有没有写进去、各形状的开关状态。</p>
     */
    private static void logPrefsProbeOnce(final Context ctx) {
        if (ctx == null || !sProbeLogged.compareAndSet(false, true)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String shape : TileLongPressConfig.ALL_SHAPES) {
                TileLongPressConfig.Style style = TileLongPressConfig.styleOf(ctx, shape);
                sb.append(shape)
                        .append(TileLongPressConfig.isBlocked(ctx, shape) ? "[blocked]" : "[-]")
                        .append(" corner=").append(style == null ? "?" : style.cornerDp)
                        .append(" alphaOn=").append(style == null ? "?" : style.alphaOnPct)
                        .append(" alphaOff=").append(style == null ? "?" : style.alphaOffPct)
                        .append(" imgOn=")
                        .append(TileLongPressConfig.tileBgImage(ctx, shape, true).length())
                        .append("chars imgOff=")
                        .append(TileLongPressConfig.tileBgImage(ctx, shape, false).length())
                        .append("chars  ");
            }
            XposedBridge.log(TAG + ": prefs probe → replaceTileBg="
                    + HookPrefs.replaceTileBackground()
                    + ", darkOnly=" + HookPrefs.tileBackgroundDarkOnly()
                    + ", forceAll=" + HookPrefs.tileBackgroundForceAll()
                    + ", anyShapeImage=" + TileLongPressConfig.anyShapeBgImage(ctx)
                    + " | " + sb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": prefs probe failed - " + t);
        }
    }

    /** 该形状已缓存了多少块磁贴视图（样式面板显示，用来判断形状判定有没有跑起来）。 */
    public static int viewCountOfShape(final String shape) {
        if (shape == null) {
            return 0;
        }
        int n = 0;
        synchronized (sSpecView) {
            for (Map.Entry<String, WeakReference<View>> entry : sSpecView.entrySet()) {
                if (shape.equals(sSpecShape.get(entry.getKey()))) {
                    n++;
                }
            }
        }
        // 没有 spec 的视图（媒体播放卡片、认不出类名的卡片）只记在 sExtraViews 里，
        // 漏掉它们的话这些形状在样式面板上恒显示 0 块，排查时会被误当成
        // 「一张都没认出来」，白白往形状判定那条错路上跑
        synchronized (sExtraViews) {
            for (Map.Entry<View, String> entry : sExtraViews.entrySet()) {
                if (shape.equals(entry.getValue())) {
                    n++;
                }
            }
        }
        return n;
    }

    /** spec → 形状；没缓存过返回 null（{@link TileBackgroundHook} 用它套用样式）。 */
    public static String shapeOf(final String spec) {
        if (spec == null) {
            return null;
        }
        String cached = sSpecShape.get(spec);
        if (cached != null) {
            return cached;
        }
        // sSpecShape 里可能没有这条 spec：本机主面板的磁贴类名带 "Resizeable"
        // （OplusQSResizeableTileView*），被 isEditPanelView 当成编辑面板视图，
        // cacheShape 就跳过了 spec→形状的写入（见那里的说明）。
        // 这里退回该 spec 记着的视图现算一次，让底图刷新那条通道（updateBackground /
        // refreshViewBackground）也能拿到形状——否则改了可见度 / 底图后刷新时会因为
        // shape 为 null 直接放弃，表现为「调了没反应，非得重启系统界面才生效」。
        View view = deref(sSpecView.get(spec));
        if (view == null) {
            return null;
        }
        String viewSpec = specFieldOf(view);
        if (viewSpec.length() > 0 && !viewSpec.equals(spec)) {
            // 视图被 RecyclerView 回收后重新 bind 成别的磁贴了，不能拿它算这条 spec 的形状
            return null;
        }
        return shapeOfView(view);
    }

    /**
     * 上机排查用：把这块磁贴的形状判定依据（磁贴实测尺寸 / 1×1 单元宽度）拼成一行。
     *
     * <p>形状判不出来时样式面板会把它显示出来，一次截图就能看出是尺寸还是单元宽度的问题。</p>
     */
    public static String sizeProbe(final View tileView) {
        if (tileView == null) {
            return "view=null";
        }
        return "cls=" + tileView.getClass().getSimpleName()
                + ", view=" + tileView.getWidth() + "x" + tileView.getHeight()
                + ", unit=" + unitWidthPx(tileView.getContext())
                + ", bg=" + (backgroundView(tileView) == null ? "null" : "ok");
    }

    /**
     * 从底图/图标这类子视图向上找磁贴视图，现场判定形状并补进缓存。
     *
     * <p>兜底用：底图 hook 侧解析出的 spec 与视图侧缓存的 spec 万一对不上，
     * 也能按形状把样式套上去。</p>
     */
    public static String shapeOfView(final View child) {
        try {
            // deviceplugin 卡片（耳机 / 设备空间）先认出来：它是独立的一张卡片，
            // 不能让它外面那层容器（编辑面板里可能是 OplusQSResizeableTileView*）
            // 抢先把它认成 1×1 圆形磁贴，那样底图和圆角都会跟圆形磁贴共用一套
            View devCard = devicePluginCardOf(child);
            if (devCard != null) {
                return devicePluginShapeOf(devCard);
            }
            View tileView = findTileView(child);
            if (tileView == null) {
                // 媒体播放卡片（2×2 那块大面板）不走磁贴视图那一族：
                // 它旁边的亮度 / 音量滑块会让实测尺寸只有「一半宽」，按尺寸现算会算成 2×1，
                // 底图这边就套错了形状，所以这里直接钉成 2×2
                if (findMediaPanel(child) != null) {
                    return TileLongPressConfig.SHAPE_2X2;
                }
                // 认不出类名的控制中心卡片（设备空间 / 耳机这类 deviceplugin 卡片）：
                // 按「在控制中心内 + 尺寸够一块磁贴」找出这张卡片，归到「方形卡片」组。
                // 它们没有 spec，进不了缓存，也就没法走下面那条 spec 链路。
                // 不能并进 1×1 圆形磁贴：那样底图和圆角都会跟圆形磁贴共用一套
                int cell = unitWidthPx(child.getContext());
                View card = cardAncestor(child, cell);
                return card == null ? null : TileLongPressConfig.SHAPE_CARD;
            }
            // spec 只用来补缓存，读不到不能影响形状结果：
            // OplusQSHighlightTileView / OplusQSResizeableTileView* 都没有 mTileSpec 字段，
            // 直接 getObjectField 会抛 NoSuchFieldError，被外层 catch 吞掉后整个形状就丢了
            // —— 这正是「尺寸量得到、形状却判不出」的原因。
            String spec = specFieldOf(tileView);
            String shape = resolveShape(tileView);
            if (shape == null && spec.length() > 0) {
                // 视图还没布局（宽度 0）或取不到 1×1 单元宽度时，回落到该 spec 缓存过的形状。
                // 不回落的话底图按形状改了圆角、磁贴 outline 却退回系统形状，两边就对不上
                // （表现为切深浅色后自定义底图的圆角和磁贴裁剪形状不一致）
                shape = sSpecShape.get(spec);
            }
            if (shape == null) {
                return null;
            }
            if (spec.length() > 0) {
                // 编辑面板的三种形状预览共用同一个 spec：把预览的形状写进 spec 级缓存，
                // 会把主面板那条记录盖掉（1×1 预览一刷，主面板的三阶段就变成圆形）。
                // 跟 cacheShape 一样，编辑面板不写这个缓存，它的形状由视图类名现算
                if (!isEditPanelView(tileView)) {
                    sSpecShape.put(spec, shape);
                }
                rememberAnchor(spec, tileView);
            }
            return shape;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 安全读视图上的 {@code mTileSpec}；视图没有这个字段时返回空串。
     *
     * <p>字段只声明在 {@code OplusQSTileBaseView} 上（反编译确认），
     * {@code OplusQSHighlightTileView}、{@code OplusQSResizeableTileView*}
     * 都是直接继承 {@code QSTileView} 的另一条支线，没有这个字段。</p>
     */
    private static String specFieldOf(final View tileView) {
        try {
            Object spec = XposedHelpers.getObjectField(tileView, FIELD_TILE_SPEC);
            return spec instanceof String ? (String) spec : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 沿视图树向上找磁贴视图（主面板 OplusQSTileViewImpl / 编辑面板 ResizeableTileView 等）。 */
    private static View findTileView(View child) {
        View current = child;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String cn = current.getClass().getName();
            // 必须用 Resizeable 而不是 OplusQSResizeableTileView：
            // 三阶段的预览类叫 OplusQSResizeableThreeStageView，不含 "OplusQSResizeableTileView"
            // 这个子串，漏掉它就会一路向上走、最后返回 null，
            // 长按三阶段时形状判不出来 → 拦截失效。
            if (cn.contains("QSTileBaseView") || cn.contains("QSTileView")
                    || cn.contains("Resizeable")
                    || cn.contains("HighlightTileView")) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 磁贴重新可见时，若该形状的样式（圆角 / 可见度 / 底图）与上次不同就刷新底图。
     *
     * <p>去相册选完底图回到控制中心时，SystemUI 这边没有回调可挂，只能靠面板重新
     * 展开带来的可见性变化来触发，这样不用重启 SystemUI 就能看到新底图。</p>
     */
    private static void refreshIfStyleChanged(final View tileView) {
        try {
            Context ctx = tileView.getContext();
            if (!HookPrefs.replaceTileBackground()
                    && !TileLongPressConfig.anyShapeBgImage(ctx)
                    && !TileLongPressConfig.anyStyle(ctx)) {
                return;
            }
            Object specObj = XposedHelpers.getObjectField(tileView, FIELD_TILE_SPEC);
            if (!(specObj instanceof String)) {
                return;
            }
            String spec = (String) specObj;
            String shape = sSpecShape.get(spec);
            if (shape == null) {
                return;
            }
            String signature = styleSignature(ctx, shape);
            if (signature.equals(sSpecStyleSignature.get(spec))) {
                return;
            }
            sSpecStyleSignature.put(spec, signature);
            XposedBridge.log(TAG + ": style changed, spec=" + spec + " shape=" + shape);
            TileBackgroundHook.refreshTileBackground(tileView);
        } catch (Throwable ignored) {
        }
    }

    /** 样式的参数签名，用来判断底图需不需要重刷。开 / 关两套都要算进去。 */
    private static String styleSignature(final Context ctx, final String shape) {
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(ctx, shape);
        int corner = style != null ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        int onAlpha = style != null ? style.alphaOnPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        int offAlpha = style != null ? style.alphaOffPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        return corner + "|" + onAlpha + "|" + offAlpha
                + "|" + imageSignature(TileLongPressConfig.tileBgImage(ctx, shape, true))
                + "|" + imageSignature(TileLongPressConfig.tileBgImage(ctx, shape, false));
    }

    /** 单张底图的签名（长度 + 哈希），空串表示没设置。 */
    private static String imageSignature(final String base64) {
        return base64.length() + ":" + base64.hashCode();
    }

    /**
     * 样式改完后刷新该形状所有已缓存的磁贴底图（主线程调用）。
     *
     * @return 实际刷新到的磁贴视图数量（0 表示该形状还没有磁贴被缓存过）
     */
    public static int refreshShape(final String shape) {
        if (shape == null) {
            return 0;
        }
        // 通知卡片不在磁贴那套缓存里（底图由 NotificationBackgroundView 自己画），
        // 刷新走它自己的入口：让已经画过的通知底图重画一遍
        if (TileLongPressConfig.SHAPE_NOTIFICATION.equals(shape)) {
            // 通知卡片在那一格里改的是「文字颜色」（存 TINT_LABEL），
            // 颜色一变要让已经显示的通知文字立刻跟上，所以先重贴文字再重画底图
            NotificationTextHook.refresh(null);
            return NotificationHook.refresh();
        }
        // 亮度 / 音量胶囊同理：底图由 OplusQsVerticalSeekBar 自己画，不在磁贴缓存里
        if (TileLongPressConfig.SHAPE_SLIDER.equals(shape)) {
            // 胶囊上还有两颗图标（亮度 / 音量）是另一条着色链路，也得跟着刷一遍；
            // 不刷的话「改了图标色要等下次亮屏才生效」，看着就像没生效
            TileColorHook.refresh(shape);
            return SliderHook.refresh();
        }
        // 「清除全部」按钮的底图由 ClearAllController 每次下拉重设，
        // 重刷得让系统那条链路再走一遍，见 ClearAllButtonHook
        if (TileLongPressConfig.SHAPE_CLEAR.equals(shape)) {
            return ClearAllButtonHook.refresh();
        }
        // 控制中心右上角那排小圆钮（★ / ✎ / ⚙ / ⋮）不是磁贴、不在下面的缓存里，
        // 样式由 QuickEntranceButtonHook 直接套在按钮视图上，所以这里直接交给它
        // （不能往下走：后面的 sSpecView / extras 都跟这排按钮无关）
        if (TileLongPressConfig.SHAPE_ENTRANCE.equals(shape)) {
            return QuickEntranceButtonHook.refresh();
        }
        // 媒体播放卡片与认不出类名的卡片都不在 sSpecView 里（读不到 mTileSpec），
        // 单独记着，最后跟磁贴视图一起套样式
        List<View> extras = extraViewsOf(shape);

        List<View> targets = new ArrayList<View>();
        synchronized (sSpecView) {
            for (Map.Entry<String, WeakReference<View>> entry : sSpecView.entrySet()) {
                WeakReference<View> ref = entry.getValue();
                View view = ref == null ? null : ref.get();
                if (view == null) {
                    continue;
                }
                // sSpecShape 里可能没有这个 spec 的形状：主面板的 2×1 / 三阶段磁贴
                // 类名带 "Resizeable"，被 isEditPanelView 当成编辑面板视图，
                // cacheShape 与 shapeOfView 都跳过了 spec→形状的写入（见那里的说明）。
                // 取不到就<b>现场算</b>——否则这里一块视图都找不到，改完圆角 / 底图
                // 一块都不刷新，只能重启系统界面（用户反馈的"改不了"）。
                String recorded = sSpecShape.get(entry.getKey());
                String actual = recorded != null ? recorded : shapeOfView(view);
                if (!shape.equals(actual)) {
                    continue;
                }
                targets.add(view);
            }
        }
        targets.addAll(extras);
        // 缓存的视图可能已经被 RecyclerView 回收换成别的实例（sSpecView 里那块早就
        // 不在屏幕上了），所以再从根视图把控制中心里<b>现挂着</b>的磁贴扫一遍。
        // 只扫，不去判：形状用 shapeOfView 现算，跟目标形状一致才算数。
        for (View root : rootsOfCachedViews()) {
            List<View> live = new ArrayList<View>();
            collectLiveTiles(root, shape, live);
            for (View view : live) {
                if (!targets.contains(view)) {
                    targets.add(view);
                }
            }
        }
        // 必须走 applyStyleDirect（形状已知，直接套上去），不能走 refreshTileBackground：
        // 后者要重新从视图上读 mTileSpec，而本机主面板磁贴是 OplusQSResizeableTileView*
        // 一族，压根没有这个字段 → spec 读到 null → shouldApplyTile 直接放弃刷新
        // （实测一次刷新 20 块全是 "refresh skip(shouldApply=false) spec=null"）。
        // 表现就是「改可见度 / 底图后不实时生效，非得重启系统界面」。
        final boolean cardShape = TileLongPressConfig.SHAPE_CARD.equals(shape)
                || TileLongPressConfig.SHAPE_CARD_WIDE.equals(shape);
        for (View view : targets) {
            if (cardShape) {
                // 卡片（1×1 方形 / 1×2）：卡片本体 + 内部所有铺着我们底图的层全部重设一遍。
                // 可见的那层不一定被 cardVisibleLayer 记住，只赌一层就会出现「看着没变」。
                TileBackgroundHook.applyStyleToCardLayers(view, shape);
            } else {
                TileBackgroundHook.applyStyleDirect(view, shape);
            }
        }
        // 图标底色（2×1 图标下的小方块 / 三阶段跟着滑动的白块）是独立于磁贴底图的
        // 另一块色块，改完颜色得让它自己重刷一遍；其它形状内部会直接跳过
        IconColorHook.refresh(shape);
        // 图标着色与文字颜色是图标 / 标签自己的颜色，跟上面那块色块也是两回事
        TileColorHook.refresh(shape);
        XposedBridge.log(TAG + ": refreshed shape=" + shape + ", views=" + targets.size());
        return targets.size();
    }

    /** 缓存视图所在的那几个根视图（同一棵树只留一个）。 */
    private static List<View> rootsOfCachedViews() {
        List<View> roots = new ArrayList<View>();
        synchronized (sSpecView) {
            for (Map.Entry<String, WeakReference<View>> entry : sSpecView.entrySet()) {
                WeakReference<View> ref = entry.getValue();
                addRoot(roots, ref == null ? null : ref.get());
            }
        }
        synchronized (sExtraViews) {
            for (View view : sExtraViews.keySet()) {
                addRoot(roots, view);
            }
        }
        return roots;
    }

    private static void addRoot(final List<View> roots, final View view) {
        if (view == null) {
            return;
        }
        View root = view.getRootView();
        if (root != null && !roots.contains(root)) {
            roots.add(root);
        }
    }

    /**
     * 把这棵树里<b>现挂着</b>的磁贴按形状收进 {@code out}。
     *
     * <p>刷新时不能只信 {@link #sSpecView} 里缓存的视图——网格会回收视图，
     * 缓存里那块可能早就不在屏幕上了，样式套上去看不见（用户反馈的「可见度不实时」）。
     * 这里从根视图现扫，形状用 {@link #shapeOfView} 现算，跟目标形状一致才算数。</p>
     *
     * <p>只对「看着像磁贴 / 媒体面板 / 设备卡」的类名算形状，避免给整棵树每个 View 都算一遍。</p>
     */
    private static void collectLiveTiles(final View view, final String shape, final List<View> out) {
        if (view == null || out.size() >= 60) {
            return;
        }
        String cn = view.getClass().getName();
        if (cn.contains("TileView") || cn.contains("MediaPanelView")
                || cn.contains("DeviceCard") || cn.contains("EntranceCard")) {
            if (shape.equals(shapeOfView(view))) {
                out.add(view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLiveTiles(group.getChildAt(i), shape, out);
            }
        }
    }

    // ------------------------------------------------------------ 长按拦截

    /**
     * 记下「用户实际长按的那块磁贴视图」。
     *
     * <p>{@code QSTileImpl.longClick} 只拿得到 tile、拿不到视图，而编辑/缩放面板里
     * 同一块磁贴的 1×1 / 2×1 / 三阶段<b>三种预览同时存在且共用同一个 spec</b>，
     * 靠 spec 反查视图必然指错形状（表现为「长按三阶段，拦截失效」）。
     * {@code View.performLongClick()} 是长按进入视图回调的必经之路，在这里把视图抓下来，
     * 长按拦截就能按被按的那块视图现场判形状。</p>
     */
    private static int hookPressedView(final LoadPackageParam lpparam) {
        int n = 0;
        try {
            Class<?> viewClass = XposedHelpers.findClass("android.view.View",
                    lpparam.classLoader);
            XC_MethodHook handler = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) {
                        return;
                    }
                    View pressed = (View) param.thisObject;
                    View tile = findTileView(pressed);
                    if (tile != null) {
                        sPressedView = new WeakReference<View>(tile);
                        sPressedAt = SystemClock.uptimeMillis();
                    }
                    blockAtViewLevel(pressed, param);
                }
            };
            XposedHelpers.findAndHookMethod(viewClass, "performLongClick", handler);
            n++;
            try {
                // Android 13 起 View 还有带坐标的重载，部分子类只覆写这一个，
                // 只钩无参版本的话那些视图的长按会整个漏掉
                XposedHelpers.findAndHookMethod(viewClass, "performLongClick",
                        float.class, float.class, handler);
                n++;
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookPressedView failed - " + t);
        }
        return n;
    }

    /**
     * 视图层的长按拦截：补住<b>不走 {@code QSTileImpl}</b> 的那部分。
     *
     * <p>常规磁贴的长按会一路走到 {@code QSTileImpl.longClick}，由
     * {@link #onLongClick} 兜住；但有两类收不到：</p>
     * <ul>
     *   <li>2×2 媒体卡片（{@code OplusQsLargeMediaPanelView} 一族）以及它下面那排按钮
     *       用的是自己的面板视图，根本不经过 QSTileImpl；</li>
     *   <li>耳机 / 设备空间这类 ColorOS 自带磁贴，长按不经过
     *       {@code QSTileImpl.longClick}（实测钩子收不到），但视图层照样会走
     *       {@code View.performLongClick}。</li>
     * </ul>
     *
     * <p>所以这里在长按进入视图回调的必经之路上按视图现场判形状，命中就吞掉。
     * 视图层先吞掉的话 {@code OnLongClickListener} 根本不会被调用，
     * 下游那条 QSTileImpl 链路自然也不会跑，不会重复弹面板。</p>
     *
     * @return true 表示已吞掉长按
     */
    private static boolean blockAtViewLevel(View pressed, XC_MethodHook.MethodHookParam param) {
        // 通知行也够大，会被下面按尺寸兜底那一步当成「认不出的卡片」弹诊断提示。
        // 通知有自己的长按链路（NotificationHook#hookLongPress），这里整棵子树摘出去
        if (NotificationHook.isInsideNotification(pressed)) {
            return false;
        }
        // 亮度 / 音量胶囊也够大，同样会被当成「认不出的卡片」弹诊断提示。
        // 它们的长按由 SliderHook 在触摸链路里接管
        if (SliderHook.isInsideSlider(pressed)) {
            return false;
        }
        // 「清除全部」按钮的长按由 ClearAllButtonHook 直接挂在按钮上，
        // 这里摘出去，免得被下面按尺寸兜底那一步当成「认不出的卡片」弹诊断提示
        if (ClearAllButtonHook.isInsideClearAll(pressed)) {
            return false;
        }
        // 右上角那排入口按钮的长按由 QuickEntranceButtonHook 挂在按钮自己身上
        // （★ / ✎ / ⚙ / ⋮），同样摘出去，别让尺寸兜底把它们当成认不出的卡片
        if (QuickEntranceButtonHook.isInsideEntrance(pressed)) {
            return false;
        }
        try {
            sDiagCtx = pressed.getContext();
            sDiagAnchor = new WeakReference<View>(pressed);
            View anchor;
            String shape = null;
            // 媒体面板优先：卡片本体和它下面那排按钮都归到「2×2 磁贴」这一组
            View media = findMediaPanel(pressed);
            if (media != null) {
                anchor = media;
                // 媒体卡片就是网格里那颗 2×2，底图 / 圆角跟 2×2 共用一套
                shape = TileLongPressConfig.SHAPE_2X2;
            } else {
                View tile = findTileView(pressed);
                anchor = tile;
                if (tile != null) {
                    shape = resolveShape(tile);
                    if (shape == null) {
                        // 尺寸 / 单元宽度算不出跨列数时，回落到该 spec 缓存过的形状
                        shape = sSpecShape.get(specFieldOf(tile));
                    }
                    if (shape == null) {
                        shape = guessShape(tile);
                    }
                }
                if (shape == null) {
                    // deviceplugin 卡片（耳机 / 设备空间）先按类名认：它既不含 "Tile" 也不含
                    // "Media"，下面两条都认不出它。方形与横向（无设备预连接时）是两套配置，
                    // 认出来了就不必再走按尺寸兜底那一层
                    View devCard = devicePluginCardOf(pressed);
                    if (devCard != null) {
                        anchor = devCard;
                        shape = devicePluginShapeOf(devCard);
                        rememberExtraView(devCard, shape);
                        XposedBridge.log(TAG + ": view-level longPress devCard="
                                + devCard.getClass().getSimpleName()
                                + " " + devCard.getWidth() + "x" + devCard.getHeight()
                                + " → shape=" + shape);
                    }
                }
                if (shape == null) {
                    // 既不是磁贴也不是媒体面板：沿视图树找最近一层对得上网格的容器
                    Context viewCtx = pressed.getContext();
                    int cell = unitWidthPx(viewCtx);
                    anchor = gridAncestor(pressed, cell);
                    shape = anchor == null ? null : resolveShape(anchor);
                    if (shape == null) {
                        // 最后兜一层：控制中心里认不出类名的卡片。按尺寸找出这张卡片，
                        // 归到「方形卡片」组——不能并进 1×1 圆形磁贴，
                        // 那样底图和圆角都会跟圆形磁贴共用一套
                        View card = cardAncestor(pressed, cell);
                        if (card != null) {
                            anchor = card;
                            shape = TileLongPressConfig.SHAPE_CARD;
                            rememberExtraView(card, shape);
                            XposedBridge.log(TAG + ": view-level longPress card="
                                    + card.getClass().getSimpleName()
                                    + " " + card.getWidth() + "x" + card.getHeight()
                                    + " → shape=" + shape);
                        }
                    }
                    if (shape == null) {
                        // 认不出时把被按视图往上几层的类名 / 尺寸一起显示出来：
                        // 上机排查时一张截图就能看出这两块磁贴到底是什么视图
                        notifyNotBlocked("长按没拦到：认不出这个视图（unit=" + cell + "）\n"
                                + logUnresolvedPressed(pressed, cell));
                        return false;
                    }
                }
            }

            Context ctx = pressed.getContext();
            if (ctx == null) {
                return false;
            }
            // 排查用：把三阶段卡这次到底被算成什么形状的依据全打出来
            // （anchor 类名 / spec / ThreeStage 是否命中 / spec 缓存兜底 / 兜底猜的形状）
            XposedBridge.log(TAG + ": block diag anchorCls="
                    + (anchor == null ? "null" : anchor.getClass().getName())
                    + " spec=" + (anchor == null ? "?" : specFieldOf(anchor))
                    + " threeStage=" + isThreeStageTile(anchor)
                    + " sSpecShapeFallback=" + (anchor == null ? "?" : sSpecShape.get(specFieldOf(anchor)))
                    + " guess=" + guessShape(anchor)
                    + " → shape=" + shape);
            if (!TileLongPressConfig.isBlocked(ctx, shape)) {
                logViewLevel(shape, anchor, "该形状没开拦截");
                notifyNotBlocked("长按没拦到：" + TileLongPressConfig.label(shape)
                        + " 的开关没打开（★ 里打开）");
                return false;
            }
            boolean shown = TileStylePopup.show(anchor, shape,
                    TileBackgroundHook.isTileActive(anchor), null);
            XposedBridge.log(TAG + ": view-level longPress shape=" + shape
                    + " (cls=" + anchor.getClass().getSimpleName() + ") → "
                    + (shown ? "BLOCKED" : "panel failed, pass"));
            if (!shown) {
                notifyNotBlocked("长按没拦到：" + TileLongPressConfig.label(shape)
                        + " 的面板弹不出来（看日志）");
                return false;
            }
            param.setResult(Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": view-level longPress failed - " + t);
            notifyNotBlocked("长按没拦到：拦截逻辑抛异常（看日志）\n" + t);
            return false;
        }
    }

    /**
     * 形状兜底：单元宽度取不到、spec 也没缓存过时，按磁贴视图自身的宽高比猜。
     *
     * <p>只分「正方形磁贴」和「宽卡片」两层：高度不足宽度的 1.3 倍就当宽卡片。
     * 1×1 磁贴带文字标签，视图是竖着的（175×225 这类），2×1 卡片是横着的
     * （355×225），这个口径能把两者分开。</p>
     */
    private static String guessShape(final View tile) {
        int w = tile.getWidth();
        int h = tile.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        return h * 1.3f < w
                ? TileLongPressConfig.SHAPE_2X1 : TileLongPressConfig.SHAPE_1X1;
    }

    /**
     * 临时排查用：长按没被拦到时弹一句提示。
     *
     * <p>排查「长按某块磁贴没反应」时不用去翻 LSPosed 日志，长按一下就知道卡在哪一环：
     * 形状认不出 / 开关没开 / 面板弹不出来。同一句文案只弹一次，最多
     * {@link #MAX_DIAG_TOASTS} 句，排查完可以整块删掉。</p>
     *
     * <p><b>只在编辑模式开着时弹</b>（{@link TileLongPressConfig#isEditMode}）：
     * 关掉编辑模式后长按本来就不归我们管、原样放行给系统，这时候再弹「没拦到」
     * 纯属打扰——排查提示只在用户主动开了编辑模式、却还是没拦住时才需要。</p>
     */
    private static void notifyNotBlocked(final String message) {
        if (!TileLongPressConfig.isEditMode(sDiagCtx)) {
            XposedBridge.log(TAG + ": diag (edit mode off, skipped): " + message);
            return;
        }
        if (sNotified.size() >= MAX_DIAG_TOASTS || !sNotified.add(message)) {
            return;
        }
        XposedBridge.log(TAG + ": diag: " + message);
        try {
            final Context ctx = sDiagCtx;
            WeakReference<View> ref = sDiagAnchor;
            View anchor = ref == null ? null : ref.get();
            if (ctx == null || anchor == null || !anchor.isAttachedToWindow()) {
                return;
            }
            float density = ctx.getResources().getDisplayMetrics().density;
            int pad = (int) (12 * density);
            TextView tv = new TextView(ctx);
            tv.setText(message);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13f);
            tv.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2202020);
            bg.setCornerRadius(12 * density);
            tv.setBackground(bg);

            final PopupWindow popup = new PopupWindow(tv, (int) (280 * density),
                    LinearLayout.LayoutParams.WRAP_CONTENT, false);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setOutsideTouchable(true);
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                    TileStylePopup.topMarginPx(ctx));
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        popup.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            }, 2500);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": diag popup failed - " + t);
        }
    }

    /** 视图层判出了形状但放行时的日志（同一「视图类 + 形状」只记一次，避免刷屏）。 */
    private static void logViewLevel(String shape, View anchor, String why) {
        String key = anchor.getClass().getName() + "|" + shape;
        if (!sLoggedViewLevel.add(key)) {
            return;
        }
        XposedBridge.log(TAG + ": view-level longPress shape=" + shape
                + " (cls=" + anchor.getClass().getSimpleName() + ") → pass: " + why);
    }

    /**
     * 视图层没认出形状：把被按视图的尺寸与往上几层祖先的类名记一条（同类只记一次）。
     *
     * <p>上机排查用——「长按某块磁贴没反应」时，这条日志能直接看出是视图没走长按链路，
     * 还是尺寸/单元宽度算不出跨列数。</p>
     *
     * @return 同样的类名 / 尺寸链（多行），交给诊断弹窗直接显示
     */
    private static String logUnresolvedPressed(View pressed, int cell) {
        String chain = viewChain(pressed);
        String cn = pressed.getClass().getName();
        if (!cn.equals(sLoggedPressedClass)) {
            sLoggedPressedClass = cn;
            XposedBridge.log(TAG + ": view-level longPress not resolved, unit=" + cell
                    + " | " + chain.replace('\n', ' '));
        }
        return chain;
    }

    /** 被按视图往上几层的「类名 + 实测尺寸」，每行一层。 */
    private static String viewChain(View pressed) {
        StringBuilder sb = new StringBuilder();
        View current = pressed;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (depth > 0) {
                sb.append('\n').append("↑ ");
            } else {
                sb.append("被按: ");
            }
            sb.append(current.getClass().getSimpleName())
                    .append(' ').append(current.getWidth()).append('x').append(current.getHeight());
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return sb.toString();
    }

    /**
     * deviceplugin 卡片（耳机 / 设备空间这类「1×1 方形卡片」）的类名前缀。
     *
     * <p>反编译 {@code classes5.dex} 得到：这类卡片是 QS 插件视图，整套
     * {@code com.oplus.deviceplugin.sdk} 都打包在 SystemUI 里，卡片视图是
     * {@code SquareDeviceCardView} / {@code RectangleDeviceCardView} /
     * {@code SquareEntranceCardView} / {@code RectangleEntranceCardView} /
     * {@code NoDeviceEntranceCardView} 这 5 个类（都继承同包的 {@code d}，
     * 而 {@code d} 继承 {@code ConstraintLayout}）。</p>
     *
     * <p>用包名前缀而不是类名逐个匹配：这 5 个类名是稳定的，而且这个包底下
     * <b>只有</b>这类卡片，不会误伤别的视图——之前那次「连控制中心背景都被改了」
     * 就是因为拿 {@code cardAncestor} 认出来的通用容器类名当过滤依据。</p>
     *
     * <p>前缀由 {@link #CARD_ANCHOR_CLASS} 推出来，而不是直接写成
     * {@code "…separatecardview."}：契约校验要求 APK 里每个 {@code com.oplus.*}
     * 字面量都是真实存在的<b>类名</b>，包前缀这种写法会被判成「残留的旧名字」。</p>
     */
    private static final String CARD_ANCHOR_CLASS =
            "com.oplus.deviceplugin.sdk.ui.view.separatecardview.SquareDeviceCardView";

    /** deviceplugin 卡片所在包的前缀（含结尾的点）。 */
    private static final String CARD_PACKAGE =
            CARD_ANCHOR_CLASS.substring(0, CARD_ANCHOR_CLASS.lastIndexOf('.') + 1);

    /** 这个视图是不是 deviceplugin 卡片本体。 */
    public static boolean isDevicePluginCard(final View view) {
        return view != null && view.getClass().getName().startsWith(CARD_PACKAGE);
    }

    /**
     * 这张（已确认是 deviceplugin 的）卡片是不是横向的「1×2 设备卡片」。
     *
     * <p>这个包底下的 5 个类里，{@code SquareDeviceCardView} / {@code SquareEntranceCardView}
     * 是 1:1 的方形卡；{@code RectangleDeviceCardView} / {@code RectangleEntranceCardView} /
     * {@code NoDeviceEntranceCardView} 是跨两列的宽卡（没有设备预连接时显示的就是最后这个）。
     * 按类名前缀区分方形与横向即可，不需要量尺寸——量尺寸要等布局完成，而底图钩子常常
     * 在布局之前就来了。</p>
     */
    private static boolean isWideDevicePluginCard(final View card) {
        String name = card.getClass().getSimpleName();
        return name.startsWith("Rectangle") || name.startsWith("NoDevice");
    }

    /**
     * deviceplugin 卡片的形状：方形（{@code SHAPE_CARD}）或横向（{@code SHAPE_CARD_WIDE}）。
     *
     * @return 不是 deviceplugin 卡片时返回 null（调用方据此判断「认没认出来」）
     */
    public static String devicePluginShapeOf(final View view) {
        if (!isDevicePluginCard(view)) {
            return null;
        }
        return isWideDevicePluginCard(view)
                ? TileLongPressConfig.SHAPE_CARD_WIDE : TileLongPressConfig.SHAPE_CARD;
    }

    /**
     * 从卡片内部的任意视图（例如承载底图的那层 {@code ConstraintLayout}）向上找这张卡片。
     *
     * <p>卡片里的底图不在卡片自己身上（见 {@code TileBackgroundHook#backgroundCarrier}），
     * 所以底图钩子拿到的是内部那一层，得先找回卡片本体才能判形状、套样式。</p>
     */
    public static View devicePluginCardOf(final View child) {
        View current = child;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (isDevicePluginCard(current)) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 沿视图树向上找媒体面板视图（2×2 大卡片 / 它下面那排按钮都挂在这一族视图下）。
     *
     * <p>不能靠 {@link #findTileView}：媒体面板的类名里既没有 {@code QSTileView}
     * 也没有 {@code HighlightTileView}，找磁贴那一套直接返回 null。</p>
     *
     * <p>对 {@link TileBackgroundHook} 也开放：那层挂在 {@code View#setBackgroundDrawable}
     * 上，系统每次给媒体面板 (或它内部铺满整卡的那层) 设底图都会经过，于是「布局后补一次」
     * 不再依赖 {@link #MEDIA_PANEL_CLASSES} 那几个类名能不能在本机 ROM 上找到。</p>
     */
    public static View findMediaPanel(View child) {
        View current = child;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (isMediaPanelClass(current.getClass().getName())) {
                rememberExtraView(current, TileLongPressConfig.SHAPE_2X2);
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 记下「没有 spec」的视图（媒体面板 / 认不出类名的卡片），供
     * {@link #refreshShape(String)} 刷新样式用。
     *
     * <p>它们进不了 {@link #sSpecView}，不单独记一份的话改完底图 / 圆角后没人能把它们刷出来。</p>
     */
    private static void rememberExtraView(final View view, final String shape) {
        if (view == null || shape == null) {
            return;
        }
        sExtraViews.put(view, shape);
    }

    /** 记下「没有 spec」的视图（deviceplugin 卡片），供底图钩子登记落点用。 */
    public static void rememberCardView(final View card) {
        String shape = devicePluginShapeOf(card);
        rememberExtraView(card, shape == null ? TileLongPressConfig.SHAPE_CARD : shape);
    }

    /** 某个形状当前还活着的「无 spec 视图」（弱键，已回收的自动不在里面）。 */
    private static List<View> extraViewsOf(final String shape) {
        List<View> result = new ArrayList<View>();
        synchronized (sExtraViews) {
            for (Map.Entry<View, String> entry : sExtraViews.entrySet()) {
                if (shape.equals(entry.getValue())) {
                    result.add(entry.getKey());
                }
            }
        }
        return result;
    }

    /**
     * 兜底：把「控制中心里认不出类名的卡片」找出来当锚点。
     *
     * <p>ColorOS 自带的设备空间 / 耳机这类卡片走 deviceplugin，视图类名里既没有
     * {@code QSTileView} 也没有 {@code MediaPanel}，{@link #findTileView} 与
     * {@link #gridAncestor} 都认不出来（日志表现为 {@code not resolved}）。
     * 它们的类名各不相同、没法逐个匹配，只能按尺寸找：从被按视图往上走，
     * 第一层尺寸达到 0.7 个 1×1 单元的就是这张卡片。</p>
     *
     * <p>两道保护，避免误伤别处长按：祖先里一旦出现通知栏视图就直接放弃
     * （通知行也够大，长按通知不能变成弹样式面板）；撞上面板级容器
     * （QSPanel / ScrollView / ViewPager …）同样放弃。</p>
     */
    private static View cardAncestor(final View pressed, final int cell) {
        if (cell <= 0) {
            return null;
        }
        float min = cell * 0.7f;
        // 上界：一块卡片最多跨 2 列 / 2 行（2×2 媒体面板不在这条兜底上，另有专门入口）。
        // 不设上界的话，控制中心里那些铺满整块的覆盖层会被当成卡片——实测一个
        // 963×963 的 com.oplus.charge.view.CircleView（充电动画）被认成
        // 「1×1 方形卡片」，长按它就弹出一个张冠李戴的卡片面板
        float max = cell * 2.6f;
        View current = pressed;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String cn = current.getClass().getName();
            if (cn.contains("Notification") || isPanelContainer(current)) {
                return null;
            }
            int w = current.getWidth();
            int h = current.getHeight();
            if (w >= min && h >= min && w <= max && h <= max) {
                // 只认类名里写着「卡片」的（SquareEntranceCardView / NoDeviceEntranceCardView
                // / SquareDeviceCardView 这一族）。纯按尺寸认的话，网格里那些空位 / 磁贴外壳
                // 尺寸也刚好一块磁贴大小，会被当成方形卡片——按在两块磁贴之间的空隙上，
                // 就会弹出别人的面板（用户报的「按空隙弹其它磁贴的弹窗」）。
                if (!cn.contains("Card")) {
                    return null;
                }
                // 容器里已经装着磁贴的话，它是磁贴的外壳（实测 DeformationFrameLayout），
                // 同样不是卡片
                if (containsTileView(current)) {
                    return null;
                }
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 视图树里「尺寸对得上磁贴网格」的最近一层控制中心容器，给不走磁贴链路的视图兜底判形状。
     *
     * <p>尺寸门槛取 0.7 个 1×1 单元：再小就只是图标/文字这类叶子视图，
     * 量出来的跨列数没有意义（小视图按四舍五入会算出 0 列 0 行）。</p>
     */
    private static View gridAncestor(View pressed, int cell) {
        if (cell <= 0) {
            return null;
        }
        View current = pressed;
        for (int depth = 0; current != null && depth < 10; depth++) {
            // 装了磁贴的容器是磁贴外壳，不是网格里那张「认不出类名的卡片」：
            // 按在两块磁贴之间的空隙上时不能拿它去推形状
            if (isQsContainer(current) && !isPanelContainer(current)
                    && !containsTileView(current)) {
                int w = current.getWidth();
                int h = current.getHeight();
                if (w >= cell * 0.7f && h >= cell * 0.7f) {
                    return current;
                }
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 这棵子树里有没有磁贴视图（带 {@code mTileSpec} 的那种）。
     *
     * <p>用来把「磁贴的外壳容器」跟「认不出类名的卡片」分开：外壳里装着磁贴，
     * 卡片里没有。按在两块磁贴之间的空隙上时，按到的就是外壳那一层，
     * 尺寸刚好一块磁贴大小，不区分的话会被当成方形卡片弹出别人的面板。</p>
     */
    private static boolean containsTileView(final View view) {
        if (view == null) {
            return false;
        }
        if (specFieldOf(view).length() > 0) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsTileView(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只认「磁贴 / 媒体面板」这一族的容器。
     *
     * <p>门槛刻意收得很紧：放成「类名含 QS」的话，亮度/音量滑杆这类
     * 够大又不是磁贴的控件也会被判成 1×1 而弹面板；完全不加限制的话，
     * 通知栏里的通知行同样够大，长按通知会被算成 2×1 宽卡片——
     * 两种都会把原有长按弄坏。</p>
     */
    private static boolean isQsContainer(View view) {
        String cn = view.getClass().getName();
        return cn.contains("Tile") || cn.contains("Media");
    }

    /**
     * 面板级的大容器：尺寸也够大，但长按它们不是磁贴语义
     * （空白区长按另有 {@code EmptyAreaGestureProxy} 处理），不能拿来判形状。
     */
    private static boolean isPanelContainer(View view) {
        String cn = view.getClass().getName();
        String[] skip = {"QSRootView", "QSPanel", "Scrim", "ScrollView", "RecyclerView",
                "ViewPager", "Sliding", "QSCustomizer", "QuickSettings"};
        for (String key : skip) {
            if (cn.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static int hookLongClick(Class<?> tileImpl, Class<?> expandable) {
        if (tileImpl == null || expandable == null) {
            XposedBridge.log(TAG + ": longClick hook skipped, tileImpl=" + tileImpl
                    + ", expandable=" + expandable);
            return 0;
        }
        int n = 0;
        n += hookOne(tileImpl, expandable, "longClick");
        n += hookOne(tileImpl, expandable, "handleLongClick");
        return n;
    }

    private static int hookOne(Class<?> tileImpl, Class<?> expandable, String method) {
        try {
            XposedHelpers.findAndHookMethod(tileImpl, method, expandable,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            onLongClick(param);
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + method + " failed - " + t);
            return 0;
        }
    }

    /** 命中已开启拦截的形状时吞掉系统长按，并弹出该形状的样式面板。 */
    private static void onLongClick(XC_MethodHook.MethodHookParam param) {
        Object tile = param.thisObject;
        try {
            Object specObj = XposedHelpers.callMethod(tile, "getTileSpec");
            if (!(specObj instanceof String)) {
                return;
            }
            String spec = (String) specObj;
            // 优先级：实际被长按的那块视图 > 该 spec 自己记着的视图 > spec 级形状缓存。
            // 必须是「属于这次长按的那块」——编辑/缩放面板里三种形状的预览共用同一个 spec，
            // 拿别的视图现算形状会把这块磁贴判成别的形状（面板开错形状）。
            //
            // 只认「刚刚按下」记下的那块视图。视图层没消费本次按压时（三阶段这类走
            // QSTileImpl.longClick 的磁贴不会走到视图层），sPressedView 还停在上一张
            // 磁贴上；而编辑面板的预览视图身上没有 mTileSpec，光靠 spec 核对拦不住它
            // ——实测先按 1×1 再长按三阶段，形状被上一块 1×1 带偏成 1×1。
            View pressed = deref(sPressedView);
            if (!isUsable(pressed)
                    || SystemClock.uptimeMillis() - sPressedAt > PRESS_FRESH_MS) {
                pressed = null;
            }
            if (pressed != null) {
                String pressedSpec = specFieldOf(pressed);
                // 主面板磁贴带 mTileSpec，必须与本次 spec 一致；预览视图没有这个字段，
                // 不因此否定它（它确实属于这次长按的那张卡）
                if (pressedSpec.length() > 0 && !pressedSpec.equals(spec)) {
                    pressed = null;
                }
            }
            // 还是没拿到，就补该 spec 自己记着的视图（rememberAnchor 存的那块）：
            // 网格视图会被 RecyclerView 回收后重新 bind，用前核对 mTileSpec 防串台
            if (pressed == null) {
                View specView = deref(sSpecView.get(spec));
                if (isUsable(specView)) {
                    String viewSpec = specFieldOf(specView);
                    if (viewSpec.length() == 0 || viewSpec.equals(spec)) {
                        pressed = specView;
                    }
                }
            }
            // 形状只信「确认属于本次 spec 的视图」；一块都没有才退回 spec 级缓存
            // （cacheShape 在磁贴布局时按 spec 记下的形状）。绝不能像以前那样拿
            // anchorFor() 的结果现算——那个兜底会落到 sLastAnchor，也就是上一张长按 /
            // 布局过的磁贴，于是把它的形状安到这张卡头上：实测先按 2×1 再长按三阶段，
            // 面板变成「2×1 宽卡片」；先按 1×1 又变成「1×1」。
            String shape = isUsable(pressed) ? shapeOfView(pressed) : null;
            if (shape == null) {
                shape = sSpecShape.get(spec);
            }
            // 弹窗挂靠 / 位置可以用宽一点的兜底：形状已经定死，拿哪块视图当 anchor 都行
            View anchor = isUsable(pressed) ? pressed : anchorFor(spec);
            Context ctx = (Context) XposedHelpers.getObjectField(tile, FIELD_CONTEXT);
            boolean blocked = shape != null && TileLongPressConfig.isBlocked(ctx, shape);
            XposedBridge.log(TAG + ": longClick spec=" + spec + " shape=" + shape
                    + " (anchor=" + (anchor == null ? "null" : anchor.getClass().getSimpleName())
                    + ", anchorSpec=" + (anchor == null ? "?" : specFieldOf(anchor)) + ")"
                    + " → " + (blocked ? "BLOCKED" : "pass"));
            if (!blocked) {
                return;
            }
            // 彻底吞掉：不启动 Activity，也不弹任何系统提示
            param.setResult(null);

            if (anchor == null) {
                XposedBridge.log(TAG + ": no attached tile view, style panel skipped");
                return;
            }
            Object starter = null;
            try {
                starter = XposedHelpers.getObjectField(tile, FIELD_ACTIVITY_STARTER);
            } catch (Throwable ignored) {
            }
            // 默认编辑长按这块磁贴当前所处的状态，用户一眼就能对上自己看到的样子
            TileStylePopup.show(anchor, shape, TileBackgroundHook.isTileActive(anchor), starter);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": longClick handling failed - " + t);
        }
    }

    /** 取该 spec 的磁贴视图当 anchor；不可用时回落到最近布局过的那块。 */
    private static View anchorFor(String spec) {
        View view = deref(sSpecView.get(spec));
        if (isUsable(view)) {
            return view;
        }
        View last = sLastAnchor.get();
        if (isUsable(last)) {
            return last;
        }
        // 兜底：缓存里可能全指向已销毁的视图（例如刚退出编辑面板），
        // 弹窗只需要一块还挂在窗口上的磁贴当 anchor，形状已由 spec 决定，用哪块都行
        synchronized (sSpecView) {
            for (WeakReference<View> ref : sSpecView.values()) {
                View candidate = deref(ref);
                if (isUsable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isUsable(View view) {
        return view != null && view.isAttachedToWindow();
    }
}
