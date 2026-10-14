package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * 长按通知界面<b>头部</b>（时钟 / 日期 / 铃铛那条）后弹出的样式面板。
 *
 * <p>形态与 {@link QsBackgroundPopup}（控制中心背景面板）一致，内容换成两项颜色：
 * <b>字体颜色</b>（时钟 / 日期）与<b>图标颜色</b>（铃铛 / 状态图标）。
 * 每项「选颜色 → 取色面板（{@link IconColorPickerPopup}）」「清除 → 跟随系统」，
 * 落地走 {@link NotifHeaderHook#setColor} + {@link NotifHeaderHook#refresh()}，即时生效。</p>
 *
 * <p>入口：{@link NotifHeaderHook} 给头部根挂的 {@code OnLongClickListener}。</p>
 */
public final class NotifHeaderStylePopup {

    private static final String TAG = "SystemUIHook/NotifHeader";

    private static final int WIDTH_DP = 300;
    private static final int CORNER_DP = 16;
    private static final int PADDING_H_DP = 18;

    /** 当前已弹出的实例。 */
    private static PopupWindow sCurrent;

    private NotifHeaderStylePopup() {
    }

    public static void dismiss() {
        try {
            if (sCurrent != null) {
                sCurrent.dismiss();
            }
        } catch (Throwable ignored) {
        }
        sCurrent = null;
    }

    /**
     * 弹出通知界面样式面板。
     *
     * @param anchor 头部根视图（用于取窗口 token）
     * @param ctx    SystemUI 上下文
     */
    public static void show(final View anchor, final Context ctx) {
        if (anchor == null || ctx == null) {
            return;
        }
        dismiss();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(title(ctx, "通知界面样式"));
        card.addView(hint(ctx, "头部（时钟 / 日期 / 图标）的调色 · 长按头部打开"));

        card.addView(colorRow(anchor, ctx, NotifHeaderHook.KIND_FONT, "字体颜色",
                "时钟与日期的文字颜色"));
        card.addView(colorRow(anchor, ctx, NotifHeaderHook.KIND_ICON, "图标颜色",
                "铃铛、蓝牙 / WiFi / 电量等头部图标"));

        card.addView(spacer(ctx, 10));
        card.addView(statusText(ctx, anchor));

        ScrollView scroller = TileStylePopup.scrollBody(ctx, card);
        scroller.setBackground(rounded(ctx, UiTheme.popupCard(ctx), CORNER_DP));

        PopupWindow popup = new PopupWindow(scroller,
                dp(ctx, WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        try {
            popup.setElevation(dp(ctx, 8));
        } catch (Throwable ignored) {
        }

        try {
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                    TileStylePopup.topMarginPx(ctx));
            sCurrent = popup;
            XposedBridge.log(TAG + ": header style panel shown");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": header style panel show failed - " + t);
        }
    }

    // ------------------------------------------------------------ 内容行

    /** 一行颜色项：标签 + 当前值 + 「选择 / 清除」。 */
    private static View colorRow(final View anchor, final Context ctx, final int kind,
            final String label, final String hint) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        final int current = NotifHeaderHook.getColor(ctx, kind);
        final boolean has = current != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;

        final TextView value = valueText(ctx);
        box.addView(labelRow(ctx, label, value, colorText(current)));

        View hintView = hint(ctx, hint);
        // hint 自带底部 padding，去掉一层让两行贴近些
        hintView.setPadding(dp(ctx, PADDING_H_DP), 0, dp(ctx, PADDING_H_DP), dp(ctx, 4));
        box.addView(hintView);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(ctx, 40), 1f);
        lp.rightMargin = dp(ctx, 6);
        buttons.addView(actionButton(ctx, has ? "更换颜色" : "选择颜色",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                        IconColorPickerPopup.showForNotifHeader(anchor, kind,
                                NotifHeaderHook.getColor(ctx, kind));
                    }
                }), lp);
        if (has) {
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    0, dp(ctx, 40), 1f);
            buttons.addView(actionButton(ctx, "跟随系统", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NotifHeaderHook.setColor(ctx, kind,
                            TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM);
                    NotifHeaderHook.refresh();
                    dismiss();
                    XposedBridge.log(TAG + ": " + label + " -> follow system");
                }
            }), lp2);
        }
        box.addView(buttons);
        return box;
    }

    private static String colorText(int argb) {
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return "跟随系统";
        }
        return String.format("#%08X", argb);
    }

    private static TextView statusText(Context ctx, View anchor) {
        TextView tv = new TextView(ctx);
        int font = NotifHeaderHook.getColor(ctx, NotifHeaderHook.KIND_FONT);
        int icon = NotifHeaderHook.getColor(ctx, NotifHeaderHook.KIND_ICON);
        boolean ok = anchor instanceof ViewGroup && ((ViewGroup) anchor).getChildCount() > 0;
        tv.setText("头部视图：已找到\n字体 " + colorText(font) + " · 图标 " + colorText(icon)
                + (ok ? "" : "\n头部视图还没挂好，重开通知界面后生效"));
        tv.setTextColor(UiTheme.hint(ctx));
        tv.setTextSize(11f);
        tv.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 4), dp(ctx, PADDING_H_DP), dp(ctx, 10));
        return tv;
    }

    // ------------------------------------------------------------ 视图小工具（与 QsBackgroundPopup 同款）

    private static View title(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(UiTheme.title(ctx));
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 16), dp(ctx, PADDING_H_DP), dp(ctx, 4));
        return tv;
    }

    private static View hint(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(UiTheme.hint(ctx));
        tv.setTextSize(11f);
        tv.setPadding(dp(ctx, PADDING_H_DP), 0, dp(ctx, PADDING_H_DP), dp(ctx, 10));
        return tv;
    }

    private static TextView valueText(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(UiTheme.ACCENT);
        tv.setTextSize(13f);
        tv.setSingleLine(true);
        return tv;
    }

    private static View labelRow(Context ctx, String label, TextView value, String valueText) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 8), dp(ctx, PADDING_H_DP), 0);

        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextColor(UiTheme.label(ctx));
        name.setTextSize(14f);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        value.setText(valueText);
        row.addView(value, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static View actionButton(Context ctx, String label, View.OnClickListener click) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(UiTheme.popupButtonText(ctx));
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rounded(ctx, UiTheme.popupButtonBg(ctx), 10));
        btn.setClickable(true);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42));
        lp.leftMargin = dp(ctx, PADDING_H_DP);
        lp.rightMargin = dp(ctx, PADDING_H_DP);
        lp.topMargin = dp(ctx, 4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    private static GradientDrawable rounded(Context ctx, int color, int cornerDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(ctx, cornerDp));
        return d;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
