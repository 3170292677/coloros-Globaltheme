package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Switch;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;

/**
 * 点 ★ 按钮弹出的配置面板。
 *
 * <p>外观仿 ColorOS 控制中心 ⋮ 按钮的弹窗：深色圆角卡片，每行一项。
 * 内容为本模块自定义：</p>
 * <ul>
 *   <li><b>编辑模式</b>——一个总开关同时管两件事：打开后所有形状的磁贴长按（含 2×2
 *       媒体卡片）都不再打开系统设置/应用、改为弹样式面板，控制中心<b>空白处</b>的长按
 *       也改为弹背景面板；</li>
 *   <li><b>压掉背景模糊</b>；</li>
 *   <li><b>导入主题包</b>——选一个 ColorOS 全局主题包，把里面的界面底图按形状套上去；</li>
 *   <li><b>强制重启系统界面</b>。</li>
 * </ul>
 *
 * <p>顶部原来还有一段「编辑模式」大标题 + 说明文字，和下面的开关行重复，已去掉。</p>
 */
public final class TileLongPressPopup {

    private static final String TAG = "SystemUIHook/LongPress";

    // 颜色一律从 UiTheme 取，跟着控制中心的深色开关走，见 UiTheme

    private static final int WIDTH_DP = 260;
    private static final int CORNER_DP = 16;
    private static final int PADDING_H_DP = 16;
    /** 带副标题的行高（两行文字，比单行高一点）。 */
    private static final int ROW_HEIGHT_TALL_DP = 54;
    /** 卡片顶部留白：原来这里是大标题，标题去掉后第一行文字会直接顶到卡片上沿。 */
    private static final int PADDING_TOP_DP = 8;
    /** 卡片底部留白：不留的话最后一行文字会贴着卡片下沿，看着像被切了。 */
    private static final int PADDING_BOTTOM_DP = 8;
    /** 卡片右边缘距屏幕右边的距离。 */
    private static final int SCREEN_MARGIN_DP = 12;

    /** 当前已弹出的实例，用于再次点击时收起。 */
    private static PopupWindow sCurrent;

    private TileLongPressPopup() {
    }

    /** 在 anchor（★ 按钮）下方弹出配置面板；已弹出时再次调用则收起。 */
    public static void toggle(View anchor) {
        if (sCurrent != null && sCurrent.isShowing()) {
            dismiss();
            return;
        }
        try {
            show(anchor);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": show failed - " + t);
        }
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

    private static void show(View anchor) {
        final Context ctx = anchor.getContext();

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedCard(ctx));

        // 顶部原来有一大段「编辑模式」标题 + 说明文字，和下面那行开关本身说的是同一件事，
        // 屏幕小的时候这两行白占地方，已整段去掉；只留一点上沿留白
        card.addView(spacer(ctx, PADDING_TOP_DP));

        // 所有形状（含右上角那排入口按钮、2×2 媒体卡片）合成一个总开关：逐形状那套开关太碎，
        // 且绝大多数人只想要「长按磁贴就进编辑」这一件事。
        // 控制中心空白背景的长按是另一条链路（EmptyAreaGestureProxy$1#onLongPress），
        // 原来单独占一行，现在并进这一个开关一起管
        card.addView(buildEditModeRow(ctx), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        card.addView(divider(ctx), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2)));
        card.addView(buildBlurRow(ctx), fixedHeight(ctx, ROW_HEIGHT_TALL_DP));

        // 导入主题包：选一个 ColorOS 全局主题包，把里面的界面底图按形状一次套上去。
        // 选文件必须由 Activity 来做（SystemUI 不是 Activity），所以点下去是拉起
        // 模块的透明入口去开文件选择器，见 TileStylePopup#openThemeImport
        card.addView(divider(ctx), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2)));
        card.addView(buildThemeImportButton(ctx));

        // 导出主题包：把当前配置打包成与导入格式一致的 ColorOS 主题包（备份 / 分享）。
        // 保存位置同样要由 Activity 来定，所以也走那条透明入口，见
        // TileStylePopup#openThemeExport
        card.addView(buildThemeExportButton(ctx));

        // 强制重启放在 ★ 弹窗里当兜底：底图 / 圆角 / 可见度改完本来就会自动重启一次，
        // 个别形状刷新不彻底时还能在这里手动来一遍
        card.addView(buildRestartButton(ctx));

        // 底部留白：不留的话最后一行文字会顶到卡片圆角下沿，看着像被切掉一截
        card.addView(spacer(ctx, PADDING_BOTTOM_DP));

        PopupWindow popup = new PopupWindow(card,
                dp(ctx, WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        try {
            popup.setElevation(dp(ctx, 8));
        } catch (Throwable ignored) {
        }

        // 卡片（260dp）比 ★ 按钮宽得多，按按钮右对齐会被挤出屏幕、贴到左边去。
        // 改成让卡片右边缘离屏幕右边固定 12dp，任何分辨率下位置都稳定
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int xoff = screenW - dp(ctx, SCREEN_MARGIN_DP) - (loc[0] + anchor.getWidth());
        popup.showAsDropDown(anchor, xoff, dp(ctx, 8));
        sCurrent = popup;
        XposedBridge.log(TAG + ": popup shown, shapes=" + TileLongPressConfig.ALL_SHAPES.length
                + ", xoff=" + xoff);
    }

    /** 固定高度的行参数（宽度撑满卡片）。 */
    private static LinearLayout.LayoutParams fixedHeight(Context ctx, int heightDp) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp));
    }

    /**
     * 一行：编辑模式总开关。
     *
     * <p>它替掉了原先按形状排的逐个开关：打开 = 所有形状（含 2×2 媒体卡片）的长按都弹样式面板，
     * 关闭 = 全部恢复系统原本的长按行为（打开设置 / 应用）。</p>
     *
     * <p>控制中心<b>空白处</b>的长按原先单独占一行（{@code QsBackgroundHook} 那条
     * {@code EmptyAreaGestureProxy$1#onLongPress} 链路），和这一行说的是同一件事
     * ——「长按不要再走系统原来那套」，所以并进这一个开关一起管。</p>
     */
    private static View buildEditModeRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // 这一行的副标题会换行成两三行，所以高度不能再按固定 54dp 写死（会把换行硬切）。
        // 改成撑开 + 最小高度兜底：短句时仍是原来的 54dp 观感，长句时自动长高
        row.setMinimumHeight(dp(ctx, ROW_HEIGHT_TALL_DP));
        row.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText("编辑模式");
        label.setTextColor(UiTheme.label(ctx));
        label.setTextSize(14f);
        label.setSingleLine(true);
        texts.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(ctx);
        // 这句之前被 singleLine + ellipsize 截成「……长按空白弹背…」，看着像坏掉了；
        // 这里放开成多行让它整句显示，并在末尾补上「换底图 / 颜色要重启才正常」的提醒
        sub.setText("长按磁贴弹样式面板 · 长按空白弹背景面板；"
                + "修改底图或颜色后要重启系统界面才会显示正常");
        sub.setTextColor(UiTheme.hint(ctx));
        sub.setTextSize(11f);
        texts.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        tintSwitch(sw);
        // 两件事都开着才算「开」：以前分开存，可能出现磁贴开着、空白处关着的中间态，
        // 合并后只要有一件没开就显示为关，点一下把两件一起打开
        sw.setChecked(TileLongPressConfig.isEditMode(ctx)
                && TileLongPressConfig.isBgBlocked(ctx));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                TileLongPressConfig.setEditMode(ctx, isChecked);
                TileLongPressConfig.setBgBlocked(ctx, isChecked);
                // 磁贴那边的开关是长按那一刻现读配置的，不用刷；但右上角那排入口按钮的
                // 长按监听是实打实挂在按钮上的，得让它们立刻跟着装上 / 摘掉
                QuickEntranceButtonHook.refresh();
                XposedBridge.log(TAG + ": edit mode → " + isChecked
                        + " (shapes=" + TileLongPressConfig.ALL_SHAPES.length
                        + ", qs blank long-press=" + isChecked + ")");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * 一行：压掉背景模糊开关。
     *
     * <p>打开后控制中心背景层上那层模糊整个让过去——系统壁纸和自己设的底图都按原样画。
     * 默认关闭，也就是保留系统自己的模糊。</p>
     */
    private static View buildBlurRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, PADDING_H_DP), 0, dp(ctx, 12), 0);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(ctx);
        label.setText("压掉背景模糊");
        label.setTextColor(UiTheme.label(ctx));
        label.setTextSize(14f);
        label.setSingleLine(true);
        texts.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(ctx);
        sub.setText("关闭则保留系统模糊");
        sub.setTextColor(UiTheme.hint(ctx));
        sub.setTextSize(11f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        tintSwitch(sw);
        sw.setChecked(TileLongPressConfig.isBgBlurOff(ctx));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                TileLongPressConfig.setBgBlurOff(ctx, isChecked);
                // 拦住模糊的那处钩子读的是进程内缓存，值已经更新；但底图不重画的话
                // 画面要等到下次动画才跟着变，这里主动重铺一次让它当场生效
                int count = QsBackgroundHook.refresh();
                XposedBridge.log(TAG + ": qs背景模糊压掉 → " + isChecked
                        + ", refreshed=" + count);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * 那颗「导入主题包」按钮。
     *
     * <p>选文件得由 Activity 来拿结果，SystemUI 不是 Activity，所以这里只负责把模块的
     * 透明入口拉起来（{@link TileStylePopup#openThemeImport}），用户在那边选完 zip 后
     * 由模块 App 解包、按形状逐条发回本进程，全部发完后系统界面会重启一次让配置生效。</p>
     */
    private static View buildThemeImportButton(final Context ctx) {
        TextView btn = new TextView(ctx);
        btn.setText("导入主题包");
        btn.setTextColor(UiTheme.accentText(ctx));
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedButton(ctx));
        btn.setClickable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                TileStylePopup.openThemeImport(ctx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42));
        lp.leftMargin = dp(ctx, PADDING_H_DP);
        lp.rightMargin = dp(ctx, PADDING_H_DP);
        lp.topMargin = dp(ctx, 8);
        btn.setLayoutParams(lp);
        return btn;
    }

    /**
     * 那颗「导出主题包」按钮。
     *
     * <p>把当前配置（各形状底图 + 颜色）打包成一个<b>与导入格式完全一致</b>的
     * ColorOS 主题包，方便备份 / 分享。选保存位置得由 Activity 来拿结果，
     * SystemUI 不是 Activity，所以这里只负责把模块的透明入口拉起来
     * （{@link TileStylePopup#openThemeExport}），用户在那边选完位置后由本进程
     * （SystemUI）自己读 prefs 打包写盘——底图全在它手里，模块 App 读不到。</p>
     */
    private static View buildThemeExportButton(final Context ctx) {
        TextView btn = new TextView(ctx);
        btn.setText("导出主题包");
        btn.setTextColor(UiTheme.accentText(ctx));
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedButton(ctx));
        btn.setClickable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                TileStylePopup.openThemeExport(ctx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42));
        lp.leftMargin = dp(ctx, PADDING_H_DP);
        lp.rightMargin = dp(ctx, PADDING_H_DP);
        lp.topMargin = dp(ctx, 8);
        btn.setLayoutParams(lp);
        return btn;
    }

    /**
     * 底部那颗「强制重启系统界面」按钮。
     *
     * <p>重启前由 {@link TileStylePopup#restartSystemUi} 把 prefs 落盘，
     * 不然刚改的圆角 / 可见度可能还在内存里，重启后就丢了。</p>
     */
    private static View buildRestartButton(final Context ctx) {
        TextView btn = new TextView(ctx);
        btn.setText("强制重启系统界面");
        btn.setTextColor(UiTheme.popupButtonText(ctx));
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(roundedButton(ctx));
        btn.setClickable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                TileStylePopup.restartSystemUi(ctx);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 42));
        lp.leftMargin = dp(ctx, PADDING_H_DP);
        lp.rightMargin = dp(ctx, PADDING_H_DP);
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 4);
        btn.setLayoutParams(lp);
        return btn;
    }

    /**
     * 给开关上色。
     *
     * <p>SystemUI 的窗口主题里没有 {@code colorAccent}，直接用平台 {@link Switch}
     * 会出现「打开状态却还是灰轨道」——看不出开没开。这里显式指定：
     * 打开 = 磁贴激活蓝轨道，关闭 / 滑块的灰度由 {@link UiTheme} 按深浅模式给。</p>
     */
    private static void tintSwitch(Switch sw) {
        Context ctx = sw.getContext();
        try {
            sw.setTrackTintList(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{},
                    },
                    new int[]{UiTheme.trackOn(ctx), UiTheme.trackOff(ctx)}));
            sw.setThumbTintList(ColorStateList.valueOf(UiTheme.thumb(ctx)));
        } catch (Throwable ignored) {
        }
    }

    private static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    private static View divider(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(UiTheme.divider(ctx));
        return v;
    }

    private static GradientDrawable roundedCard(Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(UiTheme.popupCard(ctx));
        d.setCornerRadius(dp(ctx, CORNER_DP));
        return d;
    }

    /** 按钮底：比卡片浅一档的圆角矩形。 */
    private static GradientDrawable roundedButton(Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(UiTheme.popupButtonBg(ctx));
        d.setCornerRadius(dp(ctx, 10));
        return d;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
