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

object ThemePreferences {
    private const val PREFS_NAME = "family_radar_theme_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"

    private val _themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val savedName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        val mode = try {
            ThemeMode.valueOf(savedName ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
        _themeModeFlow.value = mode
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
