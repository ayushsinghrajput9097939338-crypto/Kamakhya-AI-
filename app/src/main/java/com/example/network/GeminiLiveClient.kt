package com.example.network

import android.net.Uri
import android.util.Base64
import com.example.security.GeminiAuthMode
import com.example.security.GeminiLiveSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class AssistantOrbState {
    DISCONNECTED,
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

enum class LiveConnectionStatus {
    DISCONNECTED,
    CONNECTING_HANDSHAKE,
    WAITING_FOR_SETUP_COMPLETE,
    READY,
    ERROR
}

interface GeminiLiveEventsListener {
    fun onSetupCompleted()
    fun onInputTranscription(text: String)
    fun onOutputTranscription(text: String)
    fun onModelTextPart(text: String)
    fun onAudioPcmChunkReceived(pcmBytes: ByteArray, sampleRateHz: Int)
    fun onInterrupted()
    fun onTurnComplete()
    fun onSessionError(userMessage: String)
    fun onDisconnected(reason: String)
}

/**
 * Implements the Official Gemini Live WebSocket API Protocol (BidiGenerateContent).
 * - Safely builds the URL with URL-encoded "key" or "access_token" without logging credentials.
 * - Sends the initial {"setup": ...} JSON frame immediately upon connection.
 * - Waits for the server's setupComplete confirmation before enabling normal conversation.
 * - Streams 16,000 Hz mono PCM audio via {"realtimeInput": {"audio": ...}} and sends
 *   {"realtimeInput": {"audioStreamEnd": true}} when microphone capture pauses/stops.
 */
class GeminiLiveClient(
    private val listener: GeminiLiveEventsListener
) {
    private val parserScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep WebSocket stream open
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    private val isSetupConfirmed = AtomicBoolean(false)

    private val _connectionStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<LiveConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow("Not connected")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    /**
     * Builds the initial setup JSON message strictly matching the official Gemini Live API specification.
     * Does not include unsupported thinking parameters for gemini-3.8-live.
     */
    fun buildSetupJsonMessage(modelId: String): String {
        val trimmedModel = modelId.trim().ifEmpty { GeminiLiveSettings.DEFAULT_MODEL_ID }
        val normalizedModel = if (trimmedModel.startsWith("models/")) {
            trimmedModel
        } else {
            "models/$trimmedModel"
        }

        val systemInstructionPart = JSONObject().apply {
            put(
                "text",
                "You are Kamakhya AI, a helpful real-time voice assistant. Understand English and Hindi, including natural Hinglish. Reply conversationally and clearly. Use tools only when they are explicitly available and permitted. Do not claim an action succeeded unless it actually succeeded."
            )
        }
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(systemInstructionPart))
        }
        val setupObj = JSONObject().apply {
            put("model", normalizedModel)
            put("responseModalities", JSONArray().put("AUDIO"))
            put("systemInstruction", systemInstruction)
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        }
        return JSONObject().apply {
            put("setup", setupObj)
        }.toString()
    }

    /**
     * Safely constructs the WebSocket connection URL with URL-encoded query parameter.
     * Never prints or logs the resulting URL.
     */
    fun buildAuthenticatedUrl(settings: GeminiLiveSettings): Result<String> {
        val base = settings.wsEndpoint.trim()
        if (!base.startsWith("wss://") && !base.startsWith("ws://")) {
            return Result.failure(IllegalArgumentException("WebSocket endpoint must start with wss:// or ws://"))
        }
        val secret = settings.activeCredential
        if (secret.isEmpty()) {
            val missingType = if (settings.authMode == GeminiAuthMode.EPHEMERAL_TOKEN) {
                "Ephemeral token"
            } else {
                "Gemini API key"
            }
            return Result.failure(IllegalArgumentException("$missingType is missing. Please configure it in Settings → Gemini Live."))
        }

        val paramName = if (settings.authMode == GeminiAuthMode.EPHEMERAL_TOKEN) "access_token" else "key"
        val encodedSecret = URLEncoder.encode(secret, StandardCharsets.UTF_8.name())
        val separator = if (base.contains("?")) "&" else "?"
        val fullUrl = "$base$separator$paramName=$encodedSecret"
        // Convert wss:// to https:// for OkHttp Request.Builder compatibility
        val httpCompatibleUrl = when {
            fullUrl.startsWith("wss://") -> "https://" + fullUrl.removePrefix("wss://")
            fullUrl.startsWith("ws://") -> "http://" + fullUrl.removePrefix("ws://")
            else -> fullUrl
        }
        return Result.success(httpCompatibleUrl)
    }

    fun connect(settings: GeminiLiveSettings) {
        disconnect()
        val urlResult = buildAuthenticatedUrl(settings)
        val finalUrl = urlResult.getOrElse { err ->
            _connectionStatus.value = LiveConnectionStatus.ERROR
            val msg = err.message ?: "Invalid Gemini Live configuration."
            _statusDetail.value = msg
            listener.onSessionError(msg)
            return
        }

        isSetupConfirmed.set(false)
        _connectionStatus.value = LiveConnectionStatus.CONNECTING_HANDSHAKE
        _statusDetail.value = "Connecting to Gemini Live (${settings.modelId})…"

        val request = try {
            Request.Builder().url(finalUrl).build()
        } catch (_: Exception) {
            _connectionStatus.value = LiveConnectionStatus.ERROR
            val msg = "Malformed Gemini Live WebSocket URL in Settings."
            _statusDetail.value = msg
            listener.onSessionError(msg)
            return
        }

        activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionStatus.value = LiveConnectionStatus.WAITING_FOR_SETUP_COMPLETE
                _statusDetail.value = "Handshake open; awaiting session setup confirmation…"
                val setupPayload = buildSetupJsonMessage(settings.modelId)
                val sent = webSocket.send(setupPayload)
                if (!sent) {
                    handleFatalError("Failed to transmit initial setup message over WebSocket.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parserScope.launch {
                    parseIncomingJsonFrame(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parserScope.launch {
                    val decodedText = bytes.utf8()
                    parseIncomingJsonFrame(decodedText)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasSetup = isSetupConfirmed.getAndSet(false)
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                }
                val cleanReason = sanitizeErrorText(reason).ifBlank { "Code $code" }
                if (!wasSetup && code != 1000) {
                    _connectionStatus.value = LiveConnectionStatus.ERROR
                    val errMsg = "Session closed before setup completed ($cleanReason). Verify model ID '${settings.modelId}' and API key."
                    _statusDetail.value = errMsg
                    listener.onSessionError(errMsg)
                } else {
                    _connectionStatus.value = LiveConnectionStatus.DISCONNECTED
                    _statusDetail.value = "Disconnected ($cleanReason)"
                    listener.onDisconnected(cleanReason)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSetupConfirmed.set(false)
                if (activeWebSocket === webSocket) {
                    activeWebSocket = null
                }
                val httpCode = response?.code
                val msg = when (httpCode) {
                    400 -> "HTTP 400 Bad Request: Check model ID (${settings.modelId}) or setup parameters."
                    401, 403 -> "Authentication rejected (HTTP $httpCode): Check your Gemini API key permissions."
                    404 -> "Endpoint or model not found (HTTP 404): Verify WebSocket URL and model '${settings.modelId}'."
                    429 -> "Quota or rate limit exceeded (HTTP 429)."
                    else -> "Connection failed: ${t.javaClass.simpleName} (${sanitizeErrorText(t.message ?: "Network unreachable")})"
                }
                handleFatalError(msg)
            }
        })
    }

    private fun handleFatalError(message: String) {
        isSetupConfirmed.set(false)
        _connectionStatus.value = LiveConnectionStatus.ERROR
        _statusDetail.value = message
        listener.onSessionError(message)
    }

    private fun parseIncomingJsonFrame(rawJson: String) {
        val root = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return
        }

        // 1. Check server error payload
        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            val code = errObj?.optInt("code", -1) ?: -1
            val status = errObj?.optString("status", "") ?: ""
            val message = sanitizeErrorText(errObj?.optString("message", "Server returned an error.") ?: "Server error")
            handleFatalError("Gemini Live Server Error ($code $status): $message")
            return
        }

        // 2. Check setupComplete confirmation
        if (root.has("setupComplete")) {
            if (isSetupConfirmed.compareAndSet(false, true)) {
                _connectionStatus.value = LiveConnectionStatus.READY
                _statusDetail.value = "Connected & setup confirmed"
                listener.onSetupCompleted()
            }
        }

        // 3. Parse serverContent
        val serverContent = root.optJSONObject("serverContent") ?: return

        // Interruption handling
        if (serverContent.optBoolean("interrupted", false)) {
            listener.onInterrupted()
        }

        // Input transcription (user's spoken words)
        val inputTransObj = serverContent.optJSONObject("inputTranscription")
            ?: serverContent.optJSONObject("input_transcription")
        if (inputTransObj != null) {
            val userSpokenText = inputTransObj.optString("text", "")
            if (userSpokenText.isNotEmpty()) {
                listener.onInputTranscription(userSpokenText)
            }
        }

        // Output transcription (assistant's spoken words)
        val outputTransObj = serverContent.optJSONObject("outputTranscription")
            ?: serverContent.optJSONObject("output_transcription")
        if (outputTransObj != null) {
            val assistantSpokenText = outputTransObj.optString("text", "")
            if (assistantSpokenText.isNotEmpty()) {
                listener.onOutputTranscription(assistantSpokenText)
            }
        }

        // Model turn parts (audio inlineData and/or text)
        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val textPart = part.optString("text", "")
                if (textPart.isNotEmpty() && !part.optBoolean("thought", false)) {
                    listener.onModelTextPart(textPart)
                }

                val inlineData = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType", inlineData.optString("mime_type", "audio/pcm;rate=24000"))
                    val base64Data = inlineData.optString("data", "")
                    if (base64Data.isNotEmpty() && mimeType.startsWith("audio/pcm")) {
                        val sampleRate = parseSampleRateFromMime(mimeType, 24000)
                        try {
                            val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            if (pcmBytes.isNotEmpty()) {
                                listener.onAudioPcmChunkReceived(pcmBytes, sampleRate)
                            }
                        } catch (_: IllegalArgumentException) {
                            // Ignore malformed base64 chunk
                        }
                    }
                }
            }
        }

        // Turn complete
        if (serverContent.optBoolean("turnComplete", false) ||
            serverContent.optBoolean("turn_complete", false)
        ) {
            listener.onTurnComplete()
        }
    }

    /**
     * Sends a 16,000 Hz 16-bit mono little-endian PCM chunk in the documented protocol structure:
     * {"realtimeInput": {"audio": {"data": "BASE64_PCM_CHUNK", "mimeType": "audio/pcm;rate=16000"}}}
     */
    fun sendRealtimeAudioChunk(pcm16kBytes: ByteArray): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isSetupConfirmed.get() || pcm16kBytes.isEmpty()) return false

        val base64Chunk = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
        val audioObj = JSONObject().apply {
            put("data", base64Chunk)
            put("mimeType", "audio/pcm;rate=16000")
        }
        val realtimeInput = JSONObject().apply {
            put("audio", audioObj)
        }
        val root = JSONObject().apply {
            put("realtimeInput", realtimeInput)
        }
        return ws.send(root.toString())
    }

    /**
     * Sends {"realtimeInput": {"audioStreamEnd": true}} when the microphone stream is paused or stopped.
     */
    fun sendAudioStreamEnd(): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isSetupConfirmed.get()) return false

        val realtimeInput = JSONObject().apply {
            put("audioStreamEnd", true)
        }
        val root = JSONObject().apply {
            put("realtimeInput", realtimeInput)
        }
        return ws.send(root.toString())
    }

    /**
     * Sends text input using the documented "realtimeInput" text message structure:
     * {"realtimeInput": {"text": "..."}}
     */
    fun sendRealtimeTextInput(text: String): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isSetupConfirmed.get()) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false

        val realtimeInput = JSONObject().apply {
            put("text", clean)
        }
        val root = JSONObject().apply {
            put("realtimeInput", realtimeInput)
        }
        return ws.send(root.toString())
    }

    fun isSessionReady(): Boolean = isSetupConfirmed.get() && activeWebSocket != null

    fun disconnect() {
        isSetupConfirmed.set(false)
        activeWebSocket?.let { ws ->
            runCatching { ws.close(1000, "User disconnected") }
        }
        activeWebSocket = null
        _connectionStatus.value = LiveConnectionStatus.DISCONNECTED
        _statusDetail.value = "Disconnected"
    }

    /**
     * Tests the Gemini Live WebSocket connection and verifies that the server accepts
     * the initial setup message for the configured model ID.
     */
    suspend fun testConnection(settings: GeminiLiveSettings): Result<String> =
        withContext(Dispatchers.IO) {
            val urlResult = buildAuthenticatedUrl(settings)
            val url = urlResult.getOrElse { return@withContext Result.failure(it) }

            val deferred = CompletableDeferred<Result<String>>()
            val request = try {
                Request.Builder().url(url).build()
            } catch (_: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Invalid WebSocket endpoint URL."))
            }

            val testWs = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildSetupJsonMessage(settings.modelId))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleTestMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleTestMessage(webSocket, bytes.utf8())
                }

                private fun handleTestMessage(webSocket: WebSocket, jsonStr: String) {
                    val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: return
                    if (obj.has("setupComplete")) {
                        deferred.complete(
                            Result.success("Connected & verified setupComplete for model '${settings.modelId}'.")
                        )
                        webSocket.close(1000, "Test complete")
                    } else if (obj.has("error")) {
                        val errMsg = sanitizeErrorText(
                            obj.optJSONObject("error")?.optString("message") ?: "Setup rejected by server."
                        )
                        deferred.complete(
                            Result.failure(IllegalStateException("Server error: $errMsg"))
                        )
                        webSocket.close(1000, "Test error")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!deferred.isCompleted) {
                        deferred.complete(
                            Result.failure(
                                IllegalStateException(
                                    "WebSocket closed before setupComplete (code $code: ${sanitizeErrorText(reason)}). Model '${settings.modelId}' may be unavailable or key invalid."
                                )
                            )
                        )
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!deferred.isCompleted) {
                        val codeInfo = response?.code?.let { "HTTP $it" } ?: t.javaClass.simpleName
                        deferred.complete(
                            Result.failure(
                                IllegalStateException(
                                    "Handshake failed ($codeInfo): ${sanitizeErrorText(t.message ?: "Unable to reach endpoint")}"
                                )
                            )
                        )
                    }
                }
            })

            val outcome = withTimeoutOrNull(12_000L) {
                deferred.await()
            } ?: run {
                runCatching { testWs.cancel() }
                Result.failure(IllegalStateException("Timed out waiting for setupComplete from Gemini Live endpoint."))
            }
            outcome
        }

    private fun parseSampleRateFromMime(mimeType: String, defaultRate: Int): Int {
        val match = Regex("rate=(\\d+)").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: defaultRate
    }

    private fun sanitizeErrorText(raw: String): String {
        // Ensure query parameters like key=... or access_token=... are never leaked in error strings
        return raw.replace(Regex("(key|access_token)=[^&\\s]+"), "$1=[REDACTED]")
            .take(220)
    }
}
