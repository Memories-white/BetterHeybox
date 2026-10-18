package com.better.heybox.liquidglass;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

final class GlassSelectionPill {

    private static final float INSET_DP_X = 3.5f;

    private static final float INSET_DP_Y = 4f;

    private static final float VERTICAL_INSET_RATIO = 0.12f;

    private static final float RADIUS_DP = 13f;

    private static final float DARK_ALPHA_SCALE = 1.5f;

    private static final int DARK_ALPHA_CAP_PCT = 98;

    private static final float DARK_WHITE_MIX = 0.08f;

    private static final long ANIM_MS = 200L;

    private static final float TARGET_EPSILON = 0.5f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mPill = new RectF();

    private final RectF mTarget = new RectF();

    private float mDensity;
    private int mInsetX;
    private int mInsetY;

    private int mConfiguredTint = 0x29F2F2F2;

    private int mSelectAlphaPct = 100;

    private boolean mTintFollowsConfig;

    private int mCachedTint;
    private boolean mCachedDark;
    private int mCachedFailCase = -1;

    private float mStartL;
    private float mStartT;
    private float mStartR;
    private float mStartB;
    private long mStartAt;
    private boolean mAnimating;

    private boolean mPlaced;

    private int mSelected = Integer.MIN_VALUE;

    private View mRow;
    private View mTab;
    private int mTabIndex = Integer.MIN_VALUE;
    private int mRowChildCount = -1;

    GlassSelectionPill() {
    }

    void setDensity(float density) {
        if (density <= 0f) {
            density = 1f;
        }
        if (density == mDensity && mInsetX > 0) {
            return;
        }
        mDensity = density;
        mInsetX = Math.max(1, Math.round(INSET_DP_X * density));
        mInsetY = Math.max(1, Math.round(INSET_DP_Y * density));
        mPlaced = false;
    }

    void reloadConfig() {
        mConfiguredTint = GlassConfig.tabSelectTint();
        mSelectAlphaPct = GlassConfig.tabSelectAlphaPct;
        mTintFollowsConfig = GlassConfig.tabSelectFollowConfig();
        mCachedFailCase = -1;
    }

    void reset() {
        mPlaced = false;
        mAnimating = false;
        mRow = null;
        mTab = null;
        mTabIndex = Integer.MIN_VALUE;
        mRowChildCount = -1;
    }

    boolean pollSelection(MiuixGlassTabBar bar) {
        int selected;
        try {
            selected = bar.getSelectedIndex();
        } catch (Throwable ignored) {
            return false;
        }
        if (selected != mSelected) {
            mSelected = selected;
            mTab = null;
            mTabIndex = Integer.MIN_VALUE;
            return true;
        }
        return false;
    }

    boolean draw(Canvas canvas, MiuixGlassTabBar bar, boolean dark) {
        int selected;
        try {
            selected = bar.getSelectedIndex();
        } catch (Throwable ignored) {
            return mAnimating;
        }
        if (selected != mSelected) {
            mSelected = selected;
        }
        if (selected < 0) {

            mPlaced = false;
            mAnimating = false;
            return mAnimating;
        }
        long now = SystemClock.uptimeMillis();
        if (!computeTarget(bar, selected)) {

            mPlaced = false;
            mAnimating = false;
            return mAnimating;
        }
        if (!mPlaced) {

            mPill.set(mTarget);
            mPlaced = true;
            mAnimating = false;
        } else if (!mAnimating && targetMoved()) {
            beginAnim(now);
        }
        if (mAnimating) {
            advanceAnim(now);
        }
        float w = mPill.width();
        float h = mPill.height();
        if (w <= 0f || h <= 0f) {
            return mAnimating;
        }
        float radius = Math.min(RADIUS_DP * mDensity, Math.min(w, h) * 0.5f);
        mPaint.setColor(tintFor(dark));
        canvas.drawRoundRect(mPill, radius, radius, mPaint);
        return mAnimating;
    }

    private boolean computeTarget(MiuixGlassTabBar bar, int selected) {
        View row = resolveRow(bar);
        if (!(row instanceof ViewGroup)) {
            return false;
        }
        ViewGroup rowGroup = (ViewGroup) row;
        int count = rowGroup.getChildCount();
        if (count != mRowChildCount) {

            mRowChildCount = count;
            mPlaced = false;
        }
        View tab = resolveTab(rowGroup, selected);
        if (tab == null || tab.getWidth() <= 0 || tab.getHeight() <= 0) {
            return false;
        }
        int bw = bar.getWidth();
        int bh = bar.getHeight();
        if (bw <= 0 || bh <= 0) {
            return false;
        }
        int rowLeft = row.getLeft();
        int rowTop = row.getTop();
        float left = rowLeft + tab.getLeft() + mInsetX;
        float right = rowLeft + tab.getRight() - mInsetX;

        int barHeight = Math.max(1, bh);
        int insetY = Math.max(mInsetY, Math.round(barHeight * VERTICAL_INSET_RATIO));
        float top = rowTop + tab.getTop() + insetY;
        float bottom = rowTop + tab.getBottom() - insetY;

        if (right - left < 1f) {
            float cx = (left + right) * 0.5f;
            left = cx - 0.5f;
            right = cx + 0.5f;
        }
        if (bottom - top < 1f) {
            float cy = (top + bottom) * 0.5f;
            top = cy - 0.5f;
            bottom = cy + 0.5f;
        }

        if (left < 0f) {
            left = 0f;
        }
        if (top < 0f) {
            top = 0f;
        }
        if (right > bw) {
            right = bw;
        }
        if (bottom > bh) {
            bottom = bh;
        }
        if (right <= left || bottom <= top) {
            return false;
        }
        mTarget.set(left, top, right, bottom);
        return true;
    }

    private View resolveRow(ViewGroup bar) {
        View cached = mRow;
        if (cached != null && cached.getParent() == bar
                && cached instanceof ViewGroup
                && ((ViewGroup) cached).getChildCount() > 0) {
            return cached;
        }
        mRow = null;
        View best = null;
        int bestCount = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (!(c instanceof ViewGroup)) {
                continue;
            }

            if (c instanceof com.example.liquidglass.LiquidGlassView) {
                continue;
            }
            int n = ((ViewGroup) c).getChildCount();
            if (n > bestCount) {
                bestCount = n;
                best = c;
            }
        }
        if (best != null) {
            mRow = best;
        }
        return mRow;
    }

    private View resolveTab(ViewGroup row, int selected) {
        int index = selected < 0 ? 0 : selected;
        View cached = mTab;
        if (cached != null && cached.getParent() == row && mTabIndex == index) {
            return cached;
        }
        View found = null;
        int idx = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (!(c instanceof LinearLayout)) {
                continue;
            }
            if (idx == index) {
                found = c;
                break;
            }
            idx++;
        }
        if (found == null) {

            idx = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (!(c instanceof ViewGroup)) {
                    continue;
                }
                if (idx == index) {
                    found = c;
                    break;
                }
                idx++;
            }
        }
        mTab = found;
        mTabIndex = index;
        return found;
    }

    private boolean targetMoved() {
        return Math.abs(mTarget.left - mPill.left) > TARGET_EPSILON
                || Math.abs(mTarget.top - mPill.top) > TARGET_EPSILON
                || Math.abs(mTarget.right - mPill.right) > TARGET_EPSILON
                || Math.abs(mTarget.bottom - mPill.bottom) > TARGET_EPSILON;
    }

    private void beginAnim(long now) {
        mStartL = mPill.left;
        mStartT = mPill.top;
        mStartR = mPill.right;
        mStartB = mPill.bottom;
        mStartAt = now;
        mAnimating = true;
    }

    private void advanceAnim(long now) {
        float t = (now - mStartAt) / (float) ANIM_MS;
        if (t >= 1f) {
            mPill.set(mTarget);
            mAnimating = false;
            return;
        }
        if (t < 0f) {
            t = 0f;
        }
        float inv = 1f - t;

        float e = 1f - inv * inv * inv;
        mPill.left = mStartL + (mTarget.left - mStartL) * e;
        mPill.top = mStartT + (mTarget.top - mStartT) * e;
        mPill.right = mStartR + (mTarget.right - mStartR) * e;
        mPill.bottom = mStartB + (mTarget.bottom - mStartB) * e;
    }

    private int tintFor(boolean dark) {
        if (mCachedFailCase == (dark ? 1 : 0) && mCachedTint != 0) {
            return mCachedTint;
        }
        int result = computeTintFor(dark);
        mCachedFailCase = dark ? 1 : 0;
        mCachedTint = result;
        return result;
    }

    private int computeTintFor(boolean dark) {
        int alpha = 255 * Math.max(0, Math.min(100, mSelectAlphaPct)) / 100;
        if (!mTintFollowsConfig) {

            int gray = dark ? 0xEBEBEB : 0xF2F2F2;
            return (alpha << 24) | gray;
        }
        int base = mConfiguredTint;
        int a = (base >>> 24) & 0xFF;
        a = Math.round(a * (mSelectAlphaPct / 100f));
        if (!dark) {
            return (Math.max(0, Math.min(255, a)) << 24) | (base & 0x00FFFFFF);
        }
        int cap = 255 * DARK_ALPHA_CAP_PCT / 100;
        int na = Math.min(cap, Math.round(a * DARK_ALPHA_SCALE));
        int r = (base >> 16) & 0xFF;
        int g = (base >> 8) & 0xFF;
        int b = base & 0xFF;
        r = Math.round(r + (255 - r) * DARK_WHITE_MIX);
        g = Math.round(g + (255 - g) * DARK_WHITE_MIX);
        b = Math.round(b + (255 - b) * DARK_WHITE_MIX);
        return (na << 24) | (r << 16) | (g << 8) | b;
    }
}
