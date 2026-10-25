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
    private static final int SURFACE_BLEND_ALPHA = 208;

    private static final int FALLBACK_DARK = 0xFF1C1C1E;
    private static final int FALLBACK_LIGHT = 0xFFF7F7F8;

    private final float mDensity;

    private final RectF mBounds = new RectF();

    private final Matrix mShaderMatrix = new Matrix();
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTopDividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean mUseAboveOnly;
    private Bitmap mBackdrop;
    private float mRadius;
    private long mLastCaptureAt;
    private boolean mCapturing;

    private int mDrawnViews;

    private int mCaptureFailures;

    private boolean mTriedRootFallback;

    private boolean mHasBackdrop;
    private boolean mOverLight;
    private boolean mDark;
    private int mLogs;

    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private View mInheritedLiquidDroplet;

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
        mTriedRootFallback = false;
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
            View root = getRootView();
            if (root == null) {
                onCaptureFailed();
                return;
            }
            int[] rootLoc = new int[2];
            int[] selfLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            getLocationOnScreen(selfLoc);
            float dx = selfLoc[0] - rootLoc[0];
            float dy = selfLoc[1] - rootLoc[1];

            float shift = getHeight() > 0 ? getHeight() : 0f;
            mUseAboveOnly = dy >= shift;

            mBackdrop.eraseColor(0);
            Canvas c = new Canvas(mBackdrop);
            c.save();
            c.scale(BACKDROP_SCALE, BACKDROP_SCALE);
            c.translate(-dx, -(mUseAboveOnly ? dy - shift : dy));
            int vis = getVisibility();
            setVisibility(INVISIBLE);
            try {

                drawAboveSiblings(root, c);
            } finally {
                setVisibility(vis);
                c.restore();
            }

            if (mDrawnViews == 0) {
                if (!mTriedRootFallback) {
                    mTriedRootFallback = true;
                    captureWholeRoot(root, c, shift);
                }
                if (mDrawnViews == 0) {
                    mBackdrop.eraseColor(0);
                    onCaptureFailed();
                    return;
                }
            }

            int luma = averageLuma();

            int above = lumaAbove();
            mDark = above <= OVER_LIGHT_LUMA;
            applyFallbackColor();
            // The buffer is already downsampled to 20%; a small stack blur is
            // visually equivalent to a much larger screen-space blur without
            // the costly full-resolution refraction pipeline.
            StackBlur.blur(mBackdrop, 4);
            mHasBackdrop = true;
            mCaptureFailures = 0;
            mTriedRootFallback = false;
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
        mTriedRootFallback = false;
        applyFallbackColor();
        invalidate();
        if (mCaptureFailures == 1 || DEBUG_APPEARANCE) {
            LiquidGlassLog.log(Log.INFO, "miuix tabbar capture retry, failures=" + mCaptureFailures);
        }
    }

    private void captureWholeRoot(View root, Canvas canvas, float shift) {

        if (!mUseAboveOnly) {
            return;
        }
        int vis = getVisibility();
        setVisibility(INVISIBLE);
        try {
            float selfTopCanvas = shift;
            if (selfTopCanvas <= 0f) {
                return;
            }
            canvas.save();
            canvas.clipRect(0f, 0f, getWidth(), selfTopCanvas);
            root.draw(canvas);
            canvas.restore();
            mDrawnViews = 1;
        } catch (Throwable t) {

            LiquidGlassLog.logErr("miuix tabbar root fallback failed", t);
        } finally {
            setVisibility(vis);
        }
    }

    private void drawAboveSiblings(View root, Canvas canvas) {
        mDrawnViews = 0;
        if (!(root instanceof android.view.ViewGroup)) {
            return;
        }
        View child = this;
        java.util.ArrayList<View> chain = new java.util.ArrayList<>();
        while (child != null && child != root) {
            chain.add(child);
            android.view.ViewParent p = child.getParent();
            child = p instanceof View ? (View) p : null;
        }
        if (chain.isEmpty() || child != root) {
            return;
        }
        java.util.Collections.reverse(chain);
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        int[] selfLoc = new int[2];
        getLocationOnScreen(selfLoc);
        int selfTop = selfLoc[1];

        int aboveTop = Math.max(0, selfTop - getHeight());
        for (int level = 0; level < chain.size(); level++) {
            View current = chain.get(level);
            View next = level + 1 < chain.size() ? chain.get(level + 1) : null;
            int count;
            try {
                count = group.getChildCount();
            } catch (Throwable t) {
                return;
            }
            for (int i = 0; i < count; i++) {
                View c = null;
                try {
                    c = group.getChildAt(i);
                } catch (Throwable ignored) {
                    continue;
                }
                if (c == null || c == next || c == current) {
                    continue;
                }
                if (!visibleInTree(c, group)) {
                    continue;
                }
                int[] loc = new int[2];
                c.getLocationOnScreen(loc);

                if (loc[1] + c.getHeight() <= selfTop && loc[1] + c.getHeight() > aboveTop) {
                    try {
                        canvas.save();
                        canvas.clipRect(0, aboveTop, getWidth(), selfTop);
                        c.draw(canvas);
                        canvas.restore();
                        mDrawnViews++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (!(current instanceof android.view.ViewGroup)) {
                return;
            }
            group = (android.view.ViewGroup) current;
        }
    }

    private boolean visibleInTree(View v, View ancestor) {
        View cur = v;
        while (cur != null) {
            if (cur.getVisibility() != VISIBLE) {
                return false;
            }
            if (cur == ancestor) {
                return true;
            }
            android.view.ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private int lumaAbove() {
        if (mBackdrop == null || mBackdrop.isRecycled()) {
            return 255;
        }
        int w = mBackdrop.getWidth();
        int h = mBackdrop.getHeight();
        int limit = Math.max(1, Math.round(h * 0.30f));
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, limit / 6);
        long sum = 0;
        int count = 0;
        for (int y = 0; y < limit; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = mBackdrop.getPixel(x, y);
                sum += (((px >> 16) & 0xFF) * 30 + ((px >> 8) & 0xFF) * 59
                        + (px & 0xFF) * 11) / 100;
                count++;
            }
        }
        return count == 0 ? 255 : (int) (sum / count);
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

            onAppearanceChanged(overLight);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix tabbar appearance failed", t);
        }

        if (DEBUG_APPEARANCE && mLogs++ < 20) {
            Log.i("BetterHeybox", "miuix tabbar overLight=" + overLight
                    + " avg=" + luma + " dark=" + mDark + " aboveOnly=" + mUseAboveOnly
                    + " w=" + getWidth() + " h=" + getHeight());
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
