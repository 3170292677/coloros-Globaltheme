package com.thexiaoxia43.coloros.theme;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;

/**
 * 把系统磁贴底图包一层，给用户「可见度」加一个持久乘子。
 *
 * <p>系统每次淡入淡出 / 开关状态切换都会对底图调 {@code setAlpha(...)}，
 * 直接改系统底图的 alpha 会被它反复刷回满值（表现：可见度调了没用 / 一收起展开就回满）。
 * 这里把系统传来的 alpha 当成「系统动画 alpha」，跟用户可见度相乘后再真正下发给被包裹的
 * 系统底图；用户可见度走 {@link #setUserPct(int)}，系统碰不到，可见度就能一直留住。
 * 被包裹的系统底图原样绘制（含它自己的配色与系统裁剪），所以磁贴观感完全不变。</p>
 */
final class AlphaProxyDrawable extends Drawable {

    private final Drawable wrapped;
    /** 用户设的可见度（0–200），系统 alpha 乘它后下发。 */
    private int userPct = 100;
    /** 系统驱动的 alpha（0–255），存下来等用户乘子一起下发。 */
    private int systemAlpha = 255;

    AlphaProxyDrawable(Drawable wrapped) {
        this.wrapped = wrapped;
        if (wrapped != null) {
            this.systemAlpha = wrapped.getAlpha();
            wrapped.setBounds(getBounds());
        }
    }

    /** 用户可见度（0–{@link TileBackgroundDrawable#MAX_USER_ALPHA_PCT}），系统值乘它后下发。 */
    void setUserPct(int pct) {
        if (pct < 0) {
            pct = 0;
        }
        if (pct > TileBackgroundDrawable.MAX_USER_ALPHA_PCT) {
            pct = TileBackgroundDrawable.MAX_USER_ALPHA_PCT;
        }
        if (pct == userPct) {
            return;
        }
        userPct = pct;
        apply();
        invalidateSelf();
    }

    int getUserPct() {
        return userPct;
    }

    private void apply() {
        if (wrapped == null) {
            return;
        }
        int target = systemAlpha * userPct / 100;
        if (target > 255) {
            target = 255;
        }
        wrapped.setAlpha(target);
    }

    @Override
    public void setAlpha(int alpha) {
        systemAlpha = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        apply();
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return systemAlpha;
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect bounds) {
        if (wrapped != null) {
            wrapped.setBounds(bounds);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (wrapped != null) {
            wrapped.draw(canvas);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (wrapped != null) {
            wrapped.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return wrapped == null ? android.graphics.PixelFormat.TRANSLUCENT : wrapped.getOpacity();
    }

    @Override
    public void setChangingConfigurations(int configs) {
        if (wrapped != null) {
            wrapped.setChangingConfigurations(configs);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return wrapped == null ? 0 : wrapped.getChangingConfigurations();
    }

    @Override
    public boolean setState(int[] stateSet) {
        return wrapped != null && wrapped.setState(stateSet);
    }

    @Override
    public int[] getState() {
        return wrapped == null ? new int[0] : wrapped.getState();
    }

    @Override
    public void jumpToCurrentState() {
        if (wrapped != null) {
            wrapped.jumpToCurrentState();
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return wrapped == null ? -1 : wrapped.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return wrapped == null ? -1 : wrapped.getIntrinsicHeight();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        return wrapped != null && wrapped.setVisible(visible, restart);
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        if (wrapped != null) {
            wrapped.setFilterBitmap(filter);
        }
    }

    @Override
    public ConstantState getConstantState() {
        return wrapped == null ? null : wrapped.getConstantState();
    }

    @Override
    public Drawable mutate() {
        if (wrapped != null) {
            wrapped.mutate();
        }
        return this;
    }
}
