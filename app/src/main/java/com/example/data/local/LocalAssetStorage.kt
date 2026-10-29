package com.example.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StorageUsageSummary(
    val imagesBytes: Long,
    val videosBytes: Long,
    val websitesBytes: Long,
    val appsBytes: Long,
    val totalFilesCount: Int,
    val rootPath: String
)

data class WebsiteProjectFiles(
    val projectDir: File,
    val indexHtmlFile: File,
    val cssFile: File,
    val jsFile: File,
    val htmlContent: String,
    val cssContent: String,
    val jsContent: String
)

class LocalAssetStorage(
    private val context: Context,
    private val dao: KamakhyaDao
) {
    private val rootAssetsDir: File
        get() = File(context.filesDir, "kamakhya_assets").apply { mkdirs() }

    val imagesDir: File
        get() = File(rootAssetsDir, "images").apply { mkdirs() }

    val videosDir: File
        get() = File(rootAssetsDir, "videos").apply { mkdirs() }

    val websitesDir: File
        get() = File(rootAssetsDir, "websites").apply { mkdirs() }

    val appsDir: File
        get() = File(rootAssetsDir, "apps").apply { mkdirs() }

    val allAssetsFlow: Flow<List<SavedAssetEntity>> = dao.observeAllAssets()
    val actionLogsFlow: Flow<List<ActionLogEntity>> = dao.observeActionLogs()

    /**
     * Validates that a target file is strictly inside the app-owned assets directory
     * to prevent path traversal or accidental writes outside permitted directories.
     */
    fun validateSafeChildFile(parentDir: File, childRelativePath: String): File {
        val sanitizedRelative = childRelativePath
            .replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        require(sanitizedRelative.isNotBlank()) { "Invalid or empty file path." }
        val target = File(parentDir, sanitizedRelative)
        val parentCanonical = parentDir.canonicalPath
        val targetCanonical = target.canonicalPath
        require(
            targetCanonical == parentCanonical ||
                targetCanonical.startsWith(parentCanonical + File.separator)
        ) {
            "Security violation: Target path escapes app-owned directory."
        }
        return target
    }

    suspend fun saveImageBytes(
        title: String,
        prompt: String,
        bytes: ByteArray,
        mimeType: String = "image/png",
        provider: String = "SenseNova"
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.isNotEmpty()) { "Returned image data is empty." }
            val ext = if (mimeType.contains("jpeg") || mimeType.contains("jpg")) "jpg" else "png"
            val slug = sanitizeSlug(title).take(28).ifEmpty { "image" }
            val fileName = "${slug}_${System.currentTimeMillis()}.$ext"
            val targetFile = validateSafeChildFile(imagesDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }

            val entity = SavedAssetEntity(
                category = AssetCategory.IMAGES.name,
                title = title.ifBlank { "Generated Image" },
                description = prompt,
                localPath = targetFile.absolutePath,
                secondaryPath = "",
                mimeType = mimeType,
                fileSizeBytes = targetFile.length(),
                sourceProvider = provider,
                status = "SAVED_LOCALLY",
                metadataJson = JSONObject().apply {
                    put("sha256", computeSha256(targetFile))
                    put("fileName", targetFile.name)
                }.toString()
            )
            val id = dao.insertAsset(entity)
            entity.copy(id = id)
        }
    }

    suspend fun saveVideoBytes(
        title: String,
        prompt: String,
        bytes: ByteArray,
        mimeType: String = "video/mp4",
        provider: String = "HelioRoute"
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            require(bytes.size > 256) { "Video payload is empty or invalid." }
            val slug = sanitizeSlug(title).take(28).ifEmpty { "video" }
            val fileName = "${slug}_${System.currentTimeMillis()}.mp4"
            val targetFile = validateSafeChildFile(videosDir, fileName)
            FileOutputStream(targetFile).use { it.write(bytes) }

            val entity = SavedAssetEntity(
                category = AssetCategory.VIDEOS.name,
                title = title.ifBlank { "Generated Video" },
                description = prompt,
                localPath = targetFile.absolutePath,
                secondaryPath = "",
                mimeType = mimeType,
                fileSizeBytes = targetFile.length(),
                sourceProvider = provider,
                status = "SAVED_LOCALLY",
                metadataJson = JSONObject().apply {
                    put("sha256", computeSha256(targetFile))
                    put("fileName", targetFile.name)
                }.toString()
            )
            val id = dao.insertAsset(entity)
            entity.copy(id = id)
        }
    }

    /**
     * Generates a complete local HTML/CSS/JS website project in an app-owned directory.
     * Never claims the website is published online; marks status as LOCAL_PROJECT (Unpublished).
     */
    suspend fun createWebsiteProject(
        title: String,
        userPrompt: String,
        customHtml: String? = null,
        customCss: String? = null,
        customJs: String? = null,
        generatorSource: String = "Local Website Generator"
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPrompt = userPrompt.trim()
            require(cleanPrompt.isNotEmpty()) { "Website description cannot be empty." }

            val projectTitle = title.trim().ifEmpty {
                cleanPrompt.take(40).replaceFirstChar { it.uppercase() }
            }
            val folderName = "${sanitizeSlug(projectTitle).take(24)}_${System.currentTimeMillis()}"
            val projectDir = validateSafeChildFile(websitesDir, folderName).apply { mkdirs() }

            val generated = buildWebsiteFilesContent(projectTitle, cleanPrompt, customHtml, customCss, customJs)
            val indexFile = validateSafeChildFile(projectDir, "index.html")
            val cssFile = validateSafeChildFile(projectDir, "styles.css")
            val jsFile = validateSafeChildFile(projectDir, "app.js")

            indexFile.writeText(generated.first, StandardCharsets.UTF_8)
            cssFile.writeText(generated.second, StandardCharsets.UTF_8)
            jsFile.writeText(generated.third, StandardCharsets.UTF_8)

            val totalSize = indexFile.length() + cssFile.length() + jsFile.length()
            val metadata = JSONObject().apply {
                put("files", "index.html, styles.css, app.js")
                put("published", false)
                put("publishNote", "Stored in local private directory only. Not published online.")
            }

            val entity = SavedAssetEntity(
                category = AssetCategory.WEBSITES.name,
                title = projectTitle,
                description = cleanPrompt,
                localPath = projectDir.absolutePath,
                secondaryPath = indexFile.absolutePath,
                mimeType = "text/html",
                fileSizeBytes = totalSize,
                sourceProvider = generatorSource,
                status = "LOCAL_PROJECT (Unpublished)",
                metadataJson = metadata.toString()
            )
            val id = dao.insertAsset(entity)
            entity.copy(id = id)
        }
    }

    suspend fun loadWebsiteProjectFiles(asset: SavedAssetEntity): Result<WebsiteProjectFiles> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(asset.localPath)
                require(dir.exists() && dir.isDirectory) { "Website project directory no longer exists on disk." }
                val indexFile = validateSafeChildFile(dir, "index.html")
                val cssFile = validateSafeChildFile(dir, "styles.css")
                val jsFile = validateSafeChildFile(dir, "app.js")
                require(indexFile.exists() && indexFile.length() > 0L) { "index.html is missing or empty." }
                WebsiteProjectFiles(
                    projectDir = dir,
                    indexHtmlFile = indexFile,
                    cssFile = cssFile,
                    jsFile = jsFile,
                    htmlContent = indexFile.readText(StandardCharsets.UTF_8),
                    cssContent = if (cssFile.exists()) cssFile.readText(StandardCharsets.UTF_8) else "",
                    jsContent = if (jsFile.exists()) jsFile.readText(StandardCharsets.UTF_8) else ""
                )
            }
        }

    suspend fun updateWebsiteProjectFiles(
        asset: SavedAssetEntity,
        newHtml: String,
        newCss: String,
        newJs: String
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            require(newHtml.isNotBlank()) { "HTML content cannot be empty." }
            val dir = File(asset.localPath)
            require(dir.exists()) { "Project directory not found." }
            val indexFile = validateSafeChildFile(dir, "index.html")
            val cssFile = validateSafeChildFile(dir, "styles.css")
            val jsFile = validateSafeChildFile(dir, "app.js")
            indexFile.writeText(newHtml, StandardCharsets.UTF_8)
            cssFile.writeText(newCss, StandardCharsets.UTF_8)
            jsFile.writeText(newJs, StandardCharsets.UTF_8)
            val updated = asset.copy(
                fileSizeBytes = indexFile.length() + cssFile.length() + jsFile.length()
            )
            dao.updateAsset(updated)
            updated
        }
    }

    /**
     * Generates a complete, buildable Android Gradle project skeleton on local storage
     * and records it in the APPS category. Can be pushed to GitHub Actions to build a real APK.
     */
    suspend fun createAndroidAppProjectRecord(
        appName: String,
        appDescription: String,
        workflowFileName: String = "android-build.yml"
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = appName.trim().ifEmpty { "Kamakhya Generated App" }
            val slug = sanitizeSlug(cleanName).take(20).ifEmpty { "generatedapp" }
            val packageSuffix = slug.replace("_", "").take(12).ifEmpty { "app" }
            val packageName = "com.kamakhya.generated.$packageSuffix"
            val projectDir = validateSafeChildFile(appsDir, "${slug}_${System.currentTimeMillis()}").apply { mkdirs() }

            val filesMap = generateAndroidProjectFilesMap(
                appName = cleanName,
                packageName = packageName,
                description = appDescription.ifBlank { "Android app generated by Kamakhya AI" },
                workflowFileName = workflowFileName
            )

            var totalBytes = 0L
            for ((relPath, content) in filesMap) {
                val f = validateSafeChildFile(projectDir, relPath)
                f.parentFile?.mkdirs()
                f.writeText(content, StandardCharsets.UTF_8)
                totalBytes += f.length()
            }

            val metadata = JSONObject().apply {
                put("packageName", packageName)
                put("workflowFile", workflowFileName)
                put("hasRealApk", false)
                put("buildStatus", "LOCAL_SOURCE_GENERATED")
            }

            val entity = SavedAssetEntity(
                category = AssetCategory.APPS.name,
                title = cleanName,
                description = appDescription.ifBlank { "Android project source generated locally ($packageName)." },
                localPath = projectDir.absolutePath,
                secondaryPath = "", // Populated only when a real .apk file exists on disk
                mimeType = "application/vnd.android.package-archive",
                fileSizeBytes = totalBytes,
                sourceProvider = "Android Project Generator",
                status = "SOURCE_ONLY (No APK Built Yet)",
                metadataJson = metadata.toString()
            )
            val id = dao.insertAsset(entity)
            entity.copy(id = id)
        }
    }

    suspend fun attachDownloadedApkToRecord(
        asset: SavedAssetEntity,
        apkBytes: ByteArray,
        runId: Long,
        runUrl: String
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            require(apkBytes.size > 4 && apkBytes[0] == 0x50.toByte() && apkBytes[1] == 0x4B.toByte()) {
                "Downloaded file does not have a valid APK/ZIP header."
            }
            val dir = File(asset.localPath).apply { mkdirs() }
            val apkFileName = "${sanitizeSlug(asset.title).ifEmpty { "app" }}-debug.apk"
            val apkFile = validateSafeChildFile(dir, apkFileName)
            FileOutputStream(apkFile).use { it.write(apkBytes) }

            val sha256 = computeSha256(apkFile)
            val meta = runCatching { JSONObject(asset.metadataJson) }.getOrElse { JSONObject() }.apply {
                put("hasRealApk", true)
                put("apkFileName", apkFile.name)
                put("apkSizeBytes", apkFile.length())
                put("apkSha256", sha256)
                put("githubRunId", runId)
                put("githubRunUrl", runUrl)
                put("buildStatus", "APK_DOWNLOADED")
            }

            val updated = asset.copy(
                secondaryPath = apkFile.absolutePath,
                fileSizeBytes = apkFile.length(),
                sourceProvider = "GitHub Actions APK Build",
                status = "REAL_APK_AVAILABLE",
                metadataJson = meta.toString()
            )
            dao.updateAsset(updated)
            updated
        }
    }

    suspend fun updateAssetRecord(asset: SavedAssetEntity) = withContext(Dispatchers.IO) {
        dao.updateAsset(asset)
    }

    suspend fun deleteAsset(asset: SavedAssetEntity) = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(asset.localPath)
            if (target.exists() && target.canonicalPath.startsWith(rootAssetsDir.canonicalPath)) {
                target.deleteRecursively()
            }
            if (asset.secondaryPath.isNotBlank()) {
                val sec = File(asset.secondaryPath)
                if (sec.exists() && sec.canonicalPath.startsWith(rootAssetsDir.canonicalPath)) {
                    sec.delete()
                }
            }
        }
        dao.deleteAssetById(asset.id)
    }

    suspend fun clearAllLocalAssets() = withContext(Dispatchers.IO) {
        runCatching {
            if (rootAssetsDir.exists()) {
                rootAssetsDir.deleteRecursively()
            }
            rootAssetsDir.mkdirs()
        }
        dao.deleteAllAssets()
    }

    /**
     * Exports a locally saved asset file to an external URI chosen by the user via
     * Android's Storage Access Framework (SAF).
     */
    suspend fun exportAssetToSafUri(asset: SavedAssetEntity, destinationUri: Uri): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sourceFile = when {
                    asset.secondaryPath.isNotBlank() && File(asset.secondaryPath).isFile -> File(asset.secondaryPath)
                    File(asset.localPath).isFile -> File(asset.localPath)
                    else -> throw IllegalStateException("No exportable single file exists for this record.")
                }
                require(sourceFile.exists() && sourceFile.length() > 0L) {
                    "Local file does not exist or is empty: ${sourceFile.name}"
                }
                var bytesCopied = 0L
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        bytesCopied = input.copyTo(out)
                    }
                } ?: throw IllegalStateException("ContentResolver could not open output stream for selected URI.")
                require(bytesCopied > 0L) { "0 bytes written to destination." }
                bytesCopied
            }
        }

    /**
     * Imports a real user-selected file via Android's Storage Access Framework (SAF)
     * into the appropriate local category.
     */
    suspend fun importFileFromSafUri(
        category: AssetCategory,
        sourceUri: Uri
    ): Result<SavedAssetEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(sourceUri) ?: when (category) {
                AssetCategory.IMAGES -> "image/png"
                AssetCategory.VIDEOS -> "video/mp4"
                AssetCategory.WEBSITES -> "text/html"
                AssetCategory.APPS -> "application/vnd.android.package-archive"
            }

            var displayName = "imported_${System.currentTimeMillis()}"
            resolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
            }

            val bytes = resolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read selected file from Storage Access Framework.")
            require(bytes.isNotEmpty()) { "Selected file is empty (0 bytes)." }

            when (category) {
                AssetCategory.IMAGES -> saveImageBytes(
                    title = displayName,
                    prompt = "Imported via Android Storage Access Framework",
                    bytes = bytes,
                    mimeType = mimeType,
                    provider = "SAF Local Import"
                ).getOrThrow()

                AssetCategory.VIDEOS -> saveVideoBytes(
                    title = displayName,
                    prompt = "Imported via Android Storage Access Framework",
                    bytes = bytes,
                    mimeType = mimeType,
                    provider = "SAF Local Import"
                ).getOrThrow()

                AssetCategory.WEBSITES -> {
                    val html = String(bytes, StandardCharsets.UTF_8)
                    createWebsiteProject(
                        title = displayName.removeSuffix(".html"),
                        userPrompt = "Imported HTML file via SAF",
                        customHtml = html,
                        generatorSource = "SAF Local Import"
                    ).getOrThrow()
                }

                AssetCategory.APPS -> {
                    val slug = sanitizeSlug(displayName).take(24).ifEmpty { "imported_apk" }
                    val dir = validateSafeChildFile(appsDir, "${slug}_${System.currentTimeMillis()}").apply { mkdirs() }
                    val apkFile = validateSafeChildFile(dir, if (displayName.endsWith(".apk")) displayName else "$displayName.apk")
                    FileOutputStream(apkFile).use { it.write(bytes) }
                    val sha256 = computeSha256(apkFile)
                    val entity = SavedAssetEntity(
                        category = AssetCategory.APPS.name,
                        title = displayName,
                        description = "Local APK imported via Storage Access Framework",
                        localPath = dir.absolutePath,
                        secondaryPath = apkFile.absolutePath,
                        mimeType = "application/vnd.android.package-archive",
                        fileSizeBytes = apkFile.length(),
                        sourceProvider = "SAF Local Import",
                        status = "REAL_APK_AVAILABLE",
                        metadataJson = JSONObject().apply {
                            put("hasRealApk", true)
                            put("apkFileName", apkFile.name)
                            put("apkSizeBytes", apkFile.length())
                            put("apkSha256", sha256)
                        }.toString()
                    )
                    val id = dao.insertAsset(entity)
                    entity.copy(id = id)
                }
            }
        }
    }

    suspend fun logAction(
        actionName: String,
        outcomeStatus: String,
        outcomeSummary: String
    ) = withContext(Dispatchers.IO) {
        dao.insertActionLog(
            ActionLogEntity(
                actionName = actionName.take(80),
                outcomeStatus = outcomeStatus.take(40),
                outcomeSummary = outcomeSummary.take(240)
            )
        )
    }

    suspend fun clearActionHistory() = withContext(Dispatchers.IO) {
        dao.clearActionLogs()
    }

    suspend fun computeStorageUsage(): StorageUsageSummary = withContext(Dispatchers.IO) {
        fun dirSize(dir: File): Pair<Long, Int> {
            if (!dir.exists()) return 0L to 0
            var bytes = 0L
            var count = 0
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    bytes += f.length()
                    count += 1
                }
            }
            return bytes to count
        }
        val (imgB, imgC) = dirSize(imagesDir)
        val (vidB, vidC) = dirSize(videosDir)
        val (webB, webC) = dirSize(websitesDir)
        val (appB, appC) = dirSize(appsDir)
        StorageUsageSummary(
            imagesBytes = imgB,
            videosBytes = vidB,
            websitesBytes = webB,
            appsBytes = appB,
            totalFilesCount = imgC + vidC + webC + appC,
            rootPath = rootAssetsDir.absolutePath
        )
    }

    private fun buildWebsiteFilesContent(
        title: String,
        prompt: String,
        customHtml: String?,
        customCss: String?,
        customJs: String?
    ): Triple<String, String, String> {
        val safeTitle = escapeHtml(title)
        val safePrompt = escapeHtml(prompt)
        val timestamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())

        val css = customCss?.takeIf { it.isNotBlank() } ?: """
            :root {
                --bg: #0b1120;
                --card: #152238;
                --accent: #00e5ff;
                --accent-secondary: #7c4dff;
                --text: #f1f5f9;
                --muted: #94a3b8;
            }
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: radial-gradient(circle at top right, #172554, var(--bg));
                color: var(--text);
                min-height: 100vh;
                padding: 24px 16px;
                line-height: 1.6;
            }
            .container { max-width: 840px; margin: 0 auto; }
            header {
                border: 1px solid rgba(0, 229, 255, 0.25);
                background: rgba(21, 34, 56, 0.85);
                border-radius: 18px;
                padding: 24px;
                margin-bottom: 20px;
                box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
            }
            .badge {
                display: inline-block;
                font-size: 12px;
                text-transform: uppercase;
                letter-spacing: 1px;
                padding: 4px 10px;
                border-radius: 999px;
                background: rgba(0, 229, 255, 0.14);
                color: var(--accent);
                margin-bottom: 10px;
            }
            h1 { font-size: 26px; margin-bottom: 8px; }
            .subtitle { color: var(--muted); font-size: 14px; }
            .grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
                gap: 16px;
                margin-bottom: 20px;
            }
            .card {
                background: var(--card);
                border: 1px solid rgba(255, 255, 255, 0.08);
                border-radius: 14px;
                padding: 18px;
            }
            .card h2 { font-size: 17px; margin-bottom: 8px; color: var(--accent); }
            .interactive-box {
                background: var(--card);
                border-radius: 14px;
                padding: 20px;
                border: 1px solid rgba(124, 77, 255, 0.35);
            }
            input, textarea {
                width: 100%;
                padding: 10px 12px;
                border-radius: 8px;
                border: 1px solid rgba(255,255,255,0.18);
                background: rgba(11, 17, 32, 0.9);
                color: var(--text);
                margin-top: 8px;
                margin-bottom: 12px;
            }
            button {
                background: linear-gradient(135deg, var(--accent), var(--accent-secondary));
                color: #06101e;
                font-weight: 700;
                border: none;
                padding: 10px 18px;
                border-radius: 8px;
                cursor: pointer;
            }
            ul#itemsList { list-style: none; margin-top: 14px; }
            ul#itemsList li {
                padding: 10px 12px;
                background: rgba(255,255,255,0.05);
                border-radius: 8px;
                margin-bottom: 8px;
                display: flex;
                justify-content: space-between;
            }
        """.trimIndent()

        val js = customJs?.takeIf { it.isNotBlank() } ?: """
            document.addEventListener('DOMContentLoaded', () => {
                const addBtn = document.getElementById('addEntryBtn');
                const input = document.getElementById('entryInput');
                const list = document.getElementById('itemsList');
                const countSpan = document.getElementById('entryCount');
                let items = ['Initial module initialized', 'Local asset storage verified'];

                function render() {
                    if (!list) return;
                    list.innerHTML = '';
                    items.forEach((txt, idx) => {
                        const li = document.createElement('li');
                        const span = document.createElement('span');
                        span.textContent = txt;
                        const del = document.createElement('button');
                        del.textContent = 'Remove';
                        del.style.padding = '4px 10px';
                        del.style.fontSize = '12px';
                        del.onclick = () => {
                            items.splice(idx, 1);
                            render();
                        };
                        li.appendChild(span);
                        li.appendChild(del);
                        list.appendChild(li);
                    });
                    if (countSpan) countSpan.textContent = String(items.length);
                }

                if (addBtn && input) {
                    addBtn.addEventListener('click', () => {
                        const val = input.value.trim();
                        if (val) {
                            items.unshift(val);
                            input.value = '';
                            render();
                        }
                    });
                }
                render();
            });
        """.trimIndent()

        val html = customHtml?.takeIf { it.isNotBlank() } ?: """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>$safeTitle</title>
                <link rel="stylesheet" href="styles.css" />
            </head>
            <body>
                <div class="container">
                    <header>
                        <span class="badge">Local Website Project • Unpublished</span>
                        <h1>$safeTitle</h1>
                        <p class="subtitle">Specification: $safePrompt</p>
                        <p class="subtitle" style="margin-top:6px;">Generated locally on $timestamp</p>
                    </header>

                    <section class="grid">
                        <div class="card">
                            <h2>Project Overview</h2>
                            <p>This responsive HTML5/CSS3/JS project was generated directly on your device for: <strong>$safePrompt</strong>.</p>
                        </div>
                        <div class="card">
                            <h2>Interactive Status</h2>
                            <p>Active Workspace Entries: <strong id="entryCount">2</strong></p>
                            <p style="font-size:12px;color:#94a3b8;margin-top:6px;">All scripts run locally inside the sandboxed preview.</p>
                        </div>
                    </section>

                    <section class="interactive-box">
                        <h2>Interactive Workspace</h2>
                        <label for="entryInput">Add an item or note to test live DOM interactivity:</label>
                        <input id="entryInput" type="text" placeholder="Type an item and tap Add..." />
                        <button id="addEntryBtn" type="button">Add Item</button>
                        <ul id="itemsList"></ul>
                    </section>
                </div>
                <script src="app.js"></script>
            </body>
            </html>
        """.trimIndent()

        return Triple(html, css, js)
    }

    fun generateAndroidProjectFilesMap(
        appName: String,
        packageName: String,
        description: String,
        workflowFileName: String
    ): Map<String, String> {
        val packagePath = packageName.replace('.', '/')
        val safeAppName = escapeXml(appName)
        val safeDesc = escapeKotlinString(description)
        val safeTitle = escapeKotlinString(appName)

        return mapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "$safeTitle"
                include(":app")
            """.trimIndent(),

            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.7.2" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
                }
            """.trimIndent(),

            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
                android.nonTransitiveRClass=true
            """.trimIndent(),

            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                    id("org.jetbrains.kotlin.plugin.compose")
                }

                android {
                    namespace = "$packageName"
                    compileSdk = 35

                    defaultConfig {
                        applicationId = "$packageName"
                        minSdk = 24
                        targetSdk = 35
                        versionCode = 1
                        versionName = "1.0"
                    }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions {
                        jvmTarget = "17"
                    }
                    buildFeatures {
                        compose = true
                    }
                }

                dependencies {
                    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
                    implementation("androidx.core:core-ktx:1.13.1")
                    implementation("androidx.activity:activity-compose:1.9.2")
                    implementation("androidx.compose.ui:ui")
                    implementation("androidx.compose.material3:material3")
                }
            """.trimIndent(),

            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:allowBackup="true"
                        android:label="$safeAppName"
                        android:supportsRtl="true"
                        android:theme="@android:style/Theme.Material.Light.NoActionBar">
                        <activity
                            android:name=".MainActivity"
                            android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),

            "app/src/main/java/$packagePath/MainActivity.kt" to """
                package $packageName

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.layout.*
                import androidx.compose.material3.*
                import androidx.compose.runtime.*
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.unit.dp

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            MaterialTheme {
                                var count by remember { mutableIntStateOf(0) }
                                Surface(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(text = "$safeTitle", style = MaterialTheme.typography.headlineMedium)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(text = "$safeDesc", style = MaterialTheme.typography.bodyLarge)
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Button(onClick = { count++ }) {
                                            Text("Interactions: ${'$'}count")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent(),

            ".github/workflows/$workflowFileName" to """
                name: Build Android Debug APK

                on:
                  push:
                    branches: [ "main", "master" ]
                  workflow_dispatch:

                jobs:
                  build:
                    runs-on: ubuntu-latest
                    steps:
                      - name: Checkout repository
                        uses: actions/checkout@v4

                      - name: Set up JDK 17
                        uses: actions/setup-java@v4
                        with:
                          java-version: '17'
                          distribution: 'temurin'

                      - name: Setup Gradle
                        uses: gradle/actions/setup-gradle@v4
                        with:
                          gradle-version: '8.9'

                      - name: Build Debug APK
                        run: gradle assembleDebug --no-daemon

                      - name: Upload Debug APK Artifact
                        uses: actions/upload-artifact@v4
                        with:
                          name: app-debug-apk
                          path: app/build/outputs/apk/debug/*.apk
            """.trimIndent()
        )
    }

    private fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun sanitizeSlug(input: String): String {
        return input.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun escapeXml(text: String): String {
        return escapeHtml(text).replace("'", "&apos;")
    }

    private fun escapeKotlinString(text: String): String {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
    }
}
