package com.anavmarinova.kasetka

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity as startActivityOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.delay

class KasetkaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KasetkaWidget()
}

/** Home-screen deck: the cassette (with the song's art as its label), play/skip keys and your three latest tapes. */
class KasetkaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.init(context)
        MediaHub.connect(context)
        val d = MediaHub.state.value
        val s = Store.settings.value
        val pal = palette(s)
        val inserted = Store.tape(Store.insertedId.value)
        val active = inserted != null && inserted.id == Store.activeTapeId.value && d.connected && Store.awaitFrom.value == null
        val qIdx = d.queue.indexOfFirst { it.id == d.activeQueueId }
        val n = d.queue.size.coerceAtLeast(1)
        val progress = if (active && d.duration > 0)
            ((qIdx.coerceAtLeast(0) + MediaHub.positionNow(d).toFloat() / d.duration) / n).coerceIn(0f, 1f) else 0f
        val art = if (active) d.art else null
        val cassette = CassetteArt.bitmap(450, 285, skinFor(s), inserted?.name ?: "Kasetka",
            inserted?.color ?: PALETTE[0], art, progress)
        val recent = Store.tapes.value.sortedByDescending { it.lastPlayed }.take(3)
        val title = when {
            !d.permission -> "Open Kasetka to connect"
            d.connected && d.title.isNotEmpty() -> d.title
            inserted != null -> inserted.name
            else -> "No tape in the deck"
        }
        val artist = if (d.connected) d.artist.removeSuffix(" - Topic") else ""
        val playing = d.connected && d.playing

        provideContent {
            WidgetBody(context, pal, cassette, title, artist, playing, recent)
        }
    }
}

@Composable
private fun WidgetBody(context: Context, pal: Pal, cassette: android.graphics.Bitmap, title: String, artist: String,
                       playing: Boolean, recent: List<Tape>) {
    val ink = ColorProvider(pal.keyInk)
    Column(GlanceModifier.fillMaxSize().background(pal.deckBottom).cornerRadius(24.dp).padding(12.dp)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(cassette), "Cassette", GlanceModifier.width(150.dp).height(95.dp)
                .clickable(startActivityOf<MainActivity>()), contentScale = ContentScale.Fit)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(if (playing) "● PLAYING" else "● STOPPED",
                    style = TextStyle(color = ColorProvider(if (playing) Color(0xFFFF3B30) else pal.muted), fontSize = 10.sp, fontWeight = FontWeight.Bold))
                Text(title, maxLines = 2, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                if (artist.isNotEmpty()) Text(artist, maxLines = 1, style = TextStyle(color = ColorProvider(pal.muted), fontSize = 12.sp))
                Spacer(GlanceModifier.height(6.dp))
                Row {
                    WidgetKey(pal, "⏮", actionRunCallback<PrevAction>())
                    Spacer(GlanceModifier.width(6.dp))
                    WidgetKey(pal, if (playing) "❚❚" else "▶", actionRunCallback<ToggleAction>(), accent = true)
                    Spacer(GlanceModifier.width(6.dp))
                    WidgetKey(pal, "⏭", actionRunCallback<NextAction>())
                }
            }
        }
        if (recent.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                recent.forEachIndexed { i, t ->
                    if (i > 0) Spacer(GlanceModifier.width(6.dp))
                    val intent = Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_PLAY_TAPE)
                        .setData(Uri.parse("kasetka://tape/${t.id}"))
                    Box(GlanceModifier.defaultWeight().height(30.dp).background(Color(t.color)).cornerRadius(8.dp)
                        .clickable(actionStartActivity(intent)), contentAlignment = Alignment.Center) {
                        Text("📼 " + t.name, maxLines = 1, modifier = GlanceModifier.padding(horizontal = 6.dp),
                            style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetKey(pal: Pal, label: String, action: androidx.glance.action.Action, accent: Boolean = false) {
    Box(GlanceModifier.size(44.dp, 36.dp).background(if (accent) pal.accent else pal.key).cornerRadius(10.dp).clickable(action),
        contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(color = ColorProvider(if (accent) Color.White else pal.keyInk), fontSize = 16.sp, fontWeight = FontWeight.Bold))
    }
}

private suspend fun refresh(context: Context) {
    delay(600)
    KasetkaWidget().updateAll(context)
}

class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.init(context); MediaHub.connect(context); MediaHub.toggle(); refresh(context)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.init(context); MediaHub.connect(context); MediaHub.next(); refresh(context)
    }
}

class PrevAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store.init(context); MediaHub.connect(context); MediaHub.prev(); refresh(context)
    }
}
