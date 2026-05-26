// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SparkleDustViewTest {
    @Test
    fun startedViewDrawsVisibleDust() {
        val view = createLaidOutDustView()

        view.startDustAnimation()

        val bitmap = renderToBitmap(view)
        assertTrue(
            bitmap.hasPaintedPixels(),
            "Expected visible dust pixels; shown=${view.isShown}, width=${view.width}, height=${view.height}, alpha=${view.alpha}"
        )
    }

    @Test
    fun stoppedViewDoesNotDrawDust() {
        val view = createLaidOutDustView()

        view.startDustAnimation()
        view.stopDustAnimation()

        assertFalse(renderToBitmap(view).hasPaintedPixels())
    }

    private fun createLaidOutDustView(): SparkleDustView {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = SparkleDustView(activity).apply {
            alpha = 1f
            visibility = View.VISIBLE
        }
        activity.setContentView(
            view,
            ViewGroup.LayoutParams(TEST_WIDTH, TEST_HEIGHT)
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(TEST_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(TEST_HEIGHT, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, TEST_WIDTH, TEST_HEIGHT)
        controller.visible()
        return view
    }

    private fun renderToBitmap(view: View): Bitmap {
        return Bitmap.createBitmap(TEST_WIDTH, TEST_HEIGHT, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun Bitmap.hasPaintedPixels(): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getPixel(x, y) ushr 24 != 0) {
                    return true
                }
            }
        }
        return false
    }

    private companion object {
        private const val TEST_WIDTH = 360
        private const val TEST_HEIGHT = 200
    }
}
