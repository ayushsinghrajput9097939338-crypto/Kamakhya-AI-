package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class GeminiAuthMode {
    DIRECT_API_KEY,
    EPHEMERAL_TOKEN
}

data class GeminiLiveSettings(
    val modelId: String = DEFAULT_MODEL_ID,
    val wsEndpoint: String = DEFAULT_WS_ENDPOINT,
    val authMode: GeminiAuthMode = GeminiAuthMode.DIRECT_API_KEY,
    val apiKey: String = "",
    val ephemeralToken: String = ""
) {
    val activeCredential: String
        get() = if (authMode == GeminiAuthMode.EPHEMERAL_TOKEN) ephemeralToken.trim() else apiKey.trim()

    companion object {
        const val DEFAULT_MODEL_ID = "gemini-3.8-live"
        const val DEFAULT_WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

data class SenseNovaSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = ""
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://token.sensenova.ai/v1/images/generations"
        const val DEFAULT_MODEL = "sensenova-u1.5-lite"
    }
}

data class HelioRouteSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
    val videoEndpointSupported: Boolean = false
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://helioroute.com/v1/chat/completions"
        const val DEFAULT_MODEL = "kie-vid-grok-imagine"
    }
}

data class XKiroSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val chatPath: String = DEFAULT_CHAT_PATH,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = ""
) {
    val fullEndpoint: String
        get() = baseUrl.trimEnd('/') + "/" + chatPath.trimStart('/')

    companion object {
        const val DEFAULT_BASE_URL = "https://api.xkiro.com/v1"
        const val DEFAULT_CHAT_PATH = "/chat/completions"
        const val DEFAULT_MODEL = "qwen/qwen3.8-max:free"
    }
}

data class GitHubBuildSettings(
    val owner: String = "",
    val repo: String = "",
    val branch: String = DEFAULT_BRANCH,
    val token: String = "",
    val workflowFile: String = DEFAULT_WORKFLOW
) {
    companion object {
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_WORKFLOW = "main.yml"
    }
}

/**
 * Encrypts sensitive API keys and personal access tokens at rest using an AES-256-GCM key
 * backed by the Android Keystore system. Never logs credentials or includes them in exceptions.
 */
class KeystoreVault(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var fallbackTestSecret: SecretKey? = null

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (_: Exception) {
            // In local JVM unit tests (Robolectric) where AndroidKeyStore hardware provider is absent
            synchronized(this) {
                fallbackTestSecret ?: KeyGenerator.getInstance("AES").apply { init(256) }
                    .generateKey().also { fallbackTestSecret = it }
            }
        }
    }

    fun isHardwareKeystoreAvailable(): Boolean {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun putEncrypted(key: String, plaintext: String) {
        if (plaintext.isEmpty()) {
            prefs.edit().remove(key).remove("${key}_iv").apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString("${key}_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
        } catch (_: Exception) {
            // Do not log sensitive values
        }
    }

    private fun getDecrypted(key: String): String {
        val encodedCipher = prefs.getString(key, null) ?: return ""
        val encodedIv = prefs.getString("${key}_iv", null) ?: return ""
        return try {
            val cipherBytes = Base64.decode(encodedCipher, Base64.NO_WRAP)
            val ivBytes = Base64.decode(encodedIv, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, ivBytes)
            )
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    // Gemini Live
    fun loadGeminiSettings(): GeminiLiveSettings {
        val modeStr = prefs.getString("gemini_auth_mode", GeminiAuthMode.DIRECT_API_KEY.name)
        val authMode = try {
            GeminiAuthMode.valueOf(modeStr ?: GeminiAuthMode.DIRECT_API_KEY.name)
        } catch (_: Exception) {
            GeminiAuthMode.DIRECT_API_KEY
        }
        return GeminiLiveSettings(
            modelId = prefs.getString("gemini_model", GeminiLiveSettings.DEFAULT_MODEL_ID)
                ?.takeIf { it.isNotBlank() } ?: GeminiLiveSettings.DEFAULT_MODEL_ID,
            wsEndpoint = prefs.getString("gemini_endpoint", GeminiLiveSettings.DEFAULT_WS_ENDPOINT)
                ?.takeIf { it.isNotBlank() } ?: GeminiLiveSettings.DEFAULT_WS_ENDPOINT,
            authMode = authMode,
            apiKey = getDecrypted("gemini_api_key"),
            ephemeralToken = getDecrypted("gemini_ephemeral_token")
        )
    }

    fun saveGeminiSettings(settings: GeminiLiveSettings) {
        prefs.edit()
            .putString("gemini_model", settings.modelId.trim().ifEmpty { GeminiLiveSettings.DEFAULT_MODEL_ID })
            .putString("gemini_endpoint", settings.wsEndpoint.trim().ifEmpty { GeminiLiveSettings.DEFAULT_WS_ENDPOINT })
            .putString("gemini_auth_mode", settings.authMode.name)
            .apply()
        putEncrypted("gemini_api_key", settings.apiKey.trim())
        putEncrypted("gemini_ephemeral_token", settings.ephemeralToken.trim())
    }

    fun clearGeminiCredentials() {
        putEncrypted("gemini_api_key", "")
        putEncrypted("gemini_ephemeral_token", "")
    }

    // SenseNova
    fun loadSenseNovaSettings(): SenseNovaSettings {
        return SenseNovaSettings(
            endpoint = prefs.getString("sensenova_endpoint", SenseNovaSettings.DEFAULT_ENDPOINT)
                ?.takeIf { it.isNotBlank() } ?: SenseNovaSettings.DEFAULT_ENDPOINT,
            model = prefs.getString("sensenova_model", SenseNovaSettings.DEFAULT_MODEL)
                ?.takeIf { it.isNotBlank() } ?: SenseNovaSettings.DEFAULT_MODEL,
            apiKey = getDecrypted("sensenova_api_key")
        )
    }

    fun saveSenseNovaSettings(settings: SenseNovaSettings) {
        prefs.edit()
            .putString("sensenova_endpoint", settings.endpoint.trim().ifEmpty { SenseNovaSettings.DEFAULT_ENDPOINT })
            .putString("sensenova_model", settings.model.trim().ifEmpty { SenseNovaSettings.DEFAULT_MODEL })
            .apply()
        putEncrypted("sensenova_api_key", settings.apiKey.trim())
    }

    fun clearSenseNovaCredentials() {
        putEncrypted("sensenova_api_key", "")
    }

    // HelioRoute
    fun loadHelioRouteSettings(): HelioRouteSettings {
        return HelioRouteSettings(
            endpoint = prefs.getString("helioroute_endpoint", HelioRouteSettings.DEFAULT_ENDPOINT)
                ?.takeIf { it.isNotBlank() } ?: HelioRouteSettings.DEFAULT_ENDPOINT,
            model = prefs.getString("helioroute_model", HelioRouteSettings.DEFAULT_MODEL)
                ?.takeIf { it.isNotBlank() } ?: HelioRouteSettings.DEFAULT_MODEL,
            apiKey = getDecrypted("helioroute_api_key"),
            videoEndpointSupported = prefs.getBoolean("helioroute_video_supported", false)
        )
    }

    fun saveHelioRouteSettings(settings: HelioRouteSettings) {
        prefs.edit()
            .putString("helioroute_endpoint", settings.endpoint.trim().ifEmpty { HelioRouteSettings.DEFAULT_ENDPOINT })
            .putString("helioroute_model", settings.model.trim().ifEmpty { HelioRouteSettings.DEFAULT_MODEL })
            .putBoolean("helioroute_video_supported", settings.videoEndpointSupported)
            .apply()
        putEncrypted("helioroute_api_key", settings.apiKey.trim())
    }

    fun clearHelioRouteCredentials() {
        putEncrypted("helioroute_api_key", "")
    }

    // xKiro
    fun loadXKiroSettings(): XKiroSettings {
        return XKiroSettings(
            baseUrl = prefs.getString("xkiro_base_url", XKiroSettings.DEFAULT_BASE_URL)
                ?.takeIf { it.isNotBlank() } ?: XKiroSettings.DEFAULT_BASE_URL,
            chatPath = prefs.getString("xkiro_chat_path", XKiroSettings.DEFAULT_CHAT_PATH)
                ?.takeIf { it.isNotBlank() } ?: XKiroSettings.DEFAULT_CHAT_PATH,
            model = prefs.getString("xkiro_model", XKiroSettings.DEFAULT_MODEL)
                ?.takeIf { it.isNotBlank() } ?: XKiroSettings.DEFAULT_MODEL,
            apiKey = getDecrypted("xkiro_api_key")
        )
    }

    fun saveXKiroSettings(settings: XKiroSettings) {
        prefs.edit()
            .putString("xkiro_base_url", settings.baseUrl.trim().ifEmpty { XKiroSettings.DEFAULT_BASE_URL })
            .putString("xkiro_chat_path", settings.chatPath.trim().ifEmpty { XKiroSettings.DEFAULT_CHAT_PATH })
            .putString("xkiro_model", settings.model.trim().ifEmpty { XKiroSettings.DEFAULT_MODEL })
            .apply()
        putEncrypted("xkiro_api_key", settings.apiKey.trim())
    }

    fun clearXKiroCredentials() {
        putEncrypted("xkiro_api_key", "")
    }

    // GitHub Build Integration
    fun loadGitHubSettings(): GitHubBuildSettings {
        val storedWorkflow = prefs.getString("github_workflow", null)?.trim()
        // Earlier builds defaulted to android-build.yml, but this project ships main.yml.
        val workflowFile = when {
            storedWorkflow.isNullOrBlank() -> GitHubBuildSettings.DEFAULT_WORKFLOW
            storedWorkflow == "android-build.yml" -> GitHubBuildSettings.DEFAULT_WORKFLOW
            else -> storedWorkflow
        }
        return GitHubBuildSettings(
            owner = prefs.getString("github_owner", "") ?: "",
            repo = prefs.getString("github_repo", "") ?: "",
            branch = prefs.getString("github_branch", GitHubBuildSettings.DEFAULT_BRANCH)
                ?.takeIf { it.isNotBlank() } ?: GitHubBuildSettings.DEFAULT_BRANCH,
            token = getDecrypted("github_pat_token"),
            workflowFile = workflowFile
        )
    }

    fun saveGitHubSettings(settings: GitHubBuildSettings) {
        prefs.edit()
            .putString("github_owner", settings.owner.trim())
            .putString("github_repo", settings.repo.trim())
            .putString("github_branch", settings.branch.trim().ifEmpty { GitHubBuildSettings.DEFAULT_BRANCH })
            .putString("github_workflow", settings.workflowFile.trim().ifEmpty { GitHubBuildSettings.DEFAULT_WORKFLOW })
            .apply()
        putEncrypted("github_pat_token", settings.token.trim())
    }

    fun clearGitHubCredentials() {
        putEncrypted("github_pat_token", "")
    }

    fun clearAllCredentials() {
        clearGeminiCredentials()
        clearSenseNovaCredentials()
        clearHelioRouteCredentials()
        clearXKiroCredentials()
        clearGitHubCredentials()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "kamakhya_ai_master_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFS_NAME = "kamakhya_secure_vault"
    }
}
