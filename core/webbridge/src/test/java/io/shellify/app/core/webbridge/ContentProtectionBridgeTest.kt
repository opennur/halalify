package io.shellify.app.core.webbridge

import io.shellify.app.domain.model.ContentProtectionSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentProtectionBridgeTest {

    @Test
    fun `document start script contains policy and media hooks`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(
            ContentProtectionSettings(whitelist = listOf("example.com"))
        )

        assertTrue(script.contains("__shellifyContentProtection"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("img,video"))
        assertTrue(script.contains("shellifyContentProtectionUpdate"))
        assertTrue(script.contains("example.com"))
        assertTrue(script.contains("blurImages"))
    }

    @Test
    fun `document start script classifies female metadata across media sources`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("element.currentSrc"))
        assertTrue(script.contains("querySelectorAll('source')"))
        assertTrue(script.contains("closest('picture')"))
        assertTrue(script.contains("femme|girl|female|woman|women|lady"))
        assertTrue(script.contains("gender.female && currentConfig.blurFemale"))
    }

    @Test
    fun `document start script delegates regional detection and hover reveal`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("__shellifySmartDetection"))
        assertTrue(script.contains("smart.apply(element, currentConfig, result)"))
        assertTrue(script.contains("DATA_REGIONAL"))
        assertTrue(script.contains("smart.setRevealed"))
    }

    @Test
    fun `document start script only installs one top-level bridge`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("window.top !== window.self"))
        assertTrue(script.contains("if (window[KEY])"))
        assertTrue(script.contains("window[KEY].update(initialConfig)"))
        assertTrue(script.contains("regionalFallbackListenersInstalled"))
    }

    @Test
    fun `regional detection keeps a mask fallback when regional blur is unavailable`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("applyRegionalFallback(element, result)"))
        assertTrue(script.contains("DATA_REGIONAL_MASK"))
        assertTrue(script.contains("DATA_REGIONAL_FALLBACK"))
        assertTrue(script.contains("background', 'rgb(0, 0, 0)"))
        assertTrue(script.contains("setRegionalFallbackRevealed"))
        assertFalse(script.contains("if (hasRegions) clearProtectedStyle(element)"))
    }

    @Test
    fun `pending videos are not given a full filter`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("if (mediaKind(element) !== 'video')"))
        assertTrue(script.contains("element.setAttribute(DATA_PENDING, '1')"))
        assertTrue(script.contains("if (currentConfig.startupBlur) setPendingProtection(element)"))
        assertFalse(script.contains("if (currentConfig.startupBlur) setProtectedStyle(element, true)"))
        assertFalse(script.contains("if (loading && currentConfig.startupBlur) setProtectedStyle(element, true)"))
    }

    @Test
    fun `maximum strictness keeps unknown media protected after loading`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(
            ContentProtectionSettings(strictness = 1f)
        )

        assertTrue(script.contains("currentConfig.strictness >= 0.999"))
        assertTrue(script.contains("blur: isMaximumStrictness()"))
    }

    @Test
    fun `detector errors do not force every media element into full blur`() {
        val script = ContentProtectionBridge.buildDocumentStartScript(ContentProtectionSettings())

        assertTrue(script.contains("metadataBlocked || unknownAtStrictness"))
        assertFalse(script.contains("metadataBlocked || result.error || unknownAtStrictness"))
    }

    @Test
    fun `update script is safe when page has not loaded the bridge`() {
        val script = ContentProtectionBridge.buildUpdateScript(
            ContentProtectionSettings(enabled = false)
        )

        assertTrue(script.contains("api.update"))
        assertTrue(script.contains("shellifyContentProtectionUpdate"))
        assertTrue(script.contains("\"enabled\":false"))
        assertFalse(script.contains("__SHELLIFY_CONFIG__"))
    }
}
