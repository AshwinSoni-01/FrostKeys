/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.text.TextUtils;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Compatibility wrapper for editors where visible composing text is slow or unreliable.
 *
 * <p>The outer {@link RichInputConnection} and {@code InputLogic} still behave as if normal
 * composing is available. This wrapper prevents that composing state from reaching the target app:
 * composing updates are materialized as plain committed text, and final commits replace the
 * internally tracked composing range with ordinary commit/delete calls.</p>
 */
final class InternalComposingInputConnection extends InputConnectionWrapper {
    private static final int INVALID_SELECTION = -1;

    private InputConnection mTarget;
    private InputConnectionWithBufferingWrapper mBufferedTarget;
    @NonNull
    private String mComposingText = "";
    private int mSelectionStart = INVALID_SELECTION;
    private int mSelectionEnd = INVALID_SELECTION;
    private int mComposingStart = INVALID_SELECTION;
    private int mComposingEnd = INVALID_SELECTION;

    InternalComposingInputConnection(@NonNull final InputConnection target) {
        super(target, true);
        updateTargetInternal(target);
    }

    void updateTarget(@NonNull final InputConnection target) {
        if (mTarget == target) {
            return;
        }
        updateTargetInternal(target);
        resetInternalState();
    }

    private void updateTargetInternal(@NonNull final InputConnection target) {
        mTarget = target;
        mBufferedTarget = new InputConnectionWithBufferingWrapper(target);
        setTarget(mBufferedTarget);
    }

    void flush() {
        if (mBufferedTarget != null) {
            mBufferedTarget.send();
        }
    }

    void setComposingRegionWithText(final int start, final int end,
            @Nullable final CharSequence text) {
        mComposingText = text == null ? "" : text.toString();
        mComposingStart = start;
        mComposingEnd = end;
        if (end >= 0) {
            mSelectionStart = end;
            mSelectionEnd = end;
        }
    }

    void onSelectionUpdate(final int newSelStart, final int newSelEnd) {
        mSelectionStart = newSelStart;
        mSelectionEnd = newSelEnd;
    }

    void clearInternalComposingText() {
        mComposingText = "";
        mComposingStart = INVALID_SELECTION;
        mComposingEnd = INVALID_SELECTION;
    }

    private void resetInternalState() {
        clearInternalComposingText();
        mSelectionStart = INVALID_SELECTION;
        mSelectionEnd = INVALID_SELECTION;
    }

    private int composingLength() {
        return mComposingText.length();
    }

    private boolean commitPlainText(@NonNull final CharSequence text,
            final int newCursorPosition) {
        if (text.length() == 0) {
            return true;
        }
        final boolean success = super.commitText(text, newCursorPosition);
        if (success) {
            updateSelectionAfterCommit(text.length(), newCursorPosition);
        }
        return success;
    }

    private boolean deleteBeforeCursor(final int length) {
        if (length <= 0) {
            return true;
        }
        final boolean success = super.deleteSurroundingText(length, 0);
        if (success) {
            updateSelectionAfterDelete(length, 0);
        }
        return success;
    }

    private void updateSelectionAfterCommit(final int textLength, final int newCursorPosition) {
        if (mSelectionStart < 0 || mSelectionEnd < 0 || newCursorPosition != 1) {
            mSelectionStart = INVALID_SELECTION;
            mSelectionEnd = INVALID_SELECTION;
            return;
        }
        final int selectionStart = Math.min(mSelectionStart, mSelectionEnd);
        final int newSelection = selectionStart + textLength;
        mSelectionStart = newSelection;
        mSelectionEnd = newSelection;
    }

    private void updateSelectionAfterDelete(final int beforeLength, final int afterLength) {
        if (mSelectionStart < 0 || mSelectionEnd < 0) {
            return;
        }
        if (beforeLength > 0) {
            mSelectionStart = Math.max(0, mSelectionStart - beforeLength);
        }
        if (afterLength > 0) {
            mSelectionEnd = Math.max(mSelectionStart, mSelectionEnd - afterLength);
        } else {
            mSelectionEnd = mSelectionStart;
        }
    }

    private void ensureComposingStartForCurrentCursor() {
        if (mComposingStart >= 0 || mSelectionStart < 0) {
            return;
        }
        mComposingStart = Math.max(0, mSelectionStart - composingLength());
        mComposingEnd = mSelectionStart;
    }

    private static int getCommonPrefixLength(@NonNull final CharSequence first,
            @NonNull final CharSequence second) {
        final int maxLength = Math.min(first.length(), second.length());
        int index = 0;
        while (index < maxLength && first.charAt(index) == second.charAt(index)) {
            index++;
        }
        return index;
    }

    private boolean replaceInternalComposingText(@NonNull final CharSequence text,
            final int newCursorPosition) {
        ensureComposingStartForCurrentCursor();
        final String oldText = mComposingText;
        final String newText = text.toString();
        final int commonPrefixLength = getCommonPrefixLength(oldText, newText);
        final boolean success = deleteBeforeCursor(oldText.length() - commonPrefixLength)
                && commitPlainText(newText.subSequence(commonPrefixLength, newText.length()),
                        newCursorPosition);
        if (success) {
            mComposingText = newText;
            if (mComposingStart < 0 && mSelectionStart >= 0) {
                mComposingStart = Math.max(0, mSelectionStart - newText.length());
            }
            mComposingEnd = mSelectionStart >= 0
                    ? mSelectionStart
                    : (mComposingStart >= 0 ? mComposingStart + newText.length() : INVALID_SELECTION);
        }
        return success;
    }

    @Override
    public boolean setComposingText(final CharSequence text, final int newCursorPosition) {
        final String newText = text == null ? "" : text.toString();
        final String oldText = mComposingText;
        final boolean success;
        if (TextUtils.isEmpty(oldText)) {
            ensureComposingStartForCurrentCursor();
            success = commitPlainText(newText, newCursorPosition);
        } else if (newText.startsWith(oldText)) {
            success = commitPlainText(newText.substring(oldText.length()), newCursorPosition);
        } else if (oldText.startsWith(newText)) {
            success = deleteBeforeCursor(oldText.length() - newText.length());
        } else {
            success = replaceInternalComposingText(newText, newCursorPosition);
        }
        if (success) {
            mComposingText = newText;
            if (mComposingStart < 0 && mSelectionStart >= 0) {
                mComposingStart = Math.max(0, mSelectionStart - newText.length());
            }
            mComposingEnd = mSelectionStart >= 0
                    ? mSelectionStart
                    : (mComposingStart >= 0 ? mComposingStart + newText.length() : INVALID_SELECTION);
        }
        return success;
    }

    @Override
    public boolean commitText(final CharSequence text, final int newCursorPosition) {
        final CharSequence textToCommit = text == null ? "" : text;
        final boolean success;
        if (composingLength() > 0) {
            final String textString = textToCommit.toString();
            if (textString.equals(mComposingText)) {
                clearInternalComposingText();
                return true;
            }
            if (textString.startsWith(mComposingText)) {
                success = commitPlainText(textString.substring(composingLength()), newCursorPosition);
            } else {
                success = replaceInternalComposingText(textToCommit, newCursorPosition);
            }
        } else {
            success = super.commitText(textToCommit, newCursorPosition);
            if (success) {
                updateSelectionAfterCommit(textToCommit.length(), newCursorPosition);
            }
        }
        if (success) {
            clearInternalComposingText();
        }
        return success;
    }

    @Override
    public boolean finishComposingText() {
        clearInternalComposingText();
        return true;
    }

    @Override
    public boolean setComposingRegion(final int start, final int end) {
        mComposingStart = start;
        mComposingEnd = end;
        mComposingText = "";
        return true;
    }

    @Override
    public boolean deleteSurroundingText(final int beforeLength, final int afterLength) {
        if (beforeLength > 0 && composingLength() > 0) {
            final int newLength = Math.max(0, composingLength() - beforeLength);
            mComposingText = mComposingText.substring(0, newLength);
        }
        if (afterLength > 0) {
            clearInternalComposingText();
        }
        final boolean success = super.deleteSurroundingText(beforeLength, afterLength);
        if (success) {
            updateSelectionAfterDelete(beforeLength, afterLength);
        }
        return success;
    }

    @Override
    public boolean setSelection(final int start, final int end) {
        clearInternalComposingText();
        final boolean success = super.setSelection(start, end);
        if (success) {
            mSelectionStart = start;
            mSelectionEnd = end;
        }
        return success;
    }
}
