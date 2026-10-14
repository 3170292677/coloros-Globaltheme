package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 长按磁贴（且该形状已开启“替换长按选项”）后弹出的<b>样式面板</b>。
 *
 * <p>同一形状的磁贴共用一份配置，所以面板标题就是形状名。面板内容：</p>
 * <ul>
 *   <li>「开 / 关」切换：磁贴开着和关着各一套底图与可见度，切换后下面两项跟着换；
 *       <b>2×1 宽卡片没有这一栏</b>，它的底图与可见度已合并成一套；</li>
 *   <li>圆角滑杆：0–40dp，最左为“跟随系统”（1×1 保持圆形；胶囊跟随胶囊自身的圆角）；
 *       开 / 关共用；</li>
 *   <li>可见度滑杆：0–100%（100 = 完全遮住系统底图）；</li>
 *   <li>底图：从相册选图（走模块的透明选图页，不会把模块界面弹到前台）/ 清除底图；</li>
 *   <li>着色四项：图标底色 / 图标着色（激活）/ 图标着色 / 文字颜色，各自「挑颜色 +
 *       不透明度滑杆」，落在 {@link TileColorHook} 那几条链路上。设备卡片改完要重启
 *       系统界面才看得到（面板里有提示）；</li>
 *   <li>亮度 / 音量胶囊<b>分「背景 / 进度条」两页</b>：背景页管圆角、胶囊可见度与胶囊底图，
 *       进度条页管进度块自己的可见度与进度底图。两页的可见度是两份配置，互不影响；</li>
 *   <li>2×2 磁贴与其上的媒体播放卡片<b>共用一套配置</b>（{@code SHAPE_2X2}）：
 *       媒体卡片就是网格里那块 2×2，底图、圆角与着色都跟着 2×2 走。</li>
 * </ul>
 *
 * <p>圆角与可见度直接写 SystemUI 自己的 prefs（{@link TileLongPressConfig}），
 * 改完立即刷新该形状已缓存的磁贴视图。底图必须由 Activity 读相册（SystemUI 不是
 * Activity，拿不到选图结果），所以这里只负责带着形状标识与开 / 关状态把模块的
 * <b>透明选图页</b>拉起来，图片由它缩放压缩成 Base64 经广播送回 SystemUI 自己的 prefs；
 * 清除底图不需要选图结果，直接在 SystemUI 进程里改 prefs。</p>
 */
public final class TileStylePopup {

    private static final String TAG = "SystemUIHook/LongPress";

    // 颜色一律从 UiTheme 取，跟着控制中心的深色开关走，见 UiTheme

    private static final int WIDTH_DP = 300;
    private static final int CORNER_DP = 16;
    private static final int PADDING_H_DP = 18;
    /** 面板最高占屏幕的比例：留出键盘（约四成高）的位置，别把按钮压到键盘底下。 */
    private static final float MAX_HEIGHT_RATIO = 0.55f;
    /** 面板距屏幕顶部的比例。不贴顶（会被键盘顶掉下半截），也不居中（面板比屏幕还高会被裁）。 */
    private static final float TOP_MARGIN_RATIO = 0.34f;

    /** 收起控制中心后等它滑完再启动相册；不等的话新界面会开在还在退场的面板后面。 */
    private static final long SHADE_COLLAPSE_WAIT_MS = 400L;

    /**
     * 样式面板的分页：第 1 页（默认页）。
     *
     * <p>只有亮度 / 音量胶囊分页：这一页是「背景」（圆角 + 胶囊可见度 + 胶囊底图），
     * 第 2 页是「进度条」（见 {@link #PAGE_PROGRESS}）。一根胶囊里叠了两层
     * （深色胶囊 + 白色进度），两层各有各的底图与可见度，挤在一页里分不清谁管谁。</p>
     */
    private static final int PAGE_BACKGROUND = 0;
    /** 样式面板的分页：第 2 页（亮度 / 音量胶囊的「进度条」）。 */
    private static final int PAGE_PROGRESS = 1;

    /** 当前已弹出的实例。 */
    private static PopupWindow sCurrent;
    /** 触发长按的那块磁贴对应的 ActivityStarter，用于拉起模块设置页。 */
    private static Object sStarter;

    private TileStylePopup() {
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
     * 在屏幕中间弹出该形状的样式面板。
     *
     * @param anchor         触发长按的磁贴视图（只用于取窗口 token）
     * @param shape          形状标识
     * @param requestedState 当前在编辑「开」还是「关」那一套配置；
     *                       不分套的形状（2×1）会被归一成「开」，见
     *                       {@link TileLongPressConfig#stateOf(String, boolean)}
     * @param starter        QSTileImpl 的 ActivityStarter，可为 null
     * @return 面板是否真的弹出来了。调用方（长按拦截）据此决定要不要吞掉长按：
     *         面板都没弹出来还把长按吞了，用户会觉得「长按没反应」
     */
    public static boolean show(final View anchor, final String shape, final boolean requestedState,
            final Object starter) {
        return show(anchor, shape, requestedState, starter, PAGE_BACKGROUND);
    }

    /**
     * 同上，但可以指定打开哪一页。
     *
     * <p>只有亮度 / 音量胶囊分页，其余形状忽略这个参数。</p>
     *
     * @param requestedPage 见 {@link #PAGE_BACKGROUND} / {@link #PAGE_PROGRESS}
     */
    public static boolean show(final View anchor, final String shape, final boolean requestedState,
            final Object starter, final int requestedPage) {
        if (anchor == null || shape == null || shape.length() == 0) {
            return false;
        }
        dismiss();
        sStarter = starter;

        final Context ctx = anchor.getContext();
        // 只有胶囊分「背景 / 进度条」，其余形状恒在第 1 页
        final boolean paged = TileLongPressConfig.SHAPE_SLIDER.equals(shape);
        final int page = paged && requestedPage == PAGE_PROGRESS
                ? PAGE_PROGRESS : PAGE_BACKGROUND;
        final String targetShape = shape;
        // 进度条页是胶囊独有的：只有「胶囊 + 第 2 页」才是那一页
        final boolean progressPage = TileLongPressConfig.SHAPE_SLIDER.equals(shape)
                && page == PAGE_PROGRESS;
        final boolean backgroundPage = !progressPage;

        // 不分套的形状（2×1）恒按「开」那一套读 / 写；顺手把旧的「关」那套并过来清掉
        final boolean split = TileLongPressConfig.hasStateVariants(targetShape);
        final boolean active = TileLongPressConfig.stateOf(targetShape, requestedState);
        if (!split) {
            TileLongPressConfig.collapseStates(ctx, targetShape);
        }

        final TileLongPressConfig.Style style = TileLongPressConfig.styleOf(ctx, targetShape);
        final int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        final int alphaPct = style != null
                ? style.alphaPct(active) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        final int progressAlphaPct = style != null
                ? style.progressAlphaPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        final boolean hasImage =
                TileLongPressConfig.tileBgImage(ctx, targetShape, active).length() > 0;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);

        card.addView(title(ctx, TileLongPressConfig.label(targetShape) + " · 样式"));
        card.addView(hint(ctx, "该形状的磁贴长按已被拦截，这里统一调整它们的底图"));
        if (split) {
            card.addView(stateTabs(ctx, anchor, shape, active, starter));
        }
        if (paged) {
            card.addView(pageTabs(ctx, anchor, shape, active, starter, page));
        }
        final TextView status = statusText(ctx);
        renderStatus(status, ctx, targetShape, active, null);

        // ---- 圆角（开 / 关共用）----
        // 「清除全部」是固定正圆的按钮，圆角无从谈起，整段略过
        if (!TileLongPressConfig.SHAPE_CLEAR.equals(targetShape) && backgroundPage) {
            final TextView cornerValue = valueText(ctx);
            card.addView(labelRow(ctx, "圆角", cornerValue, cornerText(cornerDp)));
            final SeekBar cornerSeek = seekBar(ctx);
            cornerSeek.setMax(TileLongPressConfig.CORNER_MAX_DP + 1);
            cornerSeek.setProgress(cornerDp + 1);
            cornerValue.setText(cornerText(cornerDp));
            cornerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int dp = progress - 1;
                    cornerValue.setText(cornerText(dp));
                    if (fromUser) {
                        TileLongPressConfig.setCorner(ctx, targetShape, dp);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    int count = TileLongPressInterceptor.refreshShape(targetShape);
                    renderStatus(status, ctx, targetShape, active, "上次刷新：" + count + " 块磁贴");
                }
            });
            card.addView(cornerSeek, blockParams(ctx));
        }

        // ---- 背景页：可见度（分套的形状开 / 关各一套，2×1 只有一套）----
        // 胶囊的进度条有自己的一份可见度，在这一页只放背景那一份
        if (backgroundPage) {
            final TextView alphaValue = valueText(ctx);
            // 「背景可见度」这个名字只在胶囊那边用：它一根胶囊里还有进度那一份可见度，
            // 得靠名字区分开；2×2 的媒体页就是它自己唯一的「可见度」
            card.addView(labelRow(ctx,
                    split ? "可见度（" + stateName(active) + "）"
                            : (TileLongPressConfig.SHAPE_SLIDER.equals(targetShape)
                                    ? "背景可见度" : "可见度"),
                    alphaValue, alphaText(alphaPct)));
            final SeekBar alphaSeek = seekBar(ctx);
            alphaSeek.setMax(100);
            alphaSeek.setProgress(alphaPct);
            alphaValue.setText(alphaText(alphaPct));
            alphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    alphaValue.setText(alphaText(progress));
                    if (fromUser) {
                        TileLongPressConfig.setAlpha(ctx, targetShape, active, progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    int count = TileLongPressInterceptor.refreshShape(targetShape);
                    renderStatus(status, ctx, targetShape, active, "上次刷新：" + count + " 块磁贴");
                }
            });
            card.addView(alphaSeek, blockParams(ctx));
        }

        // ---- 图标底色 / 图标着色 / 文字颜色 ----
        // 四项的交互一模一样（挑颜色 + 不透明度），只有落到哪个字段不同，
        // 所以共用 addTintBlock；「图标底色」只有 2×1 宽卡片与三阶段卡片有那块色块
        if (backgroundPage) {
            if (TileLongPressConfig.supportsIconColor(targetShape)) {
                addTintBlock(card, ctx, anchor, targetShape, active, starter,
                        TileLongPressConfig.TINT_ICON_BG, style, status);
            }
            if (TileLongPressConfig.supportsTint(targetShape)) {
                card.addView(divider(ctx), dividerParams(ctx));
                // 方形卡片与 1×2 设备卡片是同一格配置、同一条取色链路：颜色都在卡片重建时
                // 才被取走，没有可以当场重跑的方法，所以两张卡都要把这句话说在前面
                if (TileLongPressConfig.SHAPE_CARD.equals(targetShape)
                        || TileLongPressConfig.SHAPE_CARD_WIDE.equals(targetShape)) {
                    card.addView(hint(ctx,
                            "设备卡片要拉一次控制中心才重取色，没变的话重启系统界面"));
                }
                // 开 / 关两态真的是两副面孔的只有三阶段卡片（选中那一段、未选中那两段），
                // 只有它的面板出「图标激活」；其余形状的图标只有一副面孔，
                // 面板里只出一项「图标颜色」，开 / 关两态都吃它
                if (TileLongPressConfig.SHAPE_THREE.equals(targetShape)) {
                    addTintBlock(card, ctx, anchor, targetShape, active, starter,
                            TileLongPressConfig.TINT_ICON_ON, style, status);
                }
                addTintBlock(card, ctx, anchor, targetShape, active, starter,
                        TileLongPressConfig.TINT_ICON_OFF, style, status);
                if (supportsLabelColor(targetShape)) {
                    addTintBlock(card, ctx, anchor, targetShape, active, starter,
                            TileLongPressConfig.TINT_LABEL, style, status);
                }
            }
        }

        // ---- 背景页：底图（分套的形状开 / 关各一张）----
        if (backgroundPage) {
            card.addView(divider(ctx), dividerParams(ctx));
            card.addView(hint(ctx, (split ? stateName(active) + " 的底图：" : "底图：")
                    + (hasImage ? "已设置" : "未设置")));
            card.addView(actionButton(ctx, "从相册选择底图", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    openModuleApp(ctx, targetShape, active);
                }
            }));
            card.addView(actionButton(ctx, "清除底图", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    // 清底图不需要选图结果，直接在本进程写 prefs；clearBgImage 内部会重启
                    // 系统界面，让所有视图带着「没有底图」重建
                    TileImageChannel.clearBgImage(ctx, targetShape, active);
                }
            }));
        }

        // ---- 通知卡片专属：文字颜色（只落在通知的标题与正文上）----
        if (TileLongPressConfig.SHAPE_NOTIFICATION.equals(targetShape)) {
            card.addView(divider(ctx), dividerParams(ctx));
            card.addView(hint(ctx, "以下只作用于通知的标题与正文；时间与底部按钮不受影响"));
            // 颜色复用「文字颜色」那条通道（存 TINT_LABEL），落点由 NotificationTextHook 决定
            addTintBlock(card, ctx, anchor, targetShape, active, starter,
                    TileLongPressConfig.TINT_LABEL, style, status);
        }

        // ---- 进度条页（只有亮度 / 音量胶囊有这一页）----
        // 上面那张底图铺的是整根胶囊、进度那块被挖空，露出的是系统画的进度色。
        // 想给进度块本身换图得单独一张：它不是磁贴、也没有开 / 关之分，所以借形状位
        // 存一份（见 PrefKeys#SHAPE_SLIDER_PROGRESS）。圆角跟胶囊共用，可见度是这一页
        // 自己那一个（Key#KEY_PROGRESS_ALPHA），跟背景页互不影响。
        if (progressPage) {
            final boolean hasProgressImage = TileLongPressConfig.shapeBgImage(
                    ctx, PrefKeys.SHAPE_SLIDER_PROGRESS).length() > 0;
            final TextView progAlphaValue = valueText(ctx);
            card.addView(labelRow(ctx, "进度条可见度", progAlphaValue,
                    alphaText(progressAlphaPct)));
            final SeekBar progAlphaSeek = seekBar(ctx);
            progAlphaSeek.setMax(100);
            progAlphaSeek.setProgress(progressAlphaPct);
            progAlphaValue.setText(alphaText(progressAlphaPct));
            progAlphaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    progAlphaValue.setText(alphaText(progress));
                    if (fromUser) {
                        TileLongPressConfig.setProgressAlpha(ctx, shape, progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    int count = TileLongPressInterceptor.refreshShape(targetShape);
                    renderStatus(status, ctx, targetShape, active, "上次刷新：" + count + " 块磁贴");
                }
            });
            card.addView(progAlphaSeek, blockParams(ctx));

            card.addView(divider(ctx), dividerParams(ctx));
            card.addView(hint(ctx, "进度底图：" + (hasProgressImage ? "已设置" : "未设置")));
            card.addView(hint(ctx, "进度条那一段的图；设了之后系统那条进度色会被压掉，"
                    + "圆角与胶囊一致"));
            card.addView(actionButton(ctx, "从相册选择进度底图", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    openModuleApp(ctx, PrefKeys.SHAPE_SLIDER_PROGRESS, true);
                }
            }));
            card.addView(actionButton(ctx, "清除进度底图", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    // 同「清除底图」：本进程写 prefs，再由 clearBgImage 重启系统界面
                    TileImageChannel.clearBgImage(ctx, PrefKeys.SHAPE_SLIDER_PROGRESS, true);
                }
            }));
        }
        // ---- 颜色区分（只有 2×1 与三阶段有这块色块）----
        // 让自定义图标底色和卡片底色拉开一点差别：暗色模式往黑偏、亮色模式往白偏，
        // 只动色相亮度、不动不透明度
        if (backgroundPage && (TileLongPressConfig.SHAPE_2X1.equals(targetShape)
                || TileLongPressConfig.SHAPE_THREE.equals(targetShape))) {
            card.addView(divider(ctx), dividerParams(ctx));
            card.addView(switchRow(ctx, "颜色区分",
                    "暗色模式稍暗、亮色模式稍亮，和卡片底色区分开",
                    TileLongPressConfig.isColorDiff(ctx),
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            TileLongPressConfig.setColorDiff(ctx, isChecked);
                            int count = IconColorHook.refresh(targetShape);
                            renderStatus(status, ctx, targetShape, active,
                                    "颜色区分" + (isChecked ? "开" : "关")
                                            + "：" + count + " 块");
                        }
                    }));
        }

        card.addView(spacer(ctx, 10));
        // 调试信息放最后：它最长（十来行），摆在中间会把下面的按钮顶到屏幕外 / 键盘底下
        card.addView(status);

        ScrollView scroller = scrollBody(ctx, card);
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
            // 贴屏幕上部显示而不是居中：面板本身就比屏幕高，居中后下半截会落到屏幕外；
            // 输颜色码时键盘一弹（约屏幕四成高），下面那几个按钮就彻底点不到了。
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                    topMarginPx(ctx));
            sCurrent = popup;
            XposedBridge.log(TAG + ": style panel shown, shape=" + shape
                    + ", target=" + targetShape + ", page=" + page
                    + ", state=" + (active ? "on" : "off")
                    + ", corner=" + cornerDp + ", alpha=" + alphaPct + ", image=" + hasImage);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": style panel show failed - " + t);
            return false;
        }
    }

    /**
     * 面板距屏幕顶部的像素值。
     *
     * <p>两个面板（磁贴样式、控制中心背景）共用同一套摆法，位置也就统一了。</p>
     */
    static int topMarginPx(Context ctx) {
        return (int) (ctx.getResources().getDisplayMetrics().heightPixels * TOP_MARGIN_RATIO);
    }

    /**
     * 把面板内容套进一个<b>限高可滚动</b>的容器。
     *
     * <p>面板内容比屏幕还高，直接 WRAP_CONTENT 铺出去会被屏幕裁掉；再遇上输入颜色码时
     * 弹出的键盘，下半截按钮就正好压在键盘底下——点了没反应，看着像「没保存」。
     * 限高之后内容能滚，键盘弹出来也还够得着按钮。</p>
     *
     * <p>控制中心背景面板也用这个，见 {@link QsBackgroundPopup}。</p>
     */
    static ScrollView scrollBody(final Context ctx, final View content) {
        ScrollView scroller = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                int cap = (int) (getResources().getDisplayMetrics().heightPixels
                        * MAX_HEIGHT_RATIO);
                if (getMeasuredHeight() > cap) {
                    setMeasuredDimension(getMeasuredWidth(), cap);
                }
            }
        };
        scroller.addView(content);
        scroller.setFillViewport(true);
        return scroller;
    }

    /** 「开 / 关」两个状态名。 */
    private static String stateName(boolean active) {
        return active ? "开" : "关";
    }

    /**
     * 分段切换里的一段要干的事。
     *
     * <p>Java 8 写不了 lambda，用一个只有单方法的接口代替 {@code Runnable}，
     * 「切页」这个语义比 {@code Runnable} 直白。</p>
     */
    private interface TabAction {
        void onSelect();
    }

    /** 分段切换的一行。 */
    private static LinearLayout tabRow(final Context ctx) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 4), dp(ctx, PADDING_H_DP), dp(ctx, 6));
        return row;
    }

    private static View tab(final Context ctx, final String label, final boolean selected,
            final TabAction action) {
        TextView tab = new TextView(ctx);
        tab.setText(label);
        // 选中态底是主色蓝，两种模式下都配白字
        tab.setTextColor(selected ? 0xFFFFFFFF : UiTheme.label(ctx));
        tab.setTextSize(13f);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(rounded(ctx,
                selected ? UiTheme.ACCENT : UiTheme.popupButtonBg(ctx), 8));
        tab.setClickable(true);
        if (!selected) {
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.onSelect();
                }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(ctx, 34), 1f);
        lp.leftMargin = dp(ctx, 3);
        lp.rightMargin = dp(ctx, 3);
        tab.setLayoutParams(lp);
        return tab;
    }

    /**
     * 「开 / 关」分段切换：点哪一段就重新弹出面板，编辑那一段的配置。
     *
     * <p>圆角是形状级的（开 / 关共用），所以切换后圆角滑杆位置不变；
     * 可见度与底图会换成对应那套。</p>
     */
    private static View stateTabs(final Context ctx, final View anchor, final String shape,
            final boolean active, final Object starter) {
        LinearLayout row = tabRow(ctx);
        row.addView(stateTab(ctx, anchor, shape, active, starter, false));
        row.addView(stateTab(ctx, anchor, shape, active, starter, true));
        return row;
    }

    private static View stateTab(final Context ctx, final View anchor, final String shape,
            final boolean active, final Object starter, final boolean tabActive) {
        return tab(ctx, stateName(tabActive), tabActive == active, new TabAction() {
            @Override
            public void onSelect() {
                show(anchor, shape, tabActive, starter);
            }
        });
    }

    /**
     * 分页切换（只有亮度 / 音量胶囊有两页）。
     *
     * <p>点哪一段就重新弹出面板、只铺那一页的内容，两页里的配置互不相干：
     * 背景页管圆角、胶囊可见度与胶囊底图；进度条页管进度自己的可见度与进度底图。</p>
     */
    private static View pageTabs(final Context ctx, final View anchor, final String shape,
            final boolean active, final Object starter, final int page) {
        LinearLayout row = tabRow(ctx);
        row.addView(pageTab(ctx, anchor, shape, active, starter, PAGE_BACKGROUND, "背景", page));
        row.addView(pageTab(ctx, anchor, shape, active, starter, PAGE_PROGRESS, "进度条", page));
        return row;
    }

    private static View pageTab(final Context ctx, final View anchor, final String shape,
            final boolean active, final Object starter, final int tabPage, final String label,
            final int page) {
        return tab(ctx, label, tabPage == page, new TabAction() {
            @Override
            public void onSelect() {
                show(anchor, shape, active, starter, tabPage);
            }
        });
    }

    /**
     * 强制重启系统界面（★ 弹窗底部那个按钮、以及底图存好之后自动走一次）。
     *
     * <p>样式是在视图创建时套上去的，改完配置后已经画出来的视图不会自己重来一遍。
     * SystemUI 由系统托管，进程被杀掉后会被立刻重新拉起，等价于「重启系统界面」，
     * 比逐个刷新视图干净：所有视图重新创建，样式链路完整走一遍。</p>
     *
     * <p>杀进程前先把 prefs 落盘：圆角 / 可见度这些走的是异步写，不刷一下的话
     * 刚改的配置可能还在内存里，重启后就丢了。</p>
     */
    static void restartSystemUi(final Context ctx) {
        TileLongPressConfig.flush(ctx);
        XposedBridge.log(TAG + ": restart SystemUI (prefs flushed)");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * 拉起模块的选图入口去相册选底图。
     *
     * <p>SystemUI 不是 Activity，拿不到 {@code startActivityForResult} 的结果，
     * 所以选图交给模块 App：选完图片后缩放压缩成 Base64 发广播，SystemUI 里的
     * {@link TileImageChannel} 收到后写进自己的 prefs。</p>
     *
     * <p>走的是 {@link PrefKeys#ACTIVITY_PICK_CLASS} 这个 activity-alias（透明主题），
     * 用户只会看到相册被打开，不会看到模块 App 的界面从后台弹出来。</p>
     */
    static void openModuleApp(final Context ctx, final String shape, final boolean active) {
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(HookPrefs.MODULE_PACKAGE, PrefKeys.ACTIVITY_PICK_CLASS);
            intent.putExtra(PrefKeys.EXTRA_PICK_SHAPE, shape);
            // 「开 / 关」各一套底图：让选图页知道选完的图要写到哪一套
            intent.putExtra(PrefKeys.EXTRA_PICK_STATE,
                    active ? PrefKeys.STATE_ON : PrefKeys.STATE_OFF);
            openTransparentEntry(ctx, intent, "pick activity, shape=" + shape);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": open module app failed - " + t);
            toast(ctx, "打不开模块设置页：" + t);
        }
    }

    /**
     * 拉起模块的透明入口去<b>导出主题包</b>（★ 弹窗里那颗「导出主题包」）。
     *
     * <p>和导入 / 选底图走同一个别名，多带一位 {@link PrefKeys#EXTRA_PICK_THEME_EXPORT}：
     * 那边看到这一位就开系统「新建文档」页让用户选保存位置，拿到地址后转授写权限、
     * 广播一条 {@link PrefKeys#ACTION_THEME_EXPORT} 请 SystemUI 自己打包写盘
     * （形状底图 / 颜色都存在 SystemUI 的 prefs 里，模块 App 读不到）。</p>
     */
    static void openThemeExport(final Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(HookPrefs.MODULE_PACKAGE, PrefKeys.ACTIVITY_PICK_CLASS);
            intent.putExtra(PrefKeys.EXTRA_PICK_THEME_EXPORT, true);
            openTransparentEntry(ctx, intent, "theme export");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": open theme export failed - " + t);
            toast(ctx, "打不开模块设置页：" + t);
        }
    }

    /**
     * 拉起模块的透明入口去<b>选主题包</b>（★ 弹窗里那颗「导入主题包」）。
     *
     * <p>和选底图走同一个别名，只是多带一位 {@link PrefKeys#EXTRA_PICK_THEME}：
     * 那边看到这一位就不开相册、改开系统文件选择器。解包与写回同样由模块 App 完成，
     * 用户在控制中心这边只会看到文件选择器和导入结果提示。</p>
     */
    static void openThemeImport(final Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(HookPrefs.MODULE_PACKAGE, PrefKeys.ACTIVITY_PICK_CLASS);
            intent.putExtra(PrefKeys.EXTRA_PICK_THEME, true);
            openTransparentEntry(ctx, intent, "theme import");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": open theme import failed - " + t);
            toast(ctx, "打不开模块设置页：" + t);
        }
    }

    /**
     * 把模块的透明入口拉起来。
     *
     * <p><b>顺序很重要</b>：必须先收起控制中心、<b>等它滑完</b>再启动。控制中心是
     * {@code TYPE_NOTIFICATION_SHADE} 系统窗口，一直盖在应用窗口之上；
     * 不收起来的话新 Activity 会开在它后面，用户看到的就是「点了按钮没反应」；
     * 收起来了但当场启动，新界面也会开在「还在往下滑」的面板后面，同样是「没反应」。
     * 收不起来时直接启动，再回落到磁贴那个 {@code ActivityStarter}
     * （它内部 {@code dismissShade = true}，也会顺手收起控制中心）。</p>
     */
    private static void openTransparentEntry(final Context ctx, final Intent intent,
            final String what) {
        // 从非 Activity 上下文启动必须带 NEW_TASK，否则直接抛 AndroidRuntimeException
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // 只记日志、不拦截：包可见性等情况下 resolveActivity 可能拿不到，
        // 但真正 startActivity 仍然能成
        try {
            if (ctx.getPackageManager().resolveActivity(intent, 0) == null) {
                XposedBridge.log(TAG + ": entry not resolvable: "
                        + HookPrefs.MODULE_PACKAGE + "/" + PrefKeys.ACTIVITY_PICK_CLASS);
            }
        } catch (Throwable ignored) {
        }

        // ① 先收起控制中心，别让新 Activity 开在它后面
        final boolean collapsed = QsBackgroundHook.collapseShade();
        if (!collapsed) {
            XposedBridge.log(TAG + ": shade not collapsed, start right away, " + what);
            startPickActivity(ctx, intent, what);
            return;
        }

        // ② 收起是带几百毫秒动画的。当场 startActivity 的话，新界面会开在「还在往下滑」
        //    的面板后面，看着就像点了没反应。等面板滑完再启动，顺便再补收一次兜底。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                QsBackgroundHook.collapseShade();
                startPickActivity(ctx, intent, what);
            }
        }, SHADE_COLLAPSE_WAIT_MS);
        XposedBridge.log(TAG + ": shade collapsing, " + what + " in "
                + SHADE_COLLAPSE_WAIT_MS + "ms");
    }

    /** 真正把模块的透明入口拉起来：普通 startActivity → ActivityStarter → Toast 三级兜底。 */
    private static void startPickActivity(final Context ctx, final Intent intent,
            final String what) {
        try {
            // 普通 startActivity。SystemUI 持有 START_ACTIVITIES_FROM_BACKGROUND，
            // 后台启动限制对它不生效
            ctx.startActivity(intent);
            XposedBridge.log(TAG + ": opened transparent entry via startActivity, " + what);
            return;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": startActivity failed - " + t);
        }
        // 兜底：磁贴长按面板里抓到的 ActivityStarter（它内部 dismissShade = true）
        Object starter = sStarter;
        if (starter != null) {
            try {
                XposedHelpers.callMethod(starter, "postStartActivityDismissingKeyguard",
                        intent, Integer.valueOf(0));
                XposedBridge.log(TAG + ": opened transparent entry via ActivityStarter, " + what);
                return;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ActivityStarter failed - " + t);
            }
        }
        toast(ctx, "打不开模块设置页，请手动打开「System UI Hook」应用");
    }

    /** 尽力提示一句；SystemUI 里 Toast 可能被拦，失败就算了。 */
    private static void toast(Context ctx, String text) {
        try {
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 视图小工具

    private static String cornerText(int cornerDp) {
        if (cornerDp < 0) {
            return "跟随系统";
        }
        return cornerDp + "dp";
    }

    private static String alphaText(int pct) {
        return pct + "%";
    }

    /** 着色值（图标底色 / 图标着色 / 文字颜色）的显示文案：跟随系统，或 {@code #RRGGBB}。 */
    private static String colorText(int color) {
        if (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return "跟随系统";
        }
        return String.format("#%06X", color & 0x00FFFFFF);
    }

    /**
     * 一项「着色」设置：标题 + 当前值 + 说明 + 选择按钮 + 不透明度滑杆。
     *
     * <p>「图标底色」「图标着色（激活）」「图标着色」「文字颜色」四项的交互完全一样，
     * 差别只有文案和落到哪个字段（{@code kind}），所以合在一起写。</p>
     *
     * <p>点「选择」先关掉本面板再弹取色面板：取色面板自己就是弹窗（预设色块 + 色板 +
     * 色相条 + 颜色码），塞进样式面板里会把面板撑得老高。</p>
     */
    private static void addTintBlock(final LinearLayout card, final Context ctx, final View anchor,
            final String shape, final boolean active, final Object starter, final int kind,
            final TileLongPressConfig.Style style, final TextView status) {
        final String label = TileLongPressConfig.tintLabel(shape, kind);
        final int color = style != null
                ? style.tintColor(kind) : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        final int alphaPct = style != null
                ? style.tintAlphaPct(kind) : TileLongPressConfig.DEFAULT_ICON_ALPHA_PCT;

        final TextView value = valueText(ctx);
        card.addView(labelRow(ctx, label, value, colorText(color)));
        card.addView(hint(ctx, TileLongPressConfig.tintHint(shape, kind)));
        card.addView(actionButton(ctx, "选择" + label, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                IconColorPickerPopup.show(anchor, shape, active, starter, kind, color);
            }
        }));

        final TextView alphaValue = valueText(ctx);
        card.addView(labelRow(ctx, label + "不透明度", alphaValue, alphaText(alphaPct)));
        final SeekBar seek = seekBar(ctx);
        seek.setMax(100);
        seek.setProgress(alphaPct);
        alphaValue.setText(alphaText(alphaPct));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                alphaValue.setText(alphaText(progress));
                if (fromUser) {
                    TileLongPressConfig.setTintAlpha(ctx, shape, kind, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                int count = TileLongPressInterceptor.refreshShape(shape);
                renderStatus(status, ctx, shape, active, "上次刷新：" + count + " 块磁贴");
            }
        });
        card.addView(seek, blockParams(ctx));
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

    /**
     * 这个形状的面板要不要出「文字颜色」那一项。
     *
     * <p>改不动的就不摆出来。2×2 磁贴（含媒体播放卡片）的标签不是 {@code QsLabelView}
     * ——它是系统自己拼的面板视图，{@link TileColorHook} 的文字着色钩子收不到，
     * 改了没有任何反应。亮度 / 音量胶囊与右上角那排小圆钮本来就没有标签文字，同理不出。</p>
     */
    private static boolean supportsLabelColor(final String shape) {
        return !TileLongPressConfig.SHAPE_2X2.equals(shape)
                && !TileLongPressConfig.SHAPE_SLIDER.equals(shape)
                && !TileLongPressConfig.SHAPE_ENTRANCE.equals(shape);
    }

    /**
     * 状态行：把 SystemUI 侧真正读到的配置、底图 hook 的命中情况与套用结果显示出来。
     *
     * <p>上机排查用——底图“选了但没效果”时，一眼就能看出是配置没读到、
     * 底图 hook 没跑、还是跑到了但没套用到磁贴上。</p>
     */
    private static void renderStatus(final TextView tv, final Context ctx, final String shape,
            final boolean active, final String action) {
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(ctx, shape);
        int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        int onPct = style != null ? style.alphaOnPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        int offPct = style != null ? style.alphaOffPct : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        int onKb = TileLongPressConfig.tileBgImage(ctx, shape, true).length() * 3 / 4 / 1024;
        int offKb = TileLongPressConfig.tileBgImage(ctx, shape, false).length() * 3 / 4 / 1024;
        String spec = TileBackgroundHook.lastSpec();
        String applied = TileBackgroundHook.lastAppliedShape();

        // 入口按钮不是磁贴：下面那一堆磁贴侧诊断（spec、形状判定、轮廓圆角、sSpecView 块数）
        // 对它全都不适用，那几行说的都是磁贴。这里只报它自己那点状态：
        // 这套配置读到了什么 + QuickEntranceButtonHook 那边收了几颗、套了几次
        if (TileLongPressConfig.SHAPE_ENTRANCE.equals(shape)) {
            StringBuilder own = new StringBuilder();
            own.append("读到配置：圆角 ").append(cornerText(cornerDp)).append("（单套）");
            own.append("\n底图：").append(onKb > 0 ? onKb + "KB" : "无底图")
                    .append(" · 可见度 ").append(onPct).append('%');
            own.append("\n").append(TileLongPressConfig.tintLabel(shape,
                    TileLongPressConfig.TINT_ICON_OFF)).append("：")
                    .append(colorText(style != null
                            ? style.tintColor(TileLongPressConfig.TINT_ICON_OFF)
                            : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM));
            own.append("\n").append(QuickEntranceButtonHook.probeText());
            if (action != null) {
                own.append('\n').append(action);
            }
            tv.setText(own.toString());
            return;
        }

        StringBuilder sb = new StringBuilder();
        boolean split = TileLongPressConfig.hasStateVariants(shape);
        sb.append("读到配置：圆角 ").append(cornerText(cornerDp))
                .append(split ? "（开 / 关共用）" : "（单套）");
        if (split) {
            sb.append(" · 当前编辑「").append(stateName(active)).append('」');
            sb.append("\n  开：").append(onKb > 0 ? "底图 " + onKb + "KB" : "无底图")
                    .append(" · 可见度 ").append(onPct).append('%');
            sb.append("\n  关：").append(offKb > 0 ? "底图 " + offKb + "KB" : "无底图")
                    .append(" · 可见度 ").append(offPct).append('%');
        } else {
            sb.append("\n底图：").append(onKb > 0 ? onKb + "KB" : "无底图")
                    .append(" · 可见度 ").append(onPct).append('%');
        }
        // 胶囊的进度块是另一张图、另一份可见度（样式面板里单独一页），这里单独列一行
        if (TileLongPressConfig.SHAPE_SLIDER.equals(shape)) {
            int progKb = TileLongPressConfig.shapeBgImage(
                    ctx, PrefKeys.SHAPE_SLIDER_PROGRESS).length() * 3 / 4 / 1024;
            sb.append("\n进度底图：").append(progKb > 0 ? progKb + "KB" : "无底图")
                    .append(" · 可见度 ").append(style != null
                    ? style.progressAlphaPct : TileLongPressConfig.DEFAULT_ALPHA_PCT)
                    .append('%');
            sb.append("\n进度压制：").append(SliderHook.suppressProbe());
        }
        // 通知卡片有自定义文字颜色（只落在标题 / 正文），单独列一行
        if (TileLongPressConfig.SHAPE_NOTIFICATION.equals(shape)) {
            sb.append("\n颜色 ").append(colorText(style != null
                            ? style.tintColor(TileLongPressConfig.TINT_LABEL)
                            : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM))
                    .append(" · ").append(NotificationTextHook.probeText());
        }
        sb.append("\n底图侧：hook 命中 ").append(TileBackgroundHook.hookHitCount())
                .append(" 次 · spec=").append(spec == null ? "null" : spec)
                .append(" → shape=").append(TileLongPressInterceptor.shapeOf(spec));
        sb.append("\n图片通道：接收器 ")
                .append(TileImageChannel.isRegistered() ? "已注册" : "未注册（收不到底图）")
                .append(" · 本形状磁贴 ")
                .append(TileLongPressInterceptor.viewCountOfShape(shape)).append(" 块");
        sb.append("\n磁贴圆角：").append(TileCornerHook.probe());
        sb.append("\n已触发的层：").append(TileBackgroundHook.hookPoints());
        sb.append("\n套用情况：")
                .append(applied == null ? "尚未套用到任何磁贴" : "已套用到 " + applied);
        sb.append("\n形状判定：").append(TileBackgroundHook.landingProbe());
        if (TileLongPressConfig.supportsIconColor(shape)) {
            sb.append("\n").append(TileLongPressConfig.tintLabel(TileLongPressConfig.TINT_ICON_BG))
                    .append("：").append(colorText(style != null
                    ? style.iconColor : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM))
                    .append(" · 覆盖 ").append(IconColorHook.applyCount()).append(" 次");
        }
        if (TileLongPressConfig.supportsTint(shape)) {
            int st = TileColorHook.lastState();
            sb.append("\n着色侧：hook 覆盖 ").append(TileColorHook.applyCount())
                    .append(" 次 · 其中文字 ").append(TileColorHook.labelCount()).append(" 次")
                    .append(" · 最近状态 st=").append(st)
                    .append(st == 2 ? "（激活）" : st == 1 ? "（关闭）" : "（未读到）");
            // 各入口命中次数 + 最近一次走到哪一步：哪条链路没生效一眼就能看出来
            sb.append("\n入口：").append(TileColorHook.probeText());
            sb.append("\n最近：").append(TileColorHook.lastProbe());
            // 只列面板上真有的那几项：非三阶段没有「图标激活」，看不出效果的不摆
            boolean threeStage = TileLongPressConfig.SHAPE_THREE.equals(shape);
            for (int kind = TileLongPressConfig.TINT_ICON_ON;
                    kind <= TileLongPressConfig.TINT_LABEL; kind++) {
                if (kind == TileLongPressConfig.TINT_ICON_ON && !threeStage) {
                    continue;
                }
                if (kind == TileLongPressConfig.TINT_LABEL && !supportsLabelColor(shape)) {
                    continue;
                }
                sb.append("\n").append(TileLongPressConfig.tintLabel(shape, kind)).append("：")
                        .append(colorText(style != null
                        ? style.tintColor(kind) : TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM));
            }
        }
        if (action != null) {
            sb.append('\n').append(action);
        }
        tv.setText(sb.toString());
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

    /**
     * 一行「标题 + 副标题 + 开关」，仿 ★ 弹窗里那几行开关的样式。
     *
     * <p>图标底色压深这种「形状级开关」放在对应形状的样式面板里，比塞进全局 ★ 弹窗
     * 更贴近使用路径。</p>
     */
    private static View switchRow(Context ctx, String label, String sub, boolean checked,
            CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, PADDING_H_DP), dp(ctx, 6), dp(ctx, PADDING_H_DP), dp(ctx, 6));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(ctx);
        labelView.setText(label);
        labelView.setTextColor(UiTheme.label(ctx));
        labelView.setTextSize(14f);
        labelView.setSingleLine(true);
        texts.addView(labelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (sub != null && sub.length() > 0) {
            TextView subView = new TextView(ctx);
            subView.setText(sub);
            subView.setTextColor(UiTheme.hint(ctx));
            subView.setTextSize(11f);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        row.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(ctx);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw, new LinearLayout.LayoutParams(
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
        lp.topMargin = dp(ctx, 10);
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
