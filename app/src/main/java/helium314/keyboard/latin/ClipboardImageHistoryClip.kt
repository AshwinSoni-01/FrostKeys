// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.net.Uri

object ClipboardImageHistoryClip {
    private const val PREFIX = "\u0001image\t"

    data class ImageClip(val uri: Uri, val mimeType: String, val label: String)

    fun decode(text: String): ImageClip? {
        if (!text.startsWith(PREFIX)) return null
        val parts = text.removePrefix(PREFIX).split('\t', limit = 3)
        if (parts.size != 3) return null
        return ImageClip(Uri.parse(parts[0]), parts[1], parts[2])
    }

    fun encode(uri: Uri, mimeType: String, label: String): String {
        val safeLabel = label.replace('\t', ' ').replace('\n', ' ')
        return "$PREFIX$uri\t$mimeType\t$safeLabel"
    }
}
