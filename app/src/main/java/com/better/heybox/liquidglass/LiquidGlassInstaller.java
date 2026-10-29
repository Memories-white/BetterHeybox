package com.better.heybox.liquidglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.better.heybox.GlassProvider;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class LiquidGlassInstaller {

    private static final String ID_ROOT = "vg_main_root";
    private static final String ID_BAR = "rg_main";
    private static final String ID_TIPS = "vg_tips";
    private static final String ID_MID_TAB = "vg_mid_tab";
    private static final String ID_CONTENT = "fl_container";
    private static final String ID_VIDEO_FULL = "vg_fullscreen_video_container";

    private LiquidGlassInstaller() {
    }

    public static boolean isGlassEnabled(Activity activity) {
        return isLiquidGlassEnabled(activity) || isMiuixEnabled(activity);
    }

    public static boolean isLiquidGlassEnabled(Context context) {
        try {
            com.better.heybox.HeyboxPrefs.init(context);
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_LIQUID_GLASS, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isMiuixEnabled(Context context) {
        try {
            com.better.heybox.HeyboxPrefs.init(context);
            if (com.better.heybox.HeyboxPrefs.contains(
                    com.better.heybox.App.KEY_MIUIX_BOTTOM_BAR)) {
                return com.better.heybox.HeyboxPrefs.getBoolean(
                        com.better.heybox.App.KEY_MIUIX_BOTTOM_BAR, false);
            }
            // v0.9.4 stored MIUIX as a liquid-glass sub-style. Migrate it once
            // into the new independent, mutually exclusive switch.
            boolean legacyMiuix = "1".equals(com.better.heybox.HeyboxPrefs.getString(
                    com.better.heybox.App.KEY_GLASS_STYLE, "0"));
            if (legacyMiuix) {
                com.better.heybox.HeyboxPrefs.setBoolean(
                        com.better.heybox.App.KEY_MIUIX_BOTTOM_BAR, true);
                com.better.heybox.HeyboxPrefs.setBoolean(
                        com.better.heybox.App.KEY_LIQUID_GLASS, false);
                com.better.heybox.HeyboxPrefs.setString(
                        com.better.heybox.App.KEY_GLASS_STYLE, "0");
            }
            return legacyMiuix;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean prefersExternalLiquidGlass(Context context) {
        return !isMiuixEnabled(context) && GlassProvider.prefersHbmod(context);
    }

    public static boolean isGlassBarActive() {
        try {
            View bar = sTabBarRef != null ? sTabBarRef.get() : null;
            return bar != null && bar.isAttachedToWindow();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean readPublishHidden(Context context) {        try {
            com.better.heybox.HeyboxPrefs.init(context);
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_HIDE_ADD, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void applyImmersive(Activity activity) {
        try {
            GlassConfig.load(activity);
            WindowImmersiveController.apply(activity);
        } catch (Throwable ignored) {
        }
    }

    private static volatile java.lang.ref.WeakReference<Activity> sMainRef;

    private static Activity mainActivity() {
        java.lang.ref.WeakReference<Activity> ref = sMainRef;
        Activity main = ref != null ? ref.get() : null;
        return main != null && !main.isFinishing() && !main.isDestroyed() ? main : null;
    }

    public static void applyGlassEnabled(Activity activity) {
        try {
            if (prefersExternalLiquidGlass(activity)) {
                return;
            }
            Activity main = mainActivity();
            if (main == null) {
                return;
            }
            if (isGlassEnabled(activity)) {
                scheduleInstall(main);
            } else {
                uninstallGlass(main);
                applyImmersive(main);
                applyClassicImmersive(main);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply glass enabled failed", t);
        }
    }

    public static void applyGlassStyle(Activity activity) {
        try {
            Activity main = mainActivity();
            if (main == null) {
                main = activity;
            }
            GlassConfig.load(main);
            if (!isGlassEnabled(main) || prefersExternalLiquidGlass(main)) {
                return;
            }
            uninstallGlass(main);
            scheduleInstall(main);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply glass style failed", t);
        }
    }

    public static void scheduleInstall(Activity activity) {
        sMainRef = new java.lang.ref.WeakReference<>(activity);
        if (prefersExternalLiquidGlass(activity)) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> {
            try {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                applyImmersive(activity);
                if (!isGlassEnabled(activity)) {
                    applyClassicImmersive(activity);
                    return;
                }
                ViewGroup root = findViewByName(activity, ID_ROOT);
                if (root == null) {
                    LiquidGlassLog.log(android.util.Log.WARN,
                            "root view " + ID_ROOT + " not found, retry in 200ms");
                    final int gen = sSyncGen;
                    decor.postDelayed(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed()
                                && gen == sSyncGen && isGlassEnabled(activity)) {
                            ViewGroup r = findViewByName(activity, ID_ROOT);
                            if (r != null) {
                                install(activity, r);
                            }
                        }
                    }, 200L);
                    return;
                }
                if (root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) != null) {
                    View bar = sTabBarRef.get();
                    if (bar instanceof com.example.liquidglass.LiquidGlassTabBar) {
                        rebuildTabBar((com.example.liquidglass.LiquidGlassTabBar) bar);
                    }
                    return;
                }
                install(activity, root);
            } catch (Throwable t) {
                LiquidGlassLog.logErr("scheduleInstall failed", t);
            }
        });
    }

    private static final class BarSnapshot {
        int barIndex = -1;
        int tipsIndex = -1;
        int midIndex = -1;
        ViewGroup.LayoutParams barLp;
        ViewGroup.LayoutParams tipsLp;
        ViewGroup.LayoutParams midLp;
        android.graphics.drawable.Drawable barBackground;
        int barPadLeft, barPadTop, barPadRight, barPadBottom;
        int contentAboveRule;
        View legacyShadow;
    }

    private static volatile BarSnapshot sSnapshot;

    private static final class MiuixInsetSnapshot {
        final int left;
        final int top;
        final int right;
        final int bottom;
        final boolean clipToPadding;
        final boolean viewGroup;

        MiuixInsetSnapshot(View view) {
            left = view.getPaddingLeft();
            top = view.getPaddingTop();
            right = view.getPaddingRight();
            bottom = view.getPaddingBottom();
            viewGroup = view instanceof ViewGroup;
            clipToPadding = !viewGroup || ((ViewGroup) view).getClipToPadding();
        }
    }

    private static final WeakHashMap<View, MiuixInsetSnapshot> sMiuixInsetSnapshots =
            new WeakHashMap<>();
    private static java.lang.ref.WeakReference<ViewGroup> sMiuixInsetContentRef =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<ViewGroup> sMiuixInsetHostRef =
            new java.lang.ref.WeakReference<>(null);
    private static ViewTreeObserver.OnGlobalLayoutListener sMiuixInsetListener;
    private static int sMiuixInsetFallback;

    private static volatile int sSyncGen;

    private static void uninstallGlass(Activity activity) {
        try {
            ViewGroup root = findViewByName(activity, ID_ROOT);
            if (root == null || root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) == null) {
                return;
            }
            BarSnapshot snap = sSnapshot;
            sSyncGen++;
            cancelGlassAnimators();

            ViewGroup bar = findViewByName(activity, ID_BAR);
            ViewGroup tips = findViewByName(activity, ID_TIPS);
            View midTab = findViewByName(activity, ID_MID_TAB);
            View content = findViewByName(activity, ID_CONTENT);
            unwrapCheckedListener(bar);
            removeMidPreDraw(midTab);
            clearMiuixContentInsets();
            if (content != null && snap != null
                    && content.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams clp =
                        (RelativeLayout.LayoutParams) content.getLayoutParams();
                clp.getRules()[RelativeLayout.ABOVE] = snap.contentAboveRule;
                content.setLayoutParams(clp);
            }
            if (snap != null && snap.legacyShadow != null) {
                snap.legacyShadow.setVisibility(View.VISIBLE);
            }
            View host = root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG);

            removeFromParent(bar);
            removeFromParent(tips);
            removeFromParent(midTab);
            if (host != null) {
                root.removeView(host);
            }

            if (bar != null && snap != null) {
                bar.setBackground(snap.barBackground);
                bar.setPadding(snap.barPadLeft, snap.barPadTop,
                        snap.barPadRight, snap.barPadBottom);
                bar.setVisibility(View.VISIBLE);
                root.addView(bar, readdIndex(snap.barIndex, root), snap.barLp);
            }
            if (tips != null && snap != null && snap.tipsLp != null) {
                root.addView(tips, readdIndex(snap.tipsIndex, root), snap.tipsLp);
            }
            if (midTab != null && snap != null && snap.midLp != null) {
                midTab.setTranslationX(0);
                midTab.setVisibility(View.VISIBLE);
                restoreChildren(midTab);
                root.addView(midTab, readdIndex(snap.midIndex, root), snap.midLp);
            }
            resetGlassState();
            root.requestLayout();
            LiquidGlassLog.log(android.util.Log.INFO, "liquid glass uninstalled");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("uninstall glass failed", t);
        }
    }

    private static int readdIndex(int originalIndex, ViewGroup root) {
        return Math.max(0, Math.min(originalIndex, root.getChildCount()));
    }

    private static void removeFromParent(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    private static void cancelGlassAnimators() {
        if (sTabShiftAnimator != null) {
            sTabShiftAnimator.cancel();
        }
        if (sBarWidthAnimator != null) {
            sBarWidthAnimator.cancel();
        }
        if (sDropletSizeAnimator != null) {
            sDropletSizeAnimator.cancel();
        }
        if (sCenterAnimator != null) {
            sCenterAnimator.cancel();
        }
    }

    private static void resetGlassState() {
        clearMiuixContentInsets();
        sTabBarActive = false;
        sTabBarRef = EMPTY_BAR_REF;
        sRadioBarRef = EMPTY_RADIO_REF;
        sGlassCircleRef = null;
        sContentViewRef = null;
        sHostRef = null;
        sCenterRefStatic = null;
        sMidTabRef = EMPTY_MID_REF;
        sPlusHidden = false;
        sCircleMode = false;
        sLastTabs = -1;
        sStableTabs = -1;
        sBuildSig = "";
        sSyncing = false;
        sVisibleButtons.clear();
        sSearchTabIndex = -1;
        sAppliedOrder.clear();
        restoreHiddenSearchIcons();
        invalidateSearchIconCache();
        sBasePadBottom = 0;
        sInstallPadBottom = 0;
        sNavPad = 0;
        sSnapshot = null;
        resetWidthAnimState();
    }

    private static void configureMiuixContentInsets(ViewGroup content, ViewGroup host,
                                                     int fallbackInset) {
        clearMiuixContentInsets();
        if (content == null || host == null) {
            return;
        }
        sMiuixInsetContentRef = new java.lang.ref.WeakReference<>(content);
        sMiuixInsetHostRef = new java.lang.ref.WeakReference<>(host);
        sMiuixInsetFallback = Math.max(0, fallbackInset);
        sMiuixInsetListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                ViewGroup insetContent = sMiuixInsetContentRef.get();
                ViewGroup insetHost = sMiuixInsetHostRef.get();
                if (insetContent == null || insetHost == null) {
                    return;
                }
                int inset = insetHost.getHeight();
                if (inset <= 0) {
                    inset = sMiuixInsetFallback;
                }
                applyMiuixContentInsets(insetContent, inset);
            }
        };
        content.getViewTreeObserver().addOnGlobalLayoutListener(sMiuixInsetListener);
        applyMiuixContentInsets(content, sMiuixInsetFallback);
    }

    private static void applyMiuixContentInsets(ViewGroup content, int bottomInset) {
        if (content == null || bottomInset <= 0) {
            return;
        }
        Set<View> targets = new HashSet<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            View pageRoot = content.getChildAt(i);
            if (pageRoot == null) {
                continue;
            }
            boolean foundScrollable = collectMiuixInsetTargets(pageRoot, targets);
            if (!foundScrollable) {
                targets.add(pageRoot);
            }
        }

        Iterator<Map.Entry<View, MiuixInsetSnapshot>> iterator =
                sMiuixInsetSnapshots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<View, MiuixInsetSnapshot> entry = iterator.next();
            if (!targets.contains(entry.getKey())) {
                restoreMiuixInset(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }

        for (View target : targets) {
            MiuixInsetSnapshot snapshot = sMiuixInsetSnapshots.get(target);
            if (snapshot == null) {
                snapshot = new MiuixInsetSnapshot(target);
                sMiuixInsetSnapshots.put(target, snapshot);
            }
            int targetBottom = snapshot.bottom + bottomInset;
            if (target.getPaddingLeft() != snapshot.left
                    || target.getPaddingTop() != snapshot.top
                    || target.getPaddingRight() != snapshot.right
                    || target.getPaddingBottom() != targetBottom) {
                target.setPadding(snapshot.left, snapshot.top, snapshot.right, targetBottom);
            }
            if (target instanceof ViewGroup) {
                ((ViewGroup) target).setClipToPadding(false);
            }
        }
    }

    private static boolean collectMiuixInsetTargets(View pageRoot, Set<View> targets) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(pageRoot);
        View bestTarget = null;
        long bestArea = -1L;
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 1024) {
            View view = queue.removeFirst();
            if (isVerticalMiuixInsetTarget(view, pageRoot)) {
                long area = (long) Math.max(1, view.getWidth())
                        * Math.max(1, view.getHeight());
                if (area > bestArea) {
                    bestArea = area;
                    bestTarget = view;
                }
                // Do not inspect item views inside a scrolling viewport. Personal pages
                // contain horizontal RecyclerViews whose padding must remain untouched.
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                }
            }
        }
        if (bestTarget != null) {
            targets.add(bestTarget);
            return true;
        }
        return false;
    }

    private static boolean isVerticalMiuixInsetTarget(View view, View pageRoot) {
        if (view instanceof android.widget.AbsListView
                || view instanceof android.widget.ScrollView
                || view instanceof android.webkit.WebView) {
            return true;
        }
        String name = view.getClass().getName();
        if (name.endsWith("NestedScrollView")) {
            return true;
        }
        if (!name.endsWith("RecyclerView")) {
            return false;
        }

        // RecyclerView is used for both the full-page vertical feed and several
        // short horizontal carousels. Prefer its LayoutManager orientation when
        // available, then use viewport geometry as a safe fallback.
        try {
            Object layoutManager = view.getClass().getMethod("getLayoutManager").invoke(view);
            if (layoutManager != null) {
                Object orientation = layoutManager.getClass()
                        .getMethod("getOrientation").invoke(layoutManager);
                if (orientation instanceof Number) {
                    return ((Number) orientation).intValue() == 1;
                }
            }
        } catch (Throwable ignored) {
        }
        if (view.canScrollVertically(-1) || view.canScrollVertically(1)) {
            return true;
        }
        int pageHeight = pageRoot == null ? 0 : pageRoot.getHeight();
        return pageHeight <= 0 || view.getHeight() >= pageHeight / 3;
    }

    private static void clearMiuixContentInsets() {
        ViewGroup content = sMiuixInsetContentRef.get();
        ViewTreeObserver.OnGlobalLayoutListener listener = sMiuixInsetListener;
        if (content != null && listener != null) {
            try {
                ViewTreeObserver observer = content.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnGlobalLayoutListener(listener);
                }
            } catch (Throwable ignored) {
            }
        }
        for (Map.Entry<View, MiuixInsetSnapshot> entry : sMiuixInsetSnapshots.entrySet()) {
            restoreMiuixInset(entry.getKey(), entry.getValue());
        }
        sMiuixInsetSnapshots.clear();
        sMiuixInsetContentRef = new java.lang.ref.WeakReference<>(null);
        sMiuixInsetHostRef = new java.lang.ref.WeakReference<>(null);
        sMiuixInsetListener = null;
        sMiuixInsetFallback = 0;
    }

    private static void restoreMiuixInset(View view, MiuixInsetSnapshot snapshot) {
        if (view == null || snapshot == null) {
            return;
        }
        view.setPadding(snapshot.left, snapshot.top, snapshot.right, snapshot.bottom);
        if (snapshot.viewGroup && view instanceof ViewGroup) {
            ((ViewGroup) view).setClipToPadding(snapshot.clipToPadding);
        }
    }

    private static int sClassicBarHeight = -1;
    private static int sClassicBarPadBottom = -1;
    private static int sClassicMidMargin = -1;
    private static volatile boolean sClassicImmersive;

    private static void applyClassicImmersive(final Activity activity) {
        applyClassicImmersive(activity, 0);
    }

    private static final int CLASSIC_IMMERSIVE_MAX_RETRY = 10;

    private static void applyClassicImmersive(final Activity activity, final int attempt) {
        try {
            ViewGroup root = findViewByName(activity, ID_ROOT);
            ViewGroup bar = findViewByName(activity, ID_BAR);
            if (root == null || bar == null) {
                if (attempt >= CLASSIC_IMMERSIVE_MAX_RETRY) {
                    LiquidGlassLog.log(android.util.Log.WARN,
                            "classic immersive: view not ready after "
                                    + CLASSIC_IMMERSIVE_MAX_RETRY + " retries, giving up");
                    return;
                }
                activity.getWindow().getDecorView().postDelayed(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()
                            && sHostRef == null) {
                        applyClassicImmersive(activity, attempt + 1);
                    }
                }, 200L);
                return;
            }
            View midTab = findViewByName(activity, ID_MID_TAB);
            if (!GlassConfig.immersiveGestureNavigation) {
                restoreClassicImmersive(bar, midTab);
                return;
            }
            int navPad = computeClassicNavPad(root);
            if (navPad <= 0) {
                restoreClassicImmersive(bar, midTab);
                return;
            }
            if (!sClassicImmersive) {
                sClassicBarHeight = bar.getLayoutParams().height;
                sClassicBarPadBottom = bar.getPaddingBottom();
                if (midTab != null && midTab.getLayoutParams()
                        instanceof RelativeLayout.LayoutParams) {
                    sClassicMidMargin = ((RelativeLayout.LayoutParams)
                            midTab.getLayoutParams()).bottomMargin;
                }
                sClassicImmersive = true;
            }
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (sClassicBarHeight > 0) {
                lp.height = sClassicBarHeight + navPad;
                bar.setLayoutParams(lp);
            }
            bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                    bar.getPaddingRight(), sClassicBarPadBottom + navPad);
            if (midTab != null && sClassicMidMargin >= 0 && midTab.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams mlp =
                        (RelativeLayout.LayoutParams) midTab.getLayoutParams();
                mlp.bottomMargin = sClassicMidMargin + navPad;
                midTab.setLayoutParams(mlp);
            }
            bar.requestLayout();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("classic immersive failed", t);
        }
    }

    private static void restoreClassicImmersive(ViewGroup bar, View midTab) {
        if (!sClassicImmersive) {
            return;
        }
        try {
            if (bar != null) {
                if (sClassicBarHeight > 0) {
                    bar.getLayoutParams().height = sClassicBarHeight;
                    bar.setLayoutParams(bar.getLayoutParams());
                }
                bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(),
                        bar.getPaddingRight(), Math.max(sClassicBarPadBottom, 0));
            }
            if (midTab != null && sClassicMidMargin >= 0 && midTab.getLayoutParams()
                    instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams mlp =
                        (RelativeLayout.LayoutParams) midTab.getLayoutParams();
                mlp.bottomMargin = sClassicMidMargin;
                midTab.setLayoutParams(mlp);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("restore classic immersive failed", t);
        }
        sClassicImmersive = false;
    }

    private static int computeClassicNavPad(ViewGroup root) {
        try {
            WindowInsets wi = root.getRootWindowInsets();
            if (wi == null) {
                return 0;
            }
            int nav = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? wi.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : wi.getSystemWindowInsetBottom();
            if (nav <= 0) {
                return 0;
            }
            float density = root.getResources().getDisplayMetrics().density;
            return (int) Math.min(nav, density * 56f);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void install(Activity activity, ViewGroup root) {
        if (root.findViewWithTag(LiquidGlassHostLayout.GLASS_TAG) != null) {
            return;
        }

        final int gen = sSyncGen;
        ViewGroup bar = findViewByName(activity, ID_BAR);
        if (bar == null || bar.getParent() != root) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "nav bar " + ID_BAR + " not found (bar=" + (bar != null)
                            + ", parentMatch="
                            + (bar != null && bar.getParent() == root) + ")");
            return;
        }
        ViewGroup tips = findViewByName(activity, ID_TIPS);
        View midTab = findViewByName(activity, ID_MID_TAB);
        ViewGroup content = findViewByName(activity, ID_CONTENT);
        View videoFull = findViewByName(activity, ID_VIDEO_FULL);

        restoreClassicImmersive(bar, midTab);

        BarSnapshot snap = new BarSnapshot();
        snap.barIndex = root.indexOfChild(bar);
        snap.tipsIndex = tips != null ? root.indexOfChild(tips) : -1;
        snap.midIndex = midTab != null ? root.indexOfChild(midTab) : -1;
        snap.barLp = bar.getLayoutParams();
        snap.tipsLp = tips != null ? tips.getLayoutParams() : null;
        snap.midLp = midTab != null ? midTab.getLayoutParams() : null;
        snap.barBackground = bar.getBackground();
        snap.barPadLeft = bar.getPaddingLeft();
        snap.barPadTop = bar.getPaddingTop();
        snap.barPadRight = bar.getPaddingRight();
        snap.barPadBottom = bar.getPaddingBottom();
        snap.contentAboveRule = content != null
                && content.getLayoutParams() instanceof RelativeLayout.LayoutParams
                ? ((RelativeLayout.LayoutParams) content.getLayoutParams())
                        .getRules()[RelativeLayout.ABOVE] : 0;
        sSnapshot = snap;

        Context ctx = root.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int sideMargin = Math.round(density * 10f);

        RelativeLayout.LayoutParams barLp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
        RelativeLayout.LayoutParams hostLp = new RelativeLayout.LayoutParams(
                barLp.width, RelativeLayout.LayoutParams.WRAP_CONTENT);
        copyMargins(barLp, hostLp);
        hostLp.leftMargin += sideMargin;
        hostLp.rightMargin += sideMargin;
        hostLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        int[] hostRules = hostLp.getRules();
        for (int i = 0; i < hostRules.length; i++) {
            if (i != RelativeLayout.ALIGN_PARENT_BOTTOM) {
                hostRules[i] = 0;
            }
        }

        snap.legacyShadow = hideLegacyShadow(root, bar.getId());

        int barHeightSpec = bar.getLayoutParams().height;
        int navPad = computeNavInsetPadding(activity, root, bar);
        int origBarPadBottom = bar.getPaddingBottom();
        sNavPad = navPad;
        sInstallPadBottom = origBarPadBottom + navPad;

        boolean miuixDocked = isMiuixEnabled(activity);
        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, root, bar);
        if (miuixDocked) {
            host.setId(View.generateViewId());
        }

        root.removeView(bar);
        if (tips != null && tips.getParent() == root) {
            root.removeView(tips);
        }
        if (midTab != null && midTab.getParent() == root) {
            root.removeView(midTab);
        }

        int insertAt = videoFull != null && videoFull.getParent() == root
                ? root.indexOfChild(videoFull)
                : root.getChildCount();
        root.addView(host, insertAt, hostLp);
        sHostRef = host;
        applyBarSideMargins(host);

        FrameLayout.LayoutParams barFlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeightSpec > 0 ? barHeightSpec : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
        bar.setBackground(null);
        bar.setPadding(
                bar.getPaddingLeft(), bar.getPaddingTop(),
                bar.getPaddingRight(), 0);
        host.addView(bar, 0, barFlp);

        if (tips != null) {
            FrameLayout.LayoutParams tipsFlp = new FrameLayout.LayoutParams(
                    tips.getLayoutParams().width,
                    tips.getLayoutParams().height,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
            host.addView(tips, 1, tipsFlp);
        }
        if (midTab != null) {
            RelativeLayout.LayoutParams midLp =
                    (RelativeLayout.LayoutParams) midTab.getLayoutParams();
            FrameLayout.LayoutParams midFlp = new FrameLayout.LayoutParams(
                    midLp.width, midLp.height,
                    android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
            midFlp.leftMargin = midLp.leftMargin;
            midFlp.topMargin = midLp.topMargin;
            midFlp.rightMargin = midLp.rightMargin;
            midFlp.bottomMargin = midLp.bottomMargin;
            host.addView(midTab, host.getChildCount(), midFlp);
        }

        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), origBarPadBottom + navPad);

        attachGlassRenderer(activity, host, bar, tips, midTab, content, barHeightSpec, navPad);

        if (content != null && content.getParent() == root) {
            RelativeLayout.LayoutParams clp =
                    (RelativeLayout.LayoutParams) content.getLayoutParams();
            // MIUIX is a real translucent dock: the page must continue behind it so
            // the renderer can sample the pixels directly underneath the bar.
            clp.getRules()[RelativeLayout.ABOVE] = 0;
            content.setLayoutParams(clp);
            if (miuixDocked) {
                int estimatedBarHeight = barHeightSpec > 0
                        ? barHeightSpec : Math.max(bar.getMeasuredHeight(), Math.round(56f * density));
                configureMiuixContentInsets(content, host,
                        estimatedBarHeight + origBarPadBottom + navPad);
            }
        }

        root.requestLayout();
        root.invalidate();

        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean attached;
                    @Override
                    public void onGlobalLayout() {
                        if (attached) {
                            return;
                        }
                        attached = true;
                        root.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        if (gen != sSyncGen) {

                            return;
                        }
                        host.attach();
                        applyImmersive(activity);
                        if (!sTabBarActive) {
                            setupTabPopAnimation(bar);
                        }
                        InWindowTipWatcher.start(activity);
                        LiquidGlassLog.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " navPad=" + navPad
                                        + " barH=" + bar.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    private static final float CENTER_GAP_WEIGHT = 1.0f;
    private static final float FIT_TAB_MAX_WIDTH_DP = 96f;
    private static final float SELECTED_TAB_WEIGHT = 1.4f;
    private static final float OTHER_TAB_WEIGHT = 0.9f;

    private static final float MIN_TAB_WIDTH_DP = 52f;

    private static final float TAB_CONTENT_PAD_DP = 18f;

    private static final float FALLBACK_TAB_WIDTH_DP = 54f;

    private static final float MIN_PLUS_GAP_DP = 64f;

    private static final int MAX_SIDE_MARGIN_DP = 40;
    private static final long FIT_ANIM_MS = 380L;
    private static final float FIT_ANIM_TENSION = 1.1f;
    private static volatile boolean sTabBarActive;

    private static int sInstallPadBottom;
    private static int sNavPad;

    private static void applyNavPadState(ViewGroup host) {
        if (host == null || sInstallPadBottom <= 0) {
            return;
        }
        int target = GlassConfig.immersiveGestureNavigation
                ? Math.max(sInstallPadBottom - sNavPad, 0)
                : sInstallPadBottom;
        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                host.getPaddingRight(), target);
        sBasePadBottom = target;
    }

    private static void attachGlassRenderer(Activity activity, ViewGroup host,
                                            ViewGroup bar, ViewGroup tips, View midTab,
                                            ViewGroup content, int barHeightSpec, int navPad) {
        GlassConfig.load(activity);
        applyNavPadState(host);
        if (Build.VERSION.SDK_INT < 33 || content == null) {
            return;
        }
        if (bar instanceof android.widget.RadioGroup) {
            attachQwea0TabBar(activity, host,
                    (android.widget.RadioGroup) bar, tips, midTab,
                    content, barHeightSpec, navPad);
        } else {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "nav bar is not a RadioGroup, QWEA0 tab bar unavailable");
        }
    }

    public static void refreshGlassWith(Activity activity) {
        GlassConfig.load(activity);

        refreshGlass();

        try {
            View bar = sTabBarRef.get();
            if (bar instanceof com.example.liquidglass.LiquidGlassTabBar) {
                rebuildTabBar((com.example.liquidglass.LiquidGlassTabBar) bar);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("refresh tab bar failed", t);
        }
        if (activeGlassHost() == null) {

            Activity main = mainActivity();
            if (main != null) {
                applyClassicImmersive(main);
            }
        }
    }

    static void refreshGlass() {
        try {
            WindowImmersiveController.refresh();
            applyNavPadState(sHostRef);
            applyBarGeometry();
            View bar = sTabBarRef.get();
            if (bar instanceof ViewGroup) {
                bar.invalidate();
                View droplet = findDroplet((ViewGroup) bar);
                if (droplet != null) {
                    droplet.invalidate();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static View findDroplet(ViewGroup tabBar) {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View c = tabBar.getChildAt(i);
            if (c instanceof com.example.liquidglass.LiquidGlassView
                    && !(c instanceof com.example.liquidglass.LiquidGlassTabBar)) {
                return c;
            }
        }
        return null;
    }

    private static void applyQwea0Params(com.example.liquidglass.LiquidGlassView glass,
                                         ViewGroup content, float density, boolean adaptiveTint) {
        glass.setCornerRadius(999f);
        glass.setEnableDynamicBackground(false);
        glass.setBackdropSource(content);
        glass.setMaterial(com.example.liquidglass.GlassMaterial.REGULAR);
        glass.setRefractionHeight(60f * density);
        glass.setBevelWidth(16f * density);
        glass.setDispersionStrength(0.12f);
        glass.setEnableSensorHighlight(true);
        glass.setEnableAdaptiveTint(adaptiveTint);
    }

    private static void attachQwea0TabBar(Activity activity, ViewGroup host,
                                          android.widget.RadioGroup bar,
                                          ViewGroup tips, View midTab,
                                          ViewGroup content, int barHeightSpec,
                                          int navPad) {
        try {
            sHostRef = host;
            sDensity = host.getResources().getDisplayMetrics().density;
            sRadioBarRef = new java.lang.ref.WeakReference<>(bar);
            sContentViewRef = new java.lang.ref.WeakReference<>(content);
            sCenterRefStatic = null;

            final com.example.liquidglass.LiquidGlassTabBar tabBar =
                    isMiuixEnabled(activity)
                            ? new MiuixGlassTabBar(activity)
                            : new com.example.liquidglass.LiquidGlassTabBar(activity, null, 0);
            sTabBarRef = new java.lang.ref.WeakReference<>(tabBar);
            resetWidthAnimState();
            sBuildSig = "";
            sSearchTabIndex = -1;
            sLastTabs = -1;
            sStableTabs = -1;
            float density = host.getResources().getDisplayMetrics().density;

            boolean anyVisible = false;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View c = bar.getChildAt(i);
                if (c instanceof android.widget.RadioButton
                        && c.getVisibility() == View.VISIBLE) {
                    anyVisible = true;
                    break;
                }
            }
            if (!anyVisible) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "tabbar: no radio buttons found");
                return;
            }
            if (!(tabBar instanceof MiuixGlassTabBar)) {
                applyQwea0Params(tabBar, content, density, false);
            } else {
                ((MiuixGlassTabBar) tabBar).setMiuixBackdropSource(content);
                tabBar.setPadding(tabBar.getPaddingLeft(), tabBar.getPaddingTop(),
                        tabBar.getPaddingRight(), Math.max(0, navPad));
            }
            installTintOverride();

            host.addView(tabBar, host.getChildCount(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL));

            tabBar.setOnTabSelected(new kotlin.jvm.functions.Function1<Integer, kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke(Integer index) {
                    try {
                        if (tabBar instanceof MiuixGlassTabBar) {
                            ((MiuixGlassTabBar) tabBar).onMiuixSelectionChanged();
                        }
                        if (index != null && sSearchTabIndex >= 0
                                && index == sSearchTabIndex) {

                            restoreTabSelection(tabBar);
                            if (!openSearchPage(tabBar.getContext())) {
                                LiquidGlassLog.log(android.util.Log.WARN,
                                        "search-tab: openSearchPage failed, stay on current tab");
                            }
                            return kotlin.Unit.INSTANCE;
                        }
                        if (sSyncing || index == null || index < 0
                                || index >= sVisibleButtons.size()) {
                            return kotlin.Unit.INSTANCE;
                        }
                        if (applyTabWidths(tabBar.getSelectedIndex())) {
                            reanimateDroplet(tabBar);
                        }
                        android.widget.RadioButton rb = sVisibleButtons.get(index);
                        if (rb != null && !rb.isChecked()) {
                            rb.performClick();
                        }
                    } catch (Throwable t) {
                        LiquidGlassLog.logErr("tabbar->app failed", t);
                    }
                    return kotlin.Unit.INSTANCE;
                }
            });
            installRepeatClickRefresh(tabBar);

            boolean darkSeed = isSystemNight(activity);
            sChromeLight = !darkSeed;
            applyTabBarOverLight(tabBar, darkSeed);

            mountCenterButton(activity, host, midTab, tips);
            rebuildTabBar(tabBar);

            placeCenterNow(host, tabBar, sCenterRefStatic, 0);
            applyBarGeometry();

            if (!(tabBar instanceof MiuixGlassTabBar)) {
                startBackdropMeter(tabBar);
            }

            bar.setVisibility(View.INVISIBLE);

            setupTabSelectionSync(bar, tabBar);
            startTabVisibilitySync(bar, tabBar);

            ((LiquidGlassHostLayout) host).setExternalRendererActive(true);

            LiquidGlassLog.log(android.util.Log.INFO,
                    tabBar instanceof MiuixGlassTabBar
                            ? "renderer=MIUIX docked NavigationBar"
                            : "renderer=QWEA0 LiquidGlassTabBar (glass droplet selection)");
            sTabBarActive = true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("qwea0 tabbar unavailable", t);
        }
    }

    private static void mountCenterButton(Activity activity, ViewGroup host,
                                          View midTab, ViewGroup tips) {
        View centerHost = null;
        if (midTab != null && midTab.getParent() == host) {
            host.removeView(midTab);
            midTab.setVisibility(View.VISIBLE);

            final FrameLayout center = new FrameLayout(activity);
            center.setClickable(true);
            center.addView(midTab, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER));
            center.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (host instanceof LiquidGlassHostLayout) {
                        ((LiquidGlassHostLayout) host).popChild(midTab);
                    }
                    midTab.performClick();
                }
            });
            host.addView(center, host.getChildCount());
            centerHost = center;
        }
        if (tips != null && tips.getParent() == host) {
            host.removeView(tips);
            host.addView(tips, host.getChildCount());
        }
        sCenterRefStatic = centerHost;
        sMidTabRef = new java.lang.ref.WeakReference<>(
                midTab != null ? midTab : centerHost);
        if (midTab != null) {
            removeMidPreDraw(midTab);
            sMidPreDraw = new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        if (sPlusHidden) {
                            if (midTab.getVisibility() != View.GONE) {
                                midTab.setVisibility(View.GONE);
                            }
                        } else {
                            if (midTab.getVisibility() != View.VISIBLE) {
                                midTab.setVisibility(View.VISIBLE);
                            }
                            restoreChildren(midTab);
                        }
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            };
            midTab.getViewTreeObserver().addOnPreDrawListener(sMidPreDraw);
            sMidPreDrawView = new java.lang.ref.WeakReference<>(midTab);
        }
    }

    private static void startBackdropMeter(final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        final java.util.concurrent.atomic.AtomicReference<
                com.example.liquidglass.BackdropLuminanceMeter> meterHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.example.liquidglass.BackdropLuminanceMeter meter =
                new com.example.liquidglass.BackdropLuminanceMeter(tabBar,
                        new kotlin.jvm.functions.Function1<Float, kotlin.Unit>() {
                            @Override
                            public kotlin.Unit invoke(Float luma) {
                                try {
                                    if (!GlassConfig.adaptiveChrome) {
                                        boolean uiDark = (tabBar.getResources()
                                                .getConfiguration().uiMode
                                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                                                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                                        if (sChromeLight != !uiDark
                                                || sChromeForced) {
                                            sChromeForced = true;
                                            sChromeLight = !uiDark;
                                            final boolean d = uiDark;
                                            tabBar.post(() ->
                                                    applyTabBarOverLight(tabBar, d));
                                        }
                                        return kotlin.Unit.INSTANCE;
                                    }
                                    sChromeForced = false;
                                    com.example.liquidglass.BackdropLuminanceMeter
                                            m0 = meterHolder.get();
                                    boolean overLight =
                                            m0 != null && m0.isOverLight();
                                    if (overLight != sChromeLight) {
                                        sChromeLight = overLight;
                                        final boolean flip = overLight;
                                        tabBar.post(() -> {
                                            applyTabBarOverLight(tabBar,
                                                    !flip);
                                            LiquidGlassLog.log(
                                                    android.util.Log.INFO,
                                                    "backdrop flip: overLight="
                                                            + flip);
                                        });
                                    }
                                } catch (Throwable ignored) {
                                }
                                return kotlin.Unit.INSTANCE;
                            }
                        });
        meterHolder.set(meter);
        tabBar.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        try {
                            meter.start();
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        try {
                            meter.stop();
                        } catch (Throwable ignored) {
                        }
                    }
                });
        if (tabBar.isAttachedToWindow()) {
            try {
                meter.start();
                LiquidGlassLog.log(android.util.Log.INFO,
                        "backdrop meter started (already attached)");
            } catch (Throwable ignored) {
            }
        }
    }

    private static final String HOST_TITLE_BAR_CLASS =
            "com.max.hbcommon.component.HomeTitleBar";
    private static final String HOST_SEARCH_ICON_ID = "iv_home_search";

    private static final String HOST_HOME_TAB_ID = "rb_1";

    private static final String HOST_SEARCH_ACTIVITY_CLASS =
            "com.max.hbsearch.SearchNewActivity";

    private static final String HOST_SEARCH_COMPANION_CLASS =
            "com.max.hbsearch.SearchNewActivity$a";

    private static final String HOST_SEARCH_SRC = "main";

    private static final int HOST_SEARCH_PAGE_TYPE = 37;

    private static View findSearchIcon(android.content.Context context) {
        try {
            View titleBar = sSearchTitleBarRef == null ? null : sSearchTitleBarRef.get();
            View icon = sSearchIconRef == null ? null : sSearchIconRef.get();
            if (icon != null && titleBar != null && isValidSearchIcon(icon, titleBar)) {
                return icon;
            }
            if (icon != null || titleBar != null) {
                LiquidGlassLog.log(android.util.Log.DEBUG,
                        "search-tab: cached icon rejected (icon=" + (icon != null)
                                + " titleBar=" + (titleBar != null) + "), re-resolving");
                invalidateSearchIconCache();
            }
            Activity activity = activityOf(context);
            if (activity == null || activity.getWindow() == null) {
                return null;
            }
            View found = findTitleBarInTree(activity, true, false);
            if (found == null) {
                return null;
            }
            View foundIcon = searchIconIn(found);
            if (foundIcon == null || !isValidSearchIcon(foundIcon, found)) {
                return null;
            }
            sSearchTitleBarRef = new java.lang.ref.WeakReference<>(found);
            sSearchIconRef = new java.lang.ref.WeakReference<>(foundIcon);
            LiquidGlassLog.log(android.util.Log.INFO,
                    "search-tab: resolved title icon inside "
                            + found.getClass().getName());
            return foundIcon;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("search-tab: resolve title icon failed", t);
            return null;
        }
    }

    private static Activity activityOf(android.content.Context context) {
        android.content.Context ctx = context;
        for (int i = 0; i < 6 && ctx instanceof android.content.ContextWrapper
                && !(ctx instanceof Activity); i++) {
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        if (ctx instanceof Activity) {
            Activity a = (Activity) ctx;
            if (!a.isFinishing() && !a.isDestroyed()) {
                return a;
            }
            return null;
        }
        return mainActivity();
    }

    private static boolean isValidSearchIcon(View icon, View titleBar) {
        try {
            if (!(icon instanceof android.widget.ImageView) || titleBar == null) {
                return false;
            }
            if (!icon.isAttachedToWindow() || !titleBar.isAttachedToWindow()) {
                return false;
            }
            if (((android.widget.ImageView) icon).getDrawable() == null) {
                return false;
            }
            if (isRepurposedTitleBar(titleBar)) {
                return false;
            }
            if (!titleBar.isShown() || titleBar.getHeight() <= 0) {
                return false;
            }

            if (!icon.isShown() && !sHiddenSearchIcons.containsKey(icon)) {
                return false;
            }
            return titleBarOf(icon) == titleBar;
        } catch (Throwable t) {
            return false;
        }
    }

    private static View titleBarOf(View view) {
        int depth = 0;
        for (View v = view; v != null && depth < 24; depth++) {
            if (isTitleBarClass(v.getClass())) {
                return v;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return null;
    }

    private static boolean isTitleBarClass(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (HOST_TITLE_BAR_CLASS.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRepurposedTitleBar(View titleBar) {
        return titleBar != null && sRepurposedTitleBars.containsKey(titleBar);
    }

    private static View searchIconIn(View titleBar) {
        try {
            if (titleBar == null) {
                return null;
            }
            android.content.Context context = titleBar.getContext();
            int id = context.getResources().getIdentifier(
                    HOST_SEARCH_ICON_ID, "id", context.getPackageName());
            if (id == 0) {
                return null;
            }
            View v = titleBar.findViewById(id);
            return v instanceof android.widget.ImageView ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static View findTitleBarInTree(Activity activity, boolean requireSearchIcon,
                                           boolean allowRepurposed) {
        View root = activity.getWindow().getDecorView();
        if (root == null) {
            return null;
        }
        java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4096) {
            View v = queue.poll();
            if (v == null) {
                continue;
            }
            if (isTitleBarClass(v.getClass()) && v.isAttachedToWindow()
                    && v.isShown() && v.getHeight() > 0
                    && (allowRepurposed || !isRepurposedTitleBar(v))) {
                if (!requireSearchIcon) {
                    return v;
                }
                View icon = searchIconIn(v);
                if (icon != null && isValidSearchIcon(icon, v)) {
                    return v;
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            }
        }
        return null;
    }

    private static void invalidateSearchIconCache() {
        sSearchIconRef = new java.lang.ref.WeakReference<>(null);
        sSearchTitleBarRef = new java.lang.ref.WeakReference<>(null);
    }

    private static void invalidateSearchIconFor(View titleBar) {
        if (titleBar == null) {
            return;
        }
        View cachedBar = sSearchTitleBarRef == null ? null : sSearchTitleBarRef.get();
        View cachedIcon = sSearchIconRef == null ? null : sSearchIconRef.get();
        if (cachedBar == titleBar
                || (cachedIcon != null && titleBarOf(cachedIcon) == titleBar)) {
            invalidateSearchIconCache();
        }
    }

    private static void markRepurposedTitleBar(View titleBar) {
        if (titleBar == null) {
            return;
        }
        sRepurposedTitleBars.put(titleBar, Boolean.TRUE);
        invalidateSearchIconFor(titleBar);
        LiquidGlassLog.log(android.util.Log.INFO,
                "search-tab: title bar marked repurposed (iv_home_search = 设置)");
    }

    private static boolean isHomeTabSelected(android.content.Context context) {
        try {
            android.widget.RadioGroup bar = sRadioBarRef.get();
            if (bar == null || context == null) {
                return false;
            }
            int homeId = context.getResources().getIdentifier(
                    HOST_HOME_TAB_ID, "id", context.getPackageName());
            if (homeId == 0) {
                return false;
            }
            return bar.getCheckedRadioButtonId() == homeId;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void syncTopSearchIconVisibility(android.content.Context context,
                                                    boolean searchApplied) {
        try {
            if (!searchApplied) {
                restoreHiddenSearchIcons();
                return;
            }
            if (!isHomeTabSelected(context)) {
                return;
            }
            View icon = findSearchIcon(context);
            if (icon == null) {
                return;
            }
            boolean alreadyHidden = icon.getVisibility() != View.VISIBLE;
            sHiddenSearchIcons.put(icon, Boolean.TRUE);
            if (!alreadyHidden) {
                icon.setVisibility(View.INVISIBLE);
                LiquidGlassLog.log(android.util.Log.INFO,
                        "search-tab: top title icon hidden"
                                + " (search item lives in glass bar)");
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("search-tab: sync top icon visibility failed", t);
        }
    }

    private static void restoreHiddenSearchIcons() {
        if (sHiddenSearchIcons.isEmpty()) {
            return;
        }
        for (View v : new java.util.ArrayList<>(sHiddenSearchIcons.keySet())) {
            try {
                if (v != null && v.getVisibility() != View.VISIBLE) {
                    v.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {
            }
        }
        sHiddenSearchIcons.clear();
        LiquidGlassLog.log(android.util.Log.DEBUG,
                "search-tab: hidden top icons restored");
    }

    private static boolean isSearchInBar(android.content.Context context) {
        try {
            return com.better.heybox.HeyboxPrefs.getBoolean(
                    com.better.heybox.App.KEY_GLASS_SEARCH_IN_BAR, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void restoreTabSelection(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            int selected = 0;
            android.widget.RadioGroup bar = sRadioBarRef.get();
            if (bar != null) {
                int checked = bar.getCheckedRadioButtonId();
                for (int i = 0; i < sVisibleButtons.size(); i++) {
                    android.widget.RadioButton rb = sVisibleButtons.get(i);
                    if (rb != null && rb.getId() == checked) {
                        selected = i;
                        break;
                    }
                }
            }
            sSyncing = true;
            try {
                tabBar.setSelectedIndex(selected);
            } finally {
                sSyncing = false;
            }
            applyTabWidths(selected);
            applyTabBarOverLight(tabBar, !sChromeLight);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("restore tab selection failed", t);
        }
    }

    static boolean openSearchPage(android.content.Context context) {
        if (context == null) {
            return false;
        }

        try {
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(context, HOST_SEARCH_ACTIVITY_CLASS);
            intent.putExtra("src", HOST_SEARCH_SRC);
            intent.putExtra("page_type", HOST_SEARCH_PAGE_TYPE);
            if (!(context instanceof Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            LiquidGlassLog.log(android.util.Log.DEBUG,
                    "search-tab: opened search page (direct intent)"
                            + " src=" + HOST_SEARCH_SRC
                            + " page_type=" + HOST_SEARCH_PAGE_TYPE
                            + " pick_only=<absent>");
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "search-tab: direct search intent failed, trying companion: " + t);
        }

        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> outer = Class.forName(
                    HOST_SEARCH_ACTIVITY_CLASS, false, cl);
            Class<?> companion = Class.forName(
                    HOST_SEARCH_COMPANION_CLASS, false, cl);
            Object instance = null;
            try {
                java.lang.reflect.Field f = outer.getField("a");
                instance = f.get(null);
            } catch (Throwable ignored) {
            }
            if (instance != null) {
                for (java.lang.reflect.Method m : companion.getDeclaredMethods()) {
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length != 1 || types[0] != android.content.Context.class
                            || m.getReturnType() != android.content.Intent.class) {
                        continue;
                    }
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    Object intent = m.invoke(instance, context);
                    if (intent instanceof android.content.Intent) {
                        android.content.Intent it = (android.content.Intent) intent;

                        it.removeExtra("pick_only");
                        it.removeExtra("page_extras");
                        it.putExtra("src", HOST_SEARCH_SRC);
                        it.putExtra("page_type", HOST_SEARCH_PAGE_TYPE);
                        if (!(context instanceof Activity)) {
                            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                        context.startActivity(it);
                        LiquidGlassLog.log(android.util.Log.WARN,
                                "search-tab: opened search page (companion fallback,"
                                        + " rewritten to src=" + HOST_SEARCH_SRC
                                        + " page_type=" + HOST_SEARCH_PAGE_TYPE + ")");
                        return true;
                    }
                }
            } else {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "search page launcher: companion instance field a is null");
            }
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "search page launcher failed: " + t);
        }
        LiquidGlassLog.log(android.util.Log.WARN,
                "search-tab: cannot open search page; caller may only click a verified"
                        + " title icon (never performClick blindly)");
        return false;
    }

    private static java.util.List<android.widget.RadioButton> collectVisibleButtons(
            android.widget.RadioGroup bar) {
        java.util.List<android.widget.RadioButton> out = new java.util.ArrayList<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child instanceof android.widget.RadioButton
                    && child.getVisibility() == View.VISIBLE) {
                out.add((android.widget.RadioButton) child);
            }
        }
        return out;
    }

    static float barCornerRadiusPx(int heightPx, float den) {
        return Math.min(heightPx * 0.46f, 30f * den);
    }

    private static final int TOKEN_SEARCH = -1;

    private static java.util.List<Integer> parseTabOrder(String raw) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(s);
                if (id != 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            } catch (Throwable ignored) {
            }
        }
        return ids;
    }

    private static java.util.List<Integer> applyOrderTokens(
            java.util.List<android.widget.RadioButton> hostOrder, String rawOrder,
            boolean searchInBar) {
        java.util.List<Integer> out = new java.util.ArrayList<>(hostOrder.size() + 1);
        java.util.List<Integer> prefer = parseTabOrder(rawOrder);
        java.util.Set<Integer> hostIds = new java.util.HashSet<>();
        for (int i = 0; i < hostOrder.size(); i++) {
            hostIds.add(hostOrder.get(i).getId());
        }
        for (int k = 0; k < prefer.size(); k++) {
            int token = prefer.get(k);
            if (token == TOKEN_SEARCH) {
                if (searchInBar && !out.contains(TOKEN_SEARCH)) {
                    out.add(TOKEN_SEARCH);
                }
            } else if (hostIds.contains(token) && !out.contains(token)) {
                out.add(token);
            }
        }
        for (int i = 0; i < hostOrder.size(); i++) {
            int id = hostOrder.get(i).getId();
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        if (searchInBar && !out.contains(TOKEN_SEARCH)) {
            out.add(TOKEN_SEARCH);
        }
        return out;
    }

    static java.util.List<Integer> appliedOrderTokens(
            android.widget.RadioGroup bar, boolean searchInBar) {
        java.util.List<Integer> tokens = new java.util.ArrayList<>();
        try {
            if (bar == null) {
                return tokens;
            }
            GlassConfig.load(bar.getContext());
            tokens.addAll(applyOrderTokens(
                    collectVisibleButtons(bar), GlassConfig.tabOrder, searchInBar));
        } catch (Throwable t) {
            LiquidGlassLog.logErr("appliedOrderTokens failed", t);
        }
        return tokens;
    }

    private static android.widget.RadioButton findButtonById(
            android.widget.RadioGroup bar, int id) {
        if (bar == null || id == 0) {
            return null;
        }
        try {
            View v = bar.findViewById(id);
            return v instanceof android.widget.RadioButton
                    ? (android.widget.RadioButton) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable moduleSearchDrawable(android.content.Context context) {
        try {
            Drawable d = SearchGlyphDrawable.create(context);
            if (d != null) {
                return d;
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("search-tab: SearchGlyphDrawable failed", t);
        }

        try {
            Drawable d = context.getDrawable(android.R.drawable.ic_menu_search);
            return d == null ? null : d.mutate();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("search-tab: framework search icon failed", t);
            return null;
        }
    }

    private static Drawable hostTabDrawable(android.widget.RadioButton button) {
        if (button == null) {
            return null;
        }
        try {
            Drawable[] absolute = button.getCompoundDrawables();
            if (absolute != null && absolute.length > 1 && absolute[1] != null) {
                return absolute[1];
            }
        } catch (Throwable ignored) {
        }
        try {
            Drawable[] relative = button.getCompoundDrawablesRelative();
            if (relative != null && relative.length > 1) {
                return relative[1];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Hashes a small, normalized rendering of a host icon. Remote configuration
     * can update the pixels inside the same drawable instance, so object
     * identity alone is not a sufficient change signal. Normalizing to the
     * enabled/unselected state keeps ordinary tab selection animations out of
     * the fingerprint.
     */
    private static int normalizedDrawableFingerprint(
            Drawable drawable, android.widget.RadioButton button) {
        if (drawable == null || button == null) {
            return 0;
        }
        android.graphics.Rect oldBounds = drawable.copyBounds();
        int oldLevel = drawable.getLevel();
        int[] oldState = drawable.getState();
        android.graphics.Bitmap bitmap = null;
        try {
            drawable.setState(new int[]{android.R.attr.state_enabled});
            drawable.setLevel(0);
            drawable.jumpToCurrentState();
            int size = 18;
            bitmap = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(new android.graphics.Canvas(bitmap));
            int[] pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
            int hash = 0x811C9DC5;
            for (int pixel : pixels) {
                hash ^= pixel;
                hash *= 0x01000193;
            }
            return hash;
        } catch (Throwable ignored) {
            return 0;
        } finally {
            try {
                drawable.setBounds(oldBounds);
                drawable.setLevel(oldLevel);
                drawable.setState(oldState == null ? new int[0] : oldState);
                drawable.jumpToCurrentState();
            } catch (Throwable ignored) {
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /**
     * Adds the host presentation to the rebuild signature. Heybox can replace
     * bottom-tab labels and icons after launch through remote configuration
     * without changing the RadioButton IDs. ID-only signatures therefore keep
     * the early placeholder diamonds forever.
     */
    private static void appendHostPresentationSignature(
            java.lang.StringBuilder sig,
            android.widget.RadioGroup bar,
            java.util.List<Integer> tokens) {
        for (int token : tokens) {
            if (token == TOKEN_SEARCH) {
                sig.append("|search");
                continue;
            }
            android.widget.RadioButton button = findButtonById(bar, token);
            sig.append('|').append(token).append(':');
            if (button == null) {
                sig.append("missing");
                continue;
            }
            CharSequence title = button.getText();
            sig.append(title == null ? "" : title.toString()).append('@');
            Drawable icon = hostTabDrawable(button);
            if (icon == null) {
                sig.append("none");
                continue;
            }
            sig.append(icon.getClass().getName()).append('#');
            // Track replacement of the drawable object itself. ConstantState,
            // level and bitmap generation can change while a stateful icon is
            // merely selected; including them caused a rebuild 500 ms after
            // each tap and cancelled the MIUIX scale animation.
            sig.append(System.identityHashCode(icon));
            sig.append('/').append(icon.getIntrinsicWidth())
                    .append('x').append(icon.getIntrinsicHeight())
                    .append("/px=").append(normalizedDrawableFingerprint(icon, button));
            if (icon instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap bitmap =
                        ((android.graphics.drawable.BitmapDrawable) icon).getBitmap();
                if (bitmap != null) {
                    sig.append('/').append(System.identityHashCode(bitmap))
                            .append('/').append(bitmap.getWidth())
                            .append('x').append(bitmap.getHeight())
                            .append("/gen=").append(bitmap.getGenerationId());
                }
            }
        }
    }

    private static Drawable copyHostTabDrawable(android.widget.RadioButton button) {
        Drawable source = hostTabDrawable(button);
        if (source == null) {
            return null;
        }
        try {
            Drawable.ConstantState state = source.getConstantState();
            if (state != null) {
                Drawable copy;
                try {
                    copy = state.newDrawable(button.getResources(),
                            button.getContext().getTheme());
                } catch (Throwable ignored) {
                    copy = state.newDrawable(button.getResources());
                }
                if (copy != null) {
                    copy = copy.mutate();
                    copy.setBounds(source.getBounds());
                    copy.setLevel(source.getLevel());
                    if (copy.isStateful()) {
                        copy.setState(button.getDrawableState());
                    }
                    int sourceFingerprint = normalizedDrawableFingerprint(source, button);
                    int copyFingerprint = normalizedDrawableFingerprint(copy, button);
                    if (sourceFingerprint == 0 || copyFingerprint == 0
                            || sourceFingerprint == copyFingerprint) {
                        return copy;
                    }
                    LiquidGlassLog.log(android.util.Log.INFO,
                            "tabbar: stale ConstantState, snapshot live cloud icon");
                }
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tabbar: clone host icon failed", t);
        }

        // Remote image drawables do not always expose ConstantState. Rasterize
        // the currently rendered icon instead of sharing and mutating Heybox's
        // own drawable instance.
        try {
            int width = source.getIntrinsicWidth();
            int height = source.getIntrinsicHeight();
            if (width <= 0) {
                width = Math.max(1, Math.round(24f * button.getResources()
                        .getDisplayMetrics().density));
            }
            if (height <= 0) {
                height = width;
            }
            width = Math.max(1, Math.min(width, 256));
            height = Math.max(1, Math.min(height, 256));
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Rect oldBounds = source.copyBounds();
            try {
                source.setBounds(0, 0, width, height);
                source.draw(canvas);
            } finally {
                source.setBounds(oldBounds);
            }
            return new android.graphics.drawable.BitmapDrawable(
                    button.getResources(), bitmap);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tabbar: rasterize host icon failed", t);
            return null;
        }
    }

    private static String resourceEntryName(View view) {
        if (view == null || view.getId() == View.NO_ID) {
            return "";
        }
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static CharSequence classicTabTitle(android.widget.RadioButton button) {
        String entry = resourceEntryName(button);
        switch (entry) {
            case "rb_1":
                return "首页";
            case "rb_2":
                return "热点";
            case "rb_4":
                return "游戏库";
            case "rb_5":
                return "我";
            default:
                return button == null ? "" : button.getText();
        }
    }

    private static String classicTabDrawableName(android.widget.RadioButton button) {
        String entry = resourceEntryName(button);
        switch (entry) {
            case "rb_1":
                return "nav_bar_recommend";
            case "rb_2":
                return "nav_bar_bbs";
            case "rb_4":
                return "nav_bar_store";
            case "rb_5":
                return "nav_bar_me";
            default:
                return "";
        }
    }

    private static Drawable classicTabDrawable(android.widget.RadioButton button) {
        if (button == null) {
            return null;
        }
        String name = classicTabDrawableName(button);
        if (name.isEmpty()) {
            return copyHostTabDrawable(button);
        }
        try {
            android.content.Context context = button.getContext();
            int id = context.getResources().getIdentifier(
                    name, "drawable", context.getPackageName());
            if (id != 0) {
                Drawable drawable = context.getDrawable(id);
                if (drawable != null) {
                    return drawable.mutate();
                }
            }
            LiquidGlassLog.log(android.util.Log.WARN,
                    "tabbar: classic icon unavailable " + name);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tabbar: load classic icon failed " + name, t);
        }
        return copyHostTabDrawable(button);
    }

    private static void appendClassicPresentationSignature(
            java.lang.StringBuilder sig,
            android.widget.RadioGroup bar,
            java.util.List<Integer> tokens) {
        for (int token : tokens) {
            if (token == TOKEN_SEARCH) {
                sig.append("|classic-search");
                continue;
            }
            android.widget.RadioButton button = findButtonById(bar, token);
            sig.append("|classic:").append(token).append(':')
                    .append(resourceEntryName(button)).append(':')
                    .append(classicTabDrawableName(button));
        }
    }

    private static void rebuildTabBar(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            android.widget.RadioGroup bar = sRadioBarRef.get();
            ViewGroup host = sHostRef;
            if (bar == null || host == null || tabBar == null) {
                return;
            }
            android.content.Context context = tabBar.getContext();
            GlassConfig.load(context);
            boolean publishHidden = readPublishHidden(context);

            java.util.List<android.widget.RadioButton> hostOrder = collectVisibleButtons(bar);
            if (hostOrder.isEmpty()) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "tabbar: no radio buttons found");
                return;
            }
            boolean searchInBar = isSearchInBar(context);
            Drawable searchDrawable = searchInBar ? moduleSearchDrawable(context) : null;

            boolean searchUsable = searchDrawable != null;
            if (searchInBar && !searchUsable) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "search-tab: drawable unavailable, skip");
            }

            java.util.List<Integer> tokens =
                    applyOrderTokens(hostOrder, GlassConfig.tabOrder, searchUsable);
            if (tokens.isEmpty()) {
                return;
            }

            LiquidGlassLog.log(android.util.Log.DEBUG,
                    "search-tab: index=" + sSearchTabIndex
                            + ", buttons=" + sVisibleButtons.size()
                            + ", tokens=" + tokens.size());

            java.lang.StringBuilder sig = new java.lang.StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                sig.append(tokens.get(i)).append(',');
            }
            sig.append('|').append(GlassConfig.fitTabs ? 'F' : 'f')
                    .append(publishHidden ? 'P' : 'p')
                    .append(searchDrawable != null ? 'S' : 's')
                    .append(GlassConfig.followCloudBottomBar ? 'C' : 'c');
            if (GlassConfig.followCloudBottomBar) {
                appendHostPresentationSignature(sig, bar, tokens);
            } else {
                appendClassicPresentationSignature(sig, bar, tokens);
            }
            String nextSig = sig.toString();
            if (nextSig.equals(sBuildSig)) {

                int appliedCount = sVisibleButtons.size();
                LiquidGlassLog.log(android.util.Log.DEBUG,
                        "search-tab: index=" + sSearchTabIndex
                                + ", buttons=" + sVisibleButtons.size()
                                + ", applied=" + appliedCount);

                int expectedSearchIndex = sAppliedOrder.indexOf(TOKEN_SEARCH);
                boolean aligned = sVisibleButtons.size() == sAppliedOrder.size();
                if (aligned) {
                    for (int i = 0; i < sAppliedOrder.size(); i++) {
                        int token = sAppliedOrder.get(i);
                        android.widget.RadioButton rb = sVisibleButtons.get(i);
                        if (token == TOKEN_SEARCH) {
                            if (rb != null) {
                                aligned = false;
                                break;
                            }
                        } else if (rb == null || rb.getId() != token) {
                            aligned = false;
                            break;
                        }
                    }
                }
                if (!aligned || sSearchTabIndex != expectedSearchIndex) {
                    LiquidGlassLog.log(android.util.Log.WARN,
                            "search-tab: index mismatch, forcing rebuild (applied="
                                    + sAppliedOrder
                                    + ", buttons=" + sVisibleButtons.size()
                                    + ", index=" + sSearchTabIndex
                                    + ", expected=" + expectedSearchIndex + ")");
                    sBuildSig = "";
                    restoreHiddenSearchIcons();
                    return;
                }
                boolean searchApplied = sSearchTabIndex >= 0
                        && findTabsRow(tabBar, appliedCount) != null;
                syncTopSearchIconVisibility(context, searchApplied);
                return;
            }
            sBuildSig = nextSig;

            java.util.List<com.example.liquidglass.LiquidGlassTabBar.TabItem> items =
                    new java.util.ArrayList<>(tokens.size());
            java.util.List<android.widget.RadioButton> buttons =
                    new java.util.ArrayList<>(tokens.size());
            int nextSearchIndex = -1;
            for (int i = 0; i < tokens.size(); i++) {
                int token = tokens.get(i);
                if (token == TOKEN_SEARCH) {
                    if (searchDrawable == null) {
                        continue;
                    }
                    items.add(new com.example.liquidglass.LiquidGlassTabBar.TabItem(
                            "搜索", searchDrawable));
                    buttons.add(null);
                    nextSearchIndex = items.size() - 1;
                    continue;
                }
                android.widget.RadioButton rb = findButtonById(bar, token);
                if (rb == null) {

                    continue;
                }
                Drawable icon = GlassConfig.followCloudBottomBar
                        ? copyHostTabDrawable(rb) : classicTabDrawable(rb);
                CharSequence title = GlassConfig.followCloudBottomBar
                        ? rb.getText() : classicTabTitle(rb);
                items.add(new com.example.liquidglass.LiquidGlassTabBar.TabItem(
                        title, icon));
                buttons.add(rb);
            }
            if (items.isEmpty()) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "tabbar: no items built, skip");
                return;
            }
            if (nextSearchIndex >= 0) {
                LiquidGlassLog.log(android.util.Log.INFO,
                        "search-tab: appended index=" + nextSearchIndex
                                + " (token order=" + tokens + ")");
            }

            sVisibleButtons.clear();
            sVisibleButtons.addAll(buttons);

            sAppliedOrder.clear();
            sAppliedOrder.addAll(tokens);
            sSyncing = true;
            try {
                tabBar.setTabs(items);
                sSearchTabIndex = nextSearchIndex;
                applyBarMode(bar, tabBar);
                int checked = bar.getCheckedRadioButtonId();
                int selected = 0;
                for (int i = 0; i < sVisibleButtons.size(); i++) {
                    android.widget.RadioButton rb = sVisibleButtons.get(i);
                    if (rb != null && rb.getId() == checked) {
                        selected = i;
                        break;
                    }
                }
                applyTabWidths(selected);
                tabBar.setSelectedIndex(selected);
                applyTabBarOverLight(tabBar, !sChromeLight);
                tabBar.requestLayout();

                int appliedCount = sVisibleButtons.size();
                boolean searchApplied = sSearchTabIndex >= 0
                        && findTabsRow(tabBar, appliedCount) != null;
                syncTopSearchIconVisibility(context, searchApplied);
                if (searchApplied) {

                    attachPanelEntryToSearchTab(tabBar, items.size());
                    if (context instanceof Activity) {
                        attachTitleBarPanelEntry((Activity) context);
                    }
                }
            } finally {
                sSyncing = false;
            }
            final ViewGroup h2 = host;
            final com.example.liquidglass.LiquidGlassTabBar t2 = tabBar;
            host.post(() -> placeCenterNow(h2, t2, sCenterRefStatic, 0));
            LiquidGlassLog.log(android.util.Log.INFO,
                    "glass tab bar rebuilt: tabs=" + items.size()
                            + " order=" + sAppliedOrder
                            + " publishHidden=" + publishHidden
                            + " searchInBar=" + (sSearchTabIndex >= 0));
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass tab bar rebuild failed", t);
        }
    }

    private static android.content.Context configContext() {
        Activity main = mainActivity();
        if (main != null) {
            return main;
        }
        View bar = sTabBarRef.get();
        if (bar != null) {
            return bar.getContext();
        }
        ViewGroup host = sHostRef;
        return host == null ? null : host.getContext();
    }

    private static java.util.List<Integer> currentOrderedTokens() {
        if (!sAppliedOrder.isEmpty()) {
            return new java.util.ArrayList<>(sAppliedOrder);
        }
        android.widget.RadioGroup bar = sRadioBarRef.get();
        if (bar == null) {
            return new java.util.ArrayList<>();
        }
        GlassConfig.load(bar.getContext());
        android.content.Context ctx = configContext();
        boolean searchInBar = ctx != null && isSearchInBar(ctx);
        return applyOrderTokens(
                collectVisibleButtons(bar), GlassConfig.tabOrder, searchInBar);
    }

    private static boolean hasGlassBar() {
        View bar = sTabBarRef.get();
        if (bar instanceof com.example.liquidglass.LiquidGlassTabBar) {
            return true;
        }
        return false;
    }

    static java.util.List<String> visibleTabLabels() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        try {
            if (!hasGlassBar()) {
                return labels;
            }
            java.util.List<Integer> tokens = currentOrderedTokens();
            android.widget.RadioGroup bar = sRadioBarRef.get();
            for (int i = 0; i < tokens.size(); i++) {
                int token = tokens.get(i);
                if (token == TOKEN_SEARCH) {
                    labels.add("搜索");
                    continue;
                }
                android.widget.RadioButton rb = bar == null
                        ? null : findButtonById(bar, token);
                CharSequence title = rb == null ? null : rb.getText();
                labels.add(title == null ? "" : title.toString());
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("visibleTabLabels failed", t);
        }
        return labels;
    }

    static void moveTabOrder(int from, int to) {
        try {
            java.util.List<Integer> tokens = currentOrderedTokens();
            int count = tokens.size();
            if (count <= 1) {
                return;
            }
            int f = from < 0 ? 0 : (from >= count ? count - 1 : from);
            int target = to < 0 ? 0 : (to >= count ? count - 1 : to);
            if (f == target) {
                return;
            }
            Integer moved = tokens.remove(f);
            tokens.add(target, moved);
            java.lang.StringBuilder idList = new java.lang.StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                int token = tokens.get(i);
                if (token == 0) {
                    continue;
                }
                if (idList.length() > 0) {
                    idList.append(',');
                }
                idList.append(token);
            }
            GlassConfig.tabOrder = idList.toString();

            sAppliedOrder.clear();
            sAppliedOrder.addAll(tokens);
            android.content.Context ctx = configContext();
            if (ctx != null) {
                try {
                    GlassConfig.save(ctx);
                } catch (Throwable saveError) {
                    LiquidGlassLog.logErr("tab order save failed", saveError);
                }
            }
            Activity main = mainActivity();
            if (main != null && main.getWindow() != null) {
                main.getWindow().getDecorView().post(() -> refreshGlassWith(main));
            }
            LiquidGlassLog.log(android.util.Log.INFO,
                    "tab order moved: " + f + " -> " + target
                            + " order=" + GlassConfig.tabOrder);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("moveTabOrder failed", t);
        }
    }

    static void resetTabOrder() {
        try {
            GlassConfig.tabOrder = "";
            android.content.Context ctx = configContext();
            if (ctx != null) {
                try {
                    GlassConfig.save(ctx);
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("tab order save failed", t);
                }
            }
            Activity main = mainActivity();
            if (main != null && main.getWindow() != null) {
                main.getWindow().getDecorView().post(() -> refreshGlassWith(main));
            }
            LiquidGlassLog.log(android.util.Log.INFO,
                    "tab order reset to host order");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("resetTabOrder failed", t);
        }
    }

    private static void attachTitleBarPanelEntry(Activity activity) {
        try {
            if (activity == null || prefersExternalLiquidGlass(activity)) {
                return;
            }
            if (activity.getWindow() == null) {
                return;
            }
            View titleBar = findTitleBarInTree(activity, false, true);
            if (titleBar == null) {
                LiquidGlassLog.log(android.util.Log.DEBUG,
                        "title bar panel entry: no visible HomeTitleBar found");
                return;
            }
            titleBar.setOnLongClickListener(v -> {
                GlassSettingsSheet.show(activity);
                return true;
            });
        } catch (Throwable t) {
            LiquidGlassLog.logErr("title bar panel entry failed", t);
        }
    }

    private static void attachPanelEntryToSearchTab(
            com.example.liquidglass.LiquidGlassTabBar tabBar, int tabCount) {
        if (!(tabBar.getContext() instanceof Activity) || tabCount <= 1) {
            return;
        }
        final Activity activity = (Activity) tabBar.getContext();
        if (prefersExternalLiquidGlass(activity)) {
            return;
        }
        tabBar.post(() -> {
            try {
                ViewGroup row = findTabsRow(tabBar, tabCount);
                if (row == null || row.getChildCount() == 0) {
                    return;
                }
                View last = row.getChildAt(row.getChildCount() - 1);
                if (last == null || Boolean.TRUE.equals(sGlassEntries.get(last))) {
                    return;
                }
                sGlassEntries.put(last, Boolean.TRUE);
                last.setOnLongClickListener(v -> {
                    GlassSettingsSheet.show(activity);
                    return true;
                });
            } catch (Throwable t) {
                LiquidGlassLog.logErr("search tab panel entry failed", t);
            }
        });
    }

    private static ViewGroup findTabsRow(ViewGroup bar, int tabCount) {
        java.util.ArrayDeque<ViewGroup> queue = new java.util.ArrayDeque<>();
        queue.add(bar);
        while (!queue.isEmpty()) {
            ViewGroup group = queue.poll();
            if (group != bar && group.getChildCount() == tabCount) {
                return group;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup && child != bar) {
                    queue.add((ViewGroup) child);
                }
            }
        }
        return null;
    }

    private static void startTabVisibilitySync(
            final android.widget.RadioGroup bar,
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        final int gen = sSyncGen;
        bar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != sSyncGen) {

                    return;
                }
                if (!bar.isAttachedToWindow()) {
                    return;
                }
                try {
                    rebuildTabBar(tabBar);
                    syncPlusButton(bar, tabBar);
                } catch (Throwable t) {
                    LiquidGlassLog.logErr("tab visibility sync failed", t);
                }
                bar.postDelayed(this, 500L);
            }
        }, 1500L);
    }

    private static boolean syncPlusButton(android.widget.RadioGroup bar,
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        return applyBarMode(bar, tabBar);
    }

    private static boolean applyBarMode(android.widget.RadioGroup bar,
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        View barV = sTabBarRef.get();
        ViewGroup host = sHostRef;
        View center = sCenterRefStatic;
        View mid = sMidTabRef == null ? null : sMidTabRef.get();
        if (barV == null || host == null || center == null) {
            return false;
        }
        int visibleTabs = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c instanceof android.widget.RadioButton
                    && c.getVisibility() == View.VISIBLE) {
                visibleTabs++;
            }
        }

        if (visibleTabs == sLastTabs) {
            sStableTabs = visibleTabs;
        }
        sLastTabs = visibleTabs;
        int stableTabs = sStableTabs >= 0 ? sStableTabs : visibleTabs;
        boolean miuixDocked = tabBar instanceof MiuixGlassTabBar;
        boolean publishHidden = readPublishHidden(tabBar.getContext());
        boolean circle;
        boolean wantHidden;
        if (miuixDocked) {
            circle = false;
            wantHidden = publishHidden || stableTabs % 2 == 1 || stableTabs == 0;
        } else if (publishHidden) {
            circle = false;
            wantHidden = true;
        } else if (com.better.heybox.hooks.BottomTabHook.isAnyTabHidden()
                && GlassConfig.barLayoutMode == 0) {
            circle = false;
            wantHidden = false;
        } else {
            int mode = GlassConfig.barLayoutMode;
            if (mode == 0) {
                mode = stableTabs % 2 == 1 ? 2 : 1;
            }
            circle = mode == 2;
            wantHidden = !circle && (stableTabs % 2 == 1 || stableTabs == 0);
        }
        sCircleMode = circle;
        boolean changed = false;
        if (mid != null) {
            boolean hiddenNow = mid.getVisibility() == View.GONE
                    || allChildrenGone(mid);
            if (circle || !wantHidden) {
                if (hiddenNow) {
                    mid.setVisibility(View.VISIBLE);
                    restoreChildren(mid);
                    changed = true;
                }
            } else if (!hiddenNow) {
                mid.setVisibility(View.GONE);
                changed = true;
            }
        }
        sPlusHidden = wantHidden;
        applyBarSideMargins(host);
        if (tabBar.getChildCount() == 0
                || !(tabBar.getChildAt(0) instanceof ViewGroup)) {
            return true;
        }
        ViewGroup row = (ViewGroup) tabBar.getChildAt(0);
        boolean hasGap = false;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof android.widget.Space) {
                hasGap = true;
                break;
            }
        }
        boolean wantGap = !circle && !wantHidden;
        if (wantGap && !hasGap) {
            insertCenterGap(tabBar.getContext(), tabBar);
            changed = true;
        } else if (!wantGap && hasGap) {
            for (int i = row.getChildCount() - 1; i >= 0; i--) {
                if (row.getChildAt(i) instanceof android.widget.Space) {
                    row.removeViewAt(i);
                }
            }
            changed = true;
        }
        float den = sDensity > 0 ? sDensity : 3f;
        int barH = barV.getHeight();
        int circleSize = barH > 0 ? barH : Math.round(56 * den);
        int circleGap = Math.round(8 * den);
        if (barV.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams blp =
                    (FrameLayout.LayoutParams) barV.getLayoutParams();
            int wantMargin = circle ? circleSize + circleGap : 0;
            if (blp.rightMargin != wantMargin) {
                blp.rightMargin = wantMargin;
                barV.setLayoutParams(blp);
                changed = true;
            }
        }
        boolean tabLayoutChanged = applyTabWidths();
        changed |= tabLayoutChanged;
        if (tabLayoutChanged) {
            reanimateDroplet(tabBar);
        }
        if (circle) {
            View glass = sGlassCircleRef == null ? null : sGlassCircleRef.get();
            if (glass != null && glass.getParent() != center) {
                glass = null;
            }
            if (glass == null && center instanceof ViewGroup) {
                glass = buildGlassCircle(tabBar.getContext());
                if (glass != null) {
                    sGlassCircleRef = new java.lang.ref.WeakReference<>(glass);
                    ((ViewGroup) center).addView(glass, 0,
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                    changed = true;
                }
            }
            if (glass != null && glass.getVisibility() != View.VISIBLE) {
                glass.setVisibility(View.VISIBLE);
                changed = true;
            }
            if (center.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams clp =
                        (FrameLayout.LayoutParams) center.getLayoutParams();
                int wantGravity = android.view.Gravity.END
                        | android.view.Gravity.CENTER_VERTICAL;
                if (clp.width != circleSize || clp.height != circleSize
                        || clp.gravity != wantGravity || clp.leftMargin != 0
                        || clp.topMargin != 0 || clp.rightMargin != 0
                        || clp.bottomMargin != 0) {
                    clp.width = circleSize;
                    clp.height = circleSize;
                    clp.gravity = wantGravity;
                    clp.leftMargin = 0;
                    clp.topMargin = 0;
                    clp.rightMargin = 0;
                    clp.bottomMargin = 0;
                    center.setLayoutParams(clp);
                    changed = true;
                }
            }
            if (center.getVisibility() != View.VISIBLE) {
                center.setVisibility(View.VISIBLE);
                changed = true;
            }
        } else {
            View glass = sGlassCircleRef == null ? null : sGlassCircleRef.get();
            if (glass != null && glass.getVisibility() != View.GONE) {
                glass.setVisibility(View.GONE);
                changed = true;
            }
            int wantVis = wantHidden ? View.GONE : View.VISIBLE;
            if (center.getVisibility() != wantVis) {
                center.setVisibility(wantVis);
                changed = true;
            }
        }
        if (changed) {
            row.requestLayout();
            host.requestLayout();
        }
        if (!circle) {
            final ViewGroup h2 = host;
            final View c2 = center;
            final com.example.liquidglass.LiquidGlassTabBar t2 = tabBar;
            host.post(() -> placeCenterNow(h2, t2, c2, 0));
        }
        return changed;
    }

    private static boolean fitVisibleTabsEffective(int visibleTabs) {
        if (!GlassConfig.fitTabs) {
            return false;
        }
        try {
            android.widget.RadioGroup bar = sRadioBarRef.get();
            int named = 0;
            if (bar != null) {
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    if (!(child instanceof android.widget.RadioButton)) {
                        continue;
                    }
                    CharSequence title = ((android.widget.RadioButton) child).getText();
                    if (title == null || title.toString().trim().isEmpty()) {
                        continue;
                    }
                    named++;
                }
            }
            if (sSearchTabIndex >= 0) {
                named++;
            }
            return visibleTabs > 0 && visibleTabs < named;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static View buildGlassCircle(Context context) {
        try {
            View content = sContentViewRef == null ? null : sContentViewRef.get();
            com.example.liquidglass.LiquidGlassView glass =
                    new com.example.liquidglass.LiquidGlassView(context, null, 0);
            glass.setCornerRadius(999f);
            glass.setEnableDynamicBackground(false);
            if (content != null) {
                glass.setBackdropSource(content);
            }
            glass.setMaterial(com.example.liquidglass.GlassMaterial.REGULAR);
            float den = sDensity > 0 ? sDensity : 3f;
            glass.setRefractionHeight(28f * den);
            glass.setBevelWidth(10f * den);
            glass.setDispersionStrength(0.12f);
            glass.setEnableSensorHighlight(true);
            glass.setEnableAdaptiveTint(false);
            return glass;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass circle build failed", t);
            return null;
        }
    }

    private static boolean allChildrenGone(View view) {
        if (!(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        if (group.getChildCount() == 0) {
            return false;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() != View.GONE) {
                return false;
            }
        }
        return true;
    }

    private static void restoreChildren(View view) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }

    private static boolean applyTabWidths() {
        View barV = sTabBarRef.get();
        int selected = barV instanceof com.example.liquidglass.LiquidGlassTabBar
                ? ((com.example.liquidglass.LiquidGlassTabBar) barV).getSelectedIndex()
                : 0;
        return applyTabWidths(selected);
    }

    private static boolean applyTabWidths(int selectedIndex) {
        boolean changed = false;
        try {
            View barV = sTabBarRef.get();
            if (!(barV instanceof ViewGroup)
                    || ((ViewGroup) barV).getChildCount() == 0
                    || !(((ViewGroup) barV).getChildAt(0) instanceof ViewGroup)) {
                return false;
            }
            ViewGroup row = (ViewGroup) ((ViewGroup) barV).getChildAt(0);
            int tabs = 0;
            boolean hasGap = false;
            for (int i = 0; i < row.getChildCount(); i++) {
                if (row.getChildAt(i) instanceof android.widget.LinearLayout) {
                    tabs++;
                } else if (row.getChildAt(i) instanceof android.widget.Space) {
                    hasGap = true;
                }
            }
            if (tabs == 0) {
                return false;
            }
            boolean miuixDocked = barV instanceof MiuixGlassTabBar;
            float f = miuixDocked ? 1f
                    : Math.max(50, Math.min(GlassConfig.tabWidthPct, 150)) / 100f;
            boolean fit = !miuixDocked && fitVisibleTabsEffective(tabs);
            boolean glide = fit || sFitActive;
            sFitActive = fit;
            float[] before = glide ? captureTabCenters(row) : null;
            int selected = Math.max(0, Math.min(selectedIndex, tabs - 1));

            float den = sDensity > 0 ? sDensity
                    : barV.getResources().getDisplayMetrics().density;
            float[] metrics = !miuixDocked && !fit && GlassConfig.barWidthMode == 0
                    ? adaptiveMetricsDp(den) : null;
            float gap = fit ? CENTER_GAP_WEIGHT
                    : metrics != null ? Math.max(metrics[1], 0.3f)
                    : Math.max(0.3f, (tabs + CENTER_GAP_WEIGHT) / f - tabs);
            int tabIndex = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (!(c.getLayoutParams()
                        instanceof android.widget.LinearLayout.LayoutParams)) {
                    continue;
                }
                android.widget.LinearLayout.LayoutParams lp =
                        (android.widget.LinearLayout.LayoutParams) c.getLayoutParams();
                float w;
                if (c instanceof android.widget.Space) {
                    w = gap;
                } else if (c instanceof android.widget.LinearLayout) {
                    w = fit
                            ? (tabIndex == selected
                            ? SELECTED_TAB_WEIGHT : OTHER_TAB_WEIGHT)
                            : metrics != null ? metrics[0] : f;
                    tabIndex++;
                } else {
                    continue;
                }
                if (Math.abs(lp.weight - w) > 0.001f) {
                    lp.weight = w;
                    c.setLayoutParams(lp);
                    changed = true;
                }
            }
            float totalWeight = fit
                    ? OTHER_TAB_WEIGHT * tabs
                    + (SELECTED_TAB_WEIGHT - OTHER_TAB_WEIGHT)
                    + (hasGap ? CENTER_GAP_WEIGHT : 0f)
                    : 0f;
            changed |= applyFitBarWidth(barV, totalWeight, fit, f);
            if (changed && before != null) {
                scheduleTabGlide(row, before);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply tab widths failed", t);
        }
        return changed;
    }

    private static void resetWidthAnimState() {
        if (sTabShiftAnimator != null) {
            sTabShiftAnimator.cancel();
            sTabShiftAnimator = null;
        }
        if (sBarWidthAnimator != null) {
            sBarWidthAnimator.cancel();
            sBarWidthAnimator = null;
        }
        if (sDropletSizeAnimator != null) {
            sDropletSizeAnimator.cancel();
            sDropletSizeAnimator = null;
        }
        sBarTargetWidth = Integer.MIN_VALUE;
        sBarTargetLeft = Integer.MIN_VALUE;
        sBarTargetGravity = Integer.MIN_VALUE;
        sFitActive = false;
    }

    private static void scheduleDropletGrow(
            final com.example.liquidglass.LiquidGlassTabBar tabBar,
            final int fromWidth) {
        try {
            if (fromWidth <= 0) {
                return;
            }
            final View droplet = findDroplet(tabBar);
            if (droplet == null) {
                return;
            }
            ViewTreeObserver vto = tabBar.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) {
                return;
            }
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver live = tabBar.getViewTreeObserver();
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this);
                    }
                    return !growDroplet(droplet, fromWidth);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static boolean growDroplet(View droplet, int fromWidth) {
        try {
            if (!(droplet.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return false;
            }
            final FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) droplet.getLayoutParams();
            final int toWidth = lp.width;
            if (toWidth <= 0 || Math.abs(toWidth - fromWidth) < 2) {
                return false;
            }
            if (sDropletSizeAnimator != null) {
                sDropletSizeAnimator.cancel();
            }
            final int startWidth = fromWidth;
            lp.width = startWidth;
            droplet.setLayoutParams(lp);
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Math.max(0L, FIT_ANIM_MS - 40L));
            anim.setInterpolator(
                    new android.view.animation.DecelerateInterpolator(1.6f));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                lp.width = Math.round(startWidth + (toWidth - startWidth) * t);
                droplet.setLayoutParams(lp);
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    lp.width = toWidth;
                    droplet.setLayoutParams(lp);
                }
            });
            sDropletSizeAnimator = anim;
            anim.start();
            return true;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("droplet grow failed", t);
            return false;
        }
    }

    private static float[] captureTabCenters(ViewGroup row) {
        if (row.getWidth() <= 0) {
            return null;
        }
        float[] centers = new float[row.getChildCount()];
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c.getWidth() <= 0) {
                return null;
            }
            centers[i] = c.getLeft() + c.getWidth() / 2f + c.getTranslationX();
        }
        return centers;
    }

    private static void scheduleTabGlide(final ViewGroup row,
            final float[] before) {
        try {
            ViewTreeObserver vto = row.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) {
                return;
            }
            vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    ViewTreeObserver live = row.getViewTreeObserver();
                    if (live != null && live.isAlive()) {
                        live.removeOnPreDrawListener(this);
                    }
                    glideTabsFrom(row, before);
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void glideTabsFrom(ViewGroup row, float[] before) {
        try {
            if (before == null || row.getChildCount() != before.length) {
                return;
            }
            if (sSnapWidthChanges) {
                return;
            }
            if (sBarWidthAnimator != null && sBarWidthAnimator.isRunning()) {
                return;
            }
            final View[] kids = new View[before.length];
            final float[] shift = new float[before.length];
            boolean any = false;
            for (int i = 0; i < before.length; i++) {
                View c = row.getChildAt(i);
                if (c.getWidth() <= 0) {
                    return;
                }
                kids[i] = c;
                shift[i] = before[i] - (c.getLeft() + c.getWidth() / 2f);
                any |= Math.abs(shift[i]) > 0.5f;
            }
            if (!any) {
                return;
            }
            if (sTabShiftAnimator != null) {
                sTabShiftAnimator.cancel();
            }
            for (int i = 0; i < kids.length; i++) {
                kids[i].setTranslationX(shift[i]);
            }
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(1f, 0f);
            anim.setDuration(FIT_ANIM_MS);
            anim.setInterpolator(
                    new android.view.animation.OvershootInterpolator(
                            FIT_ANIM_TENSION));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                for (int i = 0; i < kids.length; i++) {
                    kids[i].setTranslationX(shift[i] * t);
                }
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    for (View kid : kids) {
                        kid.setTranslationX(0f);
                    }
                }
            });
            sTabShiftAnimator = anim;
            anim.start();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tab glide failed", t);
        }
    }

    private static boolean applyFitBarWidth(View barV, float totalWeight,
            boolean fit, float widthScale) {
        if (!(barV.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return false;
        }
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) barV.getLayoutParams();
        ViewGroup host = sHostRef;
        float den = sDensity > 0 ? sDensity
                : barV.getResources().getDisplayMetrics().density;
        int hostWidth = host != null ? host.getWidth() : 0;
        if (hostWidth <= 0) {
            hostWidth = barV.getResources().getDisplayMetrics().widthPixels
                    - Math.round(20f * den);
        }
        int available = Math.max(0, hostWidth - lp.rightMargin);
        if (barV instanceof MiuixGlassTabBar) {
            lp.rightMargin = 0;
            return setBarWidth(barV, lp, ViewGroup.LayoutParams.MATCH_PARENT, 0,
                    android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL,
                    hostWidth);
        }
        if (!fit) {
            return setBarWidth(barV, lp, ViewGroup.LayoutParams.MATCH_PARENT, 0,
                    android.view.Gravity.TOP
                            | android.view.Gravity.FILL_HORIZONTAL, available);
        }
        if (available <= 0 || totalWeight <= 0f) {
            return false;
        }
        int innerAvailable = Math.max(1, available - Math.round(8f * den));
        int perWeight = Math.min(
                Math.round(FIT_TAB_MAX_WIDTH_DP * widthScale * den),
                Math.round(innerAvailable / totalWeight));
        int width = Math.min(available,
                Math.round(totalWeight * perWeight) + Math.round(8f * den));
        int left = Math.max(0, (available - width) / 2);
        return setBarWidth(barV, lp, width, left,
                android.view.Gravity.TOP | android.view.Gravity.START,
                available);
    }

    private static boolean setBarWidth(View barV, FrameLayout.LayoutParams lp,
            int width, int left, int gravity, int available) {
        boolean animating = sBarWidthAnimator != null
                && sBarWidthAnimator.isRunning();
        if (animating && width == sBarTargetWidth && left == sBarTargetLeft
                && gravity == sBarTargetGravity) {
            return false;
        }
        if (!animating && lp.width == width && lp.gravity == gravity
                && lp.leftMargin == left) {
            sBarTargetWidth = width;
            sBarTargetLeft = left;
            sBarTargetGravity = gravity;
            return false;
        }
        sBarTargetWidth = width;
        sBarTargetLeft = left;
        sBarTargetGravity = gravity;
        final int startWidth = barV.getWidth();
        final int startLeft = lp.leftMargin;
        int endPx = width == ViewGroup.LayoutParams.MATCH_PARENT
                ? available : width;
        if (animating) {
            sBarWidthAnimator.cancel();
        }
        if (sSnapWidthChanges || startWidth <= 0 || endPx <= 0
                || startWidth == endPx) {
            lp.width = width;
            lp.leftMargin = left;
            lp.gravity = gravity;
            barV.setLayoutParams(lp);
            return true;
        }
        final FrameLayout.LayoutParams flp = lp;
        final View target = barV;
        final int endWidth = endPx;
        final int endLeft = left;
        final int endGravity = gravity;
        final int finalWidth = width;
        flp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(FIT_ANIM_MS);
        anim.setInterpolator(
                new android.view.animation.DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            flp.width = Math.round(startWidth + (endWidth - startWidth) * t);
            flp.leftMargin = Math.round(startLeft + (endLeft - startLeft) * t);
            target.setLayoutParams(flp);
        });
        final boolean[] cancelled = {false};
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled[0]) {
                    return;
                }
                flp.width = finalWidth;
                flp.leftMargin = endLeft;
                flp.gravity = endGravity;
                target.setLayoutParams(flp);
            }
        });
        sBarWidthAnimator = anim;
        anim.start();
        return true;
    }

    private static void prepareSelectionLayout(
            com.example.liquidglass.LiquidGlassTabBar tabBar,
            int selectedIndex) {
        try {
            View droplet = findDroplet(tabBar);
            int dropletWidth = droplet == null ? 0 : droplet.getWidth();
            if (!applyTabWidths(selectedIndex)) {
                return;
            }
            scheduleDropletGrow(tabBar, dropletWidth);
            int width = tabBar.getMeasuredWidth();
            int height = tabBar.getMeasuredHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            int widthSpec = View.MeasureSpec.makeMeasureSpec(
                    width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(
                    height, View.MeasureSpec.EXACTLY);
            tabBar.measure(widthSpec, heightSpec);
            tabBar.layout(tabBar.getLeft(), tabBar.getTop(),
                    tabBar.getRight(), tabBar.getBottom());
            glideCenterTo(tabBar);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass selection layout failed", t);
        }
    }

    private static void glideCenterTo(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            View center = sCenterRefStatic;
            if (center == null
                    || !(center.getLayoutParams()
                            instanceof FrameLayout.LayoutParams)) {
                return;
            }
            if (sPlusHidden || sCircleMode
                    || center.getVisibility() != View.VISIBLE) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int n = ll.getChildCount();
            if (n < 3) {
                return;
            }
            View spacer = ll.getChildAt(n / 2);
            if (spacer.getWidth() <= 0) {
                return;
            }
            int left = tabBar.getLeft() + row.getLeft() + spacer.getLeft();
            final FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) center.getLayoutParams();
            if (left == lp.leftMargin) {
                return;
            }
            if (sCenterAnimator != null) {
                sCenterAnimator.cancel();
            }
            sCenterTargetLeft = left;
            final int from = lp.leftMargin;
            final int to = left;
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(FIT_ANIM_MS);
            anim.setInterpolator(
                    new android.view.animation.OvershootInterpolator(
                            FIT_ANIM_TENSION));
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                lp.leftMargin = Math.round(from + (to - from) * t);
                center.setLayoutParams(lp);
            });
            final boolean[] cancelled = {false};
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(android.animation.Animator a) {
                    cancelled[0] = true;
                }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled[0]) {
                        return;
                    }
                    lp.leftMargin = to;
                    center.setLayoutParams(lp);
                }
            });
            sCenterAnimator = anim;
            anim.start();
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center glide failed", t);
        }
    }

    private static int nearestTabIndex(
            com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return -1;
            }
            View droplet = findDroplet(tabBar);
            if (droplet == null) {
                return -1;
            }
            float centerX = droplet.getX() + droplet.getWidth() / 2f;
            int best = tabBar.getSelectedIndex();
            float bestDist = Float.MAX_VALUE;
            int index = 0;
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            for (int i = 0; i < ll.getChildCount(); i++) {
                View child = ll.getChildAt(i);
                if (!(child instanceof android.widget.LinearLayout)) {
                    continue;
                }
                float tabCenter = child.getLeft() + child.getWidth() / 2f;
                float d = Math.abs(tabCenter - centerX);
                if (d < bestDist) {
                    bestDist = d;
                    best = index;
                }
                index++;
            }
            return best;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void reanimateDroplet(
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        try {
            if (tabBar instanceof MiuixGlassTabBar) {
                return;
            }
            if (sBarWidthAnimator != null && sBarWidthAnimator.isRunning()) {
                return;
            }
            tabBar.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            tabBar.getViewTreeObserver()
                                    .removeOnGlobalLayoutListener(this);
                            try {
                                tabBar.setSelectedIndex(tabBar.getSelectedIndex());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    public static void syncTabVisibility() {
        try {
            if (!sTabBarActive) {
                return;
            }
            View v = sTabBarRef.get();
            if (v instanceof com.example.liquidglass.LiquidGlassTabBar) {
                rebuildTabBar((com.example.liquidglass.LiquidGlassTabBar) v);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyTabBarOverLight(
            com.example.liquidglass.LiquidGlassTabBar tabBar, boolean darkBar) {
        boolean miuix = tabBar instanceof MiuixGlassTabBar;
        if (miuix) {
            int mode = tabBar.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            darkBar = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        int selectedColor = miuix
                ? com.better.heybox.ThemeUtils.resolveAccent(tabBar.getContext())
                : (darkBar ? 0xFFFFFFFF : 0xE6000000);
        int normalColor = miuix
                ? com.better.heybox.ThemeUtils.textSecondaryColor(tabBar.getContext())
                : (darkBar ? 0xB8FFFFFF : 0x8C000000);
        int applied = 0;
        try {
            java.lang.reflect.Field af = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredField("overLightAppearance");
            af.setAccessible(true);
            af.setBoolean(tabBar, !darkBar);

            java.lang.reflect.Method uts = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredMethod("updateTabStyles");
            uts.setAccessible(true);
            uts.invoke(tabBar);
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "updateTabStyles refresh failed: " + t);
        }
        try {
            java.lang.reflect.Field tf = com.example.liquidglass.LiquidGlassTabBar.class
                    .getDeclaredField("tabs");
            tf.setAccessible(true);
            Object tabsObj = tf.get(tabBar);
            if (tabsObj instanceof java.util.List) {
                int selIdx = tabBar.getSelectedIndex();
                java.util.List<?> tabs = (java.util.List<?>) tabsObj;
                for (int i = 0; i < tabs.size(); i++) {
                    Object holder = tabs.get(i);
                    if (holder == null) {
                        continue;
                    }
                    int color = i == selIdx ? selectedColor : normalColor;
                    for (java.lang.reflect.Field hf : holder.getClass().getDeclaredFields()) {
                        hf.setAccessible(true);
                        Object val = hf.get(holder);
                        if (val instanceof android.widget.TextView) {
                            ((android.widget.TextView) val)
                                    .setTextColor(color);
                            applied++;
                        } else if (val instanceof android.widget.ImageView) {
                            ((android.widget.ImageView) val)
                                    .setImageTintList(
                                            android.content.res.ColorStateList
                                                    .valueOf(color));
                            applied++;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("direct chrome recolor failed", t);
        }
        tabBar.invalidate();
        LiquidGlassLog.log(android.util.Log.INFO,
                "chrome theme applied: dark=" + darkBar + " targets=" + applied);
    }

    static void applyMiuixChrome(MiuixGlassTabBar tabBar) {
        if (tabBar == null) {
            return;
        }
        int mode = tabBar.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        applyTabBarOverLight(tabBar,
                mode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
    }

    private static boolean isSystemNight(Activity activity) {
        int mode = activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void insertCenterGap(Context context, ViewGroup tabBar) {
        try {
            if (tabBar.getChildCount() == 0) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int count = ll.getChildCount();
            if (count < 2) {
                return;
            }
            android.widget.Space spacer = new android.widget.Space(context);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.MATCH_PARENT, CENTER_GAP_WEIGHT);

            ll.addView(spacer, (count + 1) / 2, lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center gap failed", t);
        }
    }

    private static void placeCenterNow(ViewGroup host, ViewGroup tabBar,
                                       View center, int attempt) {
        if (center == null || sPlusHidden || sCircleMode || attempt > 10) {
            return;
        }
        try {
            View row = tabBar.getChildAt(0);
            if (!(row instanceof android.widget.LinearLayout)) {
                return;
            }
            android.widget.LinearLayout ll = (android.widget.LinearLayout) row;
            int n = ll.getChildCount();
            if (n < 3) {
                return;
            }
            View spacer = ll.getChildAt(n / 2);
            if (spacer.getWidth() == 0) {
                final ViewGroup h2 = host, t2 = tabBar;
                final View c2 = center;
                final int a2 = attempt + 1;
                center.post(() -> placeCenterNow(h2, t2, c2, a2));
                return;
            }
            int left = tabBar.getLeft() + row.getLeft() + spacer.getLeft();
            if (sCenterAnimator != null && sCenterAnimator.isRunning()
                    && left == sCenterTargetLeft) {
                return;
            }
            if (center.getWidth() == spacer.getWidth()
                    && center.getHeight() == tabBar.getHeight()
                    && center.getLeft() == left
                    && center.getTop() == tabBar.getTop()
                    && center.getVisibility() == View.VISIBLE) {
                return;
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    spacer.getWidth(), tabBar.getHeight(),
                    android.view.Gravity.TOP | android.view.Gravity.START);
            lp.leftMargin = left;
            lp.topMargin = tabBar.getTop();
            center.setTranslationX(0);
            center.setLayoutParams(lp);
        } catch (Throwable ignored) {
        }
    }

    private static void centerTabsRow(ViewGroup tabBar) {
        try {
            if (tabBar.getChildCount() == 0) {
                return;
            }
            View row = tabBar.getChildAt(0);
            if (row == null || !(row.getLayoutParams()
                    instanceof FrameLayout.LayoutParams)) {
                return;
            }
            FrameLayout.LayoutParams rlp =
                    (FrameLayout.LayoutParams) row.getLayoutParams();
            int want = android.view.Gravity.CENTER_VERTICAL
                    | android.view.Gravity.FILL_HORIZONTAL;
            if (rlp.gravity == want) {
                return;
            }
            rlp.gravity = want;
            if (rlp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                rlp.leftMargin = 0;
                rlp.rightMargin = 0;
            }
            row.setLayoutParams(rlp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("center tabs row failed", t);
        }
    }

    private static void applyBarSideMargins(ViewGroup host) {
        try {
            if (!(host.getLayoutParams() instanceof RelativeLayout.LayoutParams)) {
                return;
            }
            RelativeLayout.LayoutParams lp =
                    (RelativeLayout.LayoutParams) host.getLayoutParams();
            View parent = host.getParent() instanceof View
                    ? (View) host.getParent() : null;
            int parentWidth = parent != null ? parent.getWidth() : 0;
            if (parentWidth <= 0) {
                parentWidth = host.getResources()
                        .getDisplayMetrics().widthPixels;
            }
            if (parentWidth <= 0) {
                return;
            }

            if (isMiuixEnabled(host.getContext())) {
                boolean changed = lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.leftMargin != 0 || lp.rightMargin != 0;
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                if (changed) {
                    host.setLayoutParams(lp);
                }
                return;
            }

            float den = sDensity > 0 ? sDensity
                    : host.getResources().getDisplayMetrics().density;
            int side = sideMarginPx(den);
            int usable = Math.max(0, parentWidth - side * 2);
            int target;
            switch (GlassConfig.barWidthMode) {
                case 0:
                    target = adaptiveBarWidth(host, parentWidth);
                    break;
                case 2:
                    int pct = Math.max(40,
                            Math.min(GlassConfig.barWidthPct, 100));
                    target = Math.round(usable * pct / 100f);
                    break;
                default:
                    target = usable;
                    break;
            }
            target = Math.max(0, Math.min(target, usable));
            int margin = side + Math.max(0, (usable - target) / 2);
            if (lp.leftMargin == margin && lp.rightMargin == margin) {
                return;
            }
            lp.leftMargin = margin;
            lp.rightMargin = margin;
            host.setLayoutParams(lp);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("apply bar side margins failed", t);
        }
    }

    private static int adaptiveBarWidth(View host, int parentWidth) {
        float den = sDensity > 0 ? sDensity
                : host.getResources().getDisplayMetrics().density;
        android.widget.RadioGroup radio = sRadioBarRef.get();
        int tabs = 0;
        if (radio != null) {
            for (int i = 0; i < radio.getChildCount(); i++) {
                View c = radio.getChildAt(i);
                if (c instanceof android.widget.RadioButton
                        && c.getVisibility() == View.VISIBLE) {
                    tabs++;
                }
            }
        }
        int max = Math.max(0, parentWidth - sideMarginPx(den) * 2);
        if (tabs == 0) {
            return max;
        }
        float[] metrics = adaptiveMetricsDp(den);

        float w = (tabs * metrics[0] + metrics[1]) * den + Math.round(8f * den);
        return Math.max(Math.round(MIN_TAB_WIDTH_DP * den),
                Math.min(Math.round(w), max));
    }

    private static int sideMarginPx(float den) {
        int dp = Math.max(0, Math.min(
                GlassConfig.barSideMarginDp, MAX_SIDE_MARGIN_DP));
        return Math.round(dp * den);
    }

    private static float[] adaptiveMetricsDp(float den) {
        float scale = Math.max(50, Math.min(GlassConfig.tabWidthPct, 150)) / 100f;
        float content = measureTabContentDp(den);
        float tab = (content > 0f ? content + TAB_CONTENT_PAD_DP
                : FALLBACK_TAB_WIDTH_DP) * scale;
        tab = Math.max(MIN_TAB_WIDTH_DP * scale,
                Math.min(tab, FIT_TAB_MAX_WIDTH_DP * scale));
        if (content > 0f) {

            tab = Math.max(tab, content);
        }
        float gap = sCircleMode || sPlusHidden ? 0f : plusGapDp(den, tab);
        return new float[]{tab, gap};
    }

    private static float plusGapDp(float den, float tabDp) {
        float gap = MIN_PLUS_GAP_DP;
        try {
            View mid = sMidTabRef == null ? null : sMidTabRef.get();
            int w = mid == null ? 0 : mid.getMeasuredWidth();
            if (w > 0) {
                gap = Math.max(gap, w / den + 8f);
            }
        } catch (Throwable ignored) {
        }
        return Math.max(gap, tabDp);
    }

    private static float measureTabContentDp(float den) {
        try {
            View barV = sTabBarRef.get();
            if (!(barV instanceof ViewGroup) || ((ViewGroup) barV).getChildCount() == 0
                    || !(((ViewGroup) barV).getChildAt(0) instanceof ViewGroup)) {
                return 0f;
            }
            ViewGroup row = (ViewGroup) ((ViewGroup) barV).getChildAt(0);
            int best = 0;
            for (int i = 0; i < row.getChildCount(); i++) {
                View tab = row.getChildAt(i);
                if (!(tab instanceof ViewGroup)
                        || tab instanceof android.widget.Space) {
                    continue;
                }
                int w = tabContentPx((ViewGroup) tab, den);
                if (w > best) {
                    best = w;
                }
            }
            return best > 0 ? best / den : 0f;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("tab content measure failed", t);
            return 0f;
        }
    }

    private static int tabContentPx(ViewGroup tab, float den) {
        int best = 0;
        for (int i = 0; i < tab.getChildCount(); i++) {
            View c = tab.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                continue;
            }
            int w = 0;
            if (c instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) c).getText();
                if (text != null && text.length() > 0) {
                    w = Math.round(((android.widget.TextView) c).getPaint()
                            .measureText(text.toString()));
                }
            } else if (c instanceof android.widget.ImageView) {
                android.graphics.drawable.Drawable icon =
                        ((android.widget.ImageView) c).getDrawable();
                if (icon != null) {
                    w = icon.getIntrinsicWidth();
                }
                if (w <= 0) {
                    w = c.getMeasuredWidth();
                }
            } else {
                w = c.getMeasuredWidth();
            }
            if (w > best) {
                best = w;
            }
        }
        if (best <= 0) {
            return 0;
        }

        return best + tab.getPaddingLeft() + tab.getPaddingRight();
    }

    static void applyBarGeometry() {
        sSnapWidthChanges = true;
        try {
            View barV = sTabBarRef.get();
            ViewGroup host = sHostRef;
            View center = sCenterRefStatic;
            if (!(barV instanceof ViewGroup) || host == null
                    || !(barV.getLayoutParams()
                            instanceof FrameLayout.LayoutParams)) {
                return;
            }
            float den = sDensity > 0 ? sDensity : 3f;
            boolean miuixDocked = barV instanceof MiuixGlassTabBar;
            int hDp = GlassConfig.barHeightDp;
            FrameLayout.LayoutParams blp =
                    (FrameLayout.LayoutParams) barV.getLayoutParams();

            blp.height = miuixDocked || hDp <= 0
                    ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : Math.round(hDp * den);
            if (miuixDocked) {
                blp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                blp.leftMargin = 0;
                blp.rightMargin = 0;
                blp.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.FILL_HORIZONTAL;
            }
            barV.setLayoutParams(blp);

            centerTabsRow((ViewGroup) barV);

            int off = miuixDocked ? 0 : Math.max(0, GlassConfig.barOffsetDp);
            host.setPadding(host.getPaddingLeft(), host.getPaddingTop(),
                    host.getPaddingRight(), miuixDocked ? 0
                            : sBasePadBottom + Math.round(off * den));

            applyBarSideMargins(host);
            boolean tabLayoutChanged = applyTabWidths();

            host.requestLayout();
            if (tabLayoutChanged
                    && barV instanceof com.example.liquidglass.LiquidGlassTabBar) {
                reanimateDroplet(
                        (com.example.liquidglass.LiquidGlassTabBar) barV);
            }
            final ViewGroup h2 = host;
            final ViewGroup b2 = (ViewGroup) barV;
            host.post(() -> {
                sSnapWidthChanges = true;
                try {
                    applyTabWidths();
                } finally {
                    sSnapWidthChanges = false;
                }
                placeCenterNow(h2, b2, center, 0);
            });
            android.widget.RadioGroup radio = sRadioBarRef.get();
            if (radio != null
                    && barV instanceof com.example.liquidglass.LiquidGlassTabBar) {
                applyBarMode(radio,
                        (com.example.liquidglass.LiquidGlassTabBar) barV);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr("applyBarGeometry failed", t);
        } finally {
            sSnapWidthChanges = false;
        }
    }

    private static void installRepeatClickRefresh(
            final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        if (tabBar == null) {
            return;
        }
        final float[] down = new float[2];
        final int[] selectedBefore = new int[1];
        final boolean[] moved = new boolean[1];
        tabBar.setOnTouchListener((view, event) -> {
            try {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    down[0] = event.getX();
                    down[1] = event.getY();
                    selectedBefore[0] = tabBar.getSelectedIndex();
                    moved[0] = false;
                } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                    float slop = android.view.ViewConfiguration.get(view.getContext())
                            .getScaledTouchSlop();
                    float dx = event.getX() - down[0];
                    float dy = event.getY() - down[1];
                    moved[0] = moved[0] || dx * dx + dy * dy > slop * slop;
                } else if (action == android.view.MotionEvent.ACTION_UP) {
                    if (moved[0]) {
                        int near = nearestTabIndex(tabBar);
                        if (near >= 0) {
                            prepareSelectionLayout(tabBar, near);
                        }
                    } else {
                        int target = findTabIndexAt(tabBar, event.getX());
                        if (target >= 0) {
                            prepareSelectionLayout(tabBar, target);
                        }
                        final int before = selectedBefore[0];
                        tabBar.post(() -> {
                            try {
                                if (target != before || target != tabBar.getSelectedIndex()
                                        || target < 0 || target >= sVisibleButtons.size()) {
                                    return;
                                }
                                android.widget.RadioButton button = sVisibleButtons.get(target);
                                if (button != null) {
                                    button.performClick();
                                }
                            } catch (Throwable t) {
                                LiquidGlassLog.logErr(
                                        "repeat tab refresh failed", t);
                            }
                        });
                    }
                } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
                    moved[0] = false;
                }
            } catch (Throwable t) {
                LiquidGlassLog.logErr("repeat tab touch failed", t);
            }
            return false;
        });
    }

    private static int findTabIndexAt(
            com.example.liquidglass.LiquidGlassTabBar tabBar, float x) {
        if (tabBar == null || tabBar.getChildCount() <= 0
                || !(tabBar.getChildAt(0) instanceof android.view.ViewGroup)) {
            return -1;
        }
        android.view.ViewGroup row = (android.view.ViewGroup) tabBar.getChildAt(0);
        float localX = x - row.getLeft();
        int index = 0;
        for (int childIndex = 0; childIndex < row.getChildCount(); childIndex++) {
            View child = row.getChildAt(childIndex);
            if (!(child instanceof android.widget.LinearLayout)) {
                continue;
            }
            if (localX >= child.getLeft() && localX <= child.getRight()) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private interface OnCheckedExtra {
        void onChecked(android.widget.RadioGroup group, int checkedId);
    }

    private static android.widget.RadioGroup.OnCheckedChangeListener sOriginalCheckedListener;
    private static android.widget.RadioGroup.OnCheckedChangeListener sWrappedListener;
    private static android.view.ViewTreeObserver.OnPreDrawListener sMidPreDraw;
    private static java.lang.ref.WeakReference<View> sMidPreDrawView;

    private static void wrapCheckedListener(final android.widget.RadioGroup bar,
                                            final OnCheckedExtra extra) {
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            final Object original = f.get(bar);
            sOriginalCheckedListener =
                    original instanceof android.widget.RadioGroup.OnCheckedChangeListener
                            ? (android.widget.RadioGroup.OnCheckedChangeListener) original
                            : null;
            android.widget.RadioGroup.OnCheckedChangeListener wrapper =
                    new android.widget.RadioGroup.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(android.widget.RadioGroup group, int checkedId) {
                            if (sOriginalCheckedListener != null) {
                                sOriginalCheckedListener.onCheckedChanged(group, checkedId);
                            }
                            try {
                                extra.onChecked(group, checkedId);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
            sWrappedListener = wrapper;
            bar.setOnCheckedChangeListener(wrapper);
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.WARN,
                    "checked listener wrap unavailable: " + t);
        }
    }

    private static void unwrapCheckedListener(ViewGroup barView) {
        if (!(barView instanceof android.widget.RadioGroup)
                || sWrappedListener == null) {
            return;
        }
        android.widget.RadioGroup bar = (android.widget.RadioGroup) barView;
        try {
            java.lang.reflect.Field f = android.widget.RadioGroup.class
                    .getDeclaredField("mOnCheckedChangeListener");
            f.setAccessible(true);
            if (f.get(bar) == sWrappedListener) {
                bar.setOnCheckedChangeListener(sOriginalCheckedListener);
            }
        } catch (Throwable ignored) {
        }
        sWrappedListener = null;
        sOriginalCheckedListener = null;
    }

    private static void removeMidPreDraw(View midTab) {
        if (sMidPreDraw == null) {
            return;
        }
        View attachedTo = sMidPreDrawView != null ? sMidPreDrawView.get() : null;
        if (attachedTo == null) {
            attachedTo = midTab;
        }
        if (attachedTo != null) {
            try {
                attachedTo.getViewTreeObserver().removeOnPreDrawListener(sMidPreDraw);
            } catch (Throwable ignored) {
            }
        }
        sMidPreDraw = null;
        sMidPreDrawView = null;
    }

    private static void setupTabSelectionSync(final android.widget.RadioGroup bar,
                                              final com.example.liquidglass.LiquidGlassTabBar tabBar) {
        wrapCheckedListener(bar, (group, checkedId) -> {
            for (int i = 0; i < sVisibleButtons.size(); i++) {
                android.widget.RadioButton rb = sVisibleButtons.get(i);
                if (rb != null && rb.getId() == checkedId
                        && tabBar.getSelectedIndex() != i) {
                    prepareSelectionLayout(tabBar, i);
                    tabBar.setSelectedIndex(i);
                    applyTabBarOverLight(tabBar, !sChromeLight);
                    if (tabBar instanceof MiuixGlassTabBar) {
                        ((MiuixGlassTabBar) tabBar).onMiuixSelectionChanged();
                    }
                    break;
                }
            }
        });
    }

    private static volatile boolean sTintHookInstalled;
    private static volatile boolean sChromeLight;
    private static volatile boolean sChromeForced;
    private static volatile ViewGroup sHostRef;
    private static volatile View sCenterRefStatic;
    private static final java.lang.ref.WeakReference<View> EMPTY_MID_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sMidTabRef =
            EMPTY_MID_REF;
    private static volatile boolean sPlusHidden;
    private static final java.lang.ref.WeakReference<android.widget.RadioGroup>
            EMPTY_RADIO_REF = new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<android.widget.RadioGroup>
            sRadioBarRef = EMPTY_RADIO_REF;
    private static volatile java.lang.ref.WeakReference<View> sGlassCircleRef;
    private static volatile java.lang.ref.WeakReference<View> sContentViewRef;
    private static volatile boolean sCircleMode;
    private static volatile int sLastTabs = -1;
    private static volatile int sStableTabs = -1;
    private static volatile float sDensity;
    private static int sBasePadBottom;
    private static final java.lang.ref.WeakReference<View> EMPTY_BAR_REF =
            new java.lang.ref.WeakReference<>(null);
    private static volatile java.lang.ref.WeakReference<View> sTabBarRef = EMPTY_BAR_REF;
    private static final java.util.List<android.widget.RadioButton> sVisibleButtons =
            new java.util.ArrayList<>();

    private static volatile java.lang.ref.WeakReference<View> sSearchIconRef =
            new java.lang.ref.WeakReference<>(null);

    private static volatile java.lang.ref.WeakReference<View> sSearchTitleBarRef =
            new java.lang.ref.WeakReference<>(null);

    private static final java.util.Map<View, Boolean> sRepurposedTitleBars =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());

    private static final java.util.Map<View, Boolean> sHiddenSearchIcons =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());

    private static volatile int sSearchTabIndex = -1;
    private static volatile boolean sSyncing;
    private static volatile String sBuildSig = "";

    private static final java.util.List<Integer> sAppliedOrder =
            new java.util.ArrayList<>();
    private static android.animation.ValueAnimator sTabShiftAnimator;
    private static android.animation.ValueAnimator sBarWidthAnimator;
    private static android.animation.ValueAnimator sDropletSizeAnimator;
    private static int sBarTargetWidth = Integer.MIN_VALUE;
    private static int sBarTargetLeft = Integer.MIN_VALUE;
    private static int sBarTargetGravity = Integer.MIN_VALUE;
    private static boolean sFitActive;
    private static boolean sSnapWidthChanges;
    private static android.animation.ValueAnimator sCenterAnimator;
    private static int sCenterTargetLeft = Integer.MIN_VALUE;

    static View activeGlassHost() {
        ViewGroup host = sHostRef;
        if (host == null || !host.isAttachedToWindow() || host.getHeight() <= 0) {
            return null;
        }
        return host;
    }

    private static void installTintOverride() {
        if (sTintHookInstalled) {
            return;
        }
        sTintHookInstalled = true;
        try {
            java.lang.reflect.Method m = com.example.liquidglass.LiquidGlassView.class
                    .getDeclaredMethod("currentTintColor");
            LiquidGlassHookBridge.hookExecutable(m, chain -> {
                int tint = 0x30FFFFFF;
                try {
                    Object thiz = chain.getThisObject();
                    int mode = 0;
                    boolean isBarView = false;
                    if (thiz instanceof View) {
                        mode = ((View) thiz).getResources()
                                .getConfiguration().uiMode
                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                        isBarView = thiz == sTabBarRef.get();
                    }
                    boolean light =
                            mode != android.content.res.Configuration.UI_MODE_NIGHT_YES;
                    if (light) {
                        tint = GlassConfig.lightTint();
                    } else {
                        tint = GlassConfig.darkTint();
                    }

                    int alpha = tint >>> 24;
                    tint = ((alpha * 3 / 5) << 24) | (tint & 0x00FFFFFF);
                    if (!isBarView) {
                        int a = tint >>> 24;
                        int na = Math.round(a * 0.6f);
                        tint = (na << 24) | (tint & 0x00FFFFFF);
                    }
                } catch (Throwable ignored) {
                }
                return tint;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "currentTintColor override hooked (theme body)");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("currentTintColor hook failed", t);
        }
    }

    private static final java.util.Map<View, Boolean> sGlassEntries =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<View, Boolean>());

    private static android.content.SharedPreferences.OnSharedPreferenceChangeListener sGlassPrefListener;

    private static void installGlassPrefListener() {
        try {
            if (sGlassPrefListener != null) {
                return;
            }
            android.content.SharedPreferences prefs = com.better.heybox.HeyboxPrefs.get();
            if (prefs == null) {
                return;
            }
            sGlassPrefListener = (sp, key) -> {
                try {
                    if (!isGlassPrefKey(key)) {
                        return;
                    }
                    Activity main = mainActivity();
                    if (main == null || main.getWindow() == null) {
                        return;
                    }
                    main.getWindow().getDecorView().post(() -> refreshGlassWith(main));
                } catch (Throwable ignored) {
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(sGlassPrefListener);
            LiquidGlassLog.log(android.util.Log.INFO, "glass pref listener installed");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass pref listener failed", t);
        }
    }

    private static boolean isGlassPrefKey(String key) {
        if (key == null) {
            return false;
        }
        return com.better.heybox.App.KEY_LIQUID_GLASS.equals(key)
                || com.better.heybox.App.KEY_MIUIX_BOTTOM_BAR.equals(key)
                || key.startsWith("glass_");
    }

    public static void installSettingsEntries(ClassLoader cl) {
        installGlassPrefListener();
        try {
            Class<?> htb = Class.forName(
                    "com.max.hbcommon.component.HomeTitleBar", false, cl);
            java.lang.reflect.Method g = htb.getMethod("getIv_home_search");
            LiquidGlassHookBridge.hookExecutable(g, chain -> {
                Object r = chain.proceed();
                try {
                    if (r instanceof View) {
                        View icon = (View) r;
                        View titleBar = titleBarOf(icon);
                        boolean repurposed =
                                titleBar != null && isRepurposedTitleBar(titleBar);

                        if (titleBar != null && !repurposed
                                && isValidSearchIcon(icon, titleBar)) {
                            sSearchTitleBarRef = new java.lang.ref.WeakReference<>(titleBar);
                            sSearchIconRef = new java.lang.ref.WeakReference<>(icon);
                            LiquidGlassLog.log(android.util.Log.INFO,
                                    "search-tab: title-bar hook accepted icon #"
                                            + icon.getId() + " in "
                                            + titleBar.getClass().getName());
                        } else {
                            LiquidGlassLog.log(android.util.Log.DEBUG,
                                    "search-tab: title-bar hook ignored (titleBar="
                                            + (titleBar != null) + " repurposed="
                                            + repurposed + " icon=" + icon.getClass()
                                            .getName() + ")");
                        }
                        String chainLog = "";
                        int depth = 0;
                        for (View v = icon; v != null && depth < 10; depth++) {
                            chainLog += v.getClass().getName() + "#" + v.getId()
                                    + " < ";
                            v = v.getParent() instanceof View ? (View) v.getParent() : null;
                        }
                        LiquidGlassLog.log(android.util.Log.DEBUG,
                                "title-bar chain: " + chainLog);

                        if (!repurposed && !sGlassEntries.containsKey(icon)) {
                            android.content.Context cx = icon.getContext();
                            if (cx instanceof Activity && !prefersExternalLiquidGlass(cx)) {
                                sGlassEntries.put(icon, Boolean.TRUE);
                                final Activity a = (Activity) cx;
                                icon.setOnLongClickListener(v -> {
                                    GlassSettingsSheet.show(a);
                                    return true;
                                });
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "title-bar glass entry hook installed");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("title-bar entry hook failed", t);
        }
        try {
            Class<?> sa = Class.forName(
                    "com.max.xiaoheihe.module.account.SettingActivity", false, cl);
            java.lang.reflect.Method onResume = null;
            for (Class<?> c = sa; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    onResume = c.getDeclaredMethod("onResume");
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (onResume == null) {
                LiquidGlassLog.log(android.util.Log.WARN,
                        "SettingActivity onResume not found");
                return;
            }
            LiquidGlassHookBridge.hookExecutable(onResume, chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        final Activity a = (Activity) self;
                        a.getWindow().getDecorView().postDelayed(
                                () -> injectGlassSettingsRow(a, 0), 200L);
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "SettingActivity glass row hook installed");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("SettingActivity entry hook failed", t);
        }

        try {
            Class<?> mehome = Class.forName(
                    "com.max.xiaoheihe.module.account.MehomefragmentV2", false, cl);
            java.lang.reflect.Method installViews =
                    mehome.getDeclaredMethod("installViews", View.class);
            final java.lang.reflect.Field tbHome = mehome.getDeclaredField("tb_home");
            try {
                tbHome.setAccessible(true);
            } catch (Throwable ignored) {
            }
            LiquidGlassHookBridge.hookExecutable(installViews, chain -> {
                Object r = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object tb = tbHome.get(self);
                    if (tb instanceof View) {
                        markRepurposedTitleBar((View) tb);
                    }
                } catch (Throwable t) {
                    LiquidGlassLog.log(android.util.Log.DEBUG,
                            "search-tab: mark mehome title bar failed: " + t);
                }
                return r;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "search-tab: MehomefragmentV2 repurpose hook installed");
        } catch (Throwable t) {
            LiquidGlassLog.log(android.util.Log.DEBUG,
                    "search-tab: MehomefragmentV2 hook unavailable: " + t);
        }
    }

    private static void injectGlassSettingsRow(Activity activity, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed() || attempt > 10) {
                return;
            }
            if (prefersExternalLiquidGlass(activity)) {
                return;
            }
            int id = activity.getResources().getIdentifier(
                    "vg_general_settings", "id", activity.getPackageName());
            View row = id != 0 ? activity.findViewById(id) : null;
            if (row == null) {
                activity.getWindow().getDecorView().postDelayed(
                        () -> injectGlassSettingsRow(activity, attempt + 1), 200L);
                return;
            }
            if (sGlassEntries.containsKey(row)) {
                return;
            }
            sGlassEntries.put(row, Boolean.TRUE);
            row.setOnLongClickListener(v -> {
                GlassSettingsSheet.show(activity);
                return true;
            });
            LiquidGlassLog.log(android.util.Log.INFO,
                    "glass settings row attached (long-press 通用设置)");
        } catch (Throwable t) {
            LiquidGlassLog.logErr("glass settings row failed", t);
        }
    }

    private static void setupTabPopAnimation(ViewGroup bar) {
        if (!(bar instanceof android.widget.RadioGroup)) {
            return;
        }
        wrapCheckedListener((android.widget.RadioGroup) bar, (group, checkedId) -> {
            if (group.getParent() instanceof LiquidGlassHostLayout) {
                ((LiquidGlassHostLayout) group.getParent())
                        .popChild(group.findViewById(checkedId));
            }
        });
    }

    private static View hideLegacyShadow(ViewGroup root, int barId) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child.getClass() == View.class && child.getBackground() != null) {
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    if (((RelativeLayout.LayoutParams) lp)
                            .getRules()[RelativeLayout.ABOVE] == barId) {
                        child.setVisibility(View.GONE);
                        return child;
                    }
                }
            }
        }
        return null;
    }

    private static void copyMargins(RelativeLayout.LayoutParams src,
                                    RelativeLayout.LayoutParams dst) {
        dst.leftMargin = src.leftMargin;
        dst.topMargin = src.topMargin;
        dst.rightMargin = src.rightMargin;
        dst.bottomMargin = src.bottomMargin;
    }

    private static int computeNavInsetPadding(Activity activity,
                                              ViewGroup root, ViewGroup bar) {
        try {
            WindowInsets wi = root.getRootWindowInsets();
            int nav = 0;
            if (wi == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nav = wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                nav = wi.getSystemWindowInsetBottom();
            }
            if (nav <= 0) {
                return 0;
            }
            View decor = activity.getWindow().getDecorView();
            int[] loc = new int[2];
            decor.getLocationOnScreen(loc);
            int decorBottom = loc[1] + decor.getHeight();
            int realBottom = getRealDisplayBottom(activity);
            if (realBottom <= 0) {
                return 0;
            }
            int gap = realBottom - decorBottom;
            int extra = Math.max(nav - gap, 0);
            float density = root.getResources().getDisplayMetrics().density;
            int capped = (int) Math.min(extra, density * 56f);
            int existing = Math.max(bar.getPaddingBottom(), root.getPaddingBottom());
            return Math.max(capped - existing, 0);
        } catch (Throwable t) {
            LiquidGlassLog.logErr("computeNavInsetPadding failed", t);
            return 0;
        }
    }

    private static int getRealDisplayBottom(Activity activity) {
        try {
            WindowManager wm = (WindowManager)
                    activity.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return wm.getMaximumWindowMetrics().getBounds().bottom;
            }
            Display display = wm.getDefaultDisplay();
            if (display == null) {
                return 0;
            }
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            return dm.heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends View> T findViewByName(Activity activity, String name) {
        try {
            int id = activity.getResources()
                    .getIdentifier(name, "id", activity.getPackageName());
            if (id == 0) {
                return null;
            }
            return (T) activity.findViewById(id);
        } catch (Throwable t) {
            return null;
        }
    }
}
