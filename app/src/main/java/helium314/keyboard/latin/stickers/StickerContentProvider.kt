package helium314.keyboard.latin.stickers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher
    private lateinit var authority: String

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = ctx.packageName + ".stickercontentprovider"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", METADATA)
            addURI(authority, "metadata/*", METADATA_ID)
            addURI(authority, "stickers/*", STICKERS)
            addURI(authority, "stickers/*/*", STICKERS_FILE)
            addURI(authority, "stickers/asset/*/*", STICKERS_ASSET)
        }
        Log.d(TAG, "onCreate: authority initialized as $authority")
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val match = matcher.match(uri)
        Log.d(TAG, "query: $uri, match: $match")
        return when (match) {
            METADATA -> getMetadataCursor()
            METADATA_ID -> {
                val identifier = uri.lastPathSegment ?: return null
                getPackMetadataCursor(identifier)
            }
            STICKERS -> {
                val identifier = uri.lastPathSegment ?: return null
                getStickersCursor(identifier)
            }
            else -> null
        }
    }

    private fun getMetadataCursor(): Cursor {
        val columns = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_tray_icon_file",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "android_play_store_link",
            "ios_app_store_link",
            "animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)

        // Fetch dynamic packs from internal storage
        val stickersDir = File(context!!.filesDir, "stickers")
        if (stickersDir.exists() && stickersDir.isDirectory) {
            stickersDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    val identifier = file.name
                    cursor.addRow(arrayOf<Any>(
                        identifier,
                        "KBoard Pack $identifier",
                        "KBoard",
                        "tray_icon.png",
                        "publisher@example.com",
                        "https://example.com",
                        "https://example.com/privacy",
                        "https://example.com/license",
                        "https://play.google.com/store/apps/details?id=${context!!.packageName}",
                        "https://example.com/ios",
                        1 // 1 for animated
                    ))
                }
            }
        }
        return cursor
    }

    private fun getPackMetadataCursor(identifier: String): Cursor {
        val columns = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_tray_icon_file",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "android_play_store_link",
            "ios_app_store_link",
            "animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)
        val stickersDir = File(context!!.filesDir, "stickers/$identifier")
        if (stickersDir.exists() && stickersDir.isDirectory) {
            cursor.addRow(arrayOf<Any>(
                identifier,
                "KBoard Pack $identifier",
                "KBoard",
                "tray_icon.png",
                "publisher@example.com",
                "https://example.com",
                "https://example.com/privacy",
                "https://example.com/license",
                "https://play.google.com/store/apps/details?id=${context!!.packageName}",
                "https://example.com/ios",
                1
            ))
        }
        return cursor
    }

    private fun getStickersCursor(identifier: String): Cursor {
        val columns = arrayOf("sticker_file_name", "sticker_emoji")
        val cursor = MatrixCursor(columns)
        val packDir = File(context!!.filesDir, "stickers/$identifier")
        if (packDir.exists() && packDir.isDirectory) {
            packDir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension == "webp" && file.name != "tray_icon.png") {
                    cursor.addRow(arrayOf(file.name, "😊"))
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String? {
        val match = matcher.match(uri)
        return when (match) {
            METADATA -> "vnd.android.cursor.dir/metadata"
            METADATA_ID -> "vnd.android.cursor.item/metadata"
            STICKERS -> "vnd.android.cursor.dir/stickers"
            STICKERS_FILE,
            STICKERS_ASSET -> "image/webp"
            else -> null
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val match = matcher.match(uri)
        if (match == STICKERS_ASSET || match == STICKERS_FILE) {
            val segments = uri.pathSegments
            val identifier = segments[segments.size - 2]
            val fileName = segments.last()
            val file = File(context!!.filesDir, "stickers/$identifier/$fileName")
            if (file.exists()) {
                return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
        }
        throw FileNotFoundException("Sticker not found: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "StickerProvider"
        private const val METADATA = 1
        private const val METADATA_ID = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4
        private const val STICKERS_FILE = 5
    }
}
