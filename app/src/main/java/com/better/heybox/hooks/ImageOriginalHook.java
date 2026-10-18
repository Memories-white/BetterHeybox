package com.better.heybox.hooks;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class ImageOriginalHook {

    private static final String HB_IMAGE_LOADER = "com.max.hbimage.image.HBImageLoader";

    private static final String IMAGE_UTIL_EXT = "com.max.hbimage.ImageUtilExt";

    private static final String IMG_URL_SCHEMA = "com.max.hbimage.f";

    private static final int LARGE_THUMB_MIN_PX = 480;

    private final AtomicInteger upgradeCount = new AtomicInteger();

    private final MainModule module;

    public ImageOriginalHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        if (installSchemaHook(cl)) {
            return;
        }
        installLegacyHook(cl);
    }

    private boolean installSchemaHook(ClassLoader cl) {
        try {
            Class<?> extClass = Class.forName(IMAGE_UTIL_EXT, false, cl);
            Class<?> schemaClass = Class.forName(IMG_URL_SCHEMA, false, cl);
            Method target = findResolveUrlMethod(extClass, schemaClass);
            if (target == null) {
                return false;
            }
            Constructor<?> schemaCtor = schemaClass.getDeclaredConstructor(String.class);
            schemaCtor.setAccessible(true);
            final Constructor<?> ctor = schemaCtor;
            module.hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    List<Object> args = chain.getArgs();
                    if (args == null || args.size() < 2) {
                        return result;
                    }
                    Object viewObject = args.get(1);
                    Object schema = args.get(0);
                    if (!(viewObject instanceof ImageView) || schema == null) {
                        return result;
                    }
                    String originalUrl = resolveOriginalUrl((ImageView) viewObject, (String) result);
                    if (originalUrl == null) {
                        return result;
                    }
                    Object replacement = ctor.newInstance(originalUrl);
                    List<Object> newArgs = chain.getArgs();
                    newArgs.set(0, replacement);
                    logUpgrade((String) result, originalUrl);
                    return originalUrl;
                } catch (Throwable t) {
                    module.logv(module.TAG, "图片自动原图: 替换 URL 失败，放行: " + t);
                    return result;
                }
            });
            module.logd(Log.INFO, module.TAG, "✔ 图片自动原图 Hook 已安装: ImageUtilExt#"
                    + target.getName() + "(" + describe(target) + ")");
            return true;
        } catch (Throwable t) {
            module.logv(module.TAG, "图片自动原图: 主入口不可用，回退旧入口: " + t);
            return false;
        }
    }

    private void installLegacyHook(ClassLoader cl) {
        try {
            Class<?> loaderClass = Class.forName(HB_IMAGE_LOADER, false, cl);
            Method target = findLoadMethod(loaderClass);
            if (target == null) {
                module.logd(Log.WARN, module.TAG,
                        "图片自动原图: 未找到图片加载入口，跳过");
                return;
            }
            final int expectedArgCount = target.getParameterCount();
            module.hook(target).intercept(chain -> {
                List<Object> args;
                try {
                    args = chain.getArgs();
                } catch (Throwable t) {
                    return chain.proceed();
                }
                if (args == null || args.size() != expectedArgCount) {
                    return chain.proceed();
                }
                Object viewObject = args.get(0);
                Object urlObject = args.get(1);
                if (!(viewObject instanceof ImageView) || !(urlObject instanceof String)) {
                    return chain.proceed();
                }
                String originalUrl;
                try {
                    originalUrl = resolveOriginalUrl((ImageView) viewObject, (String) urlObject);
                } catch (Throwable t) {
                    module.logv(module.TAG, "图片自动原图: 解析 URL 失败，放行: " + t);
                    return chain.proceed();
                }
                if (originalUrl == null) {
                    return chain.proceed();
                }
                List<Object> newArgs = chain.getArgs();
                newArgs.set(1, originalUrl);
                logUpgrade((String) urlObject, originalUrl);
                return chain.proceed();
            });
            module.logd(Log.INFO, module.TAG, "✔ 图片自动原图 Hook 已安装(旧入口): "
                    + target.getDeclaringClass().getSimpleName() + "#" + target.getName()
                    + "(" + describe(target) + ")");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 图片自动原图 Hook 失败", t);
        }
    }

    private static Method findResolveUrlMethod(Class<?> extClass, Class<?> schemaClass) {
        Method shapeMatch = null;
        for (Method method : extClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != String.class) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != schemaClass) {
                continue;
            }
            boolean hasImageView = false;
            for (Class<?> param : params) {
                if (param == ImageView.class) {
                    hasImageView = true;
                    break;
                }
            }
            if (!hasImageView) {
                continue;
            }
            if ("r".equals(method.getName())) {
                return method;
            }
            if (shapeMatch == null) {
                shapeMatch = method;
            }
        }
        return shapeMatch;
    }

    private static Method findLoadMethod(Class<?> loaderClass) {
        Method shapeMatch = null;
        for (Method method : loaderClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getReturnType() != Void.TYPE) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3 || params[0] != ImageView.class || params[1] != String.class) {
                continue;
            }
            if ("m".equals(method.getName())) {
                return method;
            }
            if (shapeMatch == null && params[2].isInterface()) {
                shapeMatch = method;
            }
        }
        return shapeMatch;
    }

    private String resolveOriginalUrl(ImageView view, String url) {
        if (url.isEmpty()) {
            return null;
        }
        if (!module.isEnabled(App.KEY_IMAGE_AUTO_ORIGINAL, false)) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        if (queryIndex <= 0 || queryIndex >= url.length() - 1) {
            return null;
        }
        String query = url.substring(queryIndex + 1);
        if (!isImageProcessQuery(query)) {
            return null;
        }
        int[] thumbSize = findThumbnailSize(query);
        if (thumbSize == null) {
            return null;
        }
        if (!shouldUpgrade(thumbSize, displaySize(view), view)) {
            return null;
        }
        return url.substring(0, queryIndex);
    }

    private static boolean isImageProcessQuery(String query) {
        return indexOfIgnoreCase(query, "imageMogr2") >= 0
                || indexOfIgnoreCase(query, "imageView2") >= 0;
    }

    private static int[] findThumbnailSize(String query) {
        for (String operation : query.split("&")) {
            String[] parts = operation.split("/");
            for (int i = 0; i + 1 < parts.length; i++) {
                if (!"thumbnail".equalsIgnoreCase(parts[i])) {
                    continue;
                }
                int[] size = parseGeometry(parts[i + 1]);
                if (size != null) {
                    return size;
                }
            }
        }
        return null;
    }

    private static int[] parseGeometry(String geometry) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        String value = geometry.replace("%3E", ">").replace("%3e", ">").replace("%21", "!");
        int end = value.length();
        while (end > 0) {
            char last = value.charAt(end - 1);
            if (last == '>' || last == '!') {
                end--;
            } else {
                break;
            }
        }
        value = value.substring(0, end);
        while (value.startsWith("!")) {
            value = value.substring(1);
        }
        int x = value.indexOf('x');
        if (x < 0) {
            return null;
        }
        int width = parseIntOrZero(value.substring(0, x));
        int height = parseIntOrZero(value.substring(x + 1));
        if (width <= 0 && height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    private static int[] displaySize(ImageView view) {
        int padLeft = view.getPaddingLeft();
        int padTop = view.getPaddingTop();
        int padRight = view.getPaddingRight();
        int padBottom = view.getPaddingBottom();
        int width = view.getWidth() - padLeft - padRight;
        int height = view.getHeight() - padTop - padBottom;
        if (width <= 0) {
            width = view.getMeasuredWidth() - padLeft - padRight;
        }
        if (height <= 0) {
            height = view.getMeasuredHeight() - padTop - padBottom;
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            if (width <= 0 && params.width > 0) {
                width = params.width;
            }
            if (height <= 0 && params.height > 0) {
                height = params.height;
            }
        }
        return new int[]{Math.max(width, 0), Math.max(height, 0)};
    }

    private static boolean shouldUpgrade(int[] thumbSize, int[] viewSize, ImageView view) {
        int thumbWidth = thumbSize[0];
        int thumbHeight = thumbSize[1];
        int viewWidth = viewSize[0];
        int viewHeight = viewSize[1];
        if (viewWidth > 0 && thumbWidth > 0 && viewWidth > thumbWidth) {
            return true;
        }
        if (viewHeight > 0 && thumbHeight > 0 && viewHeight > thumbHeight) {
            return true;
        }
        if (viewWidth > 0 || viewHeight > 0) {
            return false;
        }

        int thumbMax = Math.max(thumbWidth, thumbHeight);
        if (thumbMax < LARGE_THUMB_MIN_PX) {
            return false;
        }

        return true;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        int max = text.length() - needle.length();
        for (int i = 0; i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    private static int parseIntOrZero(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void logUpgrade(String from, String to) {
        int count = upgradeCount.incrementAndGet();
        if (count <= 10 || count % 100 == 0) {
            module.logd(Log.DEBUG, module.TAG, "图片自动原图: 已切换原图 (#" + count + ") "
                    + shorten(from) + " -> " + shorten(to));
        }
    }

    private static String shorten(String url) {
        if (url == null) {
            return "null";
        }
        return url.length() <= 120 ? url : url.substring(0, 120) + "...";
    }

    private static String describe(Method method) {
        StringBuilder builder = new StringBuilder();
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(params[i].getSimpleName());
        }
        return builder.toString();
    }
}