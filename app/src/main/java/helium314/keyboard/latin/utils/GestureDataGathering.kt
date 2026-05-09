// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import android.text.InputType
import com.android.inputmethod.latin.BinaryDictionary
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SingleDictionaryFacilitator
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// functionality for gesture data gathering as part of the NLNet Project https://nlnet.nl/project/GestureTyping/
// will be removed once the project is finished

const val dictTestImeOption = "useTestDictionaryFacilitator,${BuildConfig.APPLICATION_ID}.${Constants.ImeOption.NO_FLOATING_GESTURE_PREVIEW}"

var gestureDataActiveFacilitator: SingleDictionaryFacilitator? = null

private val scope = CoroutineScope(Dispatchers.IO)

// class for storing relevant information
class WordData(
    val targetWord: String,
    val suggestions: SuggestionResults,
    val composedData: ComposedData,
    val ngramContext: NgramContext,
    val keyboard: Keyboard,
    val inputStyle: Int,
    val activeMode: Boolean,
) {
    // keyboard is not immutable, so better store potentially relevant information immediately
    private val keys = keyboard.sortedKeys
    private val height = keyboard.mOccupiedHeight
    private val width = keyboard.mOccupiedWidth

    // if contacts dict is used we keep this information
    private val dictionariesInSuggestions = suggestions.map { it.mSourceDict }.toSet()
    private val packageName = keyboard.mId.mEditorInfo.packageName

    private val timestamp = System.currentTimeMillis()

    fun save(context: Context) {
        GestureDataGatheringSettings.onTrySaveData(context.prefs())
        if (!isSavingOk(context))
            return
        val dao = GestureDataDao.getInstance(context) ?: return

        val keyboardInfo = KeyboardInfo(
            width, // baseHeight is without padding, but coordinates include padding
            height,
            keys.map {
                KeyInfo(
                    it.x, it.width, it.y, it.height,
                    it.outputText ?: if (it.code > 0) StringUtils.newSingleCodePointString(it.code) else "",
                    it.popupKeys.orEmpty().map { popup ->
                        popup.mOutputText ?: if (popup.mCode > 0) StringUtils.newSingleCodePointString(popup.mCode) else ""
                    }
                )
            }
        )
        val filteredSuggestions = mutableListOf<SuggestedWords.SuggestedWordInfo>()
        for (word in suggestions) { // suggestions are sorted with highest score first
            if (word.mWord == targetWord) {
                // always add the targetWord if we have it
                filteredSuggestions.add(word)
                continue
            }
            if (word.mSourceDict.mDictType == Dictionary.TYPE_CONTACTS
                || suggestions.any { it.mWord == word.mWord && it.mSourceDict.mDictType == Dictionary.TYPE_CONTACTS })
                continue // never store contacts (might be in user history too)
            // for the personal dictionary we rely on the ignore list
            if (word.mScore < 0 && filteredSuggestions.size > 5)
                continue // no need to add bad matches
            if (filteredSuggestions.any { it.mWord == word.mWord })
                continue // only one occurrence per word
            if (filteredSuggestions.size > 18) // user sees 18 suggestions at most
            // todo: some data contains more suggestions than allowed -> there is something broken here!
                continue // should be enough
            filteredSuggestions.add(word)
        }
        val data = GestureData(
            context.getString(R.string.english_ime_name) + " " + BuildConfig.VERSION_NAME,
            if (!context.protectedPrefs().contains(Settings.PREF_LIBRARY_CHECKSUM)) null
                else context.protectedPrefs().getString(Settings.PREF_LIBRARY_CHECKSUM, "") == JniUtils.expectedDefaultChecksum(),
            targetWord,
            dictionariesInSuggestions.map {
                val hash = (it as? BinaryDictionary)?.hash ?: (it as? ReadOnlyBinaryDictionary)?.hash
                DictInfo(hash, it.mDictType, it.mLocale?.toLanguageTag())
            },
            filteredSuggestions.map { Suggestion(it.mWord, it.mScore) },
            PointerData.fromPointers(composedData.mInputPointers),
            keyboardInfo,
            activeMode,
            null
        )
        scope.launch { dao.add(data, timestamp) }
        GestureDataGatheringSettings.informAboutTooManyPassiveModeWords(activeMode, context, dao)
    }

    // find when we should NOT save
    private fun isSavingOk(context: Context): Boolean {
        if (inputStyle != SuggestedWords.INPUT_STYLE_TAIL_BATCH)
            return false
        if (activeMode && dictionariesInSuggestions.size == 1)
            return true // active mode should be fine, the size check is just an addition in case there is a bug that sets the wrong mode or dictionary facilitator
        if (Settings.getValues().mIncognitoModeEnabled)
            return false // don't save in incognito mode
        if (packageName in GestureDataGatheringSettings.getAppIgnoreList(context))
            return false // package ignored
        val inputAttributes = InputAttributes(keyboard.mId.mEditorInfo, false, "")
        val isEmailField = InputTypeUtils.isEmailVariation(inputAttributes.mInputType and InputType.TYPE_MASK_VARIATION)
        if (inputAttributes.mIsPasswordField || inputAttributes.mNoLearning || isEmailField)
            return false // probably some more inputAttributes to consider
        val ignoreWords = GestureDataGatheringSettings.getWordIgnoreList(context)
        // how to deal with the ignore list?
        // check targetWord and first 5 suggestions?
        // or check only what is in the actually saved suggestions?
        if (targetWord in ignoreWords || suggestions.take(5).any { it.word in ignoreWords })
            return false
        if (suggestions.first().mSourceDict.mDictType == Dictionary.TYPE_CONTACTS)
            return false
        return true
    }
}

data class GestureDataInfo(val id: Long, val targetWord: String, val timestamp: Long, val exported: Boolean, val activeMode: Boolean)

@Serializable
data class GestureData(
    val application: String,
    val knownLibrary: Boolean?,
    val targetWord: String?, // this will be tricky for active gathering if user corrects the word
    val dictionaries: List<DictInfo>,
    val suggestions: List<Suggestion>,
    val gesture: List<PointerData>,
    val keyboardInfo: KeyboardInfo,
    val activeMode: Boolean,
    val uuid: String?
)

// hash is only available for dictionaries from .dict files
// language can be null (but should not be)
@Serializable
data class DictInfo(val hash: String?, val type: String, val language: String?)

@Serializable
data class Suggestion(val word: String, val score: Int)

@Serializable
data class PointerData(val id: Int, val x: Int, val y: Int, val millis: Int) {
    companion object {
        fun fromPointers(pointers: InputPointers): List<PointerData> {
            val result = mutableListOf<PointerData>()
            for (i in 0..<pointers.pointerSize) {
                result.add(PointerData(
                    pointers.pointerIds[i],
                    pointers.xCoordinates[i],
                    pointers.yCoordinates[i],
                    pointers.times[i]
                ))
            }
            return result
        }
    }
}

// the old gesture typing library only works with code, not with arbitrary text
// but we take the output text (usually still a single codepoint) because we'd like to change this
@Serializable
data class KeyInfo(val left: Int, val width: Int, val top: Int, val height: Int, val value: String, val alts: List<String>)

@Serializable
data class KeyboardInfo(val width: Int, val height: Int, val keys: List<KeyInfo>)
