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

    private static final long CAPTURE_INTERVAL_MS = 120L;

    private static final long RETRY_INTERVAL_MS = 200L;

    private static final int MAX_FAST_RETRIES = 12;
    private static final long SLOW_RETRY_INTERVAL_MS = 500L;

    private static final boolean DEBUG_APPEARANCE = false;

    private static final int OVER_LIGHT_LUMA = 150;
    private static final float BACKDROP_SCALE = 1.0f;

    private static final int GLASS_ALPHA = 168;

    private static final int LIGHT_TINT = 0x24FFFFFF;
    private static final int DARK_TINT = 0x24101214;

    private static final int FALLBACK_DARK = 0x38000000;
    private static final int FALLBACK_LIGHT = 0x40FFFFFF;

    private static final boolean PILL_BELOW_DROPLET = true;

    private final float mDensity;

    private final GlassSelectionPill mPill = new GlassSelectionPill();

    private boolean mPillBroken;

    private final RectF mBounds = new RectF();

    private final Matrix mShaderMatrix = new Matrix();
    private final Paint mGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        mPill.setDensity(mDensity);
        reloadPillConfig();
    }

    private boolean initialDarkGuess() {
        try {
            int darkAlpha = Color.alpha(GlassConfig.darkTint());
            int lightAlpha = Color.alpha(GlassConfig.lightTint());
            if (darkAlpha > lightAlpha + 24) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void applyFallbackColor() {
        int color;
        try {
            color = mDark ? GlassConfig.darkTint() : GlassConfig.lightTint();
        } catch (Throwable t) {
            color = 0;
        }
        if (Color.alpha(color) == 0) {
            color = mDark ? FALLBACK_DARK : FALLBACK_LIGHT;
        }
        mFallbackPaint.setColor(color);
        mFallbackPaint.setAlpha(Math.min(Color.alpha(color), 0x99));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPreDrawListener == null) {
            mPreDrawListener = () -> {

                if (mPill.pollSelection(this)) {
                    reloadPillConfig();
                    postInvalidateOnAnimation();
                }
                maybeCapture();
                return true;
            };
            getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        }

        mPill.reset();
        reloadPillConfig();

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
        mPill.reset();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0f, 0f, w, h);
        mRadius = LiquidGlassInstaller.barCornerRadiusPx(h, mDensity);
        ensureBackdrop(w, h);
        mPill.reset();

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

        reloadPillConfig();
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
            MiuixGlassPipeline.process(mBackdrop, mDensity, mDark);
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

        if (mHasBackdrop && mBackdrop != null && !mBackdrop.isRecycled()) {
            int bw = mBackdrop.getWidth();
            int bh = mBackdrop.getHeight();
            if (mUseAboveOnly && bh > getHeight() && getHeight() > 0) {

                BitmapShader shader = new BitmapShader(
                        mBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);

                mShaderMatrix.setScale(getWidth() / (float) bw, 1f);
                shader.setLocalMatrix(mShaderMatrix);
                mGlassPaint.setShader(shader);
            } else {
                BitmapShader shader = new BitmapShader(
                        mBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                mShaderMatrix.setScale(getWidth() / (float) bw, getHeight() / (float) bh);
                shader.setLocalMatrix(mShaderMatrix);
                mGlassPaint.setShader(shader);
            }

            mGlassPaint.setAlpha(GLASS_ALPHA);
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mGlassPaint);
            mGlassPaint.setShader(null);
            mTintPaint.setColor(mDark ? DARK_TINT : LIGHT_TINT);
            mTintPaint.setAlpha(Color.alpha(mTintPaint.getColor()));
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mTintPaint);
            mTintPaint.setAlpha(255);
        } else {
            canvas.drawRoundRect(mBounds, mRadius, mRadius, mFallbackPaint);
        }
        MiuixGlassPipeline.drawEdgeHighlight(canvas, mBounds, mRadius, mDensity, mDark);
        if (PILL_BELOW_DROPLET) {
            drawSelectionPill(canvas);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!PILL_BELOW_DROPLET) {
            drawSelectionPill(canvas);
        }
        super.dispatchDraw(canvas);
    }

    private void drawSelectionPill(Canvas canvas) {
        if (mPillBroken) {
            return;
        }
        try {
            if (mPill.draw(canvas, this, mDark)) {

                postInvalidateOnAnimation();
            }
        } catch (Throwable t) {
            mPillBroken = true;
            LiquidGlassLog.logErr("miuix tabbar selection pill failed", t);
        }
    }

    private void reloadPillConfig() {
        try {
            mPill.reloadConfig();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("miuix tabbar pill config failed", t);
        }
    }
}