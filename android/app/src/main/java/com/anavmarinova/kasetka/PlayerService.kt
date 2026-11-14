package com.anavmarinova.kasetka

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the music going while Kasetka is minimised, and shows play/skip controls on the lock screen,
 *  in the notification shade and on the folded cover screen. */
class PlayerService : Service() {
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playing tape", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "Kasetka").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = MediaHub.play()
                override fun onPause() = MediaHub.pause()
                override fun onSkipToNext() = MediaHub.next()
                override fun onSkipToPrevious() = MediaHub.prev()
                override fun onSeekTo(pos: Long) = MediaHub.seekTo(pos)
                override fun onStop() = MediaHub.pause()
            })
            setSessionActivity(openApp())
            isActive = true
        }
        startForeground(ID, build(MediaHub.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.launch {
            MediaHub.state
                .map { Triple(it.title, it.playing, it.art) to (it.duration to it.artist) }
                .distinctUntilChanged()
                .collectLatest { update() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> MediaHub.toggle()
            ACT_NEXT -> MediaHub.next()
            ACT_PREV -> MediaHub.prev()
        }
        return START_NOT_STICKY
    }

    private fun update() {
        val d = MediaHub.state.value
        val tape = Store.tape(d.tapeId)
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, d.title.ifEmpty { tape?.name ?: "Kasetka" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, d.artist.removeSuffix(" - Topic"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, tape?.name ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, d.duration)
            .apply { d.art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
            .setState(if (d.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                MediaHub.positionNow(d), if (d.playing) 1f else 0f)
            .build())
        getSystemService(NotificationManager::class.java).notify(ID, build(d))
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(d: Deck): Notification {
        val tape = Store.tape(d.tapeId)
        fun act(icon: Int, label: String, action: String, code: Int) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(action, code)).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tape)
            .setContentTitle(d.title.ifEmpty { tape?.name ?: "Kasetka" })
            .setContentText(listOfNotNull(d.artist.removeSuffix(" - Topic").ifEmpty { null }, tape?.name).joinToString(" · "))
            .setLargeIcon(d.art)
            .setContentIntent(openApp())
            .setOngoing(d.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", ACT_PREV, 1))
            .addAction(if (d.playing) act(android.R.drawable.ic_media_pause, "Pause", ACT_TOGGLE, 2)
                       else act(android.R.drawable.ic_media_play, "Play", ACT_TOGGLE, 2))
            .addAction(act(android.R.drawable.ic_media_next, "Next", ACT_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val ID = 7
        const val ACT_TOGGLE = "kasetka.toggle"
        const val ACT_NEXT = "kasetka.next"
        const val ACT_PREV = "kasetka.prev"
    }
}
