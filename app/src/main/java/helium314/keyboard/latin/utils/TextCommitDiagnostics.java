/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: GPL-3.0-only
 */

package helium314.keyboard.latin.utils;

import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import helium314.keyboard.event.Event;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.common.Constants;
import helium314.keyboard.latin.define.DebugFlags;
import helium314.keyboard.latin.settings.SettingsValues;

/**
 * Safe, opt-in timing diagnostics for the software-key to target-editor commit path.
 *
 * <p>Do not log typed text from here. Keep output to timings, lengths, flags, and package/editor
 * metadata so logcat can be shared while investigating latency.</p>
 */
public final class TextCommitDiagnostics {
    private static final String TAG = "TextCommitDiag";
    private static final long NO_SEQUENCE = -1L;
    private static final long DELAYED_SELECTION_CHURN_MS = 50L;
    private static final int SELECTION_CHURN_LOG_EARLY_COUNT = 2;
    private static final int SELECTION_CHURN_LOG_EVERY = 4;

    private static final AtomicLong NEXT_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Long> CURRENT_SEQUENCE = new ThreadLocal<>();

    private static volatile long sLastPipelineSequence = NO_SEQUENCE;
    private static volatile long sLastPipelineStartUptime = -1L;
    private static volatile long sLastWriteSequence = NO_SEQUENCE;
    private static volatile long sLastWriteEndUptime = -1L;
    private static volatile long sSelectionAckSequence = NO_SEQUENCE;
    private static volatile int sSelectionAckCount = 0;

    private TextCommitDiagnostics() {
        // Utility class.
    }

    public static boolean isEnabled() {
        return DebugFlags.TEXT_COMMIT_DIAGNOSTICS_ENABLED;
    }

    public static long startOperation() {
        return isEnabled() ? SystemClock.uptimeMillis() : 0L;
    }

    public static long beginSoftwareKeyDispatch(final int code, final long eventTime,
            final boolean isKeyRepeat, final int textLength, final String listenerName) {
        if (!isEnabled()) {
            return NO_SEQUENCE;
        }
        final long sequence = beginSequence(SystemClock.uptimeMillis());
        log("stage=PointerTracker.dispatch"
                + " seq=" + sequence
                + " sinceKeyMs=0"
                + " keyKind=" + codeKind(code, textLength)
                + " textLength=" + Math.max(textLength, 0)
                + " repeat=" + isKeyRepeat
                + " eventAgeMs=" + formatMillis(SystemClock.uptimeMillis() - eventTime)
                + " listener=" + listenerName
                + " textCommitExperiment=" + DebugFlags.TEXT_COMMIT_EXPERIMENT_MODE);
        return sequence;
    }

    public static long beginHardwareKeyDispatch(final int codePoint, final long eventTime,
            final boolean isKeyRepeat, final String listenerName) {
        if (!isEnabled()) {
            return NO_SEQUENCE;
        }
        final long sequence = beginSequence(SystemClock.uptimeMillis());
        log("stage=Hardware.dispatch"
                + " seq=" + sequence
                + " sinceKeyMs=0"
                + " keyKind=" + codeKind(codePoint, 0)
                + " repeat=" + isKeyRepeat
                + " eventAgeMs=" + formatMillis(SystemClock.uptimeMillis() - eventTime)
                + " listener=" + listenerName
                + " textCommitExperiment=" + DebugFlags.TEXT_COMMIT_EXPERIMENT_MODE);
        return sequence;
    }

    public static long beginGeneratedTextDispatch(final int textLength, final String source) {
        if (!isEnabled()) {
            return NO_SEQUENCE;
        }
        final long sequence = beginSequence(SystemClock.uptimeMillis());
        log("stage=" + source
                + " seq=" + sequence
                + " sinceKeyMs=0"
                + " keyKind=generatedText"
                + " textLength=" + Math.max(textLength, 0)
                + " textCommitExperiment=" + DebugFlags.TEXT_COMMIT_EXPERIMENT_MODE);
        return sequence;
    }

    public static void clearCurrentSequence(final long sequence) {
        if (!isEnabled() || sequence == NO_SEQUENCE) {
            return;
        }
        final Long current = CURRENT_SEQUENCE.get();
        if (current != null && current == sequence) {
            CURRENT_SEQUENCE.remove();
        }
    }

    public static long currentSequence() {
        final Long sequence = CURRENT_SEQUENCE.get();
        return sequence == null ? NO_SEQUENCE : sequence;
    }

    public static void stage(final String stage) {
        stage(stage, null);
    }

    public static void stage(final String stage, final String extra) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = currentSequence();
        log("stage=" + stage
                + " seq=" + sequence
                + " sinceKeyMs=" + formatMillis(sincePipelineStart(now, sequence))
                + appendExtra(extra));
    }

    public static void duration(final String operation, final long startTime, final String extra) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = currentSequence();
        log("op=" + operation
                + " seq=" + sequence
                + " durationMs=" + formatMillis(now - startTime)
                + " sinceKeyMs=" + formatMillis(sincePipelineStart(now, sequence))
                + appendExtra(extra));
    }

    public static void inputConnectionWrite(final String operation, final long startTime,
            final int textLength, final int newCursorPosition, final boolean connected,
            final String result) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = currentSequence();
        markWriteBoundary(sequence, now);
        log("icWrite=" + operation
                + " seq=" + sequence
                + " durationMs=" + formatMillis(now - startTime)
                + " sinceKeyMs=" + formatMillis(sincePipelineStart(now, sequence))
                + " textLength=" + Math.max(textLength, 0)
                + " newCursorPosition=" + newCursorPosition
                + " connected=" + connected
                + " result=" + result);
    }

    public static void batchEdit(final String operation, final long startTime,
            final boolean connected, final String result, final boolean marksWriteBoundary) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = currentSequence();
        if (marksWriteBoundary) {
            markWriteBoundary(sequence, now);
        }
        log("batch=" + operation
                + " seq=" + sequence
                + " durationMs=" + formatMillis(now - startTime)
                + " sinceKeyMs=" + formatMillis(sincePipelineStart(now, sequence))
                + " connected=" + connected
                + " result=" + result);
    }

    public static void batchEditSkipped(final String operation, final String owner,
            final boolean marksWriteBoundary) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = currentSequence();
        if (marksWriteBoundary) {
            markWriteBoundary(sequence, now);
        }
        log("batch=" + operation
                + " seq=" + sequence
                + " durationMs=0"
                + " sinceKeyMs=" + formatMillis(sincePipelineStart(now, sequence))
                + " connected=skipped"
                + " result=skipped"
                + " skipped=true"
                + " owner=" + owner
                + " textCommitExperiment=" + DebugFlags.TEXT_COMMIT_EXPERIMENT_MODE);
    }

    public static void updateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd, final int composingSpanStart,
            final int composingSpanEnd, final EditorInfo editorInfo, final SettingsValues settingsValues,
            final boolean klipySearchActive, final boolean emojiSearchActive) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        final long sequence = sLastWriteSequence;
        final long sinceWrite = sLastWriteEndUptime < 0 ? -1L : now - sLastWriteEndUptime;
        final boolean tracksWrite = sequence != NO_SEQUENCE && sinceWrite >= 0;
        final boolean firstSelectionAck;
        final int selectionAckIndex;
        if (!tracksWrite) {
            firstSelectionAck = false;
            selectionAckIndex = 0;
        } else if (sSelectionAckSequence != sequence) {
            sSelectionAckSequence = sequence;
            sSelectionAckCount = 1;
            firstSelectionAck = true;
            selectionAckIndex = 1;
        } else {
            firstSelectionAck = false;
            selectionAckIndex = ++sSelectionAckCount;
        }
        final boolean delayedSelectionChurn = tracksWrite && !firstSelectionAck
                && sinceWrite >= DELAYED_SELECTION_CHURN_MS;
        final boolean shouldLogSelectionUpdate = firstSelectionAck
                || !tracksWrite
                || (delayedSelectionChurn
                        && (selectionAckIndex <= SELECTION_CHURN_LOG_EARLY_COUNT
                                || selectionAckIndex % SELECTION_CHURN_LOG_EVERY == 0));
        if (!shouldLogSelectionUpdate) {
            return;
        }
        final int composingLength = composingSpanStart >= 0 && composingSpanEnd >= composingSpanStart
                ? composingSpanEnd - composingSpanStart : -1;
        log("stage=LatinIME.onUpdateSelection"
                + " seq=" + sequence
                + " sinceLastWriteMs=" + formatMillis(sinceWrite)
                + " selectionAck=" + (firstSelectionAck ? "first" : tracksWrite ? "churn" : "none")
                + " selectionAckIndex=" + selectionAckIndex
                + " delayedSelectionChurn=" + delayedSelectionChurn
                + " selDeltaStart=" + (newSelStart - oldSelStart)
                + " selDeltaEnd=" + (newSelEnd - oldSelEnd)
                + " newSelectionLength=" + Math.max(0, newSelEnd - newSelStart)
                + " composingLength=" + composingLength
                + " " + metadata(editorInfo, settingsValues, klipySearchActive, emojiSearchActive));
    }

    public static void lifecycle(final String event, final EditorInfo editorInfo,
            final SettingsValues settingsValues, final boolean klipySearchActive,
            final boolean emojiSearchActive) {
        if (!isEnabled()) {
            return;
        }
        log("lifecycle=" + event + " "
                + metadata(editorInfo, settingsValues, klipySearchActive, emojiSearchActive));
    }

    public static void invariant(final String reason, final boolean searchActive,
            final boolean mainViewMatches, final boolean pointerMatches,
            final String mainViewListener, final String pointerListener, final boolean logWhenOk) {
        if (!isEnabled()) {
            return;
        }
        final boolean ok = searchActive || (mainViewMatches && pointerMatches);
        if (!logWhenOk && ok) {
            return;
        }
        final String message = "invariant=" + reason
                + " ok=" + ok
                + " searchActive=" + searchActive
                + " mainViewMatches=" + mainViewMatches
                + " pointerMatches=" + pointerMatches
                + " mainViewListener=" + mainViewListener
                + " pointerListener=" + pointerListener;
        if (ok) {
            log(message);
        } else {
            Log.w(TAG, message);
        }
    }

    public static String metadata(final EditorInfo editorInfo, final SettingsValues settingsValues,
            final boolean klipySearchActive, final boolean emojiSearchActive) {
        final String packageName = editorInfo == null ? "null" : String.valueOf(editorInfo.packageName);
        final int inputType = editorInfo == null ? 0 : editorInfo.inputType;
        final int imeOptions = editorInfo == null ? 0 : editorInfo.imeOptions;
        final String previewMode = settingsValues == null ? "unknown" : settingsValues.mPreviewRenderMode;
        final boolean needsSuggestions = settingsValues != null && settingsValues.needsToLookupSuggestions();
        final boolean userSuggestions = settingsValues != null
                && settingsValues.isSuggestionsEnabledPerUserSettings();
        final boolean fieldSuggestions = settingsValues != null
                && settingsValues.mInputAttributes.mShouldShowSuggestions;
        final boolean autoCorrect = settingsValues != null && settingsValues.mAutoCorrectEnabled;
        final boolean sound = settingsValues != null && settingsValues.mSoundOn;
        final boolean haptic = settingsValues != null && settingsValues.mVibrateOn;
        return "pkg=" + packageName
                + " inputType=" + hex(inputType)
                + " imeOptions=" + hex(imeOptions)
                + " previewMode=" + previewMode
                + " textCommitExperiment=" + DebugFlags.TEXT_COMMIT_EXPERIMENT_MODE
                + " needsSuggestions=" + needsSuggestions
                + " userSuggestions=" + userSuggestions
                + " fieldSuggestions=" + fieldSuggestions
                + " autoCorrect=" + autoCorrect
                + " sound=" + sound
                + " haptic=" + haptic
                + " klipySearch=" + klipySearchActive
                + " emojiSearch=" + emojiSearchActive;
    }

    public static String eventKind(final Event event) {
        if (event == null) {
            return "null";
        }
        if (event.isGesture()) {
            return "gesture";
        }
        if (event.isSuggestionStripPress()) {
            return "suggestion";
        }
        if (event.isFunctionalKeyEvent()) {
            return "functional";
        }
        return codeKind(event.getCodePoint(), 0);
    }

    public static String codeKind(final int code, final int textLength) {
        if (textLength > 0 || code == KeyCode.MULTIPLE_CODE_POINTS) {
            return "multiText";
        }
        if (code == Constants.CODE_SPACE) {
            return "space";
        }
        if (code <= 0) {
            return "functional";
        }
        if (!Character.isValidCodePoint(code)) {
            return "codePoint";
        }
        final int type = Character.getType(code);
        if (Character.isLetter(code)) {
            return "letter";
        }
        if (Character.isDigit(code)) {
            return "digit";
        }
        if (type == Character.OTHER_SYMBOL || type == Character.SURROGATE) {
            return "symbol";
        }
        if (type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION) {
            return "punctuation";
        }
        return "codePoint";
    }

    public static String listenerName(final Object listener) {
        if (listener == null) {
            return "null";
        }
        return listener.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(listener));
    }

    private static long beginSequence(final long now) {
        final long sequence = NEXT_SEQUENCE.incrementAndGet();
        CURRENT_SEQUENCE.set(sequence);
        sLastPipelineSequence = sequence;
        sLastPipelineStartUptime = now;
        return sequence;
    }

    private static long sincePipelineStart(final long now, final long sequence) {
        return sequence != NO_SEQUENCE && sequence == sLastPipelineSequence
                && sLastPipelineStartUptime >= 0 ? now - sLastPipelineStartUptime : -1L;
    }

    private static String formatMillis(final long value) {
        return value < 0 ? "na" : Long.toString(value);
    }

    private static String appendExtra(final String extra) {
        return extra == null || extra.isEmpty() ? "" : " " + extra;
    }

    private static String hex(final int value) {
        return String.format(Locale.US, "0x%08x", value);
    }

    private static void markWriteBoundary(final long sequence, final long now) {
        sLastWriteSequence = sequence;
        sLastWriteEndUptime = now;
        sSelectionAckSequence = NO_SEQUENCE;
        sSelectionAckCount = 0;
    }

    private static void log(final String message) {
        Log.i(TAG, message);
    }
}
