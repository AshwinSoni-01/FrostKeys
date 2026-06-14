/*
 * Copyright (C) 2026
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin;

import android.os.Build;
import android.util.Log;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class InputConnectionWithBufferingWrapper extends InputConnectionWrapper {
    private static final String TAG = "BufferedInputConnection";
    private static final boolean DEBUG = false;

    public interface InputCommand {
    }

    public static class Commit implements InputCommand {
        public final String text;

        public Commit(@NonNull String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return "Commit(" + text + ")";
        }
    }

    public static class Delete implements InputCommand {
        public final int before;
        public final int after;

        public Delete(int before, int after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public String toString() {
            return "Delete(" + before + ", " + after + ")";
        }
    }

    public static class SetComposingRegion implements InputCommand {
        public final int start;
        public final int end;

        public SetComposingRegion(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return "SetComposingRegion(" + start + ", " + end + ")";
        }
    }

    private final List<InputCommand> commandQueue = new ArrayList<>();

    public InputConnectionWithBufferingWrapper(@NonNull InputConnection target) {
        super(target, true);
    }

    private boolean canMerge(List<InputCommand> commands) {
        if ("samsung".equalsIgnoreCase(Build.MANUFACTURER)) {
            try {
                if (RichInputMethodManager.getInstance().getCurrentSubtype().isRtlSubtype()) {
                    for (InputCommand cmd : commands) {
                        if (cmd instanceof Commit) {
                            String text = ((Commit) cmd).text;
                            if ("(".equals(text) || ")".equals(text) || "[".equals(text) || "]".equals(text)
                                    || "<".equals(text) || ">".equals(text) || "{".equals(text) || "}".equals(text)) {
                                return false;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return true;
    }

    private List<InputCommand> merge(List<InputCommand> commands) {
        if (!canMerge(commands)) {
            return new ArrayList<>(commands);
        }

        StringBuilder text = new StringBuilder();
        int deletedAmount = 0;
        int deletedAfterAmount = 0;
        SetComposingRegion lastSetComposingRegion = null;

        for (InputCommand cmd : commands) {
            if (cmd instanceof Commit) {
                text.append(((Commit) cmd).text);
            } else if (cmd instanceof Delete) {
                Delete del = (Delete) cmd;
                int keep = text.length() - del.before;
                if (keep > 0) {
                    text.setLength(keep);
                } else {
                    text.setLength(0);
                    deletedAmount -= keep;
                }
                deletedAfterAmount += del.after;
            } else if (cmd instanceof SetComposingRegion) {
                lastSetComposingRegion = (SetComposingRegion) cmd;
            }
        }

        List<InputCommand> merged = new ArrayList<>();
        if (deletedAmount > 0 && deletedAfterAmount > 0 && text.length() == 0) {
            merged.add(new Delete(deletedAmount, deletedAfterAmount));
        } else {
            if (deletedAmount > 0) {
                merged.add(new Delete(deletedAmount, 0));
            }
            if (text.length() > 0) {
                merged.add(new Commit(text.toString()));
            }
            if (deletedAfterAmount > 0) {
                merged.add(new Delete(0, deletedAfterAmount));
            }
        }

        if (lastSetComposingRegion != null) {
            merged.add(lastSetComposingRegion);
        }

        return merged;
    }

    private String applyBefore(String beforeTxt) {
        StringBuilder result = new StringBuilder(beforeTxt);
        List<InputCommand> queueCopy;
        synchronized (commandQueue) {
            queueCopy = new ArrayList<>(commandQueue);
        }
        for (InputCommand cmd : queueCopy) {
            if (cmd instanceof Commit) {
                result.append(((Commit) cmd).text);
            } else if (cmd instanceof Delete) {
                Delete del = (Delete) cmd;
                int deleteChars = Math.max(0, Math.min(del.before, result.length()));
                result.setLength(result.length() - deleteChars);
            }
        }
        return result.toString();
    }

    private String applyAfter(String afterTxt) {
        String result = afterTxt;
        List<InputCommand> queueCopy;
        synchronized (commandQueue) {
            queueCopy = new ArrayList<>(commandQueue);
        }
        for (InputCommand cmd : queueCopy) {
            if (cmd instanceof Delete) {
                Delete del = (Delete) cmd;
                int skipChars = Math.max(0, Math.min(del.after, result.length()));
                result = result.substring(skipChars);
            }
        }
        return result;
    }

    private int extraHeadroom() {
        return 16;
    }

    @Override
    public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
        if (text == null) return true;
        if (DEBUG) Log.d(TAG, "commit (" + text + ")");
        synchronized (commandQueue) {
            commandQueue.add(new Commit(text.toString()));
        }
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (beforeLength < 0 || afterLength < 0) {
            return false;
        }
        if (DEBUG) Log.d(TAG, "deleteSurroundingText (" + beforeLength + ", " + afterLength + ")");
        synchronized (commandQueue) {
            commandQueue.add(new Delete(beforeLength, afterLength));
        }
        return true;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        CharSequence base = super.getTextBeforeCursor(n + extraHeadroom(), flags);
        if (base == null) return null;
        String applied = applyBefore(base.toString());
        if (applied.length() > n) {
            return applied.substring(applied.length() - n);
        }
        return applied;
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        CharSequence base = super.getTextAfterCursor(n + extraHeadroom(), flags);
        if (base == null) return null;
        String applied = applyAfter(base.toString());
        if (applied.length() > n) {
            return applied.substring(0, n);
        }
        return applied;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (DEBUG) Log.d(TAG, "setComposingRegion (" + start + ", " + end + ")");
        synchronized (commandQueue) {
            commandQueue.add(new SetComposingRegion(start, end));
        }
        return true;
    }

    public void send() {
        List<InputCommand> queueCopy;
        synchronized (commandQueue) {
            if (commandQueue.isEmpty()) {
                return;
            }
            queueCopy = new ArrayList<>(commandQueue);
            commandQueue.clear();
        }

        List<InputCommand> mergedList = merge(queueCopy);
        if (DEBUG) {
            Log.d(TAG, "Command queue: " + queueCopy + ", merged: " + mergedList);
        }

        if (alternativeApply(mergedList)) {
            return;
        }

        if (mergedList.size() > 1) {
            super.beginBatchEdit();
        }
        try {
            for (InputCommand cmd : mergedList) {
                if (cmd instanceof Commit) {
                    super.commitText(((Commit) cmd).text, 1);
                } else if (cmd instanceof Delete) {
                    super.deleteSurroundingText(((Delete) cmd).before, ((Delete) cmd).after);
                } else if (cmd instanceof SetComposingRegion) {
                    super.setComposingRegion(((SetComposingRegion) cmd).start, ((SetComposingRegion) cmd).end);
                }
            }
        } finally {
            if (mergedList.size() > 1) {
                super.endBatchEdit();
            }
        }
    }

    private boolean alternativeApply(List<InputCommand> mergedList) {
        if (mergedList.size() == 2
                && mergedList.get(0) instanceof Commit
                && mergedList.get(1) instanceof Delete) {
            Commit commit = (Commit) mergedList.get(0);
            Delete delete = (Delete) mergedList.get(1);
            if (" ".equals(commit.text) && delete.before == 0 && delete.after == 1) {
                CharSequence after = super.getTextAfterCursor(1, 0);
                if (after != null && " ".equals(after.toString())) {
                    super.finishComposingText();
                    int[] selection = extractSelection(this);
                    if (selection[0] != -1 && selection[0] == selection[1]) {
                        if (DEBUG) {
                            Log.d(TAG, "alternative application applied");
                        }
                        super.setSelection(selection[0] + 1, selection[0] + 1);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int[] extractSelection(InputConnection ic) {
        try {
            ExtractedTextRequest req = new ExtractedTextRequest();
            ExtractedText et = ic.getExtractedText(req, 0);
            if (et != null) {
                return new int[]{et.selectionStart, et.selectionEnd};
            }
        } catch (Exception e) {
            // Ignore
        }
        return new int[]{-1, -1};
    }
}
