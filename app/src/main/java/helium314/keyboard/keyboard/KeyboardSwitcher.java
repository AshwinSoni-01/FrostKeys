/*
 * Copyright (C) 2008 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Trace;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.graphics.ColorUtils;

import helium314.keyboard.event.Event;
import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.KeyboardLayoutSet.KeyboardLayoutSetException;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.keyboard.clipboard.ClipboardHistoryView;
import helium314.keyboard.keyboard.emoji.EmojiPalettesView;
import helium314.keyboard.keyboard.internal.AiWritingToolsView;
import helium314.keyboard.keyboard.internal.KeyboardState;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.latin.InputView;
import helium314.keyboard.latin.KeyboardWrapperView;
import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.suggestions.SuggestionStripView;
import helium314.keyboard.latin.utils.CapsModeUtils;
import helium314.keyboard.latin.utils.FoldableUtils;
import helium314.keyboard.latin.utils.KtxKt;
import helium314.keyboard.latin.utils.LanguageOnSpacebarUtils;
import helium314.keyboard.latin.utils.Log;
import helium314.keyboard.latin.utils.RecapitalizeMode;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ScriptUtils;
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional;
import helium314.keyboard.latin.utils.ToolbarMode;

public final class KeyboardSwitcher implements KeyboardState.SwitchActions {
    private static final String TAG = KeyboardSwitcher.class.getSimpleName();
    private static final int RESIZE_MODE_FROSTED_TINT_ALPHA = Math.round(255 * 0.90f);
    private static final float RESIZE_MODE_FROSTED_WASH_AMOUNT = 0.50f;

    private InputView mCurrentInputView;
    private KeyboardWrapperView mKeyboardViewWrapper;
    private View mMainKeyboardFrame;
    private MainKeyboardView mKeyboardView;
    private EmojiPalettesView mEmojiPalettesView;
    private KlipyPalettesView mKlipyPalettesView;
    private View mEmojiTabStripView;
    private LinearLayout mClipboardStripView;
    private HorizontalScrollView mClipboardStripScrollView;
    private SuggestionStripView mSuggestionStripView;
    private FrameLayout mStripContainer;
    private ClipboardHistoryView mClipboardHistoryView;
    private AiWritingToolsView mAiWritingToolsView;
    private AccessPointMenuView mAccessPointMenuView;
    private TextView mFakeToastView;
    private HorizontalScrollView mPersistentEmojiRowScroll;
    private LinearLayout mPersistentEmojiRowContainer;
    private helium314.keyboard.keyboard.resize.KeyboardResizeOverlayView mKeyboardResizeOverlay;
    private boolean mResizeModeActive;
    private boolean mResizeOverlayPinnedToPreview;
    private int mResizeOverlayPinnedPreviewHeight;
    private boolean mResizeOverlayPinnedAnchorBottom = true;
    // These anchors are overlay-local so IME root relayouts cannot move them.
    private int mResizeOverlayAnchorTopY = -1;
    private int mResizeOverlayAnchorBottomY = -1;
    private boolean mResizeOverlayWindowFullscreen;
    private boolean mResizeOverlayBlurSuppressed;
    private boolean mResizeOverlayBlurWasEnabled;
    private boolean mResizeModeFrostedTintApplied;
    private final ViewOutlineProvider mResizeOverlayOutlineProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (mResizeOverlayAnchorTopY < 0 || mResizeOverlayAnchorBottomY <= mResizeOverlayAnchorTopY) {
                outline.setRect(0, 0, view.getWidth(), view.getHeight());
                return;
            }
            final int top = 0;
            final int bottom = view.getHeight();
            final float radius = Settings.getInstance().getCurrent().mKeyboardCornerRadiusDp
                    * view.getResources().getDisplayMetrics().density;
            outline.setRoundRect(0, top, view.getWidth(), bottom, radius);
        }
    };
    private LatinIME mLatinIME;
    private RichInputMethodManager mRichImm;
    private boolean mIsHardwareAcceleratedDrawingEnabled;
    private java.util.List<String> mCachedPersistentEmojis = null;
    private java.util.List<String> mCurrentlyDisplayedPersistentEmojis = null;

    public void clearCachedPersistentEmojis() {
        mCachedPersistentEmojis = null;
        mCurrentlyDisplayedPersistentEmojis = null;
    }

    private KeyboardState mState;

    private KeyboardLayoutSet mKeyboardLayoutSet;

    private KeyboardTheme mKeyboardTheme;
    private Context mThemeContext;
    private int mCurrentUiMode;
    private int mCurrentOrientation;
    private int mCurrentDpi;
    private boolean mThemeNeedsReload;
    private View mCurrentAnimatingPanel = null;
    private android.view.ViewPropertyAnimator mRunningAnimator = null;

    @SuppressLint("StaticFieldLeak") // this is a keyboard, we want to keep it alive in background
    private static final KeyboardSwitcher sInstance = new KeyboardSwitcher();

    public static KeyboardSwitcher getInstance() {
        return sInstance;
    }

    private KeyboardSwitcher() {
        // Intentional empty constructor for singleton.
    }

    public static void init(final LatinIME latinIme) {
        sInstance.initInternal(latinIme);
    }

    private void initInternal(final LatinIME latinIme) {
        mLatinIME = latinIme;
        mRichImm = RichInputMethodManager.getInstance();
        mState = new KeyboardState(this);
        mIsHardwareAcceleratedDrawingEnabled = mLatinIME.enableHardwareAcceleration();
    }

    public void updateKeyboardTheme(@NonNull Context displayContext) {
        final boolean themeUpdated = updateKeyboardThemeAndContextThemeWrapper(
                displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        if (themeUpdated) {
            Settings settings = Settings.getInstance();
            settings.loadSettings(displayContext, settings.getCurrent().mLocale,
                    settings.getCurrent().mInputAttributes);
            if (mKeyboardView != null)
                mLatinIME.setInputView(onCreateInputView(displayContext, mIsHardwareAcceleratedDrawingEnabled));
        } else if (mCurrentInputView != null
                && mLatinIME.hasSuggestionStripView() == (Settings.getValues().mToolbarMode == ToolbarMode.HIDDEN
                        || mLatinIME.isEmojiSearch())) {
            mLatinIME.updateSuggestionStripView(mCurrentInputView);
        }
    }

    private boolean updateKeyboardThemeAndContextThemeWrapper(final Context context,
            final KeyboardTheme keyboardTheme) {
        final Resources res = context.getResources();
        if (mThemeNeedsReload
                || mThemeContext == null
                || !keyboardTheme.equals(mKeyboardTheme)
                || mCurrentDpi != res.getDisplayMetrics().densityDpi
                || mCurrentOrientation != res.getConfiguration().orientation
                || (mCurrentUiMode & Configuration.UI_MODE_NIGHT_MASK) != (res.getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                || !mThemeContext.getResources().equals(res)
                || Settings.getValues().mColors.haveColorsChanged(context)) {
            mThemeNeedsReload = false;
            mKeyboardTheme = keyboardTheme;
            mThemeContext = new ContextThemeWrapper(context, keyboardTheme.mStyleId);
            mCurrentUiMode = res.getConfiguration().uiMode;
            mCurrentOrientation = res.getConfiguration().orientation;
            mCurrentDpi = res.getDisplayMetrics().densityDpi;
            KeyboardLayoutSet.onKeyboardThemeChanged();
            return true;
        }
        return false;
    }

    public void loadKeyboard(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState,
            KeyboardLayoutSet.InternalAction internalAction) {
        clearCachedPersistentEmojis();
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(
                mThemeContext, editorInfo);
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        final RichInputMethodSubtype currentSubtype = mRichImm.getCurrentSubtype();
        final int keyboardHeight = ResourceUtils.getKeyboardHeightForLocale(
                ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues),
                currentSubtype.getLocale());
        final boolean oneHandedModeEnabled = settingsValues.mOneHandedModeEnabled;
        mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(currentSubtype)
                .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(oneHandedModeEnabled)
                .setInternalAction(internalAction)
                .build();
        try {
            mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, oneHandedModeEnabled);
        } catch (KeyboardLayoutSetException e) {
            Log.e(TAG, "loading keyboard failed: " + e.mKeyboardId, e.getCause());
            try {
                final InputMethodSubtype defaults = SubtypeUtilsAdditional.INSTANCE
                        .createDefaultSubtype(mRichImm.getCurrentSubtypeLocale());
                mKeyboardLayoutSet = builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
                        .setSubtype(RichInputMethodSubtype.Companion.get(defaults))
                        .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                        .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                        .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                        .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                        .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                        .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                        .setOneHandedModeEnabled(oneHandedModeEnabled)
                        .build();
                mState.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState, oneHandedModeEnabled);
                showToast("error loading the keyboard, falling back to defaults", false);
            } catch (KeyboardLayoutSetException e2) {
                Log.e(TAG, "even fallback to defaults failed: " + e2.mKeyboardId, e2.getCause());
            }
        }
    }

    public void saveKeyboardState() {
        if (getKeyboard() != null || isShowingEmojiPalettes() || isShowingClipboardHistory()) {
            mState.onSaveKeyboardState();
        }
    }

    public void onHideWindow() {
        exitResizeMode();
        clearTransitions();
        if (mKeyboardView != null) {
            mKeyboardView.onHideWindow();
        }
    }

    private void clearTransitions() {
        if (mRunningAnimator != null) {
            mRunningAnimator.cancel();
            mRunningAnimator = null;
        }
        if (mCurrentAnimatingPanel != null) {
            mCurrentAnimatingPanel.setAlpha(1f);
            mCurrentAnimatingPanel = null;
        }
        if (mKeyboardView != null)
            mKeyboardView.setAlpha(1f);
        if (mEmojiPalettesView != null)
            mEmojiPalettesView.setAlpha(1f);
        if (mClipboardHistoryView != null)
            mClipboardHistoryView.setAlpha(1f);
        if (mAiWritingToolsView != null)
            mAiWritingToolsView.setAlpha(1f);
        if (mKlipyPalettesView != null)
            mKlipyPalettesView.setAlpha(1f);
        if (mAccessPointMenuView != null)
            mAccessPointMenuView.setAlpha(1f);
    }

    private void transitionToPanel(final View targetPanel, final Runnable action) {
        if (mKeyboardView == null || targetPanel == null) {
            action.run();
            return;
        }
        if (mRunningAnimator != null) {
            mRunningAnimator.cancel();
            mRunningAnimator = null;
        }
        if (mCurrentAnimatingPanel != null) {
            mCurrentAnimatingPanel.setAlpha(1f);
            mCurrentAnimatingPanel = null;
        }
        action.run();
        targetPanel.setAlpha(0f);
        mCurrentAnimatingPanel = targetPanel;
        mRunningAnimator = targetPanel.animate()
                .alpha(1f)
                .setDuration(350)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        targetPanel.setAlpha(1f);
                        mCurrentAnimatingPanel = null;
                        mRunningAnimator = null;
                    }
                });
        mRunningAnimator.start();
    }

    private void setKeyboard(final int keyboardId, @NonNull final KeyboardSwitchState toggleState) {
        // with a hardware keyboard we might get here without ever calling
        // onCreateInputView, so don't crash
        if (mKeyboardView == null)
            return;

        final View currentPanel = getVisibleKeyboardView();
        final boolean animate = currentPanel != mKeyboardView;

        final Runnable action = new Runnable() {
            @Override
            public void run() {
                Trace.beginSection("KeyboardSwitcher#setKeyboard");
                try {
                    // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
                    final SettingsValues currentSettingsValues = Settings.getValues();
                    // TODO: pass this object to setKeyboard instead of getting the current values.
                    final MainKeyboardView keyboardView = mKeyboardView;
                    final Keyboard oldKeyboard = keyboardView.getKeyboard();
                    final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(keyboardId);
                    if (trySetKeyboardForFastCaseSwitch(keyboardView, oldKeyboard, newKeyboard,
                            currentSettingsValues, toggleState, animate)) {
                        return;
                    }
                    setMainKeyboardFrame(currentSettingsValues, toggleState);
                    keyboardView.setKeyboard(newKeyboard);
                    mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
                    final boolean searchPanelActive = (mKlipyPalettesView != null && mKlipyPalettesView.isSearchMode())
                            || (mEmojiPalettesView != null && mEmojiPalettesView.isSearchMode());
                    setKeyboardPanelOffsets(searchPanelActive
                            || (newKeyboard.mId.mElementId >= KeyboardId.ELEMENT_EMOJI_RECENTS
                                    && newKeyboard.mId.mElementId != KeyboardId.ELEMENT_NUMPAD));
                    keyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn);
                    keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
                    final boolean subtypeChanged = (oldKeyboard == null)
                            || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
                    final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils
                            .getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
                    final boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
                    keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged, languageOnSpacebarFormatType,
                            hasMultipleEnabledIMEsOrSubtypes);

                    if (currentSettingsValues.needsToLookupSuggestions()
                            && (currentSettingsValues.mInlineEmojiSearch || currentSettingsValues.mSuggestEmojis)) {
                        EmojiParserKt.loadEmojiDefaultVersionsAndPopupSpecs(mThemeContext);
                    }
                    updatePersistentEmojiRow();
                    if (mCurrentInputView != null) {
                        mCurrentInputView.requestLayout();
                    }
                } finally {
                    Trace.endSection();
                }
            }
        };

        if (animate) {
            transitionToPanel(mKeyboardView, action);
        } else {
            action.run();
        }
    }

    private boolean trySetKeyboardForFastCaseSwitch(@NonNull final MainKeyboardView keyboardView,
            @Nullable final Keyboard oldKeyboard, @NonNull final Keyboard newKeyboard,
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState, final boolean animate) {
        if (!canUseFastCaseSwitch(oldKeyboard, newKeyboard, toggleState, animate)) {
            return false;
        }
        Trace.beginSection("KeyboardSwitcher#fastCaseSwitch");
        try {
            keyboardView.setKeyboardForCaseSwitch(newKeyboard);
            keyboardView.setKeyPreviewPopupEnabled(settingsValues.mKeyPreviewPopupOn);
            keyboardView.updateShortcutKey(mRichImm.isShortcutImeReady());
            final boolean subtypeChanged = oldKeyboard == null
                    || !newKeyboard.mId.mSubtype.equals(oldKeyboard.mId.mSubtype);
            final int languageOnSpacebarFormatType = LanguageOnSpacebarUtils
                    .getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype);
            final boolean hasMultipleEnabledIMEsOrSubtypes = mRichImm.hasMultipleEnabledIMEsOrSubtypes(true);
            keyboardView.startDisplayLanguageOnSpacebar(subtypeChanged,
                    languageOnSpacebarFormatType, hasMultipleEnabledIMEsOrSubtypes);
            return true;
        } finally {
            Trace.endSection();
        }
    }

    private boolean canUseFastCaseSwitch(@Nullable final Keyboard oldKeyboard,
            @NonNull final Keyboard newKeyboard, @NonNull final KeyboardSwitchState toggleState,
            final boolean animate) {
        if (animate || oldKeyboard == null || toggleState != KeyboardSwitchState.OTHER) {
            return false;
        }
        if (!oldKeyboard.mId.isAlphabetKeyboard() || !newKeyboard.mId.isAlphabetKeyboard()) {
            return false;
        }
        if (oldKeyboard.mId.mElementId == newKeyboard.mId.mElementId) {
            return true;
        }
        return oldKeyboard.mId.mMode == newKeyboard.mId.mMode
                && oldKeyboard.mId.mSubtype.equals(newKeyboard.mId.mSubtype)
                && oldKeyboard.mOccupiedWidth == newKeyboard.mOccupiedWidth
                && oldKeyboard.mOccupiedHeight == newKeyboard.mOccupiedHeight
                && oldKeyboard.mBaseWidth == newKeyboard.mBaseWidth
                && oldKeyboard.mBaseHeight == newKeyboard.mBaseHeight
                && oldKeyboard.mTopPadding == newKeyboard.mTopPadding
                && oldKeyboard.mVerticalGap == newKeyboard.mVerticalGap
                && oldKeyboard.mMostCommonKeyWidth == newKeyboard.mMostCommonKeyWidth
                && oldKeyboard.mMostCommonKeyHeight == newKeyboard.mMostCommonKeyHeight;
    }

    public Keyboard getKeyboard() {
        if (mKeyboardView != null) {
            return mKeyboardView.getKeyboard();
        }
        return null;
    }

    // TODO: Remove this method. Come up with a more comprehensive way to reset the
    // keyboard layout
    // when a keyboard layout set doesn't get reloaded in
    // LatinIME.onStartInputViewInternal().
    public void resetKeyboardStateToAlphabet(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        if (isShowingAiWritingTools()) {
            return;
        }
        mState.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState);
    }

    public void onPressKey(final int code, final boolean isSinglePointer,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        if (isShowingKlipyPalettes() || isShowingEmojiPalettes() || isShowingClipboardHistory()
                || isShowingAiWritingTools()) {
            return;
        }
        mState.onPressKey(code, isSinglePointer, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onReleaseKey(final int code, final boolean withSliding,
            final int currentAutoCapsState, @Nullable final RecapitalizeMode currentRecapitalizeState) {
        if (isShowingKlipyPalettes() || isShowingEmojiPalettes() || isShowingClipboardHistory()
                || isShowingAiWritingTools()) {
            return;
        }
        mState.onReleaseKey(code, withSliding, currentAutoCapsState, currentRecapitalizeState);
    }

    public void onFinishSlidingInput(final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        if (isShowingKlipyPalettes() || isShowingEmojiPalettes() || isShowingClipboardHistory()
                || isShowingAiWritingTools()) {
            return;
        }
        mState.onFinishSlidingInput(currentAutoCapsState, currentRecapitalizeState);
    }

    public void enterResizeMode() {
        if (mCurrentInputView == null || mKeyboardView == null) {
            return;
        }
        mResizeModeActive = true;
        setResizeModeFrostedTint(true);
        setAlphabetKeyboard();
        if (mKeyboardResizeOverlay != null) {
            suspendResizeOverlayBlur();
            if (mMainKeyboardFrame != null) {
                mMainKeyboardFrame.setOutlineProvider(mResizeOverlayOutlineProvider);
                mMainKeyboardFrame.setClipToOutline(true);
                mMainKeyboardFrame.setAlpha(1f);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    final float density = mThemeContext.getResources().getDisplayMetrics().density;
                    mMainKeyboardFrame.setRenderEffect(RenderEffect.createBlurEffect(
                            8f * density,
                            8f * density,
                            Shader.TileMode.CLAMP));
                }
                mMainKeyboardFrame.invalidateOutline();
            }
            setResizeOverlayWindowFullscreen(true);
            mCurrentInputView.post(this::showResizeOverlayView);
        }
    }

    public void exitResizeMode() {
        mResizeModeActive = false;
        mResizeOverlayPinnedToPreview = false;
        mResizeOverlayPinnedPreviewHeight = 0;
        mResizeOverlayPinnedAnchorBottom = true;
        mResizeOverlayAnchorTopY = -1;
        mResizeOverlayAnchorBottomY = -1;
        if (mKeyboardResizeOverlay != null) {
            mKeyboardResizeOverlay.hide();
            final ViewGroup.LayoutParams params = mKeyboardResizeOverlay.getLayoutParams();
            if (params != null && params.height != 0) {
                params.height = 0;
                mKeyboardResizeOverlay.setLayoutParams(params);
            }
        }
        setResizeOverlayWindowFullscreen(false);
        setResizeModeFrostedTint(false);
        restoreResizeOverlayBlur();
        if (mMainKeyboardFrame != null) {
            mMainKeyboardFrame.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            mMainKeyboardFrame.setClipToOutline(false);
            mMainKeyboardFrame.setAlpha(1.0f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mMainKeyboardFrame.setRenderEffect(null);
            }
            mMainKeyboardFrame.invalidateOutline();
        }
    }

    public void onResizeOverlayPreviewHeightChanged(final int previewHeight) {
        onResizeOverlayPreviewHeightChanged(previewHeight, true);
    }

    public void onResizeOverlayPreviewHeightChanged(final int previewHeight, final boolean anchorBottom) {
        if (!isResizeOverlayVisible() || mCurrentInputView == null) {
            return;
        }
        final int height = Math.max(1, previewHeight);
        final int y = getResizeOverlayY(mCurrentInputView, height, anchorBottom);
        mResizeOverlayAnchorTopY = y;
        mResizeOverlayAnchorBottomY = y + height;
        mKeyboardResizeOverlay.setOverlayFrame(y, height);
        if (mMainKeyboardFrame != null) {
            mMainKeyboardFrame.invalidateOutline();
        }
    }

    public void onResizeOverlayDragCommitted(final int previewHeight) {
        onResizeOverlayDragCommitted(previewHeight, true);
    }

    public void onResizeOverlayDragCommitted(final int previewHeight, final boolean anchorBottom) {
        final int height = Math.max(1, previewHeight);
        mResizeOverlayPinnedToPreview = true;
        mResizeOverlayPinnedPreviewHeight = height;
        mResizeOverlayPinnedAnchorBottom = anchorBottom;
        onResizeOverlayPreviewHeightChanged(height, anchorBottom);
    }

    public void clearResizeOverlayPreviewPin() {
        mResizeOverlayPinnedToPreview = false;
        mResizeOverlayPinnedPreviewHeight = 0;
        mResizeOverlayPinnedAnchorBottom = true;
    }

    public boolean isResizeOverlayVisible() {
        return mKeyboardResizeOverlay != null && mKeyboardResizeOverlay.getVisibility() == View.VISIBLE;
    }

    public boolean isResizeModeActive() {
        return mResizeModeActive || isResizeOverlayVisible();
    }

    public boolean handleResizeOverlayBack() {
        if (!isResizeModeActive()) {
            return false;
        }
        exitResizeMode();
        return true;
    }

    private void suspendResizeOverlayBlur() {
        if (mResizeOverlayBlurSuppressed || mLatinIME == null) {
            return;
        }
        mResizeOverlayBlurWasEnabled = helium314.keyboard.latin.FrostedGlassHelper.isFrostedTheme(mLatinIME);
        helium314.keyboard.latin.FrostedGlassHelper.setResizeOverlayBlurSuppressed(
                mLatinIME,
                mCurrentInputView,
                true,
                false);
        mResizeOverlayBlurSuppressed = true;
    }

    private void restoreResizeOverlayBlur() {
        if (!mResizeOverlayBlurSuppressed || mLatinIME == null) {
            return;
        }
        mResizeOverlayBlurSuppressed = false;
        helium314.keyboard.latin.FrostedGlassHelper.setResizeOverlayBlurSuppressed(
                mLatinIME,
                mCurrentInputView,
                false,
                mResizeOverlayBlurWasEnabled);
        mResizeOverlayBlurWasEnabled = false;
    }

    private void setResizeModeFrostedTint(final boolean enabled) {
        if (mResizeModeFrostedTintApplied == enabled) {
            return;
        }
        if (enabled && (mLatinIME == null
                || !helium314.keyboard.latin.FrostedGlassHelper.isFrostedTheme(mLatinIME))) {
            return;
        }
        mResizeModeFrostedTintApplied = enabled;
        if (mCurrentInputView == null || mMainKeyboardFrame == null) {
            return;
        }
        final helium314.keyboard.latin.common.Colors colors = KeyboardTheme
                .getColorsForCurrentTheme(mCurrentInputView.getContext());
        if (enabled && mMainKeyboardFrame.getBackground() != null) {
            final int washColor = KeyboardTheme.isDarkThemeActive(mCurrentInputView.getContext())
                    ? android.graphics.Color.BLACK
                    : android.graphics.Color.WHITE;
            final int adjustedTintColor = ColorUtils.blendARGB(
                    colors.get(helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND),
                    washColor,
                    RESIZE_MODE_FROSTED_WASH_AMOUNT);
            final int tintColor = ColorUtils.setAlphaComponent(
                    adjustedTintColor,
                    RESIZE_MODE_FROSTED_TINT_ALPHA);
            mMainKeyboardFrame.getBackground().setColorFilter(
                    BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                            tintColor,
                            BlendModeCompat.MODULATE));
        } else {
            colors.setBackground(mMainKeyboardFrame,
                    helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND);
        }
        mMainKeyboardFrame.invalidate();
    }

    private void showResizeOverlayView() {
        if (!mResizeModeActive || mCurrentInputView == null || mKeyboardResizeOverlay == null
                || mMainKeyboardFrame == null
                || mCurrentInputView.getWindowToken() == null) {
            return;
        }
        final int height = getResizeOverlayFrameHeight();
        if (height <= 0 || mCurrentInputView.getWidth() <= 0) {
            mCurrentInputView.postDelayed(this::showResizeOverlayView, 16L);
            return;
        }

        final ViewGroup parent = (mKeyboardResizeOverlay.getParent() instanceof ViewGroup)
                ? (ViewGroup) mKeyboardResizeOverlay.getParent()
                : null;
        if (parent != null && parent != mCurrentInputView) {
            parent.removeView(mKeyboardResizeOverlay);
        }
        mResizeOverlayPinnedToPreview = false;
        mResizeOverlayPinnedPreviewHeight = 0;
        mResizeOverlayPinnedAnchorBottom = true;

        if (mKeyboardResizeOverlay.getParent() == null) {
            final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    Gravity.BOTTOM);
            mCurrentInputView.addView(mKeyboardResizeOverlay, params);
        }

        final int hostHeight = getResizeOverlayHostHeight(mCurrentInputView);
        final ViewGroup.LayoutParams params = mKeyboardResizeOverlay.getLayoutParams();
        if (params != null) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = hostHeight;
            mKeyboardResizeOverlay.setLayoutParams(params);
        }
        final Rect initialBounds = getResizeOverlayFrameBounds(mCurrentInputView);
        final int initialTop = toResizeOverlayLocalY(initialBounds.top);
        mResizeOverlayAnchorTopY = initialTop;
        mResizeOverlayAnchorBottomY = initialTop + initialBounds.height();
        mKeyboardResizeOverlay.setOverlayFrame(initialTop, initialBounds.height());
        if (mMainKeyboardFrame != null) {
            mMainKeyboardFrame.invalidateOutline();
        }
        mKeyboardResizeOverlay.show();
        mKeyboardResizeOverlay.bringToFront();
        mCurrentInputView.requestLayout();
        mCurrentInputView.post(() -> {
            if (!mResizeModeActive || mCurrentInputView == null
                    || mKeyboardResizeOverlay == null || mMainKeyboardFrame == null) {
                return;
            }
            final Rect bounds = getResizeOverlayFrameBounds(mCurrentInputView);
            final int top = toResizeOverlayLocalY(bounds.top);
            mResizeOverlayAnchorTopY = top;
            mResizeOverlayAnchorBottomY = top + bounds.height();
            mKeyboardResizeOverlay.setOverlayFrame(top, bounds.height());
            if (mMainKeyboardFrame != null) {
                mMainKeyboardFrame.invalidateOutline();
            }
        });
    }

    private int getResizeOverlayWidth(final View rootView) {
        if (rootView != null && rootView.getWidth() > 0) {
            return rootView.getWidth();
        }
        return ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private int getResizeOverlayHostHeight(final View rootView) {
        int height = 0;
        if (rootView != null && rootView.getHeight() > 0) {
            height = rootView.getHeight();
        }
        if (mThemeContext != null) {
            height = Math.max(height, mThemeContext.getResources().getDisplayMetrics().heightPixels);
        }
        return Math.max(1, height);
    }

    private int getResizeOverlayFrameHeight() {
        final int bottomReservedSpace = getCurrentKeyboardBottomPadding()
                + getResizeOverlayBottomInset();
        if (mMainKeyboardFrame != null && mMainKeyboardFrame.getHeight() > 0) {
            return Math.max(1, mMainKeyboardFrame.getHeight() - bottomReservedSpace);
        }
        if (mMainKeyboardFrame != null && mMainKeyboardFrame.getMeasuredHeight() > 0) {
            return Math.max(1, mMainKeyboardFrame.getMeasuredHeight() - bottomReservedSpace);
        }
        return 0;
    }

    public int getResizeOverlayBottomInset() {
        return mKeyboardViewWrapper == null
                ? 0
                : Math.max(0, mKeyboardViewWrapper.getPaddingBottom());
    }

    private int getCurrentKeyboardBottomPadding() {
        final Keyboard keyboard = mKeyboardView == null ? null : mKeyboardView.getKeyboard();
        if (keyboard == null) {
            return 0;
        }
        return Math.max(0, keyboard.mOccupiedHeight - keyboard.mTopPadding
                - keyboard.mBaseHeight + keyboard.mVerticalGap);
    }

    private Rect getResizeOverlayFrameBounds(final View rootView) {
        final int height = getResizeOverlayFrameHeight();
        if (rootView != null && mMainKeyboardFrame != null && mMainKeyboardFrame.getHeight() > 0) {
            final int[] rootLocation = new int[2];
            final int[] frameLocation = new int[2];
            rootView.getLocationOnScreen(rootLocation);
            mMainKeyboardFrame.getLocationOnScreen(frameLocation);
            final int top = frameLocation[1] - rootLocation[1];
            return new Rect(0, top, getResizeOverlayWidth(rootView), top + height);
        }
        if (rootView != null && rootView.getHeight() > 0) {
            final int bottom = rootView.getHeight();
            return new Rect(0, Math.max(0, bottom - height), getResizeOverlayWidth(rootView), bottom);
        }
        return new Rect(0, 0, getResizeOverlayWidth(rootView), height);
    }

    private int getResizeOverlayY(final View rootView, final int height, final boolean anchorBottom) {
        if (mResizeOverlayAnchorTopY < 0 || mResizeOverlayAnchorBottomY <= mResizeOverlayAnchorTopY) {
            final Rect bounds = getResizeOverlayFrameBounds(rootView);
            final int top = toResizeOverlayLocalY(bounds.top);
            mResizeOverlayAnchorTopY = top;
            mResizeOverlayAnchorBottomY = top + bounds.height();
        }
        return anchorBottom ? mResizeOverlayAnchorBottomY - height : mResizeOverlayAnchorTopY;
    }

    private int toResizeOverlayLocalY(final int inputViewY) {
        return inputViewY - getResizeOverlayHostTopY();
    }

    private int getResizeOverlayHostTopY() {
        if (mKeyboardResizeOverlay != null && mKeyboardResizeOverlay.getVisibility() == View.VISIBLE
                && mKeyboardResizeOverlay.getParent() != null
                && mKeyboardResizeOverlay.getHeight() > 0) {
            return mKeyboardResizeOverlay.getTop();
        }
        if (mCurrentInputView != null && mKeyboardResizeOverlay != null) {
            final ViewGroup.LayoutParams params = mKeyboardResizeOverlay.getLayoutParams();
            final int hostHeight = params != null && params.height > 0
                    ? params.height
                    : getResizeOverlayHostHeight(mCurrentInputView);
            return mCurrentInputView.getHeight() - hostHeight;
        }
        return 0;
    }

    private void scheduleResizeOverlaySyncAfterLayout(final int pinnedPreviewHeight) {
        scheduleResizeOverlaySyncAfterLayout(pinnedPreviewHeight, true);
    }

    private void scheduleResizeOverlaySyncAfterLayout(final int pinnedPreviewHeight,
            final boolean anchorBottom) {
        if (!isResizeOverlayVisible() || mCurrentInputView == null) {
            return;
        }
        if (mResizeOverlayPinnedToPreview && pinnedPreviewHeight <= 0) {
            return;
        }
        final boolean keepReleaseOverlayInPlace = pinnedPreviewHeight > 0;
        if (mMainKeyboardFrame == null) {
            if (keepReleaseOverlayInPlace) {
                clearResizeOverlayPreviewPin();
            } else {
                mCurrentInputView.post(this::syncResizeOverlayViewToFrame);
            }
            return;
        }

        final View frame = mMainKeyboardFrame;
        final boolean[] finished = { false };
        final View.OnLayoutChangeListener[] listener = new View.OnLayoutChangeListener[1];
        final Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            frame.removeOnLayoutChangeListener(listener[0]);
            if (mKeyboardResizeOverlay != null) {
                mKeyboardResizeOverlay.clearDragPreviewHeight();
            }
            clearResizeOverlayPreviewPin();
            if (keepReleaseOverlayInPlace) {
                syncResizeOverlayAnchorsToVisibleFrame();
            } else {
                syncResizeOverlayViewToFrame();
            }
        };
        final long finishDelayMs = keepReleaseOverlayInPlace ? 180L : 0L;
        listener[0] = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            frame.removeCallbacks(finish);
            frame.postDelayed(finish, finishDelayMs);
        };
        frame.addOnLayoutChangeListener(listener[0]);
        frame.postDelayed(finish, keepReleaseOverlayInPlace ? 360L : 240L);
    }

    private void syncResizeOverlayViewToFrame() {
        if (!isResizeOverlayVisible()) {
            return;
        }
        if (mResizeOverlayPinnedToPreview) {
            return;
        }
        final int height = getResizeOverlayFrameHeight();
        if (height > 0) {
            if (mCurrentInputView == null) {
                return;
            }
            final Rect bounds = getResizeOverlayFrameBounds(mCurrentInputView);
            final int top = toResizeOverlayLocalY(bounds.top);
            mResizeOverlayAnchorTopY = top;
            mResizeOverlayAnchorBottomY = top + bounds.height();
            if (mKeyboardResizeOverlay != null) {
                mKeyboardResizeOverlay.setOverlayFrame(top, bounds.height());
                mKeyboardResizeOverlay.requestLayout();
                mKeyboardResizeOverlay.invalidate();
            }
            if (mMainKeyboardFrame != null) {
                mMainKeyboardFrame.invalidateOutline();
            }
        }
    }

    private void syncResizeOverlayAnchorsToVisibleFrame() {
        if (!isResizeOverlayVisible() || mKeyboardResizeOverlay == null) {
            return;
        }
        mResizeOverlayAnchorTopY = mKeyboardResizeOverlay.getOverlayFrameTop();
        mResizeOverlayAnchorBottomY = mResizeOverlayAnchorTopY
                + mKeyboardResizeOverlay.getOverlayFrameHeight();
    }

    private void setResizeOverlayWindowFullscreen(final boolean fullscreen) {
        if (mResizeOverlayWindowFullscreen == fullscreen) {
            return;
        }
        mResizeOverlayWindowFullscreen = fullscreen;
        if (mLatinIME == null || mCurrentInputView == null) {
            return;
        }
        mCurrentInputView.setMinimumHeight(0);
        KtxKt.updateSoftInputWindowLayoutParameters(
                mLatinIME,
                mCurrentInputView,
                !fullscreen);
        mCurrentInputView.requestLayout();
    }

    public void onKeyboardHeightScaleChanged() {
        if (mCurrentInputView != null && mCurrentInputView.isShown()) {
            reloadMainKeyboardOnly();
        } else {
            setThemeNeedsReload();
        }
        if (mCurrentInputView != null && mResizeOverlayPinnedToPreview) {
            scheduleResizeOverlaySyncAfterLayout(
                    mResizeOverlayPinnedPreviewHeight,
                    mResizeOverlayPinnedAnchorBottom);
        } else if (mCurrentInputView != null) {
            scheduleResizeOverlaySyncAfterLayout(0);
        }
    }

    private void reloadMainKeyboardOnly() {
        if (mCurrentInputView == null || mKeyboardView == null)
            return;
        final Keyboard currentKeyboard = mKeyboardView.getKeyboard();
        if (currentKeyboard == null) {
            reloadKeyboard();
            return;
        }
        // Height and bottom-padding scales affect generated key geometry but are not
        // part of
        // KeyboardId, so a same-size lookup would otherwise return stale cached keys.
        KeyboardLayoutSet.onKeyboardGeometryChanged();
        final SettingsValues settingsValues = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(mThemeContext, settingsValues);
        final RichInputMethodSubtype currentSubtype = mRichImm.getCurrentSubtype();
        final int keyboardHeight = ResourceUtils.getKeyboardHeightForLocale(
                ResourceUtils.getKeyboardHeight(mThemeContext.getResources(), settingsValues),
                currentSubtype.getLocale());

        mKeyboardLayoutSet = new KeyboardLayoutSet.Builder(mThemeContext, mLatinIME.getCurrentInputEditorInfo())
                .setKeyboardGeometry(keyboardWidth, keyboardHeight)
                .setSubtype(currentSubtype)
                .setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
                .setNumberRowEnabled(settingsValues.mShowsNumberRow)
                .setNumberRowInSymbolsEnabled(settingsValues.mShowsNumberRowInSymbols)
                .setLanguageSwitchKeyEnabled(settingsValues.isLanguageSwitchKeyEnabled())
                .setEmojiKeyEnabled(settingsValues.mShowsEmojiKey)
                .setSplitLayoutEnabled(settingsValues.mIsSplitKeyboardEnabled)
                .setOneHandedModeEnabled(settingsValues.mOneHandedModeEnabled)
                .build();

        try {
            final Keyboard newKeyboard = mKeyboardLayoutSet.getKeyboard(currentKeyboard.mId.mElementId);
            mKeyboardView.setKeyboard(newKeyboard);
            mCurrentInputView.setKeyboardTopPadding(newKeyboard.mTopPadding);
            mCurrentInputView.requestLayout();
        } catch (final KeyboardLayoutSetException e) {
            Log.e(TAG, "Unable to reload resized keyboard", e);
            reloadKeyboard();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetManualShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetManualShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetAutomaticShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setAlphabetShiftLockShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setSymbolsShiftedKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED);
    }

    public boolean isImeSuppressedByHardwareKeyboard(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN;
    }

    private void setMainKeyboardFrame(
            @NonNull final SettingsValues settingsValues,
            @NonNull final KeyboardSwitchState toggleState) {
        final int visibility = isImeSuppressedByHardwareKeyboard(settingsValues, toggleState) ? View.GONE
                : View.VISIBLE;
        final boolean klipySearchActive = mKlipyPalettesView != null && mKlipyPalettesView.isSearchMode();
        final boolean emojiSearchActive = mEmojiPalettesView != null && mEmojiPalettesView.isSearchMode();
        final int stripVisibility = (klipySearchActive || emojiSearchActive) ? View.GONE
                : (mLatinIME.hasSuggestionStripView() ? View.VISIBLE : View.GONE);
        mStripContainer.setVisibility(stripVisibility);
        PointerTracker.switchTo(mKeyboardView);
        mKeyboardView.setVisibility(visibility);
        // The visibility of {@link #mKeyboardView} must be aligned with {@link
        // #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see
        // LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame.setVisibility(visibility);
        if (!emojiSearchActive) {
            mEmojiPalettesView.setVisibility(View.GONE);
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mKlipyPalettesView != null) {
            if (!mKlipyPalettesView.isSearchMode()) {
                mKlipyPalettesView.setVisibility(View.GONE);
                mKlipyPalettesView.stopKlipyPalettes();
            }
        }
        if (!emojiSearchActive) {
            mEmojiTabStripView.setVisibility(View.GONE);
        }
        mClipboardStripScrollView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(stripVisibility);
        mClipboardHistoryView.setVisibility(View.GONE);
        mClipboardHistoryView.stopClipboardHistory();
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setVisibility(View.GONE);
            mAiWritingToolsView.onClose();
        }
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setVisibility(View.GONE);
        }
        if (mSuggestionStripView != null) {
            mSuggestionStripView.setAccessPointMenuOpen(false);
        }
        setKeyboardPanelOffsets((mKlipyPalettesView != null && mKlipyPalettesView.isSearchMode())
                || (mEmojiPalettesView != null && mEmojiPalettesView.isSearchMode()));
        updatePersistentEmojiRow();
        logTypingListenerInvariant("KeyboardSwitcher.setMainKeyboardFrame", false /* logWhenOk */);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setEmojiKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setEmojiKeyboard");
        }
        transitionToPanel(mEmojiPalettesView, new Runnable() {
            @Override
            public void run() {
                updatePersistentEmojiRow();
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.GONE);

                // Start emoji palettes
                mEmojiPalettesView.startEmojiPalettes(mKeyboardView.getKeyVisualAttribute(),
                        mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
                mEmojiPalettesView.setVisibility(View.VISIBLE);
                mEmojiTabStripView.setVisibility(View.VISIBLE);
                mStripContainer.setVisibility(getSecondaryStripVisibility());
                setKeyboardPanelOffsets(true);

                mSuggestionStripView.setVisibility(View.GONE);
                mClipboardStripScrollView.setVisibility(View.GONE);
                mClipboardHistoryView.setVisibility(View.GONE);
                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }
                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAccessPointMenuOpen(false);
                }
                updatePersistentEmojiRow();
                if (mCurrentInputView != null)
                    mCurrentInputView.requestLayout();
            }
        });
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setClipboardKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setClipboardKeyboard");
        }
        transitionToPanel(mClipboardHistoryView, new Runnable() {
            @Override
            public void run() {
                updatePersistentEmojiRow();
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.GONE);

                // Start clipboard
                mClipboardHistoryView.startClipboardHistory(mLatinIME.getClipboardHistoryManager(),
                        mKeyboardView.getKeyVisualAttribute(),
                        mLatinIME.getCurrentInputEditorInfo(), mLatinIME.mKeyboardActionListener);
                mClipboardHistoryView.setVisibility(View.VISIBLE);
                mStripContainer.setVisibility(View.GONE);
                setKeyboardPanelOffsets(true);
                mClipboardStripScrollView.setVisibility(View.GONE);

                mEmojiTabStripView.setVisibility(View.GONE);
                mSuggestionStripView.setVisibility(View.GONE);
                mEmojiPalettesView.setVisibility(View.GONE);
                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }
                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAccessPointMenuOpen(false);
                }
                updatePersistentEmojiRow();
                if (mCurrentInputView != null)
                    mCurrentInputView.requestLayout();
            }
        });
    }

    @Override
    public void setAiToolsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAiToolsKeyboard");
        }
        transitionToPanel(mAiWritingToolsView, new Runnable() {
            @Override
            public void run() {
                updatePersistentEmojiRow();
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.GONE);

                if (mAiWritingToolsView != null) {
                    // CRITICAL: Force the panel to match the frame, not the screen
                    android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    mAiWritingToolsView.setLayoutParams(lp);
                    mAiWritingToolsView.onOpen(mLatinIME.getCurrentInputConnection());
                    mAiWritingToolsView.setVisibility(View.VISIBLE);
                }
                mStripContainer.setVisibility(View.GONE);
                setKeyboardPanelOffsets(true);

                mEmojiTabStripView.setVisibility(View.GONE);
                mSuggestionStripView.setVisibility(View.GONE);
                mEmojiPalettesView.setVisibility(View.GONE);
                mClipboardHistoryView.setVisibility(View.GONE);
                mClipboardStripScrollView.setVisibility(View.GONE);
                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAccessPointMenuOpen(false);
                }
                updatePersistentEmojiRow();
                if (mCurrentInputView != null)
                    mCurrentInputView.requestLayout();
            }
        });
    }

    public void setKlipyKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setKlipyKeyboard");
        }
        transitionToPanel(mKlipyPalettesView, new Runnable() {
            @Override
            public void run() {
                updatePersistentEmojiRow();
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.GONE);

                if (mKlipyPalettesView != null) {
                    mKlipyPalettesView.startKlipyPalettes(
                            mKeyboardView.getKeyVisualAttribute(),
                            mLatinIME.getCurrentInputEditorInfo(),
                            mLatinIME.mKeyboardActionListener);
                    mKlipyPalettesView.setVisibility(View.VISIBLE);
                }
                mStripContainer.setVisibility(View.GONE);
                setKeyboardPanelOffsets(true);

                mEmojiTabStripView.setVisibility(View.GONE);
                mSuggestionStripView.setVisibility(View.GONE);
                mEmojiPalettesView.setVisibility(View.GONE);
                mClipboardHistoryView.setVisibility(View.GONE);
                mClipboardStripScrollView.setVisibility(View.GONE);
                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }
                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setAccessPointMenuOpen(false);
                }
                updatePersistentEmojiRow();
                if (mCurrentInputView != null)
                    mCurrentInputView.requestLayout();
            }
        });
    }

    @Override
    public void setAccessPointKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setAccessPointKeyboard");
        }
        transitionToPanel(mAccessPointMenuView, new Runnable() {
            @Override
            public void run() {
                updatePersistentEmojiRow();
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.GONE);
                mEmojiPalettesView.setVisibility(View.GONE);
                mClipboardHistoryView.setVisibility(View.GONE);
                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }
                mEmojiTabStripView.setVisibility(View.GONE);
                mClipboardStripScrollView.setVisibility(View.GONE);

                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.populateMenu();
                    mAccessPointMenuView.setVisibility(View.VISIBLE);
                }
                mStripContainer.setVisibility(View.VISIBLE);
                if (mSuggestionStripView != null) {
                    mSuggestionStripView.setVisibility(View.VISIBLE);
                    mSuggestionStripView.showPinnedToolbarKeys();
                }
                updatePersistentEmojiRow();
                setKeyboardPanelOffsets(false);
                if (mCurrentInputView != null)
                    mCurrentInputView.requestLayout();
            }
        });
    }

    private void setKeyboardPanelOffsets(boolean enabled) {
        final SettingsValues settingsValues = Settings.getValues();
        if (settingsValues.mIsSplitKeyboardEnabled || settingsValues.mOneHandedModeEnabled) {
            enabled = false;
        }
        final Resources res = mThemeContext.getResources();
        final int topOffset = 0;
        final int sideOffset = enabled
                ? res.getDimensionPixelSize(R.dimen.config_keyboard_panel_content_side_padding)
                : 0;
        final boolean emojiRowVisible = enabled && mPersistentEmojiRowScroll != null
                && mPersistentEmojiRowScroll.getVisibility() == View.VISIBLE;
        final boolean stripVisible = enabled && mStripContainer != null
                && mStripContainer.getVisibility() == View.VISIBLE;

        setMargins(mPersistentEmojiRowScroll, sideOffset, emojiRowVisible ? topOffset : 0, sideOffset);
        if (mMainKeyboardFrame != null) {
            setMargins(mMainKeyboardFrame.findViewById(R.id.persistent_emoji_row_divider), sideOffset, 0, sideOffset);
        }
        setMargins(mStripContainer, sideOffset, stripVisible && !emojiRowVisible ? topOffset : 0, sideOffset);
        setMargins(mKeyboardViewWrapper, 0, enabled && !stripVisible && !emojiRowVisible ? topOffset : 0, 0);
    }

    private static void setMargins(@Nullable final View view, final int left, final int top, final int right) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.leftMargin == left && params.topMargin == top && params.rightMargin == right) {
            return;
        }
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = right;
        view.setLayoutParams(params);
    }

    @Override
    public void setNumpadKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setNumpadKeyboard");
        }
        setKeyboard(KeyboardId.ELEMENT_NUMPAD, KeyboardSwitchState.OTHER);
    }

    @Override
    public void toggleNumpad(final boolean withSliding, final int autoCapsFlags,
            @Nullable final RecapitalizeMode recapitalizeMode, final boolean forceReturnToAlpha) {
        if (DEBUG_ACTION) {
            Log.d(TAG, "toggleNumpad");
        }
        mState.toggleNumpad(withSliding, autoCapsFlags, recapitalizeMode, forceReturnToAlpha, true);
    }

    public enum KeyboardSwitchState {
        HIDDEN(-1),
        SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        EMOJI(KeyboardId.ELEMENT_EMOJI_RECENTS),
        CLIPBOARD(KeyboardId.ELEMENT_CLIPBOARD),
        AI_TOOLS(KeyboardId.ELEMENT_AI_TOOLS),
        ACCESS_POINT(-1),
        KLIPY(-1),
        OTHER(-1);

        final int mKeyboardId;

        KeyboardSwitchState(int keyboardId) {
            mKeyboardId = keyboardId;
        }
    }

    public KeyboardSwitchState getKeyboardSwitchState() {
        boolean hidden = !isShowingEmojiPalettes() && !isShowingClipboardHistory() && !isShowingAiWritingTools()
                && !isShowingAccessPointMenu() && !isShowingKlipyPalettes()
                && (mKeyboardLayoutSet == null
                        || mKeyboardView == null
                        || !mKeyboardView.isShown());
        if (hidden) {
            return KeyboardSwitchState.HIDDEN;
        } else if (isShowingEmojiPalettes()) {
            return KeyboardSwitchState.EMOJI;
        } else if (isShowingClipboardHistory()) {
            return KeyboardSwitchState.CLIPBOARD;
        } else if (isShowingAiWritingTools()) {
            return KeyboardSwitchState.AI_TOOLS;
        } else if (isShowingAccessPointMenu()) {
            return KeyboardSwitchState.ACCESS_POINT;
        } else if (isShowingKlipyPalettes()) {
            return KeyboardSwitchState.KLIPY;
        } else if (isShowingKeyboardId(KeyboardId.ELEMENT_SYMBOLS_SHIFTED)) {
            return KeyboardSwitchState.SYMBOLS_SHIFTED;
        }
        return KeyboardSwitchState.OTHER;
    }

    public void onToggleKeyboard(@NonNull final KeyboardSwitchState toggleState) {
        KeyboardSwitchState currentState = getKeyboardSwitchState();
        Log.w(TAG, "onToggleKeyboard() : Current = " + currentState + " : Toggle = " + toggleState);
        if (currentState == toggleState) {
            if (toggleState == KeyboardSwitchState.ACCESS_POINT) {
                setAlphabetKeyboard();
            } else {
                mLatinIME.stopShowingInputView();
                mLatinIME.hideWindow();
                setAlphabetKeyboard();
            }
        } else {
            mLatinIME.startShowingInputView(true);
            if (toggleState == KeyboardSwitchState.EMOJI) {
                setEmojiKeyboard();
            } else if (toggleState == KeyboardSwitchState.CLIPBOARD) {
                setClipboardKeyboard();
            } else if (toggleState == KeyboardSwitchState.AI_TOOLS) {
                setAiToolsKeyboard();
            } else if (toggleState == KeyboardSwitchState.KLIPY) {
                setKlipyKeyboard();
            } else if (toggleState == KeyboardSwitchState.ACCESS_POINT) {
                if (currentState == KeyboardSwitchState.CLIPBOARD || currentState == KeyboardSwitchState.EMOJI
                        || currentState == KeyboardSwitchState.AI_TOOLS || currentState == KeyboardSwitchState.KLIPY) {
                    Log.w(TAG, "Ignoring ACCESS_POINT toggle because current state is " + currentState);
                    return;
                }
                setAccessPointKeyboard();
            } else {
                mMainKeyboardFrame.setVisibility(View.VISIBLE);
                mKeyboardView.setVisibility(View.VISIBLE);
                setKeyboard(toggleState.mKeyboardId, toggleState);

                mEmojiPalettesView.stopEmojiPalettes();
                mEmojiPalettesView.setVisibility(View.GONE);

                mClipboardHistoryView.stopClipboardHistory();
                mClipboardHistoryView.setVisibility(View.GONE);

                if (mAiWritingToolsView != null) {
                    mAiWritingToolsView.setVisibility(View.GONE);
                    mAiWritingToolsView.onClose();
                }

                if (mAccessPointMenuView != null) {
                    mAccessPointMenuView.setVisibility(View.GONE);
                }

                if (mKlipyPalettesView != null) {
                    mKlipyPalettesView.stopKlipyPalettes();
                    mKlipyPalettesView.setVisibility(View.GONE);
                }

                if (mCurrentInputView != null) {
                    mCurrentInputView.requestLayout();
                }
            }
        }
    }

    // Future method for requesting an updating to the shift state.
    @Override
    public void requestUpdatingShiftState(final int autoCapsFlags, @Nullable final RecapitalizeMode recapitalizeMode) {
        if (isShowingKlipyPalettes() || isShowingEmojiPalettes() || isShowingClipboardHistory()
                || isShowingAiWritingTools()) {
            return;
        }
        Trace.beginSection("KeyboardSwitcher#updateShiftState");
        try {
            if (DEBUG_ACTION) {
                Log.d(TAG, "requestUpdatingShiftState: "
                        + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                        + " recapitalizeMode=" + recapitalizeMode);
            }
            mState.onUpdateShiftState(autoCapsFlags, recapitalizeMode);
        } finally {
            Trace.endSection();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void startDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.startDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void cancelDoubleTapShiftKeyTimer() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "cancelDoubleTapShiftKeyTimer");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        if (keyboardView != null) {
            keyboardView.cancelDoubleTapShiftKeyTimer();
        }
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void setOneHandedModeEnabled(boolean enabled) {
        setOneHandedModeEnabled(enabled, false);
    }

    public void setOneHandedModeEnabled(boolean enabled, boolean force) {
        if (!force && mKeyboardViewWrapper.getOneHandedModeEnabled() == enabled) {
            return;
        }
        final Settings settings = Settings.getInstance();
        mKeyboardViewWrapper.setOneHandedModeEnabled(enabled);
        mKeyboardViewWrapper.setOneHandedGravity(settings.getCurrent().mOneHandedModeGravity);

        settings.writeOneHandedModeEnabled(enabled);
        reloadKeyboard();
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public void switchOneHandedMode() {
        mKeyboardViewWrapper.switchOneHandedModeSide();
        Settings.getInstance().writeOneHandedModeGravity(mKeyboardViewWrapper.getOneHandedGravity());
    }

    public void toggleSplitKeyboardMode() {
        final Settings settings = Settings.getInstance();
        settings.writeSplitKeyboardEnabled(
                !settings.getCurrent().mIsSplitKeyboardEnabled,
                mCurrentOrientation == Configuration.ORIENTATION_LANDSCAPE,
                FoldableUtils.INSTANCE.isFolded());
        setOneHandedModeEnabled(settings.getCurrent().mOneHandedModeEnabled, true);
        reloadKeyboard();
    }

    public void reloadKeyboard() {
        if (mCurrentInputView == null)
            return;
        if (mEmojiPalettesView != null)
            mEmojiPalettesView.clearKeyboardCache();
        reloadMainKeyboard();
    }

    public void reloadMainKeyboard() {
        // Reload the entire keyboard, and switch to the previous layout
        final boolean wasEmoji = isShowingEmojiPalettes();
        final boolean wasClipboard = isShowingClipboardHistory();
        final boolean wasAiTools = isShowingAiWritingTools();
        final boolean wasKlipy = isShowingKlipyPalettes();
        loadKeyboard(mLatinIME.getCurrentInputEditorInfo(), Settings.getValues(),
                mLatinIME.getCurrentAutoCapsState(), mLatinIME.getCurrentRecapitalizeState(), null);
        if (wasEmoji) {
            setEmojiKeyboard();
        } else if (wasClipboard) {
            setClipboardKeyboard();
        } else if (wasAiTools) {
            setAiToolsKeyboard();
        } else if (wasKlipy) {
            setKlipyKeyboard();
        }
    }

    /**
     * Displays a toast message.
     *
     * @param text       The text to display in the toast message.
     * @param briefToast If true, the toast duration will be short; otherwise, it
     *                   will last longer.
     */
    public void showToast(final String text, final boolean briefToast) {
        // In API 32 and below, toasts can be shown without a notification permission.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            final int toastLength = briefToast ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
            final Toast toast = Toast.makeText(mLatinIME, text, toastLength);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else {
            final int toastLength = briefToast ? 2000 : 3500;
            showFakeToast(text, toastLength);
        }
    }

    private static int getSecondaryStripVisibility() {
        return Settings.getValues().mSecondaryStripVisible ? View.VISIBLE : View.GONE;
    }

    // Displays a toast-like message with the provided text for a specified
    // duration.
    private void showFakeToast(final String text, final int timeMillis) {
        if (mFakeToastView == null || mFakeToastView.getVisibility() == View.VISIBLE)
            return;

        final Drawable appIcon = mFakeToastView.getCompoundDrawables()[0];
        if (appIcon != null) {
            final int bound = mFakeToastView.getLineHeight();
            appIcon.setBounds(0, 0, bound, bound);
            mFakeToastView.setCompoundDrawables(appIcon, null, null, null);
        }
        mFakeToastView.setText(text);
        KeyboardTypeface.applyToTextView(mFakeToastView);
        mFakeToastView.setVisibility(View.VISIBLE);
        mFakeToastView.bringToFront();
        mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_in));

        mFakeToastView.postDelayed(() -> {
            mFakeToastView.startAnimation(AnimationUtils.loadAnimation(mLatinIME, R.anim.fade_out));
            mFakeToastView.setVisibility(View.GONE);
        }, timeMillis);
    }

    // Implements {@link KeyboardState.SwitchActions}.
    @Override
    public boolean isInDoubleTapShiftKeyTimeout() {
        if (DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout");
        }
        final MainKeyboardView keyboardView = getMainKeyboardView();
        return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout();
    }

    /**
     * Updates state machine to figure out when to automatically switch back to the
     * previous mode.
     */
    public void onEvent(final Event event, final int currentAutoCapsState,
            @Nullable final RecapitalizeMode currentRecapitalizeState) {
        if (isShowingKlipyPalettes() || isShowingEmojiPalettes() || isShowingClipboardHistory()
                || isShowingAiWritingTools()) {
            return;
        }
        mState.onEvent(event, currentAutoCapsState, currentRecapitalizeState);
    }

    public boolean isShowingKeyboardId(@NonNull int... keyboardIds) {
        if (mKeyboardView == null || !mKeyboardView.isShown()) {
            return false;
        }
        final Keyboard keyboard = mKeyboardView.getKeyboard();
        if (keyboard == null) // may happen when using hardware keyboard
            return false;
        int activeKeyboardId = keyboard.mId.mElementId;
        for (int keyboardId : keyboardIds) {
            if (activeKeyboardId == keyboardId) {
                return true;
            }
        }
        return false;
    }

    public boolean isShowingEmojiPalettes() {
        return mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE;
    }

    public boolean isShowingClipboardHistory() {
        return mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE;
    }

    public boolean isShowingAiWritingTools() {
        return mAiWritingToolsView != null && mAiWritingToolsView.getVisibility() == View.VISIBLE;
    }

    public boolean isShowingAccessPointMenu() {
        return mAccessPointMenuView != null && mAccessPointMenuView.getVisibility() == View.VISIBLE;
    }

    public boolean isShowingKlipyPalettes() {
        return mKlipyPalettesView != null && mKlipyPalettesView.getVisibility() == View.VISIBLE;
    }

    public boolean isShowingPopupKeysPanel() {
        if (isShowingEmojiPalettes() || isShowingClipboardHistory() || isShowingAiWritingTools()
                || isShowingAccessPointMenu() || isShowingKlipyPalettes()) {
            return false;
        }
        return mKeyboardView != null && mKeyboardView.isShowingPopupKeysPanel();
    }

    public boolean isShowingStripContainer() {
        return mStripContainer != null && mStripContainer.isShown();
    }

    public EmojiPalettesView getEmojiPalettesView() {
        return mEmojiPalettesView;
    }

    public KlipyPalettesView getKlipyPalettesView() {
        return mKlipyPalettesView;
    }

    public AccessPointMenuView getAccessPointMenuView() {
        return mAccessPointMenuView;
    }

    public View getVisibleKeyboardView() {
        if (isShowingEmojiPalettes()) {
            return mEmojiPalettesView;
        } else if (isShowingClipboardHistory()) {
            return mClipboardHistoryView;
        } else if (isShowingAiWritingTools()) {
            return mAiWritingToolsView;
        } else if (isShowingAccessPointMenu()) {
            return mAccessPointMenuView;
        } else if (isShowingKlipyPalettes()) {
            return mKlipyPalettesView;
        }
        return mKeyboardView;
    }

    public View getWrapperView() {
        return mKeyboardViewWrapper;
    }

    public View getEmojiTabStrip() {
        return mEmojiTabStripView;
    }

    public LinearLayout getClipboardStrip() {
        return mClipboardStripView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return mKeyboardView;
    }

    public boolean isKlipySearchModeActive() {
        return mKlipyPalettesView != null && mKlipyPalettesView.isSearchMode();
    }

    public boolean isEmojiSearchModeActive() {
        return mEmojiPalettesView != null && mEmojiPalettesView.isSearchMode();
    }

    public void logTypingListenerInvariant(final String reason, final boolean logWhenOk) {
        // No-op diagnostics removed
    }

    public MainKeyboardView getKeyboardView() {
        return mKeyboardView;
    }

    public FrameLayout getStripContainer() {
        return mStripContainer;
    }

    public View getClipboardHistoryView() {
        return mClipboardHistoryView;
    }

    public View getAiWritingToolsView() {
        return mAiWritingToolsView;
    }

    public void deallocateMemory() {
        if (mKeyboardView != null) {
            mKeyboardView.cancelAllOngoingEvents();
            mKeyboardView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.stopEmojiPalettes();
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.stopClipboardHistory();
        }
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.onClose();
        }
        if (mKlipyPalettesView != null) {
            mKlipyPalettesView.stopKlipyPalettes();
        }
    }

    public void trimMemory() {
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.clearKeyboardCache();
        }
    }

    @SuppressLint("InflateParams")
    public View onCreateInputView(@NonNull Context displayContext, final boolean isHardwareAcceleratedDrawingEnabled) {
        if (mKeyboardView != null) {
            mKeyboardView.closing();
        }
        exitResizeMode();
        PointerTracker.clearOldViewData();
        final SharedPreferences prefs = KtxKt.prefs(displayContext);
        if (mSuggestionStripView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mSuggestionStripView);
        if (mClipboardHistoryView != null)
            prefs.unregisterOnSharedPreferenceChangeListener(mClipboardHistoryView);
        if (mThemeNeedsReload) // necessary in some cases (e.g. theme switch) when mThemeNeedsReload is set
                               // before first keyboard load
            Settings.getInstance().loadSettings(displayContext, Settings.getValues().mLocale,
                    Settings.getValues().mInputAttributes);

        updateKeyboardThemeAndContextThemeWrapper(displayContext, KeyboardTheme.getKeyboardTheme(displayContext));
        mCurrentInputView = (InputView) LayoutInflater.from(mThemeContext).inflate(R.layout.input_view, null);
        mMainKeyboardFrame = mCurrentInputView.findViewById(R.id.main_keyboard_frame);
        mEmojiPalettesView = mCurrentInputView.findViewById(R.id.emoji_palettes_view);
        mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);
        mAiWritingToolsView = mCurrentInputView.findViewById(R.id.ai_writing_tools_view);
        mKlipyPalettesView = mCurrentInputView.findViewById(R.id.klipy_palettes_view);
        mAccessPointMenuView = mCurrentInputView.findViewById(R.id.access_point_menu_view);
        mFakeToastView = mCurrentInputView.findViewById(R.id.fakeToast);

        mKeyboardViewWrapper = mCurrentInputView.findViewById(R.id.keyboard_view_wrapper);
        mKeyboardViewWrapper.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        mKeyboardView = mCurrentInputView.findViewById(R.id.keyboard_view);
        mKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        mKeyboardView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
            mEmojiPalettesView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
            mClipboardHistoryView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        if (mKlipyPalettesView != null) {
            mKlipyPalettesView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        }
        if (mAiWritingToolsView != null) {
            mAiWritingToolsView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
            mAiWritingToolsView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.setKeyboardActionListener(mLatinIME.mKeyboardActionListener);
        }
        mEmojiTabStripView = mCurrentInputView.findViewById(R.id.emoji_tab_strip_container);
        mClipboardStripView = mCurrentInputView.findViewById(R.id.clipboard_strip);
        mClipboardStripScrollView = mCurrentInputView.findViewById(R.id.clipboard_strip_scroll_view);
        mSuggestionStripView = mCurrentInputView.findViewById(R.id.suggestion_strip_view);
        mStripContainer = mCurrentInputView.findViewById(R.id.strip_container);
        mPersistentEmojiRowScroll = mCurrentInputView.findViewById(R.id.persistent_emoji_row_scroll);
        mPersistentEmojiRowContainer = mCurrentInputView.findViewById(R.id.persistent_emoji_row_container);

        mKeyboardResizeOverlay = new helium314.keyboard.keyboard.resize.KeyboardResizeOverlayView(mThemeContext);
        mKeyboardResizeOverlay.setId(R.id.keyboard_resize_overlay);
        mKeyboardResizeOverlay.setVisibility(View.GONE);
        mKeyboardResizeOverlay.init(mMainKeyboardFrame, this);
        final FrameLayout.LayoutParams resizeOverlayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM);
        mCurrentInputView.addView(mKeyboardResizeOverlay, resizeOverlayParams);

        if (mMainKeyboardFrame instanceof ViewGroup) {
            ((ViewGroup) mMainKeyboardFrame).setLayoutTransition(null);
        }
        if (mCurrentInputView != null) {
            mCurrentInputView.setLayoutTransition(null);
        }
        if (mStripContainer != null) {
            mStripContainer.setLayoutTransition(null);
        }

        prefs.registerOnSharedPreferenceChangeListener(mSuggestionStripView);
        prefs.registerOnSharedPreferenceChangeListener(mClipboardHistoryView);
        PointerTracker.switchTo(mKeyboardView);
        return mCurrentInputView;
    }

    public int getKeyboardShiftMode() {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return WordComposer.CAPS_MODE_OFF;
        }
        return keyboard.mId.getKeyboardCapsMode();
    }

    public String getCurrentKeyboardScript() {
        if (null == mKeyboardLayoutSet) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        return mKeyboardLayoutSet.getScript();
    }

    public void switchToSubtype(InputMethodSubtype subtype) {
        mLatinIME.switchToSubtype(subtype);
    }

    // used for debug
    public String getLocaleAndConfidenceInfo() {
        return mLatinIME.getLocaleAndConfidenceInfo();
    }

    public void updatePersistentEmojiRow() {
        if (mPersistentEmojiRowScroll == null || mPersistentEmojiRowContainer == null || mCurrentInputView == null) {
            return;
        }
        final android.content.SharedPreferences prefs = KtxKt.prefs(mCurrentInputView.getContext());
        final boolean enabled = prefs.getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW,
                helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW);
        final View divider = mMainKeyboardFrame != null
                ? mMainKeyboardFrame.findViewById(R.id.persistent_emoji_row_divider)
                : null;
        final KeyboardSwitchState state = getKeyboardSwitchState();
        final boolean inPanel = state == KeyboardSwitchState.EMOJI || state == KeyboardSwitchState.CLIPBOARD
                || state == KeyboardSwitchState.AI_TOOLS || state == KeyboardSwitchState.KLIPY
                || (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE)
                || (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE)
                || (mAiWritingToolsView != null && mAiWritingToolsView.getVisibility() == View.VISIBLE)
                || (mKlipyPalettesView != null && mKlipyPalettesView.getVisibility() == View.VISIBLE);
        if (!enabled || mMainKeyboardFrame == null || mMainKeyboardFrame.getVisibility() != View.VISIBLE) {
            mPersistentEmojiRowScroll.setVisibility(View.GONE);
            if (divider != null)
                divider.setVisibility(View.GONE);
            mCurrentlyDisplayedPersistentEmojis = null;
            return;
        }
        if (inPanel) {
            mPersistentEmojiRowScroll.setVisibility(View.GONE);
            if (divider != null)
                divider.setVisibility(View.GONE);
            mCurrentlyDisplayedPersistentEmojis = null;
            return;
        }
        mPersistentEmojiRowScroll.setVisibility(View.VISIBLE);
        if (divider != null)
            divider.setVisibility(View.VISIBLE);

        final java.util.List<String> rawEmojis;
        if (mCachedPersistentEmojis != null
                && !helium314.keyboard.keyboard.emoji.EmojiPalettesView.AdaptiveEmojiEngine
                        .shouldRefreshFastRow(mPersistentEmojiRowContainer.getContext())) {
            rawEmojis = mCachedPersistentEmojis;
        } else {
            rawEmojis = helium314.keyboard.keyboard.emoji.EmojiPalettesView.AdaptiveEmojiEngine
                    .getFastRowEmojis(mPersistentEmojiRowContainer.getContext());
            mCachedPersistentEmojis = rawEmojis;
        }
        final java.util.List<String> emojis = new java.util.ArrayList<>();
        if (rawEmojis.size() >= 10) {
            // (6, 7, 8, 9, 10) on the left, (1, 2, 3, 4, 5) on the right just like old
            // project
            emojis.addAll(rawEmojis.subList(5, 10));
            emojis.addAll(rawEmojis.subList(0, 5));
        } else {
            emojis.addAll(rawEmojis);
        }

        if (mCurrentlyDisplayedPersistentEmojis != null && mCurrentlyDisplayedPersistentEmojis.equals(emojis)
                && mPersistentEmojiRowContainer.getChildCount() > 0) {
            mPersistentEmojiRowScroll.setVisibility(View.VISIBLE);
            if (divider != null)
                divider.setVisibility(View.VISIBLE);
            return;
        }
        mCurrentlyDisplayedPersistentEmojis = emojis;

        mPersistentEmojiRowContainer.removeAllViews();
        final android.content.Context context = mPersistentEmojiRowContainer.getContext();
        final SettingsValues sv = Settings.getValues();
        final int kbWidth = sv != null ? helium314.keyboard.latin.utils.ResourceUtils.getKeyboardWidth(context, sv)
                : context.getResources().getDisplayMetrics().widthPixels;
        final int itemWidth = kbWidth / 10;
        final int height = (int) (36 * context.getResources().getDisplayMetrics().density);

        for (final String emoji : emojis) {
            final TextView tv = new TextView(context);
            tv.setText(emoji);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
            KeyboardTypeface.applyToTextView(tv, emoji, android.graphics.Typeface.DEFAULT);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(itemWidth, height));
            tv.setClickable(true);
            tv.setFocusable(true);
            final android.util.TypedValue outValue = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            tv.setBackgroundResource(outValue.resourceId);
            tv.setOnClickListener(v -> {
                if (mLatinIME != null && mLatinIME.mKeyboardActionListener != null) {
                    mLatinIME.mKeyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS);
                    mLatinIME.mKeyboardActionListener.onTextInput(emoji);
                    mLatinIME.mKeyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false);
                    if (mEmojiPalettesView != null) {
                        mEmojiPalettesView.addRecentEmoji(emoji);
                    } else {
                        helium314.keyboard.keyboard.emoji.EmojiPalettesView.AdaptiveEmojiEngine
                                .recordEmojiUsage(context, emoji);
                    }
                    updatePersistentEmojiRow();
                }
            });
            mPersistentEmojiRowContainer.addView(tv);
        }

        // Add the polished right-side "Remove Row" button from old project
        final LinearLayout removeBtn = new LinearLayout(context);
        removeBtn.setOrientation(LinearLayout.HORIZONTAL);
        removeBtn.setGravity(Gravity.CENTER);
        final int paddingH = (int) (12 * context.getResources().getDisplayMetrics().density);
        final int paddingV = (int) (6 * context.getResources().getDisplayMetrics().density);
        final int marginH = (int) (16 * context.getResources().getDisplayMetrics().density);
        removeBtn.setPadding(paddingH, paddingV, paddingH, paddingV);

        final LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (int) (32 * context.getResources().getDisplayMetrics().density));
        removeParams.gravity = Gravity.CENTER_VERTICAL;
        removeParams.leftMargin = marginH;
        removeParams.rightMargin = marginH;
        removeBtn.setLayoutParams(removeParams);

        final android.graphics.drawable.GradientDrawable removeBg = new android.graphics.drawable.GradientDrawable();
        removeBg.setColor(0x20808080); // subtle semi-transparent background matching t.keySurface.copy(alpha = 0.3f)
        removeBg.setCornerRadius(16 * context.getResources().getDisplayMetrics().density);
        removeBtn.setBackground(removeBg);
        removeBtn.setClickable(true);
        removeBtn.setFocusable(true);
        removeBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, false).apply();
            updatePersistentEmojiRow();
            if (mCurrentInputView != null)
                mCurrentInputView.requestLayout();
        });

        final TextView removeText = new TextView(context);
        removeText.setText("Remove row");
        removeText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        KeyboardTypeface.applyToTextView(removeText);
        removeText.setGravity(Gravity.CENTER);
        removeText.setTextColor(0xFF808080); // subtle gray text matching t.keyText.copy(alpha = 0.6f)
        removeBtn.addView(removeText);

        mPersistentEmojiRowContainer.addView(removeBtn);
    }

    public boolean isShowingPersistentEmojiRow() {
        return mPersistentEmojiRowScroll != null && mPersistentEmojiRowScroll.getVisibility() == View.VISIBLE;
    }

    public int getPersistentEmojiRowHeight() {
        if (mPersistentEmojiRowScroll == null)
            return 0;
        float density = mPersistentEmojiRowScroll.getContext().getResources().getDisplayMetrics().density;
        return (int) (41 * density);
    }

    /**
     * Marks the theme as outdated. The theme will be reloaded next time the
     * keyboard is shown.
     * If the keyboard is currently showing, theme will be reloaded immediately.
     */
    public void setThemeNeedsReload() {
        mThemeNeedsReload = true;
        if (mLatinIME == null || !mLatinIME.isInputViewShown())
            return; // will be reloaded right before showing IME

        // Hide and show IME, showing will trigger the reload.
        // Reloading while IME is shown is glitchy, and hiding / showing is so fast the
        // user shouldn't notice.
        mLatinIME.hideWindow();
        try {
            mLatinIME.showWindow(true);
        } catch (IllegalStateException e) {
            // in tests isInputViewShown returns true, but showWindow throws
            // "IllegalStateException: Window token is not set yet."
        }
    }

    public void updateLiveFrostedGlassColors() {
        if (mCurrentInputView == null)
            return;
        final helium314.keyboard.latin.common.Colors colors = helium314.keyboard.keyboard.KeyboardTheme
                .getColorsForCurrentTheme(
                        mCurrentInputView.getContext());
        if (colors == null)
            return;

        // 1. Update mMainKeyboardFrame background
        if (mMainKeyboardFrame != null) {
            colors.setBackground(mMainKeyboardFrame, helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND);
            mMainKeyboardFrame.invalidate();
        }
        if (mCurrentInputView != null) {
            mCurrentInputView.invalidate();
        }

        // 2. Update mKeyboardView theme colors and force a redraw
        if (mKeyboardView != null) {
            mKeyboardView.updateThemeColors(colors);
        }

        // 3. Update mSuggestionStripView background and keys
        if (mSuggestionStripView != null) {
            mSuggestionStripView.updateThemeColors(colors);
        }

        // Update AccessPointMenuView keys
        if (mAccessPointMenuView != null) {
            mAccessPointMenuView.updateThemeColors(colors);
        }

        // 4. Update the soft window background blur radius
        if (mLatinIME != null) {
            helium314.keyboard.latin.FrostedGlassHelper.configureFrostedGlass(mLatinIME, mCurrentInputView,
                    helium314.keyboard.latin.FrostedGlassHelper.isFrostedTheme(mLatinIME));
        }
    }

    public void forceUpdateKeyboardTheme(Context context) {
        mThemeNeedsReload = true;
        mKeyboardTheme = null;
        mThemeContext = null;
    }
}
