package com.anavmarinova.kasetka

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Required by Android so Kasetka may see and control YouTube Music's player (Premium mode). It doesn't read notifications. */
class KasetkaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Store.init(this)
        Ytm.connect(this)
    }
}

/**
 * Premium mode: YouTube Music does the playing (your account, no ads), Kasetka is the remote control.
 * It fills the same deck state as the built-in player, so the deck, widget and track list work the same.
 */
object Ytm {
    const val PKG = "com.google.android.apps.youtube.music"

    private var controller: MediaController? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    /** Tape we asked YouTube Music to start; confirmed once its song changes. */
    private var pendingTape: String? = null
    private var staleVid: String? = null
    private var expectVid: String? = null

    val active: Boolean get() = Store.settings.value.useYtm

    fun hasPermission(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun installed(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage(PKG) != null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pull()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pull()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = pull()
        override fun onSessionDestroyed() { controller = null; pull() }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun connect(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        if (!hasPermission(c)) return
        val msm = c.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(c, KasetkaListener::class.java)
        try {
            if (!listening) { msm.addOnActiveSessionsChangedListener(sessions, comp, main); listening = true }
            pick(msm.getActiveSessions(comp))
        } catch (e: SecurityException) { }
    }

    private fun pick(list: List<MediaController>?) {
        val yt = list?.firstOrNull { it.packageName == PKG }
        if (yt?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = yt
            yt?.registerCallback(callback, main)
            if (yt != null) MediaHub.log("YouTube Music connected (quiet start ${if (supportsUri()) "supported" else "not supported"})")
        }
        pull()
    }

    private fun supportsUri(): Boolean =
        ((controller?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_URI) != 0L

    fun pull() {
        if (!active) return
        val prev = MediaHub.state.value
        val c = controller
        if (c == null) {
            MediaHub.state.value = prev.copy(connected = false, playing = false)
            MediaHub.refreshWidget(); return
        }
        val m = c.metadata
        val s = c.playbackState
        val vid = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        var tapeId = prev.tapeId
        if (pendingTape != null && vid != null && (vid != staleVid || vid == expectVid)) {
            tapeId = pendingTape; pendingTape = null; staleVid = null
            MediaHub.log("YouTube Music is playing the tape")
        }
        val q = try {
            c.queue?.map {
                QItem(it.queueId, it.description.title?.toString() ?: "", it.description.subtitle?.toString() ?: "",
                    it.description.iconBitmap, it.description.mediaId)
            }
        } catch (e: Exception) { null } ?: emptyList()
        val now = Deck(
            permission = true, connected = true, tapeId = tapeId,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            mediaId = vid,
            playing = s?.state == PlaybackState.STATE_PLAYING || s?.state == PlaybackState.STATE_BUFFERING,
            position = s?.position ?: 0,
            positionAt = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            queue = q,
            activeQueueId = s?.activeQueueItemId ?: -1,
        )
        MediaHub.state.value = now
        if (tapeId != null && pendingTape == null) {
            if (vid != prev.mediaId) Store.rememberVideo(vid)
            if (q.isNotEmpty()) Store.learnFirstVideo(q.first().mediaId)
        }
        if (now.title != prev.title || now.playing != prev.playing || now.tapeId != prev.tapeId || now.art !== prev.art)
            MediaHub.refreshWidget()
    }

    private suspend fun firstVideo(t: Tape): String? =
        t.lastVideo ?: t.firstVideo
            ?: t.list?.takeIf { it != "LM" && it != "LL" && !it.startsWith("RD") }?.let { Playlists.songs(it).firstOrNull()?.id }
            ?: t.video

    private fun url(t: Tape, v: String?): String = when {
        t.list != null && v != null -> "https://music.youtube.com/watch?v=$v&list=${t.list}"
        t.list != null -> "https://music.youtube.com/playlist?list=${t.list}"
        !t.videos.isNullOrEmpty() -> "https://music.youtube.com/watch_videos?video_ids=${t.videos.joinToString(",")}"
        else -> "https://music.youtube.com/watch?v=${t.video}"
    }

    private suspend fun waitSwitch(ms: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (pendingTape != null && SystemClock.elapsedRealtime() < end) delay(150)
        return pendingTape == null
    }

    /** Start a tape in YouTube Music: quietly if it allows, otherwise by opening it briefly. */
    fun start(ctx: Context, t: Tape) {
        Store.scope.launch {
            Store.markStarted(t.id)
            val v = firstVideo(t)
            val link = url(t, v)
            staleVid = MediaHub.state.value.mediaId ?: ""
            expectVid = v
            pendingTape = t.id
            MediaHub.state.value = MediaHub.state.value.copy(tapeId = null)
            MediaHub.log("YouTube Music ← $link")
            val c = controller
            if (c != null && supportsUri()) {
                c.transportControls.playFromUri(Uri.parse(link), Bundle())
                if (waitSwitch(3500)) return@launch
                MediaHub.log("quiet start didn't switch, opening YouTube Music")
            }
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                pendingTape = null
                Toast.makeText(ctx, "Install YouTube Music to use Premium mode", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (v == null && t.videos.isNullOrEmpty()) {
                Toast.makeText(ctx, "Tap ▶ Play in YouTube Music — next time Kasetka starts this tape by itself.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val ok = waitSwitch(8000)
            delay(400)
            val app = appCtx ?: return@launch
            try {
                app.startActivity(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (e: Exception) { }
            if (!ok) { pendingTape = null; MediaHub.log("YouTube Music didn't switch to the tape") }
        }
    }

    private val tc get() = controller?.transportControls
    fun play() { tc?.play() }
    fun pause() { tc?.pause() }
    fun next() { tc?.skipToNext() }
    fun prev() { if (MediaHub.positionNow() > 3000) tc?.seekTo(0) else tc?.skipToPrevious() }
    fun seekTo(ms: Long) { tc?.seekTo(ms.coerceAtLeast(0)) }
    fun skipTo(queueId: Long) { tc?.skipToQueueItem(queueId) }
}
