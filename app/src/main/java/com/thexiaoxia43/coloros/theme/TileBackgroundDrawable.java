package com.thexiaoxia43.coloros.theme;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 用于替换 OPlus 主题磁贴底图的圆角底图。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *   <li>纯色：使用配置的 ARGB 颜色填充圆角矩形；</li>
 *   <li>图片：把自定义图片按“居中裁剪”的方式绘制进圆角矩形。</li>
 * </ul>
 *
 * <p>圆角半径传负值表示“跟随磁贴形状”：按绘制区域短边的一半取圆角，
 * 1×1 磁贴因此保持圆形、2×1 卡片保持胶囊形，不会把圆形磁贴画成方块。
 * 非负半径走 {@link SmoothCorners} 的系统同款「连续圆角」，角上才能跟系统重合。</p>
 *
 * <p>可见度用 {@code alphaPct}（0–{@link #MAX_USER_ALPHA_PCT}）单独控制：纯色模式下与颜色自带的 alpha
 * 相乘，图片模式下直接决定图片的可见度。超过 100 表示叠两层。</p>
 *
 * <p><b>两个 alpha 是分开的，这是为了不被系统覆盖。</b>
 * 系统背景层 {@code ScrimView} 每驱动一次淡入淡出都会执行
 * {@code mDrawable.setAlpha((int) (mViewAlpha * 255))}，也就是不停地往
 * {@link #setAlpha(int)} 里塞它自己的值。如果把用户设的可见度也放在那里，
 * 就会被系统反复刷回满值——表现就是「可见度改不了」。所以这里：</p>
 * <ul>
 *   <li>{@link #setAlpha(int)}：系统驱动的 alpha（动画进度），存 {@code systemAlpha}；</li>
 *   <li>{@link #setUserAlphaPct(int)}：用户设的可见度，存 {@code userAlphaPct}，系统碰不到；</li>
 *   <li>真正画的时候两者相乘，见 {@link #draw(Canvas)}。</li>
 * </ul>
 */
public class TileBackgroundDrawable extends Drawable {

    /** 用户可见度上限（%）：200 表示最多再叠一层把不透明度补满。 */
    public static final int MAX_USER_ALPHA_PCT = 200;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final RectF boundsRect = new RectF();
    private final Rect srcRect = new Rect();
    private final Matrix shaderMatrix = new Matrix();

    private final int color;
    /** 圆角像素半径；负值 = 跟随形状（短边一半）。 */
    private final float radiusPx;
    /**
     * 逐角圆角半径（8 个 float）。非空时<b>忽略 {@link #radiusPx} / {@link #radiusRatio}</b>，
     * 直接用 {@code Path.addRoundRect(rect, radii, ...)} 画正圆弧逐角。
     *
     * <p>用于通知分组连体卡场景：子行需要「首行顶角圆、底角直；末行顶角直、底角圆；
     * 中间行四角直」，统一圆角做不到。必须原生支持，不能靠外层 canvas.clipPath
     * ——硬件加速下 clipPath 会被静默忽略（RoundedClipDrawable 就是这么废的）。</p>
     */
    private final float[] perCornerRadii;
    /**
     * 主题包素材自带的圆角比例（圆角像素 ÷ 素材宽度）；0 = 不用这条。
     *
     * <p>素材尺寸和绘制区域尺寸不保证 1:1，所以只能存比例、画的时候按当前
     * {@link #boundsRect} 的宽度现算。优先级高于 {@link #radiusPx}。</p>
     */
    private final float radiusRatio;
    private final Bitmap bitmap;
    /**
     * 有图片时要不要把 {@link #color} 先铺一层在下面，见构造器同名字段。
     */
    private final boolean colorUnderImage;
    /**
     * 强制绘制尺寸（像素）。大于 0 时<b>忽略系统给的 bounds</b>，始终按这个尺寸画。
     *
     * <p>系统背景层 {@code ScrimView} 会给 drawable 设一个自己的 {@code mDrawableBounds}，
     * 那个矩形往往比屏幕小（只覆盖面板内容区），照它画就会变成「小一圈的一层」。
     * 传屏幕尺寸进来，就能保证不管系统怎么设，画出来都是整屏。</p>
     */
    private final int fillWidth;
    private final int fillHeight;

    /** 用户设的可见度（0–{@link #MAX_USER_ALPHA_PCT}），只由 {@link #setUserAlphaPct(int)} 改，系统动不到。 */
    private int userAlphaPct = 100;
    /** 系统驱动的 alpha（0–255），由 {@link #setAlpha(int)} 写，画的时候与用户值相乘。 */
    private int systemAlpha = 255;

    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap) {
        this(color, radiusPx, bitmap,
                bitmap != null ? Math.round(Color.alpha(color) * 100f / 255f) : 100);
    }

    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap, int alphaPct) {
        this(color, radiusPx, bitmap, alphaPct, 0, 0);
    }

    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap, int alphaPct,
                                  int fillWidth, int fillHeight) {
        this(color, radiusPx, bitmap, alphaPct, fillWidth, fillHeight, 0f);
    }

    /**
     * @param radiusRatio 主题包素材自带的圆角比例（圆角像素 ÷ 素材宽度）；0 = 不用。
     *                    大于 0 时忽略 {@code radiusPx}，按绘制宽度现算像素值，
     *                    见 {@link TileLongPressConfig#materialCornerRatio}。
     */
    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap, int alphaPct,
                                  int fillWidth, int fillHeight, float radiusRatio) {
        this(color, radiusPx, bitmap, alphaPct, fillWidth, fillHeight, radiusRatio, false);
    }

    /**
     * @param colorUnderImage 有图片时要不要把 {@code color} 先铺一层在下面。
     *
     * <p>默认 {@code false}，也就是老行为「有图就不画颜色」——磁贴那条链路传进来的
     * 颜色只是纯色模式的填充，图片模式下不需要它。控制中心背景要的是「颜色垫底、
     * 图片盖在上面」，所以它显式传 {@code true}：图片有透明处就透出底色，
     * 没设图时看到的直接就是那块颜色。</p>
     */
    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap, int alphaPct,
                                  int fillWidth, int fillHeight, float radiusRatio,
                                  boolean colorUnderImage) {
        this(color, radiusPx, bitmap, alphaPct, fillWidth, fillHeight, radiusRatio,
                colorUnderImage, null);
    }

    /**
     * 全参数构造器，含逐角圆角。
     *
     * @param perCornerRadii 8 个 float 的逐角圆角半径（左上XY、右上XY、右下XY、左下XY）；
     *                       传 null 表示用 radiusPx 统一圆角。
     */
    public TileBackgroundDrawable(int color, float radiusPx, Bitmap bitmap, int alphaPct,
                                  int fillWidth, int fillHeight, float radiusRatio,
                                  boolean colorUnderImage, float[] perCornerRadii) {
        this.color = color;
        this.radiusPx = radiusPx;
        this.radiusRatio = radiusRatio > 0f ? radiusRatio : 0f;
        this.bitmap = bitmap;
        this.fillWidth = fillWidth;
        this.fillHeight = fillHeight;
        this.colorUnderImage = colorUnderImage;
        this.perCornerRadii = perCornerRadii;
        this.userAlphaPct = clampPct(alphaPct);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(userAlphaPct * 255 / 100);
    }

    @Override
    public void draw(Canvas canvas) {
        // 目标不透明度 = 系统动画 alpha × 用户设的可见度。
        // 可见度最高 200%，乘出来会超过 255，而 Paint 的 alpha 上限就是 255，
        // 所以超出的部分靠「再叠一遍」补：两层叠完的不透明度是 1-(1-a)(1-b)。
        final int target = systemAlpha * userAlphaPct / 100;
        final int first = Math.min(255, target);
        final int second = target - first;
        if (first > 0) {
            drawLayer(canvas, first);
        }
        if (second > 0) {
            drawLayer(canvas, second);
        }
    }

    /** 按给定 alpha 画一遍这一层。 */
    private void drawLayer(Canvas canvas, int alpha) {
        paint.setAlpha(alpha);
        if (fillWidth > 0 && fillHeight > 0) {
            // 忽略系统给的局部 bounds，直接铺满整屏
            boundsRect.set(0f, 0f, fillWidth, fillHeight);
        } else {
            boundsRect.set(getBounds());
        }
        if (boundsRect.isEmpty()) {
            return;
        }

        // 构造裁剪路径：perCornerRadii 优先（逐角正圆弧），否则走原来的 SmoothCorners
        if (perCornerRadii != null && perCornerRadii.length >= 8) {
            clipPath.reset();
            clipPath.addRoundRect(boundsRect, perCornerRadii, Path.Direction.CW);
        } else {
            final float radius;
            final float weight;
            if (radiusRatio > 0f) {
                radius = radiusRatio * boundsRect.width();
                weight = SmoothCorners.weight();
            } else if (radiusPx >= 0f) {
                radius = radiusPx;
                weight = SmoothCorners.weight();
            } else {
                radius = Math.min(boundsRect.width(), boundsRect.height()) / 2f;
                weight = 0f;
            }
            SmoothCorners.buildPath(clipPath, boundsRect.left, boundsRect.top,
                    boundsRect.right, boundsRect.bottom, radius, weight);
        }

        final boolean hasBitmap = bitmap != null && !bitmap.isRecycled();
        // 颜色垫底：没有图片时它就是背景本身；有图片而 colorUnderImage 打开时，
        // 图片的透明处会透出它（控制中心背景的「颜色 + 底图」就是这么叠的）
        if (!hasBitmap || colorUnderImage) {
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(alpha * Color.alpha(color) / 255);
            canvas.drawPath(clipPath, paint);
        }
        if (hasBitmap) {
            // 用 BitmapShader + drawPath 直接画，canvas.clipPath 在硬件加速下会被静默忽略
            final float bw = bitmap.getWidth();
            final float bh = bitmap.getHeight();
            // 等比居中裁剪：保证铺满且不变形（磁贴/通知底图都用这个）。
            final float scale = Math.max(boundsRect.width() / bw, boundsRect.height() / bh);
            final float sw = bw * scale;
            final float sh = bh * scale;
            shaderMatrix.reset();
            shaderMatrix.setScale(scale, scale);
            // 居中裁剪：scale 后 bitmap 尺寸 ≥ bounds，左上角偏移负数 = 居中
            shaderMatrix.postTranslate((boundsRect.width() - sw) / 2f,
                    (boundsRect.height() - sh) / 2f);
            BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setLocalMatrix(shaderMatrix);
            paint.setShader(shader);
            paint.setAlpha(alpha);
            canvas.drawPath(clipPath, paint);
            paint.setShader(null);
        }
    }

    /**
     * 系统驱动的 alpha（0–255）。
     *
     * <p>系统背景层会反复用这个入口塞它的动画进度，所以这里<b>只存不画</b>，
     * 用户设的可见度不会被它冲掉。</p>
     */
    @Override
    public void setAlpha(int alpha) {
        systemAlpha = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        invalidateSelf();
    }

    /** 系统读这个值判断「这一层要不要画」，所以返回系统 alpha 而不是合并值。 */
    @Override
    public int getAlpha() {
        return systemAlpha;
    }

    /**
     * 用户设的可见度（0–{@link #MAX_USER_ALPHA_PCT}）。
     *
     * <p>与系统 alpha 分开存，拖动可见度滑杆时调这个即可，不需要重建 drawable。
     * 超过 100 的部分表示「比满不透明还要再压一层」，见 {@link #draw(Canvas)}。</p>
     */
    public void setUserAlphaPct(int pct) {
        int value = clampPct(pct);
        if (value == userAlphaPct) {
            return;
        }
        userAlphaPct = value;
        invalidateSelf();
    }

    public int getUserAlphaPct() {
        return userAlphaPct;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static int clampPct(int value) {
        if (value < 0) {
            return 0;
        }
        return value > MAX_USER_ALPHA_PCT ? MAX_USER_ALPHA_PCT : value;
    }
}
