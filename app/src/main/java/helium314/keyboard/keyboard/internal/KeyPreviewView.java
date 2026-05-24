/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.KeyboardTheme;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.StringUtilsKt;
import helium314.keyboard.latin.settings.Settings;

import java.util.HashSet;

/** The pop up key preview view. */
// Android Studio complains about TextView, but we're not using tint or auto-size that should be the relevant differences
public class KeyPreviewView extends TextView {
    public static final int POSITION_MIDDLE = 0;
    public static final int POSITION_LEFT = 1;
    public static final int POSITION_RIGHT = 2;

    private final Rect mBackgroundPadding = new Rect();
    private static final HashSet<String> sNoScaleXTextSet = new HashSet<>();
    private int mBackgroundResourceId;
    private boolean mNeedsCircularTextCentering;

    public KeyPreviewView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyPreviewView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setGravity(Gravity.CENTER);
    }

    public void setPreviewVisual(final Key key, final KeyboardIconsSet iconsSet, final KeyDrawParams drawParams) {
        // What we show as preview should match what we show on a key top in onDraw().
        if (key.getIconName() != null) {
            mNeedsCircularTextCentering = false;
            setIncludeFontPadding(true);
            updateCircularPreviewPadding();
            setCompoundDrawables(key.getPreviewIcon(iconsSet), null, null, null);
            setText(null);
            return;
        }

        final String previewLabel = key.getPreviewLabel();
        mNeedsCircularTextCentering = isCircularPreviewStyle() && !StringUtilsKt.isEmoji(previewLabel);
        setIncludeFontPadding(!mNeedsCircularTextCentering);
        updateCircularPreviewPadding();
        setCompoundDrawables(null, null, null, null);
        setTextColor(drawParams.mPreviewTextColor);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, key.selectPreviewTextSize(drawParams)
                * Settings.getValues().mFontSizeMultiplier);
        KeyboardTypeface.applyToTextView(this, previewLabel, key.selectPreviewTypeface(drawParams));
        // TODO Should take care of temporaryShiftLabel here.
        setTextAndScaleX(previewLabel);
    }

    public void setPreviewBackgroundResource(final int backgroundResourceId) {
        if (mBackgroundResourceId == backgroundResourceId) {
            return;
        }
        setBackgroundResource(backgroundResourceId);
        mBackgroundResourceId = backgroundResourceId;
    }

    private void setTextAndScaleX(final String text) {
        setTextScaleX(1.0f);
        setText(text);
        if (sNoScaleXTextSet.contains(text)) {
            return;
        }
        if (StringUtilsKt.isEmoji(text)) {
            sNoScaleXTextSet.add(text);
            return;
        }
        // TODO: Override {@link #setBackground(Drawable)} that is supported from API 16 and
        // calculate maximum text width.
        final Drawable background = getBackground();
        if (background == null) {
            return;
        }
        background.getPadding(mBackgroundPadding);
        final int maxWidth = background.getIntrinsicWidth() - mBackgroundPadding.left
                - mBackgroundPadding.right;
        final float width = getTextWidth(text, getPaint());
        if (width <= maxWidth) {
            sNoScaleXTextSet.add(text);
            return;
        }
        setTextScaleX(maxWidth / width);
    }

    public static void clearTextCache() {
        sNoScaleXTextSet.clear();
    }

    private static float getTextWidth(final String text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0.0f;
        }
        final int len = text.length();
        final float[] widths = new float[len];
        final int count = paint.getTextWidths(text, 0, len, widths);
        float width = 0;
        for (int i = 0; i < count; i++) {
            width += widths[i];
        }
        return width;
    }

    // Background state set
    private static final int[][][] KEY_PREVIEW_BACKGROUND_STATE_TABLE = {
        { // POSITION_MIDDLE
            {},
            { R.attr.state_has_popup_keys}
        },
        { // POSITION_LEFT
            { R.attr.state_left_edge },
            { R.attr.state_left_edge, R.attr.state_has_popup_keys}
        },
        { // POSITION_RIGHT
            { R.attr.state_right_edge },
            { R.attr.state_right_edge, R.attr.state_has_popup_keys}
        }
    };
    private static final int STATE_NORMAL = 0;
    private static final int STATE_HAS_POPUPKEYS = 1;

    public void setPreviewBackground(final boolean hasPopupKeys, final int position) {
        final Drawable background = getBackground();
        if (background == null) {
            return;
        }
        final int hasPopupKeysState = hasPopupKeys ? STATE_HAS_POPUPKEYS : STATE_NORMAL;
        background.setState(KEY_PREVIEW_BACKGROUND_STATE_TABLE[position][hasPopupKeysState]);

        if (isCircularPreviewStyle()) {
            updateCircularPreviewPadding();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (isCircularPreviewStyle()) {
            final int width = getMeasuredWidth();
            final int height = getMeasuredHeight();
            final int minDiameter = (int) (56 * getResources().getDisplayMetrics().density);
            int diameter = Math.max(Math.max(width, height), minDiameter);
            setMeasuredDimension(diameter, diameter);

            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(android.view.View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            setClipToOutline(true);
        } else {
            setOutlineProvider(null);
            setClipToOutline(false);
        }
    }

    private static boolean isCircularPreviewStyle() {
        final String themeStyle = Settings.getValues().mColors.getThemeStyle();
        return KeyboardTheme.STYLE_ROUNDED.equals(themeStyle)
                || KeyboardTheme.STYLE_CIRCLE.equals(themeStyle);
    }

    private void updateCircularPreviewPadding() {
        if (!isCircularPreviewStyle()) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        final int horizontalPadding = (int) (8 * density);
        final int topPadding = (int) ((mNeedsCircularTextCentering ? 5 : 8) * density);
        final int bottomPadding = (int) ((mNeedsCircularTextCentering ? 11 : 8) * density);
        setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);
    }
}
