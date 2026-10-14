package com.thexiaoxia43.coloros.theme;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 模块设置页（精简版）。
 *
 * <p>原来的控制中心整页预览已经删除；现在这里只保留 hook 真正用到的全局参数。</p>
 *
 * <p>它同时承担一个特殊入口：控制中心里长按磁贴弹出样式面板后点「修改底图」，
 * SystemUI（不是 Activity，拿不到选图结果）会带着 {@link PrefKeys#EXTRA_PICK_SHAPE}
 * 把本页拉起来。这时不显示设置表单，直接引导到系统相册选图，把图片缩放压缩成
 * Base64 写进模块 prefs，SystemUI 侧读出来当磁贴底图。</p>
 */
public class SettingsActivity extends Activity {

    /** Android 13+ 的系统相册选图 action。 */
    private static final String ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES";
    private static final int REQ_PICK_IMAGE = 1001;
    /** 设置页里「导入主题包」选压缩包的请求码。 */
    private static final int REQ_PICK_ZIP = 1002;
    /** ★ 弹窗里「导出主题包」新建保存位置的请求码。 */
    private static final int REQ_CREATE_ZIP = 1003;
    /** 底图最长边（像素）。磁贴最大也就 2×1/2×2，512 足够且 Base64 不会太大。 */
    private static final int MAX_IMAGE_SIDE = 512;
    private static final int JPEG_QUALITY = 80;
    /**
     * 控制中心背景图最长边。背景要铺满整屏，512 会糊，
     * 但 Base64 存在 prefs 里也不能太大，1440 是清晰度与体积的折中。
     */
    private static final int MAX_BG_IMAGE_SIDE = 1440;
    private static final int BG_JPEG_QUALITY = 80;
    /**
     * 主题包底图的最长边。
     *
     * <p>比相册那条链路的 {@link #MAX_IMAGE_SIDE} 宽：主题包里的素材最大 1020×280，
     * 按 512 会被降采样成 510×140，糊得看得出来。用 1440 等于原样保留
     * （2 的幂降采样下 sample 还是 1），而素材本身只有几十 KB，编码成 Base64
     * 存进 prefs 没压力。</p>
     */
    private static final int MAX_THEME_IMAGE_SIDE = 1440;

    private android.content.SharedPreferences prefs;
    /** 选图流程中要写入的形状标识。 */
    private String pickShape;
    /** 选图流程中要写入的状态：true = 磁贴「开」那套，false = 「关」那套。 */
    private boolean pickActive;
    /**
     * 本次是不是被控制中心那边的透明入口（★ 弹窗的「导入主题包」、通知卡片的
     * 「导入字体」）通过透明别名拉起来的。
     *
     * <p>是的话，流程走完（或用户取消）就把本页收掉——留着的话后台会多一个
     * 用户看不见的空白 Activity。从设置页正常点进来的那条链路不受影响。</p>
     */
    private boolean transparentEntry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PrefKeys.PREFS_NAME, MODE_PRIVATE);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        // 亮色模式下状态栏图标要翻成深色，否则白图标压在浅色底上完全看不见
        try {
            int vis = getWindow().getDecorView().getSystemUiVisibility();
            if (UiTheme.isNight(this)) {
                vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(vis);
        } catch (Throwable ignored) {
        }

        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(PrefKeys.EXTRA_PICK_THEME, false)) {
            handleThemeImportRequest();
            return;
        }
        if (intent != null && intent.getBooleanExtra(PrefKeys.EXTRA_PICK_THEME_EXPORT, false)) {
            handleThemeExportRequest();
            return;
        }
        String requestedShape = intent == null ? null
                : intent.getStringExtra(PrefKeys.EXTRA_PICK_SHAPE);
        if (requestedShape != null && requestedShape.length() > 0) {
            handlePickRequest(requestedShape, requestedState());
            return;
        }

        buildSettingsUi();
        // SystemUI 那份「★ 按钮可见性」是本模块唯一的配置来源（读不到这边的 prefs），
        // 打开设置页时同步一次，保证开关显示的状态和控制中心实际状态一致
        syncEntranceVisibility();
    }

    // ------------------------------------------- 来自控制中心长按面板的改底图请求

    /**
     * 控制中心长按面板点「从相册选择底图」后走到这里。
     *
     * <p>这条链路是<b>全透明</b>的：控制中心那边启动的是
     * {@link PrefKeys#ACTIVITY_PICK_CLASS} 这个 activity-alias（清单里套了
     * {@code Theme.Translucent.NoTitleBar}），本页也不再设任何内容视图，
     * 所以用户看到的只是「相册被打开了」，不会看到模块 App 的界面从后台弹出来。
     * 结果一律用 Toast 提示，不铺满屏。</p>
     */
    private void handlePickRequest(String shape, boolean active) {
        pickShape = shape;
        pickActive = active;
        // 透明兜底：万一别名上的主题没生效（被 ROM 覆盖等），也不让窗口挡住相册
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        launchGallery();
    }

    /**
     * ★ 弹窗那颗「导入主题包」走到这里（同样走 {@link PrefKeys#ACTIVITY_PICK_CLASS}）。
     *
     * <p>和选底图一样是全透明的：用户只该看见文件选择器，不该看见模块 App 的界面被
     * 拉到前台。选完 zip 后的确认框与结果提示都在这条链路里走完，然后本页自动结束。</p>
     */
    private void handleThemeImportRequest() {
        transparentEntry = true;
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        launchZipPicker();
    }

    /**
     * ★ 弹窗那颗「导出主题包」走到这里（同样走 {@link PrefKeys#ACTIVITY_PICK_CLASS}）。
     *
     * <p>同样全透明：用户只该看见「保存到哪里」的系统界面，不该看见模块 App 的界面。
     * 选好地址后由 SystemUI 那边（配置都在它的 prefs 里）打包写入，本页随即收掉。</p>
     */
    private void handleThemeExportRequest() {
        transparentEntry = true;
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        } catch (Throwable ignored) {
        }
        launchZipCreator();
    }

    /** 透明入口（★ / 通知卡弹窗拉起的导入）走完流程后把本页收掉，别在后台留一个空白窗口。 */
    private void finishIfTransparent() {
        if (transparentEntry) {
            finish();
        }
    }

    /**
     * 把选中的图片交给 SystemUI。
     *
     * <p>走广播而不是写 prefs：SystemUI 用 {@code XSharedPreferences} 读不到模块
     * 自己的 prefs，广播则由 SystemUI 进程内注册的
     * {@code TileImageChannel} 收到后写进它自己的 prefs。</p>
     *
     * <p>「清除底图」不在这里——那个不需要选图结果，SystemUI 侧直接在本进程改
     * prefs 就行，用不着把模块 App 拉起来。</p>
     */
    private boolean sendToSystemUI(String shape, boolean active, byte[] raw) {
        return sendToSystemUI(shape, active, raw, false, 0, 0, 0, 0, 0);
    }

    /**
     * 把一张底图交给 SystemUI。
     *
     * @param silent 批量导入时置 true：SystemUI 那边只写盘不重启（主题包导入一次要写
     *               九个形状，逐条重启第一条之后就没了），九条都发完后由调用方统一重启一次。
     * @param iconArgb 主题包颜色表里的图标底色；0 表示这一趟不带
     *                 （只有 2×1 与三阶段会带，见 {@link PrefKeys#EXTRA_ICON_COLOR}）
     * @param tintOnArgb  图标着色（激活）；0 表示这一趟不带
     * @param tintOffArgb 图标着色（关）；0 表示这一趟不带
     * @param labelArgb   文字颜色；0 表示这一趟不带
     * @param qsBgArgb    控制中心背景的纯色；0 表示这一趟不带。它不分形状（整个背景一份），
     *                    所以每趟都带同一个值，见 {@link PrefKeys#EXTRA_QS_BG_COLOR}
     */
    private boolean sendToSystemUI(String shape, boolean active, byte[] raw, boolean silent,
            int iconArgb, int tintOnArgb, int tintOffArgb, int labelArgb, int qsBgArgb) {
        return sendToSystemUI(shape, active, raw, silent, iconArgb, tintOnArgb, tintOffArgb,
                labelArgb, qsBgArgb, null);
    }

    /**
     * 同上，另外带上<b>模块自有配置的整份快照</b>（{@code assets/systemui_hook_extras.xml}）。
     *
     * <p>主题包标准格式只装得下 5 条颜色和一批底图，装不下模块的全部修改项；导出时
     * 另写一张 extras 表把这些项整份记下来（见 {@link HookConfigExtras}）。导入时把它
     * 挂在<b>第一条真正发出的</b>底图广播上带过去即可——SystemUI 侧收到后一次性倒回
     * prefs，所有修改项就都还原了（见 {@code TileImageChannel#applyExtrasSnapshot}）。</p>
     *
     * <p>只带一趟：整份快照是幂等的，重复送没有意义，还白白撑大每一包广播。</p>
     *
     * @param extrasXml 快照 XML；null / 空表示这一趟不带
     */
    private boolean sendToSystemUI(String shape, boolean active, byte[] raw, boolean silent,
            int iconArgb, int tintOnArgb, int tintOffArgb, int labelArgb, int qsBgArgb,
            String extrasXml) {
        try {
            Intent intent = new Intent(PrefKeys.ACTION_SHAPE_BG_IMAGE);
            intent.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            intent.putExtra(PrefKeys.EXTRA_BG_SHAPE, shape);
            // 磁贴底图分「开 / 关」两套，SystemUI 侧按这个决定写哪个键
            intent.putExtra(PrefKeys.EXTRA_BG_STATE,
                    active ? PrefKeys.STATE_ON : PrefKeys.STATE_OFF);
            intent.putExtra(PrefKeys.EXTRA_BG_BYTES, raw);
            if (silent) {
                intent.putExtra(PrefKeys.EXTRA_BG_SILENT, true);
            }
            if (iconArgb != 0) {
                // 主题包颜色表里的图标底色（只有 2×1 与三阶段带）。不单开一条广播：
                // 这条通道本来就在用，搭车来最稳，见 PrefKeys#EXTRA_ICON_COLOR
                intent.putExtra(PrefKeys.EXTRA_ICON_COLOR, iconArgb);
            }
            // 主题包里的「图标着色 / 文字颜色」也搭这一趟车，同样不新开广播
            if (tintOnArgb != 0) {
                intent.putExtra(PrefKeys.EXTRA_ICON_TINT_ON, tintOnArgb);
            }
            if (tintOffArgb != 0) {
                intent.putExtra(PrefKeys.EXTRA_ICON_TINT_OFF, tintOffArgb);
            }
            if (labelArgb != 0) {
                intent.putExtra(PrefKeys.EXTRA_LABEL_COLOR, labelArgb);
            }
            // 控制中心背景的纯色也搭这趟车（主题包里的「通知材质背景色」）
            if (qsBgArgb != 0) {
                intent.putExtra(PrefKeys.EXTRA_QS_BG_COLOR, qsBgArgb);
            }
            // 模块自有的整份配置快照（导出包里的 extras 表）：整份送一趟，SystemUI 侧
            // 一次性倒回 prefs，把所有非底图修改项都还原（圆角 / 可见度 / 各式开关…）
            if (extrasXml != null && extrasXml.length() > 0) {
                intent.putExtra(PrefKeys.EXTRA_EXTRAS_XML, extrasXml);
            }
            sendBroadcast(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 状态标识 → 显示名。 */
    private static String stateName(boolean active) {
        return active ? "开" : "关";
    }

    /**
     * 把「素材圆角 ÷ 素材宽」这个比例交给 SystemUI。
     *
     * <p>主题包自带的界面底图本身就画好了圆角，光把图送过去还不够：SystemUI 侧会按
     * 模块那套 20dp 去裁，素材自己的圆角就被切掉一条边。比例送过去之后，outline 与
     * 底图都改按「比例 × 实际宽度」算，圆角才跟主题设计的一致。</p>
     *
     * <p>只送比例不送像素：素材尺寸和磁贴尺寸不保证 1:1，只有比例是尺度无关的。</p>
     */
    private void sendCornerToSystemUI(String shape, float ratio) {
        if (ratio <= 0f) {
            return;
        }
        try {
            Intent intent = new Intent(PrefKeys.ACTION_SHAPE_CORNER);
            intent.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            intent.putExtra(PrefKeys.EXTRA_BG_SHAPE, shape);
            intent.putExtra(PrefKeys.EXTRA_CORNER_RATIO, ratio);
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 只送一份<b>整份配置快照</b>（不带任何底图）。
     *
     * <p>用在「导出包里只有 extras 表」的场合：用户只改过可见度 / 颜色 / 各式开关，
     * 没换过任何底图，包里自然一张图都没有。这种情况下没有底图广播可搭车，就得单独
     * 发一趟——仍然复用 {@link PrefKeys#ACTION_SHAPE_BG_IMAGE}（不新开 action），
     * 只是不带形状与图片字节，SystemUI 侧收到后只铺快照、不套图
     * （见 {@code TileImageChannel#handle} 里 hasImage 的短路分支）。</p>
     */
    private void sendExtrasToSystemUI(String extrasXml) {
        if (extrasXml == null || extrasXml.length() == 0) {
            return;
        }
        try {
            Intent intent = new Intent(PrefKeys.ACTION_SHAPE_BG_IMAGE);
            intent.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            intent.putExtra(PrefKeys.EXTRA_EXTRAS_XML, extrasXml);
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这个形状要不要带上主题包的图标底色。
     *
     * <p>只有 2×1 宽卡片与三阶段卡片有「图标底色」这一项（见
     * {@link PrefKeys#ICON_COLOR_SHAPES}），别的形状没有那块小色块。</p>
     */
    private static boolean carriesIconColor(String shape) {
        for (String s : PrefKeys.ICON_COLOR_SHAPES) {
            if (s.equals(shape)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个形状要不要带上主题包的「图标着色 / 文字颜色」三项。
     *
     * <p>见 {@link PrefKeys#TINT_SHAPES}：主控制中心磁贴（1×1 / 2×1 / 三阶段）
     * 与方形设备卡片。</p>
     */
    private static boolean carriesTint(String shape) {
        for (String s : PrefKeys.TINT_SHAPES) {
            if (s.equals(shape)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 依次尝试几个选图入口，第一个能起来的就用。
     *
     * <p>首选系统相册选图页（{@code ACTION_PICK_IMAGES}），它不需要读存储权限；
     * 有的 ROM 没有这个页面，再依次回落到相册自己的 {@code ACTION_PICK}、
     * {@code ACTION_GET_CONTENT} 与 {@code ACTION_OPEN_DOCUMENT}。
     * 全都不行才提示——只试一种的话，失败时用户只会看到「什么都没发生」。</p>
     */
    private void launchGallery() {
        if (tryPick(new Intent(ACTION_PICK_IMAGES))
                || tryPick(new Intent(Intent.ACTION_PICK))
                || tryPick(new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE))
                || tryPick(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE))) {
            return;
        }
        Toast.makeText(this, "没有可用的相册选图页面", Toast.LENGTH_LONG).show();
        finish();
    }

    /** 起一个选图页；起不来返回 false，交给下一个入口。 */
    private boolean tryPick(Intent intent) {
        try {
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_IMAGE);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP) {
            // 从设置页正常进来时失败也不 finish()，留在页面上；透明入口则要收掉
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importThemePack(data.getData());
            } else {
                Toast.makeText(this, "没有选择文件", Toast.LENGTH_SHORT).show();
                finishIfTransparent();
            }
            return;
        }
        if (requestCode == REQ_CREATE_ZIP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                requestThemeExport(data.getData());
            } else {
                finishIfTransparent();
            }
            return;
        }
        if (requestCode != REQ_PICK_IMAGE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            savePickedImage(data.getData());
        } else {
            Toast.makeText(this, "没有选择图片", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /**
     * 选图链路的结果提示：用 Toast 而不是整页文字。
     *
     * <p>本页在选图链路里是全透明的，铺一页黑底文字就等于把模块界面又弹到了
     * 用户眼前——这正是要避免的。</p>
     */
    private void pickResult(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        finish();
    }

    private void savePickedImage(Uri uri) {
        try {
            // 打开相册期间本页可能被系统回收重建，实例字段会丢，
            // 所以形状优先从 Intent 里取（Intent 在重建后依然保留）
            String shape = requestedShape();
            if (shape == null) {
                pickResult("形状信息丢失，请回控制中心重新长按");
                return;
            }
            boolean qsBackground = PrefKeys.SHAPE_QS_BACKGROUND.equals(shape);
            boolean active = requestedState();
            Bitmap bitmap = decodeScaled(uri,
                    qsBackground ? MAX_BG_IMAGE_SIDE : MAX_IMAGE_SIDE);
            if (bitmap == null) {
                pickResult("读取图片失败");
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG,
                    qsBackground ? BG_JPEG_QUALITY : JPEG_QUALITY, out);
            byte[] raw = out.toByteArray();
            boolean sent = sendToSystemUI(shape, active, raw);
            pickResult(sent
                    ? "已应用：" + (qsBackground
                            ? "控制中心背景"
                            : shape + " · 「" + stateName(active) + "」底图")
                            + " · " + (raw.length / 1024) + "KB"
                    : "发送失败：模块没生效或未重启 SystemUI");
        } catch (Throwable t) {
            pickResult("保存底图失败：" + t);
        }
    }

    /** 当前要设置底图的形状：实例字段优先，其次回落到启动 Intent。 */
    private String requestedShape() {
        if (pickShape != null && pickShape.length() > 0) {
            return pickShape;
        }
        Intent intent = getIntent();
        String shape = intent == null ? null
                : intent.getStringExtra(PrefKeys.EXTRA_PICK_SHAPE);
        if (shape != null && shape.length() > 0) {
            pickShape = shape;
            return shape;
        }
        return null;
    }

    /**
     * 当前要设置的是磁贴「开」还是「关」那套底图。
     *
     * <p>打开相册期间本页可能被回收重建，所以优先读启动 Intent（重建后仍在）。</p>
     */
    private boolean requestedState() {
        Intent intent = getIntent();
        String state = intent == null ? null
                : intent.getStringExtra(PrefKeys.EXTRA_PICK_STATE);
        if (state != null) {
            pickActive = PrefKeys.STATE_ON.equals(state);
        }
        return pickActive;
    }

    /** 读相册图片并按 2 的幂降采样到最长边不超过 {@code maxSide}。 */
    private Bitmap decodeScaled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
            sample <<= 1;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        in = getContentResolver().openInputStream(uri);
        try {
            return BitmapFactory.decodeStream(in, null, options);
        } finally {
            closeQuietly(in);
        }
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 设置界面
    //
    // 外观照 LSPosed 管理器那种卡片式布局：整块底 + 圆角大卡片 + 底部一颗胶囊形导航条。
    // 全部用代码搭，不引 XML 布局也不引任何 UI 库——本项目的构建脚本是
    // aapt2 + javac + d8 手搓的，没有依赖解析，拉不动 GitHub 上的 Material / Compose 库。
    // 颜色一律从 UiTheme 取，跟着系统深色开关走。

    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final int CARD_RADIUS_DP = 24;
    private static final int CARD_PAD_DP = 18;
    private static final int NAV_HEIGHT_DP = 64;
    private static final int TOP_BAR_HEIGHT_DP = 56;
    /** 底部导航只留「首页 / 我的」两栏（扩展栏已去掉）。 */
    private static final String[] NAV_LABELS = {"首页", "我的"};
    private static final int[] NAV_ICONS = {R.drawable.nav_home, R.drawable.nav_person};

    /** 作者酷安主页。 */
    private static final String AUTHOR_URL = "https://www.coolapk.com/u/24154051";
    /** 等 PONG 的时长，超时就当模块没激活。 */
    private static final long PING_TIMEOUT_MS = 1000L;

    private final LinearLayout[] pages = new LinearLayout[NAV_LABELS.length];
    private final View[] navItems = new View[NAV_LABELS.length];
    private final ImageView[] navIcons = new ImageView[NAV_LABELS.length];
    private final TextView[] navLabels = new TextView[NAV_LABELS.length];

    /** 主页那颗「模块已激活」状态点。 */
    private TextView statusDot;
    private boolean activated;
    /** 探测模块是否已注入 SystemUI 的回包接收器。 */
    private BroadcastReceiver pongReceiver;

    /** LSPosed 管理器的包信息，只查一次（读不到就一直是 null）。 */
    private android.content.pm.PackageInfo lsposedInfo;
    private boolean lsposedQueried;

    private void buildSettingsUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiTheme.bg(this));

        // 窗口是 FLAG_TRANSLUCENT_STATUS，内容从 y=0 起算，也就是会钻到状态栏底下。
        // 顶栏自己让出状态栏的高度，滚动内容再让出整个顶栏的高度。
        final int statusBar = statusBarHeight();
        final int topBar = statusBar + dp(TOP_BAR_HEIGHT_DP);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // 上下都留出固定栏的高度：不留的话第一张卡片会钻到顶栏底下、最后一张会被导航条压住
        scroll.setClipToPadding(false);
        scroll.setPadding(0, topBar, 0, dp(NAV_HEIGHT_DP + 44));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), 0);
        scroll.addView(content, new ViewGroup.LayoutParams(MP, WC));
        root.addView(scroll, new FrameLayout.LayoutParams(MP, MP));

        for (int i = 0; i < pages.length; i++) {
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            content.addView(pages[i], new LinearLayout.LayoutParams(MP, WC));
        }

        buildHomePage(pages[0]);
        buildMinePage(pages[1]);

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(MP, topBar);
        topLp.gravity = Gravity.TOP;
        root.addView(buildTopBar(statusBar), topLp);

        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(MP, dp(NAV_HEIGHT_DP));
        navLp.gravity = Gravity.BOTTOM;
        navLp.leftMargin = dp(16);
        navLp.rightMargin = dp(16);
        navLp.bottomMargin = dp(18);
        root.addView(buildNavBar(), navLp);

        setContentView(root);
        selectPage(0);
        probeActivation();
    }

    // ------------------------------------------------------------ 顶栏

    /**
     * 顶部固定栏：左边模块名，右边一颗「强制重启系统界面」按钮。
     *
     * <p>重启必须由 SystemUI 自己执行（{@link TileStylePopup#restartSystemUi} 杀的是
     * <b>调用方所在进程</b>），所以这里只发广播，由 SystemUI 里的
     * {@link TileImageChannel} 代劳。</p>
     */
    private View buildTopBar(int statusBarInset) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // 顶栏不透明：不然滚动内容会从标题和按钮后面透出来
        bar.setBackgroundColor(UiTheme.bg(this));
        bar.setPadding(dp(20), statusBarInset, dp(16), 0);

        TextView name = new TextView(this);
        name.setText("System UI Hook");
        name.setTextColor(UiTheme.title(this));
        name.setTextSize(19f);
        name.setIncludeFontPadding(false);
        bar.addView(name, new LinearLayout.LayoutParams(0, WC, 1f));

        ImageView restart = new ImageView(this);
        restart.setImageResource(R.drawable.ic_restart);
        restart.setColorFilter(UiTheme.title(this));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(UiTheme.sunken(this));
        restart.setBackground(circle);
        int pad = dp(9);
        restart.setPadding(pad, pad, pad, pad);
        restart.setClickable(true);
        restart.setContentDescription("强制重启系统界面");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestart();
            }
        });
        bar.addView(restart, new LinearLayout.LayoutParams(dp(38), dp(38)));
        return bar;
    }

    /** 重启是不可逆操作，先弹个确认框。 */
    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("强制重启系统界面")
                .setMessage("系统界面会立刻重启一次。正在编辑的底图 / 圆角配置会先落盘，不会丢。")
                .setPositiveButton("重启", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        requestSystemUiRestart();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 请求 SystemUI 重启自己。
     *
     * <p>不能在本进程直接调 {@link TileStylePopup#restartSystemUi}——它内部是
     * {@code Process.killProcess(myPid())}，在模块 App 里执行只会把模块自己杀掉。</p>
     */
    private void requestSystemUiRestart() {
        notifySystemUi(PrefKeys.ACTION_RESTART_SYSTEMUI);
        Toast.makeText(this, "已请求重启系统界面", Toast.LENGTH_SHORT).show();
    }

    /** 给 SystemUI 发一条定向广播（只有它能收到，别的应用收不到）。 */
    private void notifySystemUi(String action) {
        notifySystemUi(action, null, false);
    }

    /**
     * 给 SystemUI 发一条定向广播，可附带一个布尔参数。
     *
     * <p>带参数是必须的：SystemUI 读不到模块 App 的 prefs（跨进程
     * {@code XSharedPreferences} 在这台机器上不工作），开关的新值只能这么送过去。</p>
     *
     * @param extraKey   附带的布尔参数名；为 null 表示不带
     * @param extraValue 附带的布尔参数值
     */
    private void notifySystemUi(String action, String extraKey, boolean extraValue) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            if (extraKey != null) {
                intent.putExtra(extraKey, extraValue);
            }
            sendBroadcast(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "通知系统界面失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 给 SystemUI 发一条定向广播，可附带一个字符串参数。
     *
     * <p>导出主题包要用：保存地址（{@code content://} 的 Uri 字符串）只能这么递过去。</p>
     *
     * @param extraKey   附带的字符串参数名；为 null 表示不带
     * @param extraValue 附带的字符串参数值
     */
    private void notifySystemUi(String action, String extraKey, String extraValue) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            if (extraKey != null) {
                intent.putExtra(extraKey, extraValue);
            }
            sendBroadcast(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "通知系统界面失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把「★ 按钮可见性」的当前值同步给 SystemUI。
     *
     * <p>每次打开设置页都同步一次，避免出现「开关显示是关的、控制中心那颗 ★ 还在」：
     * SystemUI 那份 prefs 里没这个键时按默认（显示）走，跟设置页显示的状态可能对不上。</p>
     */
    private void syncEntranceVisibility() {
        notifySystemUi(PrefKeys.ACTION_ENTRANCE_CHANGED, PrefKeys.EXTRA_ENTRANCE_VISIBLE,
                prefs.getBoolean(PrefKeys.KEY_SHOW_ENTRANCE_BUTTON,
                        PrefKeys.DEF_SHOW_ENTRANCE_BUTTON));
    }

    // ------------------------------------------------------------ 首页

    private void buildHomePage(LinearLayout page) {
        page.addView(buildLsposedCard());
        page.addView(buildStarButtonCard());
        page.addView(buildThemeImportCard());
        page.addView(buildResetCard());
    }

    /**
     * 顶部 LSPosed 卡：图标 + 名称 + 版本 + 「● 模块已激活」，下面几行框架信息。
     *
     * <p>版本号读的是 LSPosed 管理器的包信息（{@link PrefKeys#LSPOSED_PACKAGE}），
     * 也就是这个 Magisk 模块对外暴露的版本。</p>
     */
    private View buildLsposedCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(CARD_PAD_DP), dp(CARD_PAD_DP), dp(CARD_PAD_DP), dp(14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_module);
        icon.setColorFilter(UiTheme.title(this));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(UiTheme.sunken(this));
        icon.setBackground(circle);
        int iconPad = dp(13);
        icon.setPadding(iconPad, iconPad, iconPad, iconPad);
        head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WC, 1f);
        textsLp.leftMargin = dp(14);
        texts.setLayoutParams(textsLp);

        TextView name = new TextView(this);
        name.setText("LSPosed");
        name.setTextColor(UiTheme.title(this));
        name.setTextSize(21f);
        name.setIncludeFontPadding(false);
        texts.addView(name);

        TextView version = new TextView(this);
        version.setText(lsposedVersion());
        version.setTextColor(UiTheme.value(this));
        version.setTextSize(13f);
        version.setIncludeFontPadding(false);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(WC, WC);
        versionLp.topMargin = dp(3);
        version.setLayoutParams(versionLp);
        texts.addView(version);
        head.addView(texts);

        statusDot = new TextView(this);
        statusDot.setText("检测中…");
        statusDot.setTextColor(UiTheme.hint(this));
        statusDot.setTextSize(13f);
        statusDot.setIncludeFontPadding(false);
        head.addView(statusDot);
        card.addView(head);

        TextView desc = new TextView(this);
        desc.setText("Hook 已生效，重启目标应用后即可使用。");
        desc.setTextColor(UiTheme.hint(this));
        desc.setTextSize(13f);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(dp(CARD_PAD_DP), 0, dp(CARD_PAD_DP), dp(16));
        card.addView(desc);

        card.addView(cardDivider());
        card.addView(infoRow("框架版本", lsposedVersionName()));
        card.addView(cardDivider());
        card.addView(infoRow("作用域", PrefKeys.SYSTEMUI_PACKAGE));
        card.addView(cardDivider());
        card.addView(infoRow("模块版本", versionLabel()));
        return card;
    }

    /** 星星按钮卡：控制中心右上角那颗 ★ 快捷入口要不要显示。 */
    private View buildStarButtonCard() {
        LinearLayout card = card();
        card.addView(cardTitle("星星按钮"));
        card.addView(switchRow("让星星按钮可见",
                "关掉后控制中心不再显示 ★ 入口，磁贴长按样式面板也就进不去了",
                PrefKeys.KEY_SHOW_ENTRANCE_BUTTON, PrefKeys.DEF_SHOW_ENTRANCE_BUTTON,
                PrefKeys.ACTION_ENTRANCE_CHANGED));
        return card;
    }

    private View buildResetCard() {
        LinearLayout card = card();
        card.addView(actionRow("重置所有配置", UiTheme.DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        }));
        return card;
    }

    // ------------------------------------------------------------ 导入主题包

    /**
     * 「导入主题包」卡：把一个 ColorOS 全局主题包的界面底图按形状套到控制中心上。
     *
     * <p>主题包的 SystemUI 子模块就是一个以 {@code com.android.systemui} 命名的压缩包，
     * 形状映射表见 {@link ThemePackImporter#TARGETS}。这里只做底图，包里的
     * {@code colors.xml} 与指纹动画帧不处理。</p>
     */
    private View buildThemeImportCard() {
        LinearLayout card = card();
        card.addView(cardTitle("导入主题包"));

        TextView desc = new TextView(this);
        desc.setText("选一个 ColorOS 全局主题包（那个以 com.android.systemui 命名的压缩包），"
                + "把里面的界面底图按形状套到控制中心上，一次 "
                + ThemePackImporter.TARGETS.length + " 个形状。");
        desc.setTextColor(UiTheme.hint(this));
        desc.setTextSize(13f);
        desc.setLineSpacing(dp(3), 1f);
        desc.setPadding(dp(CARD_PAD_DP), 0, dp(CARD_PAD_DP), dp(14));
        card.addView(desc);

        card.addView(cardDivider());
        card.addView(actionRow("选择主题包（zip）", UiTheme.accentText(this),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchZipPicker();
                    }
                }));
        // 反向操作放在同一张卡里：导出的包与上面导入认的格式完全一致，
        // 可以拿来备份当前配置，也可以喂回「选择主题包」原样套回来
        card.addView(cardDivider());
        card.addView(actionRow("导出主题包（zip）", UiTheme.accentText(this),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        launchZipCreator();
                    }
                }));
        return card;
    }

    /**
     * 拉系统文件选择器挑主题包。
     *
     * <p>类型给 {@code *}{@code /*} 而不是 {@code application/zip}：主题包那个文件
     * <b>没有扩展名</b>，DocumentsUI 会把它判成 {@code application/octet-stream}
     * 而置灰选不中。{@code EXTRA_MIME_TYPES} 里把 zip 排前面，让它在列表里优先显示。</p>
     */
    private void launchZipPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/octet-stream", "*/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_ZIP);
            return;
        } catch (Throwable ignored) {
        }
        // 有的 ROM 没有文档选择页，回落到通用的取内容
        try {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(fallback, REQ_PICK_ZIP);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件选择页面", Toast.LENGTH_LONG).show();
            finishIfTransparent();
        }
    }

    /**
     * 拉系统的「新建文档」界面，让用户挑把主题包保存到哪里。
     *
     * <p>{@code ACTION_CREATE_DOCUMENT} 回来的是一个可写的 {@code content://} 地址，
     * 而且<b>只有本页拿到了写权限</b>——真正写文件的是 SystemUI 进程，所以拿到结果后
     * 还得把这个地址转授给它（见 {@link #requestThemeExport}）。</p>
     *
     * <p>建议文件名给「全局主题【时间】.zip」：一眼能看出这是刚导出的那份配置，
     * 时间戳还能区分开多次导出的包，不至于重名互相覆盖。这只是一个建议值，
     * 用户仍可在这一页里手动改名。</p>
     */
    private void launchZipCreator() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, buildExportFileName());
        try {
            startActivityForResult(intent, REQ_CREATE_ZIP);
            return;
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的保存页面", Toast.LENGTH_LONG).show();
            finishIfTransparent();
        }
    }

    /**
     * 导出时建议的文件名：{@code 全局主题【yyyyMMdd_HHmmss】.zip}。
     *
     * <p>时间用下划线分隔，不用冒号——部分存储路径不接受冒号，写进去会被系统改掉或存不下。</p>
     */
    private static String buildExportFileName() {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        return "全局主题【" + ts + "】.zip";
    }

    /**
     * 把选好的保存地址转授给 SystemUI，请它把主题包写进去。
     *
     * <p>两步都是必须的：</p>
     * <ul>
     *   <li>{@code grantUriPermission}——写权限只落在本页（拿到了
     *       {@code ACTION_CREATE_DOCUMENT} 的结果），SystemUI 是另一个进程 / uid，
     *       不转授的话它 {@code openOutputStream} 会直接 {@code SecurityException}；</li>
     *   <li>发 {@link PrefKeys#ACTION_THEME_EXPORT}——打包用的形状底图与颜色都存在
     *       SystemUI 自己的 prefs 里，本页读不到，只能让它自己读自己写。</li>
     * </ul>
     *
     * <p>结果提示交给 SystemUI 那一侧弹（同进程就能拿到成败），所以这里发完就收工，
     * 透明入口也就不会有「后台留一个空白窗口」的问题。</p>
     */
    private void requestThemeExport(final Uri uri) {
        boolean granted = false;
        try {
            grantUriPermission(PrefKeys.SYSTEMUI_PACKAGE, uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            granted = true;
        } catch (Throwable t) {
            // 本页跑在模块 App 进程，用不了 Xposed 的日志通道，退回系统日志
            android.util.Log.w("SystemUIHook/ThemeExport", "grant uri failed", t);
        }
        if (granted) {
            notifySystemUi(PrefKeys.ACTION_THEME_EXPORT,
                    PrefKeys.EXTRA_THEME_EXPORT_URI, uri.toString());
        }
        finishIfTransparent();
    }

    /** 覆盖已有底图前先问一句：这一步会静默改掉一批形状，不提示的话用户会觉得莫名其妙。 */
    private void importThemePack(final Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("导入主题包")
                .setMessage("将把包里的界面底图套到 1×1 磁贴、右上角那排小圆钮（★ ✎ ⚙ ⋮）、"
                        + "2×1 宽卡、三阶段卡、2×2 媒体卡、方形卡片、通知卡、"
                        + "清除按钮与亮度 / 音量胶囊上，这些形状已有的底图会被覆盖；"
                        + "包里的全部配置项（各形状的圆角 / 可见度、图标颜色、文字颜色、"
                        + "各式开关等）也会一并还原。导入后系统界面会重启一次。")
                .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        applyThemePack(uri);
                        finishIfTransparent();
                    }
                })
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        finishIfTransparent();
                    }
                })
                .show();
    }

    /**
     * 解包 → 按形状编码 → 逐条静默发给 SystemUI → 最后统一重启一次系统界面。
     *
     * <p>九条图片广播都带 {@link PrefKeys#EXTRA_BG_SILENT}：SystemUI 那边只落盘不重启。
     * 九条发完再发一条 {@link PrefKeys#ACTION_RESTART_SYSTEMUI} 收尾，让所有视图带着
     * 新配置统一重建（包里只带配置快照、没有底图的形状也一并生效）。</p>
     *
     * <p>编码走 PNG 而不是相册链路的 JPEG：这批主题底图大片透明（1×1 是正圆 + 透明四角、
     * 清除按钮是圆环、通知卡是白底），JPEG 没有 alpha，透明处会变成黑块。</p>
     */
    private void applyThemePack(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                Toast.makeText(this, "读取主题包失败", Toast.LENGTH_LONG).show();
                return;
            }
            Map<String, byte[]> entries =
                    ThemePackImporter.readEntries(in, ThemePackImporter.wantedEntries());

            // 颜色表里的「图标激活时的背景颜色」（{@code status_bar_qs_tile_bg_color_active}）：
            // 那是 2×1 宽卡片与三阶段卡片图标底下那块小色块的底色。它跟着各自那条
            // 底图广播一起送（见 PrefKeys#EXTRA_ICON_COLOR），不单开一条广播
            byte[] colors = entries.get(ThemePackImporter.COLORS_ENTRY);
            int iconArgb = ThemePackImporter.tileIconBgColor(colors);
            // 同一个颜色表里还有「图标本身的颜色」（激活 / 未激活）与「标签文字颜色」，
            // 那三项对应样式面板里的「图标着色（激活）/ 图标着色 / 文字颜色」
            int tintOnArgb = ThemePackImporter.colorValue(colors,
                    ThemePackImporter.KEY_TILE_ICON_ACTIVE);
            int tintOffArgb = ThemePackImporter.colorValue(colors,
                    ThemePackImporter.KEY_TILE_ICON_INACTIVE);
            if (tintOnArgb == 0 && tintOffArgb != 0) {
                // 主题包的颜色表里普遍只有「未激活」那一条
                // （status_bar_qs_tile_icon_color_inactive），没有 ..._active。
                // 不兜底的话，「图标激活」这一项永远是「跟随系统」，
                // 1×1 圆形磁贴开着时的图标色就吃不到主题包——看着就是
                // 「颜色能手动改，但导入主题包不适配」。
                tintOnArgb = tintOffArgb;
            }
            int labelArgb = ThemePackImporter.colorValue(colors,
                    ThemePackImporter.KEY_TILE_LABEL);
            // 控制中心背景的纯色：颜色表里的「通知背景遮罩色」（没有时退回「通知材质背景色」），
            // 且一律顶成不透明——半透明的底会透出系统那层跟日夜模式换的 scrim，
            // 把主题包里那批半透明磁贴素材染成两种颜色，见 ThemePackImporter#qsBackgroundColor。
            // 它不是某个形状的属性，而是整个背景一份，所以跟着每趟底图广播一起送
            int qsBgArgb = ThemePackImporter.qsBackgroundColor(colors);

            // 模块自有的整份配置快照（导出包里那张 extras 表）：把它挂在第一条真正发出的
            // 底图广播上带过去，SystemUI 侧一次性倒回 prefs，把所有非底图修改项都还原。
            // 标准层（colors.xml）装不下的圆角 / 可见度 / 各式开关等都在这里
            byte[] extrasBytes = entries.get(ThemePackImporter.EXTRAS_ENTRY);
            String extrasXml = extrasBytes == null || extrasBytes.length == 0 ? null
                    : new String(extrasBytes, StandardCharsets.UTF_8);

            int sent = 0;
            // 整份快照只搭第一趟车；这一个布尔跨两张 Target 表，保证只送一次
            boolean extrasSent = false;
            StringBuilder missing = new StringBuilder();
            for (ThemePackImporter.Target target : ThemePackImporter.TARGETS) {
                byte[] raw = entries.get(target.entry);
                if (raw == null || raw.length == 0) {
                    if (missing.length() > 0) {
                        missing.append("、");
                    }
                    missing.append(target.label);
                    continue;
                }
                Bitmap bitmap = ThemePackImporter.decode(raw, MAX_THEME_IMAGE_SIDE,
                        ThemePackImporter.isNinePatch(target.entry));
                if (bitmap == null) {
                    if (missing.length() > 0) {
                        missing.append("、");
                    }
                    missing.append(target.label);
                    continue;
                }
                byte[] png = ThemePackImporter.toPng(bitmap);
                // 圆角比例要在 recycle 之前量：量的是原图的透明边，不是编码后的字节
                float ratio = ThemePackImporter.adaptsCorner(target.shape)
                        ? ThemePackImporter.cornerRatioOf(bitmap) : 0f;
                bitmap.recycle();
                int color = carriesIconColor(target.shape) ? iconArgb : 0;
                // 三项着色只送给能吃它们的形状（主控制中心磁贴 + 方形设备卡片）
                boolean tint = carriesTint(target.shape);
                if (sendToSystemUI(target.shape, target.active, png, true, color,
                        tint ? tintOnArgb : 0, tint ? tintOffArgb : 0,
                        tint ? labelArgb : 0, qsBgArgb,
                        extrasSent ? null : extrasXml)) {
                    sent++;
                    extrasSent = true;
                    // 跟着图一起把圆角比例送过去，否则 SystemUI 会按 20dp 把素材的
                    // 圆角切掉一条边（两个 2×1 形状共用同一张图，比例自然也一样）
                    sendCornerToSystemUI(target.shape, ratio);
                }
            }

            // 标准格式没有落点的两张底图（控制中心背景图、胶囊进度底图）：另起条目名带上。
            // 它们是伪形状、无开 / 关之分、也不吃图标颜色与各项着色，所以只送图本身；
            // 圆角比例同样按素材量（这两张都被 adaptsCorner 判为自适应）
            for (ThemePackImporter.Target target : ThemePackImporter.EXTRA_TARGETS) {
                byte[] raw = entries.get(target.entry);
                if (raw == null || raw.length == 0) {
                    continue;
                }
                Bitmap bitmap = ThemePackImporter.decode(raw, MAX_THEME_IMAGE_SIDE, false);
                if (bitmap == null) {
                    continue;
                }
                byte[] png = ThemePackImporter.toPng(bitmap);
                float ratio = ThemePackImporter.cornerRatioOf(bitmap);
                bitmap.recycle();
                if (sendToSystemUI(target.shape, target.active, png, true, 0, 0, 0, 0, qsBgArgb,
                        extrasSent ? null : extrasXml)) {
                    sent++;
                    extrasSent = true;
                    sendCornerToSystemUI(target.shape, ratio);
                }
            }

            if (sent == 0 && extrasXml == null) {
                Toast.makeText(this, "这个包里没有能用的内容", Toast.LENGTH_LONG).show();
                return;
            }
            // 包里一张底图都没有、只有整份配置快照（用户只改过可见度 / 颜色 / 开关，
            // 没换过图）：没有底图广播可搭车，单独把快照送一趟
            if (sent == 0) {
                sendExtrasToSystemUI(extrasXml);
            }
            // 收尾：九条都落盘了，请 SystemUI 重启一次，把所有视图带着新配置统一重建
            notifySystemUi(PrefKeys.ACTION_RESTART_SYSTEMUI);
            Toast.makeText(this, "已导入 " + sent + " / "
                            + (ThemePackImporter.TARGETS.length
                            + ThemePackImporter.EXTRA_TARGETS.length) + " 项"
                            + (extrasXml != null ? "，含全部配置项" : "")
                            + (qsBgArgb != 0
                            ? "，背景颜色 #" + Integer.toHexString(qsBgArgb).toUpperCase() : "")
                            + (iconArgb != 0
                            ? "，图标底色 #" + Integer.toHexString(iconArgb).toUpperCase() : "")
                            + (tintOnArgb != 0
                            ? "，图标着色 #" + Integer.toHexString(tintOnArgb).toUpperCase() : "")
                            + (labelArgb != 0
                            ? "，文字颜色 #" + Integer.toHexString(labelArgb).toUpperCase() : "")
                            + (missing.length() > 0 ? "，缺少：" + missing : "")
                            + "，系统界面正在重启",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "导入失败：" + t, Toast.LENGTH_LONG).show();
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------ LSPosed 版本探测

    /**
     * LSPosed 管理器的包信息，只查一次。
     *
     * <p>清单里的 {@code <queries>} 声明了 {@link PrefKeys#LSPOSED_PACKAGE}，
     * 否则 Android 11+ 的包可见性会直接抛 {@code NameNotFoundException}。</p>
     */
    private android.content.pm.PackageInfo lsposed() {
        if (!lsposedQueried) {
            lsposedQueried = true;
            try {
                lsposedInfo = getPackageManager()
                        .getPackageInfo(PrefKeys.LSPOSED_PACKAGE, 0);
            } catch (Throwable ignored) {
            }
        }
        return lsposedInfo;
    }

    /** LSPosed 版本，形如 {@code 2.2.0 (7854)}。 */
    private String lsposedVersion() {
        android.content.pm.PackageInfo info = lsposed();
        if (info == null) {
            return "未检测到";
        }
        long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
        return (info.versionName == null ? "?" : info.versionName) + " (" + code + ")";
    }

    /** 只要版本名（副标题已经带了 versionCode，信息行里不重复）。 */
    private String lsposedVersionName() {
        android.content.pm.PackageInfo info = lsposed();
        return info == null || info.versionName == null ? "未检测到" : info.versionName;
    }

    // ------------------------------------------------------------ 我的

    private void buildMinePage(LinearLayout page) {
        LinearLayout author = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(CARD_PAD_DP), dp(CARD_PAD_DP), dp(CARD_PAD_DP), dp(CARD_PAD_DP));

        final ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_star);
        avatar.setColorFilter(UiTheme.accentText(this));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(UiTheme.sunken(this));
        avatar.setBackground(circle);
        // 头像照片按圆形裁：正方形图直接铺满会被裁成圆的，不然就是个方块
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setFallbackAvatar(avatar);
        // prefs 里存着上次取到的头像就先铺上，网络那一趟回来再换成新的
        Bitmap cachedAvatar = CoolapkAvatar.cached(this);
        if (cachedAvatar != null) {
            showAvatarBitmap(avatar, cachedAvatar);
        }
        head.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
        CoolapkAvatar.load(this, new CoolapkAvatar.Callback() {
            @Override
            public void onAvatar(Bitmap bitmap) {
                showAvatarBitmap(avatar, bitmap);
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsLp = new LinearLayout.LayoutParams(0, WC, 1f);
        textsLp.leftMargin = dp(14);
        texts.setLayoutParams(textsLp);

        TextView name = new TextView(this);
        name.setText("System UI Hook");
        name.setTextColor(UiTheme.title(this));
        name.setTextSize(19f);
        name.setIncludeFontPadding(false);
        texts.addView(name);

        TextView by = new TextView(this);
        by.setText("作者 · 酷安 @24154051");
        by.setTextColor(UiTheme.value(this));
        by.setTextSize(13f);
        by.setIncludeFontPadding(false);
        LinearLayout.LayoutParams byLp = new LinearLayout.LayoutParams(WC, WC);
        byLp.topMargin = dp(4);
        by.setLayoutParams(byLp);
        texts.addView(by);
        head.addView(texts);
        author.addView(head);

        author.addView(cardDivider());
        author.addView(linkRow("酷安主页", "coolapk.com/u/24154051", AUTHOR_URL));
        page.addView(author);
    }

    /** 头像没取到时的兜底：原来那颗星星图标（圆形底 + 内缩留白）。 */
    private void setFallbackAvatar(ImageView avatar) {
        avatar.setImageResource(R.drawable.ic_star);
        avatar.setColorFilter(UiTheme.accentText(this));
        int pad = dp(14);
        avatar.setPadding(pad, pad, pad, pad);
    }

    /**
     * 铺上酷安取回来的头像。
     *
     * <p>要把底色滤镜清掉——{@link #setFallbackAvatar} 给星星上的那层着色
     * 会把真人头像也染成主题色。留白也要清，否则照片会被缩在一个小圈里。</p>
     */
    private void showAvatarBitmap(ImageView avatar, Bitmap bitmap) {
        avatar.setColorFilter(null);
        avatar.setPadding(0, 0, 0, 0);
        avatar.setImageBitmap(bitmap);
    }

    // ------------------------------------------------------------ 底部导航

    private View buildNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiTheme.card(this));
        // 胶囊：圆角取高度一半
        bg.setCornerRadius(dp(NAV_HEIGHT_DP / 2));
        bar.setBackground(bg);
        bar.setElevation(dp(8));

        for (int i = 0; i < NAV_LABELS.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setPadding(dp(18), dp(6), dp(18), dp(6));

            ImageView icon = new ImageView(this);
            icon.setImageResource(NAV_ICONS[i]);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            iconLp.bottomMargin = dp(3);
            icon.setLayoutParams(iconLp);
            item.addView(icon);

            TextView label = new TextView(this);
            label.setText(NAV_LABELS[i]);
            label.setTextSize(11f);
            label.setIncludeFontPadding(false);
            item.addView(label);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectPage(index);
                }
            });

            // 外层槽位撑满一半宽度，胶囊背景只包住图标 + 文字，选中时不会拉成一整条
            LinearLayout slot = new LinearLayout(this);
            slot.setGravity(Gravity.CENTER);
            slot.addView(item, new LinearLayout.LayoutParams(WC, WC));
            bar.addView(slot, new LinearLayout.LayoutParams(0, MP, 1f));

            navItems[i] = item;
            navIcons[i] = icon;
            navLabels[i] = label;
        }
        return bar;
    }

    private void selectPage(int index) {
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == index;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(24));
            pill.setColor(selected ? UiTheme.navSelectedBg(this) : Color.TRANSPARENT);
            navItems[i].setBackground(pill);
            // 重新 setImageResource + setColorFilter：确保两颗图标各持自己的 drawable，
            // 不会因为共用 ConstantState 被一起染成同一个颜色
            navIcons[i].setImageResource(NAV_ICONS[i]);
            navIcons[i].setColorFilter(selected ? UiTheme.accentText(this) : UiTheme.navIdle(this));
            navLabels[i].setTextColor(selected ? UiTheme.accentText(this) : UiTheme.navIdle(this));
        }
    }

    // ------------------------------------------------------------ 模块激活探测

    /**
     * 问一下 SystemUI 在不在。
     *
     * <p>模块 App 自己没法判断 LSPosed 有没有把模块注入 SystemUI，所以发一条
     * {@link PrefKeys#ACTION_PING}，由 SystemUI 进程里 {@code TileImageChannel}
     * 的接收器回一条 {@link PrefKeys#ACTION_PONG}；收到才算激活。回包顺带带上
     * Xposed API 版本——那个数只有被注入的进程里才问得出来。</p>
     */
    private void probeActivation() {
        if (statusDot == null) {
            return;
        }
        pongReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                setStatus("● 模块已激活", UiTheme.OK);
            }
        };
        try {
            IntentFilter filter = new IntentFilter(PrefKeys.ACTION_PONG);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pongReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(pongReceiver, filter);
            }
        } catch (Throwable t) {
            pongReceiver = null;
        }

        setStatus("检测中…", UiTheme.hint(this));
        try {
            Intent ping = new Intent(PrefKeys.ACTION_PING);
            ping.setPackage(PrefKeys.SYSTEMUI_PACKAGE);
            sendBroadcast(ping);
        } catch (Throwable ignored) {
        }
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!activated) {
                    setStatus("● 未检测到模块", UiTheme.WARN);
                }
            }
        }, PING_TIMEOUT_MS);
    }

    private void setStatus(String text, int color) {
        if (statusDot == null) {
            return;
        }
        if (color == UiTheme.OK) {
            activated = true;
        }
        statusDot.setText(text);
        statusDot.setTextColor(color);
    }

    @Override
    protected void onDestroy() {
        if (pongReceiver != null) {
            try {
                unregisterReceiver(pongReceiver);
            } catch (Throwable ignored) {
            }
            pongReceiver = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 卡片与行

    /** 一张卡片：圆角块，内部竖排，自带下边距。 */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiTheme.card(this));
        bg.setCornerRadius(dp(CARD_RADIUS_DP));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private View cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(UiTheme.title(this));
        tv.setTextSize(17f);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(CARD_PAD_DP), dp(16), dp(CARD_PAD_DP), dp(8));
        return tv;
    }

    private View cardDivider() {
        View v = new View(this);
        v.setBackgroundColor(UiTheme.divider(this));
        v.setLayoutParams(new LinearLayout.LayoutParams(MP, Math.max(1, dp(1) / 2)));
        return v;
    }

    /** 一行「左标签 / 右值」。 */
    private View infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(CARD_PAD_DP), dp(15), dp(CARD_PAD_DP), dp(15));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextColor(UiTheme.label(this));
        left.setTextSize(15f);
        left.setIncludeFontPadding(false);
        row.addView(left, new LinearLayout.LayoutParams(0, WC, 1f));

        TextView right = new TextView(this);
        right.setText(value);
        right.setTextColor(UiTheme.value(this));
        right.setTextSize(15f);
        right.setIncludeFontPadding(false);
        right.setSingleLine(true);
        right.setEllipsize(TextUtils.TruncateAt.START);
        row.addView(right);
        return row;
    }

    /** 一行可点的外链。 */
    private View linkRow(String label, String value, final String url) {
        View row = infoRow(label, value + "  ›");
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 一行「标签 +（可选副标题）+ 开关」。 */
    private View switchRow(String label, String hint, final String key, boolean def) {
        return switchRow(label, hint, key, def, null);
    }

    /**
     * 一行「标签 +（可选副标题）+ 开关」。
     *
     * @param notifyAction 不为 null 时，开关一变就发这条广播通知 SystemUI 立刻生效；
     *                     靠 hook 自己按 1 秒节流去轮询的话，用户会看到「改了没反应」
     */
    private View switchRow(String label, String hint, final String key, boolean def,
            final String notifyAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(CARD_PAD_DP), dp(14), dp(CARD_PAD_DP), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, WC, 1f));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(UiTheme.label(this));
        tv.setTextSize(16f);
        tv.setIncludeFontPadding(false);
        texts.addView(tv);

        if (hint != null) {
            TextView sub = new TextView(this);
            sub.setText(hint);
            sub.setTextColor(UiTheme.hint(this));
            sub.setTextSize(12f);
            sub.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(WC, WC);
            subLp.topMargin = dp(4);
            subLp.rightMargin = dp(12);
            sub.setLayoutParams(subLp);
            texts.addView(sub);
        }
        row.addView(texts);

        android.widget.Switch sw = new android.widget.Switch(this);
        tintSwitch(sw);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setTag(key);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton cb, boolean checked) {
                prefs.edit().putBoolean((String) cb.getTag(), checked).apply();
                TileConfigs.invalidate();
                if (notifyAction != null) {
                    // 附带的布尔值只有「★ 按钮可见性」这条广播会读（见 TileImageChannel），
                    // 以后再加别的开关时记得同步那边的取法
                    notifySystemUi(notifyAction, PrefKeys.EXTRA_ENTRANCE_VISIBLE, checked);
                }
            }
        });
        row.addView(sw);
        return row;
    }

    private void tintSwitch(android.widget.Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        try {
            sw.setTrackTintList(new ColorStateList(states,
                    new int[]{UiTheme.trackOn(this), UiTheme.trackOff(this)}));
            sw.setThumbTintList(ColorStateList.valueOf(UiTheme.thumb(this)));
        } catch (Throwable ignored) {
        }
    }

    /** 整行可点的动作按钮（居中文字）。 */
    private View actionRow(String label, int color, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(16f);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setPadding(dp(CARD_PAD_DP), dp(17), dp(CARD_PAD_DP), dp(17));
        tv.setClickable(true);
        tv.setOnClickListener(click);
        return tv;
    }

    /** 版本号，取不到就不显示（构建脚本里写的是 1.0）。 */
    private String versionLabel() {
        try {
            android.content.pm.PackageInfo info =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 重置

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("确认")
                .setMessage("将重置全部开关、每磁贴配置以及按形状设置的自定义底图。\n"
                        + "重置后系统界面会立即重启一次。")
                .setPositiveButton("重置", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // 模块侧这份（开关、每磁贴 JSON）整体清空就等于回到默认值
                        prefs.edit().clear().apply();
                        TileConfigs.invalidate();
                        // 真正的配置（按形状的圆角 / 可见度 / 底图、长按开关、控制中心背景）
                        // 存在 SystemUI 自己的 prefs 里，这边清不到，只能请它自己清
                        try {
                            notifySystemUi(PrefKeys.ACTION_RESET_CONFIG);
                        } catch (Throwable t) {
                            Toast.makeText(SettingsActivity.this,
                                    "已重置本机配置，但通知系统界面失败：" + t,
                                    Toast.LENGTH_LONG).show();
                        }
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 状态栏高度（像素）。取不到就按 24dp 兜底，别让顶栏压到状态栏上。 */
    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        return dp(24);
    }
}
