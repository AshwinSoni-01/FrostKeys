package helium314.keyboard.latin.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KlipyHistoryDaoTest {
    private lateinit var dao: KlipyHistoryDao

    @BeforeTest
    fun setUp() {
        dao = KlipyHistoryDao.getInstance(ApplicationProvider.getApplicationContext<Context>())
        clearKlipyHistory()
    }

    @AfterTest
    fun tearDown() {
        clearKlipyHistory()
    }

    @Test
    fun gifRecentHistoryIsCappedBelowPinnedRow() {
        repeat(KlipyHistoryDao.MAX_RECENT_GIF_HISTORY + 5) { index ->
            dao.addHistory("gif_$index", "https://example.com/$index.gif", KlipyHistoryDao.TYPE_GIF)
        }

        val history = dao.getRecentHistory(KlipyHistoryDao.TYPE_GIF)

        assertEquals(KlipyHistoryDao.MAX_RECENT_GIF_HISTORY, history.size)
        assertEquals("gif_${KlipyHistoryDao.MAX_RECENT_GIF_HISTORY + 4}", history.first().id)
        assertEquals("gif_5", history.last().id)
    }

    @Test
    fun stickerRecentHistoryIsCappedBelowPinnedRow() {
        repeat(KlipyHistoryDao.MAX_RECENT_STICKER_HISTORY + 5) { index ->
            dao.addHistory("sticker_$index", "https://example.com/$index.webp", KlipyHistoryDao.TYPE_STICKER)
        }

        val history = dao.getRecentHistory(KlipyHistoryDao.TYPE_STICKER)

        assertEquals(KlipyHistoryDao.MAX_RECENT_STICKER_HISTORY, history.size)
        assertEquals("sticker_${KlipyHistoryDao.MAX_RECENT_STICKER_HISTORY + 4}", history.first().id)
        assertEquals("sticker_5", history.last().id)
    }

    @Test
    fun reusingHistoryItemMovesItToTopWithoutGrowingHistory() {
        repeat(KlipyHistoryDao.MAX_RECENT_STICKER_HISTORY) { index ->
            dao.addHistory("sticker_$index", "https://example.com/$index.webp", KlipyHistoryDao.TYPE_STICKER)
        }

        dao.addHistory("sticker_7", "https://example.com/updated.webp", KlipyHistoryDao.TYPE_STICKER)

        val history = dao.getRecentHistory(KlipyHistoryDao.TYPE_STICKER)

        assertEquals(KlipyHistoryDao.MAX_RECENT_STICKER_HISTORY, history.size)
        assertEquals("sticker_7", history.first().id)
        assertEquals("https://example.com/updated.webp", history.first().url)
    }

    @Test
    fun pinnedHistoryIsCappedAndFifthPinDemotesOldestPinToRecentHistory() {
        repeat(KlipyHistoryDao.MAX_PINNED_HISTORY + 1) { index ->
            val id = "gif_$index"
            dao.addHistory(id, "https://example.com/$index.gif", KlipyHistoryDao.TYPE_GIF)
            dao.pinHistory(id, KlipyHistoryDao.TYPE_GIF)
            Thread.sleep(2)
        }

        val pinned = dao.getPinnedHistory(KlipyHistoryDao.TYPE_GIF)
        val recent = dao.getRecentHistory(KlipyHistoryDao.TYPE_GIF)

        assertEquals(KlipyHistoryDao.MAX_PINNED_HISTORY, pinned.size)
        assertEquals("gif_4", pinned.first().id)
        assertEquals("gif_1", pinned.last().id)
        assertEquals("gif_0", recent.first().id)
        assertEquals(false, recent.first().isPinned)
    }

    @Test
    fun combinedHistoryReturnsPinnedItemsBeforeRecentItems() {
        dao.addHistory("gif_recent", "https://example.com/recent.gif", KlipyHistoryDao.TYPE_GIF)
        dao.addHistory("gif_pinned", "https://example.com/pinned.gif", KlipyHistoryDao.TYPE_GIF)
        dao.pinHistory("gif_pinned", KlipyHistoryDao.TYPE_GIF)

        val history = dao.getHistory(KlipyHistoryDao.TYPE_GIF)

        assertEquals("gif_pinned", history.first().id)
        assertEquals(true, history.first().isPinned)
        assertEquals("gif_recent", history[1].id)
        assertEquals(false, history[1].isPinned)
    }

    @Test
    fun unpinMovesPinnedItemIntoRecentHistory() {
        dao.addHistory("sticker_1", "https://example.com/1.webp", KlipyHistoryDao.TYPE_STICKER)
        dao.pinHistory("sticker_1", KlipyHistoryDao.TYPE_STICKER)

        dao.unpinHistory("sticker_1", KlipyHistoryDao.TYPE_STICKER)

        assertEquals(emptyList(), dao.getPinnedHistory(KlipyHistoryDao.TYPE_STICKER))
        val recent = dao.getRecentHistory(KlipyHistoryDao.TYPE_STICKER)
        assertEquals("sticker_1", recent.first().id)
        assertEquals(false, recent.first().isPinned)
    }

    @Test
    fun deleteHistoryRemovesPinnedAndUnpinnedItems() {
        dao.addHistory("sticker_pinned", "https://example.com/pinned.webp", KlipyHistoryDao.TYPE_STICKER)
        dao.addHistory("sticker_recent", "https://example.com/recent.webp", KlipyHistoryDao.TYPE_STICKER)
        dao.pinHistory("sticker_pinned", KlipyHistoryDao.TYPE_STICKER)

        dao.deleteHistory("sticker_pinned", KlipyHistoryDao.TYPE_STICKER)
        dao.deleteHistory("sticker_recent", KlipyHistoryDao.TYPE_STICKER)

        assertEquals(emptyList(), dao.getPinnedHistory(KlipyHistoryDao.TYPE_STICKER))
        assertEquals(emptyList(), dao.getRecentHistory(KlipyHistoryDao.TYPE_STICKER))
    }

    @Test
    fun normalHistoryCapIsIndependentFromPinnedItems() {
        repeat(KlipyHistoryDao.MAX_PINNED_HISTORY) { index ->
            val id = "gif_pin_$index"
            dao.addHistory(id, "https://example.com/$id.gif", KlipyHistoryDao.TYPE_GIF)
            dao.pinHistory(id, KlipyHistoryDao.TYPE_GIF)
            Thread.sleep(2)
        }
        repeat(KlipyHistoryDao.MAX_RECENT_GIF_HISTORY + 4) { index ->
            dao.addHistory("gif_recent_$index", "https://example.com/recent_$index.gif", KlipyHistoryDao.TYPE_GIF)
        }

        assertEquals(KlipyHistoryDao.MAX_PINNED_HISTORY, dao.getPinnedHistory(KlipyHistoryDao.TYPE_GIF).size)
        assertEquals(KlipyHistoryDao.MAX_RECENT_GIF_HISTORY, dao.getRecentHistory(KlipyHistoryDao.TYPE_GIF).size)
        assertEquals(KlipyHistoryDao.MAX_GIF_HISTORY, dao.getHistory(KlipyHistoryDao.TYPE_GIF).size)
    }

    @Test
    fun reusingPinnedHistoryItemPreservesPinnedStateAndUpdatesContent() {
        dao.addHistory("gif_1", "https://example.com/old.gif", KlipyHistoryDao.TYPE_GIF)
        dao.pinHistory("gif_1", KlipyHistoryDao.TYPE_GIF)

        dao.addHistory("gif_1", "https://example.com/new.gif", KlipyHistoryDao.TYPE_GIF, previewUrl = "https://example.com/preview.gif")

        val pinned = dao.getPinnedHistory(KlipyHistoryDao.TYPE_GIF)
        assertEquals(1, pinned.size)
        assertEquals("gif_1", pinned.first().id)
        assertEquals("https://example.com/new.gif", pinned.first().url)
        assertEquals("https://example.com/preview.gif", pinned.first().previewUrl)
        assertEquals(true, pinned.first().isPinned)
    }

    private fun clearKlipyHistory() {
        dao.clearHistory(KlipyHistoryDao.TYPE_GIF)
        dao.clearHistory(KlipyHistoryDao.TYPE_STICKER)
    }
}
