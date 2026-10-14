package com.thexiaoxia43.coloros.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Base64;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把当前配置打包成一个<b>主题包</b>，写到用户选好的地址。
 *
 * <p><b>为什么在 SystemUI 进程里打包</b>：形状底图（Base64）、圆角比例、各项颜色
 * 都存在 SystemUI 自己的 prefs（{@code systemui_hook_ui}）里，模块 App 读不到；
 * 反过来把图片经 Binder 送过去又会顶爆事务上限。所以模块设置页只负责拿到
 * {@code ACTION_CREATE_DOCUMENT} 的结果，转授写权限后把地址广播过来，本进程读自己的
 * prefs 打包写盘（见 {@link TileImageChannel#handleThemeExport}）。</p>
 *
 * <p><b>产出的格式与「导入主题包」认的格式完全一致</b>——顶层就是 SystemUI 那一层：
 * {@code assets/colors.xml} + {@code res/drawable-xxhdpi/<entry>.png}，
 * 条目名与 {@link ThemePackImporter#TARGETS} 一一对应。所以导出的包能被
 * {@link ThemePackImporter} 原样读回来，往返一致。</p>
 *
 * <p>在这个标准层之外，包里还多两样<b>只属于本模块</b>的东西，把「所有修改项」带全：</p>
 * <ul>
 *   <li>{@link ThemePackImporter#EXTRAS_ENTRY}（{@code assets/systemui_hook_extras.xml}）：
 *       SystemUI prefs 里除底图以外<b>全部</b>键值的快照（每个形状的圆角 / 可见度、
 *       图标底色与四种着色的不透明度、控制中心背景色、各式开关……），见
 *       {@link HookConfigExtras}；</li>
 *   <li>{@link ThemePackImporter#EXTRA_TARGETS}：标准格式没有落点的控制中心背景图与
 *       胶囊进度底图，另起条目名带上。</li>
 * </ul>
 *
 * <p>这两样都不会破坏格式兼容：标准导入器按条目名精确匹配，不认识就跳过，
 * 带它们的包仍然是合法主题包。</p>
 *
 * <p><b>两处刻意的取舍</b>：</p>
 * <ul>
 *   <li>主题包里同一张图会被多条 Target 引用（{@code ..._inactive.png} 同时喂 1×1「关」
 *       与右上角小圆钮；{@code highlighttile...} 同时喂 2×1 与三阶段；
 *       {@code status_nodevice_card...} 同时喂方形卡片与 1×2 设备卡片）。一个条目只能
 *       装一张图，所以按 {@link ThemePackImporter#TARGETS} 的顺序取<b>第一条命中</b>的。
 *       这是格式本身的取舍，不是这里的实现缺陷；</li>
 *   <li>{@code systemui_icon_volume_bg.9.png} 导出的是当初导入时已经裁掉 1px 黑边的图，
 *       不再补回拉伸标记。再导入一次会被再裁 1px（往返各丢 2px），胶囊那点尺寸无感，
 *       不值得为它把 nine-patch 的边框重新编出来。</li>
 * </ul>
 */
public final class ThemePackExporter {

    private static final String TAG = "SystemUIHook/ThemeExport";

    /** 导出时解码用：底图本身已在导入 / 选图时缩到很小，这里给足上限不再二次降采样。 */
    private static final int MAX_SIDE = 1440;

    /** 一次导出的结果，供 {@link TileImageChannel} 记日志与弹提示。 */
    public static final class Result {
        /** 是否成功写出。 */
        public final boolean ok;
        /** 写进去几张底图。 */
        public final int imageCount;
        /** 写进去几项颜色。 */
        public final int colorCount;
        /** 写进去几条<b>模块自有配置</b>（extras 表的条目数，也就是「全部修改项」的条数）。 */
        public final int extrasCount;
        /** 压缩包字节数。 */
        public final long byteCount;
        /** 失败原因；成功时为 null。 */
        public final String message;

        Result(boolean ok, int imageCount, int colorCount, int extrasCount, long byteCount,
                String message) {
            this.ok = ok;
            this.imageCount = imageCount;
            this.colorCount = colorCount;
            this.extrasCount = extrasCount;
            this.byteCount = byteCount;
            this.message = message;
        }
    }

    private ThemePackExporter() {
    }

    /**
     * 打包并写到 {@code uri}。
     *
     * @param ctx SystemUI 侧 Context（prefs 与 ContentResolver 都用它）
     * @param uri 用户在「新建文档」里选好的保存地址
     * @return 结果；任何一步出错都返回 {@code ok=false} + 原因，不抛异常
     */
    public static Result export(final Context ctx, final Uri uri) {
        if (ctx == null || uri == null) {
            return new Result(false, 0, 0, 0, 0, "参数为空");
        }
        try {
            Map<String, byte[]> images = collectImages(ctx);
            String colorsXml = buildColorsXml(ctx);
            // 模块自有的那批修改项（圆角 / 可见度 / 各着色的不透明度 / 各式开关…）
            // 标准颜色表装不下，另写一张 extras 表；导入看到它就把这些项一并还原
            String extrasXml = HookConfigExtras.build(ctx);
            int extrasCount = countItems(extrasXml);
            if (images.isEmpty() && colorsXml == null && extrasXml.length() == 0) {
                return new Result(false, 0, 0, 0, 0, "没有可导出的配置");
            }
            long bytes = writeZip(ctx, uri, images, colorsXml, extrasXml);
            int colorCount = colorsXml == null ? 0 : countColors(colorsXml);
            return new Result(true, images.size(), colorCount, extrasCount, bytes, null);
        } catch (Throwable t) {
            log("export failed - " + t);
            return new Result(false, 0, 0, 0, 0, String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ 图片

    /**
     * 把每个条目对应的底图读出来编码成 PNG。
     *
     * <p>覆盖两张表：{@link ThemePackImporter#TARGETS}（标准主题包给各形状供的图，导出时
     * 原样回填到标准条目名，方便这个包仍然能被别的工具 / 老导入链路读）+
     * {@link ThemePackImporter#EXTRA_TARGETS}（标准格式没有落点的控制中心背景图与胶囊
     * 进度底图，另起条目名带上）。</p>
     *
     * <p>同一 entry 被多条 Target 引用时只取第一条<b>真的有图</b>的：先到先占，
     * 后面的同 entry 条目只在前面没产出时才补上。</p>
     *
     * @return 条目名 → PNG 字节（只含有图的条目）
     */
    private static Map<String, byte[]> collectImages(final Context ctx) {
        Map<String, byte[]> images = new LinkedHashMap<String, byte[]>();
        for (ThemePackImporter.Target target : ThemePackImporter.TARGETS) {
            putImage(ctx, images, target);
        }
        for (ThemePackImporter.Target target : ThemePackImporter.EXTRA_TARGETS) {
            putImage(ctx, images, target);
        }
        return images;
    }

    /** 读一个 Target 对应的底图并编码成 PNG 放进 {@code images}；没图 / 解码失败就跳过。 */
    private static void putImage(final Context ctx, final Map<String, byte[]> images,
            final ThemePackImporter.Target target) {
        if (images.containsKey(target.entry)) {
            return;
        }
        // bgImage 内部按形状归一化：磁贴形状读开 / 关那套（不分套的恒按「开」），
        // 控制中心背景 / 胶囊进度这类伪形状走不带状态的老键
        String base64 = TileLongPressConfig.bgImage(ctx, target.shape, target.active);
        if (base64 == null || base64.length() == 0) {
            return;
        }
        byte[] raw;
        try {
            raw = Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            log("decode base64 failed for " + target.shape + " - " + t);
            return;
        }
        // ninePatch 传 false：prefs 里存的是当初裁过边的图，这里不再动
        Bitmap bitmap = ThemePackImporter.decode(raw, MAX_SIDE, false);
        if (bitmap == null) {
            log("bitmap decode failed for " + target.shape);
            return;
        }
        byte[] png = ThemePackImporter.toPng(bitmap);
        bitmap.recycle();
        images.put(target.entry, png);
    }

    // ------------------------------------------------------------------ 颜色

    /**
     * 生成 {@code assets/colors.xml}。
     *
     * <p>只写「设过」的那几条（跟随系统的 -1 与 0 一律不写）——主题包的颜色表里没有
     * 「恢复系统」这一说，写进去反而会在导入时把「跟随系统」当成一个具体色值套错。</p>
     *
     * <p>取值与导入互为镜像（见 {@code SettingsActivity#applyThemePack}）：导入时每个
     * 颜色都往一批形状上套，导出时就从其中<b>一个代表形状</b>读回来，往返稳定。
     * 图标底色只往 2×1 / 三阶段套（{@link PrefKeys#ICON_COLOR_SHAPES}），
     * 着色四项只往主控制中心磁贴套（{@link PrefKeys#TINT_SHAPES}），
     * 所以这里分别从 2×1 与 1×1 / 三阶段读。</p>
     *
     * <p>{@code notification_scrim_bg_mask_color} 在导入侧会被
     * {@link ThemePackImporter#qsBackgroundColor} 等比压暗到中灰一档，所以这份包再导入时
     * 背景色会变暗一点——那是导入对<b>所有</b>主题包一视同仁的规则，不是格式不一致。</p>
     *
     * @return xml 文本；一条都没有时返回 null
     */
    private static String buildColorsXml(final Context ctx) {
        StringBuilder body = new StringBuilder();

        // 图标底色：2×1 宽卡片与三阶段卡片共用，取 2×1，空则退三阶段
        int iconBg = TileLongPressConfig.iconColorOf(ctx, TileLongPressConfig.SHAPE_2X1);
        if (isFollowSystem(iconBg)) {
            iconBg = TileLongPressConfig.iconColorOf(ctx, TileLongPressConfig.SHAPE_THREE);
        }
        appendColor(body, ThemePackImporter.KEY_TILE_ICON_BG, iconBg);

        // 图标着色（关）：主控制中心磁贴里所有非三阶段形状共用一格，取 1×1
        int tintOff = TileLongPressConfig.tintColorOf(ctx, TileLongPressConfig.SHAPE_1X1,
                TileLongPressConfig.TINT_ICON_OFF);
        appendColor(body, ThemePackImporter.KEY_TILE_ICON_INACTIVE, tintOff);

        // 图标着色（激活）：只有三阶段卡片是独立一格，取它；取不到就用「关」兜底，
        // 对齐导入侧「..._active 缺失时拿 ..._inactive 顶上」的兜底
        int tintOn = TileLongPressConfig.tintColorOf(ctx, TileLongPressConfig.SHAPE_THREE,
                TileLongPressConfig.TINT_ICON_ON);
        if (!isFollowSystem(tintOn) || !isFollowSystem(tintOff)) {
            appendColor(body, ThemePackImporter.KEY_TILE_ICON_ACTIVE,
                    isFollowSystem(tintOn) ? tintOff : tintOn);
        }

        // 文字颜色：与「图标着色」同一批形状，取 1×1 那一格
        int label = TileLongPressConfig.tintColorOf(ctx, TileLongPressConfig.SHAPE_1X1,
                TileLongPressConfig.TINT_LABEL);
        appendColor(body, ThemePackImporter.KEY_TILE_LABEL, label);

        // 控制中心背景色：整个背景一份
        int qsBg = TileLongPressConfig.qsBackgroundColor(ctx);
        appendColor(body, ThemePackImporter.KEY_QS_BG, qsBg);

        if (body.length() == 0) {
            return null;
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources>\n"
                + body
                + "</resources>\n";
    }

    /** 拼一条 {@code <color name="#AARRGGBB">}；跟随系统 / 空值直接跳过。 */
    private static void appendColor(final StringBuilder body, final String name, final int argb) {
        if (isFollowSystem(argb)) {
            return;
        }
        body.append("    <color name=\"").append(name).append("\">")
                .append(toHex(argb))
                .append("</color>\n");
    }

    /** 这个值是不是「没设过 / 跟随系统」。 */
    private static boolean isFollowSystem(final int argb) {
        return argb == 0 || argb == TileLongPressConfig.ICON_COLOR_FOLLOW_SYSTEM;
    }

    /** ARGB → {@code #AARRGGBB}（大写，补足 8 位）。 */
    private static String toHex(final int argb) {
        String hex = Integer.toHexString(argb).toUpperCase();
        StringBuilder sb = new StringBuilder("#");
        for (int i = hex.length(); i < 8; i++) {
            sb.append('0');
        }
        return sb.append(hex).toString();
    }

    /** xml 里写了几条 color（给导出提示用）。 */
    private static int countColors(final String xml) {
        int count = 0;
        int at = 0;
        while ((at = xml.indexOf("<color ", at)) >= 0) {
            count++;
            at += 7;
        }
        return count;
    }

    /** extras 表里写了几条 {@code <item>}（也就是「全部修改项」的条数，给导出提示用）。 */
    private static int countItems(final String xml) {
        if (xml == null) {
            return 0;
        }
        int count = 0;
        int at = 0;
        while ((at = xml.indexOf("<item ", at)) >= 0) {
            count++;
            at += 6;
        }
        return count;
    }

    // ------------------------------------------------------------------ 打包

    /** 把图片、颜色表与 extras 表写成一个 zip。返回写出的字节数。 */
    private static long writeZip(final Context ctx, final Uri uri,
            final Map<String, byte[]> images, final String colorsXml,
            final String extrasXml) throws Exception {
        OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) {
            throw new IllegalStateException("openOutputStream 返回 null");
        }
        long total = 0;
        ZipOutputStream zip = new ZipOutputStream(out);
        try {
            // 颜色表放第一条：导入器按顺序扫，命中的条目整条读进内存即可
            if (colorsXml != null) {
                byte[] bytes = colorsXml.getBytes("UTF-8");
                writeEntry(zip, ThemePackImporter.COLORS_ENTRY, bytes);
                total += bytes.length;
            }
            // extras 表：模块自有的整份修改项，见 HookConfigExtras。放在图片前面，
            // 导入时它体积小、先读到就能先把配置铺好，再逐张补底图
            if (extrasXml != null && extrasXml.length() > 0) {
                byte[] bytes = extrasXml.getBytes("UTF-8");
                writeEntry(zip, ThemePackImporter.EXTRAS_ENTRY, bytes);
                total += bytes.length;
            }
            for (Map.Entry<String, byte[]> e : images.entrySet()) {
                writeEntry(zip, e.getKey(), e.getValue());
                total += e.getValue().length;
            }
            // finish 必须在 close 之前显式调：close 也会收尾，但显式调能第一时间把
            // 异常抛出来（写盘失败要在这一层就知道）
            zip.finish();
        } finally {
            try {
                zip.close();
            } catch (Throwable ignored) {
            }
        }
        return total;
    }

    /** 写一个 zip 条目（DEFLATED，与主题包本身的打包方式一致）。 */
    private static void writeEntry(final ZipOutputStream zip, final String name,
            final byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data, 0, data.length);
        zip.closeEntry();
    }

    /** 本进程（SystemUI）里的日志；与其它 hook 同一通道。 */
    private static void log(final String message) {
        de.robv.android.xposed.XposedBridge.log(TAG + ": " + message);
    }
}
