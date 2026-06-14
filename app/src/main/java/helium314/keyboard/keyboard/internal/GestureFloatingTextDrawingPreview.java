/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;

import androidx.annotation.NonNull;

import helium314.keyboard.keyboard.KeyboardTheme;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;

/**
 * The class for single gesture preview text. The class for multiple gesture preview text will be
 * derived from it.
 */
public class GestureFloatingTextDrawingPreview extends AbstractDrawingPreview {
    private static final float PREVIEW_BACKGROUND_LIGHT_BLEND = 0.86f;
    private static final float PREVIEW_BACKGROUND_DARK_BLEND = 0.18f;
    private static final float NON_PILL_ROUND_RADIUS_DP = 12.0f;
    private static final float PREVIEW_SHADOW_PADDING_DP = 8.0f;
    private static final int LIGHT_BACKGROUND_TEXT_COLOR = 0xFF202124;
    private static final int DARK_BACKGROUND_TEXT_COLOR = 0xFFE8EAED;

    protected static final class GesturePreviewTextParams {
        public final boolean mGesturePreviewDynamic;
        public final int mGesturePreviewTextOffset;
        public final int mGesturePreviewTextHeight;
        public final float mGesturePreviewHorizontalPadding;
        public final float mGesturePreviewVerticalPadding;
        public final float mGesturePreviewRoundRadius;
        public final float mDisplayDensity;
        public final int mDisplayWidth;
        public final int mDisplayHeight;

        private final int mGesturePreviewTextSize;
        private final int mGesturePreviewTextColor;
        private final int mGesturePreviewColor;
        private final boolean mUsePillBackground;
        private final float mNonPillRoundRadius;
        private final Paint mPaint = new Paint();

        private static final char[] TEXT_HEIGHT_REFERENCE_CHAR = { 'M' };

        public GesturePreviewTextParams(final TypedArray mainKeyboardViewAttr) {
            final Colors colors = Settings.getValues().mColors;
            mGesturePreviewDynamic = Settings.getValues().mGestureFloatingPreviewDynamicEnabled;
            mGesturePreviewTextSize = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextSize, 0);
            final int gesturePreviewColor = getFloatingPreviewBackgroundColor(
                    colors.get(ColorType.KEY_PREVIEW_BACKGROUND));
            final int themeTextColor = colors.get(ColorType.KEY_TEXT);
            mGesturePreviewTextColor = getFloatingPreviewTextColor(themeTextColor,
                    gesturePreviewColor);
            mGesturePreviewTextOffset = mainKeyboardViewAttr.getDimensionPixelOffset(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewTextOffset, 0);
            mGesturePreviewColor = gesturePreviewColor;
            mGesturePreviewHorizontalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewHorizontalPadding, 0.0f);
            mGesturePreviewVerticalPadding = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewVerticalPadding, 0.0f);
            mGesturePreviewRoundRadius = mainKeyboardViewAttr.getDimension(
                    R.styleable.MainKeyboardView_gestureFloatingPreviewRoundRadius, 0.0f);
            mDisplayDensity = mainKeyboardViewAttr.getResources().getDisplayMetrics().density;
            mDisplayWidth = mainKeyboardViewAttr.getResources().getDisplayMetrics().widthPixels;
            mDisplayHeight = mainKeyboardViewAttr.getResources().getDisplayMetrics().heightPixels;
            mUsePillBackground = KeyboardTheme.STYLE_ROUNDED.equals(colors.getThemeStyle())
                    || KeyboardTheme.STYLE_CIRCLE.equals(colors.getThemeStyle());
            mNonPillRoundRadius = Math.max(mGesturePreviewRoundRadius,
                    NON_PILL_ROUND_RADIUS_DP
                            * mainKeyboardViewAttr.getResources().getDisplayMetrics().density);

            final Paint textPaint = getTextPaint();
            final Rect textRect = new Rect();
            textPaint.getTextBounds(TEXT_HEIGHT_REFERENCE_CHAR, 0, 1, textRect);
            mGesturePreviewTextHeight = textRect.height();
        }

        public Paint getTextPaint() {
            mPaint.setAntiAlias(true);
            mPaint.setTextAlign(Align.CENTER);
            mPaint.setTextSize(mGesturePreviewTextSize);
            mPaint.setTypeface(KeyboardTypeface.customTypeface());
            mPaint.setColor(mGesturePreviewTextColor);
            return mPaint;
        }

        public Paint getBackgroundPaint() {
            mPaint.setAntiAlias(true);
            mPaint.setColor(mGesturePreviewColor);
            return mPaint;
        }

        public float getBackgroundRoundRadius(final RectF rectangle) {
            return mUsePillBackground ? rectangle.height() / 2.0f : mNonPillRoundRadius;
        }
    }

    private final GesturePreviewTextParams mParams;
    private final RectF mGesturePreviewRectangle = new RectF();
    private final RectF mGesturePreviewShadowRectangle = new RectF();
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mKeyboardViewOrigin = CoordinateUtils.newInstance();
    private FloatingPreviewPopupView mPopupPreviewView;
    private PopupWindow mPopupWindow;
    private View mPopupAnchorView;
    private boolean mDrawInPopup;
    private int mPopupX;
    private int mPopupY;
    private int mPopupWidth;
    private int mPopupHeight;
    private int mPreviewTextX;
    private int mPreviewTextY;
    private SuggestedWords mSuggestedWords = SuggestedWords.getEmptyInstance();
    private final int[] mLastPointerCoords = CoordinateUtils.newInstance();

    public GestureFloatingTextDrawingPreview(final TypedArray mainKeyboardViewAttr) {
        mParams = new GesturePreviewTextParams(mainKeyboardViewAttr);
    }

    @Override
    public void setDrawingView(@NonNull final DrawingPreviewPlacerView drawingView) {
        super.setDrawingView(drawingView);
        mPopupAnchorView = drawingView;
        mPopupPreviewView = new FloatingPreviewPopupView(drawingView.getContext());
    }

    @Override
    public void setKeyboardViewGeometry(@NonNull final int[] originCoords, final int width,
            final int height) {
        super.setKeyboardViewGeometry(originCoords, width, height);
        CoordinateUtils.copy(mKeyboardViewOrigin, originCoords);
    }

    @Override
    public void onDeallocateMemory() {
        dismissFloatingPreviewPopup();
    }

    public void dismissGestureFloatingPreviewText() {
        setSuggestedWords(SuggestedWords.getEmptyInstance());
    }

    public void setSuggestedWords(@NonNull final SuggestedWords suggestedWords) {
        if (!isPreviewEnabled() || isPreviewRenderModeOff()) {
            mSuggestedWords = SuggestedWords.getEmptyInstance();
            dismissFloatingPreviewPopup();
            invalidateDrawingView();
            return;
        }
        mSuggestedWords = suggestedWords;
        updatePreviewPosition();
    }

    @Override
    public void setPreviewPosition(@NonNull final PointerTracker tracker) {
        if (!isPreviewEnabled() || isPreviewRenderModeOff()) {
            dismissFloatingPreviewPopup();
            invalidateDrawingView();
            return;
        }
        tracker.getLastCoordinates(mLastPointerCoords);
        updatePreviewPosition();
    }

    /**
     * Draws gesture preview text
     * @param canvas The canvas where preview text is drawn.
     */
    @Override
    public void drawPreview(@NonNull final Canvas canvas) {
        if (!isPreviewEnabled() || isPreviewRenderModeOff() || mSuggestedWords.isEmpty()
                || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
            return;
        }
        if (mDrawInPopup) {
            return;
        }
        drawStyledPreview(canvas, mGesturePreviewRectangle, mPreviewTextX, mPreviewTextY);
    }

    private void drawStyledPreview(@NonNull final Canvas canvas, @NonNull final RectF rectangle,
            final float textX, final float textY) {
        drawGesturePreviewShadow(canvas, rectangle);
        final float round = mParams.getBackgroundRoundRadius(rectangle);
        canvas.drawRoundRect(
                rectangle, round, round, mParams.getBackgroundPaint());
        final String text = mSuggestedWords.getWord(0);
        canvas.drawText(text, textX, textY, mParams.getTextPaint());
    }

    private void drawGesturePreviewShadow(@NonNull final Canvas canvas,
            @NonNull final RectF rectangle) {
        final float density = mParams.mDisplayDensity;
        final float round = mParams.getBackgroundRoundRadius(rectangle);
        drawGesturePreviewShadowLayer(canvas, -4.0f * density, -2.0f * density,
                4.0f * density, 5.0f * density, 0x07000000, round, rectangle);
        drawGesturePreviewShadowLayer(canvas, -2.5f * density, 1.0f * density,
                2.5f * density, 6.0f * density, 0x0D000000, round, rectangle);
        drawGesturePreviewShadowLayer(canvas, -1.0f * density, 2.0f * density,
                1.0f * density, 4.0f * density, 0x12000000, round, rectangle);
    }

    private void drawGesturePreviewShadowLayer(@NonNull final Canvas canvas, final float leftOutset,
            final float topOutset, final float rightOutset, final float bottomOutset,
            final int color, final float round, @NonNull final RectF rectangle) {
        mGesturePreviewShadowRectangle.set(
                rectangle.left + leftOutset,
                rectangle.top + topOutset,
                rectangle.right + rightOutset,
                rectangle.bottom + bottomOutset);
        mShadowPaint.setColor(color);
        canvas.drawRoundRect(mGesturePreviewShadowRectangle, round, round, mShadowPaint);
    }

    /**
     * Updates gesture preview text position based on mLastPointerCoords.
     */
    protected void updatePreviewPosition() {
        if (isPreviewRenderModeOff()) {
            dismissFloatingPreviewPopup();
            invalidateDrawingView();
            return;
        }
        if (mSuggestedWords.isEmpty() || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
            dismissFloatingPreviewPopup();
            invalidateDrawingView();
            return;
        }
        final String text = mSuggestedWords.getWord(0);

        final int textHeight = mParams.mGesturePreviewTextHeight;
        final float textWidth = mParams.getTextPaint().measureText(text);
        final float hPad = mParams.mGesturePreviewHorizontalPadding;
        final float vPad = mParams.mGesturePreviewVerticalPadding;
        final float rectWidth = textWidth + hPad * 2.0f;
        final float rectHeight = textHeight + vPad * 2.0f;

        final float rectX = mParams.mGesturePreviewDynamic ? Math.min(
                Math.max(CoordinateUtils.x(mLastPointerCoords) - rectWidth / 2.0f, 0.0f),
                mParams.mDisplayWidth - rectWidth)
            : (mParams.mDisplayWidth - rectWidth) / 2.0f;
        final float rectY = mParams.mGesturePreviewDynamic ? CoordinateUtils.y(mLastPointerCoords)
                - mParams.mGesturePreviewTextOffset - rectHeight
            : -mParams.mGesturePreviewTextOffset - rectHeight;
        mGesturePreviewRectangle.set(rectX, rectY, rectX + rectWidth, rectY + rectHeight);

        mPreviewTextX = (int)(rectX + hPad + textWidth / 2.0f);
        mPreviewTextY = (int)(rectY + vPad) + textHeight;
        final boolean wasDrawnInPopup = mDrawInPopup;
        mDrawInPopup = showOrUpdateFloatingPreviewPopup();
        // TODO: Should narrow the invalidate region.
        if (!mDrawInPopup || !wasDrawnInPopup) {
            invalidateDrawingView();
        }
    }

    private boolean showOrUpdateFloatingPreviewPopup() {
        if (!Settings.PREVIEW_RENDER_MODE_POPUP.equals(getPreviewRenderMode())) {
            dismissFloatingPreviewPopup();
            return false;
        }
        if (mPopupAnchorView == null || mPopupPreviewView == null
                || !mPopupAnchorView.isAttachedToWindow()) {
            dismissFloatingPreviewPopup();
            return false;
        }
        final View rootView = mPopupAnchorView.getRootView();
        if (rootView == null) {
            dismissFloatingPreviewPopup();
            return false;
        }

        final int shadowPadding = Math.round(PREVIEW_SHADOW_PADDING_DP * mParams.mDisplayDensity);
        final int rootWidth = rootView.getWidth() > 0 ? rootView.getWidth() : mParams.mDisplayWidth;
        final int rootHeight = rootView.getHeight() > 0 ? rootView.getHeight() : mParams.mDisplayHeight;
        final int requiredTopOverflow = Math.round(mParams.mGesturePreviewTextOffset
                + mParams.mGesturePreviewTextHeight
                + mParams.mGesturePreviewVerticalPadding * 2.0f
                + shadowPadding * 2.0f);
        final int topOverflow = Math.min(mParams.mDisplayHeight,
                Math.max(rootHeight, requiredTopOverflow));
        final int popupX = -shadowPadding;
        final int popupY = -topOverflow - shadowPadding;
        final int popupWidth = rootWidth + shadowPadding * 2;
        final int popupHeight = rootHeight + topOverflow + shadowPadding * 2;
        final int previewOffsetX = CoordinateUtils.x(mKeyboardViewOrigin) - popupX;
        final int previewOffsetY = CoordinateUtils.y(mKeyboardViewOrigin) - popupY;

        mPopupPreviewView.setPreviewGeometry(mGesturePreviewRectangle, mPreviewTextX,
                mPreviewTextY, previewOffsetX, previewOffsetY, shadowPadding);
        try {
            if (mPopupWindow == null) {
                mPopupWindow = new PopupWindow(mPopupPreviewView, popupWidth, popupHeight, false);
                mPopupWindow.setTouchable(false);
                mPopupWindow.setOutsideTouchable(false);
                mPopupWindow.setClippingEnabled(false);
                mPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                mPopupX = popupX;
                mPopupY = popupY;
                mPopupWidth = popupWidth;
                mPopupHeight = popupHeight;
            }
            if (mPopupWindow.isShowing()) {
                if (popupX != mPopupX || popupY != mPopupY
                        || popupWidth != mPopupWidth || popupHeight != mPopupHeight) {
                    mPopupWindow.update(popupX, popupY, popupWidth, popupHeight, false);
                    mPopupX = popupX;
                    mPopupY = popupY;
                    mPopupWidth = popupWidth;
                    mPopupHeight = popupHeight;
                }
            } else {
                mPopupWindow.setWidth(popupWidth);
                mPopupWindow.setHeight(popupHeight);
                mPopupWindow.showAtLocation(rootView, Gravity.NO_GRAVITY, popupX, popupY);
            }
            return true;
        } catch (final RuntimeException e) {
            dismissFloatingPreviewPopup();
            return false;
        }
    }

    private void dismissFloatingPreviewPopup() {
        mDrawInPopup = false;
        if (mPopupWindow != null) {
            mPopupWindow.dismiss();
            mPopupWindow = null;
        }
    }

    private static boolean isPreviewRenderModeOff() {
        return Settings.PREVIEW_RENDER_MODE_OFF.equals(getPreviewRenderMode());
    }

    private static String getPreviewRenderMode() {
        final String renderMode = Settings.getValues().mPreviewRenderMode;
        if (Settings.PREVIEW_RENDER_MODE_DIRECT.equals(renderMode)
                || Settings.PREVIEW_RENDER_MODE_OFF.equals(renderMode)) {
            return renderMode;
        }
        return Settings.PREVIEW_RENDER_MODE_POPUP;
    }

    private static int getFloatingPreviewBackgroundColor(final int themeColor) {
        final int opaqueThemeColor = Color.rgb(
                Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor));
        if (isLightColor(opaqueThemeColor)) {
            return blendColors(opaqueThemeColor, Color.WHITE, PREVIEW_BACKGROUND_LIGHT_BLEND);
        }
        return blendColors(opaqueThemeColor, Color.BLACK, PREVIEW_BACKGROUND_DARK_BLEND);
    }

    private static int getFloatingPreviewTextColor(final int themeTextColor,
            final int previewBackgroundColor) {
        if (isLightColor(previewBackgroundColor) && isLightColor(themeTextColor)) {
            return LIGHT_BACKGROUND_TEXT_COLOR;
        }
        if (!isLightColor(previewBackgroundColor) && !isLightColor(themeTextColor)) {
            return DARK_BACKGROUND_TEXT_COLOR;
        }
        return themeTextColor;
    }

    private static int blendColors(final int fromColor, final int toColor, final float amount) {
        final float inverse = 1.0f - amount;
        return Color.rgb(
                Math.round(Color.red(fromColor) * inverse + Color.red(toColor) * amount),
                Math.round(Color.green(fromColor) * inverse + Color.green(toColor) * amount),
                Math.round(Color.blue(fromColor) * inverse + Color.blue(toColor) * amount));
    }

    private static boolean isLightColor(final int color) {
        final int red = Color.red(color);
        final int green = Color.green(color);
        final int blue = Color.blue(color);
        final double brightness = red * red * 0.241 + green * green * 0.691 + blue * blue * 0.068;
        return brightness >= 180.0 * 180.0;
    }

    private final class FloatingPreviewPopupView extends View {
        private final RectF mPopupPreviewRectangle = new RectF();
        private final Rect mPopupPreviewDirtyBounds = new Rect();
        private float mTextX;
        private float mTextY;

        FloatingPreviewPopupView(final android.content.Context context) {
            super(context);
        }

        void setPreviewGeometry(@NonNull final RectF previewRectangle, final float textX,
                final float textY, final int previewOffsetX, final int previewOffsetY,
                final int dirtyPadding) {
            invalidatePreviewBounds();
            mPopupPreviewRectangle.set(
                    previewRectangle.left + previewOffsetX,
                    previewRectangle.top + previewOffsetY,
                    previewRectangle.right + previewOffsetX,
                    previewRectangle.bottom + previewOffsetY);
            mTextX = textX + previewOffsetX;
            mTextY = textY + previewOffsetY;
            mPopupPreviewDirtyBounds.set(
                    (int)Math.floor(mPopupPreviewRectangle.left) - dirtyPadding,
                    (int)Math.floor(mPopupPreviewRectangle.top) - dirtyPadding,
                    (int)Math.ceil(mPopupPreviewRectangle.right) + dirtyPadding,
                    (int)Math.ceil(mPopupPreviewRectangle.bottom) + dirtyPadding);
            invalidatePreviewBounds();
        }

        private void invalidatePreviewBounds() {
            if (mPopupPreviewDirtyBounds.isEmpty()) {
                return;
            }
            invalidate(mPopupPreviewDirtyBounds.left, mPopupPreviewDirtyBounds.top,
                    mPopupPreviewDirtyBounds.right, mPopupPreviewDirtyBounds.bottom);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            if (mSuggestedWords.isEmpty() || TextUtils.isEmpty(mSuggestedWords.getWord(0))) {
                return;
            }
            drawStyledPreview(canvas, mPopupPreviewRectangle, mTextX, mTextY);
        }
    }
}
