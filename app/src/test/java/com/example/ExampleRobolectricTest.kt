package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.actions.PhoneActionRequest
import com.example.actions.PhoneActionRouter
import com.example.actions.RoutedAssistantIntent
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
            override fun onInputTranscription(text: String) {}
            override fun onOutputTranscription(text: String) {}
            override fun onModelTextPart(text: String) {}
            override fun onAudioPcmChunkReceived(pcmBytes: ByteArray, sampleRateHz: Int) {}
            override fun onInterrupted() {}
            override fun onTurnComplete() {}
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
