package helium314.keyboard

import helium314.keyboard.latin.utils.ResourceUtils
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class ResourceUtilsTest {
    @Test fun keyboardHeightScaleOnlyAppliesToBurmese() {
        assertEquals(1250, ResourceUtils.getKeyboardHeightForLocale(1000, Locale("my")))
        assertEquals(1250, ResourceUtils.getKeyboardHeightForLocale(1000, Locale.forLanguageTag("my-MM")))
        assertEquals(1000, ResourceUtils.getKeyboardHeightForLocale(1000, Locale.US))
        assertEquals(1000, ResourceUtils.getKeyboardHeightForLocale(1000, null))
    }
}
