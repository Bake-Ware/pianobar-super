package org.pianobarsuper.app.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.R
import org.pianobarsuper.app.playback.Playback
import org.pianobarsuper.app.ui.MainActivity

/** The home-screen widget: now playing, with listen on this phone, love and next. */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NowPlayingWidgets.update(context, ids)
        refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        NowPlayingWidgets.update(context, intArrayOf(id))

    /** Without the app open nothing polls the server, so fetch the current song once. */
    private fun refresh(context: Context) {
        val app = context.applicationContext as PianobarApp
        val pending = goAsync()
        app.repo.scope.launch { try { app.repo.refreshOnce() } finally { pending.finish() } }
    }

}

/** Next and Love from the widget. Not exported: only the widget's own buttons reach it. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = when (intent.action) {
            NEXT -> "act_songnext"
            LOVE -> "act_songlove"
            else -> return
        }
        val app = context.applicationContext as PianobarApp
        val pending = goAsync()
        app.repo.scope.launch {
            try {
                app.repo.command(buildJsonObject { put("action", action) })
                app.repo.refreshOnce()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val NEXT = "org.pianobarsuper.app.widget.NEXT"
        const val LOVE = "org.pianobarsuper.app.widget.LOVE"
    }
}

/**
 * Starts or stops listening on this phone from the widget. Android only lets
 * a visible app start playback, so this invisible activity does it and closes.
 */
class WidgetListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PianobarApp
        when {
            app.repo.store.origin == null -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Playback.state.value.listening -> Playback.stopListening(this)
            else -> Playback.listen(this)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

object NowPlayingWidgets {
    private var content: WidgetContent? = null
    private var artKey = ""
    private var art: Bitmap? = null

    /** Follow the app's state for as long as the process lives. */
    fun init(app: PianobarApp) {
        app.repo.scope.launch {
            combine(app.repo.snapshot, Playback.state, app.repo.connection) { snapshot, phone, connection ->
                WidgetContent.from(snapshot, phone, connection)
            }.distinctUntilChanged().collect { next ->
                content = next
                update(app)
                if (next.art != artKey) loadArt(app, next.art)
            }
        }
    }

    private suspend fun loadArt(app: PianobarApp, key: String) {
        artKey = key
        art = null
        val url = app.repo.artUrl(key) ?: return update(app)
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val api = app.repo.api.value ?: return@withContext null
                api.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code != 200) return@use null
                    val bytes = response.body?.bytes()?.takeIf { it.size < 8 * 1024 * 1024 } ?: return@use null
                    rounded(decode(bytes, 320) ?: return@use null)
                }
            } catch (e: Exception) { null }
        }
        if (artKey == key) { art = bitmap; update(app) }
    }

    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        return if (side > target) square.scale(target, target) else square
    }

    private fun rounded(source: Bitmap): Bitmap {
        val output = createBitmap(source.width, source.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val radius = source.width * .07f
        Canvas(output).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        return output
    }

    fun update(context: Context, ids: IntArray? = null) {
        val manager = AppWidgetManager.getInstance(context)
        val all = ids ?: manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
        if (all.isEmpty()) return
        val app = context.applicationContext as PianobarApp
        val shown = content ?: WidgetContent.from(app.repo.snapshot.value, Playback.state.value, app.repo.connection.value)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        for (id in all) {
            // Launchers report the portrait size as min width × max height, landscape as max width × min height.
            val options = manager.getAppWidgetOptions(id)
            val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: 250
            val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: 110
            manager.updateAppWidget(id, views(context, shown, compact = height < 100, widthDp = width, heightDp = height))
        }
    }

    private fun views(context: Context, content: WidgetContent, compact: Boolean, widthDp: Int, heightDp: Int): RemoteViews {
        val views = RemoteViews(context.packageName, if (compact) R.layout.widget_now_playing_compact else R.layout.widget_now_playing)
        views.setTextViewText(R.id.widget_title, content.title)
        views.setTextViewText(R.id.widget_artist, content.artist)
        views.setTextViewText(R.id.widget_station, content.station)
        views.setViewVisibility(R.id.widget_station, if (compact || content.station.isEmpty()) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.widget_status, content.status)
        views.setViewVisibility(R.id.widget_status, if (content.status.isEmpty()) View.GONE else View.VISIBLE)
        if (!compact) {
            // Larger text when the widget is tall, as on the car's head unit.
            val scale = (heightDp / 130f).coerceIn(1f, 1.6f)
            views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 18 * scale)
            views.setTextViewTextSize(R.id.widget_artist, TypedValue.COMPLEX_UNIT_SP, 14 * scale)
            views.setTextViewTextSize(R.id.widget_station, TypedValue.COMPLEX_UNIT_SP, 12 * scale)
            views.setTextViewTextSize(R.id.widget_status, TypedValue.COMPLEX_UNIT_SP, 12 * scale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutWidth(R.id.widget_icon, 18 * scale, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.widget_icon, 18 * scale, TypedValue.COMPLEX_UNIT_DIP)
            }
        }
        // Square art as tall as the widget, but never crowding out the text and buttons.
        val density = context.resources.displayMetrics.density
        val artDp = minOf(heightDp - if (compact) 16 else 24, (widthDp * if (compact) .2f else .38f).toInt()).coerceAtLeast(32)
        views.setInt(R.id.widget_art, "setMaxWidth", (artDp * density).toInt())
        views.setInt(R.id.widget_art, "setMaxHeight", (artDp * density).toInt())
        val bitmap = art.takeIf { content.art.isNotEmpty() && artKey == content.art }
        if (bitmap != null) views.setImageViewBitmap(R.id.widget_art, bitmap) else views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)

        views.setInt(R.id.widget_listen, "setBackgroundResource", if (content.listening) R.drawable.widget_button_on else R.drawable.widget_button)
        views.setImageViewResource(R.id.widget_love, if (content.loved) R.drawable.ic_widget_love else R.drawable.ic_widget_love_outline)
        views.setInt(R.id.widget_love, "setColorFilter", if (content.loved) 0xFFE68B55.toInt() else 0xFFFFFFFF.toInt())
        views.setBoolean(R.id.widget_love, "setEnabled", content.canLove)
        views.setInt(R.id.widget_love, "setImageAlpha", if (content.canLove) 255 else 90)
        views.setBoolean(R.id.widget_next, "setEnabled", content.canNext)
        views.setInt(R.id.widget_next, "setImageAlpha", if (content.canNext) 255 else 90)

        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), immutable)
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setOnClickPendingIntent(R.id.widget_icon, open)
        views.setOnClickPendingIntent(R.id.widget_listen, PendingIntent.getActivity(context, 1,
            Intent(context, WidgetListenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), immutable))
        views.setOnClickPendingIntent(R.id.widget_love, PendingIntent.getBroadcast(context, 2,
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.LOVE), immutable))
        views.setOnClickPendingIntent(R.id.widget_next, PendingIntent.getBroadcast(context, 3,
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.NEXT), immutable))
        return views
    }
}
