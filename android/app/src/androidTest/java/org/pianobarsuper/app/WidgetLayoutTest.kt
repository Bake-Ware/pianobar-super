package org.pianobarsuper.app

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app icon sits in the widget's top-right corner without covering anything, at phone and head-unit sizes. */
@RunWith(AndroidJUnit4::class)
class WidgetLayoutTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun check(layout: Int, widthDp: Int, heightDp: Int) {
        val density = context.resources.displayMetrics.density
        val root = LayoutInflater.from(context).inflate(layout, null) as ViewGroup
        root.findViewById<TextView>(R.id.widget_title).text = "A very long song title that has to ellipsize somewhere near the icon"
        root.findViewById<TextView>(R.id.widget_artist).text = "An artist with a long name as well"
        root.findViewById<TextView>(R.id.widget_station).text = "Station"
        root.findViewById<TextView>(R.id.widget_status).text = "Listening on this phone"
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val icon = bounds(root.findViewById(R.id.widget_icon))
        val label = "layout $widthDp×$heightDp dp, icon $icon"
        assertTrue("$label: in the top-right quarter", icon.width() > 0 && icon.right > width * 3 / 4 && icon.bottom < height / 2 + icon.height())
        for (id in listOf(R.id.widget_art, R.id.widget_title, R.id.widget_artist, R.id.widget_station, R.id.widget_status,
                R.id.widget_listen, R.id.widget_love, R.id.widget_next)) {
            val view = root.findViewById<View>(id)
            if (view.visibility != View.VISIBLE || view.width == 0) continue
            assertFalse("$label overlaps ${context.resources.getResourceEntryName(id)} ${bounds(view)}", Rect.intersects(icon, bounds(view)))
        }
    }

    private fun bounds(view: View): Rect {
        var x = 0; var y = 0
        var current: View? = view
        while (current != null) { x += current.left; y += current.top; current = current.parent as? View }
        return Rect(x, y, x + view.width, y + view.height)
    }

    @Test fun phoneSizes() {
        check(R.layout.widget_now_playing_compact, 320, 64)
        check(R.layout.widget_now_playing_compact, 180, 40)
        check(R.layout.widget_now_playing, 250, 110)
        check(R.layout.widget_now_playing, 360, 180)
    }

    @Test fun headUnitSizes() {
        check(R.layout.widget_now_playing, 720, 240)
        check(R.layout.widget_now_playing, 960, 400)
    }
}
