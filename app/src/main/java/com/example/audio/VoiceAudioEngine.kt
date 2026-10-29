package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Handles native Android 16,000 Hz 16-bit mono PCM microphone capture via AudioRecord
 * and 24,000 Hz 16-bit mono PCM streaming playback via AudioTrack off the main thread.
 */
class VoiceAudioEngine(private val context: Context) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var captureJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private val isRecordingAtomic = AtomicBoolean(false)

    private var playbackJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var playbackQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val isPlayingAtomic = AtomicBoolean(false)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlayingAudio = MutableStateFlow(false)
    val isPlayingAudio: StateFlow<Boolean> = _isPlayingAudio.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun checkAudioRecord16kSupport(): String {
        val minBuf = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return if (minBuf > 0) {
            "Supported (minBuffer = $minBuf bytes @ 16,000 Hz 16-bit Mono PCM)"
        } else {
            "Unavailable or unsupported by hardware (code = $minBuf)"
        }
    }

    fun checkAudioTrack24kSupport(): String {
        val minBuf = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        return if (minBuf > 0) {
            "Supported (minBuffer = $minBuf bytes @ 24,000 Hz 16-bit Mono PCM)"
        } else {
            "Unavailable (code = $minBuf)"
        }
    }

    /**
     * Starts streaming 16,000 Hz, 16-bit mono little-endian PCM chunks via [onPcmChunk].
     */
    fun startMicrophoneCapture(
        onPcmChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (!hasRecordAudioPermission()) {
            onError("Microphone permission (RECORD_AUDIO) has not been granted.")
            return false
        }
        if (isRecordingAtomic.get()) {
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            onError("Device audio hardware does not support 16,000 Hz 16-bit mono PCM capture.")
            return false
        }

        val bufferSizeInBytes = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )
        } catch (se: SecurityException) {
            onError("Microphone access denied by Android security check.")
            return false
        } catch (t: Throwable) {
            onError("Failed to initialize AudioRecord: ${t.javaClass.simpleName}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            onError("AudioRecord failed to initialize (microphone may be in use by another app).")
            return false
        }

        audioRecord = record
        isRecordingAtomic.set(true)
        _isRecording.value = true

        captureJob = engineScope.launch {
            val readBuffer = ByteArray(CHUNK_SIZE_BYTES)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    onError("Microphone failed to enter recording state.")
                    return@launch
                }

                while (isActive && isRecordingAtomic.get()) {
                    if (!hasRecordAudioPermission()) {
                        onError("Microphone permission was revoked during capture.")
                        break
                    }
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        val chunk = readBuffer.copyOf(bytesRead)
                        _micLevel.value = calculateNormalizedRms(chunk)
                        onPcmChunk(chunk)
                    } else if (bytesRead < 0) {
                        onError("AudioRecord read error (code $bytesRead).")
                        break
                    }
                }
            } catch (t: Throwable) {
                onError("Microphone capture stopped due to error: ${t.javaClass.simpleName}")
            } finally {
                isRecordingAtomic.set(false)
                _isRecording.value = false
                _micLevel.value = 0f
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                }
                runCatching { record.release() }
                if (audioRecord === record) {
                    audioRecord = null
                }
            }
        }
        return true
    }

    suspend fun stopMicrophoneCapture(): Boolean {
        val wasRecording = isRecordingAtomic.getAndSet(false)
        _isRecording.value = false
        _micLevel.value = 0f
        runCatching {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            }
        }
        captureJob?.cancelAndJoin()
        captureJob = null
        return wasRecording
    }

    /**
     * Queues incoming 24,000 Hz 16-bit mono little-endian PCM bytes for sequential playback.
     */
    fun enqueuePcmPlaybackChunk(pcmBytes: ByteArray, sampleRateHz: Int = PLAYBACK_SAMPLE_RATE) {
        if (pcmBytes.isEmpty()) return
        ensurePlaybackWorkerRunning(sampleRateHz)
        playbackQueue.trySend(pcmBytes)
    }

    private fun ensurePlaybackWorkerRunning(sampleRateHz: Int) {
        if (playbackJob?.isActive == true && audioTrack != null) return

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, 9600))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) {
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return
        }

        audioTrack = track
        playbackJob = engineScope.launch {
            try {
                track.play()
                for (chunk in playbackQueue) {
                    if (!isActive) break
                    isPlayingAtomic.set(true)
                    _isPlayingAudio.value = true
                    var offset = 0
                    while (offset < chunk.size && isActive) {
                        val written = track.write(
                            chunk,
                            offset,
                            chunk.size - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) break
                        offset += written
                    }
                    if (playbackQueue.isEmpty) {
                        isPlayingAtomic.set(false)
                        _isPlayingAudio.value = false
                    }
                }
            } catch (_: Throwable) {
                // Interrupted or stopped
            } finally {
                isPlayingAtomic.set(false)
                _isPlayingAudio.value = false
                runCatching { track.stop() }
                runCatching { track.release() }
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
        }
    }

    /**
     * Immediately stops and flushes ongoing assistant voice playback (e.g. on user barge-in
     * or server interruption event).
     */
    fun interruptAndFlushPlayback() {
        val oldQueue = playbackQueue
        playbackQueue = Channel(Channel.UNLIMITED)
        oldQueue.cancel()

        playbackJob?.cancel()
        playbackJob = null

        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
        audioTrack = null
        isPlayingAtomic.set(false)
        _isPlayingAudio.value = false
    }

    suspend fun releaseAll() {
        stopMicrophoneCapture()
        interruptAndFlushPlayback()
    }

    private fun calculateNormalizedRms(pcm16Le: ByteArray): Float {
        if (pcm16Le.size < 2) return 0f
        var sumSquares = 0.0
        val sampleCount = pcm16Le.size / 2
        for (i in 0 until sampleCount) {
            val low = pcm16Le[i * 2].toInt() and 0xFF
            val high = pcm16Le[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return (rms * 3.5f).coerceIn(0f, 1f)
    }

    companion object {
        const val CAPTURE_SAMPLE_RATE = 16000
        const val PLAYBACK_SAMPLE_RATE = 24000
        // ~100ms of 16kHz 16-bit mono audio = 3200 bytes
        private const val CHUNK_SIZE_BYTES = 3200
    }
}
