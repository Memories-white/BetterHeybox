package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.ViewUtils;

import java.lang.reflect.Method;
import java.util.List;

public final class CommentScreenshotShareHook {

    private static final String ACTION_TAG = "CommentScreenshot";
    private static final String ACTION_LABEL = "截图此评论";

    private final MainModule module;

    public CommentScreenshotShareHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        try {
            hookPanelActions(cl);
            hookPanelClick(cl);
            module.logd(Log.INFO, module.TAG, "✔ 评论分享面板截图目标已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 评论分享面板截图目标安装失败", t);
        }
    }

    private void hookPanelActions(ClassLoader cl) throws Throwable {
        Class<?> manager = Class.forName("com.max.common.common.share.ShareViewManager", false, cl);
        Class<?> forwardType = Class.forName("com.max.data.model.share.IForwardModel", false, cl);
        Method build = manager.getDeclaredMethod("m", Context.class, forwardType, List.class);
        module.hook(build).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object actions = chain.getArg(2);
                if (actions instanceof List && isCommentForward(chain.getArg(1), cl)
                        && recordedComment() != null) {
                    appendScreenshotAction((List<Object>) actions, cl,
                            chain.getArg(0) instanceof Context ? (Context) chain.getArg(0) : null);
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "追加评论截图目标失败: " + t);
            }
            return result;
        });
    }

    private void hookPanelClick(ClassLoader cl) throws Throwable {
        Class<?> dispatcher = Class.forName("com.max.common.common.share.ShareChannelDispatcher", false, cl);
        Class<?> actionType = Class.forName("com.max.data.model.share.IAction", false, cl);
        Class<?> modelType = Class.forName("com.max.data.model.share.BaseActionModel", false, cl);
        Method dispatch = dispatcher.getDeclaredMethod("e", Context.class, actionType, modelType);
        module.hook(dispatch).intercept(chain -> {
            try {
                Object action = chain.getArg(1);
                if (isScreenshotAction(action, cl) && recordedComment() != null) {
                    captureRecordedComment();
                    return null;
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "评论截图目标点击处理失败: " + t);
            }
            return chain.proceed();
        });
    }

    private boolean isScreenshotAction(Object action, ClassLoader cl) {
        if (action == null) {
            return false;
        }
        try {
            Class<?> customActionClass = Class.forName(
                    "com.max.data.model.share.IAction$CustomAction", false, cl);
            if (!customActionClass.isInstance(action)) {
                return false;
            }
            Method getActionTag = customActionClass.getMethod("getActionTag");
            return ACTION_TAG.equals(String.valueOf(getActionTag.invoke(action)));
        } catch (Throwable t) {
            return false;
        }
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

    private void appendScreenshotAction(List<Object> actions, ClassLoader cl, Context context) {
        if (hasAction(actions, cl)) {
            return;
        }
        Class<?> actionObjClass;
        Class<?> actionClass;
        Class<?> customActionClass;
        try {
            actionObjClass = Class.forName("com.max.data.bean.share.ActionObj", false, cl);
            actionClass = Class.forName("com.max.data.model.share.IAction", false, cl);
            customActionClass = Class.forName("com.max.data.model.share.IAction$CustomAction", false, cl);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "评论截图目标类型不可用: " + t);
            return;
        }
        try {
            Object customAction = customActionClass.getConstructor(String.class)
                    .newInstance(ACTION_TAG);
            Object actionObject = actionObjClass.getConstructor(
                            String.class, Integer.class, String.class, String.class,
                            String.class, String.class, actionClass)
                    .newInstance(ACTION_LABEL, resolvePanelIcon(context),
                            null, null, null, null, customAction);
            actions.add(actionObject);
            module.logd(Log.INFO, module.TAG, "评论分享面板已追加「" + ACTION_LABEL
                    + "」: count=" + actions.size());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构造评论截图目标失败: " + t);
        }
    }

    private boolean hasAction(List<Object> actions, ClassLoader cl) {
        try {
            for (Object actionObject : actions) {
                if (actionObject == null) {
                    continue;
                }
                Object action = actionObject.getClass().getMethod("getAction").invoke(actionObject);
                Object tag = action.getClass().getMethod("getActionTag").invoke(action);
                if (ACTION_TAG.equals(String.valueOf(tag))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {

        }
        return false;
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

    private static View recordedComment() {
        return CommentCopyHook.recordedCommentForSharePanel();
    }

    private static int resolvePanelIcon(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            return context.getResources().getIdentifier(
                    "bbs_sharebutton_download_46x46", "drawable", MainModule.TARGET_PKG);
        } catch (Throwable t) {
            return 0;
        }
    }
}
