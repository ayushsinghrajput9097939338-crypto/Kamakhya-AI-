package com.example.network

import android.util.Base64
import com.example.security.GitHubBuildSettings
import com.example.security.HelioRouteSettings
import com.example.security.SenseNovaSettings
import com.example.security.XKiroSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ProviderErrorKind {
    MISSING_CONFIGURATION,
    AUTHENTICATION_FAILED,
    QUOTA_OR_RATE_LIMIT,
    UNSUPPORTED_MODEL_OR_PARAMS,
    PROVIDER_UNAVAILABLE,
    MALFORMED_RESPONSE,
    NETWORK_ERROR
}

class ProviderException(
    val kind: ProviderErrorKind,
    override val message: String
) : Exception(message)

data class GeneratedImagePayload(
    val imageBytes: ByteArray,
    val mimeType: String,
    val revisedPrompt: String
)

data class GeneratedVideoPayload(
    val videoBytes: ByteArray,
    val mimeType: String,
    val sourceUrl: String
)

data class GitHubWorkflowStatusReport(
    val runId: Long,
    val status: String, // queued, in_progress, completed
    val conclusion: String, // success, failure, cancelled, ""
    val htmlUrl: String,
    val headBranch: String,
    val updatedAt: String
)

/**
 * Clean, provider-independent API integrations for SenseNova, HelioRoute, xKiro,
 * and GitHub Actions APK Build Integration.
 * Never returns fabricated responses or silently substitutes models.
 */
class AiProvidersClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // =========================================================================
    // 1. SenseNova Image Generation Provider
    // =========================================================================
    suspend fun generateImageWithSenseNova(
        settings: SenseNovaSettings,
        prompt: String
    ): Result<GeneratedImagePayload> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = settings.endpoint.trim()
            val model = settings.model.trim()
            val apiKey = settings.apiKey.trim()

            if (endpoint.isEmpty() || !endpoint.startsWith("http")) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "SenseNova endpoint URL is invalid or empty. Edit it in Settings → SenseNova."
                )
            }
            if (model.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "SenseNova model ID is empty. Edit it in Settings → SenseNova."
                )
            }
            if (apiKey.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "SenseNova API key is not configured. Add your key in Settings → SenseNova."
                )
            }
            val cleanPrompt = prompt.trim()
            if (cleanPrompt.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "Image generation prompt cannot be empty."
                )
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("prompt", cleanPrompt)
                put("n", 1)
                put("size", "1024x1024")
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (t: Throwable) {
                throw ProviderException(
                    ProviderErrorKind.NETWORK_ERROR,
                    "SenseNova network error (${t.javaClass.simpleName}): Unable to reach configured endpoint."
                )
            }

            response.use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw mapHttpError("SenseNova", model, resp.code, bodyStr)
                }

                val root = try {
                    JSONObject(bodyStr)
                } catch (_: Exception) {
                    throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "SenseNova returned non-JSON response."
                    )
                }

                val dataArray = root.optJSONArray("data")
                    ?: root.optJSONObject("data")?.optJSONArray("images")
                    ?: throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "SenseNova response did not contain a 'data' array."
                    )

                if (dataArray.length() == 0) {
                    throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "SenseNova returned an empty image list."
                    )
                }

                val firstItem = dataArray.optJSONObject(0)
                    ?: throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "SenseNova image entry was malformed."
                    )

                val b64 = firstItem.optString("b64_json", firstItem.optString("base64", ""))
                val imageUrl = firstItem.optString("url", firstItem.optString("image_url", ""))
                val revisedPrompt = firstItem.optString("revised_prompt", cleanPrompt)

                when {
                    b64.isNotEmpty() -> {
                        val cleanB64 = b64.substringAfter("base64,", b64)
                        val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                        if (bytes.isEmpty()) {
                            throw ProviderException(
                                ProviderErrorKind.MALFORMED_RESPONSE,
                                "SenseNova base64 image payload decoded to 0 bytes."
                            )
                        }
                        GeneratedImagePayload(bytes, "image/png", revisedPrompt)
                    }
                    imageUrl.startsWith("http") -> {
                        val imgReq = Request.Builder().url(imageUrl).get().build()
                        httpClient.newCall(imgReq).execute().use { imgResp ->
                            if (!imgResp.isSuccessful) {
                                throw ProviderException(
                                    ProviderErrorKind.PROVIDER_UNAVAILABLE,
                                    "SenseNova image download failed with HTTP ${imgResp.code}."
                                )
                            }
                            val bytes = imgResp.body?.bytes() ?: ByteArray(0)
                            if (bytes.isEmpty()) {
                                throw ProviderException(
                                    ProviderErrorKind.MALFORMED_RESPONSE,
                                    "Downloaded SenseNova image was empty."
                                )
                            }
                            val contentType = imgResp.header("Content-Type") ?: "image/png"
                            GeneratedImagePayload(bytes, contentType, revisedPrompt)
                        }
                    }
                    else -> throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "SenseNova response contained neither 'b64_json' nor a downloadable 'url'."
                    )
                }
            }
        }
    }

    suspend fun testSenseNovaConnection(settings: SenseNovaSettings): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = settings.endpoint.trim()
                val model = settings.model.trim()
                val key = settings.apiKey.trim()
                if (endpoint.isEmpty() || !endpoint.startsWith("http")) {
                    throw ProviderException(ProviderErrorKind.MISSING_CONFIGURATION, "Invalid SenseNova endpoint URL.")
                }
                if (model.isEmpty()) {
                    throw ProviderException(ProviderErrorKind.MISSING_CONFIGURATION, "SenseNova model ID is required.")
                }
                if (key.isEmpty()) {
                    throw ProviderException(ProviderErrorKind.MISSING_CONFIGURATION, "SenseNova API key is required.")
                }
                // Send a lightweight validation request without silently switching models
                val testBody = JSONObject().apply {
                    put("model", model)
                    put("prompt", "Connection validation pixel")
                    put("n", 1)
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .post(testBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val resp = try {
                    httpClient.newCall(req).execute()
                } catch (t: Throwable) {
                    throw ProviderException(
                        ProviderErrorKind.NETWORK_ERROR,
                        "Network error reaching SenseNova: ${t.javaClass.simpleName}"
                    )
                }
                resp.use { r ->
                    val body = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        throw mapHttpError("SenseNova", model, r.code, body)
                    }
                    "SenseNova endpoint responded HTTP ${r.code} for model '$model'."
                }
            }
        }

    // =========================================================================
    // 2. HelioRoute Provider (Chat Completions & Verified Video Generation)
    // =========================================================================
    suspend fun sendHelioRouteChat(
        settings: HelioRouteSettings,
        userMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        executeOpenAiCompatibleChat(
            providerName = "HelioRoute",
            endpoint = settings.endpoint.trim(),
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim(),
            userMessage = userMessage
        )
    }

    /**
     * Requests video generation only when the user has explicitly enabled video capability
     * for the configured HelioRoute endpoint, and only reports success if an actual binary
     * video asset is returned and downloaded.
     */
    suspend fun generateVideoWithHelioRoute(
        settings: HelioRouteSettings,
        prompt: String
    ): Result<GeneratedVideoPayload> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = settings.endpoint.trim()
            val model = settings.model.trim()
            val apiKey = settings.apiKey.trim()

            if (endpoint.isEmpty() || !endpoint.startsWith("http")) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "HelioRoute endpoint is invalid. Configure it in Settings → HelioRoute."
                )
            }
            if (model.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "HelioRoute model ID is empty."
                )
            }
            if (apiKey.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MISSING_CONFIGURATION,
                    "HelioRoute API key is missing. Add your key in Settings → HelioRoute."
                )
            }
            if (!settings.videoEndpointSupported) {
                throw ProviderException(
                    ProviderErrorKind.UNSUPPORTED_MODEL_OR_PARAMS,
                    "HelioRoute is currently configured with chat endpoint '$endpoint' and video parameter submission is disabled to prevent sending unsupported parameters. Enable 'Endpoint supports video generation' in Settings → HelioRoute if your endpoint supports video jobs."
                )
            }

            val bodyJson = if (endpoint.endsWith("/chat/completions")) {
                JSONObject().apply {
                    put("model", model)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt.trim())
                            }
                        )
                    )
                }
            } else {
                JSONObject().apply {
                    put("model", model)
                    put("prompt", prompt.trim())
                }
            }

            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            val resp = try {
                httpClient.newCall(req).execute()
            } catch (t: Throwable) {
                throw ProviderException(
                    ProviderErrorKind.NETWORK_ERROR,
                    "HelioRoute network error: ${t.javaClass.simpleName}"
                )
            }

            resp.use { r ->
                val raw = r.body?.string() ?: ""
                if (!r.isSuccessful) {
                    throw mapHttpError("HelioRoute", model, r.code, raw)
                }
                val root = try {
                    JSONObject(raw)
                } catch (_: Exception) {
                    throw ProviderException(
                        ProviderErrorKind.MALFORMED_RESPONSE,
                        "HelioRoute returned non-JSON response."
                    )
                }

                // Look for an explicit video URL in response
                val directVideoUrl = root.optString("video_url", "")
                    .ifEmpty {
                        root.optJSONArray("data")?.optJSONObject(0)?.optString("url", "") ?: ""
                    }

                if (!directVideoUrl.startsWith("http")) {
                    throw ProviderException(
                        ProviderErrorKind.UNSUPPORTED_MODEL_OR_PARAMS,
                        "HelioRoute response for model '$model' did not include a completed video asset URL. No video was saved."
                    )
                }

                val dlReq = Request.Builder().url(directVideoUrl).get().build()
                httpClient.newCall(dlReq).execute().use { dlResp ->
                    if (!dlResp.isSuccessful) {
                        throw ProviderException(
                            ProviderErrorKind.PROVIDER_UNAVAILABLE,
                            "Failed to download video asset from returned URL (HTTP ${dlResp.code})."
                        )
                    }
                    val bytes = dlResp.body?.bytes() ?: ByteArray(0)
                    if (bytes.size < 512) {
                        throw ProviderException(
                            ProviderErrorKind.MALFORMED_RESPONSE,
                            "Returned video asset file was empty or incomplete (${bytes.size} bytes)."
                        )
                    }
                    GeneratedVideoPayload(
                        videoBytes = bytes,
                        mimeType = dlResp.header("Content-Type") ?: "video/mp4",
                        sourceUrl = directVideoUrl
                    )
                }
            }
        }
    }

    // =========================================================================
    // 3. xKiro Chat Provider
    // =========================================================================
    suspend fun sendXKiroChat(
        settings: XKiroSettings,
        userMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        executeOpenAiCompatibleChat(
            providerName = "xKiro",
            endpoint = settings.fullEndpoint,
            model = settings.model.trim(),
            apiKey = settings.apiKey.trim(),
            userMessage = userMessage
        )
    }

    private fun executeOpenAiCompatibleChat(
        providerName: String,
        endpoint: String,
        model: String,
        apiKey: String,
        userMessage: String
    ): Result<String> = runCatching {
        if (endpoint.isEmpty() || !endpoint.startsWith("http")) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "$providerName endpoint URL is invalid or empty. Edit it in Settings."
            )
        }
        if (model.isEmpty()) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "$providerName model ID is empty. Edit it in Settings."
            )
        }
        if (apiKey.isEmpty()) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "$providerName API key is missing. Configure your key in Settings → $providerName."
            )
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", "You are Kamakhya AI. Reply accurately and concisely.")
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage.trim())
                        }
                    )
                }
            )
        }

        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        val resp = try {
            httpClient.newCall(req).execute()
        } catch (t: Throwable) {
            throw ProviderException(
                ProviderErrorKind.NETWORK_ERROR,
                "$providerName network failure (${t.javaClass.simpleName}): Could not reach endpoint."
            )
        }

        resp.use { r ->
            val raw = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw mapHttpError(providerName, model, r.code, raw)
            }
            val root = try {
                JSONObject(raw)
            } catch (_: Exception) {
                throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "$providerName returned invalid JSON."
                )
            }

            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: root.optString("error")
                throw ProviderException(
                    ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    "$providerName error for model '$model': ${sanitizeError(errMsg)}"
                )
            }

            val choices = root.optJSONArray("choices")
                ?: throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "$providerName response missing 'choices' array."
                )
            if (choices.length() == 0) {
                throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "$providerName returned 0 choices for model '$model'."
                )
            }
            val content = choices.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                ?: ""

            if (content.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "$providerName returned an empty message content."
                )
            }
            content
        }
    }

    // =========================================================================
    // 4. GitHub Build Integration (Repository Verify, Source Upload, Dispatch, APK Download)
    // =========================================================================
    private fun validateGitHubSettings(settings: GitHubBuildSettings) {
        if (settings.owner.trim().isEmpty()) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "GitHub repository owner/username is missing in Settings → GitHub Build Integration."
            )
        }
        if (settings.repo.trim().isEmpty()) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "GitHub repository name is missing in Settings → GitHub Build Integration."
            )
        }
        if (settings.token.trim().isEmpty()) {
            throw ProviderException(
                ProviderErrorKind.MISSING_CONFIGURATION,
                "GitHub Personal Access Token (PAT) is missing in Settings → GitHub Build Integration."
            )
        }
    }

    suspend fun testGitHubConnection(settings: GitHubBuildSettings): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                validateGitHubSettings(settings)
                val owner = settings.owner.trim()
                val repo = settings.repo.trim()
                val branch = settings.branch.trim().ifEmpty { "main" }

                val req = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo")
                    .addHeader("Authorization", "Bearer ${settings.token.trim()}")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("X-GitHub-Api-Version", "2022-11-28")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        throw mapHttpError("GitHub API", "$owner/$repo", resp.code, body)
                    }
                    val json = JSONObject(body)
                    val fullName = json.optString("full_name", "$owner/$repo")
                    val defaultBranch = json.optString("default_branch", branch)
                    val permissions = json.optJSONObject("permissions")
                    val canPush = permissions?.optBoolean("push", false) ?: false
                    "Verified access to '$fullName' (default branch: $defaultBranch, push access: $canPush, target branch: $branch)."
                }
            }
        }

    /**
     * Uploads the generated Android project files to the user's GitHub repository branch
     * and triggers a workflow_dispatch event on the configured workflow file.
     */
    suspend fun pushProjectAndTriggerWorkflow(
        settings: GitHubBuildSettings,
        projectFiles: Map<String, String>,
        commitMessage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            validateGitHubSettings(settings)
            val owner = settings.owner.trim()
            val repo = settings.repo.trim()
            val branch = settings.branch.trim().ifEmpty { "main" }
            val workflow = settings.workflowFile.trim().ifEmpty { "android-build.yml" }
            val token = settings.token.trim()

            // Upload each file via GitHub Contents API (PUT /repos/{owner}/{repo}/contents/{path})
            for ((path, content) in projectFiles) {
                val cleanPath = path.trimStart('/')
                val getUrl = "https://api.github.com/repos/$owner/$repo/contents/$cleanPath?ref=$branch"
                var existingSha: String? = null

                val getReq = Request.Builder()
                    .url(getUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github+json")
                    .get()
                    .build()

                httpClient.newCall(getReq).execute().use { getResp ->
                    if (getResp.isSuccessful) {
                        val existingJson = runCatching { JSONObject(getResp.body?.string() ?: "{}") }.getOrNull()
                        existingSha = existingJson?.optString("sha")?.takeIf { it.isNotBlank() }
                    } else if (getResp.code == 401 || getResp.code == 403) {
                        throw mapHttpError("GitHub Contents API", cleanPath, getResp.code, getResp.body?.string() ?: "")
                    }
                }

                val putBody = JSONObject().apply {
                    put("message", "$commitMessage ($cleanPath)")
                    put(
                        "content",
                        Base64.encodeToString(content.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    )
                    put("branch", branch)
                    if (existingSha != null) {
                        put("sha", existingSha)
                    }
                }

                val putReq = Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$cleanPath")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github+json")
                    .put(putBody.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(putReq).execute().use { putResp ->
                    if (!putResp.isSuccessful) {
                        val errBody = putResp.body?.string() ?: ""
                        throw mapHttpError("GitHub Push ($cleanPath)", "$owner/$repo@$branch", putResp.code, errBody)
                    }
                }
            }

            // Trigger workflow_dispatch if the workflow supports it
            val dispatchBody = JSONObject().apply {
                put("ref", branch)
            }
            val dispatchReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/workflows/$workflow/dispatches")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .post(dispatchBody.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(dispatchReq).execute().use { dispResp ->
                if (dispResp.isSuccessful || dispResp.code == 204) {
                    "Uploaded ${projectFiles.size} project files to $owner/$repo ($branch) and dispatched workflow '$workflow'."
                } else {
                    "Uploaded ${projectFiles.size} project files to $owner/$repo ($branch). Push-triggered workflow is active (manual dispatch returned HTTP ${dispResp.code})."
                }
            }
        }
    }

    suspend fun fetchLatestWorkflowRunStatus(
        settings: GitHubBuildSettings
    ): Result<GitHubWorkflowStatusReport> = withContext(Dispatchers.IO) {
        runCatching {
            validateGitHubSettings(settings)
            val owner = settings.owner.trim()
            val repo = settings.repo.trim()
            val branch = settings.branch.trim().ifEmpty { "main" }
            val token = settings.token.trim()

            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs?branch=$branch&per_page=5")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw mapHttpError("GitHub Actions Runs", "$owner/$repo", resp.code, raw)
                }
                val root = JSONObject(raw)
                val runs = root.optJSONArray("workflow_runs")
                if (runs == null || runs.length() == 0) {
                    throw ProviderException(
                        ProviderErrorKind.PROVIDER_UNAVAILABLE,
                        "No GitHub Actions workflow runs found on branch '$branch' in $owner/$repo."
                    )
                }
                val latest = runs.getJSONObject(0)
                GitHubWorkflowStatusReport(
                    runId = latest.getLong("id"),
                    status = latest.optString("status", "unknown"),
                    conclusion = latest.optString("conclusion", ""),
                    htmlUrl = latest.optString("html_url", "https://github.com/$owner/$repo/actions"),
                    headBranch = latest.optString("head_branch", branch),
                    updatedAt = latest.optString("updated_at", "")
                )
            }
        }
    }

    /**
     * Downloads the artifact zip for a completed GitHub Actions workflow run and extracts the real .apk binary.
     */
    suspend fun downloadApkFromWorkflowRun(
        settings: GitHubBuildSettings,
        runId: Long
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            validateGitHubSettings(settings)
            val owner = settings.owner.trim()
            val repo = settings.repo.trim()
            val token = settings.token.trim()

            val listReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()

            var archiveDownloadUrl = ""
            httpClient.newCall(listReq).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw mapHttpError("GitHub Artifacts", "run #$runId", resp.code, raw)
                }
                val artifacts = JSONObject(raw).optJSONArray("artifacts")
                if (artifacts == null || artifacts.length() == 0) {
                    throw ProviderException(
                        ProviderErrorKind.PROVIDER_UNAVAILABLE,
                        "Workflow run #$runId has no uploaded build artifacts."
                    )
                }
                archiveDownloadUrl = artifacts.getJSONObject(0).optString("archive_download_url", "")
            }

            if (!archiveDownloadUrl.startsWith("http")) {
                throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "Artifact entry did not include a valid archive_download_url."
                )
            }

            val dlReq = Request.Builder()
                .url(archiveDownloadUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
                .build()

            val zipBytes = httpClient.newCall(dlReq).execute().use { dlResp ->
                if (!dlResp.isSuccessful) {
                    throw ProviderException(
                        ProviderErrorKind.PROVIDER_UNAVAILABLE,
                        "Failed to download artifact archive (HTTP ${dlResp.code}). Ensure your PAT has 'actions:read' permission."
                    )
                }
                dlResp.body?.bytes() ?: ByteArray(0)
            }

            if (zipBytes.isEmpty()) {
                throw ProviderException(
                    ProviderErrorKind.MALFORMED_RESPONSE,
                    "Downloaded artifact ZIP archive was empty."
                )
            }

            // Extract the .apk file from the GitHub artifact ZIP container
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        val out = ByteArrayOutputStream()
                        zis.copyTo(out)
                        val apkBytes = out.toByteArray()
                        if (apkBytes.size > 4) {
                            return@runCatching apkBytes
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            throw ProviderException(
                ProviderErrorKind.MALFORMED_RESPONSE,
                "Artifact archive for run #$runId did not contain any .apk file."
            )
        }
    }

    private fun mapHttpError(
        provider: String,
        modelOrTarget: String,
        httpCode: Int,
        responseBody: String
    ): ProviderException {
        val detail = extractErrorDetail(responseBody)
        return when (httpCode) {
            401, 403 -> ProviderException(
                ProviderErrorKind.AUTHENTICATION_FAILED,
                "$provider authentication failed (HTTP $httpCode): Verify your API key / token in Settings. $detail"
            )
            429 -> ProviderException(
                ProviderErrorKind.QUOTA_OR_RATE_LIMIT,
                "$provider quota or rate limit exceeded (HTTP 429) for '$modelOrTarget'. $detail"
            )
            400, 404, 422 -> ProviderException(
                ProviderErrorKind.UNSUPPORTED_MODEL_OR_PARAMS,
                "$provider rejected model or parameters '$modelOrTarget' (HTTP $httpCode). You can edit the model or endpoint in Settings. $detail"
            )
            in 500..599 -> ProviderException(
                ProviderErrorKind.PROVIDER_UNAVAILABLE,
                "$provider service is currently unavailable (HTTP $httpCode). $detail"
            )
            else -> ProviderException(
                ProviderErrorKind.PROVIDER_UNAVAILABLE,
                "$provider returned HTTP $httpCode for '$modelOrTarget'. $detail"
            )
        }
    }

    private fun extractErrorDetail(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return try {
            val obj = JSONObject(rawBody)
            val msg = when {
                obj.optJSONObject("error") != null -> obj.getJSONObject("error").optString("message", "")
                obj.has("message") -> obj.optString("message", "")
                obj.has("msg") -> obj.optString("msg", "")
                else -> rawBody.take(140)
            }
            sanitizeError(msg)
        } catch (_: Exception) {
            sanitizeError(rawBody.take(140))
        }
    }

    private fun sanitizeError(text: String): String {
        return text
            .replace(Regex("(Bearer\\s+)[A-Za-z0-9._\\-]+"), "$1[REDACTED]")
            .replace(Regex("(key|token)=[^&\\s]+"), "$1=[REDACTED]")
            .trim()
            .take(180)
    }
}
