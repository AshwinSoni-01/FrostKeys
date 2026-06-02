// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardImageHistoryClipTest {
    @Test
    fun imageHistoryClipRoundTripsAndSanitizesLabel() {
        val uri = Uri.parse("content://com.orion.kboard.fileprovider/cache/clipboard/clip_1_a.png")
        val encoded = ClipboardHistoryManager.encodeImageHistoryClip(uri, "image/png", "Screenshot\tone\n")
        val decoded = ClipboardHistoryManager.decodeImageHistoryClip(encoded)

        assertEquals(uri, decoded?.uri)
        assertEquals("image/png", decoded?.mimeType)
        assertEquals("Screenshot one ", decoded?.label)
    }

    @Test
    fun imageMimeTypesAreNormalizedWithMatchingExtensions() {
        val jpeg = ClipboardHistoryManager.normalizeImageMimeType("IMAGE/JPG; charset=binary")
        val png = ClipboardHistoryManager.normalizeImageMimeType("image/x-png")
        val webp = ClipboardHistoryManager.normalizeImageMimeType("image/x-webp")
        val heic = ClipboardHistoryManager.normalizeImageMimeType("image/heic-sequence")
        val fallback = ClipboardHistoryManager.normalizeImageMimeType("text/plain")

        assertEquals("image/jpeg", jpeg.mimeType)
        assertEquals("jpg", jpeg.extension)
        assertEquals("image/png", png.mimeType)
        assertEquals("png", png.extension)
        assertEquals("image/webp", webp.mimeType)
        assertEquals("webp", webp.extension)
        assertEquals("image/heic", heic.mimeType)
        assertEquals("heic", heic.extension)
        assertEquals("image/png", fallback.mimeType)
        assertEquals("png", fallback.extension)
    }

    @Test
    fun cacheNamesAreStableAndUniqueForScreenshotsWithSameTimestamp() {
        val firstUri = Uri.parse("content://media/external/images/media/100")
        val secondUri = Uri.parse("content://media/external/images/media/101")
        val timestamp = 1_771_111_111_000L

        val firstName = ClipboardHistoryManager.cacheFileNameForSource(firstUri, timestamp, "image/png")
        val firstNameAgain = ClipboardHistoryManager.cacheFileNameForSource(firstUri, timestamp, "image/png")
        val secondName = ClipboardHistoryManager.cacheFileNameForSource(secondUri, timestamp, "image/png")

        assertEquals(firstName, firstNameAgain)
        assertNotEquals(firstName, secondName)
        assertTrue(firstName.startsWith("clip_${timestamp}_"))
        assertTrue(firstName.endsWith(".png"))
    }

    @Test
    fun ownClipboardCacheUriDetectionAcceptsOnlyClipboardCacheProviderPaths() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val valid = Uri.parse("content://${context.packageName}.fileprovider/cache/clipboard/clip_1_a.png")
        val wrongAuthority = Uri.parse("content://other.fileprovider/cache/clipboard/clip_1_a.png")
        val wrongFolder = Uri.parse("content://${context.packageName}.fileprovider/cache/images/clip_1_a.png")
        val traversal = Uri.parse("content://${context.packageName}.fileprovider/cache/clipboard/../secret.png")

        assertTrue(ClipboardHistoryManager.isOwnClipboardCacheUri(context, valid))
        assertFalse(ClipboardHistoryManager.isOwnClipboardCacheUri(context, wrongAuthority))
        assertFalse(ClipboardHistoryManager.isOwnClipboardCacheUri(context, wrongFolder))
        assertFalse(ClipboardHistoryManager.isOwnClipboardCacheUri(context, traversal))
    }
}
