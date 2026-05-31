/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

import helium314.keyboard.latin.common.CoordinateUtils;

import java.util.concurrent.CopyOnWriteArrayList;

public final class DrawingPreviewPlacerView extends RelativeLayout {
    private final int[] mKeyboardViewOrigin = CoordinateUtils.newInstance();

    private final CopyOnWriteArrayList<AbstractDrawingPreview> mPreviews = new CopyOnWriteArrayList<>();

    public DrawingPreviewPlacerView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        final Paint layerPaint = new Paint();
        layerPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        setLayerType(LAYER_TYPE_HARDWARE, layerPaint);
    }

    public void addPreview(final AbstractDrawingPreview preview) {
        if (!mPreviews.contains(preview)) {
            mPreviews.add(preview);
        }
    }

    public void setKeyboardViewGeometry(final int[] originCoords, final int width,
            final int height) {
        CoordinateUtils.copy(mKeyboardViewOrigin, originCoords);
        for (AbstractDrawingPreview preview : mPreviews) {
            preview.setKeyboardViewGeometry(originCoords, width, height);
        }
    }

    public void deallocateMemory() {
        for (AbstractDrawingPreview preview : mPreviews) {
            preview.onDeallocateMemory();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        deallocateMemory();
    }

    @Override
    public void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final int originX = CoordinateUtils.x(mKeyboardViewOrigin);
        final int originY = CoordinateUtils.y(mKeyboardViewOrigin);
        canvas.translate(originX, originY);
        for (AbstractDrawingPreview preview : mPreviews) {
            preview.drawPreview(canvas);
        }
        canvas.translate(-originX, -originY);
    }
}
