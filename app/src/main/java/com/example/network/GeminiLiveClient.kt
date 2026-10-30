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

data class GeminiFunctionCall(
    /** Optional in the Live API schema; include it in the response when supplied. */
    val id: String?,
    val name: String,
    val args: JSONObject
)

data class GeminiFunctionResponse(
    /** Optional in the Live API schema; include it in the response when supplied. */
    val id: String?,
    val name: String,
    val response: JSONObject
)

interface GeminiLiveEventsListener {
    fun onSetupCompleted()
    fun onFunctionCalls(functionCalls: List<GeminiFunctionCall>)
    fun onInputTranscription(text: String)
    fun onOutputTranscription(text: String)
    fun onModelTextPart(text: String)
    fun onAudioPcmChunkReceived(pcmBytes: ByteArray, sampleRateHz: Int)
    fun onInterrupted()
    /** True only when interactionStatus is IDLE (or absent); IN_PROGRESS is not a final turn. */
    fun onTurnComplete(isFinal: Boolean)
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
                "You are Kamakhya AI, a real-time voice assistant for Hindi, English and natural Hinglish. " +
                    "You are connected to Android app tools declared in this session. For a request to open an app, " +
                    "change brightness or media volume, open the dialer, set an alarm, open system settings, " +
                    "generate an image, create a website, or create an Android app project, call the matching function. " +
                    "Never say that you cannot perform an action when a matching declared function is available. " +
                    "Do not merely explain how to do the action: call the function and wait for its result. " +
                    "For phone calls and alarms, the app may display a confirmation request; never claim they are complete " +
                    "until the app reports success. For image generation, use generate_image and its prompt argument. " +
                    "Examples: 'YouTube open karo' means call open_installed_app with app_name='YouTube'; " +
                    "'यूट्यूब खोलो' means the same; 'एक बर्ड की इमेज बनाओ' means call generate_image with prompt='बर्ड'. " +
                    "Do not answer that you cannot open apps or generate images when the matching app tool is available. " +
                    "If a tool reports an error or missing configuration, explain that exact limitation. " +
                    "For ordinary conversation, speak naturally and keep the conversation going."
            )
        }
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(systemInstructionPart))
        }
        // Live API schema: response modalities belong inside generationConfig.
        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().put("AUDIO"))
        }
        val functionDeclarations = buildFunctionDeclarations()
        val tools = JSONArray().put(
            JSONObject().put("functionDeclarations", functionDeclarations)
        )
        val setupObj = JSONObject().apply {
            put("model", normalizedModel)
            put("generationConfig", generationConfig)
            put("systemInstruction", systemInstruction)
            put("tools", tools)
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        }
        return JSONObject().apply {
            put("setup", setupObj)
        }.toString()
    }

    /**
     * Function declarations exposed to Gemini 3.8 Live. The app executes these locally
     * and returns their results through the Live API toolResponse message.
     */
    private fun buildFunctionDeclarations(): JSONArray {
        fun declaration(
            name: String,
            description: String,
            properties: JSONObject,
            required: List<String>
        ): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("description", description)
                // Keep the live conversation responsive while the app executes the function.
                put("behavior", "NON_BLOCKING")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", properties)
                    put("required", JSONArray(required))
                })
            }
        }

        fun stringProperty(description: String): JSONObject = JSONObject().apply {
            put("type", "STRING")
            put("description", description)
        }

        fun integerProperty(description: String): JSONObject = JSONObject().apply {
            put("type", "INTEGER")
            put("description", description)
        }

        val declarations = JSONArray()
        declarations.put(declaration(
            "open_installed_app",
            "Open an installed Android app by its visible app name, for example YouTube, Chrome or WhatsApp.",
            JSONObject().put("app_name", stringProperty("Visible name of the installed app to launch.")),
            listOf("app_name")
        ))
        declarations.put(declaration(
            "set_screen_brightness",
            "Set the Android system screen brightness to an absolute percentage from 0 to 100.",
            JSONObject().put("percent", integerProperty("Desired screen brightness percentage, 0 to 100.")),
            listOf("percent")
        ))
        declarations.put(declaration(
            "set_media_volume",
            "Set Android media playback volume to an absolute percentage from 0 to 100.",
            JSONObject().put("percent", integerProperty("Desired media volume percentage, 0 to 100.")),
            listOf("percent")
        ))
        declarations.put(declaration(
            "open_phone_dialer",
            "Open the Android dialer with a contact name or phone number. The app will require the user to confirm before proceeding; this never places a call automatically.",
            JSONObject().put("contact_or_number", stringProperty("Contact name or phone number to prepare in the dialer.")),
            listOf("contact_or_number")
        ))
        declarations.put(declaration(
            "set_alarm",
            "Request an Android alarm using the system Clock app. The app will show a confirmation before opening the alarm UI.",
            JSONObject()
                .put("hour24", integerProperty("Hour in 24-hour format, 0 to 23."))
                .put("minute", integerProperty("Minute, 0 to 59."))
                .put("label", stringProperty("Short alarm label.")),
            listOf("hour24", "minute")
        ))
        declarations.put(declaration(
            "open_system_settings",
            "Open a supported Android system settings page. Supported values include system, display, sound, wifi, bluetooth, location and battery.",
            JSONObject().put("setting", stringProperty("Settings page category to open.")),
            listOf("setting")
        ))
        declarations.put(declaration(
            "generate_image",
            "Generate one image using the configured SenseNova image provider and save it in the app's local Images assets. Call this for spoken requests to create, draw or generate an image or picture.",
            JSONObject().put("prompt", stringProperty("The visual subject and details to generate. Do not include command wording such as 'make an image'.")),
            listOf("prompt")
        ))
        declarations.put(declaration(
            "create_website",
            "Create a local unpublished HTML/CSS/JavaScript website project in the app's Assets section.",
            JSONObject().put("prompt", stringProperty("Description of the website to create.")),
            listOf("prompt")
        ))
        declarations.put(declaration(
            "create_android_app",
            "Create a local Android app starter project record that can later be sent to the configured GitHub Actions build workflow. This does not itself guarantee a custom complete app or an APK.",
            JSONObject()
                .put("app_name", stringProperty("Name for the Android project."))
                .put("description", stringProperty("Description of the requested app.")),
            listOf("app_name", "description")
        ))
        return declarations
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
                    if (activeWebSocket === webSocket) {
                        parseIncomingJsonFrame(text)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                parserScope.launch {
                    if (activeWebSocket === webSocket) {
                        parseIncomingJsonFrame(bytes.utf8())
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Ignore callbacks from an older socket after a retry/new session has started.
                if (activeWebSocket !== webSocket) return
                val wasSetup = isSetupConfirmed.getAndSet(false)
                activeWebSocket = null
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
                // An obsolete socket must not change the status of a newer connection attempt.
                if (activeWebSocket !== webSocket) return
                isSetupConfirmed.set(false)
                activeWebSocket = null
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

        // 3. Execute function calls requested by Gemini. Live API requires the client
        // to execute each call and send a matching toolResponse with the same ID.
        val toolCall = root.optJSONObject("toolCall") ?: root.optJSONObject("tool_call")
        if (toolCall != null) {
            val callsJson = toolCall.optJSONArray("functionCalls")
                ?: toolCall.optJSONArray("function_calls")
            val calls = mutableListOf<GeminiFunctionCall>()
            if (callsJson != null) {
                for (index in 0 until callsJson.length()) {
                    val call = callsJson.optJSONObject(index) ?: continue
                    val id = call.optString("id", "").trim().takeIf { it.isNotEmpty() }
                    val name = call.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val args = call.optJSONObject("args") ?: JSONObject()
                    calls += GeminiFunctionCall(id = id, name = name, args = args)
                }
            }
            if (calls.isNotEmpty()) listener.onFunctionCalls(calls)
            return
        }

        // 4. Parse serverContent
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
            // Gemini 3.8 may emit turnComplete while async tool work is still running.
            // Do not clear the utterance or run local fallback until interactionStatus=IDLE.
            val interactionStatus = serverContent.optString("interactionStatus",
                serverContent.optString("interaction_status", "IDLE"))
            listener.onTurnComplete(isFinal = !interactionStatus.equals("IN_PROGRESS", ignoreCase = true))
        }
    }

    /** Sends function results back to Gemini using the dedicated Live API toolResponse frame. */
    fun sendFunctionResponses(responses: List<GeminiFunctionResponse>): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isSetupConfirmed.get() || responses.isEmpty()) return false

        val functionResponses = JSONArray()
        responses.forEach { item ->
            if (item.name.isNotBlank()) {
                functionResponses.put(JSONObject().apply {
                    item.id?.takeIf { it.isNotBlank() }?.let { put("id", it) }
                    put("name", item.name)
                    put("response", item.response)
                    // For NON_BLOCKING function declarations, wait for a natural pause
                    // before Gemini speaks the result. This is a top-level FunctionResponse field.
                    put("scheduling", "WHEN_IDLE")
                })
            }
        }
        if (functionResponses.length() == 0) return false
        val root = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", functionResponses)
        )
        return ws.send(root.toString())
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
