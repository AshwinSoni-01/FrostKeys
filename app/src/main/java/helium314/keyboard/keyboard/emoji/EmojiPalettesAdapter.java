/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.settings.Settings;

final class EmojiPalettesAdapter extends RecyclerView.Adapter<EmojiPalettesAdapter.ViewHolder> {
    private static final String TAG = EmojiPalettesAdapter.class.getSimpleName();

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_PAGE = 1;

    private final EmojiViewCallback mEmojiViewCallback;
    private final EmojiCategory mEmojiCategory;
    private final List<AdapterItem> mItems = new ArrayList<>();

    abstract static class AdapterItem {
        final int categoryId;
        AdapterItem(int categoryId) { this.categoryId = categoryId; }
        abstract long getStableId();
    }

    static final class HeaderItem extends AdapterItem {
        final String title;
        HeaderItem(int categoryId, String title) {
            super(categoryId);
            this.title = title;
        }
        @Override
        long getStableId() {
            return (((long) categoryId) << 32) | 0xFFFFFFFFL;
        }
    }

    static final class PageItem extends AdapterItem {
        final int pagePosition;
        PageItem(int categoryId, int pagePosition) {
            super(categoryId);
            this.pagePosition = pagePosition;
        }
        @Override
        long getStableId() {
            return (((long) categoryId) << 32) | pagePosition;
        }
    }

    public EmojiPalettesAdapter(final EmojiCategory emojiCategory, final EmojiViewCallback emojiViewCallback) {
        mEmojiCategory = emojiCategory;
        mEmojiViewCallback = emojiViewCallback;
        setHasStableIds(true);
        updateItems();
    }

    public void updateItems() {
        mItems.clear();
        for (final EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
            final int categoryId = properties.mCategoryId;
            mItems.add(new HeaderItem(categoryId, mEmojiCategory.getAccessibilityDescription(categoryId)));
            final int pageCount = properties.getPageCount();
            for (int i = 0; i < pageCount; i++) {
                mItems.add(new PageItem(categoryId, i));
            }
        }
        notifyDataSetChanged();
    }

    public int getCategoryIdForPosition(int position) {
        if (position >= 0 && position < mItems.size()) {
            return mItems.get(position).categoryId;
        }
        return -1;
    }

    public int getHeaderPositionOfCategory(int categoryId) {
        for (int i = 0; i < mItems.size(); i++) {
            final AdapterItem item = mItems.get(i);
            if (item instanceof HeaderItem && item.categoryId == categoryId) {
                return i;
            }
        }
        return -1;
    }

    public int getFirstPagePositionOfCategory(int categoryId) {
        for (int i = 0; i < mItems.size(); i++) {
            final AdapterItem item = mItems.get(i);
            if (item instanceof PageItem && item.categoryId == categoryId) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public long getItemId(int position) {
        return mItems.get(position).getStableId();
    }

    @Override
    public int getItemViewType(int position) {
        return mItems.get(position) instanceof HeaderItem ? VIEW_TYPE_HEADER : VIEW_TYPE_PAGE;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_HEADER) {
            final TextView textView = new TextView(parent.getContext());
            final float density = parent.getContext().getResources().getDisplayMetrics().density;
            final int paddingH = (int) (12 * density);
            final int paddingV = (int) (8 * density);
            textView.setPadding(paddingH, paddingV, paddingH, paddingV);
            textView.setTextSize(14f);
            KeyboardTypeface.applyToTextView(textView, null, android.graphics.Typeface.DEFAULT_BOLD);
            textView.setTextColor(Settings.getValues().mColors.get(ColorType.EMOJI_KEY_TEXT));
            textView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new HeaderViewHolder(textView);
        } else {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            final EmojiPageKeyboardView keyboardView = (EmojiPageKeyboardView) inflater.inflate(
                    R.layout.emoji_keyboard_page, parent, false);
            keyboardView.setEmojiViewCallback(mEmojiViewCallback);
            keyboardView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new PageViewHolder(keyboardView);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final AdapterItem item = mItems.get(position);
        if (holder instanceof HeaderViewHolder) {
            ((HeaderViewHolder) holder).textView.setText(((HeaderItem) item).title);
        } else if (holder instanceof PageViewHolder) {
            final PageItem pageItem = (PageItem) item;
            final Keyboard keyboard = mEmojiCategory.getKeyboardFromAdapterPosition(pageItem.categoryId, pageItem.pagePosition);
            ((PageViewHolder) holder).getKeyboardView().setKeyboard(keyboard);
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        if (holder instanceof PageViewHolder) {
            final EmojiPageKeyboardView keyboardView = ((PageViewHolder) holder).getKeyboardView();
            keyboardView.releaseCurrentKey(false);
            keyboardView.deallocateMemory();
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        if (holder instanceof PageViewHolder) {
            final EmojiPageKeyboardView keyboardView = ((PageViewHolder) holder).getKeyboardView();
            keyboardView.releaseCurrentKey(false);
            keyboardView.deallocateMemory();
        }
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    abstract static class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(View v) { super(v); }
    }

    static final class HeaderViewHolder extends ViewHolder {
        final TextView textView;
        public HeaderViewHolder(TextView v) {
            super(v);
            textView = v;
        }
    }

    static final class PageViewHolder extends ViewHolder {
        final EmojiPageKeyboardView customView;
        public PageViewHolder(View v) {
            super(v);
            customView = (EmojiPageKeyboardView) v;
        }
        public EmojiPageKeyboardView getKeyboardView() {
            return customView;
        }
    }
}

