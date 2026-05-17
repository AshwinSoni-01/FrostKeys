package helium314.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class StickerIntegrityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBinaryCopyIntegrity() {
        val srcFile = tempFolder.newFile("src.webp")
        val content = "fake webp content with ANIM chunks".toByteArray()
        srcFile.writeBytes(content)

        val dstFile = File(tempFolder.root, "dst.webp")
        srcFile.inputStream().use { input ->
            dstFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val srcSha = sha256Hex(srcFile)
        val dstSha = sha256Hex(dstFile)

        assertEquals("SHA-256 should be identical after copy", srcSha, dstSha)
        assertEquals("Content should be identical", content.size.toLong(), dstFile.length())
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
