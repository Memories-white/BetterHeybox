package com.better.heybox.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.WeakHashMap;

final class TopBarGlass {

    private static final long REFRESH_MS = 200L;
    private static final float BACKDROP_SCALE = 0.6f;

    private static final int BACKDROP_ROWS = 260;

    private static final WeakHashMap<View, LiftState> LIFTED = new WeakHashMap<>();

    private static java.lang.ref.WeakReference<View> sLastLifted;

    private static final int SAMPLE_WINDOW_MAX = 420;

    private static final WeakHashMap<View, GlassDrawable> ACTIVE = new WeakHashMap<>();
    private static final WeakHashMap<View, Drawable> ORIGINAL = new WeakHashMap<>();

    private TopBarGlass() {
    }

    static void apply(Activity activity, View anchor) {
        try {
            if (activity == null) {
                return;
            }
            View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            View titleBar = findTitleBarFrom(anchor);
            if (titleBar == null) {
                titleBar = findTitleBar(decor);
            }
            if (titleBar == null) {

                titleBar = findHomeHeader(decor);
            }
            if (titleBar == null) {
                LiquidGlassLog.log(Log.WARN, "topbar feather glass: title bar not found");
                return;
            }
            boolean dark = Boolean.TRUE.equals(detectDarkTheme(titleBar));

            liftScrollerIfEnabled(activity);
            liftContent(titleBar);

            alignContentBelowBar(decor, titleBar);

            paintStatusBarGlass(activity, titleBar);

            ensureOverlay(activity, decor, dark);
            if (ACTIVE.containsKey(titleBar)) {
                return;
            }

            liftContent(titleBar);
            ORIGINAL.put(titleBar, titleBar.getBackground());

            if (titleBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) titleBar.getParent()).setClipChildren(false);
                ((ViewGroup) titleBar.getParent()).setClipToPadding(false);
            }
            titleBar.setZ(1f);
            GlassDrawable glass = new GlassDrawable(titleBar);
            ACTIVE.put(titleBar, glass);
            titleBar.setBackground(glass);
            glass.attach();

            for (Overlay overlay : OVERLAYS) {
                if (overlay.decorView() == decor) {
                    glass.bindOverlay(overlay);
                    overlay.noteScrolled();
                    break;
                }
            }
            LiquidGlassLog.log(Log.INFO, "topbar feather glass applied");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar feather glass apply failed", t);
        }
    }

    private static final class LiftState {

        final java.util.HashMap<View, Integer> shifted;
        final int paddingTop;
        final int topMargin;
        final boolean clipToPadding;
        final boolean hadMarginParams;

        int lift;

        LiftState(int paddingTop, int topMargin, boolean clipToPadding,
                  boolean hadMarginParams) {
            this.paddingTop = paddingTop;
            this.topMargin = topMargin;
            this.clipToPadding = clipToPadding;
            this.hadMarginParams = hadMarginParams;
            this.shifted = new java.util.HashMap<>();
        }
    }

    private static void liftContent(View titleBar) {
        try {
            if (titleBar == null || !(titleBar.getParent() instanceof View)) {
                return;
            }
            int barH = titleBar.getHeight();
            if (barH <= 0) {
                return;
            }
            View root = titleBar.getRootView();
            if (root == null) {
                return;
            }
            if (root instanceof ViewGroup) {
                ((ViewGroup) root).setClipChildren(false);
            }
            if (titleBar.getParent() instanceof ViewGroup) {
                ((ViewGroup) titleBar.getParent()).setClipChildren(false);
            }
            titleBar.setZ(1f);            int[] barLoc = new int[2];
            titleBar.getLocationInWindow(barLoc);
            int barBottom = barLoc[1] + barH;

            View scroller = findInnerScroller(root, barLoc[1]);
            if (scroller == null) {
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no inner scroller");
                return;
            }
            if (LIFTED.containsKey(scroller)) {

                reclaimShifts(scroller, barH);
                return;
            }
            if (!liftOne(scroller, barH, false)) {
                return;
            }
            sLastLifted = new java.lang.ref.WeakReference<>(scroller);

            int barY = barLoc[1];
            int screenW = root.getWidth();
            ArrayList<View> all = findContentScrollers(root, screenW);
            View deepest = null;
            int deepestDepth = -1;
            for (View v : all) {
                if (v == scroller || LIFTED.containsKey(v)) {
                    continue;
                }
                int depth = 0;
                for (View p = v; p != null; p = (p.getParent() instanceof View)
                        ? (View) p.getParent() : null) {
                    depth++;
                }
                if (depth > deepestDepth) {
                    deepestDepth = depth;
                    deepest = v;
                }
            }
            if (deepest != null) {
                int[] loc = new int[2];
                deepest.getLocationInWindow(loc);

                int targetTop = Math.min(barY, barLoc[1]);

                int padSum = 0;
                for (View p = (deepest.getParent() instanceof View)
                        ? (View) deepest.getParent() : null;
                        p != null && p != scroller;
                        p = (p.getParent() instanceof View) ? (View) p.getParent() : null) {
                    padSum += p.getPaddingTop();
                }
                int delta = Math.max(0, loc[1] - targetTop - padSum);
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift probe: "
                        + deepest.getClass().getSimpleName() + " top=" + loc[1]
                        + " padSum=" + padSum + " delta=" + delta);

                if (Math.abs(delta) > Math.max(8, barH / 8) && Math.abs(delta) <= barH * 2) {
                    liftOne(deepest, delta, true);
                }
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar lift content failed", t);
        }
    }

    private static boolean liftOne(View scroller, int barH, boolean chained) {
        if (scroller == null) {
            return false;
        }
        try {
            ViewGroup.LayoutParams lp = scroller.getLayoutParams();
            if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
                if (chained) {
                    return false;
                }
                return false;
            }
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            int oldMargin = mlp.topMargin;
            int oldTop = scroller.getTop();
            int oldH = scroller.getHeight();
            if (chained && oldMargin < 0) {
                return false;
            }

            java.util.HashMap<View, Integer> shiftedChildren = new java.util.HashMap<>();
            if (scroller instanceof ViewGroup) {
                shiftChildren((ViewGroup) scroller, -barH, shiftedChildren);
            }
            LiquidGlassLog.log(Log.VERBOSE, "topbar lift adjust pad=" + scroller.getPaddingTop()
                    + " newPad=" + (scroller.getPaddingTop() + barH)
                    + " oldTop=" + oldTop + " oldH=" + oldH
                    + " lp=" + scroller.getLayoutParams().getClass().getSimpleName());
            LiftState state = new LiftState(
                    scroller.getPaddingTop(), oldMargin,
                    scroller instanceof ViewGroup && ((ViewGroup) scroller).getClipToPadding(),
                    true);
            state.shifted.putAll(shiftedChildren);

            state.lift = barH;
            LIFTED.put(scroller, state);
            mlp.topMargin = oldMargin - barH;
            scroller.setLayoutParams(mlp);
            scroller.setPadding(scroller.getPaddingLeft(),
                    scroller.getPaddingTop() + barH,
                    scroller.getPaddingRight(), scroller.getPaddingBottom());
            if (scroller instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) scroller;
                group.setClipToPadding(false);
                if (group.getParent() instanceof ViewGroup) {
                    ((ViewGroup) group.getParent()).setClipChildren(false);
                }
            }
            LiquidGlassLog.log(Log.VERBOSE, "topbar lift: scroller="
                    + scroller.getClass().getName() + " barH=" + barH
                    + " oldMargin=" + oldMargin + " chained=" + chained);
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar lift one failed", t);
            return false;
        }
    }

    private static void reclaimShifts(View scroller, int barH) {
        LiftState st = LIFTED.get(scroller);
        if (st == null) {
            return;
        }

        final int lift = st.lift > 0 ? st.lift : barH;
        for (java.util.Map.Entry<View, Integer> e : st.shifted.entrySet()) {
            View child = e.getKey();
            if (child == null || child.getParent() != scroller) {
                continue;
            }
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) lp).topMargin == e.getValue()) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.topMargin = e.getValue() - lift;
                child.setLayoutParams(mlp);
            }
        }
    }

    private static void shiftChildren(ViewGroup group, int dy,
            java.util.HashMap<View, Integer> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || !(child.getLayoutParams()
                    instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }
            ViewGroup.MarginLayoutParams clp =
                    (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            if (out != null && !out.containsKey(child)) {
                out.put(child, clp.topMargin);
            }
            clp.topMargin += dy;
            child.setLayoutParams(clp);
        }
    }

    private static int glassHeight(View decor, View bar) {
        int fallback = Math.round(22f * decor.getResources().getDisplayMetrics().density);
        int statusH = 0;
        try {
            int id = decor.getResources().getIdentifier(
                    "status_bar_height", "dimen", "android");
            if (id > 0) {
                statusH = decor.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        if (bar == null || bar.getHeight() <= 0) {
            return Math.max(1, fallback);
        }
        int[] loc = new int[2];
        bar.getLocationInWindow(loc);
        int bottom = loc[1] + bar.getHeight();
        if (statusH > 0 && bottom > statusH) {
            return Math.max(1, bottom - statusH);
        }
        return Math.max(1, bar.getHeight());
    }

    static void liftScrollerIfEnabled(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            View decor = activity.getWindow() == null
                    ? null : activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            View bar = findTitleBar(decor);
            int barH;
            if (bar != null && bar.getHeight() > 0) {
                barH = bar.getHeight();
            } else {
                int fallback = Math.round(22f * activity.getResources()
                        .getDisplayMetrics().density);
                if (fallback <= 0) {
                    return;
                }
                barH = fallback;
            }

            int barTop = barH;
            if (bar != null && bar.isShown()) {
                int[] barLoc = new int[2];
                bar.getLocationInWindow(barLoc);
                if (barLoc[1] > 0) {
                    barTop = barLoc[1] + barH;
                }
            }
            if (decor instanceof ViewGroup) {
                ((ViewGroup) decor).setClipChildren(false);
            }

            final int glassH = glassHeight(decor, bar);
            View scroller = findInnerScroller(decor, barTop);
            if (scroller == null) {
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no scroller for barTop=" + barTop);
                return;
            }
            if (LIFTED.containsKey(scroller)) {
                reclaimShifts(scroller, glassH);
                return;
            }
            LiquidGlassLog.log(Log.VERBOSE, "topbar lift target: "
                    + scroller.getClass().getSimpleName() + " top=" + locOf(scroller)[1]
                    + " h=" + scroller.getHeight() + " barH=" + barH);

            int liftBy = Math.max(1, Math.min(barH, locOf(scroller)[1] - barTop));

            liftBy = Math.max(liftBy, glassH);

            int[] curLoc = locOf(scroller);
            int wantedBy = Math.max(glassH, curLoc[1] - barTop);
            if (curLoc[1] > 0 && wantedBy > liftBy) {
                liftBy = Math.min(wantedBy, glassH + barH);
            }
            if (liftOne(scroller, liftBy, true)) {
                sLastLifted = new java.lang.ref.WeakReference<>(scroller);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar lift scroller failed", t);
        }
    }

    private static void alignContentBelowBar(View decor, View titleBar) {
        try {
            if (!(decor instanceof ViewGroup) || titleBar == null) {
                return;
            }
            int[] barLoc = new int[2];
            int[] decoLoc = new int[2];
            titleBar.getLocationInWindow(barLoc);
            decor.getLocationInWindow(decoLoc);
            int barBottom = barLoc[1] + titleBar.getHeight() - decoLoc[1];
            if (barBottom <= 0) {
                return;
            }
            int screenW = decor.getWidth();
            int maxH = 0;
            int minH = decor.getHeight() / 2;
            View best = null;
            ViewGroup container = (ViewGroup) decor;
            ArrayList<View> queue = new ArrayList<>();
            for (int i = 0; i < container.getChildCount(); i++) {
                queue.add(container.getChildAt(i));
            }
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 120) {
                View v = queue.remove(0);
                if (v == null || v.getVisibility() != View.VISIBLE) {
                    continue;
                }
                int[] loc = locOf(v);
                int top = loc[1] - decoLoc[1];
                boolean wide = v.getWidth() * 10 >= screenW * 8;

                if (wide && v.getHeight() >= minH && top >= 0
                        && top < barBottom && v != titleBar) {
                    if (v.getHeight() > maxH) {
                        maxH = v.getHeight();
                        best = v;
                    }
                    continue;
                }
                if (v instanceof ViewGroup && top < barBottom + barBottom) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        queue.add(g.getChildAt(i));
                    }
                }
            }
            if (best == null) {
                return;
            }
            int[] loc = locOf(best);
            int top = loc[1] - decoLoc[1];
            if (top <= 0) {
                return;
            }
            if (!(best.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
                return;
            }
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) best.getLayoutParams();

            int newMargin = lp.topMargin - top;
            if (newMargin >= 0) {
                return;
            }
            lp.topMargin = newMargin;
            best.setLayoutParams(lp);
            if (best instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) best;
                g.setPadding(g.getPaddingLeft(), g.getPaddingTop() + top,
                        g.getPaddingRight(), g.getPaddingBottom());
                g.setClipToPadding(false);
            }
            LiquidGlassLog.log(Log.VERBOSE, "topbar align: "
                    + best.getClass().getSimpleName() + " top=" + top
                    + " margin=" + newMargin + " barBottom=" + barBottom);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar align failed", t);
        }
    }

    private static View findInnerScroller(View root, int barTop) {
        ArrayList<View> hits = new ArrayList<>();
        collectScrollers(root, hits);
        View fallback = null;
        int fallbackTop = Integer.MAX_VALUE;
        ArrayList<String> logHits = new ArrayList<>();
        int rootW = root.getWidth() > 0 ? root.getWidth()
                : root.getResources().getDisplayMetrics().widthPixels;
        int rootH = root.getHeight() > 0 ? root.getHeight()
                : root.getResources().getDisplayMetrics().heightPixels;

        final int slack = Math.max(16, rootH / 12);
        for (View v : hits) {
            String name = v.getClass().getName();
            if (name.contains("HorizontalScrollView")) {
                continue;
            }
            if (v.getWidth() * 10 < rootW * 8) {
                continue;
            }
            String simple = v.getClass().getSimpleName();
            if (simple.contains("ViewPager")) {

                View inner = findEntryScroller(v, barTop, slack, rootH);
                if (inner == null) {
                    if (logHits.size() < 14) {
                        logHits.add(simple + ":no-inner");
                    }
                    continue;
                }
                int[] iLoc = locOf(inner);
                if (logHits.size() < 14) {
                    logHits.add(simple + ">" + inner.getClass().getSimpleName()
                            + "@" + iLoc[1] + "/" + inner.getHeight());
                }

                final boolean entry = iLoc[1] >= 0 && iLoc[1] <= barTop + slack;
                if (entry && inner.getHeight() * 6 >= rootH && inner.getHeight() >= 200) {
                    LiquidGlassLog.log(Log.VERBOSE, "topbar lift pick: "
                            + inner.getClass().getName() + " top=" + iLoc[1]
                            + " h=" + inner.getHeight() + " hits=" + logHits);
                    return inner;
                }
                continue;
            }
            int[] loc = locOf(v);
            if (loc[1] + v.getHeight() <= barTop + 8) {
                continue;
            }
            if (logHits.size() < 14) {
                logHits.add(simple + "@" + loc[1] + "/" + v.getHeight());
            }
            if (v.getHeight() * 2 >= rootH && v.getHeight() >= 200) {
                LiquidGlassLog.log(Log.VERBOSE, "topbar lift pick: " + name + " top=" + loc[1]
                        + " h=" + v.getHeight() + " pad=" + v.getPaddingTop()
                        + " hits=" + logHits);
                return v;
            }

            if (loc[1] > 0 && (fallback == null || loc[1] < fallbackTop)) {
                fallback = v;
                fallbackTop = loc[1];
            }
        }
        if (fallback == null) {
            LiquidGlassLog.log(Log.VERBOSE, "topbar lift: no candidate, hits=" + logHits);
            return null;
        }
        LiquidGlassLog.log(Log.VERBOSE, "topbar lift pick: fallback "
                + fallback.getClass().getSimpleName() + " top=" + fallbackTop
                + " hits=" + logHits);
        return fallback;
    }

    private static View findEntryScroller(View v, int barTop, int slack, int rootH) {
        int[] loc = locOf(v);
        if (loc[1] > 0 && loc[1] <= barTop + slack
                && v.getHeight() * 2 >= rootH && v.getHeight() >= 200) {
            return v;
        }
        return realScroller(v);
    }

    private static View realScroller(View v) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;

            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null && c.isShown() && LIFTED.containsKey(c) && c.getHeight() >= 200) {
                    return c;
                }
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || !c.isShown()) {
                    continue;
                }
                String n = c.getClass().getName();
                if (n.contains("Scroll") || n.contains("RecyclerView")) {
                    int[] loc = locOf(c);
                    if (loc[1] >= 0 && c.getHeight() >= 200) {
                        return c;
                    }
                }
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || !c.isShown()) {
                    continue;
                }
                View r = realScroller(c);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static View scrollHolderOf(View root) {
        if (root == null) {
            return null;
        }
        View pager2Sliver = null;
        View consecutive = null;
        View rv = null;
        java.util.ArrayList<View> stack = new java.util.ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            View v = stack.remove(stack.size() - 1);
            if (v == null || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            String name = v.getClass().getName();
            if (name.contains("ViewPager2") && name.contains("RecyclerViewImpl")) {
                if (pager2Sliver == null) {
                    pager2Sliver = v;
                }
            } else if (name.contains("ConsecutiveScroller")) {
                if (consecutive == null) {
                    consecutive = v;
                }
            } else if (name.contains("RecyclerView")) {
                if (rv == null) {
                    rv = v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) {
                    stack.add(g.getChildAt(i));
                }
            }
        }
        if (pager2Sliver != null) {
            return pager2Sliver;
        }
        if (consecutive != null) {
            return consecutive;
        }
        return rv;
    }
    private static int[] locOf(View v) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return loc;
    }

    private static void collectAbsOffset(View ancestor, View target, int[] out) {
        out[0] = Integer.MIN_VALUE;
        out[1] = 0;
        int x = target.getLeft();
        int y = target.getTop();
        View p = target.getParent() instanceof View ? (View) target.getParent() : null;
        while (p != null) {
            if (p == ancestor) {
                out[0] = x;
                out[1] = y;
                return;
            }
            x += p.getLeft() - p.getScrollX();
            y += p.getTop() - p.getScrollY();
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }
    }

    private static View findLiftableAncestor(View v, int barTop, int rootW) {
        View best = null;
        View pager = null;
        View p = v.getParent() instanceof View ? (View) v.getParent() : null;
        while (p != null) {
            String name = p.getClass().getName();
            boolean isPager = name.contains("ViewPager");
            if (isPager) {
                pager = p;
            }
            int[] loc = locOf(p);
            boolean fullW = p.getWidth() * 10 >= rootW * 8;
            boolean tallEnough = p.getHeight() >= 200;

            if (fullW && tallEnough && loc[1] >= barTop - 16 && !isPager
                    && loc[1] <= barTop + 8) {
                return p;
            }
            if (isPager && fullW && tallEnough) {
                best = p;
            }
            if (pager != null && passedPager(p) > 1) {
                break;
            }
            p = p.getParent() instanceof View ? (View) p.getParent() : null;
        }

        return pager != null ? pager : best;
    }

    private static int passedPager(View v) {
        return v.getClass().getName().contains("ViewPager") ? 2 : 0;
    }

    private static ArrayList<View> findContentScrollers(View root, int screenW) {
        ArrayList<View> all = new ArrayList<>();
        collectScrollers(root, all);
        ArrayList<View> pick = new ArrayList<>();
        for (View v : all) {
            if (v.getWidth() < screenW * 0.9f || v.getHeight() < 200) {
                continue;
            }
            String name = v.getClass().getName();
            if (name.contains("$RecyclerViewImpl")) {
                continue;
            }

            if (name.contains("ViewPager")) {
                continue;
            }
            pick.add(v);
        }
        return pick;
    }

    private static void collectScrollers(View v, ArrayList<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE) {
            return;
        }
        String name = v.getClass().getName();
        boolean scrollable = v instanceof android.widget.ScrollView
                || v instanceof android.widget.HorizontalScrollView
                || name.contains("RecyclerView")
                || name.contains("ScrollView")
                || name.contains("ViewPager");
        if (scrollable && v.getWidth() > 0 && v.getHeight() > 0) {

            if (!name.contains("$RecyclerViewImpl")) {
                out.add(v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectScrollers(g.getChildAt(i), out);
            }
        }
    }

    private static void releaseLifted() {
        for (View v : new ArrayList<>(LIFTED.keySet())) {
            LiftState st = LIFTED.remove(v);
            if (st == null) {
                continue;
            }
            try {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).topMargin = st.topMargin;
                    v.setLayoutParams(lp);
                }
                v.setPadding(v.getPaddingLeft(), st.paddingTop,
                        v.getPaddingRight(), v.getPaddingBottom());
                if (v instanceof ViewGroup) {
                    ((ViewGroup) v).setClipToPadding(st.clipToPadding);
                }
                for (java.util.Map.Entry<View, Integer> e : st.shifted.entrySet()) {
                    View child = e.getKey();
                    ViewGroup.LayoutParams clp = child.getLayoutParams();
                    if (clp instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) clp).topMargin = e.getValue();
                        child.setLayoutParams(clp);
                    }
                }
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar lift restore failed", t);
            }
        }
    }

    static void releaseAll() {
        try {
            for (View v : new ArrayList<>(ACTIVE.keySet())) {
                release(v);
            }
            releaseLifted();
            for (Overlay overlay : new ArrayList<>(OVERLAYS)) {
                overlay.detach();
            }
            OVERLAYS.clear();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar feather glass release failed", t);
        }
    }

    private static void release(View v) {
        GlassDrawable glass = ACTIVE.remove(v);
        if (glass != null) {
            glass.detach();
        }

        if (!ORIGINAL.containsKey(v)) {
            return;
        }
        Drawable original = ORIGINAL.remove(v);
        if (v != null) {
            v.setBackground(original);
        }
    }

    private static void paintStatusBarGlass(Activity activity, View titleBar) {
        try {
            if (activity == null || activity.getWindow() == null) {
                return;
            }
            android.view.Window window = activity.getWindow();

            window.setStatusBarColor(android.graphics.Color.TRANSPARENT);

            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar status bar glass failed", t);
        }
    }

    private static final java.util.Set<Overlay> OVERLAYS =
            java.util.Collections.newSetFromMap(new WeakHashMap<>());

    private static Overlay ensureOverlay(Activity activity, View decor, boolean dark) {
        for (Overlay overlay : OVERLAYS) {
            if (overlay.decorView() == decor) {
                overlay.setDark(dark);
                return overlay;
            }
        }
        Overlay overlay = newOverlay(decor, dark);
        OVERLAYS.add(overlay);
        overlay.attach();

        for (View bar : new ArrayList<>(ACTIVE.keySet())) {
            if (bar != null && bar.getRootView() == decor) {
                ACTIVE.get(bar).bindOverlay(overlay);
                break;
            }
        }
        return overlay;
    }

    static void sync(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            if (activity.getWindow() == null) {
                return;
            }
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            View bar = findTitleBar(decor);
            if (bar == null) {
                return;
            }

            apply(activity, null);
            ensureEveryWindow(activity);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("topbar feather glass sync failed", t);
        }
    }

    private static void ensureEveryWindow(Activity activity) {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method current = activityThreadClass
                    .getMethod("currentActivityThread");
            Object thread = current.invoke(null);
            java.lang.reflect.Field activitiesField =
                    activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object map = activitiesField.get(thread);
            if (!(map instanceof java.util.Map)) {
                return;
            }
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                if (record == null) {
                    continue;
                }
                java.lang.reflect.Field activityField = record.getClass()
                        .getDeclaredField("activity");
                activityField.setAccessible(true);
                Object obj = activityField.get(record);
                if (!(obj instanceof Activity)) {
                    continue;
                }
                Activity act = (Activity) obj;
                if (act.isFinishing() || act.getWindow() == null) {
                    continue;
                }
                View decor = act.getWindow().getDecorView();
                View bar = decor == null ? null : findTitleBar(decor);
                if (bar == null) {
                    continue;
                }
                boolean dark = Boolean.TRUE.equals(detectDarkTheme(bar));
                Overlay overlay = ensureOverlay(act, decor, dark);
                overlay.invalidate();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isHomeWindow(View decor) {
        try {
            Context context = decor.getContext();
            String pkg = context.getPackageName();
            for (String name : new String[]{"tb_home", "fl_tb_home_container"}) {
                int id = context.getResources().getIdentifier(name, "id", pkg);
                if (id != 0 && decor.findViewById(id) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static final TopBarGlass OUTER = new TopBarGlass();

    private static Overlay newOverlay(View decor, boolean dark) {
        return OUTER.new Overlay(decor, dark);
    }

    private static View findTitleBarFrom(View anchor) {
        View current = anchor;
        for (int i = 0; current != null && i < 16; i++) {
            if (current.getClass().getSimpleName().contains("TitleBar")) {
                return current;
            }
            android.view.ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    static View findTitleBar(View root) {
        if (root == null) {
            return null;
        }

        try {
            Context context = root.getContext();
            String pkg = context.getPackageName();
            for (String name : new String[]{"tb_home", "fl_tb_home_container"}) {
                int id = context.getResources().getIdentifier(name, "id", pkg);
                if (id != 0) {
                    View view = root.findViewById(id);
                    if (view != null && view.getHeight() > 0) {
                        return view;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return findTitleBarByClass(root);
    }

    private static View findHomeHeader(View decor) {
        if (decor == null) {
            return null;
        }
        try {
            int pkgId = decor.getContext().getResources()
                    .getIdentifier("fl_tb_home_container", "id",
                            decor.getContext().getPackageName());
            if (pkgId != 0) {
                View v = decor.findViewById(pkgId);
                if (v != null && v.isShown() && v.getHeight() > 0) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findTitleBarByClass(View root) {
        if (root == null) {
            return null;
        }
        if (root.getClass().getSimpleName().contains("TitleBar")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTitleBarByClass(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Boolean detectDarkTheme(View bar) {
        Integer textColor = findTextColor(bar);
        if (textColor == null) {
            return null;
        }
        return luminance(textColor) > 140;
    }

    private static Integer findTextColor(View root) {
        if (root == null) {
            return null;
        }
        if (root instanceof TextView) {
            int color = ((TextView) root).getCurrentTextColor();
            if ((color >>> 24) != 0) {
                return color;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Integer found = findTextColor(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int luminance(int color) {
        return (((color >> 16) & 0xFF) * 30 + ((color >> 8) & 0xFF) * 59
                + (color & 0xFF) * 11) / 100;
    }

    private static final class GlassDrawable extends Drawable {

        private final View mBar;
        private final float mDensity;
        private boolean mDark;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix mMatrix = new Matrix();

        private Bitmap mBackdrop;
        private long mLastCaptureAt;
        private boolean mCapturing;
        private boolean mLogged;
        private ViewTreeObserver.OnScrollChangedListener mScrollListener;

        private java.lang.ref.WeakReference<Overlay> mOverlayRef;

        void bindOverlay(Overlay overlay) {
            mOverlayRef = new java.lang.ref.WeakReference<>(overlay);
            mOverlayBound = true;
        }

        private boolean mOverlayBound;

        GlassDrawable(View bar) {
            mBar = bar;
            Context context = bar.getContext();
            mDensity = context.getResources().getDisplayMetrics().density;
            Boolean themeDark = detectDarkTheme(bar);
            mDark = themeDark != null ? themeDark
                    : (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        }

        void attach() {
            if (mScrollListener != null) {
                return;
            }

            mScrollListener = () -> {
                Overlay ov = mOverlayRef == null ? null : mOverlayRef.get();
                if (ov != null) {

                    ov.noteScrolled();
                    ov.refreshAfterIdle();
                    return;
                }
                invalidateSelf();
            };
            try {
                mBar.getViewTreeObserver().addOnScrollChangedListener(mScrollListener);
            } catch (Throwable ignored) {
            }
        }

        void detach() {
            if (mScrollListener != null) {
                try {
                    mBar.getViewTreeObserver().removeOnScrollChangedListener(mScrollListener);
                } catch (Throwable ignored) {
                }
                mScrollListener = null;
            }
            recycle();
        }

        void refresh() {
            mLastCaptureAt = 0;
            invalidateSelf();
        }

        void refreshAppearance() {
            Boolean themeDark = detectDarkTheme(mBar);
            if (themeDark != null) {
                mDark = themeDark;
            }
            mLastCaptureAt = 0;
            recycle();
            invalidateSelf();
        }

        private void recycle() {
            if (mBackdrop != null && !mBackdrop.isRecycled()) {
                mBackdrop.recycle();
            }
            mBackdrop = null;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) {
                return;
            }
            maybeCapture(width, height);
            if (mBackdrop == null || mBackdrop.isRecycled()) {
                return;
            }
            BitmapShader shader = new BitmapShader(
                    mBackdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mMatrix.setScale(width / (float) mBackdrop.getWidth(),
                    height / (float) mBackdrop.getHeight());
            shader.setLocalMatrix(mMatrix);
            mPaint.setShader(shader);
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.drawRect(0f, 0f, width, height, mPaint);
            canvas.restore();
            mPaint.setShader(null);
        }

        private void maybeCapture(int width, int height) {

            if (mCapturing || !mOverlayBound) {
                return;
            }
            if (!mBar.isAttachedToWindow() || !mBar.isShown()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - mLastCaptureAt < OVERLAY_REFRESH_MS) {
                return;
            }
            mLastCaptureAt = now;

            int bw = Math.max(1, Math.round(width * BACKDROP_SCALE));
            int bh = Math.max(1, Math.round(height * BACKDROP_SCALE));
            if (mBackdrop == null || mBackdrop.isRecycled()
                    || mBackdrop.getWidth() != bw || mBackdrop.getHeight() != bh) {
                recycle();
                try {
                    mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("topbar glass backdrop alloc failed", t);
                    mBackdrop = null;
                    return;
                }
            }

            View root = mBar.getRootView();
            if (root == null) {
                return;
            }
            int[] barLoc = new int[2];
            int[] rootLoc = new int[2];
            mBar.getLocationInWindow(barLoc);
            root.getLocationInWindow(rootLoc);
            float dx = barLoc[0] - rootLoc[0];

            int barTopInRoot = barLoc[1] - rootLoc[1];
            mCapturing = true;
            try {
                Canvas canvas = new Canvas(mBackdrop);
                canvas.save();
                canvas.scale(BACKDROP_SCALE, BACKDROP_SCALE);

                int rowsBelow = Math.max(BACKDROP_ROWS, mBar.getHeight() * 2);
                canvas.translate(-dx, -(barTopInRoot + rowsBelow));

                root.draw(canvas);
                canvas.restore();
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar glass capture failed", t);
            } finally {
                mCapturing = false;
            }

            try {

                Boolean themeDark = detectDarkTheme(mBar);
                if (themeDark != null) {
                    mDark = themeDark;
                }

                applySurfaceTint();
                applyFeather();
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar glass process failed", t);
            }
            if (!mLogged) {
                mLogged = true;
                Log.i("BetterHeybox", "topbar feather glass backdrop "
                        + bw + "x" + bh);
            }
        }

        private void applySurfaceTint() {
            Paint paint = new Paint();
            paint.setColor(mDark ? 0x38000000 : 0x3DFFFFFF);
            new Canvas(mBackdrop).drawRect(0f, 0f,
                    mBackdrop.getWidth(), mBackdrop.getHeight(), paint);
        }

        private void applyFeather() {
            int w = mBackdrop.getWidth();
            int h = mBackdrop.getHeight();
            int fade = Math.max(1, Math.min(h / 2, Math.round(h * 0.45f)));
            int base = mBackdrop.getPixel(w / 2, h - 1);
            int transparentBase = base & 0x00FFFFFF;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(0f, h - fade, 0f, h,
                    transparentBase, base, Shader.TileMode.CLAMP));
            new Canvas(mBackdrop).drawRect(0f, h - fade, w, h, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final long OVERLAY_REFRESH_MS = 200L;

    private static final boolean DEBUG_DUMP_BACKDROP = false;

    private static final long SCROLL_KICK_WINDOW_MS = 260L;

    private static final int OVERLAY_ALIGN_TOLERANCE_PX = 8;

    private static final float OVERLAY_EXTRA = 1.6f;

            private static final float mStrongBlurDp = 34f;

    final class Overlay extends View {

        private final View mDecor;

        View decorView() {
            return mDecor;
        }
        private boolean mDark;
        private boolean mCapturing;
        private Bitmap mBackdrop;
        private Bitmap mOutput;
        private long mLastCaptureAt;
        private boolean mLogged;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        Overlay(View decor, boolean dark) {
            super(decor.getContext());
            mDecor = decor;
            mDark = dark;
            setWillNotDraw(false);
        }

        void setDark(boolean dark) {
            if (mDark != dark) {
                mDark = dark;
                mLastCaptureAt = 0;
                invalidate();
            }
        }

        private boolean mTicking;
        private GlassDrawable mScrollTicker;

        private GlassDrawable findTickerIn(View root) {
            for (View bar : new ArrayList<>(ACTIVE.keySet())) {
                if (bar != null && bar.isAttachedToWindow()
                        && bar.getRootView() == root) {
                    return ACTIVE.get(bar);
                }
            }
            return null;
        }

        void kick() {
            if (mTicking || mCapturing) {
                return;
            }
            mTicking = true;
            try {
                postOnAnimation(mTickRunnable);
            } catch (Throwable ignored) {
                mTicking = false;
            }
        }

        private final Runnable mTickRunnable = new Runnable() {
            @Override
            public void run() {
                mTicking = false;
                if (!isAttachedToWindow()) {
                    return;
                }
                if (mScrollTicker != null) {
                    mScrollTicker.refresh();
                }
                invalidate();

                if (isScrollingNow()) {
                    kick();
                }
            }
        };

        private volatile long mScrollKickAt;

        void noteScrolled() {
            mScrollKickAt = SystemClock.uptimeMillis();
            kick();
        }

        private boolean isScrollingNow() {
            if (mScrollKickAt == 0L) {
                return false;
            }
            return SystemClock.uptimeMillis() - mScrollKickAt < SCROLL_KICK_WINDOW_MS;
        }

        void refreshAfterIdle() {
            if (mIdleRefreshPosted) {
                return;
            }
            mIdleRefreshPosted = true;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    mIdleRefreshPosted = false;
                    if (!isScrollingNow()) {
                        invalidate();
                    }
                }
            }, SCROLL_KICK_WINDOW_MS + 40L);
        }

        private boolean mIdleRefreshPosted;

        void attach() {
            if (!(mDecor instanceof ViewGroup)) {
                return;
            }

            post(new Runnable() {
                @Override
                public void run() {
                    mScrollTicker = findTickerIn(mDecor);
                    kick();
                }
            });
            ViewGroup group = (ViewGroup) mDecor;

            int barH = hostTitleBarHeight();
            if (barH <= 0) {
                barH = Math.round(22f * getResources().getDisplayMetrics().density);
            }

            int top = hostTitleBarTop();

            int offsetY = top;

            int bandH = Math.round(barH * 1.6f);
            ViewGroup.LayoutParams lp;
            if (group instanceof android.widget.FrameLayout) {
                lp = new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH);
                ((android.widget.FrameLayout.LayoutParams) lp).topMargin = 0;
            } else if (group instanceof android.widget.LinearLayout) {
                lp = new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH);
                ((android.widget.LinearLayout.LayoutParams) lp).topMargin = 0;
            } else {
                lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, offsetY + barH + bandH);
            }
            try {
                group.addView(this, lp);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar overlay attach failed", t);
                return;
            }

            paintTitleBarSurface(barH);
            LiquidGlassLog.log(Log.INFO, "topbar overlay attached top=" + top + " h="
                    + (barH + bandH) + " glass=" + barH + " dark=" + mDark);
        }

        private int hostTitleBarTop() {
            View bar = titleBarView();
            if (bar == null) {
                return statusBarHeight();
            }
            int[] origin = new int[2];
            int[] loc = new int[2];
            mDecor.getLocationInWindow(origin);
            bar.getLocationInWindow(loc);
            int top = loc[1] - origin[1];
            if (top > 0) {

                return top;
            }

            return 0;
        }

        private void paintTitleBarSurface(int glassH) {
            final View bar = titleBarView();
            if (bar == null) {
                return;
            }
            final Boolean themeDark = detectDarkTheme(bar);
            if (themeDark != null) {
                mDark = themeDark;
            }

            final int alpha = mDark ? 0x3A : 0x42;
            final int rgb = mDark ? 0x181A1C : 0xF7F8FA;
            Drawable surface = new Drawable() {
                @Override
                public void draw(Canvas canvas) {
                    Rect b = getBounds();
                    int bw = b.width();
                    int bh = b.height();
                    if (bw <= 0 || bh <= 0) {
                        return;
                    }

                    if (mBackdrop != null && !mBackdrop.isRecycled()) {

                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.setScale(bw / (float) mBackdrop.getWidth(),
                                bh / (float) mBackdrop.getHeight());
                        BitmapShader shader = new BitmapShader(mBackdrop,
                                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        shader.setLocalMatrix(m);
                        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                        p.setShader(shader);
                        canvas.drawRect(0f, 0f, bw, bh, p);

                        p.setShader(null);
                        p.setColor((alpha << 24) | rgb);
                        canvas.drawRect(0f, 0f, bw, bh, p);
                    } else {
                        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                        p.setColor((0xF0 << 24) | rgb);
                        canvas.drawRect(0f, 0f, bw, bh, p);
                    }
                }

                @Override
                public void setAlpha(int a) {
                }

                @Override
                public void setColorFilter(android.graphics.ColorFilter cf) {
                }

                @Override
                public int getOpacity() {
                    return android.graphics.PixelFormat.TRANSLUCENT;
                }
            };
            ORIGINAL.put(bar, bar.getBackground());
            bar.setBackground(surface);
        }

        void detach() {
            try {
                if (getParent() instanceof ViewGroup) {
                    ((ViewGroup) getParent()).removeView(this);
                }
            } catch (Throwable ignored) {
            }
            recycle();
        }

        private int statusBarHeight() {
            try {
                int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id != 0) {
                    return getResources().getDimensionPixelSize(id);
                }
            } catch (Throwable ignored) {
            }
            return Math.round(24f * getResources().getDisplayMetrics().density);
        }

        private int titleBarHeight() {
            return hostTitleBarHeight() + getTop();
        }

        private int hostTitleBarHeight() {
            View bar = titleBarView();
            if (bar != null && bar.getHeight() > 0) {
                return bar.getHeight();
            }

            for (View known : new ArrayList<>(ORIGINAL.keySet())) {
                if (known != null && known.getHeight() > 0) {
                    return known.getHeight();
                }
            }
            int barH = 0;
            View decor = mDecor;
            Context context = getContext();
            for (String name : new String[]{"tb_home", "fl_tb_home_container"}) {
                try {
                    int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
                    if (id != 0) {
                        View view = decor.findViewById(id);
                        if (view != null && view.getHeight() > 0) {
                            int[] origin = new int[2];
                            int[] loc = new int[2];
                            decor.getLocationInWindow(origin);
                            view.getLocationInWindow(loc);
                            int bottom = loc[1] - origin[1] + view.getHeight();
                            barH = bottom > view.getHeight() ? bottom : view.getHeight();
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (barH <= 0) {
                barH = Math.round(22f * getResources().getDisplayMetrics().density);
            }
            return barH;
        }

        private int overlayTotalHeight() {
            int barH = titleBarHeight();
            return barH + Math.round(barH * OVERLAY_EXTRA);
        }

        private int maxDrawableRegionH() {
            View decor = mDecor;
            int limit = 0;
            int screenW = decor.getWidth() > 0 ? decor.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int screenH = decor.getHeight() > 0 ? decor.getHeight() : getResources().getDisplayMetrics().heightPixels;
            for (View v : findContentScrollers(decor, screenW)) {
                if (!v.isShown() || v.getHeight() < 200) {
                    continue;
                }
                int[] loc = new int[2];
                v.getLocationInWindow(loc);

                if (loc[1] < 0 || loc[1] >= screenH - 64) {
                    continue;
                }
                limit = Math.max(limit, loc[1] + titleBarHeight());
            }
            if (limit <= 0) {

                return titleBarHeight() + Math.round(titleBarHeight() * OVERLAY_EXTRA);
            }
            return limit;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mCapturing) {
                return;
            }

            final int offsetY = getTop();
            maybeCapture(w, h);

            buildProgressiveFade(w, h, titleBarHeight(), offsetY);

            if (offsetY > 0 && mOutput != null && !mOutput.isRecycled()) {
                int mode = mPaint.getXfermode() == null ? -1 : 0;
                mPaint.setXfermode(null);
                Rect srcTop = new Rect(0, 0, mOutput.getWidth(), Math.min(mOutput.getHeight(), offsetY));
                Rect dstTop = new Rect(0, 0, w, Math.min(h, offsetY));
                canvas.drawBitmap(mOutput, srcTop, dstTop, mPaint);
                if (mode != 0) {
                    mPaint.setXfermode(null);
                }
            }

            if (mOutput != null && !mOutput.isRecycled()) {
                int dstTop = Math.max(0, Math.min(getHeight(), offsetY + titleBarHeight()));

                int dstBottom = getHeight();
                if (dstBottom > dstTop) {
                    Rect dst = new Rect(0, dstTop, w, dstBottom);
                    Rect src = new Rect(0, Math.min(mOutput.getHeight(), dstTop), mOutput.getWidth(),
                            Math.min(mOutput.getHeight(), dstBottom));
                    canvas.drawBitmap(mOutput, src, dst, mPaint);
                }
            }
        }

        private boolean isContentFlowing() {
            View scroller = sLastLifted == null ? null : sLastLifted.get();
            if (scroller == null || !scroller.isShown()) {
                return false;
            }
            View probe = scroller;
            if (probe instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) probe;
                int hidden = 0;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && c.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    if (LIFTED.containsKey(c)) {
                        continue;
                    }
                    int[] loc = locOf(c);
                    return loc[1] < 0;
                }
                return false;
            }
            return false;
        }

        int renderedHeight() {
            if (mOutput == null || mOutput.isRecycled()) {
                return 0;
            }
            return Math.round(mOutput.getHeight() / BACKDROP_SCALE);
        }

        private void buildProgressiveFade(int w, int h, int glassH, int offsetY) {
            if (mOutput == null || mOutput.isRecycled()
                    || mOutput.getWidth() != w || mOutput.getHeight() != h) {
                if (mOutput != null && !mOutput.isRecycled()) {
                    mOutput.recycle();
                }
                try {
                    mOutput = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("topbar overlay alloc failed", t);
                    mOutput = null;
                    return;
                }
            }
            Canvas c = new Canvas(mOutput);
            c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);

            glassH = Math.max(1, Math.round(glassH / Math.max(0.001f, BACKDROP_SCALE)));
            glassH = Math.min(glassH, h);
            offsetY = Math.max(0, Math.min(offsetY, glassH));

            final float[] stops = {0.00f, 0.25f, 0.50f, 0.75f, 1.00f};
            final float[] factors = {1.0f, 232f / 255f, 176f / 255f, 96f / 255f, 0f};

            int bandTop = Math.max(0, Math.min(glassH, h));
            int bandH = Math.max(0, h - bandTop);
            int baseRgb = mDark ? 0x101214 : 0xFFFFFF;

            if (mBackdrop != null && !mBackdrop.isRecycled()) {
                int[] winAlphas = new int[stops.length];

                int glassAlpha = mDark ? 198 : 200;
                for (int i = 0; i < stops.length; i++) {
                    winAlphas[i] = (Math.round(glassAlpha * factors[i]) << 24) | 0xFFFFFF;
                }

                float flat = bandH > 0 ? bandTop / (float) h : 1f;

                float[] mStops = {0f, Math.max(0f, flat), Math.max(flat, 0.25f + flat * 0.75f),
                        Math.max(flat, 0.50f + flat * 0.50f), Math.max(flat, 0.75f + flat * 0.25f), 1f};
                int[] mAlphas = {glassAlpha, glassAlpha,
                        Math.round(glassAlpha * 232f / 255f),
                        Math.round(glassAlpha * 176f / 255f),
                        Math.round(glassAlpha * 96f / 255f), 0};
                for (int i = 0; i < mStops.length; i++) {
                    if (i > 1 && mStops[i] <= mStops[i - 1]) {
                        mStops[i] = Math.min(1f, mStops[i - 1] + 0.0001f);
                    }
                    mAlphas[i] = (mAlphas[i] << 24) | 0xFFFFFF;
                }
                c.saveLayer(0f, 0f, w, h, null);
                Rect src = new Rect(0, 0, mBackdrop.getWidth(),
                        Math.min(mBackdrop.getHeight(), h));
                Rect dst = new Rect(0, 0, w, h);
                c.drawBitmap(mBackdrop, src, dst, mPaint);
                Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
                mask.setShader(new LinearGradient(0f, 0f, 0f, h, mAlphas, mStops,
                        Shader.TileMode.CLAMP));
                mask.setXfermode(new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.DST_IN));
                c.drawRect(0f, 0f, w, h, mask);
                c.restore();
            }

            int[] veil = new int[stops.length];
            int veilTop = mDark ? 48 : 58;
            for (int i = 0; i < stops.length; i++) {
                veil[i] = ((Math.round(veilTop * factors[i])) << 24) | baseRgb;
            }
            if (bandH > 0) {

                float flat = bandH > 0 ? bandTop / (float) h : 1f;
                float[] vStops = {0f, Math.max(0f, flat), Math.max(flat, 0.25f + flat * 0.75f),
                        Math.max(flat, 0.50f + flat * 0.50f), Math.max(flat, 0.75f + flat * 0.25f), 1f};
                int[] vColors = {veilTop, veilTop, veil[1], veil[2], veil[3], veil[4]};
                for (int i = 0; i < vColors.length; i++) {
                    vColors[i] = (vColors[i] << 24) | baseRgb;
                }
                for (int i = 1; i < vStops.length; i++) {
                    if (vStops[i] <= vStops[i - 1]) {
                        vStops[i] = Math.min(1f, vStops[i - 1] + 0.0001f);
                    }
                }
                Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                veilPaint.setShader(new LinearGradient(0f, 0f, 0f, h, vColors, vStops,
                        Shader.TileMode.CLAMP));
                c.drawRect(0f, 0f, w, h, veilPaint);
            }
            Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
            solid.setColor((veilTop << 24) | baseRgb);
            c.drawRect(0f, 0f, w, bandTop, solid);

            if (!isContentFlowing()) {
                int hideTop = Math.round((bandTop + bandH * 0.30f));
                int fadeEnd = Math.max(hideTop + 1, h);
                int pageRgb = mDark ? 0x000000 : 0xFFFFFF;
                Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG);
                cover.setShader(new LinearGradient(0f, hideTop, 0f, fadeEnd,
                        new int[]{(0xFF << 24) | pageRgb, (0x00 << 24) | pageRgb},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0f, hideTop, w, h, cover);
            }

            if (bandH > 4) {
                int cutTop = bandTop + Math.round(bandH * 0.45f);
                Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                cut.setXfermode(new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.DST_OUT));
                cut.setShader(new LinearGradient(0f, cutTop, 0f, bandTop + bandH,
                        new int[]{0x00FFFFFF, 0xFFFFFFFF}, new float[]{0f, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(0f, cutTop, w, bandTop + bandH, cut);
            }

            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setColor(mDark ? 0x30FFFFFF : 0x8CFFFFFF);
            float edgeH = Math.max(1f, 0.8f * getResources().getDisplayMetrics().density
                    * BACKDROP_SCALE);
            c.drawRect(0f, Math.max(0f, bandTop - edgeH), w, bandTop, edge);

            Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlight.setColor(mDark ? 0x1FFFFFFF : 0x66FFFFFF);
            c.drawRect(0f, 0f, w, Math.max(1f, 1f * getResources().getDisplayMetrics().density
                    * BACKDROP_SCALE), highlight);
        }

        private void drawFeatheredBand(Canvas canvas, int w, int top, int bandH) {
            if (bandH <= 0) {
                return;
            }
            int bh = mBackdrop.getHeight();
            int bw = mBackdrop.getWidth();

            for (int y = 0; y < bandH; y++) {
                float t = 1f - (y / (float) bandH);
                t = t * t * t;
                int srcRow = Math.min(bh - 1, Math.round(y * BACKDROP_SCALE));
                mPaint.setShader(null);
                mPaint.setColor(rowAverageColor(srcRow));
                mPaint.setAlpha(Math.round(255 * t * 0.98f));
                canvas.drawRect(0f, top + y, w, top + y + 1, mPaint);
            }
            mPaint.setAlpha(255);
        }

        private int rowAverageColor(int row) {
            int w = mBackdrop.getWidth();
            int step = Math.max(1, w / 24);
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int x = 0; x < w; x += step) {
                int c = mBackdrop.getPixel(x, row);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
            if (n == 0) {
                return 0xFFFFFFFF;
            }
            return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
        }

        private void maybeCapture(int w, int regionH) {
            long now = SystemClock.uptimeMillis();
            if (now - mLastCaptureAt < OVERLAY_REFRESH_MS) {
                return;
            }
            mLastCaptureAt = now;

            int bw = Math.max(1, Math.round(w * BACKDROP_SCALE));
            int bh = Math.max(1, Math.round(regionH * BACKDROP_SCALE));
            if (mBackdrop == null || mBackdrop.isRecycled()
                    || mBackdrop.getWidth() != bw || mBackdrop.getHeight() != bh) {
                recycle();
                try {
                    mBackdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("topbar overlay alloc failed", t);
                    mBackdrop = null;
                    return;
                }
            }

            mCapturing = true;

            int oldVis = getVisibility();
            setVisibility(GONE);
            final View bar = titleBarView();
            final boolean barVisible = bar != null && bar.isShown();
            final int barVis = bar != null ? bar.getVisibility() : View.VISIBLE;
            final boolean hideBar = bar != null && bar != mDecor
                    && bar.getVisibility() == View.VISIBLE;
            if (hideBar) {
                bar.setVisibility(INVISIBLE);
            }

            final View srcRoot = captureSource();
            View scroller = sLastLifted == null ? null : sLastLifted.get();
            if (scroller != null && (scroller.getWidth() <= 0 || scroller.getHeight() <= 0)) {
                scroller = null;
            }

            if (scroller != null && !scroller.getClass().getName().contains("Scroll")
                    && !scroller.getClass().getName().contains("RecyclerView")) {
                View inner = realScroller(scroller);
                if (inner != null) {
                    scroller = inner;
                } else {
                    LiquidGlassLog.log(Log.VERBOSE, "topbar src: no inner scroller in "
                            + scroller.getClass().getName());
                }
            }
            try {
                Canvas canvas = new Canvas(mBackdrop);
                canvas.scale(BACKDROP_SCALE, BACKDROP_SCALE);
                if (!barVisible) {

                    for (int y = 0; y < bh; y++) {
                        for (int x = 0; x < bw; x++) {
                            mBackdrop.setPixel(x, y, 0xFFF7F8FA);
                        }
                    }
                } else if (scroller != null) {
                    final int glassRegionH = Math.max(1, titleBarHeight());
                    int[] locRootStart = new int[2];
                    int[] locScroller;
                    mDecor.getLocationInWindow(locRootStart);
                    scroller.getLocationInWindow(locScroller = new int[2]);
                    boolean located = false;
                    try {

                        View decorParent = (View) mDecor.getParent();
                        int[] curLoc = new int[2];
                        if (decorParent == null) {
                            mDecor.getLocationInWindow(curLoc);
                        } else {
                            int[] decorLoc = new int[2];
                            mDecor.getLocationInWindow(decorLoc);
                            curLoc[0] = decorLoc[0];
                            curLoc[1] = decorLoc[1];
                        }

                        int[] abs = new int[2];
                        collectAbsOffset(mDecor, scroller, abs);
                        if (abs[0] != Integer.MIN_VALUE) {
                            locScroller = new int[]{curLoc[0] + abs[0], curLoc[1] + abs[1]};
                            located = true;
                        }
                    } catch (Throwable ignored) {
                    }

                    float dy = locScroller[1] - locRootStart[1] - getTop();

            final int listScroll = scrollOffsetOf(scroller);
            if (hideBar) {
                bar.setVisibility(barVis);
            }

            if (Math.abs(dy) > OVERLAY_ALIGN_TOLERANCE_PX) {
                dy = 0f;
            }

            mBackdrop.eraseColor(0x00000000);
            canvas.translate(0, -dy - listScroll * BACKDROP_SCALE);

            canvas.translate(0, -scroller.getPaddingTop() * BACKDROP_SCALE);

            canvas.translate(0, -glassRegionH * BACKDROP_SCALE);

            canvas.translate(0, getTop() * BACKDROP_SCALE);
            canvas.save();
            try {
                scroller.draw(canvas);
            } catch (Throwable ignored) {
            }
                    canvas.restore();
                } else {
                    mBackdrop.eraseColor(0x00000000);
                    int[] locRoot = new int[2];
                    int[] locOnScreen = new int[2];
                    mDecor.getLocationInWindow(locRoot);
                    mDecor.getLocationOnScreen(locOnScreen);
                    final int rootTop = locRoot[1];
                    final int offsetY = locOnScreen[1] - locRoot[1] + barOffsetY();
                    canvas.translate(0, -offsetY);

                    for (View child : captureChain()) {
                        canvas.save();
                        canvas.translate(child.getLeft(), child.getTop() - rootTop);
                        try {
                            child.draw(canvas);
                        } catch (Throwable ignored) {
                        }
                        canvas.restore();
                    }
                }
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar overlay capture failed", t);
            } finally {
                if (hideBar) {
                    bar.setVisibility(barVis);
                }
                setVisibility(oldVis);
                mCapturing = false;
            }

            try {
                fillMissingRows();

                MiuixGlassPipeline.process(mBackdrop, mStrongBlurDp, mDark);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar overlay process failed", t);
            }
            try {

                buildProgressiveFade(bw, bh,
                        Math.round((titleBarHeight()) * BACKDROP_SCALE),
                        Math.round(getTop() * BACKDROP_SCALE));
            } catch (Throwable t) {
                LiquidGlassLog.logErr("topbar fade build failed", t);
            }

            if (DEBUG_DUMP_BACKDROP) {
                try {
                    java.io.File dir = getContext().getExternalFilesDir(null);
                    if (dir != null) {
                        if (!dir.exists()) {
                            dir.mkdirs();
                        }
                        java.io.FileOutputStream fos =
                                new java.io.FileOutputStream(new java.io.File(dir, "topbar_backdrop.png"));
                        mBackdrop.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        fos.close();
                    }
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("topbar dump failed", t);
                }
            }
            if (!mLogged) {
                mLogged = true;
                Log.i("BetterHeybox", "topbar overlay backdrop " + bw + "x" + bh);
            }
        }

        private void fillMissingRows() {
            if (mBackdrop == null || mBackdrop.isRecycled()) {
                return;
            }
            int w = mBackdrop.getWidth();
            int h = mBackdrop.getHeight();
            int[] row = new int[w];
            int[] first = null;
            int lastFilled = -1;
            for (int y = 0; y < h; y++) {
                mBackdrop.getPixels(row, 0, w, 0, y, w, 1);
                boolean has = false;
                for (int i = 0; i < w; i += 4) {
                    if ((row[i] >>> 24) != 0) {
                        has = true;
                        break;
                    }
                }
                if (!has) {
                    continue;
                }
                if (first == null) {
                    first = row.clone();

                    for (int fill = 0; fill < y; fill++) {
                        mBackdrop.setPixels(first, 0, w, 0, fill, w, 1);
                    }
                } else if (lastFilled >= 0 && y > lastFilled + 1) {

                    for (int fill = lastFilled + 1; fill < y; fill++) {
                        mBackdrop.setPixels(first, 0, w, 0, fill, w, 1);
                    }
                }
                first = row.clone();
                lastFilled = y;
            }
            if (first == null) {

                mBackdrop.eraseColor(mDark ? 0xFF181A1C : 0xFFF7F8FA);
                return;
            }
            for (int fill = lastFilled + 1; fill < h; fill++) {
                mBackdrop.setPixels(first, 0, w, 0, fill, w, 1);
            }
        }

        private int scrollOffsetOf(View scroller) {
            if (scroller == null) {
                return 0;
            }
            try {

                java.lang.reflect.Method getLm = scroller.getClass().getMethod("getLayoutManager");
                Object lm = getLm.invoke(scroller);
                if (lm != null) {
                    java.lang.reflect.Method m = lm.getClass().getMethod("computeVerticalScrollOffset");
                    Object v = m.invoke(lm);
                    if (v instanceof Integer) {
                        return Math.max(0, (Integer) v);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                return Math.max(0, scroller.getScrollY());
            } catch (Throwable t) {
                return 0;
            }
        }

        private int barOffsetY() {
            View bar = titleBarView();
            int barH = 0;
            if (bar != null && bar.getHeight() > 0) {
                barH = bar.getHeight();
            } else {
                barH = Math.round(22f * getResources().getDisplayMetrics().density);
            }
            return Math.max(BACKDROP_ROWS, barH * 2);
        }

        private View titleBarView() {
            for (View v : ACTIVE.keySet()) {
                if (v != null && v.isAttachedToWindow()) {
                    return v;
                }
            }
            return null;
        }

        private View captureSource() {
            try {
                View content = mDecor.findViewById(android.R.id.content);
                if (content != null && content.getVisibility() == View.VISIBLE) {
                    return content;
                }
            } catch (Throwable ignored) {
            }
            return mDecor;
        }

        private View captureRoot() {
            View content = null;
            try {
                content = mDecor.findViewById(android.R.id.content);
            } catch (Throwable ignored) {
            }
            if (content == null || content.getVisibility() != View.VISIBLE) {
                return content == null ? mDecor : content;
            }
            if (!(content instanceof ViewGroup)) {
                return content;
            }
            ViewGroup group = (ViewGroup) content;
            View best = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null || child == this || child.getVisibility() != View.VISIBLE
                        || child.getTop() > 8 || child.getWidth() <= 0) {
                    continue;
                }
                if (best == null || child.getWidth() * child.getHeight()
                        > best.getWidth() * best.getHeight()) {
                    best = child;
                }
            }
            return best != null ? best : content;
        }

        private ArrayList<View> captureChain() {
            ArrayList<View> out = new ArrayList<>();
            View content = captureRoot();
            if (!(content instanceof ViewGroup)) {
                out.add(content);
                return out;
            }
            ViewGroup group = (ViewGroup) content;
            int rootW = mDecor.getWidth() > 0 ? mDecor.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null || child == this || child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                if (child.getWidth() * 10 < rootW * 9 || child.getHeight() < 200) {
                    continue;
                }
                out.add(child);
            }
            if (out.isEmpty()) {
                out.add(content);
            }
            return out;
        }

        private void recycle() {
            if (mBackdrop != null && !mBackdrop.isRecycled()) {
                mBackdrop.recycle();
            }
            mBackdrop = null;
            if (mOutput != null && !mOutput.isRecycled()) {
                mOutput.recycle();
            }
            mOutput = null;
        }
    }

}
