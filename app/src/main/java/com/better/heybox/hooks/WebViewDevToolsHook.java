package com.better.heybox.hooks;

import android.util.Log;
import android.webkit.WebView;

import java.lang.reflect.Constructor;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/** 为小黑盒内置 WebView 开启 Chrome DevTools 远程调试。 */
public final class WebViewDevToolsHook {
    private final MainModule module;

    public WebViewDevToolsHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            int count = 0;
            for (Constructor<?> constructor : WebView.class.getDeclaredConstructors()) {
                try {
                    module.hook(constructor).intercept(chain -> {
                        Object result = chain.proceed();
                        applyDevToolsState();
                        return result;
                    });
                    count++;
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "WebView 构造器 Hook 失败: " + constructor, t);
                }
            }
            module.logd(Log.INFO, module.TAG, "✔ WebView 原生 DevTools Hook 已安装（构造器 " + count + " 个）");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ WebView DevTools Hook 失败", t);
        }
    }

    private static volatile Boolean sLastApplied;

    private void applyDevToolsState() {
        boolean enable = module.isEnabled(App.KEY_WEBVIEW_DEVTOOLS, false);
        Boolean last = sLastApplied;
        if (last != null && last == enable) {
            return;
        }
        try {
            WebView.setWebContentsDebuggingEnabled(enable);
            sLastApplied = enable;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "同步 WebView DevTools 状态(" + enable + ")失败", t);
        }
    }
}
