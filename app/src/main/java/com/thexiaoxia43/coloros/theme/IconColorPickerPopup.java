package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * 取色面板：图标底色 / 图标着色（激活）/ 图标着色 / 文字颜色四项共用。
 *
 * <p>四项的交互完全一样（都是「挑个颜色 + 一个不透明度滑杆」），差别只有标题文案和
 * 落到哪个字段，所以用一个 {@code kind} 参数区分（见
 * {@link TileLongPressConfig#TINT_ICON_BG} 那一族）。</p>
 *
 * <p>左侧预设色块（3 列）、中间饱和度 / 明度色板、右侧色相条、下面十六进制输入框，
 * 底部「确认 / 取消」。标题栏右侧的小方块始终显示当前颜色，「跟随系统」也在这一行
 * ——它是一个「清掉覆盖」的动作，跟挑颜色不是一回事，单独摆出来更好找。</p>
 *
 * <p>色相条与色板都是自绘的（系统没有现成控件）：色板 = 纯色相底 + 横向白→透明 +
 * 纵向透明→黑三层叠加，选择点用双色圆环描出来，深浅底色上都看得见。</p>
 *
 * <p>确认后写 prefs → 重刷该形状的磁贴 → 重新弹回样式面板；取消只是弹回样式面板，
 * 不动配置。</p>
 *
 * <p>十六进制输入框是<b>边打边生效</b>的：只要敲进去的是十六进制字符（哪怕只有 1~2 位），
 * 色板 / 色相条 / 预览块 / 预设块就立刻跟着变；敲满 6 位合法色号（{@code #RRGGBB} /
 * {@code RRGGBB}）后停手约 300ms 自动写 prefs 并重刷该形状，不用再点「确认」；
 * 键盘上的「完成」则等同于点「确认」（落地并弹回上一层面板）。</p>
 */
public final class IconColorPickerPopup {

    private static final String TAG = "SystemUIHook/IconColor";

    private static final int WIDTH_DP = 320;
    private static final int CORNER_DP = 16;
    private static final int PADDING_DP = 16;

    /** 预设色块：3 列 × 6 行。 */
    private static final int SWATCH_COLUMNS = 3;
    private static final int SWATCH_DP = 26;
    private static final int SWATCH_GAP_DP = 3;

    /** 饱和度 / 明度色板的边长，以及色相条的宽度。 */
    private static final int SV_DP = 160;
    private static final int HUE_DP = 20;
    private static final int SV_HUE_GAP_DP = 8;

    /** 十六进制输入框与按钮的高度。 */
    private static final int HEX_HEIGHT_DP = 38;
    private static final int BUTTON_HEIGHT_DP = 40;

    /** 输入框敲满一个完整色号后停这么一下再落地，免得连着改字时中间那几个瞬时值来回刷。 */
    private static final long HEX_APPLY_DELAY_MS = 300L;

    /** 输入框轮询的间隔（TextWatcher 没派发时的兜底，见 onHexEdited）。 */
    private static final long HEX_POLL_MS = 200L;

    /** 诊断用：已经记过日志的输入框文本（确认 TextWatcher 到底有没有被派发）。 */
    private static final Set<String> sHexLogged = new HashSet<String>();

    /** 色相条的颜色序列（红→黄→绿→青→蓝→品→红）。 */
    private static final int[] HUE_COLORS = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
            0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
    };

    /** 预设色块（跟参考的取色面板一致的排布：白灰黑 / 红橙黄 / 绿系 / 蓝系 / 青紫品）。 */
    private static final int[] PRESETS = {
            0xFFFFFFFF, 0xFFD9D9D9, 0xFF000000,
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00,
            0xFF00FF00, 0xFF00A000, 0xFF006400,
            0xFF9DD5F0, 0xFF0000FF, 0xFF00008B,
            0xFF00FFFF, 0xFF800080, 0xFFFF00FF,
            0xFFE01E3E, 0xFFFF9500, 0xFF34C759,
    };

    /** 当前已弹出的实例。 */
    private static PopupWindow sCurrent;

    /** 弹出取色面板时用的锚点：确认 / 取消后要靠它把样式面板弹回去。 */
    private static volatile java.lang.ref.WeakReference<View> sAnchor =
            new java.lang.ref.WeakReference<View>(null);

    /** 这一轮弹的是控制中心「背景颜色」而不是磁贴那四项，落地与弹回都不同，见 applyColor。 */
    private static boolean sQsBackground;

    /**
     * 这一轮弹的是<b>通知头部</b>（时钟 / 日期 / 图标）取色：值是
     * {@link NotifHeaderHook#KIND_FONT} / {@link NotifHeaderHook#KIND_ICON}；
     * {@code -1} = 不是（磁贴 / 控制中心背景那两路）。
     *
     * <p>三个入口各自先把这位置好再进 {@code showInternal}，落地 / 标题 / 取消弹回
     * 都按它分流——比给 {@code showInternal} 加参数动的地方少。</p>
     */
    private static int sNotifHeaderKind = -1;

    private IconColorPickerPopup() {
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
     * 弹出取色面板。
     *
     * @param anchor  触发长按的磁贴视图（取窗口 token 用）
     * @param shape   形状标识
     * @param active  当前在编辑「开」还是「关」
     * @param starter QSTileImpl 的 ActivityStarter，可为 null
     * @param kind    要改的是哪一项，见 {@link TileLongPressConfig#TINT_ICON_BG} 那一族
     * @param current 当前颜色；{@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM} 表示还没设过
     * @return 面板是否弹出来了
     */
    public static boolean show(final View anchor, final String shape, final boolean active,
            final Object starter, final int kind, final int current) {
        sNotifHeaderKind = -1;
        return showInternal(anchor, shape, active, starter, kind, current, false);
    }

    /**
     * 弹出<b>通知头部</b>（字体 / 图标）的取色面板。
     *
     * <p>色板 / 色相条 / 预设块与磁贴那四项完全一样，区别只有落地：写的是
     * {@link NotifHeaderHook#setColor}（「跟随系统」= 清掉颜色），确认 / 取消后
     * 弹回 {@link NotifHeaderStylePopup}。</p>
     *
     * @param kind {@link NotifHeaderHook#KIND_FONT} 或 {@link NotifHeaderHook#KIND_ICON}
     */
    public static boolean showForNotifHeader(final View anchor, final int kind,
            final int current) {
        sNotifHeaderKind = kind;
        return showInternal(anchor, null, false, null,
                TileLongPressConfig.TINT_LABEL, current, false);
    }

    /**
     * 弹出控制中心<b>背景颜色</b>的取色面板。
     *
     * <p>色板 / 色相条 / 预设块与磁贴那四项完全一样，区别只有落地：写的是
     * {@link TileLongPressConfig#setQsBackgroundColor}（「跟随系统」= 清掉纯色），
     * 确认 / 取消后弹回 {@link QsBackgroundPopup} 而不是磁贴样式面板。</p>
     *
     * @param current 当前纯色；{@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM} 表示还没设过
     */
    public static boolean showForQsBackground(final View anchor, final int current) {
        sNotifHeaderKind = -1;
        return showInternal(anchor, PrefKeys.SHAPE_QS_BACKGROUND, true, null,
                TileLongPressConfig.TINT_ICON_OFF, current, true);
    }

    private static boolean showInternal(final View anchor, final String shape, final boolean active,
            final Object starter, final int kind, final int current, final boolean qsBackground) {
        sQsBackground = qsBackground;
        if (anchor == null) {
            return false;
        }
        dismiss();
        final Context ctx = anchor.getContext();
        sAnchor = new java.lang.ref.WeakReference<View>(anchor);

        // hsv 是这一轮编辑的**唯一真源**：色板、色相条、输入框、预览块都从它推出来
        final float[] hsv = new float[3];
        Color.colorToHSV(normalize(current), hsv);
        // 纯黑时色相 / 饱和度都没有意义，把明度抬一点，选择点才不会贴死在右下角
        if (hsv[1] == 0f && hsv[2] == 0f) {
            hsv[2] = 1f;
        }

        // 回写文本会再次触发输入框监听，用它挡掉自激循环
        final boolean[] updating = new boolean[1];

        // 输入框文本「上次看过」的值：个别 ROM 的弹窗里 EditText 拿不到文本变更回调，
        // 下面的轮询兜底靠它认出用户确实改了字（push 回写时同步刷新它，免得被误判成用户输入）
        final String[] lastSeen = new String[] { null };

        final SvView sv = new SvView(ctx);
        final HueView hue = new HueView(ctx);
        final EditText hex = new EditText(ctx);
        final View preview = new View(ctx);

        final Runnable push = new Runnable() {
            @Override
            public void run() {
                updating[0] = true;
                sv.setHsv(hsv[0], hsv[1], hsv[2]);
                hue.setHue(hsv[0]);
                int color = Color.HSVToColor(hsv);
                hex.setText(String.format("#%06X", color & 0x00FFFFFF));
                hex.setSelection(hex.getText().length());
                // 这串字是我们自己写的，同步给 lastSeen，轮询别把它当成用户改字
                lastSeen[0] = hex.getText() == null ? null : hex.getText().toString();
                preview.setBackground(box(ctx, color, 2, UiTheme.divider(ctx), 6));
                updating[0] = false;
            }
        };

        // 输入框里敲出一个完整色号（#RRGGBB / RRGGBB）就即时落地：写配置 → 刷该形状，
        // 面板留着不动，好让用户一边看效果一边接着改。半截色号（位数不够 / 有非法字符）
        // 一律不碰配置，也不去改用户正在打的那串字。
        final Runnable applyHex = new Runnable() {
            @Override
            public void run() {
                int parsed = parseHex(hex.getText() == null ? null : hex.getText().toString());
                if (parsed < 0) {
                    return;
                }
                applyColorLive(ctx, shape, kind, 0xFF000000 | parsed);
            }
        };

        sv.setOnChange(new SvView.OnChange() {
            @Override
            public void onChange(float sat, float value) {
                hsv[1] = sat;
                hsv[2] = value;
                push.run();
            }
        });
        hue.setOnChange(new HueView.OnChange() {
            @Override
            public void onChange(float h) {
                hsv[0] = h;
                push.run();
            }
        });

        // ------------------------------------------------------------ 标题行
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(ctx, PADDING_DP), dp(ctx, 14), dp(ctx, PADDING_DP), dp(ctx, 10));

        TextView title = new TextView(ctx);
        title.setText(sNotifHeaderKind == NotifHeaderHook.KIND_FONT ? "字体颜色"
                : sNotifHeaderKind == NotifHeaderHook.KIND_ICON ? "图标颜色"
                : sQsBackground ? "背景颜色" : TileLongPressConfig.tintLabel(shape, kind));
        title.setTextColor(UiTheme.title(ctx));
        title.setTextSize(15f);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView follow = new TextView(ctx);
        follow.setText("跟随系统");
        follow.setTextColor(UiTheme.ACCENT);
        follow.setTextSize(13f);
        follow.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        follow.setClickable(true);
        follow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyColor(ctx, shape, active, starter, kind,
                        TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM);
            }
        });
        titleRow.addView(follow);

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                dp(ctx, 26), dp(ctx, 26));
        titleRow.addView(preview, previewParams);

        // ------------------------------------------------------------ 左侧预设
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout gridRow = null;
        for (int i = 0; i < PRESETS.length; i++) {
            if (i % SWATCH_COLUMNS == 0) {
                gridRow = new LinearLayout(ctx);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(gridRow);
            }
            final int preset = PRESETS[i];
            gridRow.addView(presetCell(ctx, preset, preset == normalize(current),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Color.colorToHSV(preset, hsv);
                            if (hsv[1] == 0f && hsv[2] == 0f) {
                                hsv[2] = 1f;
                            }
                            push.run();
                            refreshPresets(ctx, grid, preset);
                        }
                    }));
        }

        // ------------------------------------------------------ 色板 / 色相条
        LinearLayout svRow = new LinearLayout(ctx);
        svRow.setOrientation(LinearLayout.HORIZONTAL);
        svRow.addView(sv, new LinearLayout.LayoutParams(dp(ctx, SV_DP), dp(ctx, SV_DP)));
        svRow.addView(gapW(ctx, SV_HUE_GAP_DP));
        svRow.addView(hue, new LinearLayout.LayoutParams(dp(ctx, HUE_DP), dp(ctx, SV_DP)));

        final int rightWidth = SV_DP + SV_HUE_GAP_DP + HUE_DP;

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(svRow);

        // ------------------------------------------------------------ 十六进制
        hex.setSingleLine(true);
        hex.setTextSize(14f);
        hex.setTextColor(UiTheme.title(ctx));
        hex.setGravity(Gravity.CENTER_VERTICAL);
        hex.setInputType(InputType.TYPE_CLASS_TEXT);
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hex.setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0);
        hex.setBackground(box(ctx, UiTheme.popupButtonBg(ctx), 1, UiTheme.divider(ctx), 8));
        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updating[0]) {
                    return;
                }
                // 手一碰输入框就把上一次排队的落地撤了：等这轮停手再统一应用
                hex.removeCallbacks(applyHex);

                String raw = s == null ? null : s.toString();
                lastSeen[0] = raw;
                logHexWatch(raw);
                // 宽松解析：半截色号（比如刚敲到 "FF00"）也先让面板跟着变，用户能边打边看。
                // 这里只刷面板、**不重写输入框**——push 会把文字改成规范 #RRGGBB 并把光标顶到
                // 末尾，那会跟正在打字的用户打架。
                onHexEdited(ctx, shape, kind, raw, hsv, sv, hue, preview, grid, hex, applyHex);
            }
        });
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(
                dp(ctx, rightWidth), dp(ctx, HEX_HEIGHT_DP));
        hexParams.topMargin = dp(ctx, 10);
        right.addView(hex, hexParams);

        // 键盘右下角的「完成」等同于点「确认」：落地并退回样式面板
        hex.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) {
                    return false;
                }
                int parsed = parseHex(hex.getText() == null ? null : hex.getText().toString());
                if (parsed < 0) {
                    return true;
                }
                hex.removeCallbacks(applyHex);
                applyColor(ctx, shape, active, starter, kind, 0xFF000000 | parsed);
                return true;
            }
        });

        // -------------------------------------------------------------- 按钮
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(pickerButton(ctx, "确认", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyColor(ctx, shape, active, starter, kind, Color.HSVToColor(hsv));
            }
        }));
        buttons.addView(pickerButton(ctx, "取消", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 取消：不动配置，只把上一层面板弹回去
                dismiss();
                if (sNotifHeaderKind >= 0) {
                    NotifHeaderStylePopup.show(anchor, ctx);
                } else if (sQsBackground) {
                    QsBackgroundPopup.show(anchor, ctx);
                } else {
                    TileStylePopup.show(anchor, shape, active, starter);
                }
            }
        }));
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                dp(ctx, rightWidth), dp(ctx, BUTTON_HEIGHT_DP));
        buttonsParams.topMargin = dp(ctx, 10);
        right.addView(buttons, buttonsParams);

        // -------------------------------------------------------------- 组装
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(ctx, PADDING_DP), 0, dp(ctx, PADDING_DP), dp(ctx, PADDING_DP));
        int gridWidth = SWATCH_COLUMNS * SWATCH_DP + SWATCH_COLUMNS * SWATCH_GAP_DP;
        body.addView(grid, new LinearLayout.LayoutParams(dp(ctx, gridWidth),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(gapW(ctx, 12));
        body.addView(right, new LinearLayout.LayoutParams(dp(ctx, rightWidth),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(titleRow);
        card.addView(body);
        card.setBackground(rounded(ctx, UiTheme.popupCard(ctx), CORNER_DP));

        push.run();

        PopupWindow popup = new PopupWindow(card,
                dp(ctx, WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        try {
            popup.setElevation(dp(ctx, 8));
        } catch (Throwable ignored) {
        }

        // TextWatcher 的兜底：个别 ROM 上弹窗里的 EditText 收不到文本变更回调（文本变了、
        // 回调不来），定时读一眼输入框内容，用户改了字照样实时变色。push 自己回写文本时会
        // 同步 lastSeen，所以这里只会认「用户手打的字」，不会把我们的回写当成输入。
        final Runnable[] pollRef = new Runnable[1];
        final PopupWindow self = popup;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (sCurrent != self) {
                    return; // 面板已经关了，停表
                }
                String now = hex.getText() == null ? null : hex.getText().toString();
                if (now != null && !now.equals(lastSeen[0])) {
                    lastSeen[0] = now;
                    hex.removeCallbacks(applyHex);
                    XposedBridge.log(TAG + ": hex polled, text=" + now);
                    onHexEdited(ctx, shape, kind, now, hsv, sv, hue, preview, grid, hex, applyHex);
                }
                hex.postDelayed(pollRef[0], HEX_POLL_MS);
            }
        };
        pollRef[0] = poll;

        try {
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                    TileStylePopup.topMarginPx(ctx));
            sCurrent = popup;
            hex.postDelayed(poll, HEX_POLL_MS);
            XposedBridge.log(TAG + ": picker shown, shape=" + shape
                    + ", current=" + String.format("#%06X", normalize(current) & 0x00FFFFFF));
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": picker show failed - " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 落地

    /** 写配置 → 重刷色块 → 弹回样式面板。{@code color} 为「跟随系统」时清掉覆盖。 */
    private static void applyColor(final Context ctx, final String shape, final boolean active,
            final Object starter, final int kind, final int color) {
        if (sNotifHeaderKind >= 0) {
            applyNotifHeader(ctx, color);
            return;
        }
        if (sQsBackground) {
            applyQsBackground(ctx, color);
            return;
        }
        TileLongPressConfig.setTintColor(ctx, shape, kind, color);
        int count = TileLongPressInterceptor.refreshShape(shape);
        XposedBridge.log(TAG + ": " + TileLongPressConfig.tintLabel(kind) + " "
                + (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "跟随系统" : String.format("#%06X", color & 0x00FFFFFF))
                + " applied, shape=" + shape + ", refreshed=" + count);
        View anchor = sAnchor.get();
        dismiss();
        if (anchor != null) {
            TileStylePopup.show(anchor, shape, active, starter);
        }
    }

    /**
     * 输入框敲出色号时的<b>即时落地</b>：写配置 → 刷该形状，<b>不关面板</b>。
     *
     * <p>与 {@link #applyColor} 的差别只在于不 dismiss、也不弹回上一层面板——用户还在
     * 输入框里接着打字，面板一关就没法继续调了。既然色号是边打边生效的，「取消」此时
     * 也退不回改前的颜色，只能靠「跟随系统」或再改一次色号往回找。</p>
     */
    private static void applyColorLive(final Context ctx, final String shape, final int kind,
            final int color) {
        if (sNotifHeaderKind >= 0) {
            NotifHeaderHook.setColor(ctx, sNotifHeaderKind, color);
            NotifHeaderHook.refresh();
            XposedBridge.log(TAG + ": 通知头部"
                    + (sNotifHeaderKind == NotifHeaderHook.KIND_FONT ? "字体" : "图标")
                    + "颜色 " + String.format("#%08X", color) + " applied live (hex)");
            return;
        }
        if (sQsBackground) {
            TileLongPressConfig.setQsBackgroundColor(ctx, color);
            bumpBgVisibilityOnColorChange(ctx, color);
            QsBackgroundHook.refresh();
            XposedBridge.log(TAG + ": 背景颜色 " + String.format("#%08X", color)
                    + " applied live (hex)");
            return;
        }
        TileLongPressConfig.setTintColor(ctx, shape, kind, color);
        int count = TileLongPressInterceptor.refreshShape(shape);
        XposedBridge.log(TAG + ": " + TileLongPressConfig.tintLabel(kind) + " "
                + String.format("#%06X", color & 0x00FFFFFF)
                + " applied live (hex), shape=" + shape + ", refreshed=" + count);
    }

    /** 通知头部颜色的落地：写 prefs → 重刷头部 → 弹回通知样式面板。 */
    private static void applyNotifHeader(final Context ctx, final int color) {
        NotifHeaderHook.setColor(ctx, sNotifHeaderKind, color);
        int count = NotifHeaderHook.refresh();
        XposedBridge.log(TAG + ": 通知头部"
                + (sNotifHeaderKind == NotifHeaderHook.KIND_FONT ? "字体" : "图标")
                + "颜色 "
                + (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "跟随系统" : String.format("#%08X", color))
                + " applied, refreshed=" + count);
        View anchor = sAnchor.get();
        dismiss();
        if (anchor != null) {
            NotifHeaderStylePopup.show(anchor, ctx);
        }
    }

    /** 控制中心背景纯色的落地：写 prefs → 换色顺手顶可见度 → 刷新背景层 → 弹回背景面板。 */
    private static void applyQsBackground(final Context ctx, final int color) {
        TileLongPressConfig.setQsBackgroundColor(ctx, color);
        bumpBgVisibilityOnColorChange(ctx, color);
        int count = QsBackgroundHook.refresh();
        XposedBridge.log(TAG + ": 背景颜色 "
                + (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "跟随系统" : String.format("#%08X", color))
                + " applied, refreshed=" + count);
        View anchor = sAnchor.get();
        dismiss();
        if (anchor != null) {
            QsBackgroundPopup.show(anchor, ctx);
        }
    }

    /**
     * 换色时顺手把控制中心背景可见度顶到最高档（200%）。
     *
     * <p>系统背景层自己就是半透明的，刚挑的纯色按默认 100% 可见度压不满、看着发灰；
     * 换完色自动顶到 200%（叠两层）才看得出本色。这是「换色」的附带动作，不是全局默认值
     * ——没换过色的用户仍是 100%。「跟随系统」= 清掉纯色，这时不去动用户自己调的可见度。</p>
     */
    private static void bumpBgVisibilityOnColorChange(final Context ctx, final int color) {
        if (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return;
        }
        TileLongPressConfig.setBgTransparencyPct(ctx,
                TileLongPressConfig.MIN_BG_TRANSPARENCY_PCT);
    }

    // ---------------------------------------------------------------- 输入框

    /**
     * 输入框内容变化后的统一处理：刷面板 + 满 6 位则排队落地。
     *
     * <p>{@link TextWatcher} 与下面的轮询兜底都走这里，保证两条路的行为完全一致。</p>
     */
    private static void onHexEdited(final Context ctx, final String shape, final int kind,
            final String raw, final float[] hsv, final SvView sv, final HueView hue,
            final View preview, final LinearLayout grid, final EditText hex,
            final Runnable applyHex) {
        // 宽松解析：半截色号（比如刚敲到 "FF00"）也先让面板跟着变，用户能边打边看。
        // 这里只刷面板、**不重写输入框**——push 会把文字改成规范 #RRGGBB 并把光标顶到
        // 末尾，那会跟正在打字的用户打架。
        int loose = parseHexLoose(raw);
        if (loose >= 0) {
            // parse* 只回裸 RGB（0..0xFFFFFF，恒 ≥ 0），这里补不透明再拿去刷面板：
            // 若在 parse 里就 |0xFF000000，返回值必为负 int，这里的 ≥0 判定会恒假
            int color = 0xFF000000 | loose;
            Color.colorToHSV(color, hsv);
            sv.setHsv(hsv[0], hsv[1], hsv[2]);
            hue.setHue(hsv[0]);
            preview.setBackground(box(ctx, color, 2, UiTheme.divider(ctx), 6));
            refreshPresets(ctx, grid, color);
        }

        // 刚好 6 位的完整色号才落地 prefs（停手 300ms 再写，免得连着改字时中间值来回刷）
        if (parseHex(raw) >= 0) {
            hex.postDelayed(applyHex, HEX_APPLY_DELAY_MS);
        }
    }

    /** 诊断用：记一条「TextWatcher 被派发了」的日志，每种文本只记一次，最多 40 条。 */
    private static void logHexWatch(final String raw) {
        synchronized (sHexLogged) {
            if (sHexLogged.size() >= 40 || !sHexLogged.add(raw == null ? "" : raw)) {
                return;
            }
        }
        XposedBridge.log(TAG + ": watcher fired, text=" + raw);
    }

    // ---------------------------------------------------------------- 视图工具

    /** 预设色块：选中时描主色边框并打勾，勾的颜色按底色明暗自动取黑或白。 */
    private static View presetCell(final Context ctx, final int color, final boolean selected,
            final View.OnClickListener click) {
        TextView cell = new TextView(ctx);
        cell.setTag(Integer.valueOf(color));
        cell.setGravity(Gravity.CENTER);
        cell.setTextSize(13f);
        cell.setBackground(cellBackground(ctx, color, selected));
        if (selected) {
            cell.setText("✓");
            cell.setTextColor(luminance(color) > 0.6 ? 0xFF000000 : 0xFFFFFFFF);
        }
        cell.setClickable(true);
        cell.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(ctx, SWATCH_DP), dp(ctx, SWATCH_DP));
        lp.rightMargin = dp(ctx, SWATCH_GAP_DP);
        lp.bottomMargin = dp(ctx, SWATCH_GAP_DP);
        cell.setLayoutParams(lp);
        return cell;
    }

    /** 重刷预设块的选中态（点色块 / 输颜色码之后调用）。 */
    private static void refreshPresets(final Context ctx, final LinearLayout grid,
            final int selected) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View row = grid.getChildAt(i);
            if (!(row instanceof LinearLayout)) {
                continue;
            }
            LinearLayout line = (LinearLayout) row;
            for (int j = 0; j < line.getChildCount(); j++) {
                View cell = line.getChildAt(j);
                Object tag = cell.getTag();
                if (!(tag instanceof Integer) || !(cell instanceof TextView)) {
                    continue;
                }
                int color = ((Integer) tag).intValue();
                boolean on = color == selected;
                TextView text = (TextView) cell;
                text.setBackground(cellBackground(ctx, color, on));
                text.setText(on ? "✓" : "");
                if (on) {
                    text.setTextColor(luminance(color) > 0.6 ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
        }
    }

    private static GradientDrawable cellBackground(final Context ctx, final int color,
            final boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, 5));
        d.setColor(0xFF000000 | (color & 0x00FFFFFF));
        d.setStroke(Math.max(1, dp(ctx, selected ? 3 : 1)),
                selected ? UiTheme.ACCENT : UiTheme.divider(ctx));
        return d;
    }

    private static View pickerButton(final Context ctx, final String label, final boolean primary,
            final View.OnClickListener click) {
        TextView btn = new TextView(ctx);
        btn.setText(label);
        btn.setTextColor(primary ? 0xFFFFFFFF : UiTheme.popupButtonText(ctx));
        btn.setTextSize(14f);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(rounded(ctx, primary ? UiTheme.ACCENT : UiTheme.popupButtonBg(ctx), 10));
        btn.setClickable(true);
        btn.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 4);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static View gapW(final Context ctx, final int widthDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, widthDp), 1));
        return v;
    }

    private static GradientDrawable rounded(final Context ctx, final int color, final int cornerDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(ctx, cornerDp));
        return d;
    }

    /** 带边框的圆角矩形（预览块 / 输入框共用）。 */
    private static GradientDrawable box(final Context ctx, final int color, final int strokeDp,
            final int strokeColor, final int cornerDp) {
        GradientDrawable d = rounded(ctx, color, cornerDp);
        d.setStroke(Math.max(1, dp(ctx, strokeDp)), strokeColor);
        return d;
    }

    /** 配置值归一成不透明色；「跟随系统」时给个白色当起点。 */
    private static int normalize(final int color) {
        if (color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return 0xFFFFFFFF;
        }
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    /**
     * 宽松解析：只认十六进制字符，1~6 位按右侧补 0 凑齐 6 位（方便<b>边打边看</b>），
     * 7~8 位取后 6 位（把开头的 alpha 丢掉，本面板只编辑 RGB）。空串或混进非法字符
     * 一律返回 -1（此时不刷面板，也不动配置）。
     *
     * <p>跟 {@link #parseHex} 的分工：这个只管「让面板先变个色给你看」，真正写 prefs
     * 只认 {@code parseHex} 那种刚好 6 位的完整色号。</p>
     *
     * <p>返回值是<b>裸 RGB</b>（0..0xFFFFFF，恒 ≥ 0），不带 alpha——调用处靠 {@code ≥ 0}
     * 判断解析成功与否，所以这里绝不能补 {@code 0xFF000000}，否则成功值也成了负数。</p>
     */
    private static int parseHexLoose(final String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 0 || s.length() > 8) {
            return -1;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return -1;
            }
        }
        if (s.length() > 6) {
            s = s.substring(s.length() - 6);
        }
        StringBuilder buf = new StringBuilder(s);
        while (buf.length() < 6) {
            buf.append('0');
        }
        try {
            // 只回裸 RGB（0..0xFFFFFF）：**不要**在这里 |0xFF000000，否则返回值必为负 int，
            // 调用处的 `>= 0` 判定会恒假（这就是「输入色号面板不实时变色」的病根）
            return (int) Long.parseLong(buf.toString(), 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 解析 {@code #RRGGBB} / {@code RRGGBB}；不合法返回 -1。
     *
     * <p>与 {@link #parseHexLoose} 一样只回<b>裸 RGB</b>（0..0xFFFFFF，恒 ≥ 0），
     * 调用处自己 {@code 0xFF000000 |} 补不透明。</p>
     */
    private static int parseHex(final String text) {
        if (text == null) {
            return -1;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return -1;
        }
        try {
            // 同 parseHexLoose：只回裸 RGB，别在这里补 alpha
            return (int) Long.parseLong(s, 16);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 相对亮度，用来决定勾 / 文字取黑还是白。 */
    private static double luminance(final int color) {
        return 0.299 * (Color.red(color) / 255.0)
                + 0.587 * (Color.green(color) / 255.0)
                + 0.114 * (Color.blue(color) / 255.0);
    }

    private static float clamp(final float v, final float min, final float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int dp(final Context ctx, final float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------ 自绘控件

    /**
     * 饱和度 / 明度色板。
     *
     * <p>三层叠加：纯色相底 → 横向白到透明（饱和度）→ 纵向透明到黑（明度）。
     * 选择点用黑圈套白圈，深浅底色上都看得清。</p>
     */
    private static final class SvView extends View {

        interface OnChange {
            void onChange(float sat, float value);
        }

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hue;
        private float sat = 1f;
        private float val = 1f;
        private OnChange onChange;

        SvView(Context ctx) {
            super(ctx);
        }

        void setOnChange(OnChange listener) {
            onChange = listener;
        }

        void setHsv(float h, float s, float v) {
            hue = h;
            sat = s;
            val = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            fill.setShader(null);
            fill.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(new LinearGradient(0, 0, w, 0,
                    0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(new LinearGradient(0, 0, 0, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            float cx = sat * w;
            float cy = (1f - val) * h;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(getContext(), 2));
            ring.setColor(0xFF000000);
            canvas.drawCircle(cx, cy, dp(getContext(), 7), ring);
            ring.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, dp(getContext(), 5.5f), ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
                return super.onTouchEvent(event);
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return true;
            }
            ViewParentCompat.disallow(getParent());
            sat = clamp(event.getX() / w, 0f, 1f);
            val = 1f - clamp(event.getY() / h, 0f, 1f);
            invalidate();
            if (onChange != null) {
                onChange.onChange(sat, val);
            }
            return true;
        }
    }

    /** 色相条：竖向彩虹渐变，一条白线标出当前位置。 */
    private static final class HueView extends View {

        interface OnChange {
            void onChange(float hue);
        }

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hue;
        private OnChange onChange;

        HueView(Context ctx) {
            super(ctx);
        }

        void setOnChange(OnChange listener) {
            onChange = listener;
        }

        void setHue(float h) {
            hue = h;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            fill.setShader(new LinearGradient(0, 0, 0, h, HUE_COLORS, null,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            float y = clamp(hue / 360f, 0f, 1f) * h;
            marker.setStyle(Paint.Style.STROKE);
            marker.setStrokeWidth(dp(getContext(), 2));
            marker.setColor(0xFF000000);
            canvas.drawRect(0, y - dp(getContext(), 2), w, y + dp(getContext(), 2), marker);
            marker.setColor(0xFFFFFFFF);
            canvas.drawRect(0, y - dp(getContext(), 0.5f), w, y + dp(getContext(), 0.5f), marker);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
                return super.onTouchEvent(event);
            }
            int h = getHeight();
            if (h <= 0) {
                return true;
            }
            ViewParentCompat.disallow(getParent());
            hue = clamp(event.getY() / h, 0f, 1f) * 360f;
            invalidate();
            if (onChange != null) {
                onChange.onChange(hue);
            }
            return true;
        }
    }

    /** 拖动色板时别让外层容器把事件抢走（放进滚动容器时才需要，这里只是兜底）。 */
    private static final class ViewParentCompat {
        static void disallow(android.view.ViewParent parent) {
            try {
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
