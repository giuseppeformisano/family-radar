package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val title: String, val description: String) {
    SYSTEM("Predefinito di sistema", "Segue le impostazioni del dispositivo"),
    LIGHT("Chiaro", "Sfondo chiaro per ambienti illuminati"),
    DARK("Scuro", "Sfondo scuro ad alto contrasto e riposante")
}

/** Colore della mappa, indipendente dal tema dell'app. */
enum class MapColorMode(val title: String) {
    THEME("Come il tema"),
    LIGHT("Sempre chiara"),
    DARK("Sempre scura")
}

object ThemePreferences {
    private const val PREFS_NAME = "family_radar_theme_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"

    private const val KEY_MAP_COLOR_MODE = "key_map_color_mode"

    private val _themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    private val _mapColorModeFlow = MutableStateFlow(MapColorMode.THEME)
    val mapColorModeFlow: StateFlow<MapColorMode> = _mapColorModeFlow.asStateFlow()

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val savedName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        val mode = try {
            ThemeMode.valueOf(savedName ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
        _themeModeFlow.value = mode

        _mapColorModeFlow.value = try {
            MapColorMode.valueOf(prefs.getString(KEY_MAP_COLOR_MODE, MapColorMode.THEME.name) ?: MapColorMode.THEME.name)
        } catch (_: Exception) {
            MapColorMode.THEME
        }
    }

    fun setMapColorMode(context: Context, mode: MapColorMode) {
        getPrefs(context).edit().putString(KEY_MAP_COLOR_MODE, mode.name).apply()
        _mapColorModeFlow.value = mode
    }

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = getPrefs(context)
        val savedName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(savedName ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val prefs = getPrefs(context)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeModeFlow.value = mode
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
