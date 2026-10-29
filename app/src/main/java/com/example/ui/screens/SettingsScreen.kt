package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.actions.PhoneActionRequest
import com.example.data.local.ActionLogEntity
import com.example.data.local.StorageUsageSummary
import com.example.network.GitHubWorkflowStatusReport
import com.example.security.GeminiAuthMode
import com.example.security.GeminiLiveSettings
import com.example.security.GitHubBuildSettings
import com.example.security.HelioRouteSettings
import com.example.security.SenseNovaSettings
import com.example.security.XKiroSettings
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavyBackground
import com.example.ui.theme.DeepNavyCard
import com.example.ui.theme.DeepNavyElevated
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElectricBlueAccent
import com.example.ui.theme.SoftViolet
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.theme.TextMutedDark
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.viewmodel.ProviderTestStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    geminiSettings: GeminiLiveSettings,
    senseNovaSettings: SenseNovaSettings,
    helioRouteSettings: HelioRouteSettings,
    xKiroSettings: XKiroSettings,
    gitHubSettings: GitHubBuildSettings,
    geminiTestStatus: ProviderTestStatus,
    senseNovaTestStatus: ProviderTestStatus,
    helioRouteTestStatus: ProviderTestStatus,
    xKiroTestStatus: ProviderTestStatus,
    gitHubTestStatus: ProviderTestStatus,
    latestGitHubRunReport: GitHubWorkflowStatusReport?,
    hasMicPermission: Boolean,
    hasContactsPermission: Boolean,
    canWriteSystemSettings: Boolean,
    actionLogs: List<ActionLogEntity>,
    storageUsage: StorageUsageSummary,
    audioRecordSupportInfo: String,
    audioTrackSupportInfo: String,
    isKeystoreHardwareBacked: Boolean,
    onSaveGemini: (GeminiLiveSettings) -> Unit,
    onTestGemini: (GeminiLiveSettings) -> Unit,
    onClearGemini: () -> Unit,
    onSaveSenseNova: (SenseNovaSettings) -> Unit,
    onTestSenseNova: (SenseNovaSettings) -> Unit,
    onClearSenseNova: () -> Unit,
    onSaveHelioRoute: (HelioRouteSettings) -> Unit,
    onTestHelioRoute: (HelioRouteSettings) -> Unit,
    onClearHelioRoute: () -> Unit,
    onSaveXKiro: (XKiroSettings) -> Unit,
    onTestXKiro: (XKiroSettings) -> Unit,
    onClearXKiro: () -> Unit,
    onSaveGitHub: (GitHubBuildSettings) -> Unit,
    onTestGitHub: (GitHubBuildSettings) -> Unit,
    onPollGitHubWorkflow: () -> Unit,
    onClearGitHub: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onOpenWriteSettingsPage: () -> Unit,
    onExecuteDirectPhoneAction: (PhoneActionRequest) -> Unit,
    onClearActionLogs: () -> Unit,
    onClearAllLocalAssets: () -> Unit,
    onNavigateBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBackHome)

    // Editable state holders initialized from current saved settings
    var geminiModel by rememberSaveable(geminiSettings.modelId) { mutableStateOf(geminiSettings.modelId) }
    var geminiEndpoint by rememberSaveable(geminiSettings.wsEndpoint) { mutableStateOf(geminiSettings.wsEndpoint) }
    var geminiAuthMode by rememberSaveable(geminiSettings.authMode) { mutableStateOf(geminiSettings.authMode) }
    var geminiKey by rememberSaveable(geminiSettings.apiKey) { mutableStateOf(geminiSettings.apiKey) }
    var geminiToken by rememberSaveable(geminiSettings.ephemeralToken) { mutableStateOf(geminiSettings.ephemeralToken) }

    var senseEndpoint by rememberSaveable(senseNovaSettings.endpoint) { mutableStateOf(senseNovaSettings.endpoint) }
    var senseModel by rememberSaveable(senseNovaSettings.model) { mutableStateOf(senseNovaSettings.model) }
    var senseKey by rememberSaveable(senseNovaSettings.apiKey) { mutableStateOf(senseNovaSettings.apiKey) }

    var helioEndpoint by rememberSaveable(helioRouteSettings.endpoint) { mutableStateOf(helioRouteSettings.endpoint) }
    var helioModel by rememberSaveable(helioRouteSettings.model) { mutableStateOf(helioRouteSettings.model) }
    var helioKey by rememberSaveable(helioRouteSettings.apiKey) { mutableStateOf(helioRouteSettings.apiKey) }
    var helioVideoSupported by rememberSaveable(helioRouteSettings.videoEndpointSupported) {
        mutableStateOf(helioRouteSettings.videoEndpointSupported)
    }

    var xKiroBase by rememberSaveable(xKiroSettings.baseUrl) { mutableStateOf(xKiroSettings.baseUrl) }
    var xKiroPath by rememberSaveable(xKiroSettings.chatPath) { mutableStateOf(xKiroSettings.chatPath) }
    var xKiroModel by rememberSaveable(xKiroSettings.model) { mutableStateOf(xKiroSettings.model) }
    var xKiroKey by rememberSaveable(xKiroSettings.apiKey) { mutableStateOf(xKiroSettings.apiKey) }

    var ghOwner by rememberSaveable(gitHubSettings.owner) { mutableStateOf(gitHubSettings.owner) }
    var ghRepo by rememberSaveable(gitHubSettings.repo) { mutableStateOf(gitHubSettings.repo) }
    var ghBranch by rememberSaveable(gitHubSettings.branch) { mutableStateOf(gitHubSettings.branch) }
    var ghToken by rememberSaveable(gitHubSettings.token) { mutableStateOf(gitHubSettings.token) }
    var ghWorkflow by rememberSaveable(gitHubSettings.workflowFile) { mutableStateOf(gitHubSettings.workflowFile) }

    var showClearStorageDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavyBackground)
            .padding(horizontal = 16.dp)
            .testTag("settings_screen_list"),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                Text(
                    text = "Settings & Provider Configuration",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimaryDark
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Credentials are encrypted at rest via Android Keystore AES-256-GCM. Storing a client-side API key on a personal device does not make it completely secret from someone who controls the physical device or its APK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryDark
                )
            }
        }

        // =========================================================================
        // 1. Gemini Live
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "1",
                title = "Gemini Live",
                subtitle = "Official Live API WebSocket Protocol (BidiGenerateContent)"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = geminiAuthMode == GeminiAuthMode.DIRECT_API_KEY,
                        onClick = { geminiAuthMode = GeminiAuthMode.DIRECT_API_KEY },
                        label = { Text("Direct API Key (Prototype)") }
                    )
                    FilterChip(
                        selected = geminiAuthMode == GeminiAuthMode.EPHEMERAL_TOKEN,
                        onClick = { geminiAuthMode = GeminiAuthMode.EPHEMERAL_TOKEN },
                        label = { Text("Ephemeral Token") }
                    )
                }

                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_model_input")
                )

                OutlinedTextField(
                    value = geminiEndpoint,
                    onValueChange = { geminiEndpoint = it },
                    label = { Text("WebSocket Endpoint") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_endpoint_input")
                )

                if (geminiAuthMode == GeminiAuthMode.DIRECT_API_KEY) {
                    MaskedCredentialField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it },
                        label = "Gemini API Key (Encrypted at rest)",
                        testTag = "gemini_api_key_input"
                    )
                } else {
                    MaskedCredentialField(
                        value = geminiToken,
                        onValueChange = { geminiToken = it },
                        label = "Ephemeral Access Token (Configured Separately)",
                        testTag = "gemini_ephemeral_token_input"
                    )
                }

                val currentCandidate = GeminiLiveSettings(
                    modelId = geminiModel,
                    wsEndpoint = geminiEndpoint,
                    authMode = geminiAuthMode,
                    apiKey = geminiKey,
                    ephemeralToken = geminiToken
                )

                ProviderActionButtonsRow(
                    onSave = { onSaveGemini(currentCandidate) },
                    onTest = { onTestGemini(currentCandidate) },
                    onClear = {
                        geminiKey = ""
                        geminiToken = ""
                        onClearGemini()
                    },
                    isTesting = geminiTestStatus.isTesting,
                    saveTag = "save_gemini_button",
                    testTag = "test_gemini_button"
                )

                ProviderTestResultBanner(status = geminiTestStatus)
            }
        }

        // =========================================================================
        // 2. SenseNova
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "2",
                title = "SenseNova",
                subtitle = "Configurable Image Generation Provider"
            ) {
                OutlinedTextField(
                    value = senseEndpoint,
                    onValueChange = { senseEndpoint = it },
                    label = { Text("Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sensenova_endpoint_input")
                )
                OutlinedTextField(
                    value = senseModel,
                    onValueChange = { senseModel = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sensenova_model_input")
                )
                MaskedCredentialField(
                    value = senseKey,
                    onValueChange = { senseKey = it },
                    label = "SenseNova API Key",
                    testTag = "sensenova_api_key_input"
                )

                val candidate = SenseNovaSettings(
                    endpoint = senseEndpoint,
                    model = senseModel,
                    apiKey = senseKey
                )

                ProviderActionButtonsRow(
                    onSave = { onSaveSenseNova(candidate) },
                    onTest = { onTestSenseNova(candidate) },
                    onClear = {
                        senseKey = ""
                        onClearSenseNova()
                    },
                    isTesting = senseNovaTestStatus.isTesting,
                    saveTag = "save_sensenova_button",
                    testTag = "test_sensenova_button"
                )

                ProviderTestResultBanner(status = senseNovaTestStatus)
            }
        }

        // =========================================================================
        // 3. HelioRoute
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "3",
                title = "HelioRoute",
                subtitle = "Configurable Model Operations (Chat & Supported Video Jobs)"
            ) {
                OutlinedTextField(
                    value = helioEndpoint,
                    onValueChange = { helioEndpoint = it },
                    label = { Text("Endpoint URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("helioroute_endpoint_input")
                )
                OutlinedTextField(
                    value = helioModel,
                    onValueChange = { helioModel = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("helioroute_model_input")
                )
                MaskedCredentialField(
                    value = helioKey,
                    onValueChange = { helioKey = it },
                    label = "HelioRoute API Key",
                    testTag = "helioroute_api_key_input"
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = helioVideoSupported,
                        onCheckedChange = { helioVideoSupported = it }
                    )
                    Text(
                        text = "Configured endpoint explicitly supports video generation jobs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                }

                val candidate = HelioRouteSettings(
                    endpoint = helioEndpoint,
                    model = helioModel,
                    apiKey = helioKey,
                    videoEndpointSupported = helioVideoSupported
                )

                ProviderActionButtonsRow(
                    onSave = { onSaveHelioRoute(candidate) },
                    onTest = { onTestHelioRoute(candidate) },
                    onClear = {
                        helioKey = ""
                        onClearHelioRoute()
                    },
                    isTesting = helioRouteTestStatus.isTesting,
                    saveTag = "save_helioroute_button",
                    testTag = "test_helioroute_button"
                )

                ProviderTestResultBanner(status = helioRouteTestStatus)
            }
        }

        // =========================================================================
        // 4. xKiro
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "4",
                title = "xKiro",
                subtitle = "Configurable Chat Completion Provider"
            ) {
                OutlinedTextField(
                    value = xKiroBase,
                    onValueChange = { xKiroBase = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("xkiro_base_url_input")
                )
                OutlinedTextField(
                    value = xKiroPath,
                    onValueChange = { xKiroPath = it },
                    label = { Text("Chat Path") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("xkiro_chat_path_input")
                )
                OutlinedTextField(
                    value = xKiroModel,
                    onValueChange = { xKiroModel = it },
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("xkiro_model_input")
                )
                MaskedCredentialField(
                    value = xKiroKey,
                    onValueChange = { xKiroKey = it },
                    label = "xKiro API Key",
                    testTag = "xkiro_api_key_input"
                )

                val candidate = XKiroSettings(
                    baseUrl = xKiroBase,
                    chatPath = xKiroPath,
                    model = xKiroModel,
                    apiKey = xKiroKey
                )

                ProviderActionButtonsRow(
                    onSave = { onSaveXKiro(candidate) },
                    onTest = { onTestXKiro(candidate) },
                    onClear = {
                        xKiroKey = ""
                        onClearXKiro()
                    },
                    isTesting = xKiroTestStatus.isTesting,
                    saveTag = "save_xkiro_button",
                    testTag = "test_xkiro_button"
                )

                ProviderTestResultBanner(status = xKiroTestStatus)
            }
        }

        // =========================================================================
        // 5. GitHub Build Integration
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "5",
                title = "GitHub Build Integration",
                subtitle = "Push Generated Android Projects & Compile Real Debug APKs via GitHub Actions"
            ) {
                OutlinedTextField(
                    value = ghOwner,
                    onValueChange = { ghOwner = it },
                    label = { Text("GitHub Username or Repository Owner") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_owner_input")
                )
                OutlinedTextField(
                    value = ghRepo,
                    onValueChange = { ghRepo = it },
                    label = { Text("Repository Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_repo_input")
                )
                OutlinedTextField(
                    value = ghBranch,
                    onValueChange = { ghBranch = it },
                    label = { Text("Branch (default: main)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_branch_input")
                )
                MaskedCredentialField(
                    value = ghToken,
                    onValueChange = { ghToken = it },
                    label = "GitHub Personal Access Token (PAT)",
                    testTag = "github_token_input"
                )
                OutlinedTextField(
                    value = ghWorkflow,
                    onValueChange = { ghWorkflow = it },
                    label = { Text("Workflow File Name (e.g. android-build.yml)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_workflow_input")
                )

                val candidate = GitHubBuildSettings(
                    owner = ghOwner,
                    repo = ghRepo,
                    branch = ghBranch,
                    token = ghToken,
                    workflowFile = ghWorkflow
                )

                ProviderActionButtonsRow(
                    onSave = { onSaveGitHub(candidate) },
                    onTest = { onTestGitHub(candidate) },
                    onClear = {
                        ghToken = ""
                        onClearGitHub()
                    },
                    isTesting = gitHubTestStatus.isTesting,
                    saveTag = "save_github_button",
                    testTag = "test_github_button"
                )

                OutlinedButton(
                    onClick = onPollGitHubWorkflow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Latest Workflow Run Status")
                }

                latestGitHubRunReport?.let { run ->
                    Surface(
                        color = DeepNavySurface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DeepNavyElevated)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Latest Run #${run.runId} (${run.headBranch})",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyanAccent
                            )
                            Text(
                                text = "Status: ${run.status} • Conclusion: ${run.conclusion.ifEmpty { "in_progress" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = run.htmlUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }

                ProviderTestResultBanner(status = gitHubTestStatus)
            }
        }

        // =========================================================================
        // 6. Mobile Actions
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "6",
                title = "Mobile Actions",
                subtitle = "Permissions, Native Android Action Verification & Sanitized Action Log"
            ) {
                PermissionRowItem(
                    label = "Microphone (RECORD_AUDIO)",
                    granted = hasMicPermission,
                    actionLabel = "Grant Mic",
                    onAction = onRequestMicPermission
                )
                PermissionRowItem(
                    label = "Contacts Lookup (READ_CONTACTS)",
                    granted = hasContactsPermission,
                    actionLabel = "Grant Contacts",
                    onAction = onRequestContactsPermission
                )
                PermissionRowItem(
                    label = "System Brightness (WRITE_SETTINGS)",
                    granted = canWriteSystemSettings,
                    actionLabel = "Manage Setting",
                    onAction = onOpenWriteSettingsPage
                )

                HorizontalDivider(color = DeepNavyElevated, modifier = Modifier.padding(vertical = 6.dp))

                Text(
                    text = "Quick Native Action Verification",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyanAccent
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onExecuteDirectPhoneAction(
                                PhoneActionRequest.AdjustMediaVolume(targetPercent = 40, deltaSteps = null)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Volume 40%", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            onExecuteDirectPhoneAction(
                                PhoneActionRequest.AdjustBrightness(targetPercent = 60, deltaPercent = null)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Brightness 60%", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            onExecuteDirectPhoneAction(
                                PhoneActionRequest.OpenApp("Settings")
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open Settings", style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider(color = DeepNavyElevated, modifier = Modifier.padding(vertical = 6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Action Log (Names & Outcomes Only — ${actionLogs.size} entries)",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimaryDark
                    )
                    TextButton(
                        onClick = onClearActionLogs,
                        modifier = Modifier.testTag("clear_action_logs_button")
                    ) {
                        Text("Clear History", color = StatusErrorRed)
                    }
                }

                if (actionLogs.isEmpty()) {
                    Text(
                        text = "No phone actions executed yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedDark
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        actionLogs.take(10).forEach { log ->
                            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(log.timestamp))
                            Surface(
                                color = DeepNavySurface,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${log.actionName} → ${log.outcomeSummary}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondaryDark,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = ts,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMutedDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 7. Local Storage
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "7",
                title = "Local Storage",
                subtitle = "App-Owned Private Storage & SAF Export/Import Status"
            ) {
                Text(
                    text = "Root Directory: ${storageUsage.rootPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondaryDark
                )
                Text(
                    text = "• Images: ${storageUsage.imagesBytes / 1024} KB\n" +
                        "• Videos: ${storageUsage.videosBytes / 1024} KB\n" +
                        "• Websites: ${storageUsage.websitesBytes / 1024} KB\n" +
                        "• Apps & APKs: ${storageUsage.appsBytes / 1024} KB\n" +
                        "• Total Files: ${storageUsage.totalFilesCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimaryDark
                )
                OutlinedButton(
                    onClick = { showClearStorageDialog = true },
                    border = BorderStroke(1.dp, StatusErrorRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusErrorRed),
                    modifier = Modifier.testTag("clear_all_local_storage_button")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear All Saved Local Assets")
                }
            }
        }

        // =========================================================================
        // 8. About and Diagnostics
        // =========================================================================
        item {
            SettingsSectionCard(
                sectionNumber = "8",
                title = "About and Diagnostics",
                subtitle = "Hardware Audio Verification, Keystore Status & Protocol Compliance"
            ) {
                DiagnosticRow("App Name & Build", "Kamakhya AI v1.0 (Personal Android Prototype)")
                DiagnosticRow("Gemini Live Default Model", GeminiLiveSettings.DEFAULT_MODEL_ID)
                DiagnosticRow("Capture Audio Format", audioRecordSupportInfo)
                DiagnosticRow("Playback Audio Format", audioTrackSupportInfo)
                DiagnosticRow(
                    "Credential Encryption",
                    if (isKeystoreHardwareBacked) {
                        "AndroidKeyStore AES-256-GCM Active"
                    } else {
                        "AES-256-GCM Active (JVM Fallback)"
                    }
                )
                DiagnosticRow(
                    "Security Policy",
                    "Never logs credentials or embeds keys in source/APK. Uses explicit user confirmation for consequential phone actions."
                )
            }
        }
    }

    if (showClearStorageDialog) {
        AlertDialog(
            onDismissRequest = { showClearStorageDialog = false },
            title = { Text("Clear All Local Assets?") },
            text = {
                Text("This will permanently delete all locally generated images, videos, website projects, and APK build records from the app's private storage.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearStorageDialog = false
                        onClearAllLocalAssets()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusErrorRed, contentColor = Color.White)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStorageDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    sectionNumber: String,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepNavyCard),
        border = BorderStroke(1.dp, DeepNavyElevated)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = ElectricBlueAccent.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = sectionNumber,
                        style = MaterialTheme.typography.labelLarge,
                        color = CyanAccent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryDark
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondaryDark
                    )
                }
            }
            HorizontalDivider(color = DeepNavyElevated)
            content()
        }
    }
}

@Composable
private fun MaskedCredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String
) {
    var revealed by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        leadingIcon = {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            IconButton(
                onClick = { revealed = !revealed },
                modifier = Modifier.testTag("${testTag}_reveal_toggle")
            ) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (revealed) "Hide credential" else "Reveal credential",
                    tint = TextSecondaryDark
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}

@Composable
private fun ProviderActionButtonsRow(
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    isTesting: Boolean,
    saveTag: String,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = DeepNavyBackground),
            modifier = Modifier
                .weight(1f)
                .testTag(saveTag)
        ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Save")
        }

        OutlinedButton(
            onClick = onTest,
            enabled = !isTesting,
            border = BorderStroke(1.dp, CyanAccent),
            modifier = Modifier
                .weight(1.2f)
                .testTag(testTag)
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = CyanAccent
                )
            } else {
                Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isTesting) "Testing…" else "Test Connection")
        }

        TextButton(onClick = onClear) {
            Text("Clear", color = StatusErrorRed)
        }
    }
}

@Composable
private fun ProviderTestResultBanner(status: ProviderTestStatus) {
    if (status.message.isBlank()) return
    val color = when (status.isSuccess) {
        true -> StatusConnectedGreen
        false -> StatusErrorRed
        null -> StatusWarningAmber
    }
    Surface(
        color = DeepNavySurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (status.isSuccess) {
                    true -> Icons.Filled.CheckCircle
                    false -> Icons.Filled.ErrorOutline
                    null -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimaryDark
            )
        }
    }
}

@Composable
private fun PermissionRowItem(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = if (granted) StatusConnectedGreen else StatusWarningAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimaryDark)
                Text(
                    if (granted) "Granted / Permitted" else "Not granted yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) StatusConnectedGreen else StatusWarningAmber
                )
            }
        }
        if (!granted) {
            OutlinedButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SoftViolet)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimaryDark)
    }
}
