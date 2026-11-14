package com.anavmarinova.kasetka

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class QItem(val id: Long, val title: String, val subtitle: String, val icon: Bitmap?, val mediaId: String? = null)

data class Deck(
    val permission: Boolean = true,
    /** The built-in player is loaded and ready. */
    val connected: Boolean = false,
    /** The tape currently loaded in the player. */
    val tapeId: String? = null,
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val mediaId: String? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val positionAt: Long = 0,
    val speed: Float = 1f,
    val duration: Long = 0,
    val queue: List<QItem> = emptyList(),
    val activeQueueId: Long = -1,
    /** A message for the user when a tape or song couldn't be played; cleared once shown. */
    val error: String? = null,
)

/**
 * Plays tapes with YouTube's own embedded player, running in a hidden WebView inside Kasetka.
 * The WebView lives for the whole app process, so music keeps going when the app is minimised
 * (PlayerService keeps the process alive and shows the media controls).
 */
object MediaHub {
    private const val PLAYER_URL = "https://anavmarinova-art.github.io/kasetka/player.html"

    val state = MutableStateFlow(Deck())
    private var web: WebView? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var widgetJob: Job? = null
    private var ids: List<String> = emptyList()
    private var lastSave = 0L
    private var artFor: String? = null
    /** The song that was loaded before switching tapes; ignored until the new tape actually loads. */
    private var staleVid: String? = null

    val alive: Boolean get() = web != null

    fun connect(ctx: Context) { appContext = ctx.applicationContext }

    /** The single player view. The activity shows it (invisibly) so the page renders; it survives the activity. */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context): WebView {
        appContext = ctx.applicationContext
        web?.let { return it }
        val w = WebView(MutableContextWrapper(ctx.applicationContext))
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = WebChromeClient()
        w.webViewClient = WebViewClient()
        w.addJavascriptInterface(Bridge, "Android")
        w.loadUrl(PLAYER_URL)
        web = w
        return w
    }

    private fun js(code: String) {
        main.post { web?.evaluateJavascript("window.kp && kp.$code", null) }
    }

    // ── commands ──
    fun loadTape(ctx: Context, t: Tape, play: Boolean) {
        webView(ctx)
        Store.markStarted(t.id)
        val o = JSONObject().apply {
            put("play", play)
            put("index", t.resumeIndex)
            put("time", t.resumeTime.toDouble())
            if (t.list != null) put("list", t.list)
            if (!t.videos.isNullOrEmpty()) put("videos", JSONArray(t.videos))
            if (t.video != null) put("video", t.video)
        }
        ids = emptyList()
        staleVid = state.value.mediaId
        state.value = state.value.copy(tapeId = t.id, title = "", artist = "", art = null, mediaId = null,
            playing = false, position = 0, duration = 0, queue = emptyList(), activeQueueId = -1)
        artFor = null
        js("load($o)")
        if (play) startService(ctx)
    }

    fun play() { appContext?.let { startService(it) }; js("play()") }
    fun pause() { savePosition(); js("pause()") }
    fun toggle() { if (state.value.playing) pause() else play() }
    fun next() { js("next()") }
    fun prev() { js("prev()") }
    fun seekBy(ms: Long) { js("seekBy(${ms / 1000.0})") }
    fun seekTo(ms: Long) { js("seek(${ms / 1000.0})") }
    fun skipTo(queueId: Long) { js("at($queueId)") }

    fun eject() {
        savePosition()
        js("stop()")
        state.value = state.value.copy(tapeId = null, playing = false)
        appContext?.let { it.stopService(Intent(it, PlayerService::class.java)) }
        refreshWidget()
    }

    private fun startService(ctx: Context) {
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, PlayerService::class.java)) } catch (e: Exception) { }
    }

    fun positionNow(d: Deck = state.value): Long =
        if (d.playing) d.position + ((SystemClock.elapsedRealtime() - d.positionAt) * d.speed).toLong()
        else d.position

    private fun savePosition() {
        val d = state.value
        val id = d.tapeId ?: return
        if (d.activeQueueId < 0 || staleVid != null || d.mediaId == null) return
        Store.saveResume(id, d.activeQueueId.toInt(), positionNow(d) / 1000f)
    }

    // ── reports from the player page ──
    private object Bridge {
        @JavascriptInterface fun onReady() { main.post { state.value = state.value.copy(connected = true); refreshWidget() } }
        @JavascriptInterface fun onState(json: String) { main.post { applyState(json) } }
        @JavascriptInterface fun onQueue(json: String) { main.post { applyQueue(json) } }
        @JavascriptInterface fun onError(code: Int) {
            val msg = when (code) {
                -2 -> "This tape didn't load. Check the link — the playlist has to be Public or Unlisted."
                -3 -> "This link is a YouTube Mix (a radio), not a playlist. Share a playlist or album instead."
                -4 -> "Couldn't load the playlist, so playing the song from the link instead."
                100, 101, 150 -> "This song can't play outside YouTube."
                else -> null
            }
            if (msg != null) main.post { state.value = state.value.copy(error = msg) }
        }
        @JavascriptInterface fun onEnded() {
            main.post {
                val id = state.value.tapeId ?: return@post
                Sfx.play(Sfx.Kind.CLUNK)
                Store.saveResume(id, 0, 0f)
                val ctx = appContext ?: return@post
                Store.tape(id)?.let { loadTape(ctx, it, play = false) }
            }
        }
    }

    private fun applyState(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val prev = state.value
        var vid = o.optString("videoId").ifEmpty { null }
        // Still showing the previous tape's song: wait for the new tape instead of showing old info
        if (vid != null && vid == staleVid && !o.optBoolean("playing")) return
        if (vid != null && vid != staleVid) staleVid = null
        val title = o.optString("title").ifEmpty { vid?.let { Titles.get(it)?.first } ?: "" }
        val author = o.optString("author").ifEmpty { vid?.let { Titles.get(it)?.second } ?: "" }
        if (vid != null && o.optString("title").isNotEmpty()) Titles.put(vid, o.optString("title"), o.optString("author"))
        val now = prev.copy(
            connected = true,
            title = title, artist = author, mediaId = vid,
            playing = o.optBoolean("playing"),
            position = (o.optDouble("time", 0.0) * 1000).toLong(),
            positionAt = SystemClock.elapsedRealtime(),
            duration = (o.optDouble("duration", 0.0) * 1000).toLong(),
            activeQueueId = o.optInt("index", 0).toLong(),
            queue = if (prev.queue.isEmpty() && ids.isNotEmpty()) buildQueue() else prev.queue,
        )
        state.value = now
        if (vid != null && vid != artFor) {
            artFor = vid
            Store.scope.launch {
                val b = Thumbs.load("https://i.ytimg.com/vi/$vid/hqdefault.jpg")
                if (state.value.mediaId == vid) { state.value = state.value.copy(art = b); refreshWidget() }
            }
        }
        if (now.playing && SystemClock.elapsedRealtime() - lastSave > 5000) { lastSave = SystemClock.elapsedRealtime(); savePosition() }
        if (now.title != prev.title || now.playing != prev.playing || now.tapeId != prev.tapeId) refreshWidget()
    }

    private fun applyQueue(json: String) {
        ids = try { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        state.value = state.value.copy(queue = buildQueue())
        Titles.fetchMissing(ids) { state.value = state.value.copy(queue = buildQueue()) }
    }

    private fun buildQueue(): List<QItem> = ids.mapIndexed { i, id ->
        val t = Titles.get(id)
        QItem(i.toLong(), t?.first ?: "Song ${i + 1}", t?.second?.removeSuffix(" - Topic") ?: "", null, id)
    }

    fun clearError() { state.value = state.value.copy(error = null) }

    fun refreshWidget() {
        val ctx = appContext ?: return
        widgetJob?.cancel()
        widgetJob = Store.scope.launch {
            delay(400)
            try { KasetkaWidget().updateAll(ctx) } catch (e: Exception) { }
        }
    }
}

/** Song titles for the track list, looked up once from YouTube's public oEmbed endpoint and remembered. */
object Titles {
    private val map = HashMap<String, Pair<String, String>>()
    private val queue = ArrayDeque<String>()
    private var workers = 0
    private var onChange: (() -> Unit)? = null

    fun get(id: String) = map[id]
    fun put(id: String, title: String, author: String) { map[id] = title to author }

    fun fetchMissing(ids: List<String>, changed: () -> Unit) {
        onChange = changed
        ids.filter { it !in map && it !in queue }.forEach { queue.addLast(it) }
        while (workers < 4 && queue.isNotEmpty()) {
            workers++
            Store.scope.launch {
                while (queue.isNotEmpty()) {
                    val id = queue.removeFirst()
                    val r = withContext(Dispatchers.IO) {
                        try {
                            val u = "https://www.youtube.com/oembed?format=json&url=" +
                                URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                            val c = URL(u).openConnection() as HttpURLConnection
                            c.connectTimeout = 8000; c.readTimeout = 8000
                            if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
                        } catch (e: Exception) { null }
                    }
                    if (r != null) { map[id] = r.optString("title") to r.optString("author_name"); onChange?.invoke() }
                }
                workers--
            }
        }
    }
}
