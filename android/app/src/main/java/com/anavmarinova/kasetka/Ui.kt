@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.anavmarinova.kasetka

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings as SysSettings
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.MutableContextWrapper
import android.view.ViewGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ───────────────────────── helpers ─────────────────────────

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun openListenerSettings(ctx: Context) {
    val comp = ComponentName(ctx, KasetkaListener::class.java).flattenToString()
    try {
        ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(SysSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp))
    } catch (e: Exception) {
        ctx.startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

private fun openAppDetails(ctx: Context) {
    ctx.startActivity(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}

object Thumbs {
    private val cache = LruCache<String, Bitmap>(60)
    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val b = withContext(Dispatchers.IO) {
            try {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 8000
                    inputStream.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) { null }
        }
        if (b != null) cache.put(url, b)
        return b
    }
}

@Composable
private fun rememberThumb(url: String?): Bitmap? {
    val st = produceState<Bitmap?>(null, url) { value = url?.let { Thumbs.load(it) } }
    return st.value
}

class Ctl(
    val rew: () -> Unit, val play: () -> Unit, val toggle: () -> Unit, val stop: () -> Unit,
    val ff: () -> Unit, val eject: () -> Unit, val hold: (Int) -> Unit, val holdEnd: () -> Unit,
)

private data class AddState(val editing: Tape?, val link: String)

// ───────────────────────── app ─────────────────────────

@Composable
fun KasetkaApp(act: MainActivity) {
    val tapes by Store.tapes.collectAsState()
    val s by Store.settings.collectAsState()
    val insertedId by Store.insertedId.collectAsState()
    val activeId by Store.activeTapeId.collectAsState()
    val deck by MediaHub.state.collectAsState()
    val half by act.halfOpened.collectAsState()
    val visible by act.visible.collectAsState()
    val pendingPlay by Store.pendingPlay.collectAsState()
    val pendingShare by Store.pendingShare.collectAsState()
    val awaitFrom by Store.awaitFrom.collectAsState()

    val pal = palette(s)
    val skin = skinFor(s)
    val cfg = LocalConfiguration.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var forceCover by remember { mutableStateOf(false) }
    val isCover = forceCover || cfg.smallestScreenWidthDp < 560 || half
    val inserted = tapes.find { it.id == insertedId }
    val isActive = inserted != null && inserted.id == deck.tapeId && deck.connected

    var addDialog by remember { mutableStateOf<AddState?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showTracks by remember { mutableStateOf(false) }
    var showTapes by remember { mutableStateOf(false) }
    var fast by remember { mutableIntStateOf(0) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    fun haptic(kind: Int = HapticFeedbackConstants.KEYBOARD_TAP) {
        if (s.haptics) view.performHapticFeedback(kind)
    }

    fun startOrPlay(t: Tape) {
        if (deck.tapeId == t.id && deck.connected) MediaHub.play() else MediaHub.loadTape(ctx, t, play = true)
    }

    fun insert(t: Tape, autoplay: Boolean) {
        Sfx.play(Sfx.Kind.CLUNK); haptic(HapticFeedbackConstants.CONFIRM)
        Store.setInserted(t.id)
        if (autoplay) startOrPlay(t)
        else if (deck.tapeId != t.id) MediaHub.loadTape(ctx, t, play = false)
        if (!autoplay) toast(ctx, if (s.foldToPlay) "Fold the phone to play — or press PLAY" else "Press PLAY")
    }

    // Fold = play, unfold = (optionally) pause
    var prevCover by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isCover) {
        val was = prevCover
        prevCover = isCover
        if (was != null && was != isCover) {
            if (isCover && s.foldToPlay && inserted != null && !(isActive && deck.playing)) {
                Sfx.play(Sfx.Kind.CLUNK); haptic(HapticFeedbackConstants.CONFIRM)
                startOrPlay(inserted)
            }
            if (!isCover && s.pauseOnUnfold && deck.playing) MediaHub.pause()
        }
    }

    val keepOn = isCover && deck.playing && s.keepScreenOn
    DisposableEffect(keepOn) {
        if (keepOn) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { }
    }
    LaunchedEffect(deck.playing, s.hiss, visible, isActive) { Sfx.setHiss(isActive && deck.playing && s.hiss && visible) }

    LaunchedEffect(deck.error) {
        deck.error?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); MediaHub.clearError() }
    }
    LaunchedEffect(pendingPlay) {
        pendingPlay?.let { id -> Store.pendingPlay.value = null; Store.tape(id)?.let { insert(it, true) } }
    }
    LaunchedEffect(pendingShare) {
        pendingShare?.let { Store.pendingShare.value = null; addDialog = AddState(null, it) }
    }
    // Learn the song count of a playlist tape once YouTube Music shares its queue
    LaunchedEffect(isActive, deck.queue.size) {
        if (isActive && inserted != null && deck.queue.size > 1 && inserted.count != deck.queue.size)
            Store.update(inserted.id) { it.copy(count = deck.queue.size) }
    }

    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) { value = SystemClock.elapsedRealtime(); delay(400) }
    }
    val pos = if (isActive) { now; MediaHub.positionNow(deck) } else 0L
    val qIdx = deck.queue.indexOfFirst { it.id == deck.activeQueueId }
    val n = if (isActive) deck.queue.size.coerceAtLeast(1) else 1
    val progress = if (isActive && deck.duration > 0)
        ((qIdx.coerceAtLeast(0) + pos.toFloat() / deck.duration) / n).coerceIn(0f, 1f) else 0f
    val playing = isActive && deck.playing
    val thumb = rememberThumb(inserted?.coverUrl())
    val labelArt = (if (isActive) deck.art else null) ?: thumb

    val trk = if (isActive && deck.queue.isNotEmpty() && qIdx >= 0) "%02d/%02d".format(qIdx + 1, deck.queue.size) else "--/--"
    val title = when {
        isActive && deck.title.isNotEmpty() -> deck.title
        inserted != null -> inserted.name
        else -> "No tape"
    }
    val sub = when {
        isActive && deck.artist.isNotEmpty() -> "${deck.artist.removeSuffix(" - Topic")} · ${inserted?.name}"
        inserted != null -> if (isCover) "Tap the cassette to play" else "Ready — press play"
        else -> "Pick a tape from the shelf"
    }

    val ctl = Ctl(
        rew = { Sfx.play(Sfx.Kind.CLICK); haptic(); MediaHub.prev() },
        play = {
            Sfx.play(Sfx.Kind.CLICK); haptic()
            if (inserted == null) toast(ctx, "Insert a tape first") else startOrPlay(inserted)
        },
        toggle = {
            Sfx.play(Sfx.Kind.CLICK); haptic()
            when {
                playing -> MediaHub.pause()
                inserted != null -> startOrPlay(inserted)
                else -> showTapes = true
            }
        },
        stop = { Sfx.play(Sfx.Kind.CLICK); haptic(); MediaHub.pause() },
        ff = { Sfx.play(Sfx.Kind.CLICK); haptic(); MediaHub.next() },
        eject = {
            Sfx.play(Sfx.Kind.CLUNK); haptic(HapticFeedbackConstants.CONFIRM)
            MediaHub.eject()
            Store.setInserted(null)
        },
        hold = { dir ->
            fast = dir; haptic(HapticFeedbackConstants.LONG_PRESS)
            holdJob?.cancel()
            holdJob = scope.launch { while (true) { MediaHub.seekBy(4000L * dir); delay(160) } }
        },
        holdEnd = { fast = 0; holdJob?.cancel(); holdJob = null },
    )

    val scheme = if (pal.dark) darkColorScheme(primary = pal.accent, surface = pal.panel2)
    else lightColorScheme(primary = pal.accent, surface = pal.panel2)

    MaterialTheme(colorScheme = scheme) {
        StarsLayer(enabled = s.stars, colors = pal.stars) {
            Box(Modifier.fillMaxSize().background(pal.bg).safeDrawingPadding()) {
                // YouTube's player lives here, invisible, underneath the deck (visible when troubleshooting)
                val playerView: @Composable (Modifier) -> Unit = { m ->
                    AndroidView(factory = { c ->
                        MediaHub.webView(c).also { w ->
                            (w.parent as? ViewGroup)?.removeView(w)
                            (w.context as? MutableContextWrapper)?.baseContext = c
                        }
                    }, modifier = m)
                }
                if (!s.showPlayer) {
                    playerView(Modifier.size(220.dp).graphicsLayer { alpha = 0f })
                    Box(Modifier.fillMaxSize().background(pal.bg))
                }
                if (isCover) {
                    CoverView(pal, skin, inserted, labelArt, progress, playing, fast, title, sub, trk,
                        fmt(pos) + if (isActive && deck.duration > 0) " / " + fmt(deck.duration) else "",
                        landscape = cfg.screenWidthDp > cfg.screenHeightDp, ctl = ctl, forceCover = forceCover,
                        onTapes = { showTapes = true }, onTracks = { showTracks = true },
                        onSettings = { showSettings = true }, onExit = { forceCover = false })
                } else {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(pal, onCover = { forceCover = true }, onSettings = { showSettings = true },
                            onAdd = { addDialog = AddState(null, "") })
                        val ytmOk = remember(visible, s.useYtm) { Ytm.hasPermission(ctx) }
                        if (s.useYtm && !ytmOk) Notice(pal, "Connect YouTube Music (Premium mode)",
                            "To control YouTube Music, Kasetka needs Notification access. It doesn't read your notifications.") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(pal, "Allow access", primary = true) { openListenerSettings(ctx) }
                                Chip(pal, "Switch greyed out?") { openAppDetails(ctx) }
                            }
                            Text("If the switch is greyed out: tap \"Switch greyed out?\", then ⋮ (top right) → Allow restricted settings, then try again.",
                                color = pal.muted, fontSize = 12.sp)
                        }
                        val shelf: @Composable (Modifier) -> Unit = { m ->
                            Shelf(tapes, insertedId, pal, m, onTap = { insert(it, false) },
                                onLong = { addDialog = AddState(it, "") })
                        }
                        val deckUi: @Composable (Modifier) -> Unit = { m ->
                            DeckPanel(pal, skin, inserted, labelArt, progress, playing, fast,
                                trk, title, fmt(pos), ctl, onTracks = { showTracks = true }, modifier = m, haptic = { haptic(it) })
                        }
                        if (cfg.screenWidthDp >= 760) {
                            Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                shelf(Modifier.weight(1f).fillMaxHeight())
                                Box(Modifier.weight(1.05f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    deckUi(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                deckUi(Modifier.fillMaxWidth())
                                shelf(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }

        if (s.showPlayer) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), contentAlignment = Alignment.BottomStart) {
                AndroidView(factory = { c ->
                    MediaHub.webView(c).also { w ->
                        (w.parent as? ViewGroup)?.removeView(w)
                        (w.context as? MutableContextWrapper)?.baseContext = c
                    }
                }, modifier = Modifier.size(320.dp, 200.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
        if (showTapes) {
            ModalBottomSheet(onDismissRequest = { showTapes = false }, containerColor = pal.panel2) {
                Shelf(tapes, insertedId, pal, Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 12.dp),
                    onTap = { showTapes = false; insert(it, true) },
                    onLong = { addDialog = AddState(it, "") },
                    extra = { Chip(pal, "＋", primary = true) { addDialog = AddState(null, "") } })
            }
        }
        if (showTracks) {
            ModalBottomSheet(onDismissRequest = { showTracks = false }, containerColor = pal.panel2) {
                TrackList(pal, deck, inserted, isActive, onDone = { showTracks = false })
            }
        }
        addDialog?.let { st -> AddTapeDialog(pal, st, onClose = { addDialog = null }) }
        if (showSettings) SettingsDialog(pal, s, deck.permission, onClose = { showSettings = false })
    }
}

// ───────────────────────── top bar & notices ─────────────────────────

@Composable
private fun Chip(pal: Pal, label: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(label,
        modifier
            .clip(RoundedCornerShape(if (pal.round) 20.dp else 12.dp))
            .background(if (primary) pal.accent else pal.panel)
            .border(1.dp, if (primary) Color.Transparent else pal.line, RoundedCornerShape(if (pal.round) 20.dp else 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = if (primary) Color.White else pal.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
}

@Composable
private fun TopBar(pal: Pal, onCover: () -> Unit, onSettings: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(pal.accent))
        Text("Kasetka", Modifier.weight(1f), fontFamily = FontFamily.Cursive, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, color = pal.text)
        Chip(pal, "⤓ Cover view", onClick = onCover)
        Chip(pal, "⚙", onClick = onSettings)
        Chip(pal, "＋ Tape", primary = true, onClick = onAdd)
    }
}

@Composable
private fun Notice(pal: Pal, title: String, body: String, content: @Composable () -> Unit = {}) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth()
        .clip(RoundedCornerShape(16.dp)).background(pal.accent.copy(alpha = 0.16f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = pal.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(body, color = pal.muted, fontSize = 13.sp)
        content()
    }
}

// ───────────────────────── shelf ─────────────────────────

@Composable
private fun Shelf(tapes: List<Tape>, insertedId: String?, pal: Pal, modifier: Modifier,
                  onTap: (Tape) -> Unit, onLong: (Tape) -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    var tab by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    val list = (if (tab == "recent") tapes.filter { it.lastPlayed > 0 }.sortedByDescending { it.lastPlayed } else tapes)
        .filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(pal.panel).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(pal.panel2).padding(3.dp)) {
                for ((k, l) in listOf("all" to "All", "recent" to "Recent")) {
                    Text(l, Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (tab == k) pal.accent else Color.Transparent)
                        .clickable { tab = k }.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = if (tab == k) Color.White else pal.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            BasicTextField(q, { q = it }, singleLine = true,
                textStyle = TextStyle(color = pal.text, fontSize = 14.sp), cursorBrush = SolidColor(pal.accent),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(pal.panel2)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { inner -> if (q.isEmpty()) Text("Search tapes", color = pal.muted, fontSize = 14.sp); inner() })
            extra()
        }
        Spacer(Modifier.height(10.dp))
        when {
            tapes.isEmpty() -> Text("📼\nYour shelf is empty.\nTap ＋ Tape and paste a YouTube Music link — or in YouTube Music tap Share → Kasetka.",
                Modifier.fillMaxWidth().padding(24.dp), color = pal.muted, textAlign = TextAlign.Center, fontSize = 14.sp)
            list.isEmpty() -> Text(if (tab == "recent") "Nothing played yet." else "No tapes match.",
                Modifier.fillMaxWidth().padding(24.dp), color = pal.muted, textAlign = TextAlign.Center)
            else -> LazyVerticalGrid(GridCells.Adaptive(118.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.id }) { t ->
                    TapeCase(t, t.id == insertedId, pal, onClick = { onTap(t) }, onLong = { onLong(t) })
                }
            }
        }
    }
}

@Composable
private fun TapeCase(t: Tape, current: Boolean, pal: Pal, onClick: () -> Unit, onLong: () -> Unit) {
    val thumb = rememberThumb(t.coverUrl())
    val shape = RoundedCornerShape(9.dp)
    Box(Modifier.aspectRatio(4f / 3f)
        .then(if (current) Modifier.border(2.dp, pal.accent, RoundedCornerShape(11.dp)).padding(3.dp) else Modifier)
        .clip(shape).background(pal.caseBg)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(6.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color(t.color))) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Box(Modifier.fillMaxWidth().height(9.dp).background(Color.Black.copy(alpha = 0.18f)))
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.75f)))
            }
            Text(t.name, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xE6FFFDF4))
                .padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color(0xFF1D1D1D), fontFamily = FontFamily.Cursive, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.matchParentSize().background(Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.3f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.1f)))))
        t.trackCount?.let {
            Text("$it trk", Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x8C000000)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ───────────────────────── deck ─────────────────────────

@Composable
private fun CassetteView(tape: Tape?, skin: Skin, art: Bitmap?, progress: Float, spinning: Boolean, fastDir: Int, modifier: Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning, fastDir) {
        if (!spinning && fastDir == 0) return@LaunchedEffect
        val speed = if (fastDir != 0) 1100f * fastDir else 150f
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) angle = (angle + (t - last) / 1e9f * speed) % 360f
                last = t
            }
        }
    }
    Canvas(modifier.aspectRatio(300f / 190f)) {
        drawIntoCanvas {
            CassetteArt.draw(it.nativeCanvas, size.width, size.height, skin, tape?.name ?: "",
                tape?.color ?: PALETTE[0], art, progress, angle)
        }
    }
}

@Composable
private fun InsertingCassette(tape: Tape, skin: Skin, art: Bitmap?, progress: Float, spinning: Boolean, fastDir: Int, modifier: Modifier) {
    val anim = remember { Animatable(-1f) }
    LaunchedEffect(Unit) { anim.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 320f)) }
    CassetteView(tape, skin, art, progress, spinning, fastDir,
        modifier.graphicsLayer {
            translationY = anim.value * size.height * 0.7f
            alpha = (1f + anim.value).coerceIn(0f, 1f)
        })
}

@Composable
private fun DeckKey(icon: String, label: String?, pal: Pal, modifier: Modifier = Modifier, accent: Boolean = false,
                    down: Boolean = false, height: Dp = 66.dp, iconSize: Int = 20,
                    onTap: () -> Unit, onHold: (() -> Unit)? = null, onHoldEnd: (() -> Unit)? = null) {
    var pressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val sink = if (pressed || down) 5.dp else 0.dp
    val shape = RoundedCornerShape(if (pal.round) 18.dp else 10.dp)
    Box(modifier.height(height + 6.dp).pointerInput(Unit) {
        detectTapGestures(onPress = {
            pressed = true
            var held = false
            val job = if (hold != null) scope.launch { delay(380); held = true; hold?.invoke() } else null
            val released = tryAwaitRelease()
            job?.cancel()
            pressed = false
            if (held) holdEnd?.invoke() else if (released) tap()
        })
    }) {
        Box(Modifier.fillMaxWidth().height(height).offset(y = 6.dp).clip(shape).background(pal.keyEdge))
        Box(Modifier.fillMaxWidth().height(height).offset(y = sink).clip(shape)
            .background(Brush.verticalGradient(listOf(pal.keyTop, pal.key))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, color = if (accent) pal.accent else pal.keyInk, fontSize = iconSize.sp)
                if (label != null) Text(label, color = if (accent) pal.accent else pal.keyInk, fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun VolumeWheel(pal: Pal, haptic: (Int) -> Unit) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var vol by remember { mutableIntStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var off by remember { mutableFloatStateOf(0f) }
    val acc = remember { floatArrayOf(0f) }
    val fb by rememberUpdatedState(haptic)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.width(50.dp).height(92.dp).clip(RoundedCornerShape(12.dp)).pointerInput(Unit) {
            detectVerticalDragGestures { change, dy ->
                change.consume()
                off += dy
                acc[0] -= dy
                val step = 12.dp.toPx()
                while (abs(acc[0]) >= step) {
                    val dir = if (acc[0] > 0) 1 else -1
                    acc[0] -= dir * step
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    Sfx.play(Sfx.Kind.TICK)
                    fb(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }) {
            drawRect(pal.ridge1)
            val period = 7.dp.toPx()
            var y = (off % period) - period
            while (y < size.height) {
                drawRect(pal.ridge2, Offset(0f, y), Size(size.width, 3.dp.toPx()))
                y += period
            }
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent,
                Color.Transparent, Color.Black.copy(alpha = 0.5f))))
        }
        Text("VOL %02d".format(vol), color = pal.keyInk.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun DeckPanel(pal: Pal, skin: Skin, tape: Tape?, art: Bitmap?, progress: Float, playing: Boolean, fast: Int,
                      trk: String, title: String, time: String, ctl: Ctl, onTracks: () -> Unit,
                      modifier: Modifier, haptic: (Int) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(if (pal.round) 32.dp else 22.dp))
        .background(Brush.verticalGradient(listOf(pal.deckTop, pal.deckBottom))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (playing) Color(0xFFFF3B30) else Color(0xFF551111)))
            Spacer(Modifier.width(6.dp))
            Text("STEREO CASSETTE DECK", Modifier.weight(1f), color = pal.keyInk.copy(alpha = 0.7f),
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, maxLines = 1)
            Text("≡ TRACKS", Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.2f))
                .clickable(onClick = onTracks).padding(horizontal = 10.dp, vertical = 5.dp),
                color = pal.keyInk.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(300f / 190f).clip(RoundedCornerShape(14.dp)).background(pal.door),
            contentAlignment = Alignment.Center) {
            if (tape == null) Text("Tap a tape on the shelf\nto load it", color = Color.White.copy(alpha = 0.55f),
                fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center)
            else key(tape.id) {
                InsertingCassette(tape, skin, art, progress, playing, fast, Modifier.fillMaxWidth(0.9f))
            }
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.18f),
                Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.06f)))))
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(pal.lcdBg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val st = TextStyle(color = pal.lcdFg, fontFamily = FontFamily.Monospace, fontSize = 19.sp)
            Text(trk, style = st)
            Text(title.uppercase(), Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE), style = st, maxLines = 1)
            Text(time, style = st)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeckKey("⏪", "REW", pal, Modifier.weight(1f), onTap = ctl.rew, onHold = { ctl.hold(-1) }, onHoldEnd = ctl.holdEnd)
                DeckKey("▶", "PLAY", pal, Modifier.weight(1f), accent = true, down = playing, onTap = ctl.play)
                DeckKey("■", "STOP", pal, Modifier.weight(1f), onTap = ctl.stop)
                DeckKey("⏩", "FF", pal, Modifier.weight(1f), onTap = ctl.ff, onHold = { ctl.hold(1) }, onHoldEnd = ctl.holdEnd)
                DeckKey("⏏", "EJECT", pal, Modifier.weight(1f), onTap = ctl.eject)
            }
            VolumeWheel(pal, haptic)
        }
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val gap = 8.dp.toPx()
            var y = gap / 2
            while (y < size.height) {
                var x = gap / 2
                while (x < size.width) { drawCircle(Color.Black.copy(alpha = 0.35f), 1.4.dp.toPx(), Offset(x, y)); x += gap }
                y += gap
            }
        }
    }
}

// ───────────────────────── folded (cover screen / tent) ─────────────────────────

@Composable
private fun CoverView(pal: Pal, skin: Skin, tape: Tape?, art: Bitmap?, progress: Float, playing: Boolean, fast: Int,
                      title: String, sub: String, trk: String, time: String, landscape: Boolean, ctl: Ctl,
                      forceCover: Boolean, onTapes: () -> Unit, onTracks: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    val cassette: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            if (tape == null) Box(Modifier.fillMaxWidth().aspectRatio(300f / 190f).clip(RoundedCornerShape(16.dp))
                .border(3.dp, pal.line, RoundedCornerShape(16.dp)).clickable(onClick = onTapes), contentAlignment = Alignment.Center) {
                Text("No tape in the deck.\nTap here to pick one.", color = pal.muted, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            } else {
                key(tape.id) { InsertingCassette(tape, skin, art, progress, playing, fast, Modifier.fillMaxWidth()) }
                Row(Modifier.matchParentSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = ctl.rew))
                    Box(Modifier.weight(1.2f).fillMaxHeight().clickable(onClick = ctl.toggle))
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = ctl.ff))
                }
            }
        }
    }
    val info: @Composable () -> Unit = {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, Modifier.basicMarquee(iterations = Int.MAX_VALUE), color = pal.text, fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp, maxLines = 1)
            Text(sub, color = pal.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 10.dp, bottom = 4.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(pal.line)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(pal.accent))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(if (trk == "--/--") "" else "TRACK $trk", Modifier.weight(1f), color = pal.muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text(time, color = pal.muted, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            DeckKey("⏪", null, pal, Modifier.width(66.dp), height = 58.dp, iconSize = 24, onTap = ctl.rew,
                onHold = { ctl.hold(-1) }, onHoldEnd = ctl.holdEnd)
            DeckKey(if (playing) "❚❚" else "▶", null, pal, Modifier.width(86.dp), accent = true, height = 70.dp, iconSize = 26, onTap = ctl.toggle)
            DeckKey("⏩", null, pal, Modifier.width(66.dp), height = 58.dp, iconSize = 24, onTap = ctl.ff,
                onHold = { ctl.hold(1) }, onHoldEnd = ctl.holdEnd)
        }
    }
    val chips: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(pal, "📼 Tapes", onClick = onTapes)
            Chip(pal, "≡ Tracks", onClick = onTracks)
            Chip(pal, "⚙", onClick = onSettings)
            if (forceCover) Chip(pal, "⤒", onClick = onExit)
        }
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            cassette(Modifier.weight(1.25f))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) { info(); controls(); chips() }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
            cassette(Modifier.widthIn(max = 720.dp).fillMaxWidth())
            info(); controls(); chips()
        }
    }
}

// ───────────────────────── track list ─────────────────────────

@Composable
private fun TrackList(pal: Pal, deck: Deck, tape: Tape?, isActive: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var mixName by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tape?.name ?: "Tracks", color = pal.text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(if (deck.queue.isNotEmpty()) "${deck.queue.size} songs · " + (if (picking) "tick the songs you want" else "tap one to play it") else "",
                    color = pal.muted, fontSize = 12.sp)
            }
            if (isActive && deck.queue.isNotEmpty())
                Chip(pal, if (picking) "Done" else "Pick songs", primary = picking) { picking = !picking; if (!picking) picked.clear() }
        }
        Spacer(Modifier.height(8.dp))
        if (!isActive || deck.queue.isEmpty()) {
            Text(if (tape == null) "Put a tape in the deck first." else "Loading the songs… press PLAY if nothing shows up.",
                color = pal.muted, modifier = Modifier.padding(vertical = 24.dp))
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(deck.queue, key = { _, q -> q.id }) { i, q ->
                val now = q.id == deck.activeQueueId
                val sel = q.mediaId != null && q.mediaId in picked
                val thumb = rememberThumb(q.mediaId?.let { "https://i.ytimg.com/vi/$it/default.jpg" })
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (now) pal.accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable {
                        Sfx.play(Sfx.Kind.TICK)
                        if (picking) q.mediaId?.let { if (it in picked) picked.remove(it) else picked.add(it) }
                        else MediaHub.skipTo(q.id)
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${i + 1}", Modifier.width(28.dp), color = pal.muted, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                    if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop)
                    else Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(pal.line))
                    Column(Modifier.weight(1f)) {
                        Text(q.title, color = pal.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (q.subtitle.isNotEmpty()) Text(q.subtitle, color = pal.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (picking) Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) pal.accent else Color.Transparent)
                        .border(2.dp, if (sel) pal.accent else pal.muted, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (sel) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (picking) {
            val order = deck.queue.mapNotNull { it.mediaId }.filter { it in picked }
            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${picked.size} picked", Modifier.weight(1f), color = pal.text, fontWeight = FontWeight.Bold)
                    Chip(pal, "Pick all") {
                        val all = deck.queue.mapNotNull { it.mediaId }
                        if (picked.size == all.size) picked.clear() else { picked.clear(); picked.addAll(all) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mixName, { mixName = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("New tape label") })
                    Chip(pal, "⏺ Record", primary = true) {
                        if (order.isEmpty()) { toast(ctx, "Pick some songs first"); return@Chip }
                        val name = mixName.trim().ifBlank { "Mixtape ${Store.tapes.value.count { it.videos != null } + 1}" }
                        Store.add(Tape(id = "t" + System.currentTimeMillis(), name = name, videos = order, color = Store.nextColor(),
                            cover = "https://i.ytimg.com/vi/${order.first()}/hqdefault.jpg", count = order.size))
                        Sfx.play(Sfx.Kind.CLUNK)
                        toast(ctx, "Recorded ${order.size} songs onto “$name” 📼")
                        picked.clear(); picking = false; mixName = ""
                    }
                }
                Chip(pal, "Each picked song → its own tape", modifier = Modifier.fillMaxWidth()) {
                    if (order.isEmpty()) { toast(ctx, "Pick some songs first"); return@Chip }
                    val made = order.mapIndexed { k, id ->
                        val q = deck.queue.first { it.mediaId == id }
                        Tape(id = "t${System.currentTimeMillis()}_$k", name = q.title.take(40), video = id,
                            color = PALETTE[(Store.tapes.value.size + k) % PALETTE.size], cover = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                    }
                    Store.setTapes(made + Store.tapes.value)
                    Sfx.play(Sfx.Kind.CLUNK)
                    toast(ctx, "Made ${made.size} new tapes 📼")
                    picked.clear(); picking = false
                }
            }
        }
    }
}

// ───────────────────────── dialogs ─────────────────────────

@Composable
private fun AddTapeDialog(pal: Pal, st: AddState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val t = st.editing
    var link by remember {
        mutableStateOf(when {
            t == null -> st.link
            t.list != null -> "https://music.youtube.com/playlist?list=${t.list}"
            t.video != null -> "https://music.youtube.com/watch?v=${t.video}"
            else -> ""
        })
    }
    var name by remember { mutableStateOf(t?.name ?: "") }
    var err by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (t != null && t.videos != null && link.isBlank()) {
            Store.update(t.id) { it.copy(name = name.ifBlank { it.name }) }; onClose(); return
        }
        val p = parseLink(link)
        if (p.error != null) { err = p.error; return }
        if ((p.list == "LM" || p.list == "LL") && !Store.settings.value.useYtm) {
            err = "Liked Music only plays inside the YouTube Music app. Save those songs into a playlist and share that."; return
        }
        val autoName = name.isBlank() && p.list != "LM"
        val label = name.trim().ifBlank { if (p.list == "LM") "Liked Music" else "Tape ${Store.tapes.value.size + 1}" }
        if (t != null) {
            val changed = t.list != p.list || (p.video != null && t.video != p.video) || t.videos != null
            Store.update(t.id) {
                if (changed) it.copy(name = label, list = p.list, video = p.video, videos = null, lastVideo = null,
                    cover = null, count = null, firstVideo = null)
                else it.copy(name = label)
            }
            if (changed) Store.tape(t.id)?.let { nt -> Store.scope.launch { Store.ensureMeta(nt, rename = false) } }
        } else {
            val nt = Tape(id = "t" + System.currentTimeMillis(), name = label, list = p.list, video = p.video, color = Store.nextColor())
            Store.add(nt)
            Store.scope.launch { Store.ensureMeta(nt, rename = autoName) }
            toast(ctx, "Tape on the shelf 📼")
        }
        Sfx.play(Sfx.Kind.CLUNK)
        onClose()
    }

    AlertDialog(onDismissRequest = onClose, containerColor = pal.panel2, titleContentColor = pal.text, textContentColor = pal.text,
        title = { Text(if (t == null) "New tape" else "Edit tape") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(link, { link = it; err = null }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("YouTube Music link") },
                    placeholder = { Text(if (t?.videos != null) "Mixtape — leave empty to keep" else "https://music.youtube.com/playlist?list=…") })
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Label (written on the tape)") })
                err?.let { Text(it, color = Color(0xFFFF5A5A), fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Text("In YouTube Music: open a playlist or album → Share → Kasetka adds it straight away. Leave the label empty to use the playlist's name.",
                    color = pal.muted, fontSize = 12.sp)
                if (t != null) TextButton(onClick = { Store.remove(t.id); onClose() }) {
                    Text("Remove tape", color = Color(0xFFFF5A5A))
                }
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text("Save tape") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun SetRow(pal: Pal, label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (sub != null) Text(sub, color = pal.muted, fontSize = 12.sp)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsDialog(pal: Pal, s: Settings, permission: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var importText by remember { mutableStateOf("") }
    fun set(n: Settings) { Store.setSettings(n); MediaHub.refreshWidget() }
    AlertDialog(onDismissRequest = onClose, containerColor = pal.panel2, titleContentColor = pal.text, textContentColor = pal.text,
        title = { Text("Settings") },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Theme", color = pal.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(pal, "Light", primary = s.theme == "light") { set(s.copy(theme = "light")) }
                    Chip(pal, "Dark", primary = s.theme != "light") { set(s.copy(theme = "dark")) }
                }
                SetRow(pal, "Whimsy", "Pastel sparkle mode on top of light or dark", s.whimsy) { set(s.copy(whimsy = it)) }
                Text("Cassette skin", color = pal.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, sk) in SKINS) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(sk.body))
                            .then(if (s.skin == k) Modifier.border(3.dp, pal.accent, RoundedCornerShape(9.dp)) else Modifier)
                            .clickable { set(s.copy(skin = k)) })
                    }
                }
                HorizontalDivider(color = pal.line)
                SetRow(pal, "Fold to play", "Closing the phone starts the tape", s.foldToPlay) { set(s.copy(foldToPlay = it)) }
                SetRow(pal, "Pause when unfolding", null, s.pauseOnUnfold) { set(s.copy(pauseOnUnfold = it)) }
                SetRow(pal, "Auto-play next tape", "When a tape ends, the next one on the shelf starts", s.autoNext) { set(s.copy(autoNext = it)) }
                SetRow(pal, "Keep cover screen on", "While a tape plays on the folded screen", s.keepScreenOn) { set(s.copy(keepScreenOn = it)) }
                SetRow(pal, "Tape hiss", null, s.hiss) { set(s.copy(hiss = it)) }
                SetRow(pal, "Key clicks", null, s.clicks) { set(s.copy(clicks = it)) }
                SetRow(pal, "Haptics", null, s.haptics) { set(s.copy(haptics = it)) }
                SetRow(pal, "Stars on touch", null, s.stars) { set(s.copy(stars = it)) }
                SetRow(pal, "Show YouTube player", "Troubleshooting: shows the hidden player and what it's doing", s.showPlayer) { set(s.copy(showPlayer = it)) }
                if (s.showPlayer) {
                    val lines by MediaHub.log.collectAsState()
                    Text("Player log", color = pal.text, fontWeight = FontWeight.Bold)
                    Text(if (lines.isEmpty()) "(nothing yet — put a tape in and press PLAY)" else lines.takeLast(18).joinToString("\n"),
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(8.dp),
                        color = pal.text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
                HorizontalDivider(color = pal.line)
                SetRow(pal, "Play through YouTube Music (Premium)",
                    "Your account, no ads, Liked Music works. YouTube Music may flash up when a new tape starts.", s.useYtm) {
                    set(s.copy(useYtm = it)); MediaHub.switchEngine(ctx)
                    if (it && !Ytm.hasPermission(ctx)) openListenerSettings(ctx)
                }
                Text(if (s.useYtm) "YouTube Music plays your tapes; Kasetka is the remote. Notification access: " +
                        (if (Ytm.hasPermission(ctx)) "on ✓" else "needed") else
                    "Songs play inside Kasetka with YouTube's player — it keeps going when the app is minimised, with controls on the lock screen.",
                    color = pal.muted, fontSize = 12.sp)
                HorizontalDivider(color = pal.line)
                Text("Bring tapes from the web version", color = pal.text, fontWeight = FontWeight.Bold)
                Text("In the web Kasetka: ⚙ → Copy backup, then paste here.", color = pal.muted, fontSize = 12.sp)
                OutlinedTextField(importText, { importText = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                    placeholder = { Text("Paste backup") })
                Chip(pal, "Import tapes", primary = true) {
                    val n = Store.importBackup(importText)
                    toast(ctx, when { n < 0 -> "That backup didn't read right"; n == 0 -> "Nothing new to import"; else -> "Imported $n tapes 📼" })
                    if (n > 0) importText = ""
                }
            }
        })
}

// ───────────────────────── stars on touch ─────────────────────────

private class Star(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float,
                   val size: Float, var rot: Float, val vr: Float, val color: Color)

private fun starPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    for (i in 0 until 10) {
        val rr = if (i % 2 == 1) r * 0.42f else r
        val a = i * Math.PI / 5 - Math.PI / 2
        val x = cx + (cos(a) * rr).toFloat(); val y = cy + (sin(a) * rr).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

@Composable
private fun StarsLayer(enabled: Boolean, colors: List<Color>, content: @Composable () -> Unit) {
    val stars = remember { ArrayList<Star>() }
    var frame by remember { mutableLongStateOf(0L) }
    var wake by remember { mutableIntStateOf(0) }
    val last = remember { floatArrayOf(0f, 0f) }
    val cols by rememberUpdatedState(colors)

    fun spawn(o: Offset, n: Int, spread: Float) {
        repeat(n) {
            val a = Random.nextFloat() * 6.283f
            val sp = Random.nextFloat() * spread + 1f
            stars.add(Star(o.x, o.y, cos(a) * sp, sin(a) * sp - 1.5f, 1f, 10f + Random.nextFloat() * 16f,
                Random.nextFloat() * 6f, (Random.nextFloat() - 0.5f) * 0.2f, cols.random()))
        }
        wake++
    }

    LaunchedEffect(wake) {
        while (stars.isNotEmpty()) {
            withFrameNanos { t ->
                val it = stars.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.x += p.vx; p.y += p.vy; p.vy += 0.15f; p.rot += p.vr; p.life -= 0.022f
                    if (p.life <= 0f) it.remove()
                }
                frame = t
            }
        }
        frame = 0L
    }

    Box(Modifier.fillMaxSize().pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                for (c in ev.changes) {
                    if (c.pressed && !c.previousPressed) {
                        spawn(c.position, 12, 5f); last[0] = c.position.x; last[1] = c.position.y
                    } else if (c.pressed && (c.position - Offset(last[0], last[1])).getDistance() > 28f) {
                        spawn(c.position, 2, 2f); last[0] = c.position.x; last[1] = c.position.y
                    }
                }
            }
        }
    }) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            if (frame == 0L && stars.isEmpty()) return@Canvas
            for (p in stars) {
                rotate(p.rot * 57.3f, Offset(p.x, p.y)) {
                    drawPath(starPath(p.x, p.y, p.size), p.color.copy(alpha = p.life.coerceIn(0f, 1f)))
                }
            }
        }
    }
}
