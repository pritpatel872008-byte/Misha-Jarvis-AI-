package ai.misha.jarvis

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Prefer an installed female-labelled voice when the engine exposes one; never downloads or clones a voice. */
object MishaVoiceProfile {
    fun configure(engine: TextToSpeech, locale: Locale) {
        val voices = try { engine.voices.orEmpty().filter { it.locale.language == locale.language } } catch (_: Exception) { emptyList() }
        val preferred = voices.filter { it.name.contains("female", ignoreCase = true) }
            .maxByOrNull { it.quality }
        try {
            if (preferred != null) engine.voice = preferred else engine.language = locale
            // A slightly slower, lower-energy delivery is closer to a calm, composed assistant.
            engine.setSpeechRate(0.88f)
            engine.setPitch(0.94f)
        } catch (_: Exception) {
            try { engine.language = locale } catch (_: Exception) { }
        }
    }
}
