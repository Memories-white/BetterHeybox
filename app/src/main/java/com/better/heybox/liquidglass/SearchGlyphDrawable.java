package com.better.heybox.liquidglass;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class SearchGlyphDrawable extends Drawable {

    private static final float CONTENT_DP = 24f;

    private static final float STROKE_DP = 2f;

    private static final int BASE_COLOR = 0xFFFFFFFF;

    private static final float LENS_CX = 0.4375f;
    private static final float LENS_CY = 0.4375f;
    private static final float LENS_R_OUTER = 0.271f;
    private static final float HANDLE_END = 0.855f;
    private static final float SQRT_HALF = 0.70710677f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private float mDensity = 1f;

    private int mIntrinsicSize;

    private int mAlpha = 255;

    private ColorStateList mTintList;
    private boolean mGeometryValid;

    private SearchGlyphDrawable() {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setColor(BASE_COLOR);
    }

    static Drawable create(Context context) {
        SearchGlyphDrawable d = new SearchGlyphDrawable();
        float density = 1f;
        if (context != null) {
            try {
                density = context.getResources().getDisplayMetrics().density;
            } catch (Throwable ignored) {
            }
        }
        if (density <= 0f) {
            density = 1f;
        }
        d.mDensity = density;
        d.mIntrinsicSize = Math.round(CONTENT_DP * density);
        return d;
    }

    @Override
    public int getIntrinsicWidth() {
        return mIntrinsicSize;
    }

    @Override
    public int getIntrinsicHeight() {
        return mIntrinsicSize;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mGeometryValid = false;
        if (bounds == null) {
            return;
        }
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) {
            return;
        }
        float size = Math.min(w, h);
        float ox = bounds.left + (w - size) * 0.5f;
        float oy = bounds.top + (h - size) * 0.5f;

        float stroke = STROKE_DP * mDensity;
        float refSize = CONTENT_DP * mDensity;
        if (refSize > 0f && size < refSize) {
            stroke *= size / refSize;
        }
        if (stroke <= 0f) {
            stroke = 1f;
        }
        mPaint.setStrokeWidth(stroke);

        float cx = ox + size * LENS_CX;
        float cy = oy + size * LENS_CY;

        float r = size * LENS_R_OUTER - stroke * 0.5f;
        if (r <= 0f) {
            return;
        }
        mPath.reset();
        mPath.addCircle(cx, cy, r, Path.Direction.CW);

        float inner = r * SQRT_HALF;
        mPath.moveTo(cx + inner, cy + inner);
        mPath.lineTo(ox + size * HANDLE_END, oy + size * HANDLE_END);
        mGeometryValid = true;
    }

    @Override
    public void draw(Canvas canvas) {
        if (!mGeometryValid) {
            return;
        }
        int color = mTintList != null
                ? mTintList.getColorForState(getState(), BASE_COLOR)
                : BASE_COLOR;

        int alpha = Color.alpha(color) * mAlpha / 255;
        mPaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
        canvas.drawPath(mPath, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        int clamped = alpha < 0 ? 0 : (alpha > 255 ? 255 : alpha);
        if (clamped == mAlpha) {
            return;
        }
        mAlpha = clamped;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return mPaint.getColorFilter();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mTintList = tint;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return mTintList != null && mTintList.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mTintList != null && mTintList.isStateful()) {
            invalidateSelf();
            return true;
        }
        return false;
    }

    @Override
    public int getOpacity() {

        return PixelFormat.TRANSLUCENT;
    }
}
