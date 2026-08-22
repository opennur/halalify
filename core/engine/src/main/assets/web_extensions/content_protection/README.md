# Content Protection Extension

The built-in GeckoView extension runs its media protection content script at `document_start`.
It requests the active per-app policy through the native message delegate and accepts live policy
updates through the `shellifyContentProtectionUpdate` document event.

The extension includes a local Human/TensorFlow.js detector. It runs only BlazeFace and FaceRes for
female/male face classification; the bundled MoveNet weights are retained for a future independent
body pass and are not loaded during face inference. A face box is blurred only when its gender
matches the active policy. The detector renders fixed `backdrop-filter` overlays over those boxes
instead of applying a filter to the whole media element. Videos are sampled repeatedly and overlays
are repositioned on scroll and resize.

The model runtime and weights are bundled in this directory. No media or model request leaves the
device. If inference is unavailable, metadata and maximum-strictness checks can still protect media,
but a detector error does not permanently leave every image or video under a full-media blur.

## Third-Party Models

- `human.js`: `@vladmandic/human` 3.3.6, MIT, Copyright Vladimir Mandic.
- `blazeface`: MediaPipe BlazeFace, converted by Human; Apache-2.0 source model.
- `movenet-multipose`: TensorFlow MoveNet MultiPose, converted by Human; Apache-2.0 source model.
- `faceres`: HSE FaceRes, converted by Human; Apache-2.0 source project.

The Human model credits and upstream links are documented at
<https://github.com/vladmandic/human/wiki/Models>. The Human runtime and model package licenses
are included in `THIRD_PARTY_LICENSES.md`.
