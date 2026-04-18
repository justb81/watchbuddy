package com.justb81.watchbuddy.phone.service

import com.justb81.watchbuddy.BuildConfig
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.service.CompanionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests for the NSD TXT record assembly. This pins the on-the-wire
 * contract advertised by the phone so regressions (like the hard-coded
 * `version=1` placeholder seen in #259) are caught by CI.
 */
@DisplayName("CompanionService.buildTxtAttributes")
class CompanionServiceNsdTest {

    private fun config(
        backend: LlmBackend = LlmBackend.LITERT,
        qualityScore: Int = 70,
        modelVariant: LlmOrchestrator.ModelVariant? = LlmOrchestrator.ModelVariant.GEMMA4_E2B
    ) = LlmOrchestrator.LlmConfig(
        backend = backend,
        modelVariant = modelVariant,
        qualityScore = qualityScore
    )

    @Test
    fun `version carries the phone app BuildConfig versionName`() {
        val attrs = CompanionService.buildTxtAttributes(BuildConfig.VERSION_NAME, config())
        assertEquals(BuildConfig.VERSION_NAME, attrs["version"])
    }

    @Test
    fun `version is never the legacy hardcoded protocol placeholder`() {
        val attrs = CompanionService.buildTxtAttributes(BuildConfig.VERSION_NAME, config())
        val version = attrs["version"]
        // Guard against regressing to the old NSD_TXT_VERSION = "1" placeholder.
        assertTrue(
            version != null && version != "1",
            "version TXT field must carry the real versionName, got '$version'"
        )
    }

    @Test
    fun `version field is verbatim the provided semver string`() {
        val attrs = CompanionService.buildTxtAttributes("0.15.1", config())
        assertEquals("0.15.1", attrs["version"])
    }

    @Test
    fun `modelQuality equals llmConfig qualityScore as string`() {
        val attrs = CompanionService.buildTxtAttributes("0.15.1", config(qualityScore = 90))
        assertEquals("90", attrs["modelQuality"])
    }

    @Test
    fun `llmBackend equals llmConfig backend name for LITERT`() {
        val attrs = CompanionService.buildTxtAttributes(
            "0.15.1",
            config(backend = LlmBackend.LITERT)
        )
        assertEquals("LITERT", attrs["llmBackend"])
    }

    @Test
    fun `llmBackend equals llmConfig backend name for AICORE`() {
        val attrs = CompanionService.buildTxtAttributes(
            "0.15.1",
            config(backend = LlmBackend.AICORE, qualityScore = 150, modelVariant = null)
        )
        assertEquals("AICORE", attrs["llmBackend"])
    }

    @Test
    fun `llmBackend equals llmConfig backend name for NONE`() {
        val attrs = CompanionService.buildTxtAttributes(
            "0.15.1",
            config(backend = LlmBackend.NONE, qualityScore = 0, modelVariant = null)
        )
        assertEquals("NONE", attrs["llmBackend"])
    }

    @Test
    fun `no unexpected TXT keys are set`() {
        val attrs = CompanionService.buildTxtAttributes("0.15.1", config())
        assertEquals(
            setOf("version", "modelQuality", "llmBackend"),
            attrs.keys,
            "TXT contract must stay limited to version, modelQuality, llmBackend"
        )
    }
}
