package com.anavmarinova.kasetka

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Required by Android so Kasetka may see and control YouTube Music's player. It does nothing with notifications. */
class KasetkaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Store.init(this)
        MediaHub.connect(this)
    }
}

data class QItem(val id: Long, val title: String, val subtitle: String, val icon: Bitmap?)

data class Deck(
    val permission: Boolean = false,
    val connected: Boolean = false,
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
)

/** Watches YouTube Music's media session and sends it play/pause/skip commands. */
object MediaHub {
    const val YTM = "com.google.android.apps.youtube.music"

    val state = MutableStateFlow(Deck())
    private var controller: MediaController? = null
    private var appContext: Context? = null
    private var listening = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var widgetJob: Job? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pull()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pull()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = pull()
        override fun onSessionDestroyed() { controller = null; pull() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun hasPermission(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun connect(ctx: Context) {
        val c = ctx.applicationContext
        appContext = c
        if (!hasPermission(c)) {
            state.value = state.value.copy(permission = false, connected = false, playing = false)
            return
        }
        val msm = c.getSystemService(MediaSessionManager::class.java) ?: return
        val comp = ComponentName(c, KasetkaListener::class.java)
        try {
            if (!listening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, comp, main)
                listening = true
            }
            pick(msm.getActiveSessions(comp))
        } catch (e: SecurityException) {
            state.value = state.value.copy(permission = false, connected = false)
        }
    }

    private fun pick(list: List<MediaController>?) {
        val yt = list?.firstOrNull { it.packageName == YTM }
        if (yt?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = yt
            yt?.registerCallback(callback, main)
        }
        pull()
    }

    private fun pull() {
        val prev = state.value
        val c = controller
        val ctx = appContext
        if (c == null) {
            state.value = prev.copy(connected = false, playing = false,
                permission = ctx?.let { hasPermission(it) } ?: prev.permission)
            changed(prev)
            return
        }
        val m = c.metadata
        val s = c.playbackState
        val q = try {
            c.queue?.map {
                QItem(it.queueId, it.description.title?.toString() ?: "",
                    it.description.subtitle?.toString() ?: "", it.description.iconBitmap)
            }
        } catch (e: Exception) { null } ?: emptyList()
        state.value = Deck(
            permission = true,
            connected = true,
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            mediaId = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            playing = s?.state == PlaybackState.STATE_PLAYING || s?.state == PlaybackState.STATE_BUFFERING,
            position = s?.position ?: 0,
            positionAt = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            queue = q,
            activeQueueId = s?.activeQueueItemId ?: -1,
        )
        changed(prev)
    }

    private fun changed(prev: Deck) {
        val now = state.value
        if (now.mediaId != prev.mediaId) {
            if (Store.awaitFrom.value != null && now.mediaId != Store.awaitFrom.value) Store.awaitFrom.value = null
            if (Store.awaitFrom.value == null) Store.rememberVideo(now.mediaId)
        }
        if (now.title != prev.title || now.playing != prev.playing || now.connected != prev.connected ||
            now.art !== prev.art || now.permission != prev.permission) refreshWidget()
    }

    fun refreshWidget() {
        val ctx = appContext ?: return
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(400)
            try { KasetkaWidget().updateAll(ctx) } catch (e: Exception) { }
        }
    }

    fun positionNow(d: Deck = state.value): Long =
        if (d.playing) d.position + ((SystemClock.elapsedRealtime() - d.positionAt) * d.speed).toLong()
        else d.position

    private val tc get() = controller?.transportControls
    fun play() { tc?.play() }
    fun pause() { tc?.pause() }
    fun toggle() { if (state.value.playing) pause() else play() }
    fun next() { tc?.skipToNext() }
    fun prev() { if (positionNow() > 3000) tc?.seekTo(0) else tc?.skipToPrevious() }
    fun seekBy(ms: Long) { tc?.seekTo((positionNow() + ms).coerceAtLeast(0)) }
    fun skipTo(queueId: Long) { tc?.skipToQueueItem(queueId) }
}

/** Starts a tape in YouTube Music, then (optionally) hops straight back to the deck. */
object Launcher {
    fun ytIntent(t: Tape): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(t.playUrl()))
            .setPackage(MediaHub.YTM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun start(activity: ComponentActivity, tape: Tape) {
        activity.lifecycleScope.launch {
            val t = Store.ensureMeta(tape, rename = false)
            // Until YouTube Music actually switches songs, don't treat what's playing as this tape
            if (Store.activeTapeId.value != t.id) {
                Store.awaitFrom.value = MediaHub.state.value.mediaId ?: ""
                Store.scope.launch { delay(12000); Store.awaitFrom.value = null }
            }
            openInYtm(activity, t)
        }
    }

    private fun openInYtm(activity: Activity, t: Tape) {
        Store.markStarted(t.id)
        val yt = ytIntent(t)
        try {
            if (!t.canAutoplay) {
                activity.startActivity(yt)
                Toast.makeText(activity, "Tap ▶ in YouTube Music to start this tape", Toast.LENGTH_LONG).show()
            } else if (Store.settings.value.returnToDeck) {
                val back = Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                activity.startActivities(arrayOf(yt, back))
            } else activity.startActivity(yt)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Install YouTube Music to play tapes", Toast.LENGTH_LONG).show()
        }
    }
}
