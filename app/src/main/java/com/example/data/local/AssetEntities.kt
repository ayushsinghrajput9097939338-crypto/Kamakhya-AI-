package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AssetCategory(val displayName: String) {
    IMAGES("Images"),
    VIDEOS("Videos"),
    WEBSITES("Websites"),
    APPS("Apps")
}

@Entity(tableName = "saved_assets")
data class SavedAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String, // AssetCategory.name
    val title: String,
    val description: String,
    val localPath: String, // Primary file or directory path on local storage
    val secondaryPath: String = "", // e.g., index.html path or downloaded .apk file path
    val mimeType: String,
    val fileSizeBytes: Long,
    val sourceProvider: String, // e.g. "SenseNova", "Local Website Generator", "GitHub Actions APK", "SAF Import"
    val status: String, // e.g. "LOCAL_FILE", "LOCAL_PROJECT (Unpublished)", "PROJECT_READY", "BUILD_RUNNING", "APK_READY", "BUILD_FAILED"
    val metadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val actionName: String,
    val outcomeStatus: String, // "SUCCESS", "REQUIRES_USER_ACTION", "PERMISSION_MISSING", "AMBIGUOUS", "FAILED", "UNAVAILABLE"
    val outcomeSummary: String, // Strictly sanitized outcome summary (no keys, tokens, or private transcripts)
    val timestamp: Long = System.currentTimeMillis()
)
