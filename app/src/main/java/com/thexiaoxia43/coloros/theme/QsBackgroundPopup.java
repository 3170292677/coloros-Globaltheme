package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * 长按控制中心<b>空白背景</b>后弹出的背景面板。
 *
 * <p>形态与 {@link TileStylePopup}（磁贴样式面板）保持一致，内容换成控制中心背景：
 * 选/清背景图 + 一条可见度滑杆。滑杆拖动即时生效，不需要等松手。</p>
 *
 * <p>选图复用磁贴那套链路：{@link TileStylePopup#openModuleApp} 把模块的透明选图页拉起来，
 * 选完图压缩成 Base64 发广播，SystemUI 里的 {@link TileImageChannel} 收到后
 * 写进自己的 prefs，再回调 {@link QsBackgroundHook#refresh()} 套上去。
 * 背景图在底图通道里占用的标识是 {@link PrefKeys#SHAPE_QS_BACKGROUND}。</p>
 *
 * <p>可见度的口径：<b>0% = 完全透明</b>（不遮挡，等于系统原样），
 * <b>100% = 完全不透明</b>（图片完全盖住面板）。默认 100%，
 * 也就是设了底图就直接铺满，想要透一点就往左拖。
 * 最高 200%：系统背景层自己就是半透明的，100% 压不满，再往上靠底图叠两层补。
 * 存进 prefs 的是反向的「透明度」= 100 - 可见度。</p>
 */
public final class QsBackgroundPopup {

    private static final String TAG = "SystemUIHook/QsBg";

    // 颜色一律从 UiTheme 取，跟着控制中心的深色开关走，见 UiTheme

    private static final int WIDTH_DP = 300;
    private static final int CORNER_DP = 16;
    private static final int PADDING_H_DP = 18;

    /** 当前已弹出的实例。 */
    private static PopupWindow sCurrent;

    private QsBackgroundPopup() {
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
     * 弹出控制中心背景面板。
     *
     * @param anchor 控制中心面板根视图（用于取窗口 token 与套用背景）
     * @param ctx    SystemUI 上下文
     */
    public static void show(final View anchor, final Context ctx) {
        if (anchor == null || ctx == null) {
            return;
        }
        dismiss();

        final int transparency = TileLongPressConfig.bgTransparencyPct(ctx);
        // 面板上一切读数都按「可见度」显示：可见度 = 100 - 透明度
        final int visibility = TileLongPressConfig.MAX_BG_TRANSPARENCY_PCT - transparency;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(title(ctx, "控制中心背景"));
        card.addView(hint(ctx, "空白处长按已拦截（原为系统编辑弹窗）"));
        final TextView status = statusText(ctx);
        renderStatus(status, ctx, null);

        // ---- 背景图片 ----
        final boolean hasImage = hasImage(ctx);
        card.addView(actionButton(ctx, hasImage ? "更换背景图片" : "选择背景图片（相册）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 关掉面板再去相册：留着的话弹窗会挂在控制中心上面挡视线
                        dismiss();
                        // 控制中心背景没有开 / 关之分，状态位随便给一个即可
                        TileStylePopup.openModuleApp(ctx, PrefKeys.SHAPE_QS_BACKGROUND, true);
                    }
                }));
        if (hasImage) {
            card.addView(actionButton(ctx, "清除背景图片", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 直接写 SystemUI 自己的 prefs 并刷新，不用再拉起模块 App
                    TileLongPressConfig.setShapeBgImage(ctx, PrefKeys.SHAPE_QS_BACKGROUND, "");
                    QsBackgroundHook.refresh();
                    dismiss();
                    XposedBridge.log(TAG + ": background image cleared");
                }
            }));
        }

        // ---- 背景颜色 ----
        // 颜色和底图是「叠」的关系：图片盖在颜色上面，透明处透出颜色来；
        // 没设图片时看到的就是这个纯色。见 TileBackgroundDrawable#colorUnderImage
        final int bgColor = TileLongPressConfig.qsBackgroundColor(ctx);
        final boolean hasColor = bgColor != TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        final TextView colorValue = valueText(ctx);
        card.addView(labelRow(ctx, "背景颜色", colorValue, colorText(bgColor)));
        card.addView(hint(ctx, "没设图片时看到的就是这个纯色；设了图片则垫在图片底下，透明处透出来"));
        card.addView(actionButton(ctx, hasColor ? "更换背景颜色" : "选择背景颜色",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 关掉面板再开取色弹窗，两个 popup 不叠在一起
                        dismiss();
                        IconColorPickerPopup.showForQsBackground(anchor, bgColor);
                    }
                }));
        if (hasColor) {
            card.addView(actionButton(ctx, "清除背景颜色", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 直接写 SystemUI 自己的 prefs 并刷新，不用再拉起模块 App
                    TileLongPressConfig.setQsBackgroundColor(ctx,
                            TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM);
                    QsBackgroundHook.refresh();
                    dismiss();
                    XposedBridge.log(TAG + ": background color cleared");
                }
            }));
        }

        card.addView(divider(ctx), dividerParams(ctx));

        // ---- 可见度 ----
        final TextView alphaValue = valueText(ctx);
        card.addView(labelRow(ctx, "背景可见度", alphaValue, alphaText(visibility)));
        // 滑杆行程 0–200%：0 = 完全透明（交还系统），100 = 完全不透明，
        // 再往上系统背景层自己就是半透明的，压不满，靠底图叠两层把不透明度补上去
        final SeekBar alphaSeek = seekBar(ctx);
        alphaSeek.setMax(TileLongPressConfig.MAX_BG_TRANSPARENCY_PCT
                - TileLongPressConfig.MIN_BG_TRANSPARENCY_PCT);
        alphaSeek.setProgress(visibility);
        alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                alphaValue.setText(alphaText(progress));
                if (!fromUser) {
                    return;
                }
                // 存的是透明度，可见度越高透明度越低
                TileLongPressConfig.setBgTransparencyPct(
                        ctx, TileLongPressConfig.MAX_BG_TRANSPARENCY_PCT - progress);
                // 边拖边生效，松手前就能看到背景变化
                int count = QsBackgroundHook.refresh();
                renderStatus(status, ctx, count == 0
                        ? "面板根视图未缓存，稍后重开控制中心生效" : null);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        card.addView(alphaSeek, blockParams(ctx));
        card.addView(hint(ctx, "0% = 完全透明（交还系统） · 100% = 完全不透明"
                + " · 200% = 叠两层补满"));

        card.addView(spacer(ctx, 10));
        // 调试信息放最后：它最长，摆中间会把下面的按钮顶到屏幕外 / 键盘底下
        card.addView(status);

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
            // 摆法与磁贴样式面板一致：贴屏幕上部 + 限高可滚动，别被键盘顶掉下半截
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                    TileStylePopup.topMarginPx(ctx));
            sCurrent = popup;
            XposedBridge.log(TAG + ": background panel shown, transparency=" + transparency
                    + ", image=" + hasImage);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": background panel show failed - " + t);
        }
    }

    // ------------------------------------------------------------ 视图小工具

    private static boolean hasImage(Context ctx) {
        return TileLongPressConfig.shapeBgImage(ctx, PrefKeys.SHAPE_QS_BACKGROUND).length() > 0;
    }

    /** 可见度读数（%）。 */
    private static String alphaText(int visibility) {
        return visibility + "%";
    }

    /** 背景纯色读数：{@code #AARRGGBB}，没设过就是「跟随系统」。 */
    private static String colorText(int argb) {
        if (argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return "跟随系统";
        }
        return String.format("#%08X", argb);
    }

    /**
     * 状态行：把配置、长按命中次数、根视图缓存与套用结果都摆出来，
     * 上机排查「拖了没反应」时一眼能看出卡在哪一环。
     */
    private static void renderStatus(final TextView tv, final Context ctx, final String action) {
        StringBuilder sb = new StringBuilder();
        sb.append("读到配置：可见度 ")
                .append(TileLongPressConfig.MAX_BG_TRANSPARENCY_PCT
                        - TileLongPressConfig.bgTransparencyPct(ctx)).append('%')
                .append(" · 背景色 ")
                .append(colorText(TileLongPressConfig.qsBackgroundColor(ctx)))
                .append(" · 模糊 ")
                .append(TileLongPressConfig.bgBlurOff() ? "压掉（★ 里改）" : "保留（★ 里改）")
                .append(" · 长按拦截 ")
                .append(TileLongPressConfig.isBgBlocked(ctx) ? "开（在 ★ 里改）" : "关（在 ★ 里改）");
        sb.append("\n").append(QsBackgroundHook.probe());
        if (action != null) {
            sb.append('\n').append(action);
        }
        tv.setText(sb.toString());
    }

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

    private static TextView statusText(Context ctx) {
        TextView tv = new TextView(ctx);
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
        row.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 6), dp(ctx, PADDING_H_DP), 0);

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

    private static SeekBar seekBar(Context ctx) {
        SeekBar bar = new SeekBar(ctx);
        try {
            bar.setProgressTintList(ColorStateList.valueOf(UiTheme.ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(UiTheme.thumb(ctx)));
        } catch (Throwable ignored) {
        }
        return bar;
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

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiTheme.divider(ctx));
        return v;
    }

    private static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    private static LinearLayout.LayoutParams blockParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, PADDING_H_DP - 6);
        lp.rightMargin = dp(ctx, PADDING_H_DP - 6);
        lp.bottomMargin = dp(ctx, 4);
        return lp;
    }

    private static LinearLayout.LayoutParams dividerParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2));
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 4);
        return lp;
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
