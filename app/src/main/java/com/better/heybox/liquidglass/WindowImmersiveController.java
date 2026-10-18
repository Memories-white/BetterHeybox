package com.better.heybox.liquidglass;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class WindowImmersiveController {

    private static final Map<Activity, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<Activity, State>());
    private static final Map<Activity, Boolean> ACTIVITIES =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());

    private WindowImmersiveController() {
    }
    static void refresh() {
        ArrayList<Activity> activities;
        synchronized (ACTIVITIES) {
            activities = new ArrayList<>(ACTIVITIES.keySet());
        }
        for (Activity activity : activities) {
            if (activity != null && !activity.isFinishing()
                    && !activity.isDestroyed()) {
                apply(activity);
            }
        }
    }

    static void apply(Activity activity) {
        if (activity == null) {
            return;
        }
        ACTIVITIES.put(activity, Boolean.TRUE);
        try {
            Window window = activity.getWindow();
            if (window == null) {
                return;
            }
            if (!GlassConfig.immersiveGestureNavigation) {
                restore(activity, window);
                return;
            }
            State state = STATES.get(activity);
            boolean fresh = state == null;
            if (fresh) {
                state = new State(window);
                STATES.put(activity, state);
            }
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(state.systemUiVisibility
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            View content = decor == null
                    ? null : decor.findViewById(android.R.id.content);
            if (content != null) {
                content.setOnApplyWindowInsetsListener(DROP_NAV_INSET);
                if (fresh) {
                    content.requestApplyInsets();
                }
            }
            // 顶栏羽化玻璃要盖住状态栏那一段：宿主在 DrawerLayout 的上内衬里画了一层
            // 系统栏色块，玻璃压在它上面时状态栏那块会是一片纯色（实测 y=0~35 恒 255）。
            // 模块里没有 drawerlayout 依赖，这里走反射；它在宿主 APK 里，能找到就清掉。
            clearDrawerStatusBarScrim(decor);
            window.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false);
            }
            window.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        } catch (Throwable t) {
            LiquidGlassLog.logErr(
                    WindowImmersiveController.class.getSimpleName(), t);
        }
    }

    private static void restore(Activity activity, Window window) {
        State state = STATES.remove(activity);
        if (state == null) {
            return;
        }
        try {
            View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(state.systemUiVisibility);
                View content = decor.findViewById(android.R.id.content);
                if (content != null) {
                    content.setOnApplyWindowInsetsListener(null);
                    content.requestApplyInsets();
                }
            }
            if (state.drawsSystemBarBackgrounds) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }
            if (state.translucentNavigation) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            }
            window.setNavigationBarColor(state.navigationBarColor);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.setNavigationBarDividerColor(state.navigationBarDividerColor);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(state.navigationBarContrastEnforced);
            }
        } catch (Throwable t) {
            LiquidGlassLog.logErr(
                    WindowImmersiveController.class.getSimpleName(), t);
        }
    }

    /**
     * 清掉 DrawerLayout 自己画的状态栏底板。
     *
     * <p>宿主根布局是 DrawerLayout，它会在状态栏那一条上画一层 system bar 背景；
     * 我们的玻璃叠加层虽然盖在最上面，但采样时把那层纯色也一起采进去了，
     * 于是玻璃区上半截永远是纯白（实测 y=0~35 恒 255）。模块没有 drawerlayout
     * 依赖，所以走反射调用 {@code setStatusBarBackgroundColor(int)}。</p>
     */
    private static void clearDrawerStatusBarScrim(View decor) {
        if (decor == null) {
            return;
        }
        try {
            View drawer = decor.findViewById(
                    decor.getResources().getIdentifier(
                            "drawer_layout", "id", decor.getContext().getPackageName()));
            if (drawer == null) {
                return;
            }
            java.lang.reflect.Method m = drawer.getClass()
                    .getMethod("setStatusBarBackgroundColor", int.class);
            m.invoke(drawer, Color.TRANSPARENT);
        } catch (Throwable ignored) {
            // 宿主没这个布局/版本不同就直接跳过，不影响其它玻璃逻辑。
        }
    }

    /**
 * 仅清零导航栏 inset，保留状态栏 inset 供标题栏使用。
 * 不能用 setDecorFitsSystemWindows(false)：会连顶部一起丢，导致搜索/消息页标题栏塌陷
 */
    private static final View.OnApplyWindowInsetsListener DROP_NAV_INSET =
            new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v,
                                                        WindowInsets insets) {
                    return v.onApplyWindowInsets(withoutNavInset(insets));
                }
            };

    private static WindowInsets withoutNavInset(WindowInsets insets) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return withoutNavInsetApi30(insets);
            }
            return insets.replaceSystemWindowInsets(
                    insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), 0);
        } catch (Throwable t) {
            return insets;
        }
    }

    private static WindowInsets withoutNavInsetApi30(WindowInsets insets) {
        Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
        return new WindowInsets.Builder(insets)
                .setInsets(WindowInsets.Type.navigationBars(),
                        Insets.of(nav.left, nav.top, nav.right, 0))
                .build();
    }

    private static final class State {
        final int systemUiVisibility;
        final int navigationBarColor;
        final int navigationBarDividerColor;
        final boolean navigationBarContrastEnforced;
        final boolean drawsSystemBarBackgrounds;
        final boolean translucentNavigation;

        State(Window window) {
            View decor = window.getDecorView();
            systemUiVisibility = decor == null ? 0 : decor.getSystemUiVisibility();
            navigationBarColor = window.getNavigationBarColor();
            navigationBarDividerColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? window.getNavigationBarDividerColor() : Color.TRANSPARENT;
            navigationBarContrastEnforced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && window.isNavigationBarContrastEnforced();
            int flags = window.getAttributes().flags;
            drawsSystemBarBackgrounds = (flags & WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            translucentNavigation = (flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0;
        }
    }
}
