/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.KeyboardTheme;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.common.CoordinateUtils;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * This class controls pop up key previews. This class decides:
 * - what kind of key previews should be shown.
 * - where key previews should be placed.
 * - how key previews should be shown and dismissed.
 */
public final class KeyPreviewChoreographer {
    private static final float CIRCULAR_PREVIEW_SHADOW_PADDING_DP = 8.0f;
    private static final float CIRCULAR_PREVIEW_BACKGROUND_LIGHT_BLEND = 0.86f;
    private static final float CIRCULAR_PREVIEW_BACKGROUND_DARK_BLEND = 0.18f;

    // Free {@link KeyPreviewView} pool that can be used for key preview.
    private final ArrayDeque<KeyPreviewView> mFreeKeyPreviewViews = new ArrayDeque<>();
    // Map from {@link Key} to {@link KeyPreviewView} that is currently being displayed as key
    // preview.
    private final HashMap<Key,KeyPreviewView> mShowingKeyPreviewViews = new HashMap<>();
    private final HashMap<Key,View> mShowingKeyPreviewContentViews = new HashMap<>();
    private FrameLayout mPreviewLayer;
    private PopupWindow mPreviewLayerWindow;
    private int mPreviewLayerX;
    private int mPreviewLayerY;
    private int mPreviewLayerWidth;
    private int mPreviewLayerHeight;

    private final KeyPreviewDrawParams mParams;

    public KeyPreviewChoreographer(final KeyPreviewDrawParams params) {
        mParams = params;
    }

    public KeyPreviewView getKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.remove(key);
        if (keyPreviewView != null) {
            removeFromParent(mShowingKeyPreviewContentViews.remove(key));
            removeFromParent(keyPreviewView);
            return keyPreviewView;
        }
        keyPreviewView = mFreeKeyPreviewViews.poll();
        if (keyPreviewView != null) {
            removeFromParent(keyPreviewView);
            return keyPreviewView;
        }
        final Context context = placerView.getContext();
        keyPreviewView = new KeyPreviewView(context, null /* attrs */);
        keyPreviewView.setLayoutParams(ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        return keyPreviewView;
    }

    public boolean isShowingKeyPreview(final Key key) {
        return mShowingKeyPreviewViews.containsKey(key);
    }

    public void clear() {
        mShowingKeyPreviewViews.clear();
        mShowingKeyPreviewContentViews.clear();
        if (mPreviewLayer != null) {
            mPreviewLayer.removeAllViews();
        }
        if (mPreviewLayerWindow != null) {
            mPreviewLayerWindow.dismiss();
            mPreviewLayerWindow = null;
        }
        mPreviewLayer = null;
        mFreeKeyPreviewViews.clear();
    }

    public void dismissKeyPreview(final Key key) {
        if (key == null) {
            return;
        }
        final KeyPreviewView keyPreviewView = mShowingKeyPreviewViews.get(key);
        if (keyPreviewView == null) {
            return;
        }
        // Dismiss preview
        mShowingKeyPreviewViews.remove(key);
        removeFromParent(mShowingKeyPreviewContentViews.remove(key));
        removeFromParent(keyPreviewView);
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeKeyPreviewViews.add(keyPreviewView);
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int fullKeyboardViewWidth, final int[] keyboardOrigin,
            final ViewGroup placerView) {
        final KeyPreviewView keyPreviewView = getKeyPreviewView(key, placerView);
        final PreviewPlacement placement = placeKeyPreview(
                key, keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin);
        showKeyPreview(key, keyPreviewView, placerView, placement);
    }

    private PreviewPlacement placeKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final KeyboardIconsSet iconsSet, final KeyDrawParams drawParams,
            final int fullKeyboardViewWidth, final int[] originCoords) {
        final Colors colors = Settings.getValues().mColors;
        keyPreviewView.setPreviewBackgroundResource(getPreviewBackgroundResId(colors));
        keyPreviewView.setPreviewVisual(key, iconsSet, drawParams);
        keyPreviewView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mParams.setGeometry(keyPreviewView);
        final int previewWidth = keyPreviewView.getMeasuredWidth();
        final int previewHeight = keyPreviewView.getMeasuredHeight();
        final int keyDrawWidth = key.getDrawWidth();
        // The key preview is horizontally aligned with the center of the visible part of the
        // parent key. If it doesn't fit in this {@link KeyboardView}, it is moved inward to fit and
        // the left/right background is used if such background is specified.
        final int keyPreviewPosition;
        int previewX = key.getDrawX() - (previewWidth - keyDrawWidth) / 2 + CoordinateUtils.x(originCoords);
        if (previewX < 0) {
            previewX = 0;
            keyPreviewPosition = KeyPreviewView.POSITION_LEFT;
        } else if (previewX > fullKeyboardViewWidth - previewWidth) {
            previewX = fullKeyboardViewWidth - previewWidth;
            keyPreviewPosition = KeyPreviewView.POSITION_RIGHT;
        } else {
            keyPreviewPosition = KeyPreviewView.POSITION_MIDDLE;
        }
        // The circular LXX preview has no tail padding, so float it above the key.
        final boolean isCircularPreview = isCircularPreviewStyle(colors);
        final boolean hasPopupKeys = (key.getPopupKeys() != null);
        keyPreviewView.setPreviewBackground(hasPopupKeys, keyPreviewPosition);
        if (isCircularPreview) {
            keyPreviewView.setCircularPreviewBackgroundColor(
                    getCircularPreviewBackgroundColor(colors.get(ColorType.KEY_PREVIEW_BACKGROUND)));
        } else {
            colors.setBackground(keyPreviewView, ColorType.KEY_PREVIEW_BACKGROUND);
        }
        final int shadowPadding = isCircularPreview
                ? dpToPx(keyPreviewView, CIRCULAR_PREVIEW_SHADOW_PADDING_DP) : 0;
        final int previewY = isCircularPreview
                ? key.getY() - previewHeight - (int) (4 * keyPreviewView.getResources().getDisplayMetrics().density)
                    + CoordinateUtils.y(originCoords)
                : key.getY() - previewHeight + key.getHeight() - mParams.mPreviewOffset
                    + CoordinateUtils.y(originCoords);

        ViewLayoutUtils.placeViewAt(keyPreviewView, previewX, previewY, previewWidth, previewHeight);
        keyPreviewView.setPivotX(previewWidth / 2.0f);
        keyPreviewView.setPivotY(previewHeight);
        return new PreviewPlacement(previewX, previewY, previewWidth, previewHeight, shadowPadding);
    }

    private int getPreviewBackgroundResId(final Colors colors) {
        return isCircularPreviewStyle(colors)
                ? R.drawable.keyboard_key_feedback_circle
                : mParams.mPreviewBackgroundResId;
    }

    private static boolean isCircularPreviewStyle(final Colors colors) {
        final String themeStyle = colors.getThemeStyle();
        return KeyboardTheme.STYLE_ROUNDED.equals(themeStyle)
                || KeyboardTheme.STYLE_CIRCLE.equals(themeStyle);
    }

    void showKeyPreview(final Key key, final KeyPreviewView keyPreviewView,
            final View anchorView, final PreviewPlacement placement) {
        removeFromParent(keyPreviewView);
        keyPreviewView.setVisibility(View.VISIBLE);
        mShowingKeyPreviewViews.put(key, keyPreviewView);
        if (!ensurePreviewLayer(anchorView, placement)) {
            return;
        }
        final View popupContentView = createPopupContentView(keyPreviewView, placement);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                placement.getPopupWidth(), placement.getPopupHeight());
        params.leftMargin = placement.getPopupX() - mPreviewLayerX;
        params.topMargin = placement.getPopupY() - mPreviewLayerY;
        mPreviewLayer.addView(popupContentView, params);
        mShowingKeyPreviewContentViews.put(key, popupContentView);
    }

    private boolean ensurePreviewLayer(final View anchorView, final PreviewPlacement placement) {
        final View rootView = anchorView.getRootView();
        if (rootView == null || !anchorView.isAttachedToWindow()) {
            return false;
        }
        if (mPreviewLayer == null) {
            mPreviewLayer = new FrameLayout(anchorView.getContext());
            mPreviewLayer.setClipChildren(false);
            mPreviewLayer.setClipToPadding(false);
        }
        final int rootWidth = rootView.getWidth() > 0 ? rootView.getWidth()
                : Math.max(placement.getPopupX() + placement.getPopupWidth(), placement.mWidth);
        final int rootHeight = rootView.getHeight() > 0 ? rootView.getHeight()
                : Math.max(placement.getPopupY() + placement.getPopupHeight(), placement.mHeight);
        
        // Use a fixed top overflow (rootHeight) instead of dynamically calculating it based on the popup placement.
        // This ensures layerY and layerHeight remain constant, preventing extremely expensive PopupWindow.update() 
        // calls during fast typing that cause dropped frames and lag.
        final int topOverflow = rootHeight;
        
        final int layerX = -placement.mShadowPadding;
        final int layerY = -topOverflow - placement.mShadowPadding;
        final int layerWidth = rootWidth + placement.mShadowPadding * 2;
        final int layerHeight = rootHeight + topOverflow + placement.mShadowPadding * 2;

        try {
            if (mPreviewLayerWindow == null) {
                mPreviewLayerWindow = new PopupWindow(mPreviewLayer, layerWidth, layerHeight, false);
                mPreviewLayerWindow.setTouchable(false);
                mPreviewLayerWindow.setOutsideTouchable(false);
                mPreviewLayerWindow.setClippingEnabled(false);
                mPreviewLayerWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                mPreviewLayerX = layerX;
                mPreviewLayerY = layerY;
                mPreviewLayerWidth = layerWidth;
                mPreviewLayerHeight = layerHeight;
            }
            if (mPreviewLayerWindow.isShowing()) {
                if (layerX != mPreviewLayerX || layerY != mPreviewLayerY
                        || layerWidth != mPreviewLayerWidth || layerHeight != mPreviewLayerHeight) {
                    mPreviewLayerWindow.update(layerX, layerY, layerWidth, layerHeight, false);
                    mPreviewLayerX = layerX;
                    mPreviewLayerY = layerY;
                    mPreviewLayerWidth = layerWidth;
                    mPreviewLayerHeight = layerHeight;
                }
            } else {
                mPreviewLayerWindow.setWidth(layerWidth);
                mPreviewLayerWindow.setHeight(layerHeight);
                mPreviewLayerWindow.showAtLocation(rootView, Gravity.NO_GRAVITY, layerX, layerY);
            }
            return true;
        } catch (final RuntimeException e) {
            clear();
            return false;
        }
    }

    private static View createPopupContentView(final KeyPreviewView keyPreviewView,
            final PreviewPlacement placement) {
        if (placement.mShadowPadding <= 0) {
            return keyPreviewView;
        }
        final FrameLayout shadowContainer = new FrameLayout(keyPreviewView.getContext());
        shadowContainer.setClipChildren(false);
        shadowContainer.setClipToPadding(false);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                placement.mWidth, placement.mHeight);
        params.leftMargin = placement.mShadowPadding;
        params.topMargin = placement.mShadowPadding;
        shadowContainer.addView(keyPreviewView, params);
        return shadowContainer;
    }

    private static void removeFromParent(final View view) {
        if (view == null) {
            return;
        }
        final ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }

    private static int dpToPx(final View view, final float dp) {
        return (int)(dp * view.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int getCircularPreviewBackgroundColor(final int themeColor) {
        final int opaqueThemeColor = Color.rgb(
                Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor));
        if (isLightColor(opaqueThemeColor)) {
            return blendColors(opaqueThemeColor, Color.WHITE,
                    CIRCULAR_PREVIEW_BACKGROUND_LIGHT_BLEND);
        }
        return blendColors(opaqueThemeColor, Color.BLACK, CIRCULAR_PREVIEW_BACKGROUND_DARK_BLEND);
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

    private static final class PreviewPlacement {
        final int mX;
        final int mY;
        final int mWidth;
        final int mHeight;
        final int mShadowPadding;

        PreviewPlacement(final int x, final int y, final int width, final int height,
                final int shadowPadding) {
            mX = x;
            mY = y;
            mWidth = width;
            mHeight = height;
            mShadowPadding = shadowPadding;
        }

        int getPopupX() {
            return mX - mShadowPadding;
        }

        int getPopupY() {
            return mY - mShadowPadding;
        }

        int getPopupWidth() {
            return mWidth + mShadowPadding * 2;
        }

        int getPopupHeight() {
            return mHeight + mShadowPadding * 2;
        }
    }
}
