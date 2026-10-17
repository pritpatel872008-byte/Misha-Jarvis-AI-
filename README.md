# Misha Jarvis AI

Native Android assistant built with Kotlin and Jetpack Compose, using the existing Misha backend only through a configurable HTTPS API base URL.

## Requirements
- Android Studio with Android SDK 35+
- JDK 17

## Build
Open this repository in Android Studio and run the `app` configuration. The project includes a minimal Android build workflow; no API keys are committed.

## Current implementation scope
- Dark Jarvis-style home UI
- Android SpeechRecognizer input and TextToSpeech output
- Configurable HTTPS assistant API endpoint
- Local conversation history with persistent storage
- Permission-aware command handling and explicit confirmation before account-impacting actions

## Safety
SOS/call answering/device lock actions require real Android roles, user-granted permissions, and configured destinations. The app must never claim an emergency message or phone action succeeded unless Android/backend confirms it.

## API configuration
Set `MISHA_API_BASE_URL` in `local.properties` or Android Studio BuildConfig settings for your own backend. Do not commit API keys or tokens.
