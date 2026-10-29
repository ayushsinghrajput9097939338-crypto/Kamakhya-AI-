package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.actions.AppLaunchCandidate
import com.example.actions.ContactDialCandidate
import com.example.actions.PhoneActionOutcome
import com.example.actions.PhoneActionRequest
import com.example.actions.PhoneActionRouter
import com.example.actions.RoutedAssistantIntent
import com.example.audio.VoiceAudioEngine
import com.example.data.local.ActionLogEntity
import com.example.data.local.AssetCategory
import com.example.data.local.KamakhyaDatabase
import com.example.data.local.LocalAssetStorage
import com.example.data.local.SavedAssetEntity
import com.example.data.local.StorageUsageSummary
import com.example.data.local.WebsiteProjectFiles
import com.example.network.AiProvidersClient
import com.example.network.AssistantOrbState
import com.example.network.GeminiLiveClient
import com.example.network.GeminiLiveEventsListener
import com.example.network.GitHubWorkflowStatusReport
import com.example.network.LiveConnectionStatus
import com.example.security.GeminiLiveSettings
import com.example.security.GitHubBuildSettings
import com.example.security.HelioRouteSettings
import com.example.security.KeystoreVault
import com.example.security.SenseNovaSettings
import com.example.security.XKiroSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AppScreen {
    HOME,
    ASSETS,
    SETTINGS
}

enum class MessageSender {
    USER_VOICE,
    USER_TEXT,
    ASSISTANT_VOICE,
    ASSISTANT_TEXT,
    ACTION_ROUTER,
    SYSTEM_ERROR
}

data class ConversationMessage(
    val id: Long = System.nanoTime(),
    val sender: MessageSender,
    val title: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val linkedCategory: AssetCategory? = null
)

data class ProviderTestStatus(
    val isTesting: Boolean = false,
    val isSuccess: Boolean? = null,
    val message: String = ""
)

class KamakhyaViewModel(application: Application) : AndroidViewModel(application), GeminiLiveEventsListener {

    private val appContext = application.applicationContext
    val keystoreVault = KeystoreVault(appContext)
    private val database = KamakhyaDatabase.getInstance(appContext)
    val assetStorage = LocalAssetStorage(appContext, database.dao())
    val audioEngine = VoiceAudioEngine(appContext)
    val providersClient = AiProvidersClient()
    val phoneActionRouter = PhoneActionRouter(appContext)
    val geminiLiveClient = GeminiLiveClient(this)

    // Navigation state
    private val _currentScreen = MutableStateFlow(AppScreen.HOME)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _selectedAssetCategory = MutableStateFlow(AssetCategory.IMAGES)
    val selectedAssetCategory: StateFlow<AssetCategory> = _selectedAssetCategory.asStateFlow()

    // Reactive assets & action logs from Room
    val allAssets: StateFlow<List<SavedAssetEntity>> = assetStorage.allAssetsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogs: StateFlow<List<ActionLogEntity>> = assetStorage.actionLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Provider configurations
    private val _geminiSettings = MutableStateFlow(keystoreVault.loadGeminiSettings())
    val geminiSettings: StateFlow<GeminiLiveSettings> = _geminiSettings.asStateFlow()

    private val _senseNovaSettings = MutableStateFlow(keystoreVault.loadSenseNovaSettings())
    val senseNovaSettings: StateFlow<SenseNovaSettings> = _senseNovaSettings.asStateFlow()

    private val _helioRouteSettings = MutableStateFlow(keystoreVault.loadHelioRouteSettings())
    val helioRouteSettings: StateFlow<HelioRouteSettings> = _helioRouteSettings.asStateFlow()

    private val _xKiroSettings = MutableStateFlow(keystoreVault.loadXKiroSettings())
    val xKiroSettings: StateFlow<XKiroSettings> = _xKiroSettings.asStateFlow()

    private val _gitHubSettings = MutableStateFlow(keystoreVault.loadGitHubSettings())
    val gitHubSettings: StateFlow<GitHubBuildSettings> = _gitHubSettings.asStateFlow()

    // Test connection statuses
    private val _geminiTestStatus = MutableStateFlow(ProviderTestStatus())
    val geminiTestStatus: StateFlow<ProviderTestStatus> = _geminiTestStatus.asStateFlow()

    private val _senseNovaTestStatus = MutableStateFlow(ProviderTestStatus())
    val senseNovaTestStatus: StateFlow<ProviderTestStatus> = _senseNovaTestStatus.asStateFlow()

    private val _helioRouteTestStatus = MutableStateFlow(ProviderTestStatus())
    val helioRouteTestStatus: StateFlow<ProviderTestStatus> = _helioRouteTestStatus.asStateFlow()

    private val _xKiroTestStatus = MutableStateFlow(ProviderTestStatus())
    val xKiroTestStatus: StateFlow<ProviderTestStatus> = _xKiroTestStatus.asStateFlow()

    private val _gitHubTestStatus = MutableStateFlow(ProviderTestStatus())
    val gitHubTestStatus: StateFlow<ProviderTestStatus> = _gitHubTestStatus.asStateFlow()

    private val _latestGitHubRunReport = MutableStateFlow<GitHubWorkflowStatusReport?>(null)
    val latestGitHubRunReport: StateFlow<GitHubWorkflowStatusReport?> = _latestGitHubRunReport.asStateFlow()

    // Storage usage state
    private val _storageUsage = MutableStateFlow(
        StorageUsageSummary(0, 0, 0, 0, 0, appContext.filesDir.absolutePath)
    )
    val storageUsage: StateFlow<StorageUsageSummary> = _storageUsage.asStateFlow()

    // Voice & Conversation states
    val liveConnectionStatus: StateFlow<LiveConnectionStatus> = geminiLiveClient.connectionStatus
    val liveStatusDetail: StateFlow<String> = geminiLiveClient.statusDetail
    val isRecordingMic: StateFlow<Boolean> = audioEngine.isRecording
    val isPlayingVoice: StateFlow<Boolean> = audioEngine.isPlayingAudio
    val micLevel: StateFlow<Float> = audioEngine.micLevel

    private val _isProcessingTask = MutableStateFlow(false)
    val isProcessingTask: StateFlow<Boolean> = _isProcessingTask.asStateFlow()

    /**
     * Computed Orb State strictly respecting:
     * "Do not animate as if the AI is listening when microphone permission has not been granted or the connection is inactive."
     */
    val orbState: StateFlow<AssistantOrbState> = combine(
        liveConnectionStatus,
        isRecordingMic,
        isPlayingVoice,
        _isProcessingTask
    ) { connStatus, recording, speaking, processing ->
        when {
            speaking -> AssistantOrbState.SPEAKING
            processing -> AssistantOrbState.PROCESSING
            connStatus == LiveConnectionStatus.READY && recording && audioEngine.hasRecordAudioPermission() ->
                AssistantOrbState.LISTENING
            connStatus == LiveConnectionStatus.READY -> AssistantOrbState.IDLE
            connStatus == LiveConnectionStatus.CONNECTING_HANDSHAKE ||
                connStatus == LiveConnectionStatus.WAITING_FOR_SETUP_COMPLETE -> AssistantOrbState.PROCESSING
            else -> AssistantOrbState.DISCONNECTED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantOrbState.DISCONNECTED)

    private val _conversationMessages = MutableStateFlow<List<ConversationMessage>>(
        listOf(
            ConversationMessage(
                sender = MessageSender.ASSISTANT_TEXT,
                title = "Kamakhya AI Ready",
                text = "Namaste! Connect Gemini Live with your API key in Settings for real-time 16 kHz Hindi/English/Hinglish voice streaming, or use the command input below for Phone Actions (Open apps, Brightness, Volume, Dialer, Alarms, System Settings), Website Generation, Android App Generation, or configured AI providers."
            )
        )
    )
    val conversationMessages: StateFlow<List<ConversationMessage>> = _conversationMessages.asStateFlow()

    private val _pendingActionOutcome = MutableStateFlow<PhoneActionOutcome?>(null)
    val pendingActionOutcome: StateFlow<PhoneActionOutcome?> = _pendingActionOutcome.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    // Streaming transcript buffers for current turn
    private val currentInputTranscriptBuffer = StringBuilder()
    private val currentOutputTranscriptBuffer = StringBuilder()
    private var activeInputTranscriptMsgId: Long? = null
    private var activeOutputTranscriptMsgId: Long? = null

    init {
        refreshStorageUsage()
    }

    fun navigateTo(screen: AppScreen, category: AssetCategory? = null) {
        if (category != null) {
            _selectedAssetCategory.value = category
        }
        _currentScreen.value = screen
        if (screen == AppScreen.SETTINGS || screen == AppScreen.ASSETS) {
            refreshStorageUsage()
        }
    }

    fun selectAssetCategory(category: AssetCategory) {
        _selectedAssetCategory.value = category
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    fun dismissPendingActionOutcome() {
        _pendingActionOutcome.value = null
    }

    fun clearConversationHistory() {
        _conversationMessages.value = emptyList()
    }

    // =========================================================================
    // Gemini Live Session & Microphone Control
    // =========================================================================
    fun connectGeminiLiveSession() {
        val current = _geminiSettings.value
        geminiLiveClient.connect(current)
    }

    fun disconnectGeminiLiveSession() {
        viewModelScope.launch {
            val wasRecording = audioEngine.stopMicrophoneCapture()
            if (wasRecording && geminiLiveClient.isSessionReady()) {
                geminiLiveClient.sendAudioStreamEnd()
            }
            audioEngine.interruptAndFlushPlayback()
            geminiLiveClient.disconnect()
        }
    }

    fun toggleMicrophoneListening(onRequestMicPermission: () -> Unit) {
        if (!audioEngine.hasRecordAudioPermission()) {
            onRequestMicPermission()
            return
        }

        if (audioEngine.isRecording.value) {
            stopMicrophoneListening()
            return
        }

        // If assistant is currently speaking, interrupt playback first (barge-in)
        if (audioEngine.isPlayingAudio.value) {
            audioEngine.interruptAndFlushPlayback()
        }

        if (!geminiLiveClient.isSessionReady()) {
            val settings = _geminiSettings.value
            if (settings.activeCredential.isEmpty()) {
                appendMessage(
                    MessageSender.SYSTEM_ERROR,
                    "Gemini Live API Key Missing",
                    "Cannot start live microphone streaming because no Gemini API key is configured. Open Settings → Gemini Live to add your key, or use text commands below."
                )
                return
            }
            // Connect first; user can tap mic once setupComplete is confirmed
            geminiLiveClient.connect(settings)
            _statusBannerMessage.value = "Connecting to Gemini Live… Wait for 'Connected & setup confirmed', then tap the microphone."
            return
        }

        val started = audioEngine.startMicrophoneCapture(
            onPcmChunk = { pcm16k ->
                geminiLiveClient.sendRealtimeAudioChunk(pcm16k)
            },
            onError = { err ->
                viewModelScope.launch {
                    appendMessage(MessageSender.SYSTEM_ERROR, "Microphone Error", err)
                }
            }
        )
        if (!started) {
            _statusBannerMessage.value = "Could not start microphone capture."
        }
    }

    fun stopMicrophoneListening() {
        viewModelScope.launch {
            val wasRecording = audioEngine.stopMicrophoneCapture()
            if (wasRecording && geminiLiveClient.isSessionReady()) {
                // Send documented stream-ending message when automatic activity detection stream is paused/stopped
                geminiLiveClient.sendAudioStreamEnd()
            }
        }
    }

    fun interruptAssistantSpeech() {
        audioEngine.interruptAndFlushPlayback()
    }

    // GeminiLiveEventsListener callbacks
    override fun onSetupCompleted() {
        viewModelScope.launch {
            appendMessage(
                MessageSender.ASSISTANT_TEXT,
                "Gemini Live Connected",
                "Session setup confirmed for model '${_geminiSettings.value.modelId}'. Tap the microphone button to stream 16 kHz PCM voice audio or type a message."
            )
        }
    }

    override fun onInputTranscription(text: String) {
        viewModelScope.launch {
            currentInputTranscriptBuffer.append(text)
            val fullText = currentInputTranscriptBuffer.toString().trim()
            if (fullText.isEmpty()) return@launch
            val existingId = activeInputTranscriptMsgId
            if (existingId == null) {
                val msg = ConversationMessage(
                    sender = MessageSender.USER_VOICE,
                    title = "You (Voice Transcription)",
                    text = fullText
                )
                activeInputTranscriptMsgId = msg.id
                _conversationMessages.value = _conversationMessages.value + msg
            } else {
                _conversationMessages.value = _conversationMessages.value.map {
                    if (it.id == existingId) it.copy(text = fullText) else it
                }
            }
        }
    }

    override fun onOutputTranscription(text: String) {
        viewModelScope.launch {
            currentOutputTranscriptBuffer.append(text)
            val fullText = currentOutputTranscriptBuffer.toString().trim()
            if (fullText.isEmpty()) return@launch
            val existingId = activeOutputTranscriptMsgId
            if (existingId == null) {
                val msg = ConversationMessage(
                    sender = MessageSender.ASSISTANT_VOICE,
                    title = "Kamakhya AI (Voice Transcription)",
                    text = fullText
                )
                activeOutputTranscriptMsgId = msg.id
                _conversationMessages.value = _conversationMessages.value + msg
            } else {
                _conversationMessages.value = _conversationMessages.value.map {
                    if (it.id == existingId) it.copy(text = fullText) else it
                }
            }
        }
    }

    override fun onModelTextPart(text: String) {
        // If the server returns direct text parts alongside or instead of outputTranscription
        if (currentOutputTranscriptBuffer.isEmpty()) {
            onOutputTranscription(text)
        }
    }

    override fun onAudioPcmChunkReceived(pcmBytes: ByteArray, sampleRateHz: Int) {
        audioEngine.enqueuePcmPlaybackChunk(pcmBytes, sampleRateHz)
    }

    override fun onInterrupted() {
        audioEngine.interruptAndFlushPlayback()
        currentOutputTranscriptBuffer.clear()
        activeOutputTranscriptMsgId = null
    }

    override fun onTurnComplete() {
        viewModelScope.launch {
            val spokenUserCommand = currentInputTranscriptBuffer.toString().trim()
            currentInputTranscriptBuffer.clear()
            currentOutputTranscriptBuffer.clear()
            activeInputTranscriptMsgId = null
            activeOutputTranscriptMsgId = null

            // Check if the user's spoken utterance also matched a local device action or generator intent
            if (spokenUserCommand.isNotEmpty()) {
                val classified = phoneActionRouter.classifyIntent(spokenUserCommand)
                if (classified !is RoutedAssistantIntent.OrdinaryConversation) {
                    handleNonConversationRoutedIntent(classified)
                }
            }
        }
    }

    override fun onSessionError(userMessage: String) {
        viewModelScope.launch {
            audioEngine.stopMicrophoneCapture()
            audioEngine.interruptAndFlushPlayback()
            appendMessage(MessageSender.SYSTEM_ERROR, "Gemini Live Error", userMessage)
        }
    }

    override fun onDisconnected(reason: String) {
        viewModelScope.launch {
            audioEngine.stopMicrophoneCapture()
            audioEngine.interruptAndFlushPlayback()
        }
    }

    // =========================================================================
    // Central Intent Routing & Command Execution
    // =========================================================================
    fun submitUserTextOrCommand(rawInput: String) {
        val clean = rawInput.trim()
        if (clean.isEmpty()) return

        appendMessage(MessageSender.USER_TEXT, "You", clean)
        val routed = phoneActionRouter.classifyIntent(clean)

        if (routed is RoutedAssistantIntent.OrdinaryConversation) {
            handleOrdinaryConversationInput(clean)
        } else {
            handleNonConversationRoutedIntent(routed)
        }
    }

    private fun handleNonConversationRoutedIntent(routed: RoutedAssistantIntent) {
        viewModelScope.launch {
            when (routed) {
                is RoutedAssistantIntent.PhoneAction -> {
                    val outcome = phoneActionRouter.executePhoneAction(routed.request, confirmedByUser = false)
                    processPhoneActionOutcome(outcome)
                }
                is RoutedAssistantIntent.GenerateImage -> {
                    executeImageGenerationFlow(routed.prompt)
                }
                is RoutedAssistantIntent.GenerateVideo -> {
                    executeVideoGenerationFlow(routed.prompt)
                }
                is RoutedAssistantIntent.CreateWebsite -> {
                    executeWebsiteGenerationFlow(routed.prompt)
                }
                is RoutedAssistantIntent.CreateAndroidApp -> {
                    executeAndroidAppGenerationFlow(routed.prompt)
                }
                is RoutedAssistantIntent.OrdinaryConversation -> {
                    handleOrdinaryConversationInput(routed.message)
                }
            }
        }
    }

    private fun handleOrdinaryConversationInput(message: String) {
        viewModelScope.launch {
            // 1. If Gemini Live WebSocket session is active, send via documented realtimeInput text frame
            if (geminiLiveClient.isSessionReady()) {
                val sent = geminiLiveClient.sendRealtimeTextInput(message)
                if (sent) return@launch
            }

            // 2. Otherwise check if xKiro or HelioRoute chat is configured with an API key
            val xKiro = _xKiroSettings.value
            val helio = _helioRouteSettings.value

            if (xKiro.apiKey.isNotBlank()) {
                _isProcessingTask.value = true
                val res = providersClient.sendXKiroChat(xKiro, message)
                _isProcessingTask.value = false
                res.onSuccess { reply ->
                    appendMessage(MessageSender.ASSISTANT_TEXT, "Kamakhya AI (via xKiro ${xKiro.model})", reply)
                }.onFailure { err ->
                    appendMessage(
                        MessageSender.SYSTEM_ERROR,
                        "xKiro Request Failed",
                        err.message ?: "Provider request failed."
                    )
                }
                return@launch
            }

            if (helio.apiKey.isNotBlank()) {
                _isProcessingTask.value = true
                val res = providersClient.sendHelioRouteChat(helio, message)
                _isProcessingTask.value = false
                res.onSuccess { reply ->
                    appendMessage(MessageSender.ASSISTANT_TEXT, "Kamakhya AI (via HelioRoute ${helio.model})", reply)
                }.onFailure { err ->
                    appendMessage(
                        MessageSender.SYSTEM_ERROR,
                        "HelioRoute Request Failed",
                        err.message ?: "Provider request failed."
                    )
                }
                return@launch
            }

            // 3. No live voice session and no chat API key configured — report honestly without fake AI replies
            appendMessage(
                MessageSender.SYSTEM_ERROR,
                "AI Provider Not Connected",
                "No active Gemini Live session or configured chat API key (xKiro / HelioRoute). To chat with an AI model, add your API key in Settings and connect Gemini Live. You can still execute local Phone Actions (e.g. 'Volume 50 percent kar do', 'Open Settings', 'Kal subah 6 baje alarm laga do') or generate local Website / Android App projects right now."
            )
        }
    }

    // =========================================================================
    // Phone Action Execution & Disambiguation Handlers
    // =========================================================================
    fun confirmPendingPhoneAction(request: PhoneActionRequest) {
        viewModelScope.launch {
            _pendingActionOutcome.value = null
            val outcome = phoneActionRouter.executePhoneAction(request, confirmedByUser = true)
            processPhoneActionOutcome(outcome)
        }
    }

    fun selectAmbiguousAppToLaunch(candidate: AppLaunchCandidate) {
        viewModelScope.launch {
            _pendingActionOutcome.value = null
            val outcome = phoneActionRouter.launchPackageNow(candidate)
            processPhoneActionOutcome(outcome)
        }
    }

    fun selectContactOrManualDial(displayLabel: String, phoneNumber: String) {
        viewModelScope.launch {
            _pendingActionOutcome.value = null
            val outcome = phoneActionRouter.launchDialerWithNumber(displayLabel, phoneNumber)
            processPhoneActionOutcome(outcome)
        }
    }

    fun openSystemWriteSettingsPage() {
        phoneActionRouter.openWriteSettingsPermissionPage()
    }

    fun executeDirectPhoneActionFromSettings(request: PhoneActionRequest) {
        viewModelScope.launch {
            val outcome = phoneActionRouter.executePhoneAction(request, confirmedByUser = true)
            processPhoneActionOutcome(outcome)
        }
    }

    private suspend fun processPhoneActionOutcome(outcome: PhoneActionOutcome) {
        when (outcome) {
            is PhoneActionOutcome.Success -> {
                assetStorage.logAction(outcome.actionName, "SUCCESS", outcome.logSummary)
                appendMessage(MessageSender.ACTION_ROUTER, outcome.actionName, outcome.userMessage)
            }
            is PhoneActionOutcome.ConfirmationRequired -> {
                _pendingActionOutcome.value = outcome
                assetStorage.logAction(outcome.actionName, "REQUIRES_USER_ACTION", "Waiting for user confirmation")
            }
            is PhoneActionOutcome.AmbiguousAppSelection -> {
                _pendingActionOutcome.value = outcome
                assetStorage.logAction("Open Installed App", "AMBIGUOUS", "Multiple matching apps found")
            }
            is PhoneActionOutcome.AmbiguousContactSelection -> {
                _pendingActionOutcome.value = outcome
                assetStorage.logAction("Open Phone Dialer", "AMBIGUOUS", "Multiple matching contacts found")
            }
            is PhoneActionOutcome.WriteSettingsPermissionRequired -> {
                _pendingActionOutcome.value = outcome
                assetStorage.logAction("Adjust Screen Brightness", "PERMISSION_MISSING", "WRITE_SETTINGS required")
                appendMessage(MessageSender.ACTION_ROUTER, "Permission Required", outcome.explanation)
            }
            is PhoneActionOutcome.ContactsPermissionOrManualDialRequired -> {
                _pendingActionOutcome.value = outcome
                assetStorage.logAction("Open Phone Dialer", "REQUIRES_USER_ACTION", "Contacts permission or manual number required")
            }
            is PhoneActionOutcome.FailedOrUnavailable -> {
                assetStorage.logAction(outcome.actionName, "FAILED", outcome.logSummary)
                appendMessage(MessageSender.SYSTEM_ERROR, "${outcome.actionName} Unavailable", outcome.userMessage)
            }
        }
    }

    // =========================================================================
    // Provider Asset Generation Flows (Image, Video, Website, Android App)
    // =========================================================================
    fun executeImageGenerationFlow(prompt: String) {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val settings = _senseNovaSettings.value
            val result = providersClient.generateImageWithSenseNova(settings, prompt)
            _isProcessingTask.value = false

            result.onSuccess { payload ->
                val saved = assetStorage.saveImageBytes(
                    title = prompt.take(36),
                    prompt = payload.revisedPrompt,
                    bytes = payload.imageBytes,
                    mimeType = payload.mimeType,
                    provider = "SenseNova (${settings.model})"
                )
                saved.onSuccess { entity ->
                    refreshStorageUsage()
                    appendMessage(
                        sender = MessageSender.ACTION_ROUTER,
                        title = "Image Saved to Local Storage",
                        text = "Generated image '${entity.title}' (${entity.fileSizeBytes / 1024} KB) using SenseNova model '${settings.model}'. Tap to view in Assets → Images.",
                        linkedCategory = AssetCategory.IMAGES
                    )
                }.onFailure { saveErr ->
                    appendMessage(
                        MessageSender.SYSTEM_ERROR,
                        "Local Save Error",
                        saveErr.message ?: "Failed to save image to local storage."
                    )
                }
            }.onFailure { err ->
                appendMessage(
                    MessageSender.SYSTEM_ERROR,
                    "SenseNova Image Generation Unavailable",
                    "${err.message ?: "Failed to generate image."} You can configure your SenseNova API key/model in Settings, or import a local image in Assets."
                )
            }
        }
    }

    fun executeVideoGenerationFlow(prompt: String) {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val settings = _helioRouteSettings.value
            val result = providersClient.generateVideoWithHelioRoute(settings, prompt)
            _isProcessingTask.value = false

            result.onSuccess { payload ->
                val saved = assetStorage.saveVideoBytes(
                    title = prompt.take(36),
                    prompt = prompt,
                    bytes = payload.videoBytes,
                    mimeType = payload.mimeType,
                    provider = "HelioRoute (${settings.model})"
                )
                saved.onSuccess { entity ->
                    refreshStorageUsage()
                    appendMessage(
                        sender = MessageSender.ACTION_ROUTER,
                        title = "Video Saved to Local Storage",
                        text = "Downloaded and verified video '${entity.title}' (${entity.fileSizeBytes / 1024} KB). Open Assets → Videos to play.",
                        linkedCategory = AssetCategory.VIDEOS
                    )
                }.onFailure { saveErr ->
                    appendMessage(
                        MessageSender.SYSTEM_ERROR,
                        "Video Save Error",
                        saveErr.message ?: "Failed to save video file."
                    )
                }
            }.onFailure { err ->
                appendMessage(
                    MessageSender.SYSTEM_ERROR,
                    "Video Generation Not Completed",
                    "${err.message ?: "Video provider unavailable."} No fake video asset was created. You can review HelioRoute settings or generate a local interactive Website instead."
                )
            }
        }
    }

    fun executeWebsiteGenerationFlow(prompt: String, customTitle: String = "") {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val title = customTitle.trim().ifEmpty {
                prompt.replace(Regex("^(?:please\\s+)?(?:create|build|make|generate)\\s+(?:a\\s+)?(?:website|webpage|web page)\\s*(?:for|about|on)?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .take(36)
                    .ifEmpty { "Local Web Project" }
            }
            val saved = assetStorage.createWebsiteProject(
                title = title,
                userPrompt = prompt
            )
            _isProcessingTask.value = false

            saved.onSuccess { entity ->
                refreshStorageUsage()
                assetStorage.logAction("Website Generator", "SUCCESS", "Created local HTML/CSS/JS project")
                appendMessage(
                    sender = MessageSender.ACTION_ROUTER,
                    title = "Local Website Project Created",
                    text = "Created local project '${entity.title}' (index.html, styles.css, app.js) in app-owned storage. Status: Unpublished local project. Open Assets → Websites to preview in WebView or edit source.",
                    linkedCategory = AssetCategory.WEBSITES
                )
            }.onFailure { err ->
                appendMessage(
                    MessageSender.SYSTEM_ERROR,
                    "Website Generator Error",
                    err.message ?: "Could not create website files."
                )
            }
        }
    }

    fun executeAndroidAppGenerationFlow(prompt: String, customAppName: String = "") {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val appName = customAppName.trim().ifEmpty {
                prompt.replace(Regex("^(?:please\\s+)?(?:create|build|make|generate)\\s+(?:an?\\s+)?(?:android app|mobile app|apk)\\s*(?:for|about|on|named)?\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .take(30)
                    .ifEmpty { "Kamakhya Utility App" }
            }
            val workflowFile = _gitHubSettings.value.workflowFile.ifBlank { "android-build.yml" }
            val created = assetStorage.createAndroidAppProjectRecord(
                appName = appName,
                appDescription = prompt,
                workflowFileName = workflowFile
            )
            _isProcessingTask.value = false

            created.onSuccess { entity ->
                refreshStorageUsage()
                assetStorage.logAction("Android Project Generator", "SUCCESS", "Created local Android project source")
                appendMessage(
                    sender = MessageSender.ACTION_ROUTER,
                    title = "Android App Project Generated",
                    text = "Generated Gradle/Kotlin Compose project source for '${entity.title}'. To compile a real installable APK, configure GitHub Build Integration in Settings and tap 'Push & Trigger GitHub APK Build' inside Assets → Apps.",
                    linkedCategory = AssetCategory.APPS
                )
            }.onFailure { err ->
                appendMessage(
                    MessageSender.SYSTEM_ERROR,
                    "Android Project Generator Error",
                    err.message ?: "Failed to generate Android project files."
                )
            }
        }
    }

    /**
     * Pushes a generated Android app project to the user's configured GitHub repository
     * and dispatches the GitHub Actions workflow to build a real APK.
     */
    fun pushAppToGitHubAndTriggerBuild(asset: SavedAssetEntity) {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val ghSettings = _gitHubSettings.value
            val meta = runCatching { JSONObject(asset.metadataJson) }.getOrElse { JSONObject() }
            val packageName = meta.optString("packageName", "com.kamakhya.generated.app")
            val workflowFile = ghSettings.workflowFile.ifBlank { "android-build.yml" }

            val filesMap = assetStorage.generateAndroidProjectFilesMap(
                appName = asset.title,
                packageName = packageName,
                description = asset.description,
                workflowFileName = workflowFile
            )

            val pushRes = providersClient.pushProjectAndTriggerWorkflow(
                settings = ghSettings,
                projectFiles = filesMap,
                commitMessage = "Kamakhya AI build for ${asset.title}"
            )
            _isProcessingTask.value = false

            pushRes.onSuccess { msg ->
                val updatedMeta = meta.apply {
                    put("buildStatus", "WORKFLOW_DISPATCHED")
                    put("repository", "${ghSettings.owner}/${ghSettings.repo}")
                    put("branch", ghSettings.branch)
                }
                assetStorage.updateAssetRecord(
                    asset.copy(
                        status = "GITHUB_BUILD_DISPATCHED",
                        sourceProvider = "GitHub Actions (${ghSettings.owner}/${ghSettings.repo})",
                        metadataJson = updatedMeta.toString()
                    )
                )
                assetStorage.logAction("GitHub Build Integration", "SUCCESS", "Pushed source & triggered workflow")
                _statusBannerMessage.value = msg
            }.onFailure { err ->
                assetStorage.logAction("GitHub Build Integration", "FAILED", "Push/dispatch failed")
                _statusBannerMessage.value = err.message ?: "GitHub push failed."
            }
        }
    }

    /**
     * Polls the latest GitHub Actions workflow run for an App asset; if the workflow succeeded,
     * downloads and extracts the real .apk artifact into local storage.
     */
    fun pollGitHubBuildAndDownloadApk(asset: SavedAssetEntity? = null) {
        viewModelScope.launch {
            _isProcessingTask.value = true
            val ghSettings = _gitHubSettings.value
            val statusRes = providersClient.fetchLatestWorkflowRunStatus(ghSettings)

            statusRes.onSuccess { report ->
                _latestGitHubRunReport.value = report
                if (report.status == "completed" && report.conclusion == "success" && asset != null) {
                    val apkRes = providersClient.downloadApkFromWorkflowRun(ghSettings, report.runId)
                    apkRes.onSuccess { apkBytes ->
                        assetStorage.attachDownloadedApkToRecord(
                            asset = asset,
                            apkBytes = apkBytes,
                            runId = report.runId,
                            runUrl = report.htmlUrl
                        )
                        refreshStorageUsage()
                        assetStorage.logAction("GitHub APK Download", "SUCCESS", "Downloaded verified APK from run #${report.runId}")
                        _statusBannerMessage.value = "Downloaded real APK (${apkBytes.size / 1024} KB) from workflow run #${report.runId}!"
                    }.onFailure { dlErr ->
                        _statusBannerMessage.value = "Workflow succeeded (#${report.runId}), but APK download failed: ${dlErr.message}"
                    }
                } else {
                    if (asset != null) {
                        val meta = runCatching { JSONObject(asset.metadataJson) }.getOrElse { JSONObject() }.apply {
                            put("githubRunId", report.runId)
                            put("githubRunUrl", report.htmlUrl)
                            put("buildStatus", "${report.status.uppercase()} (${report.conclusion.ifEmpty { "running" }})")
                        }
                        val newStatus = when {
                            report.status != "completed" -> "BUILD_${report.status.uppercase()}"
                            report.conclusion == "success" -> "BUILD_SUCCEEDED (Ready to Download APK)"
                            else -> "BUILD_${report.conclusion.uppercase()}"
                        }
                        assetStorage.updateAssetRecord(
                            asset.copy(status = newStatus, metadataJson = meta.toString())
                        )
                    }
                    _statusBannerMessage.value =
                        "Run #${report.runId}: status=${report.status}, conclusion=${report.conclusion.ifEmpty { "in_progress" }}"
                }
            }.onFailure { err ->
                _statusBannerMessage.value = err.message ?: "Failed to query GitHub workflow status."
            }
            _isProcessingTask.value = false
        }
    }

    // =========================================================================
    // Asset Management & SAF Export / Import
    // =========================================================================
    fun deleteAssetConfirmed(asset: SavedAssetEntity) {
        viewModelScope.launch {
            assetStorage.deleteAsset(asset)
            refreshStorageUsage()
            _statusBannerMessage.value = "Deleted '${asset.title}' from local storage."
        }
    }

    fun exportAssetToUri(asset: SavedAssetEntity, targetUri: Uri) {
        viewModelScope.launch {
            val res = assetStorage.exportAssetToSafUri(asset, targetUri)
            res.onSuccess { bytes ->
                _statusBannerMessage.value = "Exported '${asset.title}' ($bytes bytes) via Storage Access Framework."
            }.onFailure { err ->
                _statusBannerMessage.value = "Export failed: ${err.message}"
            }
        }
    }

    fun importAssetFromUri(category: AssetCategory, sourceUri: Uri) {
        viewModelScope.launch {
            val res = assetStorage.importFileFromSafUri(category, sourceUri)
            res.onSuccess { entity ->
                refreshStorageUsage()
                _statusBannerMessage.value = "Imported '${entity.title}' (${entity.fileSizeBytes / 1024} KB) into ${category.displayName}."
            }.onFailure { err ->
                _statusBannerMessage.value = "Import failed: ${err.message}"
            }
        }
    }

    suspend fun loadWebsiteFiles(asset: SavedAssetEntity): Result<WebsiteProjectFiles> {
        return assetStorage.loadWebsiteProjectFiles(asset)
    }

    fun saveEditedWebsiteFiles(
        asset: SavedAssetEntity,
        html: String,
        css: String,
        js: String
    ) {
        viewModelScope.launch {
            val res = assetStorage.updateWebsiteProjectFiles(asset, html, css, js)
            res.onSuccess {
                refreshStorageUsage()
                _statusBannerMessage.value = "Saved updated HTML/CSS/JS files for '${asset.title}'."
            }.onFailure { err ->
                _statusBannerMessage.value = "Failed to save website edits: ${err.message}"
            }
        }
    }

    fun clearAllLocalAssetsConfirmed() {
        viewModelScope.launch {
            assetStorage.clearAllLocalAssets()
            refreshStorageUsage()
            _statusBannerMessage.value = "Cleared all locally saved assets."
        }
    }

    fun clearActionLogs() {
        viewModelScope.launch {
            assetStorage.clearActionHistory()
            _statusBannerMessage.value = "Cleared local phone action log history."
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            _storageUsage.value = assetStorage.computeStorageUsage()
        }
    }

    // =========================================================================
    // Settings Save, Clear & Connection Testing
    // =========================================================================
    fun saveGeminiConfig(settings: GeminiLiveSettings) {
        keystoreVault.saveGeminiSettings(settings)
        _geminiSettings.value = keystoreVault.loadGeminiSettings()
        _geminiTestStatus.value = ProviderTestStatus(isSuccess = true, message = "Gemini Live configuration saved securely.")
    }

    fun clearGeminiCredentials() {
        keystoreVault.clearGeminiCredentials()
        _geminiSettings.value = keystoreVault.loadGeminiSettings()
        _geminiTestStatus.value = ProviderTestStatus(isSuccess = null, message = "Cleared saved Gemini credentials.")
    }

    fun testGeminiConnection(candidate: GeminiLiveSettings) {
        viewModelScope.launch {
            _geminiTestStatus.value = ProviderTestStatus(isTesting = true, message = "Testing WebSocket & setupComplete…")
            val res = geminiLiveClient.testConnection(candidate)
            _geminiTestStatus.value = res.fold(
                onSuccess = { ProviderTestStatus(isTesting = false, isSuccess = true, message = it) },
                onFailure = { ProviderTestStatus(isTesting = false, isSuccess = false, message = it.message ?: "Test failed") }
            )
        }
    }

    fun saveSenseNovaConfig(settings: SenseNovaSettings) {
        keystoreVault.saveSenseNovaSettings(settings)
        _senseNovaSettings.value = keystoreVault.loadSenseNovaSettings()
        _senseNovaTestStatus.value = ProviderTestStatus(isSuccess = true, message = "SenseNova settings saved.")
    }

    fun clearSenseNovaCredentials() {
        keystoreVault.clearSenseNovaCredentials()
        _senseNovaSettings.value = keystoreVault.loadSenseNovaSettings()
        _senseNovaTestStatus.value = ProviderTestStatus(isSuccess = null, message = "Cleared SenseNova API key.")
    }

    fun testSenseNovaConnection(candidate: SenseNovaSettings) {
        viewModelScope.launch {
            _senseNovaTestStatus.value = ProviderTestStatus(isTesting = true, message = "Validating SenseNova endpoint…")
            val res = providersClient.testSenseNovaConnection(candidate)
            _senseNovaTestStatus.value = res.fold(
                onSuccess = { ProviderTestStatus(isTesting = false, isSuccess = true, message = it) },
                onFailure = { ProviderTestStatus(isTesting = false, isSuccess = false, message = it.message ?: "Validation failed") }
            )
        }
    }

    fun saveHelioRouteConfig(settings: HelioRouteSettings) {
        keystoreVault.saveHelioRouteSettings(settings)
        _helioRouteSettings.value = keystoreVault.loadHelioRouteSettings()
        _helioRouteTestStatus.value = ProviderTestStatus(isSuccess = true, message = "HelioRoute settings saved.")
    }

    fun clearHelioRouteCredentials() {
        keystoreVault.clearHelioRouteCredentials()
        _helioRouteSettings.value = keystoreVault.loadHelioRouteSettings()
        _helioRouteTestStatus.value = ProviderTestStatus(isSuccess = null, message = "Cleared HelioRoute API key.")
    }

    fun testHelioRouteConnection(candidate: HelioRouteSettings) {
        viewModelScope.launch {
            _helioRouteTestStatus.value = ProviderTestStatus(isTesting = true, message = "Testing HelioRoute endpoint…")
            val res = providersClient.sendHelioRouteChat(candidate, "Ping connection test. Reply with OK.")
            _helioRouteTestStatus.value = res.fold(
                onSuccess = { ProviderTestStatus(isTesting = false, isSuccess = true, message = "Connected! Model replied: ${it.take(80)}") },
                onFailure = { ProviderTestStatus(isTesting = false, isSuccess = false, message = it.message ?: "Test failed") }
            )
        }
    }

    fun saveXKiroConfig(settings: XKiroSettings) {
        keystoreVault.saveXKiroSettings(settings)
        _xKiroSettings.value = keystoreVault.loadXKiroSettings()
        _xKiroTestStatus.value = ProviderTestStatus(isSuccess = true, message = "xKiro settings saved.")
    }

    fun clearXKiroCredentials() {
        keystoreVault.clearXKiroCredentials()
        _xKiroSettings.value = keystoreVault.loadXKiroSettings()
        _xKiroTestStatus.value = ProviderTestStatus(isSuccess = null, message = "Cleared xKiro API key.")
    }

    fun testXKiroConnection(candidate: XKiroSettings) {
        viewModelScope.launch {
            _xKiroTestStatus.value = ProviderTestStatus(isTesting = true, message = "Testing xKiro chat completion…")
            val res = providersClient.sendXKiroChat(candidate, "Ping connection test. Reply with OK.")
            _xKiroTestStatus.value = res.fold(
                onSuccess = { ProviderTestStatus(isTesting = false, isSuccess = true, message = "Connected! Model replied: ${it.take(80)}") },
                onFailure = { ProviderTestStatus(isTesting = false, isSuccess = false, message = it.message ?: "Test failed") }
            )
        }
    }

    fun saveGitHubConfig(settings: GitHubBuildSettings) {
        keystoreVault.saveGitHubSettings(settings)
        _gitHubSettings.value = keystoreVault.loadGitHubSettings()
        _gitHubTestStatus.value = ProviderTestStatus(isSuccess = true, message = "GitHub Build Integration settings saved.")
    }

    fun clearGitHubCredentials() {
        keystoreVault.clearGitHubCredentials()
        _gitHubSettings.value = keystoreVault.loadGitHubSettings()
        _gitHubTestStatus.value = ProviderTestStatus(isSuccess = null, message = "Cleared GitHub Personal Access Token.")
    }

    fun testGitHubConnection(candidate: GitHubBuildSettings) {
        viewModelScope.launch {
            _gitHubTestStatus.value = ProviderTestStatus(isTesting = true, message = "Verifying GitHub repository access…")
            val res = providersClient.testGitHubConnection(candidate)
            _gitHubTestStatus.value = res.fold(
                onSuccess = { ProviderTestStatus(isTesting = false, isSuccess = true, message = it) },
                onFailure = { ProviderTestStatus(isTesting = false, isSuccess = false, message = it.message ?: "Verification failed") }
            )
        }
    }

    fun hasRecordAudioPermission(): Boolean = audioEngine.hasRecordAudioPermission()

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canWriteSystemSettings(): Boolean = Settings.System.canWrite(appContext)

    private fun appendMessage(
        sender: MessageSender,
        title: String,
        text: String,
        linkedCategory: AssetCategory? = null
    ) {
        val msg = ConversationMessage(
            sender = sender,
            title = title,
            text = text,
            linkedCategory = linkedCategory
        )
        _conversationMessages.value = _conversationMessages.value + msg
    }

    override fun onCleared() {
        super.onCleared()
        geminiLiveClient.disconnect()
        viewModelScope.launch {
            audioEngine.releaseAll()
        }
    }
}
