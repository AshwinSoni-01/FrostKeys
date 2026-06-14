/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Trace;
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
    private static final float PREVIEW_LAYER_TOP_OVERFLOW_DP = 128.0f;

    // Recycler holder to pair each key preview view with its shadow container parent.
    private static final class KeyPreviewHolder {
        final FrameLayout shadowContainer;
        final KeyPreviewView keyPreviewView;

        KeyPreviewHolder(final Context context) {
            shadowContainer = new FrameLayout(context);
            shadowContainer.setClipChildren(false);
            shadowContainer.setClipToPadding(false);

            keyPreviewView = new KeyPreviewView(context, null /* attrs */);
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(0, 0);
            shadowContainer.addView(keyPreviewView, params);

            shadowContainer.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
        }
    }

    private final ArrayDeque<KeyPreviewHolder> mFreeHolders = new ArrayDeque<>();
    private final HashMap<Key, KeyPreviewHolder> mShowingHolders = new HashMap<>();
    private final ArrayDeque<KeyPreviewView> mFreeDirectKeyPreviewViews = new ArrayDeque<>();
    private final HashMap<Key, KeyPreviewView> mShowingDirectKeyPreviewViews = new HashMap<>();

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

    public boolean isShowingKeyPreview(final Key key) {
        return mShowingHolders.containsKey(key) || mShowingDirectKeyPreviewViews.containsKey(key);
    }

    public void clear() {
        clearPopupLayer();
        clearDirectPreviews();
    }

    public void dismissKeyPreview(final Key key) {
        if (key == null) {
            return;
        }
        dismissPopupKeyPreview(key);
        dismissDirectKeyPreview(key);
    }

    private boolean dismissPopupKeyPreview(final Key key) {
        final KeyPreviewHolder holder = mShowingHolders.remove(key);
        if (holder == null) {
            return false;
        }
        holder.shadowContainer.setVisibility(View.INVISIBLE);
        mFreeHolders.add(holder);
        return true;
    }

    private boolean dismissDirectKeyPreview(final Key key) {
        final KeyPreviewView keyPreviewView = mShowingDirectKeyPreviewViews.remove(key);
        if (keyPreviewView == null) {
            return false;
        }
        keyPreviewView.setTag(null);
        keyPreviewView.setVisibility(View.INVISIBLE);
        mFreeDirectKeyPreviewViews.add(keyPreviewView);
        return true;
    }

    public void placeAndShowKeyPreview(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int fullKeyboardViewWidth, final int[] keyboardOrigin,
            final ViewGroup placerView, final View anchorView) {
        Trace.beginSection("KeyPreview#placeAndShow");
        try {
            final String renderMode = getPreviewRenderMode();
            if (Settings.PREVIEW_RENDER_MODE_OFF.equals(renderMode)) {
                clear();
                return;
            }
            if (Settings.PREVIEW_RENDER_MODE_DIRECT.equals(renderMode)) {
                clearPopupLayer();
                final KeyPreviewView keyPreviewView = getDirectKeyPreviewView(key, placerView);
                placeKeyPreview(key, keyPreviewView, iconsSet, drawParams,
                        fullKeyboardViewWidth, keyboardOrigin);
                showDirectKeyPreview(key, keyPreviewView);
                return;
            }

            clearDirectPreviews();
            KeyPreviewHolder holder = mShowingHolders.remove(key);
            if (holder != null) {
                holder.shadowContainer.setVisibility(View.INVISIBLE);
                mFreeHolders.add(holder);
            }
            holder = mFreeHolders.poll();
            if (holder == null) {
                holder = createNewHolder(placerView.getContext());
            }
            final PreviewPlacement placement = placeKeyPreview(
                    key, holder.keyPreviewView, iconsSet, drawParams, fullKeyboardViewWidth, keyboardOrigin);
            showKeyPreview(key, holder, anchorView, placement);
        } finally {
            Trace.endSection();
        }
    }

    private KeyPreviewView getDirectKeyPreviewView(final Key key, final ViewGroup placerView) {
        KeyPreviewView keyPreviewView = mShowingDirectKeyPreviewViews.remove(key);
        if (keyPreviewView == null) {
            keyPreviewView = mFreeDirectKeyPreviewViews.poll();
        }
        if (keyPreviewView == null) {
            keyPreviewView = new KeyPreviewView(placerView.getContext(), null /* attrs */);
            keyPreviewView.setLayoutParams(ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        }
        final ViewParent parent = keyPreviewView.getParent();
        if (parent != placerView) {
            removeFromParent(keyPreviewView);
            placerView.addView(keyPreviewView, ViewLayoutUtils.newLayoutParam(placerView, 0, 0));
        }
        return keyPreviewView;
    }

    private void showDirectKeyPreview(final Key key, final KeyPreviewView keyPreviewView) {
        keyPreviewView.setVisibility(View.VISIBLE);
        mShowingDirectKeyPreviewViews.put(key, keyPreviewView);
    }

    private KeyPreviewHolder createNewHolder(final Context context) {
        final KeyPreviewHolder holder = new KeyPreviewHolder(context);
        if (mPreviewLayer != null) {
            mPreviewLayer.addView(holder.shadowContainer);
        }
        return holder;
    }

    public void warmUpPreviewLayer(final View anchorView) {
        if (!Settings.PREVIEW_RENDER_MODE_POPUP.equals(getPreviewRenderMode())) {
            clearPopupLayer();
            return;
        }
        Trace.beginSection("KeyPreview#warmLayer");
        try {
            final View rootView = getUsableRootView(anchorView);
            if (rootView == null || rootView.getWidth() <= 0 || rootView.getHeight() <= 0) {
                return;
            }
            final int shadowPadding = getStableLayerShadowPadding(anchorView);
            final int topOverflow = dpToPx(anchorView, PREVIEW_LAYER_TOP_OVERFLOW_DP);
            showOrUpdatePreviewLayer(rootView,
                    -shadowPadding,
                    -topOverflow - shadowPadding,
                    rootView.getWidth() + shadowPadding * 2,
                    rootView.getHeight() + topOverflow + shadowPadding * 2);
        } catch (final RuntimeException e) {
            clear();
        } finally {
            Trace.endSection();
        }
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

    void showKeyPreview(final Key key, final KeyPreviewHolder holder,
            final View anchorView, final PreviewPlacement placement) {
        if (!ensurePreviewLayer(anchorView, placement)) {
            holder.shadowContainer.setVisibility(View.INVISIBLE);
            mFreeHolders.add(holder);
            return;
        }

        if (holder.shadowContainer.getParent() == null) {
            mPreviewLayer.addView(holder.shadowContainer);
        }

        // Apply placement geometry to the KeyPreviewView inside the container
        final FrameLayout.LayoutParams innerParams = (FrameLayout.LayoutParams) holder.keyPreviewView.getLayoutParams();
        if (innerParams.width != placement.mWidth || innerParams.height != placement.mHeight
                || innerParams.leftMargin != placement.mShadowPadding || innerParams.topMargin != placement.mShadowPadding) {
            innerParams.width = placement.mWidth;
            innerParams.height = placement.mHeight;
            innerParams.leftMargin = placement.mShadowPadding;
            innerParams.topMargin = placement.mShadowPadding;
            holder.keyPreviewView.setLayoutParams(innerParams);
        }

        // Adjust the shadow container dimensions to account for shadow padding
        final FrameLayout.LayoutParams outerParams = (FrameLayout.LayoutParams) holder.shadowContainer.getLayoutParams();
        final int popupWidth = placement.getPopupWidth();
        final int popupHeight = placement.getPopupHeight();
        if (outerParams.width != popupWidth || outerParams.height != popupHeight) {
            outerParams.width = popupWidth;
            outerParams.height = popupHeight;
            holder.shadowContainer.setLayoutParams(outerParams);
        }

        // Fast GPU translation position update without triggering layout passes
        holder.shadowContainer.setTranslationX(placement.getPopupX() - mPreviewLayerX);
        holder.shadowContainer.setTranslationY(placement.getPopupY() - mPreviewLayerY);

        holder.shadowContainer.setVisibility(View.VISIBLE);
        mShowingHolders.put(key, holder);
    }

    private boolean ensurePreviewLayer(final View anchorView, final PreviewPlacement placement) {
        Trace.beginSection("KeyPreview#ensureLayer");
        try {
            final View rootView = getUsableRootView(anchorView);
            if (rootView == null) {
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

            final int topOverflow = Math.max(dpToPx(anchorView, PREVIEW_LAYER_TOP_OVERFLOW_DP),
                    Math.max(0, -placement.getPopupY()));
            final int layerShadowPadding = Math.max(placement.mShadowPadding,
                    getStableLayerShadowPadding(anchorView));

            final int layerX = -layerShadowPadding;
            final int layerY = -topOverflow - layerShadowPadding;
            final int layerWidth = rootWidth + layerShadowPadding * 2;
            final int layerHeight = rootHeight + topOverflow + layerShadowPadding * 2;

            try {
                showOrUpdatePreviewLayer(rootView, layerX, layerY, layerWidth, layerHeight);
                return true;
            } catch (final RuntimeException e) {
                clear();
                return false;
            }
        } finally {
            Trace.endSection();
        }
    }

    private View getUsableRootView(final View anchorView) {
        if (anchorView == null || !anchorView.isAttachedToWindow()) {
            return null;
        }
        return anchorView.getRootView();
    }

    private int getStableLayerShadowPadding(final View anchorView) {
        return dpToPx(anchorView, CIRCULAR_PREVIEW_SHADOW_PADDING_DP);
    }

    private void clearPopupLayer() {
        mShowingHolders.clear();
        mFreeHolders.clear();
        if (mPreviewLayer != null) {
            mPreviewLayer.removeAllViews();
        }
        if (mPreviewLayerWindow != null) {
            mPreviewLayerWindow.dismiss();
            mPreviewLayerWindow = null;
        }
        mPreviewLayer = null;
    }

    private void clearDirectPreviews() {
        for (final KeyPreviewView keyPreviewView : mShowingDirectKeyPreviewViews.values()) {
            removeFromParent(keyPreviewView);
        }
        for (final KeyPreviewView keyPreviewView : mFreeDirectKeyPreviewViews) {
            removeFromParent(keyPreviewView);
        }
        mShowingDirectKeyPreviewViews.clear();
        mFreeDirectKeyPreviewViews.clear();
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

    private static String getPreviewRenderMode() {
        final String renderMode = Settings.getValues().mPreviewRenderMode;
        if (Settings.PREVIEW_RENDER_MODE_DIRECT.equals(renderMode)
                || Settings.PREVIEW_RENDER_MODE_OFF.equals(renderMode)) {
            return renderMode;
        }
        return Settings.PREVIEW_RENDER_MODE_POPUP;
    }

    private void showOrUpdatePreviewLayer(final View rootView, final int layerX, final int layerY,
            final int layerWidth, final int layerHeight) {
        if (mPreviewLayer == null) {
            mPreviewLayer = new FrameLayout(rootView.getContext());
            mPreviewLayer.setClipChildren(false);
            mPreviewLayer.setClipToPadding(false);
        }
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
                Trace.beginSection("KeyPreview#updateLayerWindow");
                try {
                    mPreviewLayerWindow.update(layerX, layerY, layerWidth, layerHeight, false);
                } finally {
                    Trace.endSection();
                }
                mPreviewLayerX = layerX;
                mPreviewLayerY = layerY;
                mPreviewLayerWidth = layerWidth;
                mPreviewLayerHeight = layerHeight;
            }
        } else {
            Trace.beginSection("KeyPreview#showLayerWindow");
            try {
                mPreviewLayerWindow.setWidth(layerWidth);
                mPreviewLayerWindow.setHeight(layerHeight);
                mPreviewLayerWindow.showAtLocation(rootView, Gravity.NO_GRAVITY, layerX, layerY);
            } finally {
                Trace.endSection();
            }
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
