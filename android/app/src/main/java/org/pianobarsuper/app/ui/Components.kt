package org.pianobarsuper.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import org.pianobarsuper.app.PianobarApp
import org.pianobarsuper.app.data.PhoneLibrary
import org.pianobarsuper.app.data.Repository

val LocalApp = compositionLocalOf<PianobarApp> { error("No app") }

@Composable fun repo(): Repository = LocalApp.current.repo
@Composable fun phone(): PhoneLibrary = LocalApp.current.phone

fun time(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun duration(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

/** Cover art from the server (signed in) or a remote URL; a note when there is none. */
@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 6.dp, model: Any? = null) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val source = model ?: url
        if (source == null) {
            Icon(Icons.Rounded.MusicNote, null, Modifier.size(size / 2.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context).data(source).crossfade(true).build(),
                imageLoader = repo().imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(size / 2.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                loading = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(size / 2.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

@Composable
fun SongRow(
    title: String,
    subtitle: String,
    art: String?,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    artModel: Any? = null,
    trailing: String = "",
    onClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, 48.dp, model = artModel)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.secondary else LocalContentColor.current,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            if (subtitle.isNotEmpty()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))
        actions()
    }
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** Sound tags such as [laugh] are performed by the voice; show them as stage directions. */
private val soundWords = mapOf("laugh" to "laughs", "chuckle" to "chuckles", "sigh" to "sighs", "gasp" to "gasps",
    "clear throat" to "clears throat", "groan" to "groans", "cough" to "coughs", "sniff" to "sniffs", "shush" to "shh")

fun spokenText(text: String): String = text.replace(Regex("""\[([^\[\]]{1,24})]""")) { match ->
    soundWords[match.groupValues[1].lowercase()]?.let { "($it)" } ?: ""
}.replace(Regex("""\s+"""), " ").trim()

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}
