package io.shellify.app.domain.model

/**
 * Per-app, on-device media protection policy.
 *
 * The settings are intentionally data-only so the same policy can be passed to either browser
 * engine without coupling the domain module to WebView, GeckoView, or a model runtime.
 */
data class ContentProtectionSettings(
    val enabled: Boolean = true,
    val blurImages: Boolean = true,
    val blurVideos: Boolean = true,
    val blurAmount: Int = DEFAULT_BLUR_AMOUNT,
    val grayscale: Boolean = true,
    val strictness: Float = DEFAULT_STRICTNESS,
    val blurMale: Boolean = false,
    val blurFemale: Boolean = true,
    val startupBlur: Boolean = true,
    val hoverReveal: Boolean = true,
    val whitelist: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_BLUR_AMOUNT = 20
        const val DEFAULT_STRICTNESS = 0.5f
        const val MAX_BLUR_AMOUNT = 80
        const val MIN_STRICTNESS = 0f
        const val MAX_STRICTNESS = 1f
    }
}
