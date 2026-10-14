package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
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
 * 通知卡片标题 / 正文的<b>文字颜色</b>。
 *
 * <p>只改颜色，不动字体：颜色复用样式面板里那一项「文字颜色」，
 * 存在 {@link TileLongPressConfig} 通知形状（{@code SHAPE_NOTIFICATION}）的
 * {@code TINT_LABEL} 那一格。</p>
 *
 * <p><b>为什么钩 {@code TextView#setTextColor}</b>：实时更新的通知（进度、计时这类）
 * 每刷新一次，RemoteViews 都会把文本和颜色重新下发一遍，而且颜色写在文本<b>之后</b>——
 * 只贴在 {@code setText} 上的颜色会被紧接着的 {@code setTextColor} 当场盖掉，
 * 看着就是「改完立刻被改回来」。钩在 {@code setTextColor} 后面补写才压得住。</p>
 *
 * <p><b>靠 id 判该上色哪些文字</b>：标题 / 正文 / 展开后的大段正文
 * （{@code title} / {@code text} / {@code big_text}），外加 ColorOS 通知专有的一批
 * systemui id（分组子行的 {@code notification_title} / {@code notification_text}、
 * 头部<b>应用名</b> {@code app_name}、<b>时间</b> {@code time} / {@code chronometer}、
 * 底部<b>操作按钮文字</b> {@code action0_tv}~{@code action3_tv}，见
 * {@link #SYS_UI_ID_NAMES}）。前几个到处可能撞名，还要求这个 {@code TextView}
 * 确实在通知容器里（祖先类名带 Notification）；后者是 systemui 通知布局专有的，认 id 就够。
 * 文字里应用自带的局部上色 span（{@code ForegroundColorSpan} 等）与链接色
 * （{@code ClickableSpan}，如 LSPosed 通知的「LSPosed」）也一并覆盖成自定义色。</p>
 */
public final class NotificationTextHook {

    private static final String TAG = "SystemUIHook/NotificationText";

    /**
     * 通知里该套颜色的那几块文字的 id（标题 / 正文 / 展开后的大段正文）。
     *
     * <p>编译期不直接写 {@code android.R.id.*}：这几个 id 不在公开 SDK 里
     * （系统自己用的是 {@code com.android.internal.R.id.*}），所以运行时反射取，
     * 三个命名空间都取一遍求并集，取不到的就少认一类：{@code android.R.id} /
     * {@code com.android.internal.R.id}（普通通知的 title / text / big_text）与
     * {@code com.android.systemui.R.id}（分组 / 堆叠子行的 notification_title / notification_text）。</p>
     */
    private static final Set<Integer> sTargetIds = new HashSet<Integer>();

    /** systemui 自己的包名（分组 / 堆叠子行那几个 id 属于它）。 */
    private static final String SYS_UI_PKG = "com.android.systemui";

    /** 是否已经从 systemui 的 Resources 里补齐了 notification_title / notification_text。 */
    private static volatile boolean sSystemUiIdsResolved;

    /** 诊断用：解析到的分组子行标题 id（0 = 没解析到）。 */
    private static volatile int sSysUiTitleId;
    /** 诊断用：解析到的分组子行正文 id（0 = 没解析到）。 */
    private static volatile int sSysUiTextId;

    /**
     * 是否已按当前配置同步过颜色。
     *
     * <p>用它挡住热路径上的 prefs 读取：{@code setTextColor} 每帧都可能有，
     * 每次都去读 SharedPreferences 太亏。配置一变由 {@link #refresh} 把它清掉，
     * 下次使用时再读一次。</p>
     */
    private static volatile boolean sSynced;
    /**
     * 同步后的文字颜色（ARGB）；{@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM} = 跟随系统。
     */
    private static volatile int sTextColor = TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;

    /** 每个被改过颜色的 {@code TextView} 原来的文字颜色状态列表，还原时整份塞回去。 */
    private static final Map<TextView, ColorStateList> sOriginalColors =
            Collections.synchronizedMap(new WeakHashMap<TextView, ColorStateList>());

    /**
     * 每个被改过 span 的 {@code TextView} 原来的 {@code ForegroundColorSpan} 记录。
     *
     * <p>每条记录是 {@code {start, end, flags, colorArgb}} 四元组：还原（跟随系统）时
     * 删掉我们替换进去的 span，再按原边界和原颜色重建，应用特意做的局部高亮不失真。</p>
     */
    private static final Map<TextView, List<int[]>> sOriginalSpans =
            Collections.synchronizedMap(new WeakHashMap<TextView, List<int[]>>());

    /**
     * 每个被改过链接色的 {@code TextView} 原来的链接色状态列表，还原时塞回去。
     *
     * <p>{@code ClickableSpan}（可点击的文字，如 LSPosed 通知里的「LSPosed」这个词）
     * 绘制时用的不是文字基础色，而是 {@code TextView} 的<b>链接色</b>——只改基础色，
     * 可点击的词依然是系统链接色（浅灰紫），看上去就是「就这一个词不跟色」。</p>
     */
    private static final Map<TextView, ColorStateList> sOriginalLinkColors =
            Collections.synchronizedMap(new WeakHashMap<TextView, ColorStateList>());

    /**
     * 标记「这次 {@code setTextColor} 是我们自己写的」，挡住 {@code setTextColor} 钩子的自激。
     */
    private static final ThreadLocal<Boolean> sApplyingColor =
            new ThreadLocal<Boolean>() {
                @Override
                protected Boolean initialValue() {
                    return Boolean.FALSE;
                }
            };

    private static final Set<String> sLogged = new HashSet<String>();

    /** {@link #resolveSystemUiIds} 最多试几次；试够了就落闩，免得热路径每次都去查资源。 */
    private static final int MAX_RESOLVE_ATTEMPTS = 8;

    /** {@link #resolveSystemUiIds} 已经试过几次。 */
    private static int sResolveAttempts;

    /** 诊断用：把「在通知里、但没认出来」的文字记一笔，定位像「某张卡片颜色没跟上」的 id。 */
    private static final Set<String> sDiagSeen = new HashSet<String>();

    /** 诊断还能走多少次（含没命中的）；走完就彻底不查，保证热路径开销有界。 */
    private static int sDiagBudget = 400;

    private NotificationTextHook() {
    }

    public static void install(final LoadPackageParam lpparam) {
        Set<Integer> ids = new HashSet<Integer>();
        addTargetId(ids, "com.android.internal.R$id", lpparam.classLoader, "title");
        addTargetId(ids, "com.android.internal.R$id", lpparam.classLoader, "text");
        addTargetId(ids, "com.android.internal.R$id", lpparam.classLoader, "big_text");
        addTargetId(ids, "android.R$id", lpparam.classLoader, "title");
        addTargetId(ids, "android.R$id", lpparam.classLoader, "text");
        // ColorOS 的**分组 / 堆叠**通知子行不走 android:id/*，而是用 systemui 自己的
        // notification_title / notification_text（真机 dump 实证：普通通知是
        // android:id/title+text，分组子行是 com.android.systemui:id/notification_title+text）。
        // 漏了这两个 id 的后果：分组子行文字不跟自定义文字颜色走，仍是系统的白色，
        // 在我们换上去的浅色底图上直接「融进背景」看不见。
        addTargetId(ids, "com.android.systemui.R$id", lpparam.classLoader, "notification_title");
        addTargetId(ids, "com.android.systemui.R$id", lpparam.classLoader, "notification_text");
        synchronized (sTargetIds) {
            sTargetIds.clear();
            sTargetIds.addAll(ids);
        }
        // 尽量在通知来之前就把分组 / 堆叠子行的两个 id 解析好（拿不到就等运行时再试）
        resolveSystemUiIds(eagerContext());

        int hooked = 0;
        try {
            // 文本刚下发时也贴一次颜色；真正的兜底在 setTextColor 那条钩子上
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                    TextView.BufferType.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof TextView)) {
                                return;
                            }
                            // 我们自己 setText（还原 span 用的整段替换）时不重入
                            if (Boolean.TRUE.equals(sApplyingColor.get())) {
                                return;
                            }
                            try {
                                applyToView((TextView) param.thisObject);
                            } catch (Throwable t) {
                                logOnce("apply on setText failed - " + t);
                            }
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setText failed - " + t);
        }
        hooked += hookTextColor(int.class);
        hooked += hookTextColor(ColorStateList.class);
        XposedBridge.log(TAG + ": installed (hooks=" + hooked
                + ", targetIds=" + ids.size() + ")");
    }

    /**
     * 钩 {@code TextView#setTextColor} 的两个重载，在系统写完颜色之后把自定义色补回去。
     *
     * <p>实时更新的通知靠 RemoteViews 重下发布局，颜色写在文本之后，我们贴在
     * {@code setText} 上的颜色会被当场盖掉，只有补在 {@code setTextColor} 后面才压得住。</p>
     *
     * @return 挂上返回 1，失败返回 0
     */
    private static int hookTextColor(final Class<?> paramType) {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setTextColor", paramType,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof TextView)) {
                                return;
                            }
                            try {
                                enforceTextColor((TextView) param.thisObject);
                            } catch (Throwable t) {
                                logOnce("enforce color failed - " + t);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook setTextColor(" + paramType.getSimpleName()
                    + ") failed - " + t);
            return 0;
        }
    }

    /** 反射取一个 id 值并并进去；取不到就跳过。 */
    private static void addTargetId(final Set<Integer> ids, final String className,
            final ClassLoader classLoader, final String field) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, classLoader);
            ids.add(XposedHelpers.getStaticIntField(cls, field));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 应用

    private static void applyToView(final TextView view) {
        if (!sSynced) {
            Context ctx = view.getContext();
            if (ctx == null) {
                return;
            }
            sync(ctx);
        }
        if (sTextColor == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            // 颜色「跟随系统」：没有任何覆盖，热路径一步返回
            return;
        }
        if (!isTargetText(view)) {
            return;
        }
        applyTextColor(view, sTextColor);
    }

    /** 这个 {@code TextView} 是不是通知里该套颜色的那几块（标题 / 正文 / 操作按钮）。 */
    private static boolean isTargetText(final TextView view) {
        boolean ok = matchesTargetText(view);
        if (!ok) {
            // 诊断：在通知里却没认出来的文字，把 id / 类名记一笔（有界，别拖慢热路径）
            diagUnmatched(view);
        }
        return ok;
    }

    private static boolean matchesTargetText(final TextView view) {
        // 通知里的操作按钮（「回复」/「复制验证码」/「标记已读」等情况）：整颗就是 Button，
        // 文字直接设在它自己身上，没有 title / text 那几个 id，也不带 ActionButton 类名，
        // 靠下面的「卡片里任意文字都上色」兜底认到，这里不再单独处理。
        int id = view.getId();
        resolveSystemUiIds(view.getContext());
        // ColorOS 分组 / 堆叠卡片的子行标题 / 正文用的是 systemui 自己的
        // notification_title / notification_text（或框架 header 的 app_name / time）。
        // 这些 id 名随版本变、又分属 framework / systemui 两个包，逐个匹配既繁琐又易漏。
        // 直接改成「只要是通知卡片容器里的 TextView 就上色」——见 {@link #isNotificationText}。
        if (id == sSysUiTitleId || id == sSysUiTextId) {
            logOnce("group text matched (id=" + id + ")");
            return true;
        }
        // 应用小图标右上角的角标（数字「3」那类，NotificationBadgeView）也是 TextView，
        // 就挂在通知行里——上宽网会把它一起染成自定义色，紫字压在深色角标圆上很难看。
        // 角标不是「通知文字」，排除。
        if (view.getClass().getName().contains("Badge")) {
            return false;
        }
        return isNotificationText(view);
    }

    /**
     * 「这个视图在<b>通知卡片</b>里」——通知文字上色的唯一闸门。
     *
     * <p>之前靠具体 id 名（{@code title}/{@code text}/{@code app_name}/{@code time}/
     * {@code action0_tv}…）逐个认，结果一换 ColorOS 小版本 id 名就对不上，
     * 头部应用名、时间、操作按钮文字集体掉队。改成<b>结构性判定</b>：祖先链里出现
     * 下列任意卡片级容器类名就算——它们是通知行 / 头部 / 内容 / 分组子容器 / 混合通知，
     * 几乎覆盖一张卡上所有文字（标题、正文、应用名、时间、按钮文字、分组子行）。</p>
     *
     * <p>用「卡片级」关键字而非裸 {@code Notification}，是为了不误伤外层
     * {@code NotificationStackScrollLayout}（整块通知栏滚动容器）和
     * {@code *NotificationSectionHeader}（「通知 / 静音」分区标题）。</p>
     */
    private static final String[] NOTIF_CONTAINER_KEYWORDS = {
            "NotificationRow", "NotificationHeaderView", "NotificationContent",
            "NotificationChildrenContainer", "HybridNotification",
            "NotificationTemplateViewWrapper", "NotificationViewWrapper",
    };

    /** 「这个 TextView 在通知卡片里」——文字上色和操作按钮压深共用的闸门。 */
    public static boolean isInsideNotificationCard(final View view) {
        return isNotificationText(view);
    }

    private static boolean isNotificationText(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 16; depth++) {
            String name = current.getClass().getName();
            for (String key : NOTIF_CONTAINER_KEYWORDS) {
                if (name.contains(key)) {
                    return true;
                }
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * systemui 通知布局专有的 id 名，逐个用 {@code Resources#getIdentifier} 解析。
     *
     * <p>样本（系统界面_16.00.12）resources.arsc 实证：</p>
     * <ul>
     *   <li>{@code notification_title} / {@code notification_text}：分组 / 堆叠子行的标题 / 正文
     *       （{@code HybridNotificationView} 布局）；</li>
     *   <li>{@code app_name}：卡片头部的<b>应用名</b>（「信息」「腾讯科技」），头部由代码构建、
     *       布局文件里没有；</li>
     *   <li>{@code time} / {@code chronometer}：卡片右上角的<b>时间</b>（「刚刚」）——
     *       {@code DateTimeView}，是 {@code TextView} 子类，走同一套钩子；</li>
     *   <li>{@code action0_tv}~{@code action3_tv}：底部<b>操作按钮的文字</b>——按钮容器
     *       （类名含 {@code ActionButton}）里的文字子 view，只认容器类名认不到它。</li>
     * </ul>
     */
    private static final String[] SYS_UI_ID_NAMES = {
            "notification_title", "notification_text", "app_name", "time", "chronometer",
            "action0_tv", "action1_tv", "action2_tv", "action3_tv",
    };

    /**
     * 补上 ColorOS 通知专有的一批 id（见 {@link #SYS_UI_ID_NAMES}）。
     *
     * <p>真机实证：{@code com.android.systemui.R$id} 用反射取不到（{@code targetIds} 一直是 3，
     * 说明 R 类没进 classloader），改用 {@code Resources#getIdentifier} 按资源名查。装钩子时先
     * 拿 Application 当 Context 试一次；查不到就等见到通知文字时拿它的 Context 再试，
     * 最多试 {@link #MAX_RESOLVE_ATTEMPTS} 次（避免拿到非 systemui 的 Resources 时一次失败就永久落闩）。</p>
     */
    private static void resolveSystemUiIds(final Context ctx) {
        if (sSystemUiIdsResolved || ctx == null) {
            return;
        }
        synchronized (NotificationTextHook.class) {
            if (sSystemUiIdsResolved) {
                return;
            }
            if (sResolveAttempts >= MAX_RESOLVE_ATTEMPTS) {
                // 试够了还没查到：认命落闩，别让热路径每次都去查资源
                sSystemUiIdsResolved = true;
                return;
            }
            sResolveAttempts++;
            try {
                android.content.res.Resources res = ctx.getResources();
                Set<Integer> found = new HashSet<Integer>();
                int title = 0;
                int text = 0;
                for (String name : SYS_UI_ID_NAMES) {
                    int id = res.getIdentifier(name, "id", SYS_UI_PKG);
                    if (id == 0) {
                        continue;
                    }
                    found.add(id);
                    if ("notification_title".equals(name)) {
                        title = id;
                    } else if ("notification_text".equals(name)) {
                        text = id;
                    }
                }
                if (found.isEmpty()) {
                    // 这次没查到（多半拿到的不是 systemui 的 Resources）：别落闩，换个 ctx 再试
                    return;
                }
                sSystemUiIdsResolved = true;
                sSysUiTitleId = title;
                sSysUiTextId = text;
                synchronized (sTargetIds) {
                    sTargetIds.addAll(found);
                }
                XposedBridge.log(TAG + ": resolved systemui ids, count=" + found.size()
                        + ", total=" + sTargetIds.size());
            } catch (Throwable t) {
                logOnce("resolve systemui ids failed - " + t);
            }
        }
    }

    /**
     * 贴文字颜色；{@link TileLongPressConfig#ICON_COLOR_FOLLOW_SYSTEM} 时还原成系统原色。
     *
     * <p>原色按 {@link ColorStateList} 整份存下来（文字颜色可能是状态列表，按下时会变），
     * 还原时整份塞回去，免得把系统按状态取色那套弄丢。</p>
     */
    private static void applyTextColor(final TextView view, final int colorArgb) {
        if (colorArgb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            ColorStateList orig;
            synchronized (sOriginalColors) {
                if (!sOriginalColors.containsKey(view)) {
                    return;
                }
                orig = sOriginalColors.remove(view);
            }
            setTextColorGuarded(view, orig);
            restoreOriginalSpans(view);
            restoreOriginalLinkColors(view);
            return;
        }
        synchronized (sOriginalColors) {
            if (!sOriginalColors.containsKey(view)) {
                sOriginalColors.put(view, view.getTextColors());
            }
        }
        if (view.getCurrentTextColor() != colorArgb) {
            setTextColorGuarded(view, colorArgb);
        }
        overrideSpanColors(view, colorArgb);
        applyLinkColor(view, colorArgb);
    }

    /**
     * 把链接色一并改成自定义色。
     *
     * <p>{@code ClickableSpan#updateDrawState} 把 {@code TextPaint.color} 设成
     * {@code ds.linkColor}，而 {@code ds.linkColor} 来自 {@code TextView#getLinkTextColors()}
     * ——所以可点击文字（如 LSPosed 通知里的「LSPosed」）的颜色由链接色决定，
     * 改基础色和替换 span 都管不到它，得走 {@code setLinkTextColor}。</p>
     */
    private static void applyLinkColor(final TextView view, final int colorArgb) {
        synchronized (sOriginalLinkColors) {
            if (!sOriginalLinkColors.containsKey(view)) {
                sOriginalLinkColors.put(view, view.getLinkTextColors());
            }
        }
        view.setLinkTextColor(colorArgb);
    }

    /** 「跟随系统」还原：把链接色塞回原来的。 */
    private static void restoreOriginalLinkColors(final TextView view) {
        ColorStateList link;
        synchronized (sOriginalLinkColors) {
            link = sOriginalLinkColors.remove(view);
        }
        if (link != null) {
            view.setLinkTextColor(link);
        }
    }

    /**
     * 把文字里的 {@code ForegroundColorSpan} 统一改成自定义色。
     *
     * <p><b>为什么需要这一步</b>：有的应用（如 LSPosed）在通知正文里用 span 给个别词
     * 单独上色（「LSPosed 已加载」里的「LSPosed」是灰紫色、后面的中文才是基础色）。
     * span 在绘制时优先于 {@code TextView} 的基础颜色——只改基础色，那几个词依然是
     * 应用自己的颜色，看上去就是「这张卡的字没跟上」。</p>
     *
     * <p>改动前先快照原 span（边界 + 颜色），「跟随系统」还原时按快照重建。
     * 文本不可变（{@code SpannedString}）时整段拷成 {@code SpannableString} 再换，
     * 用 {@code sApplyingColor} 挡住自己的 {@code setText} 钩子。</p>
     */
    private static void overrideSpanColors(final TextView view, final int colorArgb) {
        try {
            CharSequence text = view.getText();
            if (!(text instanceof Spanned)) {
                return;
            }
            if (!(text instanceof Spannable)) {
                // 不可变文本：拷一份可变的，把 span 改完再整段放回去
                SpannableString copy = new SpannableString(text);
                boolean fg = replaceSpansIn(copy, copy.getSpans(
                        0, copy.length(), ForegroundColorSpan.class), colorArgb, view);
                boolean ta = replaceTextAppearanceSpans(copy, copy.getSpans(
                        0, copy.length(), TextAppearanceSpan.class), colorArgb);
                if (!fg && !ta) {
                    return;
                }
                sApplyingColor.set(Boolean.TRUE);
                try {
                    view.setText(copy, TextView.BufferType.SPANNABLE);
                } finally {
                    sApplyingColor.set(Boolean.FALSE);
                }
                return;
            }
            Spannable spannable = (Spannable) text;
            replaceSpansIn(spannable, spannable.getSpans(
                    0, spannable.length(), ForegroundColorSpan.class), colorArgb, view);
            replaceTextAppearanceSpans(spannable, spannable.getSpans(
                    0, spannable.length(), TextAppearanceSpan.class), colorArgb);
        } catch (Throwable t) {
            logOnce("override spans failed - " + t);
        }
    }

    /**
     * 把 {@code TextAppearanceSpan} 自带的文字色换成自定义色（重建 span，保留字体 / 样式 / 字号）。
     *
     * <p>只动「确实带文字色」的 span（{@code getTextColor() != null}）；字号拿不到（≤0）就不碰，
     * 免得重建时把字号弄丢。</p>
     */
    private static boolean replaceTextAppearanceSpans(final Spannable spannable,
            final TextAppearanceSpan[] spans, final int colorArgb) {
        boolean changed = false;
        for (TextAppearanceSpan span : spans) {
            ColorStateList color = span.getTextColor();
            if (color == null || span.getTextSize() <= 0) {
                continue;
            }
            if (color.getDefaultColor() == colorArgb) {
                continue;
            }
            spannable.removeSpan(span);
            spannable.setSpan(new TextAppearanceSpan(span.getFamily(), span.getTextStyle(),
                            span.getTextSize(), ColorStateList.valueOf(colorArgb),
                            span.getLinkTextColor()),
                    spannable.getSpanStart(span), spannable.getSpanEnd(span),
                    spannable.getSpanFlags(span));
            changed = true;
        }
        return changed;
    }

    /** 真正的 span 替换；{@code view} 非空时把原始 span 记进去（还原用）。 */
    private static boolean replaceSpansIn(final Spannable spannable,
            final ForegroundColorSpan[] spans, final int colorArgb,
            final TextView view) {
        boolean changed = false;
        for (ForegroundColorSpan span : spans) {
            int start = spannable.getSpanStart(span);
            int end = spannable.getSpanEnd(span);
            if (start < 0 || end <= start) {
                continue;
            }
            int flags = spannable.getSpanFlags(span);
            int old = span.getForegroundColor();
            if (old == colorArgb) {
                continue;
            }
            if (view != null) {
                rememberOriginalSpan(view, start, end, flags, old);
            }
            spannable.removeSpan(span);
            spannable.setSpan(new ForegroundColorSpan(colorArgb), start, end, flags);
            changed = true;
        }
        return changed;
    }

    /** 记住一个原始 span（只记第一次见到的，之后重复改不覆盖记录）。 */
    private static void rememberOriginalSpan(final TextView view, final int start,
            final int end, final int flags, final int color) {
        synchronized (sOriginalSpans) {
            List<int[]> list = sOriginalSpans.get(view);
            if (list == null) {
                list = new ArrayList<int[]>();
                sOriginalSpans.put(view, list);
            }
            for (int[] rec : list) {
                if (rec[0] == start && rec[1] == end && rec[3] == color) {
                    return;
                }
            }
            list.add(new int[]{start, end, flags, color});
        }
    }

    /** 「跟随系统」还原：删掉我们换上的 span，按快照重建原来的。 */
    private static void restoreOriginalSpans(final TextView view) {
        List<int[]> records;
        synchronized (sOriginalSpans) {
            records = sOriginalSpans.remove(view);
        }
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            CharSequence text = view.getText();
            if (!(text instanceof Spannable)) {
                return;
            }
            Spannable spannable = (Spannable) text;
            for (ForegroundColorSpan span : spannable.getSpans(
                    0, spannable.length(), ForegroundColorSpan.class)) {
                spannable.removeSpan(span);
            }
            for (int[] rec : records) {
                spannable.setSpan(new ForegroundColorSpan(rec[3]),
                        rec[0], rec[1], rec[2]);
            }
        } catch (Throwable t) {
            logOnce("restore spans failed - " + t);
        }
    }

    /** 走钩子保护的 {@code setTextColor}：置重入标记，写的时候自己的钩子直接放行。 */
    private static void setTextColorGuarded(final TextView view, final Object color) {
        sApplyingColor.set(Boolean.TRUE);
        try {
            if (color instanceof ColorStateList) {
                view.setTextColor((ColorStateList) color);
            } else if (color instanceof Integer) {
                view.setTextColor(((Integer) color).intValue());
            }
        } catch (Throwable t) {
            logOnce("setTextColor failed - " + t);
        } finally {
            sApplyingColor.set(Boolean.FALSE);
        }
    }

    /**
     * {@code setTextColor} 钩子的后置补写：系统刚写完的颜色如果被配置覆盖，就再写回自定义色。
     *
     * <p>三道守卫按便宜到贵排：自己写的直接放行 → 没配颜色放行 → 不是通知标题 / 正文放行。</p>
     */
    private static void enforceTextColor(final TextView view) {
        if (Boolean.TRUE.equals(sApplyingColor.get())) {
            return;
        }
        if (sTextColor == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM) {
            return;
        }
        if (!isTargetText(view)) {
            return;
        }
        applyTextColor(view, sTextColor);
    }

    /**
     * 同步当前该用的文字颜色（从配置读）。结果缓存到 {@link #sTextColor}，
     * 配置变更时由 {@link #refresh} 重置。
     */
    private static void sync(final Context ctx) {
        if (sSynced) {
            return;
        }
        synchronized (NotificationTextHook.class) {
            if (sSynced) {
                return;
            }
            int color = TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
            try {
                color = TileLongPressConfig.tintColorOf(ctx,
                        TileLongPressConfig.SHAPE_NOTIFICATION, TileLongPressConfig.TINT_LABEL);
            } catch (Throwable t) {
                logOnce("sync color failed - " + t);
            }
            sTextColor = color;
            sSynced = true;
        }
    }

    // ------------------------------------------------------------------ 变更

    /**
     * 文字颜色刚被改过：丢掉缓存，重贴已经画出来的通知。
     *
     * <p>已经显示的通知不会自己重新 {@code setText}，只靠钩子要等下次刷新才看得到新颜色。
     * 所以这里借 {@link NotificationHook} 记着的通知底图视图往上找到通知行，
     * 把整棵子树里的标题 / 正文重贴一遍。</p>
     *
     * @param ctx 拿不到 Context 时（比如从 {@code refreshShape} 里调）传 null，
     *            会退而用第一张通知视图自带的那份
     */
    public static void refresh(final Context ctx) {
        sSynced = false;
        sTextColor = TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        List<View> tracked = NotificationHook.trackedViews();
        Context use = ctx;
        if (use == null && !tracked.isEmpty()) {
            use = tracked.get(0).getContext();
        }
        if (use != null) {
            sync(use);
        }
        int count = 0;
        try {
            for (View background : tracked) {
                View root = notificationRoot(background);
                count += applySubtree(root != null ? root : background, sTextColor);
            }
        } catch (Throwable t) {
            logOnce("reapply failed - " + t);
        }
        XposedBridge.log(TAG + ": refreshed -> color="
                + (sTextColor == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                        ? "follow system" : String.format("#%08X", sTextColor))
                + ", reapplied " + count + " text view(s)");
    }

    /** 样式面板诊断行用。 */
    public static String probeText() {
        int tracked = NotificationHook.trackedViews().size();
        int applied = sOriginalColors.size();
        return (sTextColor == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM
                ? "颜色跟随系统" : String.format("颜色 #%06X", sTextColor & 0x00FFFFFF))
                + " · 通知 " + tracked + " 张 · 已贴 " + applied + " 处";
    }

    // ------------------------------------------------------------------ 工具

    private static int applySubtree(final View view, final int colorArgb) {
        if (view == null) {
            return 0;
        }
        int count = 0;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            if (isTargetText(textView)) {
                applyTextColor(textView, colorArgb);
                count++;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += applySubtree(group.getChildAt(i), colorArgb);
            }
        }
        return count;
    }

    /** 从通知里的某个视图往上找到那一行的根（{@code ExpandableNotificationRow}）。 */
    private static View notificationRoot(final View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.getClass().getName().contains("ExpandableNotificationRow")) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /** 装钩子时尽力取一份 Application 当 Context，用来提前解析分组 / 堆叠子行那两个 id。 */
    private static Context eagerContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 诊断：一个「在通知里、但没被认出来」的 {@code TextView}，把 id / 类名 / 是否在通知容器里
     * 记一笔，用来定位像「某张卡片字体颜色没跟上」这种认不出的 id。
     *
     * <p>热路径（{@code setText} / {@code setTextColor}）每帧都可能走到，所以加了硬预算：
     * 总共只走 {@link #sDiagBudget} 次，同一个 {@code id#类名} 只记一次；预算耗尽后直接返回。</p>
     */
    private static void diagUnmatched(final TextView view) {
        synchronized (sDiagSeen) {
            if (sDiagBudget <= 0) {
                return;
            }
            sDiagBudget--;
        }
        if (!isNotificationText(view)) {
            return;
        }
        int id = view.getId();
        String cls = view.getClass().getSimpleName();
        synchronized (sDiagSeen) {
            if (!sDiagSeen.add(id + "#" + cls)) {
                return;
            }
        }
        CharSequence text = view.getText();
        String preview = text == null ? "" : text.toString().replace('\n', ' ');
        if (preview.length() > 20) {
            preview = preview.substring(0, 20);
        }
        XposedBridge.log(TAG + ": unmatched text id=" + id + " (" + cls + ")"
                + " inside=" + NotificationHook.isInsideNotification(view)
                + " text=\"" + preview + "\"");
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