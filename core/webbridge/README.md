# core:webbridge — JS Injection Bridge

Owns all JavaScript injection logic for Shellify: TranslateBridge (in-page translation), NotificationBridge (System WebView notification interception), ContentProtectionBridge, and the JsInjector contract.

`ContentProtectionBridge` builds the local document-start policy script. It observes top-level `img`
and `video` elements, coordinates the bundled face detector when available, and retains the
text/visual classifier as a fallback. Regional overlays are owned by the engine asset
`smart-detector.js`; the bridge still controls per-app strictness, grayscale, hover reveal, startup
blur, and hostname whitelists. It never uploads page content or fetches a model at runtime.
