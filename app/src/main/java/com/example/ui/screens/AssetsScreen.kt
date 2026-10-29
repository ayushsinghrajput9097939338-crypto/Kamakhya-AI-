package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.AssetCategory
import com.example.data.local.SavedAssetEntity
import com.example.data.local.WebsiteProjectFiles
import com.example.ui.theme.AssetsCardBorder
import com.example.ui.theme.AssetsMainWhite
import com.example.ui.theme.AssetsSidebarGreyGreen
import com.example.ui.theme.AssetsSidebarMutedText
import com.example.ui.theme.AssetsSidebarSelected
import com.example.ui.theme.AssetsSidebarText
import com.example.ui.theme.AssetsSurfaceLight
import com.example.ui.theme.StatusErrorRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssetsScreen(
    selectedCategory: AssetCategory,
    allAssets: List<SavedAssetEntity>,
    onSelectCategory: (AssetCategory) -> Unit,
    onCreateWebsiteProject: (title: String, prompt: String) -> Unit,
    onCreateAndroidAppProject: (appName: String, description: String) -> Unit,
    onGenerateImage: (prompt: String) -> Unit,
    onGenerateVideo: (prompt: String) -> Unit,
    onPushAndBuildAndroidApp: (SavedAssetEntity) -> Unit,
    onPollAndDownloadApk: (SavedAssetEntity) -> Unit,
    onLoadWebsiteFiles: suspend (SavedAssetEntity) -> Result<WebsiteProjectFiles>,
    onSaveWebsiteFiles: (SavedAssetEntity, String, String, String) -> Unit,
    onRequestSafExport: (SavedAssetEntity) -> Unit,
    onRequestSafImport: (AssetCategory) -> Unit,
    onDeleteAssetConfirmed: (SavedAssetEntity) -> Unit,
    onNavigateBackHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBackHome)

    var assetPendingDelete by remember { mutableStateOf<SavedAssetEntity?>(null) }
    var imageAssetViewing by remember { mutableStateOf<SavedAssetEntity?>(null) }
    var videoAssetPlaying by remember { mutableStateOf<SavedAssetEntity?>(null) }
    var websiteAssetInspecting by remember { mutableStateOf<SavedAssetEntity?>(null) }
    var showCreateModalForCategory by remember { mutableStateOf<AssetCategory?>(null) }

    val filteredAssets = remember(allAssets, selectedCategory) {
        allAssets.filter { asset ->
            if (asset.category != selectedCategory.name) return@filter false
            // For VIDEOS, only show when a real non-empty video file exists on disk
            if (selectedCategory == AssetCategory.VIDEOS) {
                val f = File(asset.localPath)
                f.exists() && f.isFile && f.length() > 0L
            } else {
                true
            }
        }
    }

    // Blueprint B layout: White main background with pale grey-green left sidebar
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(AssetsMainWhite)
            .testTag("assets_screen_root")
    ) {
        // Left Sidebar (Pale grey-green)
        AssetsLeftSidebar(
            selectedCategory = selectedCategory,
            allAssets = allAssets,
            onSelectCategory = onSelectCategory
        )

        // Right Main Content Area (White background)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AssetsMainWhite)
                .padding(16.dp)
        ) {
            // Category Title & Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedCategory.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AssetsSidebarText,
                        modifier = Modifier.testTag("assets_category_header")
                    )
                    Text(
                        text = when (selectedCategory) {
                            AssetCategory.IMAGES -> "Locally saved images & SenseNova outputs"
                            AssetCategory.VIDEOS -> "Verified local video files only"
                            AssetCategory.WEBSITES -> "Local HTML/CSS/JS projects with WebView preview"
                            AssetCategory.APPS -> "Android app build records & real APK files"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AssetsSidebarMutedText
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onRequestSafImport(selectedCategory) },
                        border = BorderStroke(1.dp, AssetsSidebarText.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("assets_import_saf_button")
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Import file via SAF", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = { showCreateModalForCategory = selectedCategory },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AssetsSidebarText,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("assets_create_new_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New asset", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredAssets.isEmpty()) {
                AssetsEmptyStateView(
                    category = selectedCategory,
                    onTriggerCreate = { showCreateModalForCategory = selectedCategory },
                    onTriggerImport = { onRequestSafImport(selectedCategory) }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("assets_items_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredAssets, key = { it.id }) { asset ->
                        AssetItemCard(
                            asset = asset,
                            onOpenImage = { imageAssetViewing = asset },
                            onPlayVideo = { videoAssetPlaying = asset },
                            onPreviewWebsite = { websiteAssetInspecting = asset },
                            onPushGitHubBuild = { onPushAndBuildAndroidApp(asset) },
                            onPollAndDownloadApk = { onPollAndDownloadApk(asset) },
                            onExportSaf = { onRequestSafExport(asset) },
                            onRequestDelete = { assetPendingDelete = asset }
                        )
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    assetPendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { assetPendingDelete = null },
            title = { Text("Delete Local Asset?") },
            text = {
                Text("Are you sure you want to permanently delete '${toDelete.title}' and its local files from device storage?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteAssetConfirmed(toDelete)
                        assetPendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusErrorRed, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_asset_button")
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { assetPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create / Generate Dialog
    showCreateModalForCategory?.let { category ->
        CreateOrGenerateAssetDialog(
            category = category,
            onDismiss = { showCreateModalForCategory = null },
            onSubmitWebsite = { title, prompt ->
                showCreateModalForCategory = null
                onCreateWebsiteProject(title, prompt)
            },
            onSubmitAndroidApp = { name, desc ->
                showCreateModalForCategory = null
                onCreateAndroidAppProject(name, desc)
            },
            onSubmitImagePrompt = { prompt ->
                showCreateModalForCategory = null
                onGenerateImage(prompt)
            },
            onSubmitVideoPrompt = { prompt ->
                showCreateModalForCategory = null
                onGenerateVideo(prompt)
            }
        )
    }

    // In-App Image Viewer Dialog
    imageAssetViewing?.let { imgAsset ->
        InAppImageViewerDialog(
            asset = imgAsset,
            onExport = { onRequestSafExport(imgAsset) },
            onDismiss = { imageAssetViewing = null }
        )
    }

    // In-App Video Player Dialog
    videoAssetPlaying?.let { vidAsset ->
        InAppVideoPlayerDialog(
            asset = vidAsset,
            onDismiss = { videoAssetPlaying = null }
        )
    }

    // In-App Website WebView Preview & Source Code Editor Dialog
    websiteAssetInspecting?.let { webAsset ->
        InAppWebsitePreviewDialog(
            asset = webAsset,
            onLoadFiles = onLoadWebsiteFiles,
            onSaveEdits = onSaveWebsiteFiles,
            onExportHtml = { onRequestSafExport(webAsset) },
            onDismiss = { websiteAssetInspecting = null }
        )
    }
}

@Composable
private fun AssetsLeftSidebar(
    selectedCategory: AssetCategory,
    allAssets: List<SavedAssetEntity>,
    onSelectCategory: (AssetCategory) -> Unit
) {
    val categories = listOf(
        AssetCategory.IMAGES to Icons.Filled.Image,
        AssetCategory.VIDEOS to Icons.Filled.VideoLibrary,
        AssetCategory.WEBSITES to Icons.Filled.Language,
        AssetCategory.APPS to Icons.Filled.Android
    )

    Column(
        modifier = Modifier
            .width(116.dp)
            .fillMaxHeight()
            .background(AssetsSidebarGreyGreen)
            .padding(vertical = 16.dp, horizontal = 8.dp)
            .testTag("assets_left_sidebar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "LIBRARY",
            style = MaterialTheme.typography.labelSmall,
            color = AssetsSidebarMutedText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        categories.forEach { (category, icon) ->
            val isSelected = selectedCategory == category
            val count = allAssets.count {
                it.category == category.name &&
                    (category != AssetCategory.VIDEOS || File(it.localPath).exists())
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectCategory(category) }
                    .testTag("sidebar_category_${category.name.lowercase()}"),
                color = if (isSelected) AssetsSidebarSelected else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = category.displayName,
                            tint = AssetsSidebarText,
                            modifier = Modifier.size(20.dp)
                        )
                        if (count > 0) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = AssetsSidebarText,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = AssetsSidebarText,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetsEmptyStateView(
    category: AssetCategory,
    onTriggerCreate: () -> Unit,
    onTriggerImport: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AssetsSurfaceLight),
            border = BorderStroke(1.dp, AssetsCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (category) {
                        AssetCategory.IMAGES -> Icons.Filled.Image
                        AssetCategory.VIDEOS -> Icons.Filled.VideoLibrary
                        AssetCategory.WEBSITES -> Icons.Filled.Language
                        AssetCategory.APPS -> Icons.Filled.Android
                    },
                    contentDescription = null,
                    tint = AssetsSidebarMutedText,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No ${category.displayName} Saved Yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = AssetsSidebarText
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (category) {
                        AssetCategory.IMAGES -> "No generated or imported images are stored locally. Generate an image with SenseNova (requires API key in Settings) or import a local image via SAF."
                        AssetCategory.VIDEOS -> "No verified video files exist in local storage. Only real video files are listed here."
                        AssetCategory.WEBSITES -> "Create a responsive HTML/CSS/JS website project locally on your device and preview it inside the built-in WebView."
                        AssetCategory.APPS -> "Generate an Android Gradle project locally, push it to your GitHub Actions repository to build a real debug APK, or import an existing APK."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AssetsSidebarMutedText
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onTriggerCreate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AssetsSidebarText,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            when (category) {
                                AssetCategory.IMAGES -> "Generate Image"
                                AssetCategory.VIDEOS -> "Request Video"
                                AssetCategory.WEBSITES -> "Create Website"
                                AssetCategory.APPS -> "Create Android Project"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = onTriggerImport,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                    ) {
                        Text("Import File (SAF)")
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetItemCard(
    asset: SavedAssetEntity,
    onOpenImage: () -> Unit,
    onPlayVideo: () -> Unit,
    onPreviewWebsite: () -> Unit,
    onPushGitHubBuild: () -> Unit,
    onPollAndDownloadApk: () -> Unit,
    onExportSaf: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val dateStr = remember(asset.createdAt) {
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date(asset.createdAt))
    }
    val metaObj = remember(asset.metadataJson) {
        runCatching { JSONObject(asset.metadataJson) }.getOrElse { JSONObject() }
    }
    val hasRealApk = remember(asset.secondaryPath) {
        asset.secondaryPath.isNotBlank() && File(asset.secondaryPath).let { it.exists() && it.isFile && it.length() > 0L }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("asset_card_${asset.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AssetsSurfaceLight),
        border = BorderStroke(1.dp, AssetsCardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = asset.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AssetsSidebarText
                    )
                    Text(
                        text = "${asset.sourceProvider} • ${formatBytes(asset.fileSizeBytes)} • $dateStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = AssetsSidebarMutedText
                    )
                }

                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete ${asset.title}",
                        tint = StatusErrorRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // If IMAGE, display actual decoded bitmap preview
            if (asset.category == AssetCategory.IMAGES.name) {
                val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, asset.localPath) {
                    value = withContext(Dispatchers.IO) {
                        val f = File(asset.localPath)
                        if (f.exists() && f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
                    }
                }
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = asset.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenImage() },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Text(
                text = asset.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AssetsSidebarText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                color = AssetsSidebarSelected,
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "Status: ${asset.status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AssetsSidebarText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (asset.category == AssetCategory.APPS.name) {
                val sha = metaObj.optString("apkSha256", "")
                val runUrl = metaObj.optString("githubRunUrl", "")
                if (sha.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "APK SHA-256: ${sha.take(24)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = AssetsSidebarMutedText
                    )
                }
                if (runUrl.isNotBlank()) {
                    Text(
                        text = "GitHub Run: $runUrl",
                        style = MaterialTheme.typography.labelSmall,
                        color = AssetsSidebarMutedText
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category-specific action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (asset.category) {
                    AssetCategory.IMAGES.name -> {
                        Button(
                            onClick = onOpenImage,
                            colors = ButtonDefaults.buttonColors(containerColor = AssetsSidebarText, contentColor = Color.White)
                        ) {
                            Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View Image")
                        }
                        OutlinedButton(
                            onClick = onExportSaf,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export")
                        }
                    }

                    AssetCategory.VIDEOS.name -> {
                        Button(
                            onClick = onPlayVideo,
                            colors = ButtonDefaults.buttonColors(containerColor = AssetsSidebarText, contentColor = Color.White)
                        ) {
                            Icon(Icons.Filled.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play Video")
                        }
                        OutlinedButton(
                            onClick = onExportSaf,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export")
                        }
                    }

                    AssetCategory.WEBSITES.name -> {
                        Button(
                            onClick = onPreviewWebsite,
                            colors = ButtonDefaults.buttonColors(containerColor = AssetsSidebarText, contentColor = Color.White),
                            modifier = Modifier.testTag("preview_website_button_${asset.id}")
                        ) {
                            Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Preview & Code")
                        }
                        OutlinedButton(
                            onClick = onExportSaf,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export HTML")
                        }
                    }

                    AssetCategory.APPS.name -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onPushGitHubBuild,
                                    colors = ButtonDefaults.buttonColors(containerColor = AssetsSidebarText, contentColor = Color.White)
                                ) {
                                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Push & Build APK")
                                }
                                OutlinedButton(
                                    onClick = onPollAndDownloadApk,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Poll / Get APK")
                                }
                            }
                            if (hasRealApk) {
                                OutlinedButton(
                                    onClick = onExportSaf,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AssetsSidebarText)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Export Real APK via SAF")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateOrGenerateAssetDialog(
    category: AssetCategory,
    onDismiss: () -> Unit,
    onSubmitWebsite: (String, String) -> Unit,
    onSubmitAndroidApp: (String, String) -> Unit,
    onSubmitImagePrompt: (String) -> Unit,
    onSubmitVideoPrompt: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (category) {
                    AssetCategory.IMAGES -> "Generate Image (SenseNova)"
                    AssetCategory.VIDEOS -> "Request Video (HelioRoute)"
                    AssetCategory.WEBSITES -> "Create Local Website Project"
                    AssetCategory.APPS -> "Generate Android App Project"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (category == AssetCategory.WEBSITES || category == AssetCategory.APPS) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = {
                            Text(if (category == AssetCategory.WEBSITES) "Project Title" else "Android App Name")
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("create_asset_title_input")
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = {
                        Text(
                            when (category) {
                                AssetCategory.IMAGES -> "Image prompt for SenseNova"
                                AssetCategory.VIDEOS -> "Video prompt for HelioRoute"
                                AssetCategory.WEBSITES -> "Describe website features & layout"
                                AssetCategory.APPS -> "Describe Android app purpose"
                            }
                        )
                    },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create_asset_prompt_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (prompt.isNotBlank()) {
                        when (category) {
                            AssetCategory.IMAGES -> onSubmitImagePrompt(prompt)
                            AssetCategory.VIDEOS -> onSubmitVideoPrompt(prompt)
                            AssetCategory.WEBSITES -> onSubmitWebsite(title, prompt)
                            AssetCategory.APPS -> onSubmitAndroidApp(title, prompt)
                        }
                    }
                },
                enabled = prompt.isNotBlank(),
                modifier = Modifier.testTag("submit_create_asset_button")
            ) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun InAppImageViewerDialog(
    asset: SavedAssetEntity,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val file = remember(asset.localPath) { File(asset.localPath) }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, asset.localPath) {
        value = withContext(Dispatchers.IO) {
            if (file.exists() && file.isFile) BitmapFactory.decodeFile(file.absolutePath) else null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0E1629)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Text(file.name, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                    }
                    Row {
                        TextButton(onClick = onExport) {
                            Text("Export (SAF)")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = asset.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "Unable to decode local image file at ${asset.localPath}.",
                            color = StatusErrorRed
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InAppVideoPlayerDialog(
    asset: SavedAssetEntity,
    onDismiss: () -> Unit
) {
    var playbackError by remember { mutableStateOf<String?>(null) }
    val videoFile = remember(asset.localPath) { File(asset.localPath) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF0E1629)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(asset.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (!videoFile.exists() || videoFile.length() == 0L) {
                    Text(
                        text = "Video file does not exist or is empty.",
                        color = StatusErrorRed
                    )
                } else if (playbackError != null) {
                    Text(
                        text = playbackError!!,
                        color = StatusErrorRed,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoURI(Uri.fromFile(videoFile))
                                setOnErrorListener { _, what, extra ->
                                    playbackError =
                                        "Android MediaPlayer could not play this video codec/container (what=$what, extra=$extra). You can still export the file via SAF."
                                    true
                                }
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppWebsitePreviewDialog(
    asset: SavedAssetEntity,
    onLoadFiles: suspend (SavedAssetEntity) -> Result<WebsiteProjectFiles>,
    onSaveEdits: (SavedAssetEntity, String, String, String) -> Unit,
    onExportHtml: () -> Unit,
    onDismiss: () -> Unit
) {
    var projectFiles by remember { mutableStateOf<WebsiteProjectFiles?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isCodeMode by rememberSaveable { mutableStateOf(false) }
    var enableJs by rememberSaveable { mutableStateOf(true) }
    var webViewError by remember { mutableStateOf<String?>(null) }

    var htmlDraft by rememberSaveable { mutableStateOf("") }
    var cssDraft by rememberSaveable { mutableStateOf("") }
    var jsDraft by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(asset.id) {
        val res = onLoadFiles(asset)
        res.onSuccess { files ->
            projectFiles = files
            htmlDraft = files.htmlContent
            cssDraft = files.cssContent
            jsDraft = files.jsContent
        }.onFailure { err ->
            loadError = err.message ?: "Failed to load website project files."
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = RoundedCornerShape(18.dp),
            color = AssetsMainWhite
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.title, style = MaterialTheme.typography.titleMedium, color = AssetsSidebarText)
                        Text(
                            "Local Unpublished Project • ${asset.localPath.substringAfterLast('/')}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AssetsSidebarMutedText
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !isCodeMode,
                            onClick = { isCodeMode = false },
                            label = { Text("WebView") }
                        )
                        FilterChip(
                            selected = isCodeMode,
                            onClick = { isCodeMode = true },
                            label = { Text("Source Code") },
                            leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enableJs,
                            onCheckedChange = { enableJs = it }
                        )
                        Text(
                            "Enable JavaScript in sandbox preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = AssetsSidebarText
                        )
                    }
                    TextButton(onClick = onExportHtml) {
                        Text("Export index.html (SAF)")
                    }
                }

                if (loadError != null) {
                    Text(loadError!!, color = StatusErrorRed, style = MaterialTheme.typography.bodyMedium)
                } else if (projectFiles != null) {
                    if (!isCodeMode) {
                        if (webViewError != null) {
                            Text(
                                text = "WebView rendering notice: $webViewError",
                                color = StatusErrorRed,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        val indexFile = projectFiles!!.indexHtmlFile
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.allowFileAccess = true
                                    settings.allowContentAccess = false
                                    settings.javaScriptEnabled = enableJs
                                    settings.domStorageEnabled = false
                                    webViewClient = object : WebViewClient() {
                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                webViewError = error?.description?.toString() ?: "Render error"
                                            }
                                        }
                                    }
                                    loadUrl(Uri.fromFile(indexFile).toString())
                                }
                            },
                            update = { webView ->
                                webView.settings.javaScriptEnabled = enableJs
                                webView.loadUrl(Uri.fromFile(indexFile).toString())
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = htmlDraft,
                                onValueChange = { htmlDraft = it },
                                label = { Text("index.html") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                minLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = cssDraft,
                                onValueChange = { cssDraft = it },
                                label = { Text("styles.css") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                minLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = jsDraft,
                                onValueChange = { jsDraft = it },
                                label = { Text("app.js") },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                minLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    onSaveEdits(asset, htmlDraft, cssDraft, jsDraft)
                                    isCodeMode = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AssetsSidebarText, contentColor = Color.White)
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Local Project Files")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }
}
