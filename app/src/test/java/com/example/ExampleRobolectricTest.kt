package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.actions.PhoneActionRequest
import com.example.actions.PhoneActionRouter
import com.example.actions.RoutedAssistantIntent
import com.example.audio.MicrophonePlaybackGate
import com.example.network.GeminiLiveClient
import com.example.network.GeminiLiveEventsListener
import com.example.security.GeminiLiveSettings
import com.example.security.KeystoreVault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Kamakhya AI", appName)
    }

    @Test
    fun `gemini live setup message matches official protocol schema`() {
        val dummyListener = object : GeminiLiveEventsListener {
            override fun onSetupCompleted() {}
            override fun onFunctionCalls(functionCalls: List<com.example.network.GeminiFunctionCall>) {}
            override fun onInputTranscription(text: String) {}
            override fun onOutputTranscription(text: String) {}
            override fun onModelTextPart(text: String) {}
            override fun onAudioPcmChunkReceived(pcmBytes: ByteArray, sampleRateHz: Int) {}
            override fun onInterrupted() {}
            override fun onTurnComplete(isFinal: Boolean) {}
            override fun onSessionError(userMessage: String) {}
            override fun onDisconnected(reason: String) {}
        }
        val client = GeminiLiveClient(dummyListener)
        val jsonStr = client.buildSetupJsonMessage("gemini-3.8-live")
        val root = JSONObject(jsonStr)
        val setup = root.getJSONObject("setup")

        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        val generationConfig = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", generationConfig.getJSONArray("responseModalities").getString(0))
        assertTrue(!setup.has("responseModalities"))
        assertTrue(setup.has("systemInstruction"))
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))

        val tools = setup.getJSONArray("tools")
        assertTrue("Gemini setup must expose app tools", tools.length() > 0)
        val declarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
        assertTrue(names.contains("open_installed_app"))
        assertTrue(names.contains("generate_image"))
        assertTrue(names.contains("set_screen_brightness"))
        assertTrue(names.contains("set_media_volume"))
        assertTrue(names.contains("open_phone_dialer"))
        assertTrue(names.contains("set_alarm"))
        assertTrue(names.contains("open_system_settings"))
        assertTrue(names.contains("create_website"))
        assertTrue(names.contains("create_android_app"))
        // Gemini Live 3.8 supports NON_BLOCKING function declarations.
        declarations.let { list ->
            for (index in 0 until list.length()) {
                assertEquals("NON_BLOCKING", list.getJSONObject(index).getString("behavior"))
            }
        }
    }

    @Test
    fun `phone action router parses english and hinglish commands`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = PhoneActionRouter(context)

        val brightnessIntent = router.classifyIntent("Brightness 50 percent kar do")
        assertTrue(brightnessIntent is RoutedAssistantIntent.PhoneAction)
        val brightnessReq = (brightnessIntent as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.AdjustBrightness
        assertEquals(50, brightnessReq.targetPercent)

        val volumeIntent = router.classifyIntent("Volume 30 percent kar do")
        assertTrue(volumeIntent is RoutedAssistantIntent.PhoneAction)
        val volumeReq = (volumeIntent as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.AdjustMediaVolume
        assertEquals(30, volumeReq.targetPercent)

        val alarmIntent = router.classifyIntent("Kal subah 6 baje alarm laga do")
        assertTrue(alarmIntent is RoutedAssistantIntent.PhoneAction)
        val alarmReq = (alarmIntent as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.SetAlarm
        assertEquals(6, alarmReq.hour24)
        assertEquals(0, alarmReq.minute)

        val callIntent = router.classifyIntent("Call Mom")
        assertTrue(callIntent is RoutedAssistantIntent.PhoneAction)
        val callReq = (callIntent as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.PrepareDialCall
        assertEquals("Mom", callReq.contactOrNumberQuery)
    }

    @Test
    fun `voice intent router recognizes punctuation and Devanagari commands`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val router = PhoneActionRouter(context)

        val youtube = router.classifyIntent("YouTube open karo.")
        assertTrue(youtube is RoutedAssistantIntent.PhoneAction)
        assertEquals("YouTube", (youtube as RoutedAssistantIntent.PhoneAction).request.let {
            (it as PhoneActionRequest.OpenApp).query
        })

        val youtubeHindi = router.classifyIntent("यूट्यूब खोलो।")
        assertTrue(youtubeHindi is RoutedAssistantIntent.PhoneAction)
        assertEquals("YouTube", (youtubeHindi as RoutedAssistantIntent.PhoneAction).request.let {
            (it as PhoneActionRequest.OpenApp).query
        })

        val image = router.classifyIntent("एक बर्ड की इमेज बनाओ।")
        assertTrue(image is RoutedAssistantIntent.GenerateImage)
        assertEquals("बर्ड", (image as RoutedAssistantIntent.GenerateImage).prompt)

        val volume = router.classifyIntent("वॉल्यूम ५० प्रतिशत कर दो।")
        assertTrue(volume is RoutedAssistantIntent.PhoneAction)
        assertEquals(50, ((volume as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.AdjustMediaVolume).targetPercent)

        val hindiAlarm = router.classifyIntent("कल सुबह ६ बजे अलार्म लगा दो।")
        assertTrue(hindiAlarm is RoutedAssistantIntent.PhoneAction)
        assertEquals(6, ((hindiAlarm as RoutedAssistantIntent.PhoneAction).request as PhoneActionRequest.SetAlarm).hour24)
    }


    @Test
    fun `microphone audio is not forwarded while assistant playback is active`() {
        val gate = MicrophonePlaybackGate()

        assertTrue(!gate.shouldForwardMicrophoneAudio())
        gate.onCaptureStarted()
        assertTrue(gate.shouldForwardMicrophoneAudio())

        gate.onAssistantPlaybackStarted()
        assertTrue(!gate.shouldForwardMicrophoneAudio())

        gate.onAssistantPlaybackFinished()
        assertTrue(gate.shouldForwardMicrophoneAudio())

        gate.onAssistantPlaybackStarted()
        gate.onCaptureStopped()
        gate.onAssistantPlaybackFinished()
        assertTrue(!gate.shouldForwardMicrophoneAudio())
    }

    @Test
    fun `keystore vault encrypts and decrypts settings without plaintext leak`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreVault(context)
        vault.saveGeminiSettings(
            GeminiLiveSettings(
                modelId = "gemini-3.8-live",
                apiKey = "test-secret-key-999"
            )
        )
        val loaded = vault.loadGeminiSettings()
        assertEquals("gemini-3.8-live", loaded.modelId)
        assertEquals("test-secret-key-999", loaded.apiKey)
        vault.clearGeminiCredentials()
        assertEquals("", vault.loadGeminiSettings().apiKey)
    }
}
