package helium314.keyboard.latin.stickers

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.io.FileOutputStream

object StickerUtils {
    private const val TAG = "StickerUtils"

    fun sendStickerToWhatsApp(context: Context, stickerUri: Uri) {
        val contentResolver = context.contentResolver
        val tempFile = File(context.cacheDir, "temp_sticker.webp")
        
        try {
            // 1. Copy the file from URI to a temp file to check constraints
            contentResolver.openInputStream(stickerUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for URI: $stickerUri")
                return
            }
            
            // Verify constraints
            val fileSizeInKB = tempFile.length() / 1024
            if (fileSizeInKB > 500) {
                Log.e(TAG, "File size exceeds 500KB: $fileSizeInKB KB")
                tempFile.delete()
                return
            }
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)
            if (options.outWidth != 512 || options.outHeight != 512) {
                Log.e(TAG, "Invalid dimensions: ${options.outWidth}x${options.outHeight}. Expected 512x512.")
                tempFile.delete()
                return
            }
            
            // 2. Prepare the dedicated internal directory
            val packId = "dynamic_pack"
            val packDir = File(context.filesDir, "stickers/$packId")
            if (!packDir.exists()) {
                packDir.mkdirs()
            }
            
            // Copy to target directory
            val targetFile = File(packDir, "sticker_${System.currentTimeMillis()}.webp")
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            
            // Ensure a tray icon exists. WhatsApp requires a tray icon (96x96 PNG or WebP).
            val trayFile = File(packDir, "tray_icon.png")
            if (!trayFile.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(targetFile.absolutePath)
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, 96, 96, true)
                    FileOutputStream(trayFile).use { out ->
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create tray icon", e)
                }
            }
            
            // 3. Fire the Intent
            val authority = context.packageName + ".stickercontentprovider"
            val intent = Intent().apply {
                action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
                putExtra("sticker_pack_id", packId)
                putExtra("sticker_pack_authority", authority)
                putExtra("sticker_pack_name", "KBoard Dynamic Pack")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            
            context.startActivity(intent)
            Log.d(TAG, "Intent fired to add sticker pack to WhatsApp")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending sticker to WhatsApp", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
