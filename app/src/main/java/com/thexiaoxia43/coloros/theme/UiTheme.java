package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 亮 / 暗两套配色。
 *
 * <p>模块 App 的界面和 SystemUI 里的三个弹窗（★ 面板 / 样式面板 / 背景面板）
 * 都从这里取色，跟着系统深色开关走，不再各自硬编码一套暗色。判断依据是当前
 * Context 的 {@link Configuration#uiMode}：在模块 App 里是系统的深浅，
 * 在 SystemUI 里就是控制中心的深浅。</p>
 *
 * <p>每个方法都必须传 Context，不能在类初始化时把颜色算死：用户中途切换深色模式后，
 * 重新打开的界面 / 弹窗得用新配色。</p>
 */
public final class UiTheme {

    /** 主色（磁贴激活蓝），两种模式共用。 */
    public static final int ACCENT = 0xFF1875F5;
    /** 状态色，两种模式共用。 */
    public static final int OK = 0xFF34C759;
    public static final int WARN = 0xFFFF9F0A;
    public static final int DANGER = 0xFFFF6B6B;

    private UiTheme() {
    }

    /** 当前是不是深色模式。取不到时按深色处理（也就是改动前的观感）。 */
    public static boolean isNight(Context ctx) {
        if (ctx == null) {
            return true;
        }
        try {
            Configuration cfg = ctx.getResources().getConfiguration();
            return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    private static int pick(Context ctx, int night, int day) {
        return isNight(ctx) ? night : day;
    }

    // ------------------------------------------------------------ 模块 App 界面

    /** 页面底色。 */
    public static int bg(Context ctx) {
        return pick(ctx, 0xFF000000, 0xFFF2F2F7);
    }

    /** 卡片底色。 */
    public static int card(Context ctx) {
        return pick(ctx, 0xFF1C1C1E, 0xFFFFFFFF);
    }

    /** 卡片里的下沉块：图标圆底、输入框。 */
    public static int sunken(Context ctx) {
        return pick(ctx, 0xFF2C2C2E, 0xFFE9E9EB);
    }

    /** 底部导航选中项的胶囊底。 */
    public static int navSelectedBg(Context ctx) {
        return pick(ctx, 0xFF2F3A46, 0xFFDCE7F5);
    }

    /** 底部导航未选中项。 */
    public static int navIdle(Context ctx) {
        return pick(ctx, 0xFF8E8E93, 0xFF8E8E93);
    }

    /** 导航选中项与强调文字。 */
    public static int accentText(Context ctx) {
        return pick(ctx, 0xFF5AA9FF, 0xFF1875F5);
    }

    // ------------------------------------------------------------ SystemUI 弹窗

    /**
     * 弹窗卡片底色。
     *
     * <p>比 App 里的卡片浅一档：弹窗压在半透明的模糊层上，颜色太深会和背后的
     * 控制中心糊成一片，看不出边界。</p>
     */
    public static int popupCard(Context ctx) {
        return pick(ctx, 0xFF272828, 0xFFF7F7F9);
    }

    /** 弹窗里按钮的底色。 */
    public static int popupButtonBg(Context ctx) {
        return pick(ctx, 0xFF343536, 0xFFE8E8EA);
    }

    /** 弹窗里按钮的文字色（要压在 {@link #popupButtonBg} 上）。 */
    public static int popupButtonText(Context ctx) {
        return pick(ctx, 0xFFFFFFFF, 0xFF000000);
    }

    // ------------------------------------------------------------ 两处共用

    /** 标题 / 主要文字。 */
    public static int title(Context ctx) {
        return pick(ctx, 0xFFFFFFFF, 0xFF000000);
    }

    /** 行标签。 */
    public static int label(Context ctx) {
        return pick(ctx, 0xFFE5E5E5, 0xFF1C1C1E);
    }

    /** 行右侧的值。 */
    public static int value(Context ctx) {
        return pick(ctx, 0x99FFFFFF, 0x99000000);
    }

    /** 副标题 / 说明文字。 */
    public static int hint(Context ctx) {
        return pick(ctx, 0x8CFFFFFF, 0x8C000000);
    }

    /** 分隔线。 */
    public static int divider(Context ctx) {
        return pick(ctx, 0x1AFFFFFF, 0x1A000000);
    }

    /** 开关打开时的轨道色。 */
    public static int trackOn(Context ctx) {
        return ACCENT;
    }

    /** 开关关闭时的轨道色。 */
    public static int trackOff(Context ctx) {
        return pick(ctx, 0xFF4A4A4C, 0xFFC7C7CC);
    }

    /** 开关滑块色。 */
    public static int thumb(Context ctx) {
        return 0xFFFFFFFF;
    }
}
