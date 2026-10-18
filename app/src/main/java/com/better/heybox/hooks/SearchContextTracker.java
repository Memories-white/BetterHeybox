package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.better.heybox.App;
import com.better.heybox.MainModule;

import java.lang.ref.WeakReference;

public final class SearchContextTracker {

    private static final String SEARCH_ACTIVITY_PREFIX = "com.max.hbsearch.SearchNew";

    private static final String USER_HOME_ACTIVITY_PREFIX = "com.max.xiaoheihe.module.account.MeHomeActivity";

    private static final String FOLLOW_LIST_ACTIVITY_PREFIX =
            "com.max.xiaoheihe.module.account.FollowingAndFansActivity";

    private static final String HOME_ACTIVITY_PREFIX = "com.max.xiaoheihe.MainActivity";

    private static final String[] FOLLOW_TAB_FRAGMENT_PREFIXES = {
            "com.max.xiaoheihe.module.bbs.FollowedMomentsFragment",
    };

    private static final String[] RECOMMEND_TAB_FRAGMENT_PREFIXES = {
            "com.max.feature.feeds.RecommendFeedsFlowFragment",
    };

    private static final long RETRY_COOLDOWN_MS = 5_000L;

    private static final Object LOCK = new Object();

    private static volatile MainModule sModule;

    private static volatile boolean sRegistered;

    private static volatile long sNextRetryAt;

    private static volatile WeakReference<Activity> sResumed;

    private static volatile boolean sSearchPage;

    private static volatile boolean sFollowContext;

    private static volatile String sFollowReason;

    private SearchContextTracker() {
    }

    public static void install(MainModule module) {
        if (module != null) {
            sModule = module;
        }
        ensureRegistered();
    }

    public static boolean isSearchContext() {
        if (!sRegistered) {
            ensureRegistered();
        }
        return sSearchPage;
    }

    public static boolean isFollowContext() {
        if (!sRegistered) {
            ensureRegistered();
        }
        return sFollowContext;
    }

    public static String followContextReason() {
        return sFollowReason;
    }

    public static String resumedActivityName() {
        WeakReference<Activity> ref = sResumed;
        Activity activity = ref != null ? ref.get() : null;
        return activity != null ? activity.getClass().getName() : null;
    }

    private static void ensureRegistered() {
        if (sRegistered) {
            return;
        }
        synchronized (LOCK) {
            if (sRegistered) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now < sNextRetryAt) {
                return;
            }
            Application app = resolveApplication();
            if (app == null) {
                sNextRetryAt = now + RETRY_COOLDOWN_MS;
                log(Log.WARN, "搜索页跟踪未注册：拿不到宿主 Application");
                return;
            }
            try {
                app.registerActivityLifecycleCallbacks(new Callbacks());
                sRegistered = true;
                log(Log.INFO, "搜索页上下文跟踪已注册");
            } catch (Throwable t) {
                sNextRetryAt = now + RETRY_COOLDOWN_MS;
                log(Log.WARN, "搜索页跟踪注册失败: " + t);
            }
        }
    }

    private static Application resolveApplication() {
        try {
            Context context = App.resolveAppContext();
            if (context == null) {
                return null;
            }
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                return (Application) app;
            }
            return context instanceof Application ? (Application) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(int level, String msg) {
        MainModule module = sModule;
        if (module != null) {
            try {
                module.logd(level, module.TAG, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void refreshFollowContext(String activityName) {
        boolean follow = false;
        String reason = null;
        try {
            if (activityName == null) {
                follow = false;
            } else if (activityName.startsWith(USER_HOME_ACTIVITY_PREFIX)) {
                follow = true;
                reason = "user_home";
            } else if (activityName.startsWith(FOLLOW_LIST_ACTIVITY_PREFIX)) {
                follow = true;
                reason = "following_list";
            } else if (activityName.startsWith(HOME_ACTIVITY_PREFIX)) {

                WeakReference<Activity> ref = sResumed;
                Activity activity = ref != null ? ref.get() : null;
                if (activity != null && matchesFollowTabFragment(activity)) {
                    follow = true;
                    reason = "follow_tab";
                }
            }
        } catch (Throwable t) {
            follow = false;
            reason = null;
        }
        if (follow != sFollowContext || (reason != null && !reason.equals(sFollowReason))) {
            log(Log.INFO, "关注/用户主页上下文: 页面=" + activityName
                    + " 命中=" + follow + (reason != null ? " 类型=" + reason : ""));
        }
        sFollowReason = reason;
        sFollowContext = follow;
    }

    private static boolean matchesFollowTabFragment(Activity activity) {
        try {

            java.lang.reflect.Method fmGetter = null;
            for (Class<?> c = activity.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    fmGetter = c.getDeclaredMethod("getSupportFragmentManager");
                    if (fmGetter != null) {
                        break;
                    }
                } catch (NoSuchMethodException ignored) {

                }
            }
            if (fmGetter == null) {
                return false;
            }
            Object fm = fmGetter.invoke(activity);
            if (fm == null) {
                return false;
            }
            java.util.List<Object> all = new java.util.ArrayList<>(8);
            collectFragments(fm, all, 0);
            boolean hasFollow = false;
            for (Object f : all) {
                String name = f.getClass().getName();
                if (matchesAny(name, RECOMMEND_TAB_FRAGMENT_PREFIXES)) {
                    return false;
                }
                if (matchesAny(name, FOLLOW_TAB_FRAGMENT_PREFIXES)) {
                    hasFollow = true;
                }
            }
            return hasFollow;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void collectFragments(Object fm, java.util.List<Object> out, int depth) {
        if (fm == null || depth > 6 || out.size() > 200) {
            return;
        }
        try {
            Class<?> fmCls = Class.forName("androidx.fragment.app.FragmentManager");
            java.lang.reflect.Method getAll = fmCls.getMethod("getFragments");
            Object fragments = getAll.invoke(fm);
            if (!(fragments instanceof java.util.List)) {
                return;
            }
            for (Object f : (java.util.List<?>) fragments) {
                if (f == null || out.size() > 200) {
                    continue;
                }
                out.add(f);
                Class<?> fCls = Class.forName("androidx.fragment.app.Fragment");
                java.lang.reflect.Method childGetter = fCls.getMethod("getChildFragmentManager");
                collectFragments(childGetter.invoke(f), out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean matchesAny(String name, String[] prefixes) {
        if (name == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity == null) {
                return;
            }
            sResumed = new WeakReference<>(activity);
            String name = activity.getClass().getName();
            boolean search = name.startsWith(SEARCH_ACTIVITY_PREFIX);
            refreshFollowContext(name);
            if (search != sSearchPage) {
                sSearchPage = search;
                log(Log.INFO, "当前页面: " + name
                        + " 搜索页=" + search + " 关注/用户主页=" + sFollowContext);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            WeakReference<Activity> ref = sResumed;
            Activity resumed = ref != null ? ref.get() : null;
            if (activity == null) {
                return;
            }

            if (resumed == null || activity == resumed) {
                sResumed = null;
                if (sSearchPage || sFollowContext) {
                    sSearchPage = false;
                    sFollowContext = false;
                    sFollowReason = null;
                    log(Log.INFO, "离开页面: " + activity.getClass().getName());
                }
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            WeakReference<Activity> ref = sResumed;
            Activity resumed = ref != null ? ref.get() : null;
            if (activity != null && (resumed == null || activity == resumed)) {
                sResumed = null;
                sSearchPage = false;
                sFollowContext = false;
                sFollowReason = null;
            }
        }
    }
}
