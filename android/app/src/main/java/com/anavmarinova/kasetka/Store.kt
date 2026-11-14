package com.anavmarinova.kasetka

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

val PALETTE = intArrayOf(
    0xFFE2543A.toInt(), 0xFF3A8FE2.toInt(), 0xFF2FBF71.toInt(), 0xFFF2A33A.toInt(),
    0xFFB05CE0.toInt(), 0xFFFF5FA2.toInt(), 0xFF19B8C9.toInt(), 0xFFE2C23A.toInt()
)

private fun JSONObject.str(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

/** One cassette on the shelf: a YouTube Music playlist/album, a single song, or a mixtape of songs. */
data class Tape(
    val id: String,
    val name: String,
    val list: String? = null,
    val video: String? = null,
    val videos: List<String>? = null,
    val color: Int = PALETTE[0],
    val cover: String? = null,
    val added: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0,
    val lastVideo: String? = null,
    val count: Int? = null,
    /** First song of a playlist, looked up once so YouTube Music can start exactly this playlist. */
    val firstVideo: String? = null,
) {
    val trackCount: Int? get() = count ?: videos?.size

    /** Link that YouTube Music opens and starts playing. Playlists resume at the last song heard. */
    /** False when YouTube Music can only show the playlist page (e.g. Liked Music) and the user taps play there. */
    val canAutoplay: Boolean get() = list == null || (lastVideo ?: firstVideo) != null

    fun playUrl(): String = when {
        list != null && (lastVideo ?: firstVideo) != null -> "https://music.youtube.com/watch?v=${lastVideo ?: firstVideo}&list=$list"
        list != null -> "https://music.youtube.com/playlist?list=$list"
        !videos.isNullOrEmpty() -> "https://music.youtube.com/watch_videos?video_ids=${videos.joinToString(",")}"
        else -> "https://music.youtube.com/watch?v=$video"
    }

    fun coverUrl(): String? = cover
        ?: (lastVideo ?: firstVideo ?: video ?: videos?.firstOrNull())?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    fun sameSource(o: Tape) = list == o.list && video == o.video && videos == o.videos

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        putOpt("list", list); putOpt("video", video)
        if (videos != null) put("videos", JSONArray(videos))
        put("colorInt", color); putOpt("cover", cover)
        put("added", added); put("lastPlayed", lastPlayed)
        putOpt("lastVideo", lastVideo)
        if (count != null) put("count", count)
        putOpt("firstVideo", firstVideo)
    }

    companion object {
        fun fromJson(o: JSONObject): Tape? {
            val list = o.str("list")
            val video = o.str("video")
            val videos = o.optJSONArray("videos")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?.takeIf { it.isNotEmpty() }
            if (list == null && video == null && videos == null) return null
            val color = if (o.has("colorInt")) o.getInt("colorInt") else try {
                android.graphics.Color.parseColor(o.optString("color"))
            } catch (e: Exception) { PALETTE[0] }
            return Tape(
                id = o.str("id") ?: ("t" + System.nanoTime()),
                name = o.str("name") ?: "Tape",
                list = list, video = if (list != null) null else video, videos = if (list != null) null else videos,
                color = color, cover = o.str("cover"),
                added = o.optLong("added", System.currentTimeMillis()),
                lastPlayed = o.optLong("lastPlayed", 0),
                lastVideo = o.str("lastVideo"),
                count = if (o.has("count") && !o.isNull("count")) o.optInt("count") else null,
                firstVideo = o.str("firstVideo"),
            )
        }
    }
}

data class Settings(
    val theme: String = "dark",
    val whimsy: Boolean = false,
    val skin: String = "classic",
    val foldToPlay: Boolean = true,
    val pauseOnUnfold: Boolean = false,
    val returnToDeck: Boolean = true,
    val keepScreenOn: Boolean = true,
    val hiss: Boolean = true,
    val clicks: Boolean = true,
    val haptics: Boolean = true,
    val stars: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("theme", theme); put("whimsy", whimsy); put("skin", skin)
        put("foldToPlay", foldToPlay); put("pauseOnUnfold", pauseOnUnfold)
        put("returnToDeck", returnToDeck); put("keepScreenOn", keepScreenOn)
        put("hiss", hiss); put("clicks", clicks); put("haptics", haptics); put("stars", stars)
    }

    companion object {
        fun fromJson(s: String?): Settings {
            val d = Settings()
            val o = try { JSONObject(s ?: "{}") } catch (e: Exception) { JSONObject() }
            return Settings(
                theme = o.optString("theme", d.theme),
                whimsy = o.optBoolean("whimsy", d.whimsy),
                skin = o.optString("skin", d.skin),
                foldToPlay = o.optBoolean("foldToPlay", d.foldToPlay),
                pauseOnUnfold = o.optBoolean("pauseOnUnfold", d.pauseOnUnfold),
                returnToDeck = o.optBoolean("returnToDeck", d.returnToDeck),
                keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
                hiss = o.optBoolean("hiss", d.hiss),
                clicks = o.optBoolean("clicks", d.clicks),
                haptics = o.optBoolean("haptics", d.haptics),
                stars = o.optBoolean("stars", d.stars),
            )
        }
    }
}

data class Parsed(val list: String?, val video: String?, val error: String?)

/** Understands YouTube Music / YouTube share links, plain playlist IDs and video IDs. */
fun parseLink(raw: String): Parsed {
    val bad = Parsed(null, null, "That doesn't look like a YouTube Music or YouTube link.")
    val s = Regex("https?://\\S+").find(raw)?.value ?: raw.trim()
    if (s.isEmpty()) return Parsed(null, null, "Paste a link first.")
    if (!s.startsWith("http")) {
        if (Regex("^(PL|OLAK5uy_|RD|UU|FL|LM)[\\w-]*$").matches(s)) return Parsed(s, null, null)
        if (VIDEO_ID.matches(s)) return Parsed(null, s, null)
        return bad
    }
    val u = Uri.parse(s)
    val path = u.path ?: ""
    var list = u.getQueryParameter("list")
    if (list == null) Regex("/browse/VL([\\w-]+)").find(path)?.let { list = it.groupValues[1] }
    var video = u.getQueryParameter("v")
    if (video == null && u.host?.endsWith("youtu.be") == true) video = path.trimStart('/').take(11).ifEmpty { null }
    if (video == null) Regex("/(?:shorts|embed|live)/([\\w-]{11})").find(path)?.let { video = it.groupValues[1] }
    if (list == null && path.contains("/browse/MPRE"))
        return Parsed(null, null, "For albums, use Share → Copy link on the album page (the link should contain \"list=\").")
    if (list == null && video == null) return bad
    return Parsed(list, if (list != null) null else video, null)
}

object Store {
    private var prefs: SharedPreferences? = null

    val tapes = MutableStateFlow<List<Tape>>(emptyList())
    val settings = MutableStateFlow(Settings())
    /** The tape sitting in the deck. */
    val insertedId = MutableStateFlow<String?>(null)
    /** The tape YouTube Music was last told to play. */
    val activeTapeId = MutableStateFlow<String?>(null)
    /** While set, YouTube Music is still switching over from this song to a newly started tape. */
    val awaitFrom = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val pendingShare = MutableStateFlow<String?>(null)
    val pendingPlay = MutableStateFlow<String?>(null)

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("kasetka", Context.MODE_PRIVATE)
        prefs = p
        tapes.value = parseTapes(p.getString("tapes", "[]"))
        settings.value = Settings.fromJson(p.getString("settings", "{}"))
        insertedId.value = p.getString("inserted", null)
        activeTapeId.value = p.getString("active", null)
        if (!p.getBoolean("fix_resume_v2", false)) {
            // Earlier builds could save the wrong resume song / track count; start those fresh once.
            setTapes(tapes.value.map { it.copy(lastVideo = null, count = it.videos?.size) })
            p.edit().putBoolean("fix_resume_v2", true).apply()
        }
        // Look up first song / name / cover for playlist tapes that don't have it yet
        scope.launch { tapes.value.filter { it.list != null && it.firstVideo == null }.forEach { ensureMeta(it, rename = false) } }
    }

    /** Fetches a playlist's title and first song from YouTube's public oEmbed endpoint and saves them on the tape. */
    suspend fun ensureMeta(t: Tape, rename: Boolean): Tape {
        val list = t.list ?: return t
        if (t.firstVideo != null || list == "LM" || list == "LL") return t
        val meta = withContext(Dispatchers.IO) {
            try {
                val u = "https://www.youtube.com/oembed?format=json&url=" +
                    URLEncoder.encode("https://www.youtube.com/playlist?list=$list", "UTF-8")
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText())
            } catch (e: Exception) { null }
        } ?: return t
        val first = Regex("/vi/([A-Za-z0-9_-]{11})/").find(meta.optString("thumbnail_url"))?.groupValues?.get(1)
        val title = meta.optString("title").takeIf { it.isNotBlank() }
        update(t.id) {
            it.copy(
                firstVideo = first ?: it.firstVideo,
                cover = it.cover ?: first?.let { v -> "https://i.ytimg.com/vi/$v/hqdefault.jpg" },
                name = if (rename && title != null) title.take(40) else it.name,
            )
        }
        return tape(t.id) ?: t
    }

    private fun parseTapes(s: String?): List<Tape> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { Tape.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun persist() {
        prefs?.edit()
            ?.putString("tapes", JSONArray(tapes.value.map { it.toJson() }).toString())
            ?.putString("settings", settings.value.toJson().toString())
            ?.putString("inserted", insertedId.value)
            ?.putString("active", activeTapeId.value)
            ?.apply()
    }

    fun tape(id: String?): Tape? = tapes.value.find { it.id == id }
    fun nextColor() = PALETTE[tapes.value.size % PALETTE.size]

    fun setTapes(l: List<Tape>) { tapes.value = l; persist() }
    fun add(t: Tape) = setTapes(listOf(t) + tapes.value)
    fun update(id: String, f: (Tape) -> Tape) = setTapes(tapes.value.map { if (it.id == id) f(it) else it })
    fun remove(id: String) {
        if (insertedId.value == id) insertedId.value = null
        if (activeTapeId.value == id) activeTapeId.value = null
        setTapes(tapes.value.filter { it.id != id })
    }

    fun setInserted(id: String?) { insertedId.value = id; persist() }
    fun setSettings(s: Settings) { settings.value = s; persist() }

    fun markStarted(id: String) {
        activeTapeId.value = id
        update(id) { it.copy(lastPlayed = System.currentTimeMillis()) }
    }

    /** Remember the song a playlist tape is on, so it resumes there next time. */
    fun rememberVideo(videoId: String?) {
        if (videoId == null || !VIDEO_ID.matches(videoId)) return
        val t = tape(activeTapeId.value) ?: return
        if (t.list == null || t.lastVideo == videoId) return
        update(t.id) { it.copy(lastVideo = videoId) }
    }

    /** Import the backup text from the web version (Settings → Copy backup). */
    fun importBackup(text: String): Int {
        val arr = try {
            val t = text.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("tapes")
        } catch (e: Exception) { null } ?: return -1
        val incoming = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Tape.fromJson(it) } }
        val fresh = incoming.filter { n -> tapes.value.none { it.sameSource(n) } }
            .mapIndexed { i, t -> if (tape(t.id) != null) t.copy(id = "t${System.currentTimeMillis()}_$i") else t }
        if (fresh.isNotEmpty()) setTapes(fresh + tapes.value)
        return fresh.size
    }
}
