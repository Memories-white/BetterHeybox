package com.better.heybox.hooks;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import com.better.heybox.App;
import com.better.heybox.MainModule;

public final class ImageCacheDirHook {

    private static final String GLIDE_BUILDER = "com.bumptech.glide.c";
    private static final String GLIDE = "com.bumptech.glide.Glide";

    private static final String DISK_CACHE_FACTORY = "com.bumptech.glide.load.engine.cache.a$a";

    private static final String DISK_LRU_CACHE_FACTORY = "com.bumptech.glide.load.engine.cache.d";

    private static final long DISK_CACHE_SIZE_BYTES = 250L * 1024L * 1024L;

    private final MainModule module;

    private final AtomicBoolean appliedLogged = new AtomicBoolean(false);

    public ImageCacheDirHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            String configured = module.getString(App.KEY_IMAGE_CACHE_DIR, "");
            String path = configured == null ? "" : configured.trim();
            if (path.isEmpty()) {
                module.logv(module.TAG, "图片缓存目录: 未配置自定义目录，保持宿主默认");
                return;
            }
            File directory = new File(path);
            if (!directory.isAbsolute()) {
                module.logd(Log.WARN, module.TAG, "图片缓存目录不是绝对路径，忽略: " + path);
                return;
            }
            if (!directory.isDirectory() && !directory.mkdirs()) {
                module.logd(Log.ERROR, module.TAG, "图片缓存目录创建失败，保持宿主默认: " + path);
                return;
            }
            if (!directory.canWrite()) {
                module.logd(Log.ERROR, module.TAG, "图片缓存目录不可写，保持宿主默认: " + path);
                return;
            }

            Class<?> builderClass = Class.forName(GLIDE_BUILDER, false, cl);
            Class<?> factoryInterface = Class.forName(DISK_CACHE_FACTORY, false, cl);
            Class<?> factoryImplClass = Class.forName(DISK_LRU_CACHE_FACTORY, false, cl);

            Object factory = newDiskCacheFactory(factoryImplClass, directory.getAbsolutePath());
            Method build = findBuildMethod(builderClass);
            if (build == null) {
                module.logd(Log.WARN, module.TAG,
                        "图片缓存目录: 未找到 GlideBuilder.build，跳过");
                return;
            }
            Method setter = findFactorySetter(builderClass, factoryInterface);
            Field field = setter == null ? findFactoryField(builderClass, factoryInterface) : null;
            if (setter == null && field == null) {
                module.logd(Log.WARN, module.TAG,
                        "图片缓存目录: 未找到磁盘缓存工厂注入点（字段/ setter），跳过");
                return;
            }

            final Object factoryRef = factory;
            final Method setterRef = setter;
            final Field fieldRef = field;
            final String targetPath = directory.getAbsolutePath();
            module.hook(build).intercept(chain -> {
                try {
                    Object builder = chain.getThisObject();
                    if (builder != null) {
                        if (setterRef != null) {
                            setterRef.invoke(builder, factoryRef);
                        } else if (fieldRef != null) {
                            fieldRef.set(builder, factoryRef);
                        }
                    }
                    if (appliedLogged.compareAndSet(false, true)) {
                        module.logd(Log.INFO, module.TAG,
                                "图片缓存目录: 已接管 Glide 磁盘缓存 -> " + targetPath);
                    }
                } catch (Throwable t) {
                    if (appliedLogged.compareAndSet(false, true)) {
                        module.logd(Log.WARN, module.TAG,
                                "图片缓存目录: 替换 Glide 磁盘缓存工厂失败，本次沿用宿主默认", t);
                    }
                }
                return chain.proceed();
            });

            if (isGlideInitialized(cl)) {
                module.logd(Log.WARN, module.TAG,
                        "图片缓存目录: Glide 已初始化，新目录将在下次启动宿主后生效: " + targetPath);
            } else {
                module.logd(Log.INFO, module.TAG, "✔ 图片缓存目录 Hook 已安装: " + targetPath
                        + " (上限 " + (DISK_CACHE_SIZE_BYTES / (1024L * 1024L)) + "MB)");
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 图片缓存目录 Hook 失败", t);
        }
    }

    private static Object newDiskCacheFactory(Class<?> factoryImplClass, String path)
            throws Exception {
        Constructor<?> constructor =
                factoryImplClass.getDeclaredConstructor(String.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(path, DISK_CACHE_SIZE_BYTES);
    }

    private static Method findBuildMethod(Class<?> builderClass) {
        Method fallback = null;
        for (Method method : builderClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != Context.class) {
                continue;
            }
            if (!GLIDE.equals(method.getReturnType().getName())) {
                continue;
            }
            if (params.length == 3) {
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static Method findFactorySetter(Class<?> builderClass, Class<?> factoryInterface) {
        for (Method method : builderClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != factoryInterface) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static Field findFactoryField(Class<?> builderClass, Class<?> factoryInterface) {
        for (Field field : builderClass.getDeclaredFields()) {
            if (field.getType() != factoryInterface) {
                continue;
            }
            field.setAccessible(true);
            return field;
        }
        return null;
    }

    private static boolean isGlideInitialized(ClassLoader cl) {
        try {
            Class<?> glideClass = Class.forName(GLIDE, false, cl);
            for (Field field : glideClass.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != glideClass) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(null) != null) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
