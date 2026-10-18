# Hey Misha wake-word model

The Android runtime is integrated with openWakeWord and will only enable always-on detection when the exact custom classifier exists at:

`app/src/main/assets/hey_misha.onnx`

This file must be an openWakeWord-compatible custom classifier trained for the phrase **“Hey Misha”**, with input shape `[1, 16, 96]` and output shape `[1, 1]`. The Android runtime's shared preprocessing/embedding assets are supplied by the openwakeword-android dependency.

No stock model is substituted: “Hey Jarvis”, “Alexa”, or another keyword model would not honestly implement the requested phrase. Until the trained classifier is added, the service reports that the wake-word model is not installed and the bubble/tap-to-talk remains available.

Model training resources:
- Android runtime and custom-model requirements: https://github.com/msnilsen/openwakeword-android
- openWakeWord custom training notebook: https://github.com/dscripka/openWakeWord

Before release, evaluate false activations and missed detections on the target Galaxy M30s, background noise, speech playback, and battery use. Continuous microphone capture is only active after the user grants microphone permission and enables the assistant service.
