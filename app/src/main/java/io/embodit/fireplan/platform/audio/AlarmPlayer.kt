package io.embodit.fireplan.platform.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pico.spatial.audio.SpatialAudioTrackExtension
import com.pico.spatial.core.ecs.Entity
import io.embodit.fireplan.platform.FireLog
import kotlin.math.PI
import kotlin.math.sin

/**
 * The smoke alarm: a T3 pattern (0.5 s on / 0.5 s off, three times, then 1.5 s off) of a 3.1 kHz tone,
 * synthesised into a static `AudioTrack` that loops. It is attached to an entity through the SDK's
 * `SpatialAudioTrackExtension` (object mode), so on a headset it sounds from the detector's place;
 * if the extension refuses (emulator, or no Full Space yet) the track plays plain, which is what the
 * emulator can verify anyway (design `verification.deviceOnly`).
 */
class AlarmPlayer {
    private var track: AudioTrack? = null
    private var extension: SpatialAudioTrackExtension? = null
    private var volume = 1f

    val playing: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    /** Starts the alarm from [source]; stops a running one first. Main thread. */
    fun start(source: Entity) {
        stop()
        val pcm = pattern()
        val builder =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
        var spatial = false
        val ext =
            runCatching {
                    SpatialAudioTrackExtension().also {
                        it.spatialAudioTrackExtensionConfig(mode = SpatialAudioTrackExtension.SpatialAudioMode.OBJECT, isAmbisonic = false, builder = builder)
                        it.attachToEntityWithBuilder(source, builder)
                        spatial = true
                    }
                }
                .onFailure { FireLog.w("ALARM spatial extension refused: $it") }
                .getOrNull()
        val t =
            runCatching { builder.build() }
                .onFailure { FireLog.e("ALARM track build failed: $it", it) }
                .getOrNull() ?: return
        runCatching {
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.setVolume(volume)
            t.play()
        }
            .onFailure { FireLog.e("ALARM play failed: $it", it) }
        track = t
        extension = if (spatial) ext else null
        FireLog.i("ALARM start spatial=$spatial volume=$volume")
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        runCatching { track?.setVolume(volume) }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun resume() {
        runCatching { track?.play() }
    }

    fun stop() {
        val t = track ?: return
        runCatching { t.stop() }
        runCatching { extension?.release() }
        runCatching { t.release() }
        track = null
        extension = null
        FireLog.i("ALARM stop")
    }

    /** One loop of the T3 pattern as 16-bit PCM. */
    private fun pattern(): ShortArray {
        val total = (RATE * LOOP_S).toInt()
        val out = ShortArray(total)
        val beeps = listOf(0f to 0.5f, 1f to 1.5f, 2f to 2.5f)
        for (i in 0 until total) {
            val t = i.toFloat() / RATE
            var on = false
            var env = 0f
            for ((a, b) in beeps) {
                if (t >= a && t < b) {
                    on = true
                    // 10 ms fades at both ends keep the beep from clicking
                    env = minOf(1f, (t - a) / 0.01f, (b - t) / 0.01f).coerceAtLeast(0f)
                }
            }
            if (!on) continue
            val s = sin(2.0 * PI * TONE_HZ * t) * 0.6 * env
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private companion object {
        const val RATE = 22050
        const val TONE_HZ = 3100.0

        /** 3 x (0.5 s on + 0.5 s off) + 1.5 s pause. */
        const val LOOP_S = 4.5f
    }
}
