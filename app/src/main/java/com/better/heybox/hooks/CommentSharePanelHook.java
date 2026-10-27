package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.Toast;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ViewUtils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class CommentSharePanelHook {

    private static final String SCREENSHOT_ACTION_TAG = "CommentScreenshot";
    private static final String SCREENSHOT_ACTION_LABEL = "截图此评论";
    private static final String REPLY_ACTION_TAG = "CommentReply";
    private static final String REPLY_ACTION_LABEL = "回复";
    private static final String REPLY_ICON_NAME = "bbs_sharebutton_edit_tie_46x46";

    private static final String ADAPTER_CLASS =
            "com.max.xiaoheihe.module.bbs.adapter.CommentAdapterV2";
    private static final String COMMENT_TEXT_CLASS =
            "com.max.xiaoheihe.view.CustomLongPressExpressionTextView";
    private static final String SUB_COMMENT_VIEW_CLASS =
            "com.max.xiaoheihe.view.SubCommentView";
    private static final String COMMENT_OBJ_CLASS = "com.max.basebbs.bean.BBSCommentObj";
    /**
     * 评论区用户意图在宿主更新后会整体换包名。1.3.398 使用 {@code id.b$c}，
     * 旧版使用 {@code hd.b$c}；两者都是持有「主评论、目标评论」的 Reply intent。
     */
    private static final String[] REPLY_INTENT_CLASS_CANDIDATES = {"id.b$c", "hd.b$c"};
    /**
     * Kotlin {@code Function1} 接口的混淆类名，逐版本轮换。全树唯一实现类的判据是
     * 声明了 {@code public abstract invoke(Ljava/lang/Object;)Ljava/lang/Object;}。
     *
     * <p>1.3.397(1138/1137) = {@code vn.l}，1.3.396 = {@code xn.l}，1.3.394/395 = {@code wn.l}。
     *
     * <p><b>不能只按名字探测。</b>同名类在其它版本里可能被无关类型占用：1.3.394/395 的
     * {@code vn.l} 是 {@code JvmPlatformAnnotations.kt} 的注解（同样是 interface 但不是函数接口），
     * 1.3.396 的 {@code wn.l}、1.3.397 的 {@code un.l} 亦然。若只做 {@code Class.forName}
     * 命中即返回，394/395 上会取到注解类，真正的 {@code wn.l} 永远不会被尝试。
     * 因此这里逐个候选校验「是接口且自身声明了 invoke(Object):Object」。
     */
    private static final String[] FUNCTION1_CLASS_CANDIDATES = {"vn.l", "xn.l", "wn.l"};

    private final MainModule module;

    private static Class<?> sCommentTextClass;
    private static Class<?> sSubCommentViewClass;
    private static Class<?> sCommentObjClass;
    private static Class<?> sReplyIntentClass;
    private static Class<?> sFunction1Class;

    private static volatile WeakReference<Object> sReplyHandler;
    private static volatile WeakReference<Object> sReplyReceiver;

    public CommentSharePanelHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        boolean actions = false;
        boolean click = false;
        boolean capture = false;
        int blocked;
        try {
            resolveClasses(cl);
            hookPanelActions(cl);
            hookPanelClick(cl);
            capture = hookReplyHandlerCapture(cl);
            blocked = hookTapReply(cl);
            actions = true;
            click = true;
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 评论分享面板安装失败", t);
            blocked = 0;
        }
        module.logd(Log.WARN, module.TAG, "[评论分享面板] Hook 安装结果：面板目标=" + (actions ? "✔" : "✘")
                + " / 点击分发=" + (click ? "✔" : "✘")
                + " / 回复回调捕获=" + (capture ? "✔" : "✘")
                + " / 单击回复拦截=" + blocked + " 处"
                + " / 状态=" + (module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true) ? "开启" : "关闭"));
    }

    private void resolveClasses(ClassLoader cl) {
        sCommentTextClass = tryForName(COMMENT_TEXT_CLASS, cl);
        sSubCommentViewClass = tryForName(SUB_COMMENT_VIEW_CLASS, cl);
        sCommentObjClass = tryForName(COMMENT_OBJ_CLASS, cl);
        sReplyIntentClass = resolveReplyIntentClass(cl);
        sFunction1Class = resolveFunction1Class(cl);
        if (sCommentTextClass == null || sCommentObjClass == null || sReplyIntentClass == null) {
            module.logd(Log.WARN, module.TAG, "评论分享面板类型解析缺失: text=" + (sCommentTextClass != null)
                    + " obj=" + (sCommentObjClass != null)
                    + " intent=" + (sReplyIntentClass != null));
        }
        if (sFunction1Class == null) {
            module.logd(Log.WARN, module.TAG, "评论分享面板类型解析缺失: function1=null"
                    + "（候选 " + String.join(" / ", FUNCTION1_CLASS_CANDIDATES) + " 全部失效，"
                    + "回复回调捕获将降级）");
        }
    }

    /**
     * 逐个候选探测 Kotlin {@code Function1} 接口，并且**校验语义**而不是只看名字能否加载。
     *
     * <p>判据：候选必须是 interface，且**自身**声明了
     * {@code invoke(Ljava/lang/Object;)Ljava/lang/Object;}。只看 {@code Class.forName} 是否成功
     * 是不够的 —— 同名类在别的版本里会被无关类型占用（1.3.394/395 的 {@code vn.l} 是注解、
     * 1.3.396 的 {@code wn.l} 与 1.3.397 的 {@code un.l} 同理），命中即返回会让真正的
     * Function1 永远排不上。
     */
    private static Class<?> resolveFunction1Class(ClassLoader cl) {
        for (String name : FUNCTION1_CLASS_CANDIDATES) {
            Class<?> c = tryForName(name, cl);
            if (c == null || !c.isInterface()) {
                continue;
            }
            try {
                Method m = c.getDeclaredMethod("invoke", Object.class);
                if (m.getReturnType() == Object.class) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 该候选不是函数接口，继续尝试下一个
            }
        }
        return null;
    }

    private static Class<?> resolveReplyIntentClass(ClassLoader cl) {
        if (sCommentObjClass == null) {
            return null;
        }
        for (String name : REPLY_INTENT_CLASS_CANDIDATES) {
            Class<?> candidate = tryForName(name, cl);
            if (candidate == null) {
                continue;
            }
            try {
                candidate.getConstructor(sCommentObjClass, sCommentObjClass);
                return candidate;
            } catch (Throwable ignored) {
                // 同名类型也必须具备 Reply intent 的双评论构造器。
            }
        }
        return null;
    }

    private static Class<?> tryForName(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private void hookPanelActions(ClassLoader cl) throws Throwable {
        Class<?> manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl);
        Class<?> forwardType = Class.forName("com.max.data.model.share.IForwardModel", false, cl);
        Method build = manager.getDeclaredMethod("m", Context.class, forwardType, List.class);
        module.hook(build).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object forward = chain.getArg(1);
                Object actions = chain.getArg(2);
                Context context = chain.getArg(0) instanceof Context
                        ? (Context) chain.getArg(0) : null;
                boolean commentForward = isCommentForward(forward, cl);
                View recorded = recordedComment();
                module.logd(Log.INFO, module.TAG, "评论分享面板构建：forward="
                        + (forward == null ? "null" : forward.getClass().getName())
                        + " / 评论转发=" + commentForward
                        + " / 长按记录=" + (recorded != null)
                        + " / actions=" + (actions instanceof List ? ((List<?>) actions).size() : -1)
                        + " / 回复=" + module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)
                        + " / 截图=" + module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true));
                if (actions instanceof List && commentForward) {
                    if (recorded == null) {
                        module.logd(Log.WARN, module.TAG,
                                "评论分享面板：没有可用的长按记录，跳过「回复/截图此评论」注入"
                                        + "（长按记录 hook 是否安装见启动日志「评论自由复制」）");
                    } else {
                        appendReplyAction((List<Object>) actions, cl, context);
                        appendScreenshotAction((List<Object>) actions, cl, context);
                    }
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "追加评论面板目标失败: " + t);
            }
            return result;
        });
    }

    private boolean isCommentForward(Object forward, ClassLoader cl) {
        if (forward == null) {
            return false;
        }
        for (String name : new String[]{
                "com.max.data.model.community.CommentForwardModel",
                "com.max.data.model.community.GameCommentForwardModel"}) {
            try {
                if (Class.forName(name, false, cl).isInstance(forward)) {
                    return true;
                }
            } catch (Throwable ignored) {

            }
        }
        return false;
    }

    private void appendReplyAction(List<Object> actions, ClassLoader cl, Context context) {
        if (!module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true) || replyHandler() == null) {
            module.logd(Log.INFO, module.TAG, "跳过「回复」：开关="
                    + module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)
                    + " 回复回调=" + (replyHandler() != null));
            return;
        }
        if (sCommentObjClass == null || sReplyIntentClass == null || sFunction1Class == null) {
            module.logd(Log.INFO, module.TAG, "跳过「回复」：类型缺失 obj="
                    + (sCommentObjClass != null) + " intent=" + (sReplyIntentClass != null)
                    + " function1=" + (sFunction1Class != null));
            return;
        }
        Object comment = commentObjOf(recordedComment());
        if (comment == null) {
            return;
        }
        if (hasAction(actions, cl, REPLY_ACTION_TAG)) {
            return;
        }
        appendCustomAction(actions, cl, context, REPLY_ACTION_TAG, REPLY_ACTION_LABEL,
                resolvePanelIcon(context, REPLY_ICON_NAME));
    }

    private void appendScreenshotAction(List<Object> actions, ClassLoader cl, Context context) {
        // 截图开关关闭时不要注入「截图此评论」，否则面板上会出现一个点了没反应的死按钮。
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            module.logd(Log.INFO, module.TAG, "跳过「截图此评论」：截图总开关关闭");
            return;
        }
        if (hasAction(actions, cl, SCREENSHOT_ACTION_TAG)) {
            return;
        }
        appendCustomAction(actions, cl, context, SCREENSHOT_ACTION_TAG, SCREENSHOT_ACTION_LABEL,
                resolvePanelIcon(context, "bbs_sharebutton_download_46x46"));
    }

    private void appendCustomAction(List<Object> actions, ClassLoader cl, Context context,
                                    String tag, String label, int iconRes) {
        Class<?> actionObjClass;
        Class<?> actionClass;
        Class<?> customActionClass;
        try {
            actionObjClass = Class.forName("com.max.data.bean.share.ActionObj", false, cl);
            actionClass = Class.forName("com.max.data.model.share.IAction", false, cl);
            customActionClass = Class.forName("com.max.data.model.share.IAction$CustomAction", false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "评论面板目标类型不可用: " + t);
            return;
        }
        try {
            Object customAction = customActionClass.getConstructor(String.class)
                    .newInstance(tag);
            Object actionObject = actionObjClass.getConstructor(
                            String.class, Integer.class, String.class, String.class,
                            String.class, String.class, actionClass)
                    .newInstance(label, iconRes,
                            null, null, null, null, customAction);
            actions.add(actionObject);
            module.logd(Log.INFO, module.TAG, "评论分享面板已追加「" + label
                    + "」: count=" + actions.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构造评论面板目标「" + label + "」失败: " + t);
        }
    }

    private boolean hasAction(List<Object> actions, ClassLoader cl, String tag) {
        try {
            for (Object actionObject : actions) {
                if (actionObject == null) {
                    continue;
                }
                Object action = actionObject.getClass().getMethod("getAction").invoke(actionObject);
                if (action == null) {
                    continue;
                }
                Object actionTag = action.getClass().getMethod("getActionTag").invoke(action);
                if (tag.equals(String.valueOf(actionTag))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {

        }
        return false;
    }

    private static int resolvePanelIcon(Context context, String name) {
        if (context == null) {
            return 0;
        }
        try {
            return context.getResources().getIdentifier(
                    name, "drawable", MainModule.TARGET_PKG);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void hookPanelClick(ClassLoader cl) throws Throwable {
        Class<?> dispatcher = Class.forName("com.max.common.common.share.ShareChannelDispatcher", false, cl);
        Class<?> actionType = Class.forName("com.max.data.model.share.IAction", false, cl);
        Class<?> modelType = Class.forName("com.max.data.model.share.BaseActionModel", false, cl);
        Method dispatch = dispatcher.getDeclaredMethod("e", Context.class, actionType, modelType);
        module.hook(dispatch).intercept(chain -> {
            try {
                Object action = chain.getArg(1);
                String tag = customActionTag(action, cl);
                if (SCREENSHOT_ACTION_TAG.equals(tag) && recordedComment() != null) {
                    captureRecordedComment();
                    return null;
                }
                if (REPLY_ACTION_TAG.equals(tag)) {
                    triggerReply();
                    return null;
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "评论面板目标点击处理失败: " + t);
            }
            return chain.proceed();
        });
    }

    private String customActionTag(Object action, ClassLoader cl) {
        if (action == null) {
            return null;
        }
        try {
            Class<?> customActionClass = Class.forName(
                    "com.max.data.model.share.IAction$CustomAction", false, cl);
            if (!customActionClass.isInstance(action)) {
                return null;
            }
            Method getActionTag = customActionClass.getMethod("getActionTag");
            return String.valueOf(getActionTag.invoke(action));
        } catch (Throwable t) {
            return null;
        }
    }

    private void captureRecordedComment() {
        if (!module.isEnabled(App.KEY_SCREENSHOT_ENABLED, true)) {
            return;
        }
        View comment = recordedComment();
        if (comment == null) {
            module.logd(Log.WARN, module.TAG, "评论截图目标被点击但没有可用的评论记录");
            return;
        }
        Activity activity = ViewUtils.findActivity(comment);
        if (activity == null || activity.isFinishing()) {
            return;
        }
        ScreenshotCaptureHook screenshotHook = ScreenshotCaptureHook.getInstance();
        if (screenshotHook == null) {
            return;
        }
        module.logd(Log.INFO, module.TAG, "评论分享面板截图目标命中");
        screenshotHook.captureCommentDirectly(activity, comment,
                ScreenshotCaptureHook.SOURCE_COMMENT);
    }

    private boolean hookReplyHandlerCapture(ClassLoader cl) {
        try {
            Class<?> adapter = Class.forName(ADAPTER_CLASS, false, cl);
            int hooked = 0;
            for (Constructor<?> ctor : adapter.getDeclaredConstructors()) {
                int lambdaIdx = -1;
                Class<?>[] params = ctor.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (sFunction1Class != null && sFunction1Class.isAssignableFrom(params[i])) {
                        lambdaIdx = i;
                        break;
                    }
                }
                if (lambdaIdx < 0) {
                    continue;
                }
                final int idx = lambdaIdx;
                module.hook(ctor).intercept(chain -> {
                    try {
                        Object lambda = chain.getArg(idx);
                        if (lambda != null) {
                            sReplyHandler = new WeakReference<>(lambda);
                            sReplyReceiver = new WeakReference<>(receiverOf(lambda));
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "记录评论回复回调失败: " + t);
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到评论适配器回调参数: " + ADAPTER_CLASS);
            }
            return hooked > 0;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "评论回复回调捕获安装失败: " + t);
            return false;
        }
    }

    private static Object receiverOf(Object lambda) {
        Class<?> type = lambda.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField("receiver");
                field.setAccessible(true);
                return field.get(lambda);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object replyHandler() {
        WeakReference<Object> ref = sReplyHandler;
        return ref == null ? null : ref.get();
    }

    private void triggerReply() {
        if (!module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)) {
            return;
        }
        Object handler = replyHandler();
        if (handler == null) {
            module.logd(Log.WARN, module.TAG, "「回复」被点击但没有可用的回复回调");
            toast(recordedComment(), "回复入口已失效，请重新长按评论");
            return;
        }
        Object receiver = sReplyReceiver == null ? null : sReplyReceiver.get();
        if (receiver == null || !receiverAlive(receiver)) {
            module.logd(Log.WARN, module.TAG, "「回复」被点击但评论页已不可用");
            toast(recordedComment(), "评论页已刷新，请重新长按评论");
            return;
        }
        View comment = recordedComment();
        Object target = commentObjOf(comment);
        if (target == null) {
            module.logd(Log.WARN, module.TAG, "「回复」被点击但评论对象不可用");
            toast(comment, "没有识别到这条评论，请重新长按");
            return;
        }
        Object root = resolveRootComment(comment, target);
        if (root == null || sReplyIntentClass == null || sCommentObjClass == null) {
            module.logd(Log.WARN, module.TAG, "「回复」被点击但回复目标不完整");
            toast(comment, "回复失败，请重试");
            return;
        }
        try {
            Object intent = sReplyIntentClass.getConstructor(sCommentObjClass, sCommentObjClass)
                    .newInstance(root, target);
            handler.getClass().getMethod("invoke", Object.class).invoke(handler, intent);
            module.logd(Log.INFO, module.TAG, "评论分享面板「回复」已触发 root="
                    + (root == target ? "自身" : "主评论"));
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "触发评论回复失败: " + t);
            toast(comment, "回复失败，请重试");
        }
    }

    private static void toast(View anchor, String message) {
        if (anchor == null) {
            return;
        }
        try {
            Context context = anchor.getContext();
            if (context == null) {
                return;
            }
            Context app = context.getApplicationContext();
            Toast.makeText(app != null ? app : context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private boolean receiverAlive(Object receiver) {
        try {
            Method added = receiver.getClass().getMethod("isAdded");
            Object result = added.invoke(receiver);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (NoSuchMethodException ignored) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object commentObjOf(View view) {
        if (view == null || sCommentObjClass == null) {
            return null;
        }
        Object tag;
        try {
            tag = view.getTag();
        } catch (Throwable t) {
            return null;
        }
        return sCommentObjClass.isInstance(tag) ? tag : null;
    }

    private Object resolveRootComment(View recorded, Object target) {
        if (recorded == null || sSubCommentViewClass == null) {
            return target;
        }
        ViewParent parent = recorded.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 30) {
            View current = (View) parent;
            if (sSubCommentViewClass.isInstance(current)) {
                View main = findMainCommentAbove(current, recorded);
                Object root = main == null ? null : commentObjOf(main);
                return root != null ? root : target;
            }
            parent = current.getParent();
        }
        return target;
    }

    private View findMainCommentAbove(View start, View recorded) {
        ViewParent parent = start.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 20) {
            View current = (View) parent;
            List<View> found = new ArrayList<>();
            collectMainCommentViews(current, found, 0, new int[]{0});
            if (found.size() == 1) {
                return found.get(0);
            }
            if (found.size() > 1) {
                return null;
            }
            parent = current.getParent();
        }
        return null;
    }

    private void collectMainCommentViews(View node, List<View> out, int depth, int[] budget) {
        if (node == null || depth > 40 || budget[0] > 2000) {
            return;
        }
        budget[0]++;
        if (isCommentTextView(node)) {
            if (node.getTag() != null && !isInsideSubCommentView(node)) {
                out.add(node);
            }
            return;
        }
        if (node instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectMainCommentViews(group.getChildAt(i), out, depth + 1, budget);
            }
        }
    }

    private static boolean isCommentTextView(View view) {
        Class<?> clazz = sCommentTextClass;
        if (clazz != null) {
            return clazz.isInstance(view);
        }
        return COMMENT_TEXT_CLASS.equals(view.getClass().getName());
    }

    private boolean isInsideSubCommentView(View view) {
        if (sSubCommentViewClass == null) {
            return false;
        }
        ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 30) {
            if (sSubCommentViewClass.isInstance(parent)) {
                return true;
            }
            parent = ((View) parent).getParent();
        }
        return false;
    }

    private int hookTapReply(ClassLoader cl) {
        int blocked = 0;
        blocked += blockTapReply(cl, ADAPTER_CLASS + "$f", "onClick", 1);
        blocked += blockTapReply(cl, ADAPTER_CLASS + "$i", "onClick", 1);
        blocked += blockTapReply(cl, ADAPTER_CLASS + "$k", "onClick", 1);
        blocked += blockTapReply(cl, ADAPTER_CLASS + "$q", "b", 3);
        return blocked;
    }

    private int blockTapReply(ClassLoader cl, String className, String methodName, int paramCount) {
        try {
            Class<?> clazz = Class.forName(className, false, cl);
            Method target = null;
            for (Method method : clazz.getDeclaredMethods()) {
                if (methodName.equals(method.getName())
                        && method.getParameterTypes().length == paramCount) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                module.logd(Log.WARN, module.TAG, "✘ 单击回复拦截挂点缺失: "
                        + className + "#" + methodName);
                return 0;
            }
            module.hook(target).intercept(chain -> {
                if (module.isEnabled(App.KEY_COMMENT_REPLY_PANEL, true)) {
                    return null;
                }
                return chain.proceed();
            });
            return 1;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "单击回复拦截安装失败 " + className + "#" + methodName + ": " + t);
            return 0;
        }
    }

    private static View recordedComment() {
        return CommentCopyHook.recordedCommentForSharePanel();
    }
}
