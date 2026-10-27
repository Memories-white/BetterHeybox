package com.better.heybox.liquidglass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

final class MiuixGlassTabBar extends com.example.liquidglass.LiquidGlassTabBar {

    private static final long CAPTURE_INTERVAL_MS = 32L;

    private static final long RETRY_INTERVAL_MS = 200L;

    private static final int MAX_FAST_RETRIES = 12;
    private static final long SLOW_RETRY_INTERVAL_MS = 500L;

    private static final boolean DEBUG_APPEARANCE = false;

    private static final int OVER_LIGHT_LUMA = 150;
    // SukiSU uses a GPU texture blur. This View-based port keeps the same
    // frosted look with a small backdrop buffer so updates stay inexpensive.
    private static final float BACKDROP_SCALE = 0.20f;

    private static final int GLASS_ALPHA = 255;
    private static final int SURFACE_BLEND_ALPHA = 144;

    private static final int FALLBACK_DARK = 0xFF1C1C1E;
    private static final int FALLBACK_LIGHT = 0xFFF7F7F8;

    private final float mDensity;

    private final RectF mBounds = new RectF();

    private final Matrix mShaderMatrix = new Matrix();
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTopDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap mBackdrop;
    private float mRadius;
    private long mLastCaptureAt;
    private boolean mCapturing;

    private int mDrawnViews;

    private int mCaptureFailures;

    private boolean mHasBackdrop;
    private boolean mOverLight;
    private boolean mDark;
    private int mLogs;

    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private View mInheritedLiquidDroplet;
    private Boolean mAppliedThemeDark;
    private WeakReference<View> mBackdropSourceRef = new WeakReference<>(null);

    MiuixGlassTabBar(Context context) {
        super(context, null, 0);
        mDensity = context.getResources().getDisplayMetrics().density;

        try {
            GlassConfig.load(context);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix tabbar config load failed", t);
        }
        mDark = initialDarkGuess();
        applyFallbackColor();
        suppressInheritedLiquidDroplet();
    }

    void setMiuixBackdropSource(View source) {
        mBackdropSourceRef = new WeakReference<>(source);
        resetCaptureState();
    }

    private boolean initialDarkGuess() {
        try {
            int mode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void applyFallbackColor() {
        int color = mDark ? FALLBACK_DARK : FALLBACK_LIGHT;
        try {
            color = com.better.heybox.ThemeUtils.surfaceColor(getContext());
        } catch (Throwable ignored) {
        }
        mFallbackPaint.setColor(color);
        mFallbackPaint.setAlpha(255);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        suppressInheritedLiquidDroplet();
        if (mPreDrawListener == null) {
            mPreDrawListener = () -> {
                maybeCapture();
                return true;
            };
            getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        }
        resetCaptureState();
    }

    private void resetCaptureState() {
        mHasBackdrop = false;
        mLastCaptureAt = 0L;
        mDrawnViews = 0;
        mCaptureFailures = 0;
        if (mBackdrop != null && !mBackdrop.isRecycled()) {
            mBackdrop.eraseColor(0);
        }
        applyFallbackColor();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mPreDrawListener != null) {
            try {
                getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            } catch (Throwable ignored) {
            }
            mPreDrawListener = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        suppressInheritedLiquidDroplet();
        mBounds.set(0f, 0f, w, h);
        mRadius = 0f;
        resetCaptureState();
    }

    private void ensureBackdrop(int w, int h) {
        int bw = Math.max(1, Math.round(w * BACKDROP_SCALE));
        int bh = Math.max(1, Math.round(h * BACKDROP_SCALE));
        if (mBackdrop == null || mBackdrop.isRecycled()
                || mBackdrop.getWidth() != bw || mBackdrop.getHeight() != bh) {
            if (mBackdrop != null && !mBackdrop.isRecycled()) {
                mBackdrop.recycle();
            }
            try {
                mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("miuix tabbar backdrop alloc failed", t);
                mBackdrop = null;
            }
        }
    }

    private void maybeCapture() {
        if (mCapturing || !isAttachedToWindow() || getVisibility() != VISIBLE) {
            return;
        }
        long now = SystemClock.uptimeMillis();

        long interval = CAPTURE_INTERVAL_MS;
        if (!mHasBackdrop) {
            interval = mCaptureFailures >= MAX_FAST_RETRIES
                    ? SLOW_RETRY_INTERVAL_MS : RETRY_INTERVAL_MS;
        }
        if (now - mLastCaptureAt < interval) {
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        mLastCaptureAt = now;
        mCapturing = true;
        try {
            ensureBackdrop(getWidth(), getHeight());
            if (mBackdrop == null) {
                onCaptureFailed();
                return;
            }
            View source = mBackdropSourceRef.get();
            if (source == null || !source.isAttachedToWindow()
                    || source.getWidth() <= 0 || source.getHeight() <= 0) {
                source = getRootView();
            }
            if (source == null) {
                onCaptureFailed();
                return;
            }
            int[] sourceLoc = new int[2];
            int[] selfLoc = new int[2];
            source.getLocationOnScreen(sourceLoc);
            getLocationOnScreen(selfLoc);
            float dx = selfLoc[0] - sourceLoc[0];
            float dy = selfLoc[1] - sourceLoc[1];

            mBackdrop.eraseColor(0);
            Canvas c = new Canvas(mBackdrop);
            c.save();
            c.scale(BACKDROP_SCALE, BACKDROP_SCALE);
            // Sample the exact screen rectangle occupied by the navigation bar.
            // The previous implementation shifted this rectangle upward by one
            // bar height, which made the glass show the page area above it.
            c.translate(-dx, -dy);
            if (source == getRootView()) {
                int vis = getVisibility();
                setVisibility(INVISIBLE);
                try {
                    source.draw(c);
                    mDrawnViews = 1;
                } finally {
                    setVisibility(vis);
                }
            } else {
                // Draw the app's page container directly. Capturing the decor root
                // can lose hardware-accelerated child layers and collapse to the
                // window surface color on some MIUI devices.
                source.draw(c);
                mDrawnViews = 1;
            }
            c.restore();

            if (mDrawnViews == 0) {
                mBackdrop.eraseColor(0);
                onCaptureFailed();
                return;
            }

            int luma = averageLuma();

            mDark = initialDarkGuess();
            applyFallbackColor();
            // The buffer is already downsampled to 20%; a small stack blur is
            // visually equivalent to a much larger screen-space blur without
            // the costly full-resolution refraction pipeline.
            StackBlur.blur(mBackdrop, 5);
            mHasBackdrop = true;
            mCaptureFailures = 0;
            updateAppearance(luma);
            invalidate();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix tabbar capture failed", t);
            onCaptureFailed();
        } finally {
            mCapturing = false;
        }
    }

    private void onCaptureFailed() {
        mCaptureFailures++;
        mHasBackdrop = false;
        applyFallbackColor();
        invalidate();
        if (mCaptureFailures == 1 || DEBUG_APPEARANCE) {
            LiquidGlassLog.log(Log.INFO, "miuix tabbar capture retry, failures=" + mCaptureFailures);
        }
    }

    private int averageLuma() {
        if (mBackdrop == null || mBackdrop.isRecycled()) {
            return 255;
        }
        int w = mBackdrop.getWidth();
        int h = mBackdrop.getHeight();
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 8);
        long sum = 0;
        int count = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = mBackdrop.getPixel(x, y);
                sum += (((px >> 16) & 0xFF) * 30 + ((px >> 8) & 0xFF) * 59
                        + (px & 0xFF) * 11) / 100;
                count++;
            }
        }
        return count == 0 ? 255 : (int) (sum / count);
    }

    private void updateAppearance(int luma) {
        boolean overLight = luma > OVER_LIGHT_LUMA;
        mOverLight = overLight;
        try {
            // MIUIX chrome follows the app theme only. Backdrop luminance is
            // still sampled for the frosted surface, but must not flip icon or
            // label colors like the liquid-glass renderer does.
            onAppearanceChanged(overLight);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix tabbar appearance failed", t);
        }

        if (DEBUG_APPEARANCE && mLogs++ < 20) {
            Log.i("BetterHeybox", "miuix tabbar overLight=" + overLight
                    + " avg=" + luma + " dark=" + mDark
                    + " w=" + getWidth() + " h=" + getHeight());
        }
    }

    @Override
    protected void onAppearanceChanged(boolean ignoredBackdropAppearance) {
        boolean dark = initialDarkGuess();
        mDark = dark;
        if (mAppliedThemeDark != null && mAppliedThemeDark == dark) {
            return;
        }
        mAppliedThemeDark = dark;
        super.onAppearanceChanged(!dark);
        LiquidGlassInstaller.applyMiuixChrome(this);
    }

    /** Re-applies stable MIUIX colors and restores a visible icon selection animation. */
    void onMiuixSelectionChanged() {
        mAppliedThemeDark = null;
        onAppearanceChanged(false);
        try {
            java.lang.reflect.Field tabsField =
                    com.example.liquidglass.LiquidGlassTabBar.class
                            .getDeclaredField("tabs");
            tabsField.setAccessible(true);
            Object value = tabsField.get(this);
            if (!(value instanceof java.util.List)) {
                return;
            }
            int selected = getSelectedIndex();
            java.util.List<?> holders = (java.util.List<?>) value;
            for (int i = 0; i < holders.size(); i++) {
                Object holder = holders.get(i);
                if (holder == null) {
                    continue;
                }
                ImageView icon = null;
                for (java.lang.reflect.Field field : holder.getClass().getDeclaredFields()) {
                    field.setAccessible(true);
                    Object child = field.get(holder);
                    if (child instanceof ImageView) {
                        icon = (ImageView) child;
                        break;
                    }
                }
                if (icon == null) {
                    continue;
                }
                boolean active = i == selected;
                icon.animate().cancel();
                icon.setSelected(active);
                icon.setActivated(active);
                icon.refreshDrawableState();
                if (!active) {
                    icon.setScaleX(1f);
                    icon.setScaleY(1f);
                    icon.setAlpha(1f);
                    continue;
                }
                icon.setScaleX(0.82f);
                icon.setScaleY(0.82f);
                icon.setAlpha(0.72f);
                icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(260L)
                        .setInterpolator(new OvershootInterpolator(1.35f))
                        .start();
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix icon selection animation failed", t);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mDark = initialDarkGuess();
        applyFallbackColor();
        if (mHasBackdrop && mBackdrop != null && !mBackdrop.isRecycled()) {
            mGlassPaint.setAlpha(GLASS_ALPHA);
            mGlassPaint.setFilterBitmap(true);
            canvas.drawBitmap(mBackdrop, null, mBounds, mGlassPaint);

            int surface = mDark ? FALLBACK_DARK : FALLBACK_LIGHT;
            try {
                surface = com.better.heybox.ThemeUtils.surfaceColor(getContext());
            } catch (Throwable ignored) {
            }
            mTintPaint.setColor(surface);
            mTintPaint.setAlpha(SURFACE_BLEND_ALPHA);
            canvas.drawRect(mBounds, mTintPaint);
            mTintPaint.setAlpha(255);
        } else {
            canvas.drawRect(mBounds, mFallbackPaint);
        }
        mTopDividerPaint.setColor(mDark ? 0x24FFFFFF : 0x18000000);
        mTopDividerPaint.setStrokeWidth(Math.max(1f, 0.5f * mDensity));
        canvas.drawLine(0f, 0f, getWidth(), 0f, mTopDividerPaint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        suppressInheritedLiquidDroplet();
        super.dispatchDraw(canvas);
    }

    /**
     * The superclass is retained only for its tab bookkeeping API. Its private
     * liquid selection droplet must never participate in MIUIX rendering.
     */
    private void suppressInheritedLiquidDroplet() {
        try {
            View droplet = mInheritedLiquidDroplet;
            if (droplet == null) {
                java.lang.reflect.Field field =
                        com.example.liquidglass.LiquidGlassTabBar.class
                                .getDeclaredField("droplet");
                field.setAccessible(true);
                Object value = field.get(this);
                if (value instanceof View) {
                    droplet = (View) value;
                    mInheritedLiquidDroplet = droplet;
                }
            }
            if (droplet != null) {
                droplet.animate().cancel();
                droplet.setAlpha(0f);
                droplet.setVisibility(View.GONE);
                droplet.setEnabled(false);
                droplet.setClickable(false);
            }
        } catch (Throwable t) {
            if (mLogs++ < 1) {
                LiquidGlassLog.logErr("miuix failed to suppress inherited liquid droplet", t);
            }
        }
    }

}
