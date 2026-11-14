package com.anavmarinova.kasetka

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthesised deck sounds: key clicks, wheel ticks, the tape clunk and soft tape hiss.
 *  Played as sonification so they never pause YouTube Music. */
object Sfx {
    enum class Kind { CLICK, TICK, CLUNK }

    private const val RATE = 44100
    private val tracks = HashMap<Kind, AudioTrack>()
    private var hiss: AudioTrack? = null
    private var hissOn = false

    private fun build(samples: ShortArray, loop: Boolean = false): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(samples, 0, samples.size)
        if (loop) t.setLoopPoints(0, samples.size, -1)
        return t
    }

    private fun noiseBurst(seconds: Double, amp: Double, decay: Double): ShortArray {
        val n = (RATE * seconds).toInt()
        var prev = 0.0
        return ShortArray(n) { i ->
            val x = Random.nextDouble(-1.0, 1.0)
            val y = (x - prev) * 0.5            // crude high-pass for a crisp click
            prev = x
            (y * amp * exp(-i / (RATE * decay)) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun clunk(): ShortArray {
        val n = (RATE * 0.16).toInt()
        var phase = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = 140.0 * Math.pow(55.0 / 140.0, t / 0.16)
            phase += 2 * PI * f / RATE
            val env = exp(-t / 0.05)
            val v = sin(phase) * 0.55 * env + Random.nextDouble(-1.0, 1.0) * 0.18 * exp(-t / 0.02)
            (v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun track(k: Kind): AudioTrack = tracks.getOrPut(k) {
        build(when (k) {
            Kind.CLICK -> noiseBurst(0.035, 0.7, 0.008)
            Kind.TICK -> noiseBurst(0.014, 0.35, 0.003)
            Kind.CLUNK -> clunk()
        })
    }

    fun play(k: Kind) {
        if (!Store.settings.value.clicks) return
        try {
            val t = track(k)
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) { }
    }

    fun setHiss(on: Boolean) {
        if (on == hissOn) return
        hissOn = on
        try {
            if (on) {
                val h = hiss ?: build(noiseBurst(1.0, 0.05, 1e9), loop = true).also { hiss = it }
                h.setVolume(0.35f)
                h.play()
            } else hiss?.pause()
        } catch (e: Exception) { }
    }
}
