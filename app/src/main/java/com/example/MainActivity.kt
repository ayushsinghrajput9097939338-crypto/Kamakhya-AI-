package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.AssetCategory
import com.example.data.local.SavedAssetEntity
import com.example.ui.screens.AssetsScreen
import com.example.ui.screens.HomeVoiceScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.DeepNavyCard
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.viewmodel.AppScreen
import com.example.viewmodel.KamakhyaViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: KamakhyaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                KamakhyaAppRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun KamakhyaAppRoot(viewModel: KamakhyaViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedAssetCategory.collectAsStateWithLifecycle()
    val allAssets by viewModel.allAssets.collectAsStateWithLifecycle()
    val actionLogs by viewModel.actionLogs.collectAsStateWithLifecycle()

    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val liveStatus by viewModel.liveConnectionStatus.collectAsStateWithLifecycle()
    val liveDetail by viewModel.liveStatusDetail.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecordingMic.collectAsStateWithLifecycle()
    val isPlayingVoice by viewModel.isPlayingVoice.collectAsStateWithLifecycle()
    val micLevel by viewModel.micLevel.collectAsStateWithLifecycle()
    val messages by viewModel.conversationMessages.collectAsStateWithLifecycle()
    val pendingActionOutcome by viewModel.pendingActionOutcome.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    val geminiSettings by viewModel.geminiSettings.collectAsStateWithLifecycle()
    val senseNovaSettings by viewModel.senseNovaSettings.collectAsStateWithLifecycle()
    val helioRouteSettings by viewModel.helioRouteSettings.collectAsStateWithLifecycle()
    val xKiroSettings by viewModel.xKiroSettings.collectAsStateWithLifecycle()
    val gitHubSettings by viewModel.gitHubSettings.collectAsStateWithLifecycle()

    val geminiTest by viewModel.geminiTestStatus.collectAsStateWithLifecycle()
    val senseTest by viewModel.senseNovaTestStatus.collectAsStateWithLifecycle()
    val helioTest by viewModel.helioRouteTestStatus.collectAsStateWithLifecycle()
    val xKiroTest by viewModel.xKiroTestStatus.collectAsStateWithLifecycle()
    val gitHubTest by viewModel.gitHubTestStatus.collectAsStateWithLifecycle()
    val latestGitHubRun by viewModel.latestGitHubRunReport.collectAsStateWithLifecycle()
    val storageUsage by viewModel.storageUsage.collectAsStateWithLifecycle()

    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    var showMicPermissionRationaleDialog by remember { mutableStateOf(false) }
    var micPermissionDeniedOnce by remember { mutableStateOf(false) }
    var connectLiveAfterPermission by remember { mutableStateOf(false) }

    val hasMicPermission = remember(permissionRefreshTick) {
        viewModel.hasRecordAudioPermission()
    }
    val hasContactsPermission = remember(permissionRefreshTick) {
        viewModel.hasContactsPermission()
    }
    val canWriteSystemSettings = remember(permissionRefreshTick, currentScreen) {
        viewModel.canWriteSystemSettings()
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTick++
        if (granted && connectLiveAfterPermission) {
            connectLiveAfterPermission = false
            viewModel.connectGeminiLiveSession()
        } else if (!granted) {
            connectLiveAfterPermission = false
            micPermissionDeniedOnce = true
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionRefreshTick++
    }

    // Storage Access Framework (SAF) Export & Import launchers
    var assetPendingSafExport by remember { mutableStateOf<SavedAssetEntity?>(null) }
    var categoryPendingSafImport by remember { mutableStateOf<AssetCategory?>(null) }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { targetUri ->
        val asset = assetPendingSafExport
        assetPendingSafExport = null
        if (targetUri != null && asset != null) {
            viewModel.exportAssetToUri(asset, targetUri)
        }
    }

    val safImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        val cat = categoryPendingSafImport
        categoryPendingSafImport = null
        if (sourceUri != null && cat != null) {
            viewModel.importAssetFromUri(cat, sourceUri)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissBanner()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = DeepNavySurface,
                contentColor = TextPrimaryDark,
                modifier = Modifier.testTag("bottom_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.HOME,
                    onClick = { viewModel.navigateTo(AppScreen.HOME) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.HOME) Icons.Filled.GraphicEq else Icons.Outlined.GraphicEq,
                            contentDescription = stringResource(R.string.nav_home)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_home)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = DeepNavyCard,
                        unselectedIconColor = TextSecondaryDark,
                        unselectedTextColor = TextSecondaryDark
                    ),
                    modifier = Modifier.testTag("nav_item_home")
                )

                NavigationBarItem(
                    selected = currentScreen == AppScreen.ASSETS,
                    onClick = { viewModel.navigateTo(AppScreen.ASSETS) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.ASSETS) Icons.Filled.FolderSpecial else Icons.Outlined.FolderSpecial,
                            contentDescription = stringResource(R.string.nav_assets)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_assets)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = DeepNavyCard,
                        unselectedIconColor = TextSecondaryDark,
                        unselectedTextColor = TextSecondaryDark
                    ),
                    modifier = Modifier.testTag("nav_item_assets")
                )

                NavigationBarItem(
                    selected = currentScreen == AppScreen.SETTINGS,
                    onClick = { viewModel.navigateTo(AppScreen.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.nav_settings)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_settings)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyanAccent,
                        selectedTextColor = CyanAccent,
                        indicatorColor = DeepNavyCard,
                        unselectedIconColor = TextSecondaryDark,
                        unselectedTextColor = TextSecondaryDark
                    ),
                    modifier = Modifier.testTag("nav_item_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                AppScreen.HOME -> {
                    HomeVoiceScreen(
                        orbState = orbState,
                        connectionStatus = liveStatus,
                        connectionDetail = liveDetail,
                        isRecordingMic = isRecording,
                        isPlayingVoice = isPlayingVoice,
                        micLevel = micLevel,
                        hasMicPermission = hasMicPermission,
                        messages = messages,
                        pendingActionOutcome = pendingActionOutcome,
                        onConnectLive = {
                            if (hasMicPermission) {
                                viewModel.connectGeminiLiveSession()
                            } else {
                                connectLiveAfterPermission = true
                                showMicPermissionRationaleDialog = true
                            }
                        },
                        onDisconnectLive = { viewModel.disconnectGeminiLiveSession() },
                        onInterruptSpeech = { viewModel.interruptAssistantSpeech() },
                        onConfirmPendingAction = { viewModel.confirmPendingPhoneAction(it) },
                        onSelectAmbiguousApp = { viewModel.selectAmbiguousAppToLaunch(it) },
                        onSelectOrManualDial = { label, num -> viewModel.selectContactOrManualDial(label, num) },
                        onOpenWriteSettingsPermission = { viewModel.openSystemWriteSettingsPage() },
                        onRequestContactsPermission = {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        onDismissPendingAction = { viewModel.dismissPendingActionOutcome() },
                        onClearConversation = { viewModel.clearConversationHistory() },
                        onOpenAssetsCategory = { cat -> viewModel.navigateTo(AppScreen.ASSETS, cat) },
                        onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) }
                    )
                }

                AppScreen.ASSETS -> {
                    AssetsScreen(
                        selectedCategory = selectedCategory,
                        allAssets = allAssets,
                        onSelectCategory = { viewModel.selectAssetCategory(it) },
                        onCreateWebsiteProject = { title, prompt ->
                            viewModel.executeWebsiteGenerationFlow(prompt, title)
                        },
                        onCreateAndroidAppProject = { appName, desc ->
                            viewModel.executeAndroidAppGenerationFlow(desc, appName)
                        },
                        onGenerateImage = { prompt -> viewModel.executeImageGenerationFlow(prompt) },
                        onGenerateVideo = { prompt -> viewModel.executeVideoGenerationFlow(prompt) },
                        onPushAndBuildAndroidApp = { asset -> viewModel.pushAppToGitHubAndTriggerBuild(asset) },
                        onPollAndDownloadApk = { asset -> viewModel.pollGitHubBuildAndDownloadApk(asset) },
                        onLoadWebsiteFiles = { asset -> viewModel.loadWebsiteFiles(asset) },
                        onSaveWebsiteFiles = { asset, html, css, js ->
                            viewModel.saveEditedWebsiteFiles(asset, html, css, js)
                        },
                        onRequestSafExport = { asset ->
                            assetPendingSafExport = asset
                            val suggestedName = when (asset.category) {
                                AssetCategory.IMAGES.name -> "${asset.title.take(20)}.png"
                                AssetCategory.VIDEOS.name -> "${asset.title.take(20)}.mp4"
                                AssetCategory.WEBSITES.name -> "${asset.title.take(20)}.html"
                                AssetCategory.APPS.name -> "${asset.title.take(20)}-debug.apk"
                                else -> asset.title
                            }
                            safExportLauncher.launch(suggestedName)
                        },
                        onRequestSafImport = { cat ->
                            categoryPendingSafImport = cat
                            val mimeFilter = when (cat) {
                                AssetCategory.IMAGES -> arrayOf("image/*")
                                AssetCategory.VIDEOS -> arrayOf("video/*")
                                AssetCategory.WEBSITES -> arrayOf("text/html", "text/plain")
                                AssetCategory.APPS -> arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")
                            }
                            safImportLauncher.launch(mimeFilter)
                        },
                        onDeleteAssetConfirmed = { asset -> viewModel.deleteAssetConfirmed(asset) },
                        onNavigateBackHome = { viewModel.navigateTo(AppScreen.HOME) }
                    )
                }

                AppScreen.SETTINGS -> {
                    SettingsScreen(
                        geminiSettings = geminiSettings,
                        senseNovaSettings = senseNovaSettings,
                        helioRouteSettings = helioRouteSettings,
                        xKiroSettings = xKiroSettings,
                        gitHubSettings = gitHubSettings,
                        geminiTestStatus = geminiTest,
                        senseNovaTestStatus = senseTest,
                        helioRouteTestStatus = helioTest,
                        xKiroTestStatus = xKiroTest,
                        gitHubTestStatus = gitHubTest,
                        latestGitHubRunReport = latestGitHubRun,
                        hasMicPermission = hasMicPermission,
                        hasContactsPermission = hasContactsPermission,
                        canWriteSystemSettings = canWriteSystemSettings,
                        actionLogs = actionLogs,
                        storageUsage = storageUsage,
                        audioRecordSupportInfo = remember { viewModel.audioEngine.checkAudioRecord16kSupport() },
                        audioTrackSupportInfo = remember { viewModel.audioEngine.checkAudioTrack24kSupport() },
                        isKeystoreHardwareBacked = remember { viewModel.keystoreVault.isHardwareKeystoreAvailable() },
                        onSaveGemini = { viewModel.saveGeminiConfig(it) },
                        onTestGemini = { viewModel.testGeminiConnection(it) },
                        onClearGemini = { viewModel.clearGeminiCredentials() },
                        onSaveSenseNova = { viewModel.saveSenseNovaConfig(it) },
                        onTestSenseNova = { viewModel.testSenseNovaConnection(it) },
                        onClearSenseNova = { viewModel.clearSenseNovaCredentials() },
                        onSaveHelioRoute = { viewModel.saveHelioRouteConfig(it) },
                        onTestHelioRoute = { viewModel.testHelioRouteConnection(it) },
                        onClearHelioRoute = { viewModel.clearHelioRouteCredentials() },
                        onSaveXKiro = { viewModel.saveXKiroConfig(it) },
                        onTestXKiro = { viewModel.testXKiroConnection(it) },
                        onClearXKiro = { viewModel.clearXKiroCredentials() },
                        onSaveGitHub = { viewModel.saveGitHubConfig(it) },
                        onTestGitHub = { viewModel.testGitHubConnection(it) },
                        onPollGitHubWorkflow = { viewModel.pollGitHubBuildAndDownloadApk(null) },
                        onClearGitHub = { viewModel.clearGitHubCredentials() },
                        onRequestMicPermission = { showMicPermissionRationaleDialog = true },
                        onRequestContactsPermission = {
                            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                        },
                        onOpenWriteSettingsPage = { viewModel.openSystemWriteSettingsPage() },
                        onExecuteDirectPhoneAction = { viewModel.executeDirectPhoneActionFromSettings(it) },
                        onClearActionLogs = { viewModel.clearActionLogs() },
                        onClearAllLocalAssets = { viewModel.clearAllLocalAssetsConfirmed() },
                        onNavigateBackHome = { viewModel.navigateTo(AppScreen.HOME) }
                    )
                }
            }
        }
    }

    // Microphone Permission Rationale Dialog (Never repeatedly prompts without explanation)
    if (showMicPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = {
                showMicPermissionRationaleDialog = false
                connectLiveAfterPermission = false
            },
            title = { Text("Microphone Permission Required") },
            text = {
                Text(
                    if (micPermissionDeniedOnce) {
                        "Microphone access was previously denied. Android microphone permission is required for a continuous Gemini Live voice conversation. You can grant it from app settings and then tap Connect Live again."
                    } else {
                        stringResource(R.string.mic_permission_rationale)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMicPermissionRationaleDialog = false
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                ) {
                    Text("Continue to Permission Prompt")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMicPermissionRationaleDialog = false
                        connectLiveAfterPermission = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
