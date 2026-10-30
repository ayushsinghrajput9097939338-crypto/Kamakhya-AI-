package com.example.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class AppLaunchCandidate(
    val appLabel: String,
    val packageName: String
)

data class ContactDialCandidate(
    val displayName: String,
    val phoneNumber: String
)

sealed class PhoneActionRequest(val actionTypeLabel: String, val requiresConfirmation: Boolean) {
    data class OpenApp(val query: String) : PhoneActionRequest("Open Installed App", false)
    data class AdjustBrightness(val targetPercent: Int?, val deltaPercent: Int?) :
        PhoneActionRequest("Adjust Screen Brightness", false)
    data class AdjustMediaVolume(val targetPercent: Int?, val deltaSteps: Int?) :
        PhoneActionRequest("Adjust Media Volume", false)
    data class PrepareDialCall(val contactOrNumberQuery: String) :
        PhoneActionRequest("Open Phone Dialer", true)
    data class SetAlarm(
        val hour24: Int,
        val minute: Int,
        val label: String,
        val rawTimeDescription: String
    ) : PhoneActionRequest("Set System Alarm", true)
    data class OpenSystemSetting(val settingCategory: String, val intentAction: String) :
        PhoneActionRequest("Open System Settings", false)
}

sealed class PhoneActionOutcome {
    data class Success(
        val actionName: String,
        val userMessage: String,
        val logSummary: String
    ) : PhoneActionOutcome()

    data class ConfirmationRequired(
        val actionName: String,
        val promptMessage: String,
        val pendingRequest: PhoneActionRequest
    ) : PhoneActionOutcome()

    data class AmbiguousAppSelection(
        val query: String,
        val candidates: List<AppLaunchCandidate>
    ) : PhoneActionOutcome()

    data class AmbiguousContactSelection(
        val query: String,
        val candidates: List<ContactDialCandidate>
    ) : PhoneActionOutcome()

    data class WriteSettingsPermissionRequired(
        val requestedBrightnessPercent: Int,
        val explanation: String
    ) : PhoneActionOutcome()

    data class ContactsPermissionOrManualDialRequired(
        val contactQuery: String,
        val explanation: String
    ) : PhoneActionOutcome()

    data class FailedOrUnavailable(
        val actionName: String,
        val userMessage: String,
        val logSummary: String
    ) : PhoneActionOutcome()
}

sealed class RoutedAssistantIntent {
    data class PhoneAction(val request: PhoneActionRequest) : RoutedAssistantIntent()
    data class GenerateImage(val prompt: String) : RoutedAssistantIntent()
    data class GenerateVideo(val prompt: String) : RoutedAssistantIntent()
    data class CreateWebsite(val prompt: String) : RoutedAssistantIntent()
    data class CreateAndroidApp(val prompt: String) : RoutedAssistantIntent()
    data class OrdinaryConversation(val message: String) : RoutedAssistantIntent()
}

/**
 * Central intent classifier and phone action router implementing sections 5 and 7.1–7.7.
 */
class PhoneActionRouter(private val context: Context) {

    /**
     * Parses English, Hindi, and Hinglish user utterances into structured intents.
     */
    fun classifyIntent(rawInput: String): RoutedAssistantIntent {
        val text = normalizeForIntent(rawInput)
        val lower = text.lowercase(Locale.ROOT)

        // Brightness commands (English, Hinglish and Hindi).
        if (containsAny(lower, "brightness", "roshni", "chamak", "रोशनी", "चमक", "ब्राइटनेस")) {
            val pctMatch = Regex("(\\d{1,3})\\s*(%|percent|pratishat|प्रतिशत)?").find(lower)
            val pct = pctMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            return when {
                pct != null -> RoutedAssistantIntent.PhoneAction(
                    PhoneActionRequest.AdjustBrightness(targetPercent = pct.coerceIn(0, 100), deltaPercent = null)
                )
                containsAny(lower, "badha", "increase", "up", "zyada", "बढ़ाओ", "बढ़ा", "ज्यादा") ->
                    RoutedAssistantIntent.PhoneAction(PhoneActionRequest.AdjustBrightness(null, +20))
                containsAny(lower, "kam", "decrease", "down", "dim", "कम", "घटाओ") ->
                    RoutedAssistantIntent.PhoneAction(PhoneActionRequest.AdjustBrightness(null, -20))
                else -> RoutedAssistantIntent.PhoneAction(
                    PhoneActionRequest.OpenSystemSetting("Display & Brightness Settings", Settings.ACTION_DISPLAY_SETTINGS)
                )
            }
        }

        // Media volume commands.
        if (containsAny(lower, "volume", "aawaz", "awaaz", "sound level", "आवाज", "आवाज़", "वॉल्यूम", "ध्वनि")) {
            val pctMatch = Regex("(\\d{1,3})\\s*(%|percent|pratishat|प्रतिशत)?").find(lower)
            val pct = pctMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            return when {
                pct != null -> RoutedAssistantIntent.PhoneAction(
                    PhoneActionRequest.AdjustMediaVolume(targetPercent = pct.coerceIn(0, 100), deltaSteps = null)
                )
                containsAny(lower, "mute", "band", "silent", "बंद", "म्यूट") ->
                    RoutedAssistantIntent.PhoneAction(PhoneActionRequest.AdjustMediaVolume(0, null))
                containsAny(lower, "badha", "increase", "up", "louder", "tez", "बढ़ाओ", "तेज") ->
                    RoutedAssistantIntent.PhoneAction(PhoneActionRequest.AdjustMediaVolume(null, +2))
                containsAny(lower, "kam", "decrease", "down", "lower", "dheere", "कम", "धीमा") ->
                    RoutedAssistantIntent.PhoneAction(PhoneActionRequest.AdjustMediaVolume(null, -2))
                else -> RoutedAssistantIntent.PhoneAction(
                    PhoneActionRequest.OpenSystemSetting("Sound & Volume Settings", Settings.ACTION_SOUND_SETTINGS)
                )
            }
        }

        // Alarm commands.
        if (containsAny(lower, "alarm", "अलार्म", "घड़ी में अलार्म")) {
            val parsedAlarm = parseAlarmTimeFromText(text)
            if (parsedAlarm != null) return RoutedAssistantIntent.PhoneAction(parsedAlarm)
        }

        // Dialer commands.
        val callMatch = parseCallTarget(text)
        if (callMatch != null) {
            return RoutedAssistantIntent.PhoneAction(PhoneActionRequest.PrepareDialCall(callMatch))
        }

        // System settings commands.
        val settingReq = parseSystemSettingsCommand(lower)
        if (settingReq != null) return RoutedAssistantIntent.PhoneAction(settingReq)

        // Open an installed app. Punctuation is stripped before matching, so
        // ASR output such as "YouTube open karo." is accepted.
        val appTarget = parseOpenAppTarget(text)
        if (appTarget != null) return RoutedAssistantIntent.PhoneAction(PhoneActionRequest.OpenApp(appTarget))

        // Website project intent.
        if (containsAny(lower, "website", "web page", "webpage", "html", "वेबसाइट", "वेब पेज") &&
            containsAny(lower, "create", "build", "make", "generate", "banao", "bana do", "बनाओ", "बना दो", "बनाइए")) {
            return RoutedAssistantIntent.CreateWebsite(rawInput.trim())
        }

        // Android app project intent.
        if (containsAny(lower, "android app", "mobile app", "apk", "एंड्रॉयड ऐप", "एंड्रॉइड ऐप", "एपीके") &&
            containsAny(lower, "create", "build", "make", "generate", "banao", "bana do", "बनाओ", "बना दो", "बनाइए")) {
            return RoutedAssistantIntent.CreateAndroidApp(rawInput.trim())
        }

        // Video generation remains routed to the configured provider flow.
        if (containsAny(lower, "video", "clip", "animation", "वीडियो", "क्लिप", "एनिमेशन") &&
            containsAny(lower, "create", "make", "generate", "banao", "bana do", "बनाओ", "बना दो", "बनाइए")) {
            return RoutedAssistantIntent.GenerateVideo(rawInput.trim())
        }

        // Image generation supports both Roman Hinglish and Devanagari Hindi.
        if (containsAny(lower, "image", "photo", "picture", "illustration", "tasveer", "tasvir", "drawing", "इमेज", "तस्वीर", "तसवीर", "फोटो", "चित्र", "ड्रॉइंग") &&
            containsAny(lower, "create", "make", "generate", "draw", "banao", "bana do", "बनाओ", "बना दो", "बनाएं", "बनाइए", "तैयार करो")) {
            return RoutedAssistantIntent.GenerateImage(extractImagePrompt(text))
        }

        return RoutedAssistantIntent.OrdinaryConversation(rawInput.trim())
    }

    private fun normalizeForIntent(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val convertedDigits = buildString(normalized.length) {
            normalized.forEach { ch ->
                append(
                    when (ch) {
                        in '०'..'९' -> ('0'.code + (ch.code - '०'.code)).toChar()
                        in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                        else -> ch
                    }
                )
            }
        }
        return stripTrailingPunctuation(convertedDigits)
    }

    private fun stripTrailingPunctuation(value: String): String {
        return value.trim().trimEnd { ch ->
            ch.isWhitespace() || ch in charArrayOf('.', '!', '?', ',', ':', ';', '।', '॥', '…', '"', '\'')
        }.trim()
    }

    private fun containsAny(value: String, vararg needles: String): Boolean =
        needles.any { value.contains(it, ignoreCase = true) }

    private fun extractImagePrompt(value: String): String {
        val clean = stripTrailingPunctuation(value).trim()
        val patterns = listOf(
            Regex("^(?:please\\s+)?(?:make|create|generate|draw)\\s+(?:me\\s+)?(?:an?\\s+)?(?:image|photo|picture|illustration)\\s+(?:of\\s+)?(.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:एक\\s+)?(.+?)\\s+(?:की|का|के)\\s+(?:इमेज|तस्वीर|तसवीर|फोटो|चित्र)\\s+(?:बनाओ|बना\\s+दो|बनाएं|बनाइए)$"),
            Regex("^(?:ek\\s+)?(.+?)\\s+(?:ki|ka|ke)\\s+(?:image|photo|tasveer|tasvir)\\s+(?:banao|bana\\s+do)$", RegexOption.IGNORE_CASE),
            Regex("^(?:draw|चित्र बनाओ|ड्रॉ करो)\\s+(.+)$", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(clean)
            val prompt = match?.groupValues?.getOrNull(1)?.trim()
            if (!prompt.isNullOrBlank()) return prompt
        }
        return clean
    }

    /**
     * Executes a phone action off the main thread where appropriate.
     * If [confirmedByUser] is false and the action is consequential, returns [PhoneActionOutcome.ConfirmationRequired].
     */
    suspend fun executePhoneAction(
        request: PhoneActionRequest,
        confirmedByUser: Boolean = false
    ): PhoneActionOutcome = withContext(Dispatchers.IO) {
        if (request.requiresConfirmation && !confirmedByUser) {
            val previewText = when (request) {
                is PhoneActionRequest.PrepareDialCall ->
                    "Prepare to open the phone dialer for '${request.contactOrNumberQuery}'? (Kamakhya AI will only open the dialer; it will never place the call automatically.)"
                is PhoneActionRequest.SetAlarm ->
                    "Set a system alarm for ${request.rawTimeDescription} (${String.format(Locale.US, "%02d:%02d", request.hour24, request.minute)})?"
                else -> "Proceed with ${request.actionTypeLabel}?"
            }
            return@withContext PhoneActionOutcome.ConfirmationRequired(
                actionName = request.actionTypeLabel,
                promptMessage = previewText,
                pendingRequest = request
            )
        }

        when (request) {
            is PhoneActionRequest.OpenApp -> executeOpenApp(request.query)
            is PhoneActionRequest.AdjustBrightness -> executeAdjustBrightness(request.targetPercent, request.deltaPercent)
            is PhoneActionRequest.AdjustMediaVolume -> executeAdjustVolume(request.targetPercent, request.deltaSteps)
            is PhoneActionRequest.PrepareDialCall -> executeOpenDialer(request.contactOrNumberQuery)
            is PhoneActionRequest.SetAlarm -> executeSetAlarm(request)
            is PhoneActionRequest.OpenSystemSetting -> executeOpenSystemSetting(request.settingCategory, request.intentAction)
        }
    }

    // =========================================================================
    // 7.1 Open an installed app
    // =========================================================================
    private fun executeOpenApp(rawQuery: String): PhoneActionOutcome {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Open Installed App",
                userMessage = "App name was empty. Please specify which app to open.",
                logSummary = "FAILED: Empty app query"
            )
        }

        // Built-in direct support for Android Settings
        if (query.equals("settings", ignoreCase = true) || query.equals("setting", ignoreCase = true)) {
            return executeOpenSystemSetting("System Settings", Settings.ACTION_SETTINGS)
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val candidates = resolveInfos.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.loadLabel(pm)?.toString()?.trim() ?: pkg
            AppLaunchCandidate(appLabel = label, packageName = pkg)
        }.distinctBy { it.packageName }

        val exactMatches = candidates.filter {
            it.appLabel.equals(query, ignoreCase = true) ||
                it.packageName.equals(query, ignoreCase = true)
        }
        if (exactMatches.size == 1) {
            return launchPackageNow(exactMatches.first())
        }

        val partialMatches = candidates.filter {
            it.appLabel.contains(query, ignoreCase = true) ||
                it.packageName.substringAfterLast('.').contains(query, ignoreCase = true)
        }

        return when {
            partialMatches.isEmpty() -> {
                // Check well-known package names as fallback via getLaunchIntentForPackage
                val knownPkg = WELL_KNOWN_PACKAGES[query.lowercase(Locale.US)]
                if (knownPkg != null && pm.getLaunchIntentForPackage(knownPkg) != null) {
                    launchPackageNow(AppLaunchCandidate(query.replaceFirstChar { it.uppercase() }, knownPkg))
                } else {
                    PhoneActionOutcome.FailedOrUnavailable(
                        actionName = "Open Installed App",
                        userMessage = "App '$query' is not installed on this device or has no launchable screen.",
                        logSummary = "UNAVAILABLE: App not installed"
                    )
                }
            }
            partialMatches.size == 1 -> launchPackageNow(partialMatches.first())
            else -> PhoneActionOutcome.AmbiguousAppSelection(
                query = query,
                candidates = partialMatches.take(8)
            )
        }
    }

    fun launchPackageNow(candidate: AppLaunchCandidate): PhoneActionOutcome {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(candidate.packageName)
                ?: return PhoneActionOutcome.FailedOrUnavailable(
                    actionName = "Open Installed App",
                    userMessage = "Android could not resolve a launch intent for '${candidate.appLabel}'.",
                    logSummary = "FAILED: No launch intent"
                )
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            PhoneActionOutcome.Success(
                actionName = "Open Installed App",
                userMessage = "Launched '${candidate.appLabel}'.",
                logSummary = "SUCCESS: Launched installed app"
            )
        } catch (e: Exception) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Open Installed App",
                userMessage = "Android rejected launching '${candidate.appLabel}': ${e.javaClass.simpleName}.",
                logSummary = "FAILED: ${e.javaClass.simpleName}"
            )
        }
    }

    // =========================================================================
    // 7.2 Adjust screen brightness
    // =========================================================================
    private fun executeAdjustBrightness(targetPercent: Int?, deltaPercent: Int?): PhoneActionOutcome {
        val resolver = context.contentResolver
        val currentRaw = try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) {
            128
        }
        val currentPercent = ((currentRaw / 255f) * 100f).roundToInt().coerceIn(0, 100)
        val desiredPercent = (targetPercent ?: (currentPercent + (deltaPercent ?: 0))).coerceIn(0, 100)

        if (!Settings.System.canWrite(context)) {
            return PhoneActionOutcome.WriteSettingsPermissionRequired(
                requestedBrightnessPercent = desiredPercent,
                explanation = "Android requires the 'Modify system settings' (WRITE_SETTINGS) permission before an app can adjust system screen brightness. Tap 'Open Permission Screen' below to grant it manually."
            )
        }

        val desiredRaw = ((desiredPercent / 100f) * 255f).roundToInt().coerceIn(1, 255)
        return try {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val wrote = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, desiredRaw)
            val verifiedRaw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (!wrote || verifiedRaw < 0) {
                PhoneActionOutcome.FailedOrUnavailable(
                    actionName = "Adjust Screen Brightness",
                    userMessage = "Device rejected the system brightness modification.",
                    logSummary = "FAILED: System brightness did not update"
                )
            } else {
                val verifiedPercent = ((verifiedRaw / 255f) * 100f).roundToInt().coerceIn(0, 100)
                PhoneActionOutcome.Success(
                    actionName = "Adjust Screen Brightness",
                    userMessage = "System screen brightness changed to $verifiedPercent% (system value $verifiedRaw/255).",
                    logSummary = "SUCCESS: Brightness set to $verifiedPercent%"
                )
            }
        } catch (e: Exception) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Adjust Screen Brightness",
                userMessage = "Could not adjust screen brightness on this device (${e.javaClass.simpleName}).",
                logSummary = "FAILED: ${e.javaClass.simpleName}"
            )
        }
    }

    fun openWriteSettingsPermissionPage(): Boolean {
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // 7.3 Adjust media volume
    // =========================================================================
    private fun executeAdjustVolume(targetPercent: Int?, deltaSteps: Int?): PhoneActionOutcome {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Adjust Media Volume",
                userMessage = "AudioManager service is unavailable on this device.",
                logSummary = "UNAVAILABLE: AudioManager missing"
            )

        return try {
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager.getStreamMinVolume(stream)
            } else {
                0
            }
            val currentVol = audioManager.getStreamVolume(stream)

            val desiredIndex = if (targetPercent != null) {
                val clampedPct = targetPercent.coerceIn(0, 100)
                val range = (maxVol - minVol).coerceAtLeast(1)
                (minVol + ((clampedPct / 100.0) * range).roundToInt()).coerceIn(minVol, maxVol)
            } else {
                (currentVol + (deltaSteps ?: 0)).coerceIn(minVol, maxVol)
            }

            audioManager.setStreamVolume(stream, desiredIndex, AudioManager.FLAG_SHOW_UI)
            val verifiedVol = audioManager.getStreamVolume(stream)
            val verifiedPct = (((verifiedVol - minVol).toDouble() / (maxVol - minVol).coerceAtLeast(1)) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)

            PhoneActionOutcome.Success(
                actionName = "Adjust Media Volume",
                userMessage = "Media volume confirmed at $verifiedPct% (level $verifiedVol of $maxVol).",
                logSummary = "SUCCESS: Media volume confirmed at $verifiedPct% ($verifiedVol/$maxVol)"
            )
        } catch (se: SecurityException) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Adjust Media Volume",
                userMessage = "Do Not Disturb policy prevented changing media volume (${se.javaClass.simpleName}).",
                logSummary = "FAILED: Blocked by DND policy"
            )
        } catch (e: Exception) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Adjust Media Volume",
                userMessage = "Failed to adjust media volume (${e.javaClass.simpleName}).",
                logSummary = "FAILED: ${e.javaClass.simpleName}"
            )
        }
    }

    // =========================================================================
    // 7.4 Open the dialer for a phone call
    // =========================================================================
    private fun executeOpenDialer(contactOrNumberQuery: String): PhoneActionOutcome {
        val query = contactOrNumberQuery.trim()
        if (query.isEmpty()) {
            return PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Open Phone Dialer",
                userMessage = "Please specify a contact name or phone number to open in the dialer.",
                logSummary = "FAILED: Empty dial query"
            )
        }

        // Check if input is already a direct phone number
        val digitsOnly = query.filter { it.isDigit() || it == '+' }
        if (digitsOnly.count { it.isDigit() } >= 3 && query.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }) {
            return launchDialerWithNumber(displayLabel = digitsOnly, phoneNumber = digitsOnly)
        }

        // Otherwise, we need READ_CONTACTS permission to resolve the contact name
        val hasContactsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasContactsPermission) {
            return PhoneActionOutcome.ContactsPermissionOrManualDialRequired(
                contactQuery = query,
                explanation = "To look up '$query' in your contacts, grant Contacts permission, or enter the phone number manually below to open the Android dialer."
            )
        }

        val matches = queryMatchingContacts(query)
        return when {
            matches.isEmpty() -> PhoneActionOutcome.ContactsPermissionOrManualDialRequired(
                contactQuery = query,
                explanation = "No contact matching '$query' was found on this device. You can enter a phone number manually below to open the dialer."
            )
            matches.size == 1 -> {
                val chosen = matches.first()
                launchDialerWithNumber(displayLabel = chosen.displayName, phoneNumber = chosen.phoneNumber)
            }
            else -> PhoneActionOutcome.AmbiguousContactSelection(
                query = query,
                candidates = matches.take(8)
            )
        }
    }

    fun launchDialerWithNumber(displayLabel: String, phoneNumber: String): PhoneActionOutcome {
        val sanitizedNumber = phoneNumber.filter { it.isDigit() || it in setOf('+', '*', '#') }
        if (sanitizedNumber.isEmpty()) {
            return PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Open Phone Dialer",
                userMessage = "Invalid phone number.",
                logSummary = "FAILED: Invalid phone number"
            )
        }
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(sanitizedNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (dialIntent.resolveActivity(context.packageManager) == null) {
                return PhoneActionOutcome.FailedOrUnavailable(
                    actionName = "Open Phone Dialer",
                    userMessage = "No phone dialer application is available on this device.",
                    logSummary = "UNAVAILABLE: No dialer app"
                )
            }
            context.startActivity(dialIntent)
            PhoneActionOutcome.Success(
                actionName = "Open Phone Dialer",
                userMessage = "Opened the phone dialer for $displayLabel ($sanitizedNumber). Please press the Call button inside the dialer to place the call.",
                logSummary = "SUCCESS: Dialer opened (awaiting user call action)"
            )
        } catch (e: Exception) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Open Phone Dialer",
                userMessage = "Could not open phone dialer (${e.javaClass.simpleName}).",
                logSummary = "FAILED: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun queryMatchingContacts(nameQuery: String): List<ContactDialCandidate> {
        val results = mutableListOf<ContactDialCandidate>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && results.size < 12) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val num = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                    if (name.isNotBlank() && num.isNotBlank()) {
                        val normalized = num.replace(" ", "").replace("-", "")
                        if (results.none { it.displayName == name && it.phoneNumber.replace(" ", "") == normalized }) {
                            results.add(ContactDialCandidate(name, num))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Handled by caller
        }
        return results
    }

    // =========================================================================
    // 7.5 Set an alarm
    // =========================================================================
    private fun executeSetAlarm(request: PhoneActionRequest.SetAlarm): PhoneActionOutcome {
        val h = request.hour24.coerceIn(0, 23)
        val m = request.minute.coerceIn(0, 59)
        val tzName = TimeZone.getDefault().id

        return try {
            val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, request.label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (alarmIntent.resolveActivity(context.packageManager) == null) {
                return PhoneActionOutcome.FailedOrUnavailable(
                    actionName = "Set System Alarm",
                    userMessage = "No compatible Clock/Alarm application was found on this device to handle ACTION_SET_ALARM.",
                    logSummary = "UNAVAILABLE: No alarm app handler"
                )
            }
            context.startActivity(alarmIntent)
            val formattedTime = String.format(Locale.US, "%02d:%02d", h, m)
            PhoneActionOutcome.Success(
                actionName = "Set System Alarm",
                userMessage = "Sent alarm request for $formattedTime ($tzName) to the system Clock app. Please verify/confirm inside the Clock app if prompted.",
                logSummary = "SUCCESS: Handed off alarm ($formattedTime) to system Clock app"
            )
        } catch (e: Exception) {
            PhoneActionOutcome.FailedOrUnavailable(
                actionName = "Set System Alarm",
                userMessage = "Failed to launch system alarm app (${e.javaClass.simpleName}).",
                logSummary = "FAILED: ${e.javaClass.simpleName}"
            )
        }
    }

    // =========================================================================
    // 7.6 Open relevant system settings
    // =========================================================================
    private fun executeOpenSystemSetting(categoryLabel: String, intentAction: String): PhoneActionOutcome {
        return try {
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            PhoneActionOutcome.Success(
                actionName = "Open System Settings",
                userMessage = "Opened $categoryLabel. Because Android restricts third-party apps from changing protected system switches silently, please complete any toggle manually on the settings screen.",
                logSummary = "SUCCESS: Opened $categoryLabel"
            )
        } catch (_: Exception) {
            // Fallback to top-level Settings
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                PhoneActionOutcome.Success(
                    actionName = "Open System Settings",
                    userMessage = "Opened main Android Settings (specific page '$categoryLabel' was unavailable on this OS build).",
                    logSummary = "SUCCESS: Opened main Android Settings fallback"
                )
            } catch (e: Exception) {
                PhoneActionOutcome.FailedOrUnavailable(
                    actionName = "Open System Settings",
                    userMessage = "Could not open Android Settings (${e.javaClass.simpleName}).",
                    logSummary = "FAILED: ${e.javaClass.simpleName}"
                )
            }
        }
    }

    // =========================================================================
    // Parsing Helpers for English / Hindi / Hinglish
    // =========================================================================
    fun parseAlarmTimeFromText(text: String): PhoneActionRequest.SetAlarm? {
        val lower = normalizeForIntent(text).lowercase(Locale.ROOT)
        // Match English/Hinglish/Hindi time forms such as 6:30, 6 baje, 6 बजे, 6 am.
        val colonMatch = Regex("(\\d{1,2}):(\\d{2})\\s*(am|pm)?").find(lower)
        val bajeOrAmPmMatch = Regex("(\\d{1,2})\\s*(baje|bajey|बजे|बजें|am|pm|o'clock)").find(lower)

        var hour: Int
        var minute = 0
        var explicitMeridiem: String? = null

        if (colonMatch != null) {
            hour = colonMatch.groupValues[1].toIntOrNull() ?: return null
            minute = colonMatch.groupValues[2].toIntOrNull() ?: 0
            explicitMeridiem = colonMatch.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }
        } else if (bajeOrAmPmMatch != null) {
            hour = bajeOrAmPmMatch.groupValues[1].toIntOrNull() ?: return null
            val suffix = bajeOrAmPmMatch.groupValues[2]
            if (suffix == "am" || suffix == "pm") {
                explicitMeridiem = suffix
            }
        } else {
            return null
        }

        if (hour !in 0..23 || minute !in 0..59) return null

        val isMorningHinglish = containsAny(lower, "subah", "morning", "savere", "सुबह", "सवेरे")
        val isEveningHinglish = containsAny(lower, "shaam", "evening", "dopahar", "afternoon", "शाम", "दोपहर")
        val isNightHinglish = containsAny(lower, "raat", "night", "रात")

        when {
            explicitMeridiem == "pm" && hour in 1..11 -> hour += 12
            explicitMeridiem == "am" && hour == 12 -> hour = 0
            explicitMeridiem == null -> {
                if (isEveningHinglish && hour in 1..11) {
                    hour += 12
                } else if (isNightHinglish && hour in 5..11) {
                    hour += 12
                } else if (isMorningHinglish && hour == 12) {
                    hour = 0
                }
            }
        }

        val cal = Calendar.getInstance(TimeZone.getDefault())
        val dayDescriptor = if (containsAny(lower, "kal", "tomorrow", "कल")) "Tomorrow" else "Next occurrence"
        val timeDesc = String.format(
            Locale.US,
            "%s at %02d:%02d (%s)",
            dayDescriptor,
            hour,
            minute,
            cal.timeZone.id
        )

        return PhoneActionRequest.SetAlarm(
            hour24 = hour,
            minute = minute,
            label = "Kamakhya AI Alarm",
            rawTimeDescription = timeDesc
        )
    }

    private fun parseCallTarget(text: String): String? {
        val trimmed = stripTrailingPunctuation(text)
        val englishMatch = Regex("^(?:please\\s+)?(?:call|dial|phone)\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (englishMatch != null) {
            return stripTrailingPunctuation(englishMatch.groupValues[1])
                .replace(Regex("\\s+(?:now|please|karo|kar do)$", RegexOption.IGNORE_CASE), "")
                .trim().takeIf { it.isNotEmpty() }
        }
        val hinglishMatch = Regex("^(.+?)\\s+ko\\s+(?:call|phone|dial)\\s+(?:karo|kar do|laga do|lagao)$", RegexOption.IGNORE_CASE).find(trimmed)
        if (hinglishMatch != null) return hinglishMatch.groupValues[1].trim().takeIf { it.isNotEmpty() }
        val hindiMatch = Regex("^(.+?)\\s+को\\s+(?:कॉल|फोन|डायल)\\s+(?:करो|कर दो|लगा दो|लगाओ)$").find(trimmed)
        return hindiMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseSystemSettingsCommand(lower: String): PhoneActionRequest.OpenSystemSetting? {
        val hasSettingTrigger = lower.contains("setting") || lower.contains("kholo") ||
            lower.contains("open") || lower.contains("turn on") || lower.contains("turn off") ||
            lower.contains("toggle") || lower.contains("chalu") || lower.contains("band")
        if (!hasSettingTrigger) return null

        return when {
            lower.contains("wifi") || lower.contains("wi-fi") -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                PhoneActionRequest.OpenSystemSetting("Wi-Fi Settings", action)
            }
            lower.contains("bluetooth") ->
                PhoneActionRequest.OpenSystemSetting("Bluetooth Settings", Settings.ACTION_BLUETOOTH_SETTINGS)
            lower.contains("location") || lower.contains("gps") ->
                PhoneActionRequest.OpenSystemSetting("Location Settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            lower.contains("airplane") || lower.contains("flight mode") ->
                PhoneActionRequest.OpenSystemSetting("Airplane Mode Settings", Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            lower.contains("battery") ->
                PhoneActionRequest.OpenSystemSetting("Battery Saver Settings", Settings.ACTION_BATTERY_SAVER_SETTINGS)
            lower.contains("display") ->
                PhoneActionRequest.OpenSystemSetting("Display Settings", Settings.ACTION_DISPLAY_SETTINGS)
            lower.contains("sound") ->
                PhoneActionRequest.OpenSystemSetting("Sound Settings", Settings.ACTION_SOUND_SETTINGS)
            lower == "open settings" || lower == "settings kholo" || lower == "setting kholo" ->
                PhoneActionRequest.OpenSystemSetting("System Settings", Settings.ACTION_SETTINGS)
            else -> null
        }
    }

    private fun parseOpenAppTarget(text: String): String? {
        val trimmed = stripTrailingPunctuation(text)
        val target = Regex("^(?:please\\s+)?(?:open|launch)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(trimmed)?.groupValues?.getOrNull(1)
            ?: Regex("^(.+?)\\s+(?:app\\s+)?(?:kholo|khol do|chalu karo|open karo|खोलो|खोल दो|चालू करो|ओपन करो)$", RegexOption.IGNORE_CASE)
                .find(trimmed)?.groupValues?.getOrNull(1)
            ?: return null

        val cleanTarget = stripTrailingPunctuation(target)
            .replace(Regex("\\s+app$", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleanTarget.isEmpty() || cleanTarget.equals("website", ignoreCase = true)) return null

        return when (cleanTarget.lowercase(Locale.ROOT)) {
            "यूट्यूब", "यू ट्यूब" -> "YouTube"
            "व्हाट्सएप", "व्हाट्सऐप" -> "WhatsApp"
            "क्रोम" -> "Chrome"
            "जीमेल" -> "Gmail"
            "कैमरा" -> "Camera"
            "सेटिंग", "सेटिंग्स" -> "Settings"
            "मैप्स", "गूगल मैप्स" -> "Google Maps"
            else -> cleanTarget
        }
    }

    companion object {
        private val WELL_KNOWN_PACKAGES = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "whatsapp" to "com.whatsapp",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.google.android.deskclock",
            "camera" to "com.android.camera",
            "files" to "com.google.android.apps.nbu.files",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending"
        )
    }
}
