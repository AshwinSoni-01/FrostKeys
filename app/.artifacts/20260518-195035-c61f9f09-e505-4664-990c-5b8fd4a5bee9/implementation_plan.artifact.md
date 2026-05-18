# Fix Emoji Search UI positioning and "jump" issue

The Emoji Search interface in KBoard sometimes obscures behind the keyboard or jumps to the bottom of the screen. This is primarily caused by a race condition during keyboard transitions (e.g., orientation changes) where the keyboard height briefly becomes 0, causing `LatinIME` to close the "gap" and `EmojiSearchActivity` to detect the IME as closed.

## Proposed Changes

### [LatinIME] (file:///D:/KBoard/app/src/main/java/helium314/keyboard/latin/LatinIME.java)

- Add a `mLastKeyboardHeight` field to cache the height of the keyboard (including the suggestion strip and persistent emoji row).
- Update `onComputeInsets` to use `mLastKeyboardHeight` as a fallback when `isEmojiSearchActive` is true but the keyboard view is not yet measured.
- Ensure `mLastKeyboardHeight` is updated whenever a valid keyboard height is calculated.
- Fix `onStartCommand` to reset `isEmojiSearchActive` more reliably.

```java
    private int mLastKeyboardHeight = 0;

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (mInputView == null) return;

        View visibleKeyboardView = mKeyboardSwitcher.getWrapperView();
        // ... (find visibleKeyboardView)

        final int inputHeight = mInputView.getHeight();
        final int stripHeight = mKeyboardSwitcher.isShowingStripContainer() ? mKeyboardSwitcher.getStripContainer().getHeight() : 0;
        final int persistentEmojiRowHeight = mKeyboardSwitcher.isShowingPersistentEmojiRow() ? mKeyboardSwitcher.getPersistentEmojiRowHeight() : 0;
        final int emojiSearchHeight = getEmojiSearchActivityHeight();

        int keyboardHeight = 0;
        if (visibleKeyboardView != null && visibleKeyboardView.getHeight() > 0) {
            keyboardHeight = visibleKeyboardView.getHeight();
        } else if (isEmojiSearchActive || emojiSearchHeight > 0) {
            keyboardHeight = mLastKeyboardHeight - stripHeight - (isEmojiSearchActive || emojiSearchHeight > 0 ? 0 : persistentEmojiRowHeight);
            if (keyboardHeight < 0) keyboardHeight = 0;
        }

        if (keyboardHeight == 0 && visibleKeyboardView == null) return;
        // ...
        mLastKeyboardHeight = keyboardHeight + stripHeight + effectivePersistentEmojiRowHeight;
    }
```

### [EmojiSearchActivity] (file:///D:/KBoard/app/src/main/java/helium314/keyboard/keyboard/emoji/EmojiSearchActivity.kt)

- Increase the `closer` delay from 200ms to 1000ms. This provides a more generous buffer for the keyboard to re-initialize during orientation changes or other transitions.
- Ensure that the height reported to the IME is stable.

```kotlin
    private val closer = Runnable {
        if (!imeVisible) {
            Log.d(TAG, "IME closed")
            imeClosed = true
            cancel()
        }
    }

    // ... in onGloballyPositioned
                            if (imeOpened && !imeVisible) {
                                Handler(this@EmojiSearchActivity.mainLooper).postDelayed(closer, 1000)
                            }
```

## Verification Plan

### Automated Tests
- Since this involves complex IME-Activity interactions and UI positioning, automated unit tests are difficult. I will rely on manual verification.

### Manual Verification
1.  **Basic Positioning**: Launch Emoji Search. Verify it sits correctly above the keyboard.
2.  **Orientation Change**: Change orientation while Emoji Search is open. Verify it stays above the keyboard and doesn't "jump" to the bottom or close unexpectedly.
3.  **Keyboard Height Changes**: Change keyboard height in settings (if possible) or switch between different keyboard types (e.g., symbols). Verify Emoji Search adjusts its position.
4.  **Closing**: Close the keyboard (back button). Verify Emoji Search closes.
5.  **Selection**: Select an emoji. Verify it's inserted and the keyboard returns to its normal state.
