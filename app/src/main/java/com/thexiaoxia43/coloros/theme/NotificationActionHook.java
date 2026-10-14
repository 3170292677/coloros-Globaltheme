package com.thexiaoxia43.coloros.theme;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.Button;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 通知卡片里那排操作按钮（「回复」/「复制验证码」/「标记已读」）的底色压深。
 *
 * <p>这些按钮自带一圈比卡片更浅的灰底，浅色主题下几乎和卡片底一个亮，看着像浮着一块白。
 * 这里给它们的背景叠一层淡黑，把灰底整体压深一档，与卡片底拉开层次。</p>
 *
 * <p><b>为什么挂滤镜而不是换背景</b>：系统的灰底是带按压高亮的 state drawable，
 * 整个换掉会把按压反馈一起丢掉。挂一个 {@link PorterDuffColorFilter} 只改颜色，
 * 原来的状态切换与高亮都还在。</p>
 *
 * <p>同一排按钮的<b>文字颜色</b>由 {@link NotificationTextHook} 负责；
 * 这里只提供「是不是操作按钮」的判定（{@link #isActionButton(View)}），两边共用。</p>
 */
public final class NotificationActionHook {

    private static final String TAG = "SystemUIHook/NotificationAction";

    /** 压深用的黑：约 20% 不透明的黑叠上去，灰底整体向暗靠一档。 */
    private static final int DARKEN_COLOR = 0x33000000;

    /** 只打一次的日志（这个钩子每设一次背景就过一次，不能每次都写）。 */
    private static final Set<String> sLogged = new HashSet<String>();

    private NotificationActionHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundDrawable",
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                darken((View) param.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            // 兜底：RemoteViews apply 时按钮多半还没挂进卡片，setBackground 那一刻
            // 祖先链里还没有通知行，压深会落空。挂进窗口后再补一次。
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                darken((View) param.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(TAG + ": installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook View.setBackgroundDrawable failed - " + t);
        }
    }

    /**
     * 这个视图是不是通知卡片里的操作按钮。
     *
     * <p>样本（系统界面_16.00.12）实证：{@code smart_action_button.xml} 就是裸
     * {@code <Button>}，AOSP 的 {@code notification_action.xml} 同样是 {@code Button}
     * ——类名里<b>没有</b> {@code ActionButton}，之前的类名判定整个落空，压深没落到
     * 按钮上。改成结构判定：是 {@code Button}（或类名仍带 ActionButton 的定制款）
     * 且落在通知卡片里。卡片里其他 {@code Button}（应用 RemoteViews 自带的）极少，
     * 误伤可忽略；顶层的展开箭头是 {@code ImageButton}（ImageView 系）不会撞上。</p>
     */
    public static boolean isActionButton(final View v) {
        if (v == null) {
            return false;
        }
        String name = v.getClass().getName();
        if (!(v instanceof Button) && !name.contains("ActionButton")) {
            return false;
        }
        return NotificationTextHook.isInsideNotificationCard(v);
    }

    /** 给操作按钮的背景压深：挂一层黑滤镜，原来的按压高亮保留。 */
    private static void darken(final View v) {
        if (!isActionButton(v)) {
            return;
        }
        Drawable bg = v.getBackground();
        if (bg == null) {
            return;
        }
        try {
            // mutate：这排按钮的背景多半共用一份 ConstantState，不摘出来会把同屏
            // 别的按钮一起改掉
            bg.mutate();
            bg.setColorFilter(new PorterDuffColorFilter(DARKEN_COLOR, PorterDuff.Mode.SRC_ATOP));
            logOnce("darkened action button bg (" + v.getClass().getSimpleName() + ")");
        } catch (Throwable t) {
            logOnce("darken failed - " + t);
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
