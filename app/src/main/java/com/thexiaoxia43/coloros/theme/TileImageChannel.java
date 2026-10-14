package com.thexiaoxia43.coloros.theme;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Base64;

import de.robv.android.xposed.XposedBridge;

/**
 * 底图图片从「模块 App」到「SystemUI」的通道。
 *
 * <p>为什么不用 XSharedPreferences：实测 SystemUI 侧读不到模块 App 的 prefs
 * （LSPosed 的 {@code xposedsharedprefs} 在这台机器上不通），所以图片改成
 * 由模块设置页发一条带 JPEG 字节的广播，SystemUI 进程里动态注册的接收器收到后
 * 写进 <b>SystemUI 自己的</b> prefs（{@link TileLongPressConfig}）。
 * 同进程读写，没有权限问题。</p>
 *
 * <p>接收器在 SystemUI 启动时注册（{@link TileBackgroundHook} 触发），
 * 另外在每次套用样式时再兜底注册一次，避免启动早期注册失败后一直收不到。</p>
 */
public final class TileImageChannel {

    private static final String TAG = "SystemUIHook/Image";

    private static volatile boolean sRegistered;

    /** 接收器是否已注册（样式面板显示，用来判断底图广播能不能收到）。 */
    public static boolean isRegistered() {
        return sRegistered;
    }

    private TileImageChannel() {
    }

    /** 注册广播接收器（幂等）。 */
    public static void ensureReceiver(final Context context) {
        if (sRegistered || context == null) {
            return;
        }
        synchronized (TileImageChannel.class) {
            if (sRegistered) {
                return;
            }
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            try {
                final Context appContext = app;
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handle(appContext, intent);
                    }
                };
                IntentFilter filter = new IntentFilter(PrefKeys.ACTION_SHAPE_BG_IMAGE);
                // 同一个接收器顺带当「模块在不在」的探针，见 handle()
                filter.addAction(PrefKeys.ACTION_PING);
                // 设置页那颗「强制重启系统界面」也走这条通道，见 handle()
                filter.addAction(PrefKeys.ACTION_RESTART_SYSTEMUI);
                // 「重置所有配置」和「★ 按钮可见性变了」同样借这条通道
                filter.addAction(PrefKeys.ACTION_RESET_CONFIG);
                filter.addAction(PrefKeys.ACTION_ENTRANCE_CHANGED);
                // 主题包导入时那条「圆角按素材比例算」也走这里，见 handle()
                filter.addAction(PrefKeys.ACTION_SHAPE_CORNER);
                // 「导出主题包」：设置页选好保存位置后请本进程打包写入，见 handle()
                filter.addAction(PrefKeys.ACTION_THEME_EXPORT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ 动态注册必须显式声明是否允许外部 App 发送
                    app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    app.registerReceiver(receiver, filter);
                }
                sRegistered = true;
                // 设备卡片那几条着色落在静态方法上，拿不到 View、也就拿不到 Context，
                // 这里顺手把 Application 递过去存着，见 TileColorHook#setAppContext
                TileColorHook.setAppContext(app);
                XposedBridge.log(TAG + ": receiver registered");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": register failed - " + t);
            }
        }
    }

    /**
     * 在本进程（SystemUI）里直接清掉某个形状的底图。
     *
     * <p>「清除」不需要相册的选图结果，所以完全不用把模块 App 拉到前台走一趟广播：
     * 样式面板就在 SystemUI 进程里，直接写 prefs 再重启系统界面让视图重建即可。</p>
     *
     * @return 是否写入成功
     */
    public static boolean clearBgImage(final Context context, final String shape,
            final boolean active) {
        if (context == null || shape == null || shape.length() == 0) {
            return false;
        }
        boolean ok = false;
        try {
            ok = TileLongPressConfig.setBgImage(context, shape, active, "");
            XposedBridge.log(TAG + ": cleared in-process " + shape
                    + "(" + (active ? "on" : "off") + ") ok=" + ok);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": clear in-process failed - " + t);
        }
        // 已画出的视图身上还挂着旧底图，刷新链路不一定能把它摘干净，
        // 重启系统界面让所有视图带着「没有底图」重建最省事。重启不放在 try 里：
        // 上面万一出错，界面也一样得重建，否则清了一半的样式会留在屏幕上
        TileStylePopup.restartSystemUi(context);
        return ok;
    }

    private static void handle(final Context context, final Intent intent) {
        if (intent == null) {
            return;
        }
        // 「在不在」探测：能收到这条广播就说明模块确实注入进了 SystemUI，
        // 回一条 PONG 让设置页把主页那颗状态点翻成绿色
        if (PrefKeys.ACTION_PING.equals(intent.getAction())) {
            try {
                Intent pong = new Intent(PrefKeys.ACTION_PONG);
                pong.setPackage(PrefKeys.MODULE_PACKAGE);
                context.sendBroadcast(pong);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": pong failed - " + t);
            }
            return;
        }
        // 设置页那颗「强制重启系统界面」：必须在本进程执行，杀进程才杀得对
        if (PrefKeys.ACTION_RESTART_SYSTEMUI.equals(intent.getAction())) {
            XposedBridge.log(TAG + ": restart requested by settings app");
            TileStylePopup.restartSystemUi(context);
            return;
        }
        // ★ 按钮的可见性刚被改过：把新值写进本进程的 prefs，再立刻重评一次，
        // 别等下次控制中心排列变化。值只能靠广播带过来——SystemUI 读不到模块
        // App 的 prefs（跨进程 XSharedPreferences 在这台机器上不工作）
        if (PrefKeys.ACTION_ENTRANCE_CHANGED.equals(intent.getAction())) {
            boolean visible = intent.getBooleanExtra(PrefKeys.EXTRA_ENTRANCE_VISIBLE,
                    PrefKeys.DEF_SHOW_ENTRANCE_BUTTON);
            TileLongPressConfig.setEntranceVisible(context, visible);
            XposedBridge.log(TAG + ": entrance visibility = " + visible);
            QuickEntranceButtonHook.refresh();
            return;
        }
        // 「重置所有配置」：真正的配置在 SystemUI 自己的 prefs 里，只能本进程清
        if (PrefKeys.ACTION_RESET_CONFIG.equals(intent.getAction())) {
            HookPrefs.reloadNow();
            resetAll(context);
            return;
        }
        // 主题包导入时跟着底图一起来的那条：把「素材圆角 ÷ 素材宽」记下来，
        // 之后 outline 与底图都按「比例 × 实际宽度」算圆角
        if (PrefKeys.ACTION_SHAPE_CORNER.equals(intent.getAction())) {
            String shape = intent.getStringExtra(PrefKeys.EXTRA_BG_SHAPE);
            if (shape == null || shape.length() == 0) {
                return;
            }
            float ratio = intent.getFloatExtra(PrefKeys.EXTRA_CORNER_RATIO, 0f);
            TileLongPressConfig.setCornerRatio(context, shape, ratio);
            int count = refreshTarget(context, shape);
            XposedBridge.log(TAG + ": corner ratio " + shape + " = " + ratio
                    + " refreshed=" + count);
            return;
        }
        // 「导出主题包」：把本进程里的底图与颜色打包，写到设置页选好的那个地址
        if (PrefKeys.ACTION_THEME_EXPORT.equals(intent.getAction())) {
            handleThemeExport(context, intent);
            return;
        }
        try {
            String shape = intent.getStringExtra(PrefKeys.EXTRA_BG_SHAPE);
            byte[] raw = intent.getByteArrayExtra(PrefKeys.EXTRA_BG_BYTES);
            boolean hasImage = raw != null && raw.length > 0
                    && shape != null && shape.length() > 0;
            if (!hasImage) {
                // 没有底图，只带了整份配置快照：这是「只改过可见度 / 颜色 / 各式开关、
                // 没换过任何底图」的包——导出时包里就只有 extras 表。这类包同样要能导入，
                // 所以这里不套底图，直接把快照铺回去（界面刷新交给收尾那次重启）。
                int only = applyExtrasSnapshot(context, intent);
                XposedBridge.log(TAG + ": no image" + (shape == null ? "" : " for " + shape)
                        + ", extras-applied=" + only);
                return;
            }
            // 磁贴底图分「开 / 关」两套；控制中心背景没有状态之分，这里统一走 bgImage 分流
            boolean active = PrefKeys.STATE_ON.equals(intent.getStringExtra(PrefKeys.EXTRA_BG_STATE));
            String state = active ? "on" : "off";
            // 主题包导入一次要写九个形状，逐条重启的话第一条之后进程就没了，
            // 所以那批广播带着静默标记来：这里只写盘 + 刷新，重启留给收尾那条广播
            boolean silent = intent.getBooleanExtra(PrefKeys.EXTRA_BG_SILENT, false);
            if (!silent) {
                // 手动换图（相册选的、单张）时把主题包留下的素材圆角比例清掉：
                // 比例是照上一张素材量的，图都换了还留着会把新图裁成旧主题的圆角。
                // 主题包导入是静默批量，比例由紧跟着的那条 ACTION_SHAPE_CORNER 送，
                // 所以这里不能连批量一起清（否则就依赖两条广播的先后了）
                TileLongPressConfig.clearCornerRatio(context, shape);
            }
            String base64 = Base64.encodeToString(raw, Base64.NO_WRAP);
            boolean ok = TileLongPressConfig.setBgImage(context, shape, active, base64);
            // 模块自有的整份配置快照（导出时随包带出）：先把这些「非底图」的修改项
            // 铺回去，再让下面那几条搭车的颜色覆盖它——顺序反了的话快照里的旧颜色
            // 会把这一趟明确带来的颜色抹掉
            int extras = applyExtrasSnapshot(context, intent);
            // 主题包颜色表里的图标底色搭这趟车来（见 PrefKeys#EXTRA_ICON_COLOR）：
            // 只有 2×1 与三阶段两个形状会带，别的形状没有这一项
            int iconColor = applyIconColor(context, intent, shape);
            // 主题包里的「图标着色（激活 / 关）」与「文字颜色」也搭同一趟车，见 applyTintColors
            applyTintColors(context, intent, shape);
            // 控制中心背景的纯色（主题包里是「通知材质背景色」）同样搭这趟车，见 applyQsBgColor
            applyQsBgColor(context, intent);
            // 回读自检：确认真的写进了 SystemUI 自己的 prefs
            String back = TileLongPressConfig.bgImage(context, shape, active);
            int count = refreshTarget(context, shape);
            XposedBridge.log(TAG + ": saved " + shape + "(" + state + ") "
                    + (raw.length / 1024) + "KB"
                    + " ok=" + ok
                    + " extras=" + extras
                    + " iconColor=" + (iconColor == 0 ? "-" : "#" + Integer.toHexString(iconColor))
                    + " readBack=" + (back.length() == base64.length() ? "ok" : back.length())
                    + " refreshed=" + count
                    + " silent=" + silent);
            // 单张换图（相册选的）到这里就重启一次系统界面，让所有视图带着新底图重建。
            // 主题包导入是静默批量，重启由收尾那条广播统一做，见 EXTRA_BG_SILENT
            if (ok && !silent) {
                TileStylePopup.restartSystemUi(context);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": handle failed - " + t);
        }
    }

    /**
     * 底图广播里若带着<b>模块自有配置的整份快照</b>，就把这些「非底图」的修改项铺回去。
     *
     * <p>快照由 {@link HookConfigExtras#build} 在导出时生成（{@code ACTION_THEME_EXPORT}），
     * 导入时由设置页挂在第一条真正发出的底图广播上带过来（见
     * {@link PrefKeys#EXTRA_EXTRAS_XML}），所以这里只要把里面的键值整份倒回本进程的
     * prefs 即可——「所有修改项」就都还原了。</p>
     *
     * <p>调用时机放在底图写入之后、搭车的颜色之前：先让快照铺底，再让这一趟明确
     * 带来的颜色覆盖它，避免快照里的旧颜色把新颜色抹掉。</p>
     *
     * @return 实际还原了几条；没带快照时返回 0
     */
    private static int applyExtrasSnapshot(final Context context, final Intent intent) {
        String xml = intent.getStringExtra(PrefKeys.EXTRA_EXTRAS_XML);
        if (xml == null || xml.length() == 0) {
            return 0;
        }
        int count = HookConfigExtras.apply(context, xml);
        XposedBridge.log(TAG + ": extras snapshot applied " + count + " items");
        return count;
    }

    /**
     * 底图广播里若带着图标底色，就写进该形状的样式。
     *
     * <p>底色在模块侧是「纯色值 + 不透明度」两段存的（见
     * {@code TileLongPressConfig.Style#iconArgb}），所以这里把 alpha 拆出来单独写。
     * 写完顺手让 {@link IconColorHook} 把已经画出来的色块也重涂一遍，
     * 不必等收尾那次重启。</p>
     *
     * @return 写进去的 ARGB；这一趟没带 / 该形状没有这一项时返回 0
     */
    private static int applyIconColor(Context context, Intent intent, String shape) {
        if (!intent.hasExtra(PrefKeys.EXTRA_ICON_COLOR)
                || !TileLongPressConfig.supportsIconColor(shape)) {
            return 0;
        }
        int argb = intent.getIntExtra(PrefKeys.EXTRA_ICON_COLOR, 0);
        if (argb == 0) {
            return 0;
        }
        int alphaPct = Math.round((argb >>> 24) * 100f / 255f);
        TileLongPressConfig.setIconColor(context, shape, argb & 0x00FFFFFF);
        TileLongPressConfig.setIconAlpha(context, shape, alphaPct);
        int count = IconColorHook.refresh(shape);
        XposedBridge.log(TAG + ": icon color " + shape + " = #" + Integer.toHexString(argb)
                + " alpha=" + alphaPct + "% refreshed=" + count);
        return argb;
    }

    /**
     * 底图广播里若带着「图标着色（激活 / 关）」或「文字颜色」，就写进该形状的样式。
     *
     * <p>这三项跟图标底色一样是「纯色值 + 不透明度」两段存的，所以都把 alpha 拆出来
     * 单独写。写完让 {@link TileColorHook} 把已经画出来的磁贴重涂一遍，不必等收尾那次重启。</p>
     *
     * @return 这一趟写进去几项
     */
    private static int applyTintColors(Context context, Intent intent, String shape) {
        if (!TileLongPressConfig.supportsTint(shape)) {
            return 0;
        }
        int written = 0;
        written += applyTint(context, intent, shape, PrefKeys.EXTRA_ICON_TINT_ON,
                TileLongPressConfig.TINT_ICON_ON);
        written += applyTint(context, intent, shape, PrefKeys.EXTRA_ICON_TINT_OFF,
                TileLongPressConfig.TINT_ICON_OFF);
        written += applyTint(context, intent, shape, PrefKeys.EXTRA_LABEL_COLOR,
                TileLongPressConfig.TINT_LABEL);
        if (written > 0) {
            TileColorHook.refresh(shape);
        }
        return written;
    }

    /**
     * 控制中心背景的纯色：广播里带着才写。
     *
     * <p>它是整个背景一份、不分形状的，所以每趟底图广播都带着同一个值（见
     * {@link PrefKeys#EXTRA_QS_BG_COLOR}）。写的是同一个值，重复落盘没有副作用。</p>
     *
     * <p>写完顺手刷一次背景面板：导入收尾那条广播会重启 SystemUI，那之前能先看到
     * 颜色已经生效，出问题也容易判断卡在哪一环。</p>
     */
    private static void applyQsBgColor(final Context context, final Intent intent) {
        if (!intent.hasExtra(PrefKeys.EXTRA_QS_BG_COLOR)) {
            return;
        }
        int argb = intent.getIntExtra(PrefKeys.EXTRA_QS_BG_COLOR, 0);
        if (argb == 0) {
            return;
        }
        TileLongPressConfig.setQsBackgroundColor(context, argb);
        QsBackgroundHook.refresh();
        XposedBridge.log(TAG + ": qs background color = #" + Integer.toHexString(argb));
    }

    /** 写一项着色：广播里带着才写。 */
    private static int applyTint(Context context, Intent intent, String shape, String extra,
            int kind) {
        if (!intent.hasExtra(extra)) {
            return 0;
        }
        int argb = intent.getIntExtra(extra, 0);
        if (argb == 0) {
            return 0;
        }
        int alphaPct = Math.round((argb >>> 24) * 100f / 255f);
        TileLongPressConfig.setTintColor(context, shape, kind, argb & 0x00FFFFFF);
        TileLongPressConfig.setTintAlpha(context, shape, kind, alphaPct);
        XposedBridge.log(TAG + ": " + TileLongPressConfig.tintLabel(kind) + " " + shape
                + " = #" + Integer.toHexString(argb) + " alpha=" + alphaPct + "%");
        return 1;
    }

    /**
     * 导出主题包：把本进程里的底图与颜色打包，写到设置页选好的那个地址。
     *
     * <p>为什么要让 SystemUI 自己打包：底图（Base64）、圆角比例、各种颜色都存在
     * <b>SystemUI 自己的</b> prefs 里，模块 App 读不到；反过来把图片经 Binder 送过去
     * 又会顶爆事务上限。所以设置页只负责拿 {@code ACTION_CREATE_DOCUMENT} 的结果，
     * 转授写权限后把地址广播过来，这里读自己的 prefs 打包写盘。</p>
     *
     * <p>真正的打包逻辑在 {@link ThemePackExporter}，产出的包与「导入主题包」认的
     * 格式完全一致（{@code assets/colors.xml} + {@code res/drawable-xxhdpi/*.png}），
     * 导回去能原样套回来。</p>
     */
    private static void handleThemeExport(final Context context, final Intent intent) {
        String uriText = intent.getStringExtra(PrefKeys.EXTRA_THEME_EXPORT_URI);
        if (uriText == null || uriText.length() == 0) {
            XposedBridge.log(TAG + ": theme export without uri");
            return;
        }
        final android.net.Uri uri = android.net.Uri.parse(uriText);
        final Context appContext = context;
        // 打包 + 写盘放后台线程：可能有好几 MB 的 PNG 要编码
        new Thread(new Runnable() {
            @Override
            public void run() {
                ThemePackExporter.Result result = ThemePackExporter.export(appContext, uri);
                XposedBridge.log(TAG + ": theme export " + (result.ok ? "ok" : "failed")
                        + " images=" + result.imageCount + " colors=" + result.colorCount
                        + " extras=" + result.extrasCount
                        + " bytes=" + result.byteCount
                        + (result.message == null ? "" : " msg=" + result.message));
                showExportResult(appContext, result);
            }
        }, "theme-export").start();
    }

    /** 导出收尾：在 SystemUI 进程里弹一条 Toast（同进程就能拿到成败）。 */
    private static void showExportResult(final Context context,
            final ThemePackExporter.Result result) {
        final String text = result.ok
                ? ("已导出 " + result.imageCount + " 张底图、" + result.colorCount + " 项颜色、"
                        + result.extrasCount + " 条配置")
                : ("导出失败：" + (result.message == null ? "未知原因" : result.message));
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final Context toastContext = context;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(toastContext, text,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": export toast failed - " + t);
        }
    }

    /**
     * 「重置所有配置」：清掉本进程里那份配置，然后重启系统界面。
     *
     * <p>已经画出来的视图不会因为 prefs 变空就自己还原——底图是视图创建时套上去的，
     * 圆角 / 可见度也是那一刻算的。逐个形状刷一遍既慢又容易残留，直接重启 SystemUI
     * 让所有视图重新创建最干净，所以这里清完就交给
     * {@link TileStylePopup#restartSystemUi}（它会先把 prefs 落盘）。</p>
     */
    private static void resetAll(Context context) {
        try {
            TileLongPressConfig.clearAll(context);
            // 每磁贴配置是模块 App 写的，本进程有缓存，一并失效
            TileConfigs.invalidate();
            XposedBridge.log(TAG + ": reset done, restarting SystemUI");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": reset failed - " + t);
        }
        // 重启不放在 try 里：上面万一出错，界面也一样得重建，否则清了一半的样式会留在屏幕上
        TileStylePopup.restartSystemUi(context);
    }

    /**
     * 底图落盘后的刷新。
     *
     * <p>控制中心背景图与胶囊进度图借用同一条图片通道，只是标识不同：前者要刷新的是
     * 面板背景，后者要刷新的是那两根胶囊。</p>
     */
    private static int refreshTarget(Context context, String shape) {
        if (PrefKeys.SHAPE_QS_BACKGROUND.equals(shape)) {
            return QsBackgroundHook.refresh();
        }
        if (PrefKeys.SHAPE_SLIDER_PROGRESS.equals(shape)) {
            return SliderHook.refresh();
        }
        return TileLongPressInterceptor.refreshShape(shape);
    }
}
