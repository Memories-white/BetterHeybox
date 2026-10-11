package com.better.heybox.hooks;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class FeedRefreshUndoHook {

    private static final String VM_CLASS = "com.max.feature.feeds.viewmodel.RecommendFeedsFlowViewModel";
    private static final String UI_STATE_CLASS = "com.max.feature.feeds.uistate.RecommendFeedsFlowUiState";
    private static final String LOAD_TYPE_CLASS = "com.max.common.common.datasource.FeedsLoadType";

    private final MainModule module;

    public FeedRefreshUndoHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        boolean undo = false;
        boolean keep = false;
        try {
            Class<?> vm = Class.forName(VM_CLASS, false, cl);
            Class<?> uiState = Class.forName(UI_STATE_CLASS, false, cl);
            Class<?> loadType = Class.forName(LOAD_TYPE_CLASS, false, cl);
            Class<?> listCls = List.class;

            Method tips = null;
            Method merge = null;
            for (Method m : vm.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 4 || p[3] != boolean.class || p[0] != uiState || p[1] != loadType) {
                    continue;
                }
                if (p[2] == uiState && m.getReturnType() == uiState) {
                    tips = pick(tips, m);
                } else if (p[2] == listCls && m.getReturnType() == listCls) {
                    merge = pick(merge, m);
                }
            }

            if (tips != null) {
                module.hook(tips).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (args.size() == 4 && module.isEnabled(App.KEY_FEED_REFRESH_UNDO, false)) {
                        Object[] copy = args.toArray();
                        copy[3] = Boolean.TRUE;
                        return chain.proceed(copy);
                    }
                    return chain.proceed();
                });
                undo = true;
            }

            if (merge != null) {
                module.hook(merge).intercept(chain -> {
                    List<Object> args = chain.getArgs();
                    if (args.size() == 4 && module.isEnabled(App.KEY_FEED_KEEP_PREVIOUS, false)) {
                        Object[] copy = args.toArray();
                        copy[3] = Boolean.TRUE;
                        return chain.proceed(copy);
                    }
                    return chain.proceed();
                });
                keep = true;
            }
        } catch (Throwable t) {
            com.better.heybox.Checkpoint.mark("首页信息流 Hook 安装失败: %s", String.valueOf(t));
            module.logd(Log.WARN, module.TAG, "✘ 首页信息流 Hook 失败: " + t);
        }
        com.better.heybox.Checkpoint.mark("首页信息流 Hook 安装: 气泡=" + (undo ? "ok" : "未匹配")
                + " keep_previous=" + (keep ? "ok" : "未匹配"));
        module.logd(Log.INFO, module.TAG, "✔ 首页信息流 Hook: 撤销气泡="
                + (undo ? "ok" : "未匹配") + " 保留上次内容=" + (keep ? "ok" : "未匹配")
                + " 开关[撤销=" + module.isEnabled(App.KEY_FEED_REFRESH_UNDO, false)
                + ", 保留=" + module.isEnabled(App.KEY_FEED_KEEP_PREVIOUS, false) + "]");
    }

    private static Method pick(Method current, Method candidate) {
        return current != null ? current : candidate;
    }
}
