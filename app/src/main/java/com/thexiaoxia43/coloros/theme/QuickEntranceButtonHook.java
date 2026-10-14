package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 控制中心右上角那一排小圆钮：注入 ★，并让 ★ 与系统自带的 ✎ / ⚙ / ⋮
 * 都跟着「1×1 圆形小磁贴」（{@link TileLongPressConfig#SHAPE_ENTRANCE}）的样式走。
 *
 * <p>ColorOS 的右上角按钮由
 * {@code com.oplus.systemui.plugins.qs.quickentrance.OplusQSQuickEntranceComponent}
 * 持有：{@code editBtn}(✎) / {@code settingsButton}(⚙) / {@code moreBtn}(⋮)，
 * 父容器是字段 {@code view}（{@code OplusQSQuickEntranceContainerView}，
 * 继承自 ConstraintLayout）。</p>
 *
 * <h3>注入 ★ 时两个必须处理的坑</h3>
 * <ol>
 *   <li><b>子 View 必须带 id</b>：{@code OplusQSQuickEntranceContainerView} 内部维护一个
 *       {@code ConstraintSet}，{@code applyViewArrangement()} 会执行
 *       {@code constraintSet.clone(this)} → {@code applyTo(this)}。
 *       {@code ConstraintSet} 完全靠 View 的 id 跟踪子 View，
 *       没有 id 的视图会在 {@code clone} 阶段被漏掉、{@code applyTo} 时约束丢失。
 *       所以注入的按钮必须 {@link View#setId(int)}。</li>
 *   <li><b>约束会被重算</b>：{@code applyViewArrangement} 在排列变化（展开/收起/编辑模式）
 *       时都会跑一遍，因此这里也挂上它，跑完再确认一次按钮还在。</li>
 * </ol>
 *
 * <p>外观一致性不靠自绘：背景直接复制 ✎ 按钮的 Drawable，尺寸直接取 ✎ 的
 * LayoutParams 宽度，定位用 {@code endToStart = editBtn.getId()}。</p>
 *
 * <h3>⚙ 比另外两颗多一层</h3>
 * <p>{@code settingsButton} 是个 {@code FrameLayout}（给红点留的位置），圆底与齿轮图标
 * 实际在里层那颗 {@code settingsButtonIcon}（{@code ImageView}）上；✎ / ⋮ 则都是单颗。
 * 所以这组按钮解析出来是「外层 + 里层」两层都要管，见 {@link #BUTTON_FIELDS}。</p>
 *
 * <p><b>但两层不能各铺一遍底图</b>：模块的底图本身是半透明的（默认
 * {@code 0x33FFFFFF}），同一张铺在两层上会叠出两层白，看起来就是「设置那颗比旁边几颗
 * 亮一点」。所以底图只铺在<b>最里面那层</b>（真正画着圆底与图标的那颗），外面那层壳子
 * 的底顺手摘掉，免得从半透明的底图下面透出来。</p>
 *
 * <h3>这四颗按钮的样式</h3>
 * <p>它们和 1×1 磁贴一样是正圆、一样大，但<b>不是磁贴</b>：没有开 / 关两态、没有标签，
 * 屏幕上固定就那几颗，所以单列一组「1×1 圆形小磁贴」
 * （{@link TileLongPressConfig#SHAPE_ENTRANCE}），不跟 1×1 磁贴混在一起——混在一起的话
 * 改磁贴的圆角 / 底图会顺带把这排按钮也改了。底图只铺一层（那层就是磁贴「关」的样子：
 * 深色圆底 + 浅色图标），所以主题包导入时吃的是
 * {@code status_bar_qs_tile_bg_inactive.png} 那张，见 {@link ThemePackImporter#TARGETS}。</p>
 *
 * <p>长按是否接管跟随 ★ 弹窗里的「编辑模式」总开关——打开才接管，关掉就把系统原本的
 * 长按行为还回去。外观是直接套在这四颗按钮视图上的：贴了底图、或圆角被改成具体值时
 * 换成模块的圆角底图；只调可见度时直接在系统那份圆形底上叠不透明度，观感最接近原样。</p>
 */
public final class QuickEntranceButtonHook {

    private static final String TAG = "SystemUIHook/Entrance";

    private static final String CLASS_COMPONENT =
            "com.oplus.systemui.plugins.qs.quickentrance.OplusQSQuickEntranceComponent";
    private static final String CLASS_CONTAINER =
            "com.oplus.systemui.plugins.qs.quickentrance.ui.view.OplusQSQuickEntranceContainerView";
    private static final String FIELD_CONTAINER = "view";
    private static final String FIELD_EDIT_BTN = "editBtn";
    private static final String FIELD_SETTINGS_BTN = "settingsButton";
    private static final String FIELD_SETTINGS_ICON = "settingsButtonIcon";
    private static final String FIELD_MORE_BTN = "moreBtn";

    /**
     * 系统那三颗按钮在组件里的字段，按从左到右的顺序。
     *
     * <p>每项是 {@code {外层视图, 图标视图}}：✎ / ⋮ 是单颗按钮，两个字段命中同一个
     * View；<b>⚙ 外面套了一层</b>——{@code settingsButton} 是
     * {@code FrameLayout}（给红点留的位置），真正的圆底与齿轮图标在里层那颗
     * {@code settingsButtonIcon}（{@code ImageView}）上。两层都得收进来：只收外层的话
     * 里层会把圆底盖回去、齿轮也着不上色，看着就是「右上角那个设置图标和另外三颗
     * 不一样」；但底图只能铺在里层那一层（外层摘掉），见
     * {@link #applyEntranceStyle(ViewGroup, View)}。</p>
     */
    private static final String[][] BUTTON_FIELDS = {
            {FIELD_EDIT_BTN, FIELD_EDIT_BTN},
            {FIELD_SETTINGS_BTN, FIELD_SETTINGS_ICON},
            {FIELD_MORE_BTN, FIELD_MORE_BTN},
    };

    /**
     * 这一组吃的形状配置：{@link TileLongPressConfig#SHAPE_ENTRANCE}。
     *
     * <p>单独一组，不跟 1×1 磁贴共用——两者虽然都是正圆，但这排按钮不是磁贴、
     * 长了张固定的脸，混在一起会让「改 1×1 磁贴的底图」顺带改掉这排按钮。</p>
     */
    private static final String SHAPE = TileLongPressConfig.SHAPE_ENTRANCE;

    /**
     * 取哪一套配置：{@code true}。
     *
     * <p>这一组不分「开 / 关」两套（见 {@code TileLongPressConfig#hasStateVariants}），
     * 读写都会被归一成这一套；这里写 {@code true} 只是为了读代码时口径清楚。</p>
     */
    private static final boolean STATE = true;

    /**
     * 接管背景但没贴底图时的填充色（半透明白）。
     *
     * <p>只有「圆角被拖成具体值」这一种情况会用到：那时系统那个圆形底按形状套不上，
     * 得自己画一个圆角矩形，颜色取和系统圆形底最接近的半透明白。</p>
     */
    private static final int DEFAULT_BG_COLOR = PrefKeys.DEF_TILE_BG_COLOR;

    /** 暗色主题下 ★ 跟随系统时用的文字色（注入时的初值）。 */
    private static final int STAR_COLOR = 0xFFFFFFFF;

    /**
     * 亮色主题下 ★ 跟随系统时的兜底图标色。
     *
     * <p>只在读不到 ✎ 的图标着色时才用到（Material 那套 87% 黑，与系统亮色主题的图标观感一致）。</p>
     */
    private static final int FALLBACK_ICON_COLOR_DAY = 0xE6000000;

    /** 给注入的按钮打的 tag，用来避免重复添加。 */
    private static final String VIEW_TAG = "systemui_hook_entrance_button";

    /** ConstraintLayout 里表示“父容器”的 id。 */
    private static final int PARENT_ID = 0;
    /** ConstraintLayout 里表示“未设置约束”的值。 */
    private static final int UNSET = -1;

    /** 注入延迟：等布局真正 attach 完再插，避免 getParent() 为空。 */
    private static final long INJECT_DELAY_MS = 80L;

    /**
     * 收到配置变化（日夜切换等）后，隔多久再补一遍样式。
     *
     * <p><b>不能当场就补</b>：系统处理配置变化时会把它自己那三颗按钮的底重设一遍，
     * 我们补早了就被它随后那一轮盖掉，看着就是「切一下主题，底图回系统默认」。
     * 等它换完再铺，才盖得住。</p>
     */
    private static final long RECONFIG_DELAY_MS = 300L;

    /**
     * 这一组的长按回调。做成常量实例，好判断「现在挂的是不是我们自己那一个」。
     *
     * <p>按下时再判一次开关：从 ★ 弹窗把「编辑模式」关掉之后，监听要等下一次
     * {@link #refresh()} 才会被摘掉，中间这一下不该还弹面板。</p>
     *
     * <p>弹的是「1×1 圆形小磁贴」的样式面板（{@link TileLongPressConfig#SHAPE_ENTRANCE}）——
     * 这一组就那几颗按钮共用一份配置，没有开 / 关两态。</p>
     */
    private static final View.OnLongClickListener LONG_CLICK = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View view) {
            if (!TileLongPressConfig.isBlocked(view.getContext(), SHAPE)) {
                return false;
            }
            return TileStylePopup.show(view, SHAPE, STATE, null);
        }
    };

    /**
     * 最近一次用过的容器，供 {@link #refresh()} 就地重评。
     *
     * <p>只存引用不做清理：容器本身活到 SystemUI 进程结束，不存在泄漏问题。</p>
     */
    private static volatile ViewGroup sContainer;

    /** 组件实例：三颗系统按钮只能从它的字段里拿，{@link #refresh()} 也要靠它。 */
    private static volatile Object sComponent;

    /** 该按钮原本的背景（我们接管之前那一份），「跟随系统」时用它还原。 */
    private static final Map<View, Drawable> sOriginalBg =
            Collections.synchronizedMap(new WeakHashMap<View, Drawable>());

    /** 我们给哪几颗按钮挂过自己的长按回调（挂过的才需要摘）。 */
    private static final Map<View, Boolean> sLongPressOurs =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /**
     * ★ 的「跟随 ✎ 淡出」同步监听当前挂在哪个 {@link ViewTreeObserver} 上。
     *
     * <p>监听必须挂在容器的观察者上，而<b>观察者在视图 detach / attach 后会整个换掉</b>
     * （重启手机后 SystemUI 重新建窗口就是这种情况）：挂在旧观察者上那一份不会自己搬过去，
     * 于是 ★ 不再跟着 ✎ 淡出——面板收完被一起摘掉时才「啪」地消失，看着就是「消失延迟太高」。
     * 所以每轮重评样式时都核对一次，换了就重挂，见 {@link #ensureSyncToEditButton}。</p>
     */
    private static final Map<View, ViewTreeObserver> sSyncedVto =
            Collections.synchronizedMap(new WeakHashMap<View, ViewTreeObserver>());

    /** ★ 的同步监听当前照哪颗 ✎ 抄（组件把按钮整颗换掉时得换人）。 */
    private static final Map<View, View> sSyncedAnchor =
            Collections.synchronizedMap(new WeakHashMap<View, View>());

    /** 同步监听重挂的次数（诊断行用：重启后跟不上 ✎ 时一眼能看出挂没挂上）。 */
    private static volatile int sSyncAttach;

    /** 这一组里的按钮（含注入的 ★），供 {@link #isInsideEntrance(View)} 判定用。 */
    private static final Map<View, Boolean> sEntranceViews =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    /**
     * 最近一次套样式时的日夜模式（{@code Configuration.UI_MODE_NIGHT_*}），
     * {@code -1} = 还没套过。变了就说明系统换了主题那套资源。
     */
    private static volatile int sUiMode = -1;

    /** 最近一次套样式时这一组有几颗按钮（⚙ 算一颗，不管它套了两层）。 */
    private static volatile int sButtonCount;
    /** 最近一次实际套了样式的视图数（⚙ 有两层，所以会比按钮数多）。 */
    private static volatile int sViewCount;
    /** 套样式被执行的次数（样式面板的诊断行用）。 */
    private static volatile int sApplied;

    /** 最近一次套样式时是不是真铺了模块自己的底图（铺过才有被系统盖回去的风险）。 */
    private static volatile boolean sOwnBackground;
    /** 正在由我们设置底图：{@link #installBackgroundGuard} 里直接放行，免得自己钩自己。 */
    private static volatile boolean sSettingBackground;
    /** 补铺已经排过队了，别重复 post。 */
    private static volatile boolean sRepairPosted;
    /** 底图被系统盖回默认后补铺的次数（样式面板诊断行用）。 */
    private static volatile int sStolenFixes;

    private QuickEntranceButtonHook() {
    }

    public static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> component = XposedHelpers.findClassIfExists(CLASS_COMPONENT, lpparam.classLoader);
        if (component == null) {
            XposedBridge.log(TAG + ": " + CLASS_COMPONENT + " not found, skip");
            return;
        }

        // ① 组件生命周期：拿到容器后延迟注入
        XC_MethodHook onComponentEvent = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    final View container =
                            (View) XposedHelpers.getObjectField(param.thisObject, FIELD_CONTAINER);
                    if (container == null) {
                        XposedBridge.log(TAG + ": container field is null in "
                                + param.method.getName());
                        return;
                    }
                    container.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ensureButton(container, param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": inject failed - " + t);
                            }
                        }
                    }, INJECT_DELAY_MS);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": schedule inject failed - " + t);
                }
            }
        };
        for (String method : new String[]{"onInit", "onViewAttached"}) {
            try {
                XposedHelpers.findAndHookMethod(component, method, onComponentEvent);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook " + method + " failed - " + t);
            }
        }

        // ② 容器重算约束后重新评估一次。
        //    这里必须无条件跑 ensureButton，不能只在「按钮不见了」时补：
        //    用户在设置页把开关关掉之后，已经画出来的那颗也得被摘掉。
        Class<?> containerCls = XposedHelpers.findClassIfExists(CLASS_CONTAINER, lpparam.classLoader);
        if (containerCls != null) {
            try {
                XposedHelpers.findAndHookMethod(containerCls, "applyViewArrangement", boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    ensureButton((View) param.thisObject, null);
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": re-ensure failed - " + t);
                                }
                            }
                        });
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook applyViewArrangement failed - " + t);
            }
        } else {
            XposedBridge.log(TAG + ": " + CLASS_CONTAINER + " not found (仅兜底逻辑失效)");
        }

        // ③ 配置变化（日夜切换等）：排到系统那轮换完主题之后再补一遍样式。
        //    容器 / 组件上有这个方法就顺手挂一份；Oplus 若把它挪到了父类、或另走
        //    onThemeChanged，兜底靠 Application 那一份（进程级一定会收到）
        final XC_MethodHook onConfigChanged = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                scheduleReapply(RECONFIG_DELAY_MS);
            }
        };
        for (Class<?> cls : new Class<?>[]{containerCls, component}) {
            if (cls == null) {
                continue;
            }
            try {
                XposedHelpers.findAndHookMethod(cls, "onConfigurationChanged",
                        Configuration.class, onConfigChanged);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": hook onConfigurationChanged on "
                        + cls.getSimpleName() + " failed - " + t);
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
                    "onConfigurationChanged", Configuration.class, onConfigChanged);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Application.onConfigurationChanged failed - " + t);
        }

        // ④ 防盖：系统换主题 / 重排时会把它自己那三颗按钮的底重设一遍，把我们铺的模块
        //    底图顶回默认。盯着这排按钮上的 setBackgroundDrawable，一发现换成了不是我们
        //    的图就补一遍——这样不依赖任何「补样式」的触发时机先后
        installBackgroundGuard();

        XposedBridge.log(TAG + ": installed");
    }

    /**
     * 立刻按当前配置重评一次这一排按钮：★ 该不该在、四颗的样式与长按怎么套。
     *
     * <p>配置刚被改过时由 {@link TileImageChannel} 与
     * {@link TileLongPressInterceptor#refreshShape(String)} 调过来。只靠
     * {@code onInit} / {@code applyViewArrangement} 的话，改完圆角 / 底图要等到
     * 下次控制中心排列变化才看得到；「让星星按钮可见」关掉之后那颗按钮也一样
     * 要等到那时才被摘掉。</p>
     *
     * @return 这次套到的按钮数量（0 = 控制中心还没开过）
     */
    public static int refresh() {
        if (sContainer == null) {
            // 控制中心还没开过，按钮自然也没画出来，等 onInit 时按新值判断即可
            return 0;
        }
        refreshNow();
        return sButtonCount;
    }

    /**
     * 按当前配置重评一次这一排按钮。
     *
     * <p>与 {@link #refresh()} 的区别：这里先从组件字段重新读一遍容器——切主题 /
     * 重排时 Oplus 有可能把容器整颗换掉，{@link #sContainer} 指向的就成了旧的那颗，
     * 补样式会补到一颗已经不在屏幕上的容器上。</p>
     */
    private static void refreshNow() {
        Object component = sComponent;
        ViewGroup container = sContainer;
        View fromField = readViewField(component, FIELD_CONTAINER);
        if (fromField instanceof ViewGroup) {
            container = (ViewGroup) fromField;
        }
        if (container == null) {
            return;
        }
        try {
            ensureButton(container, component);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": refresh failed - " + t);
        }
    }

    /**
     * 隔 {@code delayMs} 补一次样式；已经排过队就不重复排
     * （一次配置变化会同时惊动容器、组件与 Application）。
     */
    private static void scheduleReapply(long delayMs) {
        final ViewGroup container = sContainer;
        if (container == null || sRepairPosted) {
            return;
        }
        sRepairPosted = true;
        container.postDelayed(new Runnable() {
            @Override
            public void run() {
                sRepairPosted = false;
                refreshNow();
            }
        }, delayMs);
    }

    /**
     * 盯住这排按钮上的 {@code View#setBackgroundDrawable}。
     *
     * <p>系统换主题 / 重排时会把它自己那三颗按钮的底重新设一遍，我们铺的模块底图就
     * 被顶回系统默认了。★ 是我们自己插进去的、没人动它，所以它反倒留得住——现象上
     * 就是「四颗里只剩 ★ 还顶着模块那张底，另外三颗回默认」。挂这个钩子能第一时间
     * 发现并补回去，不用猜系统什么时候动手。</p>
     *
     * <p>只在「我们确实铺了底图」时才做事：没铺的时候系统爱怎么设怎么设，一概不插手。</p>
     */
    private static void installBackgroundGuard() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundDrawable",
                    Drawable.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onBackgroundSet((View) param.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook View.setBackgroundDrawable failed - " + t);
        }
    }

    private static void onBackgroundSet(View v) {
        if (!sOwnBackground || sSettingBackground) {
            return;      // 我们没铺底图、或者就是我们自己在设这一下
        }
        if (v.getBackground() instanceof TileBackgroundDrawable || !isInsideEntrance(v)) {
            return;
        }
        // 系统把底盖回它自己的了：排到下一轮消息里整排重算
        sStolenFixes++;
        scheduleReapply(0L);
    }

    /**
     * 我们自己设底图。
     *
     * <p>打个标记让 {@link #onBackgroundSet} 放行——否则我们自己这一下也会被当成
     * 「系统把底盖回去了」，来回补个没完。</p>
     */
    private static void setBackgroundOurs(View v, Drawable d) {
        sSettingBackground = true;
        try {
            v.setBackground(d);
        } finally {
            sSettingBackground = false;
        }
    }

    /**
     * 确保 ★ 按钮已插到 ✎ 左边，并把这一排四颗按钮的样式套上去。
     * 可重复调用（★ 靠 tag 去重，样式每次都重算）。
     *
     * @param component 组件实例，可为 null（此时沿用 {@link #sComponent} 或从容器子 View 里找）
     */
    private static void ensureButton(View container, Object component) {
        if (!(container instanceof ViewGroup)) {
            return;
        }
        final ViewGroup parent = (ViewGroup) container;
        sContainer = parent;
        if (component != null) {
            sComponent = component;
        }
        View existing = parent.findViewWithTag(VIEW_TAG);
        // 提前算一次：★ 的背景要照它抄（见 applyEntranceStyle 里的重同步）
        View editBtn = resolveEditButton(parent);

        // 模块设置页里关掉「让星星按钮可见」之后就不再注入；已经画出来的那颗顺手摘掉
        // （必须在下一个消息循环里 removeView：这里可能正处在布局过程中）。
        // 开关值读 SystemUI 自己那份 prefs：模块 App 的 prefs 跨进程读不到，
        // 设置页改开关时会把新值随广播送过来，见 TileImageChannel
        if (!TileLongPressConfig.isEntranceVisible(parent.getContext())) {
            if (existing != null) {
                parent.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            View stale = parent.findViewWithTag(VIEW_TAG);
                            if (stale != null) {
                                parent.removeView(stale);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": remove disabled button failed - " + t);
                        }
                    }
                });
            }
        } else if (existing == null) {
            if (editBtn == null) {
                XposedBridge.log(TAG + ": skip, edit button not found (children="
                        + parent.getChildCount() + ")");
            } else {
                injectStar(parent, editBtn);
            }
        }

        // 三颗系统按钮与 ★ 共用一份样式：不管 ★ 在不在（可见性开关是另一回事），
        // 这一组都照当前配置套一遍
        // ★ 的「跟随 ✎ 淡出」同步监听每轮都核对一次：容器的 ViewTreeObserver 在
        // detach / attach 后会整个换掉（重启手机必发生），不重挂的话 ★ 就不跟 ✎ 一起淡出，
        // 要等面板收完被一起摘掉才消失
        ensureSyncToEditButton(parent, editBtn, parent.findViewWithTag(VIEW_TAG));
        applyEntranceStyle(parent, editBtn);
    }

    /** 在 ✎ 左边注入 ★（外观 / 定位 / 尺寸全部照抄 ✎）。 */
    private static void injectStar(ViewGroup parent, View editBtn) {
        Context ctx = parent.getContext();
        int size = measure(editBtn, ctx);

        TextView btn = new TextView(ctx);
        // 关键：ConstraintSet 靠 id 跟踪子 View，没 id 会被 clone/applyTo 丢掉
        btn.setId(View.generateViewId());
        btn.setTag(VIEW_TAG);
        btn.setText("★");
        btn.setTextColor(readSystemIconColor(editBtn, ctx));
        btn.setTextSize(16f);
        btn.setIncludeFontPadding(false);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setContentDescription("SystemUI Hook");
        setBackgroundOurs(btn, copyBackground(editBtn));

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TileLongPressPopup.toggle(v);
            }
        });

        parent.addView(btn, buildLayoutParams(editBtn, size));
        matchSiblingSpacing(parent, editBtn, btn);
        // 同步监听不在这里挂：{@link #ensureButton} 每轮都会核对一次、该挂才挂，
        // 见 {@link #ensureSyncToEditButton}
        XposedBridge.log(TAG + ": button injected, size=" + size
                + ", anchorId=0x" + Integer.toHexString(editBtn.getId())
                + ", parent=" + parent.getClass().getSimpleName());
    }

    /**
     * 把「1×1 圆形小磁贴」那套配置套到四颗按钮上：背景（圆角 / 可见度 / 底图）、
     * 图标颜色、长按。
     *
     * <p>读一次、循环套即可。每次 {@code applyViewArrangement} 都会走一遍，样式便宜
     * （底图走的是解码缓存）且必须重算——系统重排时可能把按钮背景重置回默认。</p>
     *
     * <p><b>一层按钮只铺一遍底图</b>：⚙ 是「外壳 + 里层图标」两层，两层各铺一遍半透明
     * 底图会叠出两层白、比旁边几颗亮。所以底图只铺在每组<b>最里面那层</b>（真正画着圆底
     * 的那颗），同组的其他层在模块底图接管时把底摘掉（先存下来，跟随系统时好还原）。</p>
     *
     * <p><b>日夜切换要重来一遍</b>：系统那三颗的圆底是「日一套、夜一套」两份资源，
     * 切一次主题就换一份。我们存下来的副本、以及注入的 ★ 那份抄来的底都还是旧主题那张，
     * 于是「切亮色再回暗色」之后 ★ 还顶着一张浅色圆底、旁边三颗已经变深——看着就是四颗
     * 不是一个样。所以 uiMode 一变就把缓存的副本全作废，并把 ★ 重新照 ✎ 抄一遍：
     * ★ 不在系统的日 / 夜更新链路上，不重抄就永远停在注入时那个主题。</p>
     *
     * @param editBtn ✎ 按钮，★ 的背景照它抄；读不到组件字段时可能为 null
     */
    private static void applyEntranceStyle(ViewGroup parent, View editBtn) {
        List<List<View>> groups = resolveEntranceGroups(parent);
        sButtonCount = groups.size();
        if (groups.isEmpty()) {
            sOwnBackground = false;
            return;
        }
        Context ctx = parent.getContext();
        // 日 / 夜切换：系统那份底已经换成新主题的了，之前存的副本一律作废
        int uiMode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (uiMode != sUiMode) {
            sUiMode = uiMode;
            sOriginalBg.clear();
        }
        TileLongPressConfig.Style style = TileLongPressConfig.styleOf(ctx, SHAPE);
        Bitmap bitmap = TileBackgroundHook.decodeShapeImage(
                TileLongPressConfig.tileBgImage(ctx, SHAPE, STATE));
        int alphaPct = style != null
                ? style.alphaPct(STATE) : TileLongPressConfig.DEFAULT_ALPHA_PCT;
        int cornerDp = style != null
                ? style.cornerDp : TileLongPressConfig.CORNER_FOLLOW_SYSTEM;
        // 「跟随系统」时是 -1：这四颗按钮原本就是正圆，-1 让底图按短边一半画
        float cornerPx = TileLongPressConfig.cornerRadiusPx(ctx, SHAPE);
        int tint = TileLongPressConfig.tintColorOf(ctx, SHAPE,
                TileLongPressConfig.TINT_ICON_OFF);
        // 三样都没动就完全不碰：系统的圆形底、按压反馈都保持原样
        boolean takeOver = bitmap != null || cornerDp >= 0
                || alphaPct != TileLongPressConfig.DEFAULT_ALPHA_PCT;
        // 是不是换成了模块自己的底图（只调可见度时用的是系统那份圆底，各层照旧）
        boolean ownBackground = takeOver && (bitmap != null || cornerPx >= 0f);

        // ★ 那份底是注入时从 ✎ 抄的副本，系统的日 / 夜更新到不了它身上；没换模块底图时
        // 每次都照当前主题的 ✎ 重抄一遍，四颗才永远一个样（✎ 读不到就只好作罢）
        View star = parent.findViewWithTag(VIEW_TAG);
        if (star != null && editBtn != null && !ownBackground) {
            Drawable fresh = copyBackground(editBtn);
            if (fresh != null) {
                setBackgroundOurs(star, fresh);
                sOriginalBg.remove(star);      // 旧副本作废，下面重新存一份
            }
        }

        int views = 0;
        for (List<View> group : groups) {
            View target = group.get(group.size() - 1);
            for (View v : group) {
                sEntranceViews.put(v, Boolean.TRUE);
                if (v == target || !ownBackground) {
                    applyBackground(v, takeOver, cornerPx, bitmap, alphaPct);
                } else {
                    // 外层壳子：底图铺在最里层就够，多铺一层会把半透明的底叠白；
                    // 它自己那份系统底（如果有）也先摘掉，免得透出来。原值先存着，
                    // 之后「跟随系统」要还原
                    rememberOriginal(v);
                    setBackgroundOurs(v, null);
                }
                applyTint(v, tint, editBtn);
                applyLongPress(v, ctx);
                views++;
            }
        }
        sViewCount = views;
        sApplied++;
        // 铺了模块自己的底图才需要防系统把它盖回去（见 installBackgroundGuard）
        sOwnBackground = ownBackground;
    }

    /**
     * 这一组的按钮，按「一颗按钮 = 一组视图」拆开。
     *
     * <p>注入的 ★ + 系统那三颗。三颗优先读组件的字段（见 {@link #BUTTON_FIELDS}）；
     * ⚙ 有外层与里层两层，都收进它那一组——底图只铺组里最里面那层，长按则每层都挂
     * （哪颗能接到手势就哪颗生效）。字段读不全时再从容器直接子 View 里按
     * {@code ImageButton} 找（★ 是 TextView，不会被误收）。</p>
     *
     * <p>判定用「是不是容器的后代」而不是「父是不是容器」：⚙ 外层套在别的容器里的
     * 情况下，父层比较会把它整颗漏掉。</p>
     */
    private static List<List<View>> resolveEntranceGroups(ViewGroup parent) {
        List<List<View>> groups = new ArrayList<List<View>>(4);
        View star = parent.findViewWithTag(VIEW_TAG);
        if (star != null) {
            groups.add(singleton(star));
        }

        int fromField = 0;
        for (String[] pair : BUTTON_FIELDS) {
            View outer = readViewField(sComponent, pair[0]);
            if (outer == null || !isInside(outer, parent)) {
                continue;
            }
            fromField++;
            List<View> group = new ArrayList<View>(2);
            addLayer(group, outer, parent);
            // 图标可能在外层里面那颗（⚙ 就是），也可能就是外层本身（✎ / ⋮）
            addLayer(group, readViewField(sComponent, pair[1]), parent);
            if (!group.isEmpty()) {
                groups.add(group);
            }
        }
        if (fromField < BUTTON_FIELDS.length) {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (!(child instanceof ImageButton) || containsView(groups, child)) {
                    continue;
                }
                groups.add(singleton(child));
            }
        }
        return groups;
    }

    /** 只有一颗视图的组。 */
    private static List<View> singleton(View v) {
        List<View> group = new ArrayList<View>(1);
        group.add(v);
        return group;
    }

    /** {@code v} 是否已经进过某组。 */
    private static boolean containsView(List<List<View>> groups, View v) {
        for (List<View> group : groups) {
            if (group.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /** 收一层视图：去重，且必须真的挂在容器下面。 */
    private static void addLayer(List<View> group, View v, ViewGroup parent) {
        if (v != null && !group.contains(v) && isInside(v, parent)) {
            group.add(v);
        }
    }

    /** {@code v} 是不是 {@code parent} 的后代（自身也算）。 */
    private static boolean isInside(View v, ViewGroup parent) {
        View node = v;
        while (node != null) {
            if (node == parent) {
                return true;
            }
            ViewParent up = node.getParent();
            node = up instanceof View ? (View) up : null;
        }
        return false;
    }

    /** 读组件里的一个 View 字段；读不到返回 null。 */
    private static View readViewField(Object component, String field) {
        if (component == null) {
            return null;
        }
        try {
            Object value = XposedHelpers.getObjectField(component, field);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 套背景：圆角 / 可见度 / 底图。
     *
     * <p>贴着底图或把圆角拖成具体值时才换成模块自己的 {@link TileBackgroundDrawable}
     * （底图那张按中心裁剪铺进圆角里）；只是调了可见度的话，在系统当前那份底的副本上叠
     * 不透明度——系统的底是带按压反馈的 drawable，能不动就不动。</p>
     *
     * <p><b>没接管时一概不碰</b>：以前这里不管接管与否都把存下来的副本重新
     * {@code setBackground} 回去，切了日夜之后那份副本还是旧主题的，按钮就顶着一张
     * 旧主题的底不动了（「切亮色再回暗色」出 bug 的一半在这里）。现在只有「上一轮确实
     * 是我们换上去的」才还原，其余情形交给系统自己更新。</p>
     */
    private static void applyBackground(View v, boolean takeOver, float cornerPx,
            Bitmap bitmap, int alphaPct) {
        boolean ours = v.getBackground() instanceof TileBackgroundDrawable;
        if (takeOver && (bitmap != null || cornerPx >= 0f)) {
            // 换之前先把系统那份存下来（已经是我们换上去的就不用再存了）
            if (!ours) {
                rememberOriginal(v);
            }
            setBackgroundOurs(v,
                    new TileBackgroundDrawable(DEFAULT_BG_COLOR, cornerPx, bitmap, alphaPct));
            return;
        }
        if (ours) {
            // 上一轮是我们换上去的：把系统那份还回去
            Drawable original = sOriginalBg.get(v);
            if (original != null) {
                original.setAlpha(alphaTo255(takeOver ? alphaPct : 100));
                setBackgroundOurs(v, original);
            }
            return;
        }
        if (takeOver) {
            // 只调了可见度：用系统当前那份底的副本叠 alpha。不用缓存里那份——
            // 它可能是另一个日夜的
            Drawable copy = copyBackground(v);
            if (copy != null) {
                copy.setAlpha(alphaTo255(alphaPct));
                setBackgroundOurs(v, copy);
            }
        }
        // 既不接管、也没留下我们的底图 → 系统自己管这份底，完全不碰
    }

    /** 可见度百分比 → 0–255 的 alpha。 */
    private static int alphaTo255(int alphaPct) {
        return Math.max(0, Math.min(255, alphaPct * 255 / 100));
    }

    /**
     * 把该按钮<b>接管前</b>那副背景存一份（复制过，不共用系统实例），
     * 「跟随系统」时靠它还原。
     *
     * <p>每次都重新抓：系统日 / 夜切换后 {@code getBackground()} 已经是新主题那份了，
     * 沿用旧副本会还原出上个主题的样子。背景已经是我们的底图、或已被我们摘成 null
     * （⚙ 的外层壳子）时不动手，保留上一次存的那份。</p>
     */
    private static void rememberOriginal(View v) {
        if (v.getBackground() instanceof TileBackgroundDrawable) {
            return;
        }
        Drawable copy = copyBackground(v);
        if (copy != null) {
            sOriginalBg.put(v, copy);
        }
    }

    /**
     * 套图标颜色：系统那三颗是 ImageView 的图标（colorFilter），★ 是文字。
     *
     * <p><b>★ 的「跟随系统」不能写死白色</b>：★ 是我们注入的 {@link TextView}，
     * 颜色不会跟着主题走，而系统那三颗的图标在亮色主题下是深色。以前这里直接给白，
     * 亮色主题下就成了「白字画在白圆底上」——整颗按钮看着只剩一块白，星星没了。</p>
     *
     * @param anchor ✎ 那颗按钮，★ 的「跟随系统」照它当前图标色取值
     */
    private static void applyTint(View v, int color, View anchor) {
        boolean follow = color == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
        if (v instanceof ImageView) {
            ImageView icon = (ImageView) v;
            if (follow) {
                icon.clearColorFilter();
            } else {
                icon.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
            }
        } else if (v instanceof TextView) {
            ((TextView) v).setTextColor(
                    follow ? readSystemIconColor(anchor, v.getContext()) : color);
        }
    }

    /**
     * 读 ✎ 当前图标的着色，供 ★ 在「跟随系统」时取用。
     *
     * <p>系统的图标色不是写在资源里的，而是运行期给 drawable 上的（日 / 夜两套），
     * 所以只能从视图上反查：先取 {@link ImageView} 的 {@code imageTintList}，
     * 再退到 ImageView / drawable 上的 {@link PorterDuffColorFilter}，
     * 都读不到时按当前日夜给一个能看清的兜底色（亮色深、暗色白）。</p>
     */
    private static int readSystemIconColor(final View anchor, final Context ctx) {
        if (anchor instanceof ImageView) {
            ImageView icon = (ImageView) anchor;
            ColorStateList tint = icon.getImageTintList();
            if (tint != null) {
                return tint.getColorForState(icon.getDrawableState(), tint.getDefaultColor());
            }
            Integer fromFilter = colorFromFilter(icon.getColorFilter());
            if (fromFilter == null) {
                Drawable d = icon.getDrawable();
                if (d != null) {
                    fromFilter = colorFromFilter(d.getColorFilter());
                }
            }
            if (fromFilter != null) {
                return fromFilter.intValue();
            }
        }
        return isNight(ctx) ? STAR_COLOR : FALLBACK_ICON_COLOR_DAY;
    }

    /**
     * 从 {@code ColorFilter} 里取色；不是 {@link PorterDuffColorFilter} 就返回 null。
     *
     * <p>{@code getColor()} 编译期拿不到（是较新 SDK 才有的方法），走反射。</p>
     */
    private static Integer colorFromFilter(ColorFilter filter) {
        if (!(filter instanceof PorterDuffColorFilter)) {
            return null;
        }
        try {
            Object r = XposedHelpers.callMethod(filter, "getColor");
            return r instanceof Integer ? (Integer) r : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 当前是不是暗色主题（读不到 Context 时按亮色处理）。 */
    private static boolean isNight(final Context ctx) {
        return ctx != null && (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 套长按：跟随「编辑模式」总开关，见 {@link TileLongPressConfig#isBlocked}。
     *
     * <p>长按监听必须挂在按钮自己身上——这几颗按钮原本不是长按可点的，
     * 只靠 {@code TileLongPressInterceptor} 那一层拦是拦不到的（那层是从
     * {@code performLongClick} 进去的，按钮不触发长按就进不去）。</p>
     *
     * <p>不接管时把监听摘掉并让长按重新不可点：留着的话按下去会有一次「长按震动」
     * 却什么都不发生，像坏了一样。</p>
     *
     * <p>只挂在<b>本来就能接手势</b>的视图上（自己可点、或我们已经挂过的）。⚙ 里面那颗
     * 图标多半自己不可点：给它挂长按会让 {@code setOnLongClickListener} 顺手把
     * {@code longClickable} 打开，于是它把触摸吃掉却不响应点击——设置按钮就点不动了。
     * 那种情况下长按交给外层那颗去接。</p>
     */
    private static void applyLongPress(View v, Context ctx) {
        boolean blocked = TileLongPressConfig.isBlocked(ctx, SHAPE);
        boolean ours = sLongPressOurs.containsKey(v);
        if (blocked) {
            if (ours || (!v.isClickable() && !v.isLongClickable())) {
                return;
            }
            v.setOnLongClickListener(LONG_CLICK);
            v.setLongClickable(true);
            sLongPressOurs.put(v, Boolean.TRUE);
        } else if (ours) {
            // 系统原本没给这几颗按钮挂长按监听（只有点击回调），所以还原就是摘掉
            v.setOnLongClickListener(null);
            v.setLongClickable(false);
            sLongPressOurs.remove(v);
        }
    }

    /** 长按的是不是这一排入口按钮（含 ★）。{@code TileLongPressInterceptor} 用它摘出这些视图。 */
    public static boolean isInsideEntrance(View v) {
        ViewGroup container = sContainer;
        if (v == null || container == null) {
            return false;
        }
        View node = v;
        while (node != null) {
            if (sEntranceViews.containsKey(node)) {
                return true;
            }
            if (node == container) {
                break;
            }
            ViewParent parent = node.getParent();
            node = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** 样式面板诊断行：这一排按钮到底注入了没、套了几次。 */
    static String probeText() {
        if (sContainer == null) {
            return "控制中心还没开过，未注入";
        }
        return "已收到 " + sButtonCount + " 颗（套到 " + sViewCount + " 处视图）· 套样式 "
                + sApplied + " 次 · 底图被系统抢回 " + sStolenFixes + " 次（已补回）"
                + " · 跟随同步 " + sSyncAttach + " 次";
    }

    /**
     * 把注入按钮与 ✎ 的间距调成和 ✎ / ⚙ / ⋮ 三者之间的一致。
     *
     * <p>这三颗按钮的间距并不是写在布局 XML 里的，而是运行期由
     * {@code ConstraintSet} 通过 {@code connect(..., margin)} 设置进去的，
     * 所以没法在 XML 或代码里直接照抄一个数值。这里等布局完成后再量出
     * ✎ 与它右边那颗按钮的实际像素间隔，把它作为我们按钮的 endMargin，
     * 这样在任何分辨率/密度下都与系统保持一致。</p>
     */
    private static void matchSiblingSpacing(final ViewGroup parent, final View anchor,
            final View btn) {
        parent.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (btn.getParent() == null || anchor.getWidth() == 0) {
                        return;
                    }
                    // 取 ✎ 右侧最近且可见的兄弟 View（即 ⚙）的实际间隔
                    int gap = Integer.MAX_VALUE;
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View child = parent.getChildAt(i);
                        if (child == anchor || child == btn
                                || child.getVisibility() != View.VISIBLE) {
                            continue;
                        }
                        int d = child.getLeft() - anchor.getRight();
                        if (d > 0 && d < gap) {
                            gap = d;
                        }
                    }
                    if (gap == Integer.MAX_VALUE) {
                        XposedBridge.log(TAG + ": sibling gap not found, keep default spacing");
                        return;
                    }
                    ViewGroup.LayoutParams lp = btn.getLayoutParams();
                    if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
                        return;
                    }
                    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                    mlp.setMarginEnd(gap);
                    // ConstraintLayout 对 END 侧约束读 endMargin，部分路径回落 rightMargin
                    mlp.rightMargin = gap;
                    btn.setLayoutParams(mlp);
                    XposedBridge.log(TAG + ": spacing matched, gap=" + gap + "px");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": matchSiblingSpacing failed - " + t);
                }
            }
        });
    }

    /**
     * 确保 ★ 的「跟随 ✎ 淡出」同步监听确实是挂着的；<b>可重复调用</b>。
     *
     * <p>监听只能挂在容器的 {@link ViewTreeObserver} 上，而观察者在视图
     * detach / attach 之后会整个换掉（重启手机后 SystemUI 重建窗口就是这种情况）。
     * 挂在旧观察者上的那一份不会自己搬到新的上面，于是 ★ 不再跟 ✎ 淡出——
     * 面板收完被一起摘掉时才「啪」地消失，观感就是「消失延迟太高」。</p>
     *
     * <p>所以这里不靠「只在注入时挂一次」，而是每轮重评样式时核对三件事，任一变了就重挂：
     * 观察者对象是不是同一个、锚点（✎）是不是同一颗、旧的观察者还活着没。</p>
     *
     * @param editBtn ✎ 按钮，读不到时为 null（此时没法同步，等下一轮）
     * @param btn     注入的 ★，可为 null（开关关掉 / 还没注入）
     */
    private static void ensureSyncToEditButton(final ViewGroup container, final View editBtn,
            final View btn) {
        if (btn == null || editBtn == null) {
            return;
        }
        final ViewTreeObserver vto;
        try {
            vto = container.getViewTreeObserver();
        } catch (Throwable t) {
            return;
        }
        if (vto == null || !vto.isAlive()) {
            return;
        }
        ViewTreeObserver hooked = sSyncedVto.get(btn);
        if (hooked == vto && sSyncedAnchor.get(btn) == editBtn) {
            return;      // 还挂在同一棵树上、照的还是同一颗 ✎
        }
        try {
            vto.addOnPreDrawListener(buildSyncListener(btn, editBtn));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": attach sync listener failed - " + t);
            return;
        }
        sSyncedVto.put(btn, vto);
        sSyncedAnchor.put(btn, editBtn);
        sSyncAttach++;
        XposedBridge.log(TAG + ": sync listener attached (#" + sSyncAttach
                + (hooked != null && !hooked.isAlive() ? ", old vto dead" : ", vto changed") + ")");
    }

    /**
     * 让注入的按钮跟随 ✎ 的可见性 / 透明度 / 位移 / 缩放。
     *
     * <p>控制中心的展开与收起是 ColorOS 的 spring transition 逐帧动画，它只把
     * {@code editBtn / settingsButton / moreBtn / view} 绑成动画目标（见组件里的
     * {@code bindTargetProperties(Set)}）。注入的按钮不在这个集合里，所以面板收起时
     * 它不会跟着淡出/滑走，会留在屏幕上。</p>
     *
     * <p>这里挂一个 {@code OnPreDrawListener}，在每帧绘制前把 ✎ 的这几个属性抄过来。
     * 只在值不同时才写入，避免每帧触发无谓的 invalidate。</p>
     *
     * <p><b>透明度两个字段都要抄</b>：ColorOS 的 {@code updateState()} 走的是隐藏 API
     * {@code setTransitionAlpha(float)}（改 {@code View.mTransitionAlpha}），而普通属性动画 /
     * {@code setAlpha} 改的是另一个字段 {@code mAlpha}，公开的 {@code getAlpha()} 只读得到后者。
     * 两者相互独立、最终相乘，只抄一个的话，碰上另一条路径 ★ 就完全不淡出。</p>
     *
     * <p>监听本身是可重挂的（见 {@link #ensureSyncToEditButton}），所以这里只负责
     * 「抄一次属性」这件事，不关心挂在哪、挂了多久。</p>
     */
    private static ViewTreeObserver.OnPreDrawListener buildSyncListener(final View btn,
            final View editBtn) {
        return new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    if (btn.getParent() == null) {
                        return true;
                    }
                    if (btn.getVisibility() != editBtn.getVisibility()) {
                        btn.setVisibility(editBtn.getVisibility());
                    }
                    // 透明度有两个独立字段，最终是两者相乘，所以两个都要抄：
                    // transitionAlpha 是 ColorOS 收起面板时走的过渡动画（隐藏 API），
                    // alpha 是普通属性动画 / setAlpha 走的那一个。只抄 transitionAlpha
                    // 的话，碰上走 mAlpha 的那条路径，★ 就完全不淡出、等面板收完
                    // 被一起摘掉才「啪」地消失。
                    float targetTransition = readTransitionAlpha(editBtn);
                    if (readTransitionAlpha(btn) != targetTransition) {
                        writeTransitionAlpha(btn, targetTransition);
                    }
                    float targetAlpha = editBtn.getAlpha();
                    if (btn.getAlpha() != targetAlpha) {
                        btn.setAlpha(targetAlpha);
                    }
                    if (btn.getTranslationX() != editBtn.getTranslationX()) {
                        btn.setTranslationX(editBtn.getTranslationX());
                    }
                    if (btn.getTranslationY() != editBtn.getTranslationY()) {
                        btn.setTranslationY(editBtn.getTranslationY());
                    }
                    if (btn.getScaleX() != editBtn.getScaleX()) {
                        btn.setScaleX(editBtn.getScaleX());
                    }
                    if (btn.getScaleY() != editBtn.getScaleY()) {
                        btn.setScaleY(editBtn.getScaleY());
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
        };
    }

    /** 读过渡透明度（隐藏 API），拿不到时回落到公开的 {@code getAlpha()}。 */
    private static float readTransitionAlpha(View v) {
        try {
            Object r = XposedHelpers.callMethod(v, "getTransitionAlpha");
            if (r instanceof Float) {
                return ((Float) r).floatValue();
            }
        } catch (Throwable ignored) {
        }
        return v.getAlpha();
    }

    /** 写过渡透明度（隐藏 API），失败时回落到公开的 {@code setAlpha(float)}。 */
    private static void writeTransitionAlpha(View v, float alpha) {
        try {
            XposedHelpers.callMethod(v, "setTransitionAlpha", Float.valueOf(alpha));
        } catch (Throwable ignored) {
            v.setAlpha(alpha);
        }
    }

    /** 优先用组件里的 editBtn 字段；拿不到就从容器直接子 View 里找第一个 ImageButton(✎)。 */
    private static View resolveEditButton(ViewGroup container) {
        View editBtn = readViewField(sComponent, FIELD_EDIT_BTN);
        if (editBtn != null) {
            return editBtn;
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof ImageButton) {
                return child;
            }
        }
        return null;
    }

    /**
     * 复制 ✎ 按钮的背景，保证圆形底与旁边两个按钮完全一致。
     *
     * <p>注意不能用 {@code mutate()}：背景是 ✎ 与 ⚙/⋮ 共用的 Drawable，
     * 一旦 mutate 会连带改变原按钮。优先用 ConstantState 复制一份独立实例，
     * 拿不到 ConstantState 时就直接共用原 Drawable（只读使用，无副作用）。</p>
     */
    private static Drawable copyBackground(View source) {
        Drawable bg = source.getBackground();
        if (bg == null) {
            return null;
        }
        Drawable.ConstantState cs = bg.getConstantState();
        if (cs == null) {
            return bg;
        }
        Drawable copy = cs.newDrawable(source.getResources());
        return copy != null ? copy : bg;
    }

    private static int measure(View editBtn, Context ctx) {
        ViewGroup.LayoutParams src = editBtn.getLayoutParams();
        int size = src != null ? src.width : 0;
        if (size <= 0) {
            size = (int) (40 * ctx.getResources().getDisplayMetrics().density + 0.5f);
        }
        return size;
    }

    /**
     * 构造与 editBtn 同类型的 LayoutParams（ConstraintLayout.LayoutParams），
     * 并把 end 约束到 editBtn 的 start，top/bottom 对齐父容器。
     */
    private static ViewGroup.LayoutParams buildLayoutParams(View editBtn, int size) {
        ViewGroup.LayoutParams src = editBtn.getLayoutParams();
        ViewGroup.LayoutParams lp = null;
        if (src != null) {
            try {
                lp = (ViewGroup.LayoutParams) src.getClass()
                        .getConstructor(int.class, int.class)
                        .newInstance(Integer.valueOf(size), Integer.valueOf(size));
            } catch (Throwable ignored) {
                lp = null;
            }
        }
        if (lp == null) {
            XposedBridge.log(TAG + ": fallback to plain LayoutParams (src="
                    + (src == null ? "null" : src.getClass().getName()) + ")");
            return new ViewGroup.LayoutParams(size, size);
        }
        try {
            XposedHelpers.setIntField(lp, "endToStart", editBtn.getId());
            XposedHelpers.setIntField(lp, "startToStart", UNSET);
            XposedHelpers.setIntField(lp, "startToEnd", UNSET);
            XposedHelpers.setIntField(lp, "endToEnd", UNSET);
            XposedHelpers.setIntField(lp, "topToTop", PARENT_ID);
            XposedHelpers.setIntField(lp, "bottomToBottom", PARENT_ID);
            XposedHelpers.setIntField(lp, "topToBottom", UNSET);
            XposedHelpers.setIntField(lp, "bottomToTop", UNSET);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": set constraint failed - " + t);
        }
        return lp;
    }
}
