package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 通知界面<b>下拉头部</b>（时钟「23:15」/ 日期「10月13日 星期二」/ 铃铛等图标）的调色与长按入口。
 *
 * <p>用户需求：长按通知界面的头部区域弹样式面板，单独调这里的<b>字体颜色</b>与
 * <b>图标颜色</b>——和控制中心背景面板（{@link QsBackgroundPopup}）同一套交互。</p>
 *
 * <p><b>头部是哪些 view</b>（样本 系统界面_16.00.12 反查实证）：通知面板的头部布局
 * {@code combined_qs_header.xml}（根 id {@code split_shade_status_bar}）里，
 * 时钟是 {@code com.android.systemui.statusbar.policy.Clock}（{@code TextView} 子类，
 * id {@code clock}）、日期是 {@code VariableDateView}（{@code TextView} 子类，
 * id {@code date}）；铃铛 / 状态图标等 {@code ImageView} 都挂在这个根容器下面。
 * 时钟的<b>直接父容器就是头部根</b>，从这里向下递归即可覆盖整条头部。</p>
 *
 * <p><b>怎么截到</b>：挂 {@code TextView#onAttachedToWindow}，只处理 id 是
 * {@code clock} / {@code date} 的 TextView（id 用 {@code getIdentifier} 按资源名解，
 * 和 {@link NotificationTextHook#resolveSystemUiIds} 同一套路）。见到时钟后：
 * 套字体色 → 找到头部根 → 递归给 {@code ImageView} 上图标色 → 给头部根挂长按监听。
 * 面板里改完色走 {@link #refresh()} 对缓存的重刷。</p>
 *
 * <p><b>配置存哪</b>：SystemUI 自己的 {@code systemui_hook_ui} prefs（和
 * {@link TileLongPressConfig} 同一份文件），两个键各存一个 ARGB，
 * {@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM}（-1）表示跟随系统。</p>
 */
public final class NotifHeaderHook {

    private static final String TAG = "SystemUIHook/NotifHeader";

    /** 字体颜色（时钟 / 日期）的 prefs 键。 */
    public static final int KIND_FONT = 0;
    /** 图标颜色（铃铛 / 状态图标）的 prefs 键。 */
    public static final int KIND_ICON = 1;

    private static final String KEY_FONT_COLOR = "notifHeaderFontColor";
    private static final String KEY_ICON_COLOR = "notifHeaderIconColor";
    private static final String PREFS_NAME = "systemui_hook_ui";

    /** systemui 里时钟 / 日期的资源 id（运行时解，解不到 = 0）。 */
    private static volatile int sClockId;
    private static volatile int sDateId;
    private static volatile boolean sIdsResolved;

    /** 缓存的头部根与时钟 / 日期（面板改色后 refresh 用）。 */
    private static volatile WeakReference<ViewGroup> sHeaderRoot =
            new WeakReference<ViewGroup>(null);
    private static volatile WeakReference<TextView> sClock =
            new WeakReference<TextView>(null);
    private static volatile WeakReference<TextView> sDate =
            new WeakReference<TextView>(null);

    /** 只打一次的日志。 */
    private static final java.util.Set<String> sLogged =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private NotifHeaderHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onTextViewAttached((TextView) param.thisObject);
                            } catch (Throwable t) {
                                logOnce("attach failed - " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TextView.onAttachedToWindow failed - " + t);
        }
    }

    // ------------------------------------------------------------ 配置读写

    /** 读颜色配置；{@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM} = 跟随系统。 */
    public static int getColor(final Context ctx, final int kind) {
        SharedPreferences prefs = prefs(ctx);
        if (prefs == null) {
            return TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        }
        String key = kind == KIND_ICON ? KEY_ICON_COLOR : KEY_FONT_COLOR;
        try {
            return prefs.getInt(key, TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM);
        } catch (Throwable t) {
            return TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        }
    }

    /** 写颜色配置；传「跟随系统」表示清除。写完调 {@link #refresh()} 生效。 */
    public static void setColor(final Context ctx, final int kind, final int argb) {
        SharedPreferences prefs = prefs(ctx);
        if (prefs == null) {
            return;
        }
        String key = kind == KIND_ICON ? KEY_ICON_COLOR : KEY_FONT_COLOR;
        prefs.edit().putInt(key, argb).apply();
        TileLongPressConfig.flush(ctx);
    }

    private static SharedPreferences prefs(final Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            Context app = ctx.getApplicationContext();
            if (app == null) {
                app = ctx;
            }
            return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            logOnce("prefs failed - " + t);
            return null;
        }
    }

    // ------------------------------------------------------------ 挂载与套用

    private static void onTextViewAttached(final TextView tv) {
        if (tv == null) {
            return;
        }
        resolveIds(tv.getContext());
        int id = tv.getId();
        if (id != sClockId && id != sDateId) {
            return;
        }
        boolean isClock = id == sClockId;
        TextView prev = (isClock ? sClock : sDate).get();
        if (prev == tv) {
            return; // 同一个实例反复 attach：只处理第一次
        }
        if (isClock) {
            sClock = new WeakReference<TextView>(tv);
        } else {
            sDate = new WeakReference<TextView>(tv);
        }
        // 时钟的父容器就是头部根（combined_qs_header 根 split_shade_status_bar）
        ViewParent p = tv.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) p;
            ViewGroup prevRoot = sHeaderRoot.get();
            if (prevRoot != root) {
                sHeaderRoot = new WeakReference<ViewGroup>(root);
                installLongPress(root);
            }
        }
        applyAll();
        logOnce("header view attached: " + (isClock ? "clock" : "date"));
    }

    /** 长按头部弹样式面板。系统头部原本没有长按行为，直接占用不冲突。 */
    private static void installLongPress(final ViewGroup root) {
        try {
            root.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    Context ctx = root.getContext();
                    if (ctx == null) {
                        return false;
                    }
                    NotifHeaderStylePopup.show(root, ctx);
                    return true;
                }
            });
            root.setLongClickable(true);
        } catch (Throwable t) {
            logOnce("long click install failed - " + t);
        }
    }

    /** 面板改色后重刷：字体色 + 图标色一起套。 */
    public static int refresh() {
        return applyAll() ? 1 : 0;
    }

    private static boolean applyAll() {
        boolean applied = false;
        try {
            int font = getColor(contextOf(sHeaderRoot.get()), KIND_FONT);
            TextView clock = sClock.get();
            TextView date = sDate.get();
            if (clock != null) {
                applyFontColor(clock, font);
                applied = true;
            }
            if (date != null) {
                applyFontColor(date, font);
                applied = true;
            }
            int icon = getColor(contextOf(sHeaderRoot.get()), KIND_ICON);
            ViewGroup root = sHeaderRoot.get();
            if (root != null) {
                applyIconColor(root, icon);
                applied = true;
            }
        } catch (Throwable t) {
            logOnce("apply failed - " + t);
        }
        return applied;
    }

    private static Context contextOf(final View v) {
        return v == null ? null : v.getContext();
    }

    /** 时钟 / 日期字体色：跟随系统时不动（系统自己管）。 */
    private static void applyFontColor(final TextView tv, final int colorArgb) {
        if (colorArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return;
        }
        try {
            if (tv.getCurrentTextColor() != colorArgb) {
                tv.setTextColor(colorArgb);
            }
        } catch (Throwable t) {
            logOnce("font color failed - " + t);
        }
    }

    /**
     * 图标颜色：递归给头部根下的所有 {@code ImageView} 挂滤镜。
     *
     * <p>{@code SRC_ATOP} 只换色保形状；跟随系统时清掉滤镜交还系统。滤镜是幂等设置，
     * 重复套不会越套越深。</p>
     */
    private static void applyIconColor(final ViewGroup root, final int colorArgb) {
        boolean follow = colorArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        try {
            tintTree(root, colorArgb, follow, 0);
        } catch (Throwable t) {
            logOnce("icon color failed - " + t);
        }
    }

    private static void tintTree(final ViewGroup root, final int colorArgb,
            final boolean follow, final int depth) {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                if (follow) {
                    iv.setColorFilter(null);
                } else {
                    iv.setColorFilter(new PorterDuffColorFilter(
                            colorArgb, PorterDuff.Mode.SRC_ATOP));
                }
            } else if (child instanceof ViewGroup) {
                tintTree((ViewGroup) child, colorArgb, follow, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------ id 解析

    /**
     * 解析 systemui 的 {@code clock} / {@code date} id。
     *
     * <p>与 {@code NotificationTextHook} 同一套路：{@code R$id} 反射取不到，
     * 走 {@code Resources#getIdentifier} 按资源名查，查不到下个 view 再试。</p>
     */
    private static void resolveIds(final Context ctx) {
        if (sIdsResolved || ctx == null) {
            return;
        }
        try {
            android.content.res.Resources res = ctx.getResources();
            int clock = res.getIdentifier("clock", "id", "com.android.systemui");
            int date = res.getIdentifier("date", "id", "com.android.systemui");
            if (clock == 0 && date == 0) {
                return; // 拿到的多半不是 systemui 的 Resources，换个 ctx 再试
            }
            sClockId = clock;
            sDateId = date;
            sIdsResolved = true;
            logOnce("resolved header ids, clock=" + clock + ", date=" + date);
        } catch (Throwable t) {
            logOnce("resolve ids failed - " + t);
        }
    }

    private static void logOnce(final String message) {
        if (!sLogged.add(message)) {
            return;
        }
        XposedBridge.log(TAG + ": " + message);
    }
}
