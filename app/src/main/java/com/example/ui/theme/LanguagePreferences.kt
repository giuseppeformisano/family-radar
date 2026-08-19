package com.example.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(val tag: String?, val label: String) {
    /** Segue la lingua del dispositivo: nessun override. */
    SYSTEM(null, "Sistema"),
    ITALIAN("it", "Italiano"),
    ENGLISH("en", "English")
}

/**
 * Lingua dell'app, selezionabile e persistita. Gemello di [ThemePreferences].
 *
 * ## Perché non l'API di sistema
 * Il per-app language nativo (`LocaleManager`) esiste solo da Android 13, e il
 * backport AndroidX richiede `appcompat` con `AppCompatActivity`: qui si usa
 * `ComponentActivity` e appcompat non è tra le dipendenze. Si applica quindi la
 * locale a mano, con `createConfigurationContext`.
 *
 * ## Perché tutto passa da [localizedContext]
 * App e notifiche devono parlare la stessa lingua. Le notifiche però vengono
 * costruite fuori da Compose, dal `Context` dell'applicazione, che non sa nulla
 * della scelta fatta in Impostazioni: senza passare da qui l'interfaccia
 * risulterebbe in inglese e le notifiche in italiano.
 *
 * Per lo stesso motivo [readStored] legge le SharedPreferences direttamente e non
 * lo StateFlow: una push FCM può arrivare su un processo appena creato, dove
 * [init] non è ancora stato chiamato e il flow vale ancora SYSTEM.
 */
object LanguagePreferences {

    private const val PREFS_NAME = "family_radar_language_prefs"
    private const val KEY_LANGUAGE = "app_language"

    private val _languageFlow = MutableStateFlow(AppLanguage.SYSTEM)
    val languageFlow: StateFlow<AppLanguage> = _languageFlow.asStateFlow()

    fun init(context: Context) {
        _languageFlow.value = readStored(context)
    }

    /** Lettura sincrona, sicura anche prima di [init]. */
    fun readStored(context: Context): AppLanguage {
        val stored = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, AppLanguage.SYSTEM.name)
        }.getOrNull()
        return runCatching { AppLanguage.valueOf(stored ?: AppLanguage.SYSTEM.name) }
            .getOrDefault(AppLanguage.SYSTEM)
    }

    /**
     * Salva la scelta. Chi chiama deve poi ricreare l'Activity: la locale viene
     * applicata in `attachBaseContext`, che gira una sola volta per istanza.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language.name)
                .apply()
        }
        _languageFlow.value = language
    }

    /**
     * Restituisce un Context le cui risorse risolvono nella lingua scelta.
     *
     * Con [AppLanguage.SYSTEM] restituisce il context originale senza toccarlo,
     * così Android continua a fare il suo lavoro (incluse le lingue che non
     * abbiamo tradotto, che ricadono su `values/`).
     */
    fun localizedContext(base: Context): Context {
        val tag = readStored(base).tag ?: return base
        return runCatching {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            base.createConfigurationContext(config)
        }.getOrDefault(base)
    }
}

/**
 * Context localizzato per i Composable che devono leggere stringhe fuori dal
 * normale flusso di `stringResource` (per esempio per comporre un testo da
 * passare a un servizio).
 *
 * Nota: l'interfaccia in generale non ne ha bisogno, perché la locale è già
 * applicata in `MainActivity.attachBaseContext` e quindi vale per tutto l'albero.
 */
@Composable
fun rememberLocalizedContext(): Context {
    val context = LocalContext.current
    return remember(context) { LanguagePreferences.localizedContext(context) }
}
