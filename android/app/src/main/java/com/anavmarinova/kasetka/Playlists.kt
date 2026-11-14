package com.anavmarinova.kasetka

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the songs of a public/unlisted YouTube playlist from its public web page.
 * YouTube's embedded player won't load whole playlists inside apps, but it plays songs one by one,
 * so Kasetka hands it the playlist's songs as a list of song IDs.
 */
object Playlists {
    data class Song(val id: String, val title: String, val artist: String)

    private val cache = HashMap<String, List<Song>>()

    suspend fun songs(listId: String): List<Song> {
        cache[listId]?.let { return it }
        val html = withContext(Dispatchers.IO) {
            try {
                val c = URL("https://www.youtube.com/playlist?list=$listId&hl=en").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                // Skips the EU cookie-consent page so the playlist itself comes back
                c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+cb")
                // Ask for the regular desktop page; the mobile page packs its data differently
                c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                val code = c.responseCode
                if (code == 200) c.inputStream.bufferedReader().readText()
                else { MediaHub.log("playlist page answered $code"); null }
            } catch (e: Exception) { MediaHub.log("playlist page failed: ${e.message}"); null }
        } ?: return emptyList()
        val songs = parse(html)
        if (songs.isNotEmpty()) {
            cache[listId] = songs
            songs.forEach { Titles.put(it.id, it.title, it.artist) }
        }
        MediaHub.log("playlist $listId: ${songs.size} songs found")
        return songs
    }

    private fun parse(html: String): List<Song> {
        val out = LinkedHashMap<String, Song>()
        // Structured data first (gives titles and artists)
        val start = html.indexOf("ytInitialData")
        if (start >= 0) {
            val brace = html.indexOf('{', start)
            val end = html.indexOf(";</script>", brace)
            if (brace > 0 && end > brace) {
                try { walk(JSONObject(html.substring(brace, end)), out) } catch (e: Exception) { }
            }
        }
        // Fallbacks: song IDs in the page (plain, or the mobile page's escaped form)
        if (out.isEmpty()) {
            val plain = html.replace("\\x22", "\"").replace("\\x7b", "{").replace("\\x7d", "}")
            Regex("(?:playlistVideoRenderer|playlistPanelVideoRenderer)\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").findAll(plain).forEach {
                val id = it.groupValues[1]
                if (id !in out) out[id] = Song(id, "", "")
            }
        }
        return out.values.toList()
    }

    private val playable = HashMap<String, Boolean>()

    /** Keeps only songs whose owners allow them to play outside YouTube (checked once, then remembered). */
    suspend fun onlyPlayable(ids: List<String>): List<String> {
        val todo = ids.filter { it !in playable }
        if (todo.isNotEmpty()) withContext(Dispatchers.IO) {
            todo.chunked(8).forEach { chunk ->
                val results = chunk.map { id ->
                    async {
                        id to try {
                            val u = "https://www.youtube.com/oembed?format=json&url=" +
                                java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
                            val c = URL(u).openConnection() as HttpURLConnection
                            c.connectTimeout = 6000; c.readTimeout = 6000
                            c.responseCode == 200
                        } catch (e: Exception) { true }   // network hiccup: let the player try it
                    }
                }.map { it.await() }
                results.forEach { (id, ok) -> playable[id] = ok }
            }
        }
        val ok = ids.filter { playable[it] != false }
        MediaHub.log("${ok.size} of ${ids.size} songs are allowed to play outside YouTube")
        return ok
    }

    private fun text(o: JSONObject?, key: String): String {
        val t = o?.optJSONObject(key) ?: return ""
        t.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = t.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }
    }

    private fun walk(node: Any?, out: LinkedHashMap<String, Song>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("playlistVideoRenderer")?.let { r ->
                    val id = r.optString("videoId")
                    if (id.length == 11 && id !in out) out[id] = Song(id, text(r, "title"), text(r, "shortBylineText"))
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
        }
    }
}
