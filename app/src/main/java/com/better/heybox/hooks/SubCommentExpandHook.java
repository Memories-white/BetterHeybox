package com.better.heybox.hooks;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.better.heybox.App;
import com.better.heybox.Checkpoint;
import com.better.heybox.MainModule;

public final class SubCommentExpandHook {

    private static final String ADAPTER_CLASS =
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2";
    private static final String COMMENTS_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentsObj";
    private static final String COMMENT_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentObj";
    private static final String SUB_COMMENT_VIEW_CLASS = "com.max.xiaoheihe.view.SubCommentView";
    private static final String FIELD_HAS_MORE = "p";
    private static final String FIELD_LOADING = "s";
    private static final String FIELD_TOTAL_LIST = "o";
    private static final String FIELD_FOOTER = "m";

    private static final String METHOD_HAS_MORE = "u";
    private static final String METHOD_TOTAL_LIST = "getComment";
    private static final String METHOD_COMMENT_ID = "getCommentid";

    private static final String METHOD_HAS_MORE_COMMENT = "hasMoreComment";

    private static final String METHOD_HAS_MORE_SUB = "isHasMoreSubComments";

    private static final String METHOD_LOAD_PAGE_CNT = "getSubCommentLoadPageCnt";

    private static final String METHOD_RENDER_FOOTER = "s";
    private static final String IS_NOT_EMPTY_CLASS = "com.max.hbcommon.utils.c";
    private static final String IS_NOT_EMPTY_METHOD = "y";

    private static final String EXPAND_BUTTON_CLASS =
            "com.max.hbcommon.component.ExpandMoreButton";

    private static final int MAX_TRACKED = 400;

    private static final int MAX_EXPAND_LOGS = 5;

    private static final int MAX_FALLBACK_DEPTH = 3;

    private static final long EXPAND_GAP_MS = 250L;

    private static final int MAX_PENDING = 80;

    private static final int MAX_RENDER_RETRY = 2;

    private static final long RENDER_RETRY_MS = 400L;

    private final MainModule module;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> autoExpanded = new LinkedHashSet<>();
    private final Set<String> warned = new HashSet<>();
    private int expandLogCount;
    private int renderLogCount;

    private final Object queueLock = new Object();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private boolean drainScheduled;

    private static volatile Field sFieldHasMore;
    private static volatile Field sFieldLoading;
    private static volatile Field sFieldTotalList;
    private static volatile Field sFieldFooter;
    private static volatile Method sMethodHasMore;
    private static volatile Method sMethodTotalList;
    private static volatile Method sMethodCommentId;
    private static volatile Method sMethodCommentsObjComment;
    private static volatile Method sMethodCommentObjHasMoreComment;
    private static volatile Method sMethodRenderFooter;
    private static volatile Method sMethodLoadPageCnt;

    private static volatile Method sMethodIsNotEmpty;
    private static volatile Method sMethodHasMoreSubComments;

    public SubCommentExpandHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            Class<?> adapterClass = Class.forName(ADAPTER_CLASS, false, cl);
            Class<?> commentsObjClass = Class.forName(COMMENTS_OBJ_CLASS, false, cl);
            Class<?> commentObjClass = Class.forName(COMMENT_OBJ_CLASS, false, cl);
            Class<?> subCommentViewClass = Class.forName(SUB_COMMENT_VIEW_CLASS, false, cl);

            sFieldHasMore = findField(subCommentViewClass, FIELD_HAS_MORE);
            sFieldLoading = findField(subCommentViewClass, FIELD_LOADING);
            sFieldTotalList = findField(subCommentViewClass, FIELD_TOTAL_LIST);

            sFieldFooter = findField(subCommentViewClass, FIELD_FOOTER);
            sMethodHasMore = findMethod(subCommentViewClass, METHOD_HAS_MORE);
            sMethodTotalList = findMethod(commentsObjClass, METHOD_TOTAL_LIST);
            sMethodCommentId = findMethod(commentObjClass, METHOD_COMMENT_ID);
            sMethodCommentsObjComment = findMethod(commentsObjClass, METHOD_TOTAL_LIST);
            sMethodCommentObjHasMoreComment = findMethod(commentObjClass, METHOD_HAS_MORE_COMMENT);
            sMethodLoadPageCnt = findMethod(commentsObjClass, METHOD_LOAD_PAGE_CNT);
            sMethodHasMoreSubComments = findMethod(commentsObjClass, METHOD_HAS_MORE_SUB);
            sMethodIsNotEmpty = findStaticStringBool(cl, IS_NOT_EMPTY_CLASS, IS_NOT_EMPTY_METHOD);
            sMethodRenderFooter = subCommentViewClass.getDeclaredMethod(
                    METHOD_RENDER_FOOTER, boolean.class);
            sMethodRenderFooter.setAccessible(true);

            Method bind = findCommentBindMethod(adapterClass, commentsObjClass,
                    subCommentViewClass);
            if (bind == null) {
                throw new NoSuchMethodException("comment bind method");
            }
            bind.setAccessible(true);
            module.hook(bind).intercept(chain -> {
                Object holder = chain.getArg(0);
                Object commentsObj = chain.getArg(1);
                Object result = chain.proceed();
                try {
                    if (module.isEnabled(App.KEY_COMMENT_AUTO_EXPAND, false)) {
                        View subCommentView = resolveSubCommentView(holder, subCommentViewClass);
                        onCommentBound(subCommentView, commentsObj);
                    }
                } catch (Throwable t) {
                    warnOnce("bound", "自动展开回复：绑定后处理异常，已放行: " + t);
                }
                return result;
            });

            boolean ready = (sFieldHasMore != null || sMethodHasMore != null)
                    && sMethodTotalList != null
                    && sMethodCommentId != null;
            Checkpoint.mark("自动展开回复安装: %b", ready);
            module.logd(ready ? Log.INFO : Log.WARN, module.TAG,
                    (ready ? "✔ 自动展开回复 Hook 已安装：" : "△ 自动展开回复 Hook 已挂，但字段解析不全：")
                            + ADAPTER_CLASS + "#" + bind.getName() + "("
                            + bind.getParameterTypes()[0].getName() + ", " + COMMENTS_OBJ_CLASS + ")"
                            + " [hasMore=" + (sFieldHasMore != null || sMethodHasMore != null)
                            + " loading=" + (sFieldLoading != null)
                            + " footer=" + (sFieldFooter != null)
                            + " list=" + (sMethodTotalList != null)
                            + " id=" + (sMethodCommentId != null) + "]");
        } catch (ClassNotFoundException | NoSuchMethodException e) {

            Checkpoint.mark("自动展开回复安装: 跳过（本版本无该挂点）");
            module.logd(Log.WARN, module.TAG,
                    "△ 自动展开回复 Hook 跳过：当前版本无 " + ADAPTER_CLASS
                            + "#public(*, " + COMMENTS_OBJ_CLASS + ") 挂点");
        } catch (Throwable t) {
            Checkpoint.mark("自动展开回复安装: 失败");
            module.logd(Log.ERROR, module.TAG, "✘ 自动展开回复 Hook 安装失败", t);
        }
    }

    /**
     * 优先选择适配器公开的两参数绑定入口。该入口在 1.3.397 是具体 holder 的混淆方法，
     * 1.3.398 则变成接收通用 ViewHolder 的 B0；按签名定位比记住短混淆名稳定。
     */
    private static Method findCommentBindMethod(Class<?> adapterClass,
                                                Class<?> commentsObjClass,
                                                Class<?> subCommentViewClass) {
        Method holderSpecific = null;
        for (Method method : adapterClass.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (method.getReturnType() != void.class || params.length != 2
                    || params[1] != commentsObjClass) {
                continue;
            }
            if (Modifier.isPublic(method.getModifiers())) {
                return method;
            }
            if (hasFieldAssignableTo(params[0], subCommentViewClass)) {
                holderSpecific = method;
            }
        }
        return holderSpecific;
    }

    private static boolean hasFieldAssignableTo(Class<?> type, Class<?> wanted) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (wanted.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View resolveSubCommentView(Object holder, Class<?> subCommentViewClass) {
        if (holder == null || subCommentViewClass == null) {
            return null;
        }
        if (subCommentViewClass.isInstance(holder) && holder instanceof View) {
            return (View) holder;
        }
        List<View> roots = new java.util.ArrayList<>();
        for (Class<?> current = holder.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (subCommentViewClass.isInstance(value) && value instanceof View) {
                        return (View) value;
                    }
                    if (value instanceof View) {
                        roots.add((View) value);
                    }
                } catch (Throwable ignored) {
                    // 继续从 itemView 或其它 View 字段寻找。
                }
            }
        }
        for (View root : roots) {
            View found = findSubCommentInTree(root, subCommentViewClass);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static View findSubCommentInTree(View root, Class<?> subCommentViewClass) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 1500) {
            View view = queue.removeFirst();
            if (subCommentViewClass.isInstance(view)) {
                return view;
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
        return null;
    }

    private void onCommentBound(View subCommentView, Object commentsObj) {
        if (subCommentView == null || commentsObj == null) {
            return;
        }
        String commentId = firstCommentId(commentList(commentsObj));
        if (commentId == null || commentId.isEmpty()) {
            return;
        }
        if (isExpanded(commentId)) {
            return;
        }
        if (!hasRemainingSubComments(commentsObj)) {
            return;
        }

        if (readBoolean(sFieldLoading, subCommentView, false)) {
            return;
        }
        if (!hasLoadedSubComments(commentsObj)) {

            enqueueExpand(subCommentView, commentId);
            return;
        }
        if (subCommentView.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!hasMoreOrUnknown(subCommentView)) {
            return;
        }
        enqueueExpand(subCommentView, commentId);
    }

    private static boolean hasMoreOrUnknown(View subCommentView) {
        Boolean read = rawHasMore(subCommentView);
        return read == null || read;
    }

    private static boolean hasRemainingSubComments(Object commentsObj) {
        List<?> list = commentList(commentsObj);
        if (list == null || list.isEmpty() || list.get(0) == null) {
            return false;
        }
        Object text = invoke(sMethodCommentObjHasMoreComment, list.get(0));
        if (text instanceof String && !((String) text).isEmpty()) {
            Object notEmpty = invoke(sMethodIsNotEmpty, null, text);
            if (notEmpty instanceof Boolean) {
                return (Boolean) notEmpty;
            }
            return true;
        }
        if (invoke(sMethodHasMoreSubComments, commentsObj) != null) {
            return Boolean.TRUE.equals(invoke(sMethodHasMoreSubComments, commentsObj));
        }

        return Boolean.TRUE.equals(invoke(sMethodHasMoreSubComments, commentsObj));
    }

    private static boolean hasLoadedSubComments(Object commentsObj) {
        List<?> list = commentList(commentsObj);
        return list != null && list.size() > 1 && list.get(0) != null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void enqueueExpand(View subCommentView, String commentId) {
        boolean schedule;
        synchronized (queueLock) {
            if (pending.size() >= MAX_PENDING) {
                return;
            }
            pending.addLast(new Pending(subCommentView, commentId));
            schedule = !drainScheduled;
            if (schedule) {
                drainScheduled = true;
            }
        }
        if (schedule) {
            main.post(this::drainPending);
        }
    }

    private void drainPending() {
        Pending next;
        synchronized (queueLock) {
            next = pending.pollFirst();
        }
        if (next != null) {
            expandPosted(next.subCommentView, next.commentId);
        }
        boolean more;
        synchronized (queueLock) {
            more = !pending.isEmpty();
            if (!more) {
                drainScheduled = false;
            }
        }
        if (more) {
            main.postDelayed(this::drainPending, EXPAND_GAP_MS);
        }
    }

    private static final class Pending {
        final View subCommentView;
        final String commentId;

        Pending(View subCommentView, String commentId) {
            this.subCommentView = subCommentView;
            this.commentId = commentId;
        }
    }

    private void expandPosted(View subCommentView, String commentId) {
        try {
            if (!module.isEnabled(App.KEY_COMMENT_AUTO_EXPAND, false)) {
                return;
            }
            if (subCommentView.getVisibility() != View.VISIBLE) {
                return;
            }
            if (readBoolean(sFieldLoading, subCommentView, false)) {
                return;
            }

            if (hasLoadedSubComments(subCommentView) && !hasMoreOrUnknown(subCommentView)) {
                return;
            }

            Object currentList = readObject(sFieldTotalList, subCommentView);
            if (!(currentList instanceof List)) {
                return;
            }
            String currentId = firstCommentId((List<?>) currentList);
            if (!commentId.equals(currentId)) {
                return;
            }
            if (!hasLoadedSubComments(subCommentView)) {

                renderFooterAndClick(subCommentView, commentId);
                return;
            }
            View footer = resolveFooter(subCommentView);
            if (footer == null || footer.getVisibility() != View.VISIBLE) {
                return;
            }
            if (!footer.hasOnClickListeners()) {
                return;
            }
            if (!markExpanded(commentId)) {
                return;
            }
            footer.performClick();
            logExpanded(commentId);
        } catch (Throwable t) {
            warnOnce("expand", "自动展开回复：点击 footer 异常，已放行: " + t);
        }
    }

    private static boolean hasLoadedSubComments(View subCommentView) {
        Object list = readObject(sFieldTotalList, subCommentView);
        return list instanceof List && ((List<?>) list).size() > 1;
    }

    private boolean renderFooterAndClick(View subCommentView, String commentId) {
        try {
            if (sMethodRenderFooter == null) {
                return false;
            }
            if (!hasFragmentCommentList(subCommentView)) {
                return false;
            }

            if (sFieldHasMore != null) {
                sFieldHasMore.setBoolean(subCommentView, true);
            }
            invoke(sMethodRenderFooter, subCommentView, false);
            View footer = resolveFooter(subCommentView);
            if (footer == null || footer.getVisibility() != View.VISIBLE
                    || !footer.hasOnClickListeners()) {
                warnOnce("renderFooter", "自动展开回复：footer 补渲染失败（宿主版本可能已变）");
                return false;
            }
            if (!markExpanded(commentId)) {
                return false;
            }
            logRendered(commentId);
            footer.performClick();
            return true;
        } catch (Throwable t) {
            warnOnce("renderFooter", "自动展开回复：footer 补渲染异常，已放行: " + t);
            return false;
        }
    }

    private static boolean hasFragmentCommentList(View subCommentView) {
        Object list = readObject(sFieldTotalList, subCommentView);
        return list instanceof List && !((List<?>) list).isEmpty()
                && ((List<?>) list).get(0) != null;
    }

    private static View resolveFooter(View subCommentView) {
        Object footer = readObject(sFieldFooter, subCommentView);
        if (footer instanceof View && ((View) footer).getParent() == subCommentView) {
            return (View) footer;
        }
        if (subCommentView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) subCommentView;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child != null && containsExpandButton(child, MAX_FALLBACK_DEPTH)) {
                    return child;
                }
            }
        }
        return null;
    }

    private static boolean containsExpandButton(View view, int depth) {
        if (view == null) {
            return false;
        }
        if (EXPAND_BUTTON_CLASS.equals(view.getClass().getName())) {
            return true;
        }
        if (depth <= 0 || !(view instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (containsExpandButton(group.getChildAt(i), depth - 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMore(View subCommentView) {
        Boolean read = rawHasMore(subCommentView);
        return read != null && read;
    }

    private static Boolean rawHasMore(View subCommentView) {
        Field field = sFieldHasMore;
        if (field != null) {
            try {
                return field.getBoolean(subCommentView);
            } catch (Throwable ignored) {

            }
        }
        Method method = sMethodHasMore;
        if (method != null) {
            try {
                Object value = method.invoke(subCommentView);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable ignored) {

            }
        }
        return null;
    }

    private static List<?> commentList(Object commentsObj) {
        Method method = sMethodTotalList;
        if (method == null || commentsObj == null) {
            return null;
        }
        try {
            Object result = method.invoke(commentsObj);
            return result instanceof List ? (List<?>) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstCommentId(List<?> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        Method method = sMethodCommentId;
        if (first == null || method == null) {
            return null;
        }
        try {
            Object id = method.invoke(first);
            return id instanceof String ? (String) id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isExpanded(String commentId) {
        synchronized (autoExpanded) {
            return autoExpanded.contains(commentId);
        }
    }

    private boolean markExpanded(String commentId) {
        synchronized (autoExpanded) {
            if (autoExpanded.contains(commentId)) {
                return false;
            }
            if (autoExpanded.size() >= MAX_TRACKED) {
                Iterator<String> iterator = autoExpanded.iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
            autoExpanded.add(commentId);
            return true;
        }
    }

    private void logExpanded(String commentId) {
        boolean log;
        synchronized (autoExpanded) {
            log = expandLogCount < MAX_EXPAND_LOGS;
            if (log) {
                expandLogCount++;
            }
        }
        if (log) {
            module.logv(module.TAG, "自动展开回复：评论 " + commentId + " 已触发「查看更多回复」");
        }
    }

    private void warnOnce(String key, String message) {
        synchronized (warned) {
            if (!warned.add(key)) {
                return;
            }
        }
        module.logd(Log.WARN, module.TAG, message);
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {

            } catch (Throwable ignored) {

            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {

            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findStaticStringBool(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            for (Method method : cls.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == String.class
                        && method.getReturnType() == Boolean.TYPE) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable ignored) {

        }
        return null;
    }

    private void logRendered(String commentId) {
        boolean log;
        synchronized (autoExpanded) {
            log = renderLogCount < MAX_EXPAND_LOGS;
            if (log) {
                renderLogCount++;
            }
        }
        if (log) {
            module.logv(module.TAG, "自动展开回复：评论 " + commentId + " 子评论区未渲染，已补渲染 footer");
        }
    }

    private static Object readObject(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean readBoolean(Field field, Object target, boolean defaultValue) {
        if (field == null || target == null) {
            return defaultValue;
        }
        try {
            return field.getBoolean(target);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }
}
