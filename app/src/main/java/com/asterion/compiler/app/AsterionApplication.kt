package com.asterion.compiler.app

import android.app.Application
import com.asterion.compiler.repository.CompilerSessionRepository
import com.asterion.compiler.repository.SettingsRepository
import com.asterion.compiler.repository.SharedPreferencesCompilerSessionRepository
import com.asterion.compiler.repository.SharedPreferencesSettingsRepository
import com.asterion.compiler.service.AndroidPromptClipboard
import com.asterion.compiler.service.AndroidWorksheetImageLoader
import com.asterion.compiler.service.AndroidWorksheetSourceReader
import com.asterion.compiler.service.DeterministicPassRefinementService
import com.asterion.compiler.service.PromptClipboard
import com.asterion.compiler.service.WorksheetImageLoader
import com.asterion.compiler.service.WorksheetSourceReader
import com.asterion.compiler.service.JsonFilePromptRefinementMemory
import com.asterion.compiler.service.PromptRefinementMemory

class AsterionApplication : Application() {
    val container: AsterionAppContainer by lazy { AsterionAppContainer(this) }
}

class AsterionAppContainer(application: Application) {
    val sessionRepository: CompilerSessionRepository = SharedPreferencesCompilerSessionRepository(application)
    val settingsRepository: SettingsRepository = SharedPreferencesSettingsRepository(application)
    val sourceReader: WorksheetSourceReader = AndroidWorksheetSourceReader(application.contentResolver)
    val imageLoader: WorksheetImageLoader = AndroidWorksheetImageLoader(application.contentResolver)
    val clipboard: PromptClipboard = AndroidPromptClipboard(application)
    val refinementMemory: PromptRefinementMemory = JsonFilePromptRefinementMemory(
        application.getFileStreamPath("prompt_refinement_rules.json"),
    )
    val refinementService = DeterministicPassRefinementService(refinementMemory = refinementMemory)
}