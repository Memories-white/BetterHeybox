package com.better.heybox.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.PixelCopy;
import android.view.ViewParent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;


public final class ScreenshotRenderer {

    
    private static final int GAP_DP = 10;
    
    private static final int PADDING_DP = 10;
    
    
    private static final int MAX_OUTPUT_HEIGHT = 20000;
    
    private static final int EXPAND_NODES = 600;
    
    private static final int SCROLL_SETTLE_MS = 300;
    
    private static final int SETTLE_EXTRA_MS = 140;
    
    private static final int CHUNK_OVERLAP = 16;

    private static final int MAX_STITCH_CHUNKS = 12;

    private ScreenshotRenderer() {
    }

    
    public interface BitmapCallback {
        void onCaptured(Bitmap bitmap);
    }

    
    public static final class FoldedText {
        private final List<TextView> relaxed = new ArrayList<>();
        private final List<Integer> maxLines = new ArrayList<>();
        private final List<TextUtils.TruncateAt> ellipsize = new ArrayList<>();
        
        public boolean clicked;

        
        public void restore() {
            for (int i = 0; i < relaxed.size(); i++) {
                try {
                    relaxed.get(i).setMaxLines(maxLines.get(i));
                    relaxed.get(i).setEllipsize(ellipsize.get(i));
                } catch (Throwable ignored) {

                }
            }
            relaxed.clear();
            maxLines.clear();
            ellipsize.clear();
        }
    }

    
    public static FoldedText expandFoldedTexts(List<View> units) {
        FoldedText state = new FoldedText();
        if (units == null) {
            return state;
        }
        for (View unit : units) {
            if (unit == null) {
                continue;
            }
            try {
                relaxFoldedText(unit, state);
                if (clickExpandControl(unit)) {
                    state.clicked = true;
                }
            } catch (Throwable ignored) {

            }
        }
        return state;
    }

    private static void relaxFoldedText(View root, FoldedText state) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            View current = queue.poll();
            if (current instanceof TextView) {
                TextView text = (TextView) current;
                int lines = text.getMaxLines();
                if (lines > 0 && lines != Integer.MAX_VALUE) {
                    state.relaxed.add(text);
                    state.maxLines.add(lines);
                    state.ellipsize.add(text.getEllipsize());
                    text.setMaxLines(Integer.MAX_VALUE);
                    text.setEllipsize(null);
                }
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
    }

    private static boolean clickExpandControl(View root) {
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < EXPAND_NODES) {
            View current = queue.poll();
            if (current instanceof TextView && current.isShown() && current.isClickable()) {
                CharSequence raw = ((TextView) current).getText();
                String text = raw == null ? "" : raw.toString().trim();
                if (text.length() > 0 && text.length() <= 12
                        && (text.contains("展开全文") || text.contains("展开剩余")
                        || "全文".equals(text))) {
                    current.performClick();
                    return true;
                }
            }
            if (current instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) current;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return false;
    }

    
    public static int backgroundColor(Context context) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
                if (value.resourceId != 0) {
                    return context.getResources().getColor(value.resourceId);
                }
            }
        } catch (Throwable ignored) {

        }
        return Color.WHITE;
    }

    
    
    public static void layoutForCapture(View unit) {
        if (unit == null) {
            return;
        }
        int width = unit.getWidth();
        if (width <= 0) {
            width = unit.getMeasuredWidth();
        }
        if (width <= 0) {
            return;
        }
        try {
            int originalHeight = unit.getHeight();
            unit.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = unit.getMeasuredHeight();
            int height = fallbackHeight(measured, originalHeight);
            if (height > 0) {
                unit.layout(0, 0, width, height);
            }
        } catch (Throwable ignored) {

        }
    }

    /**
     * UNSPECIFIED 高度重排时，match_parent / weight / ConstraintLayout 0dp 子项可能塌成 0，
     * 用视口里原本的高度兜底，避免截出被裁掉的下半张图（#59 H2）。
     */
    private static int fallbackHeight(int measured, int originalHeight) {
        if (originalHeight <= 0) {
            return measured;
        }
        if (measured <= 0 || measured < originalHeight * 0.6f) {
            return originalHeight;
        }
        return measured;
    }

    public static Bitmap captureUnit(View unit, int bgColor) {
        if (unit == null) {
            return null;
        }
        int width = unit.getWidth();
        if (width <= 0) {
            return null;
        }
        final boolean fixedHeight = ScreenshotSelectionManager.isWebView(unit);

        int savedLeft = unit.getLeft();
        int savedTop = unit.getTop();
        int savedRight = unit.getRight();
        int savedBottom = unit.getBottom();
        int originalHeight = unit.getHeight();
        int height = originalHeight;

        try {
            unit.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int measured = unit.getMeasuredHeight();
            if (!fixedHeight) {
                height = fallbackHeight(measured, originalHeight);
            }
            if (height <= 0) {
                return null;
            }
            unit.layout(0, 0, width, height);

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(bgColor);
            unit.draw(canvas);
            if (ScreenshotSelectionManager.DEBUG) {
                android.util.Log.i("BetterHeybox", "截图诊断 单元绘制 " + unit.getClass().getSimpleName()
                        + " 原 h=" + originalHeight + " measured=" + measured
                        + " -> 位图 " + width + "x" + height);
            }
            return bitmap;
        } catch (OutOfMemoryError error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                unit.layout(savedLeft, savedTop, savedRight, savedBottom);
                unit.requestLayout();
            } catch (Throwable ignored) {

            }
        }
    }

    
    public static void captureWebViewAsync(Activity activity, View web, int bgColor, BitmapCallback callback) {
        if (web == null || callback == null) {
            if (callback != null) {
                callback.onCaptured(null);
            }
            return;
        }
        int width = web.getWidth();
        int viewHeight = web.getHeight();
        if (width <= 0 || viewHeight <= 0) {
            callback.onCaptured(null);
            return;
        }

        int total = Math.min(MAX_OUTPUT_HEIGHT, viewHeight * MAX_STITCH_CHUNKS);
        int content = contentHeight(web);
        if (content > total) {
            total = content;
        }
        try {
            long budget = Runtime.getRuntime().maxMemory() / 2;
            int byMemory = (int) Math.max(viewHeight, Math.min(Integer.MAX_VALUE, budget / (4L * width)));
            total = Math.min(total, byMemory);
        } catch (Throwable ignored) {

        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                android.util.Log.i("BetterHeybox", "截图诊断 网页拼接 宽=" + width + " 视口高=" + viewHeight
                        + " 总高=" + total + " 内容高=" + content + " attempt=" + attempt);
                new WebStitcher(activity, web, width, viewHeight, total, bgColor, callback, 0).start();
                return;
            } catch (OutOfMemoryError error) {
                total /= 2;
                if (total < viewHeight) {
                    break;
                }
            }
        }
        callback.onCaptured(null);
    }

    
    private static int contentHeight(View web) {
        try {
            Object raw = web.getClass().getMethod("getContentHeight").invoke(web);
            float scale = 1f;
            try {
                Object value = web.getClass().getMethod("getScale").invoke(web);
                if (value instanceof Number) {
                    scale = ((Number) value).floatValue();
                }
            } catch (Throwable ignored) {

            }
            int content = (int) (((Number) raw).intValue() * (scale <= 0f ? 1f : scale));
            return Math.max(content, 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    
    private static Bitmap drawChunk(View web, int width, int crop, int height, int bgColor) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        try {
            Bitmap chunk = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(chunk);
            canvas.drawColor(bgColor);
            canvas.save();
            canvas.clipRect(0, 0, width, height);
            if (crop > 0) {
                canvas.translate(0, -crop);
            }
            web.draw(canvas);
            canvas.restore();
            return chunk;
        } catch (OutOfMemoryError error) {
            throw error;
        } catch (Throwable ignored) {
            return null;
        }
    }

    
    private static boolean isUniform(Bitmap chunk) {
        int width = chunk.getWidth();
        int height = chunk.getHeight();
        int step = 8;
        int reference = chunk.getPixel(0, 0);
        int sampled = 0;
        int different = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                sampled++;
                if (chunk.getPixel(x, y) != reference) {
                    different++;
                }
            }
        }
        return sampled == 0 || different * 100 < sampled;
    }

    
    private static long signature(Bitmap chunk) {
        int width = chunk.getWidth();
        int height = chunk.getHeight();
        long hash = 1125899906842597L;
        for (int gy = 0; gy < 16; gy++) {
            int y = Math.min(height - 1, gy * Math.max(1, height / 16));
            for (int gx = 0; gx < 16; gx++) {
                int x = Math.min(width - 1, gx * Math.max(1, width / 16));
                hash = hash * 31 + chunk.getPixel(x, y);
            }
        }
        return hash;
    }

    
    private static final int WARMUP_DELAY_MS = 90;

    private static final int WARMUP_SETTLE_MS = 420;

    private static final int CHUNK_RETRIES = 2;

    private static final int STITCH_ATTEMPTS = 3;

    private static final class WebStitcher {
        private final Activity activity;
        private final View web;
        private final int width;
        private final int viewHeight;
        /**
         * 真正可见的取图高度。WebView 高度可能大于父容器能露出的区域（被裁剪/被底部输入栏遮挡），
         * 此时 Chromium 的滚动上限按「可见高度」算：仍按 view.getHeight() 取 1401px 会在块尾
         * 取到大片页面背景空白，表现为长截图截到一半就断掉（#59）。
         */
        private int captureHeight;
        private int total;
        private final int bgColor;
        private final BitmapCallback callback;
        private final int attempt;
        private final int savedScrollY;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final java.util.List<View> scrolledAncestors = new java.util.ArrayList<>();
        private final java.util.List<Integer> ancestorScrolls = new java.util.ArrayList<>();

        private Bitmap output;
        private Canvas canvas;
        private int drawn;
        private int captureStartContent;
        private int captureRows;
        private long lastChunkSignature;
        private int blankRetries;
        private int jamRetries;
        private int stableTries;
        private int lastStableContent = -1;
        private int pendingCrop;
        private int pendingHeight;
        private int retries;
        private boolean restartNeeded;
        private boolean finishing;

        WebStitcher(Activity activity, View web, int width, int viewHeight, int total, int bgColor,
                BitmapCallback callback, int attempt) {
            this.activity = activity;
            this.web = web;
            this.width = width;
            this.viewHeight = viewHeight;
            this.captureHeight = viewHeight;
            this.total = total;
            this.bgColor = bgColor;
            this.callback = callback;
            this.attempt = attempt;
            this.savedScrollY = web.getScrollY();
        }

        void start() {
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 网页拼接开始 宽=" + width + " 视口高=" + viewHeight
                        + " 预算高=" + total + " 内容高=" + contentHeight(web) + " attempt=" + attempt
                        + " 已滚动=" + savedScrollY);
            }
            bringIntoView();
            updateCaptureHeight();
            warmup();
        }

        private void updateCaptureHeight() {
            try {
                Rect visible = new Rect();
                if (web.getGlobalVisibleRect(visible) && visible.height() > 0) {
                    captureHeight = Math.min(viewHeight, visible.height());
                }
            } catch (Throwable ignored) {

            }
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 网页可见高=" + captureHeight
                        + " 视图高=" + viewHeight);
            }
        }

        /**
         * 先把整篇内容快速滚一遍，触发 WebView 懒加载图片与排版；
         * 否则截图过程中图片陆续加载会让内容整体重排，拼接处出现错位或重复（#59）。
         */
        private void warmup() {
            final int content = Math.max(contentHeight(web), total);
            final int step = Math.max(viewHeight * 3 / 4, 240);
            final int limit = Math.min(content, total) + viewHeight;
            final int[] position = {0};
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (position[0] > limit) {
                        warmupDone();
                        return;
                    }
                    try {
                        web.scrollTo(0, position[0]);
                    } catch (Throwable ignored) {

                    }
                    position[0] += step;
                    handler.postDelayed(this, WARMUP_DELAY_MS);
                }
            });
        }

        private void warmupDone() {
            try {
                web.scrollTo(0, 0);
            } catch (Throwable ignored) {

            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    allocate();
                }
            }, WARMUP_SETTLE_MS);
        }

        private void allocate() {
            // 懒加载帖的 getContentHeight 会一路变高：等内容高度连续一致后再分配/开截，
            // 否则后面的滚动范围会小于真实内容。
            int measured = contentHeight(web);
            if (stableTries < 8 && (stableTries < 2 || measured != lastStableContent)) {
                lastStableContent = measured;
                stableTries++;
                if (ScreenshotSelectionManager.get().isDebug()) {
                    android.util.Log.i("BetterHeybox", "截图诊断 等内容高度稳定 第 " + stableTries
                            + " 次 内容高=" + measured);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        allocate();
                    }
                }, 380);
                return;
            }
            int content = contentHeight(web);
            if (content > total) {
                total = content;
            }
            try {
                long budget = Runtime.getRuntime().maxMemory() / 2;
                int byMemory = (int) Math.max(viewHeight, Math.min(Integer.MAX_VALUE, budget / (4L * width)));
                total = Math.min(total, Math.max(viewHeight, byMemory));
            } catch (Throwable ignored) {

            }
            try {
                output = Bitmap.createBitmap(width, total, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError error) {
                int reduced = Math.max(viewHeight * 2, total / 2);
                try {
                    output = Bitmap.createBitmap(width, reduced, Bitmap.Config.ARGB_8888);
                    total = reduced;
                } catch (OutOfMemoryError again) {
                    callback.onCaptured(null);
                    return;
                }
            }
            canvas = new Canvas(output);
            canvas.drawColor(bgColor);
            step();
        }

        /** 让 WebView 尽量完整地落在窗口内，PixelCopy 才能拿到整屏内容。 */
        private void bringIntoView() {
            try {
                int[] location = new int[2];
                int[] windowLocation = new int[2];
                View decor = activity.getWindow().getDecorView();
                web.getLocationOnScreen(location);
                decor.getLocationOnScreen(windowLocation);
                int top = location[1] - windowLocation[1];
                int windowHeight = decor.getHeight();
                if (top >= 0 && top + viewHeight <= windowHeight) {
                    return;
                }
                ViewParent parent = web.getParent();
                for (int depth = 0; depth < 20 && parent instanceof View; depth++) {
                    View p = (View) parent;
                    if (p.getScrollY() != 0) {
                        scrolledAncestors.add(p);
                        ancestorScrolls.add(p.getScrollY());
                        p.scrollTo(0, 0);
                    }
                    parent = p.getParent();
                }
                web.getLocationOnScreen(location);
                top = location[1] - windowLocation[1];
                if (top < 0 || top + viewHeight > windowHeight) {
                    web.requestRectangleOnScreen(new Rect(0, 0, width, viewHeight), false);
                }
                if (ScreenshotSelectionManager.get().isDebug()) {
                    web.getLocationOnScreen(location);
                    top = location[1] - windowLocation[1];
                    android.util.Log.i("BetterHeybox", "截图诊断 网页入屏 top=" + top
                            + " 视口高=" + viewHeight + " 窗口高=" + windowHeight);
                }
            } catch (Throwable ignored) {

            }
        }

        private void step() {
            if (finishing) {
                return;
            }
            int content = contentHeight(web);
            if (content > 0 && drawn >= content - 2) {
                finish();
                return;
            }
            if (drawn >= total) {
                finish();
                return;
            }
            // 超过 Chromium 的滚动上限会取到整屏空白帧（#59 的截断点），
            // 因此请求位置按「内容高 - 可见高」夹一下。
            int maxScroll = content > 0 ? Math.max(0, content - Math.max(1, captureHeight)) : Integer.MAX_VALUE;
            final int requested = Math.min(Math.max(0, drawn - CHUNK_OVERLAP), maxScroll);
            try {
                web.scrollTo(0, requested);
            } catch (Throwable ignored) {
                captureFallback(requested);
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    settled(requested);
                }
            }, SCROLL_SETTLE_MS);
        }

        private void settled(int requested) {
            int actual = requested;
            try {
                actual = web.getScrollY();
            } catch (Throwable ignored) {
                actual = requested;
            }
            if (actual < 0) {
                actual = requested;
            }
            // 滚不到目标位置说明已经到底：最后一屏可能不满一屏（#59 的经典场景），
            // 这时只把「还没截到」的那部分补上，绝不能重复贴上一屏的内容。
            // 懒加载帖的坑：Chromium 的 getContentHeight 已经变高，但可滚动范围还是旧的，
            // getScrollY 会卡在原位置不再前进。此时不能当成“到底”收尾，要等它重新布局后再滚。
            int contentNow = contentHeight(web);
            boolean scrollJammed = drawn > 0 && actual < requested - 2;
            if (scrollJammed && jamRetries < 10 && contentNow > Math.max(drawn, actual) + 4) {
                jamRetries++;
                if (ScreenshotSelectionManager.get().isDebug()) {
                    android.util.Log.i("BetterHeybox", "截图诊断 滚动到不了目标，等页面重新布局 第 "
                            + jamRetries + " 次 requested=" + requested + " actual=" + actual
                            + " 内容高=" + contentNow + " 已拼=" + drawn);
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        step();
                    }
                }, 420);
                return;
            }
            if (!scrollJammed) {
                jamRetries = 0;
            }
            boolean bottomReached = scrollJammed;
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 网页滚动 requested=" + requested
                        + " actual=" + actual + " 已拼=" + drawn + " 到底=" + bottomReached
                        + " 内容高=" + contentHeight(web));
            }
            int start = bottomReached ? Math.max(drawn, actual)
                    : Math.max(drawn - CHUNK_OVERLAP, actual);
            if (start >= total) {
                finish();
                return;
            }
            int availableEnd = actual + Math.max(1, captureHeight);
            if (start >= availableEnd) {
                finish();
                return;
            }
            Rect rect = captureRect(actual, start);
            if (rect == null) {
                captureFallback(actual);
                return;
            }
            pixelCopy(rect, false);
        }

        /**
         * 计算本次要从窗口的哪一块取像素：rowOffset 是块内要跳过的行数。
         * 同时把「块的第 0 行对应正文第几行」记录到 captureStartContent。
         */
        private Rect captureRect(int actual, int startContent) {
            try {
                int[] location = new int[2];
                int[] windowLocation = new int[2];
                View decor = activity.getWindow().getDecorView();
                web.getLocationOnScreen(location);
                decor.getLocationOnScreen(windowLocation);
                int windowWidth = decor.getWidth();
                int windowHeight = decor.getHeight();
                int rawTop = location[1] - windowLocation[1];
                int left = Math.max(0, location[0] - windowLocation[0]);
                int right = Math.min(left + width, windowWidth);
                int rowOffset = Math.max(0, startContent - actual);
                int clippedAbove = Math.max(0, -rawTop);
                int top = Math.max(0, rawTop) + rowOffset;
                int height = Math.min(captureHeight - rowOffset - clippedAbove, windowHeight - top);
                if (right - left <= 0 || height < Math.max(1, captureHeight / 4)) {
                    return null;
                }
                captureStartContent = actual + rowOffset + clippedAbove;
                captureRows = height;
                return new Rect(left, top, right, top + height);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void pixelCopy(final Rect rect, final boolean second) {
            final Bitmap chunk;
            try {
                chunk = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError error) {
                throw error;
            } catch (Throwable ignored) {
                if (second) {
                    captureFallback(captureStartContent);
                } else {
                    retryPixelCopy(rect);
                }
                return;
            }
            try {
                PixelCopy.request(activity.getWindow(), rect, chunk, new PixelCopy.OnPixelCopyFinishedListener() {
                    @Override
                    public void onPixelCopyFinished(int copyResult) {
                        if (copyResult != PixelCopy.SUCCESS) {
                            chunk.recycle();
                            if (second) {
                                captureFallback(captureStartContent);
                            } else {
                                retryPixelCopy(rect);
                            }
                            return;
                        }
                        if (second) {
                            accept(captureStartContent, chunk);
                            return;
                        }
                        chunk.recycle();
                        retryPixelCopy(rect);
                    }
                }, handler);
            } catch (OutOfMemoryError error) {
                throw error;
            } catch (Throwable ignored) {
                chunk.recycle();
                if (second) {
                    captureFallback(captureStartContent);
                } else {
                    retryPixelCopy(rect);
                }
            }
        }

        private void retryPixelCopy(final Rect rect) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pixelCopy(rect, true);
                }
            }, SETTLE_EXTRA_MS);
        }

        private void captureFallback(int actual) {
            int start = Math.max(drawn, actual);
            int crop = Math.max(0, start - actual);
            int height = Math.min(viewHeight - crop, total - start);
            if (height <= 0) {
                finish();
                return;
            }
            pendingCrop = crop;
            pendingHeight = height;
            Bitmap chunk = drawChunk(web, width, crop, height, bgColor);
            if (chunk == null) {
                finish();
                return;
            }
            accept(start, chunk);
        }

        private void accept(int contentStart, Bitmap chunk) {
            if (finishing) {
                chunk.recycle();
                return;
            }
            if (chunk == null || chunk.getHeight() <= 0) {
                if (chunk != null) {
                    chunk.recycle();
                }
                finish();
                return;
            }
            final int chunkHeight = chunk.getHeight();
            final int contentTop = Math.max(0, contentStart);
            final boolean uniform = drawn > 0 && isUniform(chunk);
            final long signature = uniform ? 0L : signature(chunk);
            // WebView 到底后 getScrollY() 可能仍返回请求值，像素却被限制在内容底部：
            // 此时后续每一块会和上一块完全一样。发现重复块立刻收尾，避免把底部重复贴一遍。
            if (!uniform && drawn > 0 && lastChunkSignature != 0L && signature == lastChunkSignature) {
                if (ScreenshotSelectionManager.get().isDebug()) {
                    android.util.Log.i("BetterHeybox", "截图诊断 分块与上一块相同，判定已到内容底部");
                }
                chunk.recycle();
                finish();
                return;
            }
            int overlap = Math.min(CHUNK_OVERLAP, Math.max(0, drawn - contentTop));
            overlap = Math.min(overlap, chunkHeight);
            boolean overlapOk = true;
            if (!uniform && overlap > 0) {
                overlapOk = overlapMatches(contentTop, chunk, overlap);
            }
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 WebView 分块 y=" + contentStart
                        + " 重叠校验=" + overlap + " 校验通过=" + overlapOk
                        + " 高=" + chunkHeight + " 空白=" + uniform + " 已拼=" + drawn);
            }
            if (uniform) {
                chunk.recycle();
                finish();
                return;
            }
            if (!overlapOk) {
                chunk.recycle();
                retries++;
                if (retries <= CHUNK_RETRIES) {
                    if (ScreenshotSelectionManager.get().isDebug()) {
                        android.util.Log.i("BetterHeybox", "截图诊断 重叠不一致，重截当前位置 第 " + retries + " 次");
                    }
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            int actual;
                            try {
                                actual = web.getScrollY();
                            } catch (Throwable ignored) {
                                actual = Math.max(0, drawn - CHUNK_OVERLAP);
                            }
                            settled(actual);
                        }
                    }, SETTLE_EXTRA_MS * 2L);
                    return;
                }
                // 多次重试仍旧对不上：内容多半在截图过程中重排了（懒加载/动画）。
                // 这里选择「强制接受」而不是整篇重来：整体重来既慢又可能反复失败，
                // 至少保证长图完整，接缝问题交给下一轮排查。
                android.util.Log.w("BetterHeybox", "截图诊断 重叠多次不一致，强制接受当前分块");
                restartNeeded = true;
            }
            retries = 0;
            lastChunkSignature = signature;
            // 内容到底后 Chromium 还会继续“滚动”：越界的那部分像素是纯背景色。
            // 这里把每一块末尾的空白行裁掉，避免长图尾部多出一大段空白。
            int lastRow = lastContentRow(chunk);
            if (lastRow < 0) {
                chunk.recycle();
                // 滚动后 WebView 偶尔会给出一帧还没画好的空白画面（懒加载/合成延迟）。
                // 只「原地等」往往等不到：先错开 40px 再滚回来，强制 Chromium 重新合成，
                // 再多等一会儿重取；三次仍空白且内容还没截完就退化成软件绘制兜底。
                blankRetries++;
                if (blankRetries <= 3) {
                    if (ScreenshotSelectionManager.get().isDebug()) {
                        android.util.Log.i("BetterHeybox", "截图诊断 分块空白，错位重绘后重取 第 "
                                + blankRetries + " 次 y=" + contentTop);
                    }
                    final int target = Math.max(0, contentTop);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                web.scrollTo(0, Math.max(0, target - 40));
                            } catch (Throwable ignored) {

                            }
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        web.scrollTo(0, target);
                                    } catch (Throwable ignored) {

                                    }
                                    handler.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            step();
                                        }
                                    }, 520);
                                }
                            }, 260);
                        }
                    }, 320);
                    return;
                }
                if (contentHeight(web) > drawn + 6) {
                    android.util.Log.w("BetterHeybox", "截图诊断 分块持续空白，改用软件绘制兜底 y=" + contentTop);
                    captureFallback(contentTop);
                    return;
                }
                finish();
                return;
            }
            blankRetries = 0;
            int usedHeight = Math.min(chunkHeight, lastRow + 1);
            int at = Math.max(0, contentStart);
            if (ScreenshotSelectionManager.get().isDebug()) {
                android.util.Log.i("BetterHeybox", "截图诊断 长图落点 y=" + at + " 高度=" + chunkHeight);
            }
            int previous = drawn;
            try {
                canvas.drawBitmap(chunk, new Rect(0, 0, width, usedHeight),
                        new Rect(0, at, width, at + usedHeight), null);
            } catch (Throwable ignored) {

            }
            int bottom = at + usedHeight;
            if (bottom > drawn) {
                drawn = Math.min(total, bottom);
            }
            chunk.recycle();
            if (drawn <= previous) {
                finish();
                return;
            }
            if (drawn >= total) {
                finish();
                return;
            }
            step();
        }

        /**
         * 用上一块的已绘制像素校验本块顶部重叠区，能发现内容中途重排导致的错位。
         * 按 32x4 像素块比较平均色并留余量：WebView 亚像素滚动会让逐像素抗锯齿结果不同，
         * 但真正的排版错位会让整块内容差异明显，所以块均值既能容忍抗锯齿又能发现重排。
         */
        /** 返回块内最后一行“非背景色”的行号，-1 表示整块都是空白。右侧滚动条区域不参与判断。 */
        private int lastContentRow(Bitmap chunk) {
            try {
                int chunkWidth = chunk.getWidth();
                int xLimit = Math.max(1, chunkWidth - 96);
                int stepX = Math.max(1, xLimit / 64);
                int backgroundRed = (bgColor >> 16) & 0xFF;
                int backgroundGreen = (bgColor >> 8) & 0xFF;
                int backgroundBlue = bgColor & 0xFF;
                for (int y = chunk.getHeight() - 1; y >= 0; y--) {
                    for (int x = 0; x < xLimit; x += stepX) {
                        int pixel = chunk.getPixel(x, y);
                        int distance = Math.abs(((pixel >> 16) & 0xFF) - backgroundRed)
                                + Math.abs(((pixel >> 8) & 0xFF) - backgroundGreen)
                                + Math.abs((pixel & 0xFF) - backgroundBlue);
                        if (distance > 30) {
                            return y;
                        }
                    }
                }
            } catch (Throwable ignored) {

            }
            return -1;
        }

        private boolean overlapMatches(int contentTop, Bitmap chunk, int overlap) {
            final int blockWidth = 32;
            final int blockHeight = 4;
            final int tolerance = 36;
            try {
                int blocks = 0;
                int different = 0;
                int width = chunk.getWidth();
                for (int y = 0; y + blockHeight <= overlap; y += blockHeight) {
                    int outY = contentTop + y;
                    if (outY + blockHeight > output.getHeight()) {
                        break;
                    }
                    for (int x = 0; x + blockWidth <= width; x += blockWidth) {
                        blocks++;
                        if (blockDistance(chunk, x, y, output, x, outY, blockWidth, blockHeight) > tolerance) {
                            different++;
                        }
                    }
                }
                if (ScreenshotSelectionManager.get().isDebug()) {
                    android.util.Log.i("BetterHeybox", "截图诊断 重叠块比较 总数=" + blocks + " 差异=" + different);
                }
                return blocks == 0 || different * 100 <= blocks * 12;
            } catch (Throwable ignored) {
                return true;
            }
        }

        private static int blockDistance(Bitmap first, int fx, int fy, Bitmap second, int sx, int sy,
                int blockWidth, int blockHeight) {
            int firstSum = 0;
            int secondSum = 0;
            int count = 0;
            for (int y = 0; y < blockHeight; y += 2) {
                for (int x = 0; x < blockWidth; x += 2) {
                    int a = first.getPixel(fx + x, fy + y);
                    int b = second.getPixel(sx + x, sy + y);
                    firstSum += ((a >> 16) & 0xFF) + ((a >> 8) & 0xFF) + (a & 0xFF);
                    secondSum += ((b >> 16) & 0xFF) + ((b >> 8) & 0xFF) + (b & 0xFF);
                    count++;
                }
            }
            if (count == 0) {
                return 0;
            }
            return Math.abs(firstSum - secondSum) / count / 3;
        }

        private void finish() {
            if (finishing) {
                return;
            }
            finishing = true;
            android.util.Log.i("BetterHeybox", "截图诊断 网页拼接结束 drawn=" + drawn + " total=" + total
                    + " attempt=" + attempt + " 重排重试=" + restartNeeded + " 已滚动=" + savedScrollY);
            try {
                web.scrollTo(0, savedScrollY);
            } catch (Throwable ignored) {

            }
            for (int i = 0; i < scrolledAncestors.size(); i++) {
                try {
                    scrolledAncestors.get(i).scrollTo(0, ancestorScrolls.get(i));
                } catch (Throwable ignored) {

                }
            }
            if (output == null) {
                callback.onCaptured(null);
                return;
            }
            Bitmap result = output;
            try {
                if (drawn <= 0) {
                    result = null;
                } else if (drawn < output.getHeight()) {
                    result = Bitmap.createBitmap(output, 0, 0, width, drawn);
                }
            } catch (OutOfMemoryError error) {
                result = output;
            } catch (Throwable ignored) {
                result = output;
            }
            if (result != output) {
                output.recycle();
            }
            output = null;
            canvas = null;
            callback.onCaptured(result);
        }
    }

    public static Bitmap composeVertical(Context context, List<Bitmap> parts, int bgColor) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            Bitmap only = parts.get(0);
            return only == null ? null : only.copy(Bitmap.Config.ARGB_8888, false);
        }

        float density = context.getResources().getDisplayMetrics().density;
        int gap = Math.max(1, (int) (GAP_DP * density));
        int padding = Math.max(0, (int) (PADDING_DP * density));

        int contentWidth = 0;
        int contentHeight = gap * (parts.size() - 1);
        for (Bitmap part : parts) {
            if (part == null) {
                continue;
            }
            contentWidth = Math.max(contentWidth, part.getWidth());
            contentHeight += part.getHeight();
        }
        if (contentWidth <= 0 || contentHeight <= 0) {
            return null;
        }

        int outWidth = contentWidth + padding * 2;
        int outHeight = contentHeight + padding * 2;

        float scale = 1f;
        if (outHeight > MAX_OUTPUT_HEIGHT) {
            scale = MAX_OUTPUT_HEIGHT / (float) outHeight;
            outWidth = Math.max(1, (int) (outWidth * scale));
            outHeight = MAX_OUTPUT_HEIGHT;
        }

        Bitmap output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(bgColor);
        canvas.save();
        canvas.scale(scale, scale);

        int y = padding;
        for (Bitmap part : parts) {
            if (part == null || part.isRecycled()) {
                continue;
            }
            float x = padding + (contentWidth - part.getWidth()) / 2f;
            canvas.drawBitmap(part, x, y, null);
            y += part.getHeight() + gap;
        }
        canvas.restore();
        return output;
    }
}

