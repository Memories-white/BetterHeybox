package com.better.heybox.liquidglass;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

final class MiuixGlassPipeline {

    private MiuixGlassPipeline() {
    }

    static final class Style {
        final float blurRadiusDp = 20f;
        final float saturation = 1.22f;
        final float brightness = 0.02f;
        final float contrast = 1.04f;
        final float noiseCoefficient = 0.0045f;
        final float highlightWidthDp = 0.8f;
        final float innerBlurRadiusDp = 2.8f;
        final float highlightAlpha = 0.06f;
        final float primaryIntensity = 0.40f;
        final float secondaryIntensity = 0.25f;
        final float cornerRadiusDp = 30f;

        final float surfaceTint = 0.17f;

        final float noiseScale = 1f;
    }

    private static final Style STYLE = new Style();

    private static Bitmap sNoise;

    static float clampBlurDp(float dp) {
        return Math.max(0f, Math.min(150f, dp));
    }

    static void process(Bitmap bitmap, float density, boolean darkMode) {
        process(bitmap, density, darkMode, STYLE);
    }

    static void process(Bitmap bitmap, float density, boolean darkMode, Style style) {
        if (bitmap == null || bitmap.isRecycled() || style == null) {
            return;
        }
        int radius = Math.max(1,
                Math.round(clampBlurDp(style.blurRadiusDp) * density));

        int scaled = Math.max(3, Math.round(radius * 0.22f));
        StackBlur.blur(bitmap, scaled);
        Canvas canvas = new Canvas(bitmap);
        applyColorControls(bitmap, darkMode, style);

        Paint tint = new Paint();
        tint.setColor(darkMode
                ? withAlpha(0xFF000000, style.surfaceTint)
                : withAlpha(0xFFFFFFFF, style.surfaceTint));
        canvas.drawRect(0f, 0f, bitmap.getWidth(), bitmap.getHeight(), tint);
        drawNoise(canvas, bitmap.getWidth(), bitmap.getHeight(), darkMode, style);
    }

    private static void applyColorControls(Bitmap bitmap, boolean darkMode, Style style) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(style.saturation);

        float translate = style.brightness * 100f * (darkMode ? 0.6f : 1f);
        float contrast = style.contrast;
        float t = (1f - contrast) * 127.5f + translate;
        cm.postConcat(new ColorMatrix(new float[]{
                contrast, 0, 0, 0, t,
                0, contrast, 0, 0, t,
                0, 0, contrast, 0, t,
                0, 0, 0, 1, 0
        }));
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, p);
    }

    private static void drawNoise(Canvas canvas, int width, int height, boolean darkMode,
                                  Style style) {
        int alpha = Math.round(style.noiseCoefficient * 255f * 8f);
        if (alpha <= 0) {
            return;
        }
        Bitmap noise = noiseBitmap();
        if (noise == null) {
            return;
        }
        Paint p = new Paint();
        p.setShader(new android.graphics.BitmapShader(
                noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        p.setAlpha(Math.min(alpha, 12));
        p.setColorFilter(new android.graphics.PorterDuffColorFilter(
                darkMode ? Color.WHITE : Color.BLACK, PorterDuff.Mode.SRC_IN));
        canvas.drawRect(0, 0, width, height, p);
    }

    private static synchronized Bitmap noiseBitmap() {
        if (sNoise != null && !sNoise.isRecycled()) {
            return sNoise;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.TRANSPARENT);
            int seed = 0x1234567;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    seed = seed * 1103515245 + 12345;
                    int v = (seed >>> 16) & 0xFF;
                    bmp.setPixel(x, y, (0xFF << 24) | (v << 16) | (v << 8) | v);
                }
            }
            sNoise = bmp;
            return bmp;
        } catch (Throwable t) {
            LiquidGlassLog.logErr("noise bitmap failed", t);
            return null;
        }
    }

    static void drawEdgeHighlight(Canvas canvas, RectF bounds, float radius,
                                  float density, boolean darkMode) {
        Style style = STYLE;
        float width = Math.max(style.highlightWidthDp * density, 0.75f);
        float innerBlur = Math.max(style.innerBlurRadiusDp * density, 2f);
        float inset = width * 0.5f;
        RectF r = new RectF(bounds);
        r.inset(inset, inset);
        float rr = Math.max(0f, radius - inset);

        Paint primary = new Paint(Paint.ANTI_ALIAS_FLAG);
        primary.setStyle(Paint.Style.STROKE);
        primary.setStrokeWidth(width);
        int light = darkMode ? 0x33FFFFFF : 0x40FFFFFF;
        primary.setShader(new LinearGradient(0f, r.top, r.width() * 0.7f, r.bottom,
                withAlpha(light, style.primaryIntensity), 0x00000000, Shader.TileMode.CLAMP));
        if (innerBlur > 0f) {
            primary.setMaskFilter(new BlurMaskFilter(innerBlur, BlurMaskFilter.Blur.NORMAL));
        }
        canvas.drawRoundRect(r, rr, rr, primary);

        Paint secondary = new Paint(Paint.ANTI_ALIAS_FLAG);
        secondary.setStyle(Paint.Style.STROKE);
        secondary.setStrokeWidth(width);
        secondary.setShader(new LinearGradient(r.width() * 0.3f, r.bottom, r.width(),
                r.top, 0x00000000,
                withAlpha(light, style.secondaryIntensity), Shader.TileMode.CLAMP));
        secondary.setMaskFilter(new BlurMaskFilter(innerBlur, BlurMaskFilter.Blur.NORMAL));
        canvas.drawRoundRect(r, rr, rr, secondary);
    }

    private static int withAlpha(int color, float factor) {
        int a = Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, factor)));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    static void featherTop(Canvas canvas, int width, float featherPx, boolean fromTop) {
        if (featherPx <= 0f) {
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        if (fromTop) {
            p.setShader(new LinearGradient(0f, 0f, 0f, featherPx,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        } else {
            p.setShader(new LinearGradient(0f, featherPx, 0f, 0f,
                    0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0f, 0f, width, featherPx, p);
    }

    static void drawFeatheredTop(Canvas canvas, RectF bounds, Bitmap backdrop,
                                 float blurScale, float featherPx, boolean darkMode) {
        if (backdrop == null || backdrop.isRecycled() || bounds.isEmpty()) {
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(bounds.width() / backdrop.getWidth(),
                bounds.height() / backdrop.getHeight());
        shader.setLocalMatrix(m);
        p.setShader(shader);
        p.setFilterBitmap(true);
        canvas.drawRect(bounds, p);

        Paint tint = new Paint();
        tint.setColor(darkMode ? 0x40000000 : 0x33FFFFFF);
        canvas.drawRect(bounds, tint);
        featherTop(canvas, (int) Math.ceil(bounds.width()), featherPx, false);
    }
}