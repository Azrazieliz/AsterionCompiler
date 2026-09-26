package com.asterion.compiler.repository

import android.content.Context
import com.asterion.compiler.app.AppSettings
import com.asterion.compiler.app.ThemePreference
import com.asterion.compiler.app.ThumbnailSize

interface SettingsRepository {
    fun load(): AppSettings

    fun save(settings: AppSettings)
}

class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private var settings = initial

    override fun load(): AppSettings = settings

    override fun save(settings: AppSettings) {
        this.settings = settings
    }
}

class SharedPreferencesSettingsRepository(context: Context) : SettingsRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun load(): AppSettings = AppSettings(
        themePreference = preferences.getString("theme", ThemePreference.DARK.name)
            ?.let { value -> ThemePreference.entries.firstOrNull { it.name == value } }
            ?: ThemePreference.DARK,
        thumbnailSize = preferences.getString("thumbnailSize", ThumbnailSize.STANDARD.name)
            ?.let { value -> ThumbnailSize.entries.firstOrNull { it.name == value } }
            ?: ThumbnailSize.STANDARD,
        maximumSessionHistory = preferences.getInt("maximumSessionHistory", 12).coerceIn(1, 50),
    )

    override fun save(settings: AppSettings) {
        preferences.edit()
            .putString("theme", settings.themePreference.name)
            .putString("thumbnailSize", settings.thumbnailSize.name)
            .putInt("maximumSessionHistory", settings.maximumSessionHistory.coerceIn(1, 50))
            .apply()
    }

    private companion object {
        const val PreferencesName = "asterion_compiler_settings"
    }
}