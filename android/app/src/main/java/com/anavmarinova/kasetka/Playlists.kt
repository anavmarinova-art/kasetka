package com.anavmarinova.kasetka

import kotlinx.coroutines.Dispatchers
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
                c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
                if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
            } catch (e: Exception) { null }
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
        // Fallback: any playlist song IDs in the page
        if (out.isEmpty()) {
            Regex("\"playlistVideoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").findAll(html).forEach {
                val id = it.groupValues[1]
                if (id !in out) out[id] = Song(id, "", "")
            }
        }
        return out.values.toList()
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
