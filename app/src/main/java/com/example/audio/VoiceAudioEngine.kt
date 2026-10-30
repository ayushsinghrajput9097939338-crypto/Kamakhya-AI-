package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * A small, thread-safe half-duplex gate. AudioRecord may keep reading while the assistant speaks,
 * but microphone PCM is NOT forwarded to Gemini until assistant playback has physically drained.
 */
internal class MicrophonePlaybackGate {
    private val captureActive = AtomicBoolean(false)
    private val assistantPlaybackActive = AtomicBoolean(false)

    fun onCaptureStarted() {
        captureActive.set(true)
    }

    fun onCaptureStopped() {
        captureActive.set(false)
    }

    fun onAssistantPlaybackStarted() {
        assistantPlaybackActive.set(true)
    }

    fun onAssistantPlaybackFinished() {
        assistantPlaybackActive.set(false)
    }

    fun shouldForwardMicrophoneAudio(): Boolean =
        captureActive.get() && !assistantPlaybackActive.get()
}

/**
 * Handles 16 kHz mono PCM microphone capture and 24 kHz mono PCM playback.
 *
 * Important: the microphone remains locally active during assistant playback, but its PCM chunks
 * are discarded. Uplink is reopened only after Gemini marks the response turn complete, the
 * AudioTrack playback head reaches the last written frame, and a short acoustic tail guard passes.
 */
class VoiceAudioEngine(private val context: Context) {

    private sealed class PlaybackCommand {
        data class Pcm(val bytes: ByteArray) : PlaybackCommand()
        data object EndTurn : PlaybackCommand()
    }

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackLock = Any()
    private val microphoneGate = MicrophonePlaybackGate()

    private var captureJob: Job? = null
    @Volatile private var audioRecord: AudioRecord? = null
    private val isRecordingAtomic = AtomicBoolean(false)

    private var playbackJob: Job? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var playbackQueue = Channel<PlaybackCommand>(Channel.UNLIMITED)
    private val playbackGeneration = AtomicLong(0L)
    private val assistantPlaybackTurnActive = AtomicBoolean(false)
    private val playbackFinishQueued = AtomicBoolean(false)

    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

    private val _isRecording = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRecording: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRecording

    private val _isPlayingAudio = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isPlayingAudio: kotlinx.coroutines.flow.StateFlow<Boolean> = _isPlayingAudio

    private val _micLevel = kotlinx.coroutines.flow.MutableStateFlow(0f)
    val micLevel: kotlinx.coroutines.flow.StateFlow<Float> = _micLevel

    fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

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

    fun startMicrophoneCapture(
        onPcmChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (!hasRecordAudioPermission()) {
            onError("Microphone permission (RECORD_AUDIO) has not been granted.")
            return false
        }
        if (isRecordingAtomic.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            onError("Device audio hardware does not support 16,000 Hz 16-bit mono PCM capture.")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CAPTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES)
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
        attachVoiceCaptureEffects(record)
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
                microphoneGate.onCaptureStarted()

                while (isActive && isRecordingAtomic.get()) {
                    if (!hasRecordAudioPermission()) {
                        onError("Microphone permission was revoked during capture.")
                        break
                    }
                    val bytesRead = record.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        val chunk = readBuffer.copyOf(bytesRead)
                        _micLevel.value = calculateNormalizedRms(chunk)
                        // Critical echo-loop guard: never forward the assistant's own speaker audio.
                        if (microphoneGate.shouldForwardMicrophoneAudio()) {
                            onPcmChunk(chunk)
                        }
                    } else if (bytesRead < 0) {
                        onError("AudioRecord read error (code $bytesRead).")
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                onError("Microphone capture stopped due to error: ${t.javaClass.simpleName}")
            } finally {
                isRecordingAtomic.set(false)
                microphoneGate.onCaptureStopped()
                _isRecording.value = false
                _micLevel.value = 0f
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                }
                releaseVoiceCaptureEffects()
                runCatching { record.release() }
                if (audioRecord === record) audioRecord = null
            }
        }
        return true
    }

    private fun attachVoiceCaptureEffects(record: AudioRecord) {
        releaseVoiceCaptureEffects()
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
    }

    private fun releaseVoiceCaptureEffects() {
        runCatching { echoCanceler?.enabled = false }
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.enabled = false }
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }

    suspend fun stopMicrophoneCapture(): Boolean {
        val wasRecording = isRecordingAtomic.getAndSet(false)
        microphoneGate.onCaptureStopped()
        _isRecording.value = false
        _micLevel.value = 0f
        runCatching {
            audioRecord?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
        }
        captureJob?.cancelAndJoin()
        captureJob = null
        return wasRecording
    }

    /** Called as soon as the first assistant PCM chunk arrives; this closes the mic uplink. */
    fun enqueuePcmPlaybackChunk(pcmBytes: ByteArray, sampleRateHz: Int = PLAYBACK_SAMPLE_RATE) {
        if (pcmBytes.isEmpty()) return

        synchronized(playbackLock) {
            val queue = playbackQueue
            microphoneGate.onAssistantPlaybackStarted()
            assistantPlaybackTurnActive.set(true)
            playbackFinishQueued.set(false)
            playbackGeneration.incrementAndGet()
            _isPlayingAudio.value = true

            if (!ensurePlaybackWorkerRunningLocked(sampleRateHz, queue)) {
                assistantPlaybackTurnActive.set(false)
                _isPlayingAudio.value = false
                microphoneGate.onAssistantPlaybackFinished()
                return
            }

            if (queue.trySend(PlaybackCommand.Pcm(pcmBytes.copyOf())).isFailure) {
                assistantPlaybackTurnActive.set(false)
                _isPlayingAudio.value = false
                microphoneGate.onAssistantPlaybackFinished()
            }
        }
    }

    /**
     * Called from Gemini's final turnComplete event. The worker waits for all queued PCM to be
     * written AND for AudioTrack's playback head to consume every written frame before reopening
     * microphone uplink. Repeated turnComplete events are idempotent.
     */
    fun finishAssistantPlaybackTurn(): Boolean {
        if (!assistantPlaybackTurnActive.get()) return false
        if (!playbackFinishQueued.compareAndSet(false, true)) return true
        val sent = playbackQueue.trySend(PlaybackCommand.EndTurn).isSuccess
        if (!sent) {
            playbackFinishQueued.set(false)
            assistantPlaybackTurnActive.set(false)
            _isPlayingAudio.value = false
            microphoneGate.onAssistantPlaybackFinished()
        }
        return sent
    }

    private fun ensurePlaybackWorkerRunningLocked(
        sampleRateHz: Int,
        queue: Channel<PlaybackCommand>
    ): Boolean {
        if (playbackJob?.isActive == true && audioTrack != null) return true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

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
            return false
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track.release() }
            return false
        }

        audioTrack = track
        playbackJob = engineScope.launch {
            var framesWrittenThisTurn = 0L
            var playbackHeadAtTurnStart = 0L
            try {
                track.play()
                for (command in queue) {
                    when (command) {
                        is PlaybackCommand.Pcm -> {
                            if (framesWrittenThisTurn == 0L) {
                                playbackHeadAtTurnStart = playbackHeadFrames(track)
                            }
                            var offset = 0
                            while (offset < command.bytes.size && isActive) {
                                val written = track.write(
                                    command.bytes,
                                    offset,
                                    command.bytes.size - offset,
                                    AudioTrack.WRITE_BLOCKING
                                )
                                if (written <= 0) break
                                offset += written
                                framesWrittenThisTurn += written / BYTES_PER_FRAME
                            }
                        }
                        PlaybackCommand.EndTurn -> {
                            val generationAtEnd = playbackGeneration.get()
                            awaitAudioTrackDrain(
                                track,
                                playbackHeadAtTurnStart,
                                framesWrittenThisTurn,
                                sampleRateHz
                            )
                            delay(POST_PLAYBACK_GUARD_MS)

                            // If a later chunk arrived while draining, keep the mic muted.
                            if (isActive && generationAtEnd == playbackGeneration.get() &&
                                playbackFinishQueued.get() && assistantPlaybackTurnActive.get()
                            ) {
                                playbackFinishQueued.set(false)
                                assistantPlaybackTurnActive.set(false)
                                _isPlayingAudio.value = false
                                microphoneGate.onAssistantPlaybackFinished()
                            }
                            framesWrittenThisTurn = 0L
                            playbackHeadAtTurnStart = playbackHeadFrames(track)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The ViewModel will still be able to disconnect/reconnect; do not leave mic gated.
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
                synchronized(playbackLock) {
                    if (playbackQueue === queue && audioTrack === track) {
                        audioTrack = null
                        playbackJob = null
                        assistantPlaybackTurnActive.set(false)
                        playbackFinishQueued.set(false)
                        _isPlayingAudio.value = false
                        if (isRecordingAtomic.get()) microphoneGate.onAssistantPlaybackFinished()
                    }
                }
            }
        }
        return true
    }

    private suspend fun awaitAudioTrackDrain(
        track: AudioTrack,
        turnStartHead: Long,
        framesWrittenThisTurn: Long,
        sampleRateHz: Int
    ) {
        if (framesWrittenThisTurn <= 0L) return
        val start = SystemClock.elapsedRealtime()
        val playedAtStart = framesPlayedSince(track, turnStartHead)
        val remainingFrames = (framesWrittenThisTurn - playedAtStart).coerceAtLeast(0L)
        val expectedMs = (remainingFrames * 1000L / sampleRateHz).coerceAtMost(MAX_DRAIN_WAIT_MS)
        val deadline = start + expectedMs + DRAIN_TIMEOUT_MARGIN_MS

        while (currentCoroutineContext().isActive &&
            framesPlayedSince(track, turnStartHead) < framesWrittenThisTurn &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            delay(DRAIN_POLL_INTERVAL_MS)
        }
    }

    private fun playbackHeadFrames(track: AudioTrack): Long =
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    /** Handles AudioTrack's unsigned 32-bit playback-frame counter, including wrap-around. */
    private fun framesPlayedSince(track: AudioTrack, startHead: Long): Long =
        (playbackHeadFrames(track) - startHead) and 0xFFFFFFFFL

    /** Flushes playback immediately, e.g. on server interruption or explicit user interrupt. */
    fun interruptAndFlushPlayback() {
        val oldJob: Job?
        val oldTrack: AudioTrack?
        val oldQueue: Channel<PlaybackCommand>

        synchronized(playbackLock) {
            oldQueue = playbackQueue
            playbackQueue = Channel(Channel.UNLIMITED)
            playbackGeneration.incrementAndGet()
            oldJob = playbackJob
            playbackJob = null
            oldTrack = audioTrack
            audioTrack = null
        }

        oldQueue.cancel()
        oldJob?.cancel()
        oldTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }

        playbackFinishQueued.set(false)
        assistantPlaybackTurnActive.set(false)
        _isPlayingAudio.value = false
        microphoneGate.onAssistantPlaybackFinished()
    }

    suspend fun releaseAll() {
        stopMicrophoneCapture()
        interruptAndFlushPlayback()
        engineScope.cancel()
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
        private const val CHUNK_SIZE_BYTES = 3200 // ~100 ms, 16 kHz mono 16-bit PCM
        private const val BYTES_PER_FRAME = 2L // mono, 16-bit PCM
        private const val POST_PLAYBACK_GUARD_MS = 500L
        private const val DRAIN_POLL_INTERVAL_MS = 10L
        private const val DRAIN_TIMEOUT_MARGIN_MS = 2500L
        private const val MAX_DRAIN_WAIT_MS = 60_000L
    }
}
