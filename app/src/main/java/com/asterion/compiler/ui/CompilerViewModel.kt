package com.asterion.compiler.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.asterion.compiler.app.AppDestination
import com.asterion.compiler.app.AppSettings
import com.asterion.compiler.app.AsterionAppContainer
import com.asterion.compiler.app.PersistedCompilerSession
import com.asterion.compiler.app.toPersisted
import com.asterion.compiler.app.toPromptPass
import com.asterion.compiler.app.toWorksheetSelection
import com.asterion.compiler.compiler.CompilationStatus
import com.asterion.compiler.compiler.DeterministicCompilationPipeline
import com.asterion.compiler.compiler.PromptCompilation
import com.asterion.compiler.compiler.WorksheetCompilationRequest
import com.asterion.compiler.compiler.WorksheetCompilationResult
import com.asterion.compiler.parser.ParseRequest
import com.asterion.compiler.parser.PngWorksheetSource
import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.profiles.CompilationStrategy
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.repository.CompilerSessionRepository
import com.asterion.compiler.repository.InMemoryCompilerSessionRepository
import com.asterion.compiler.repository.InMemorySettingsRepository
import com.asterion.compiler.repository.SettingsRepository
import com.asterion.compiler.service.DeterministicPassRefinementService
import com.asterion.compiler.service.PassRefinementContext
import com.asterion.compiler.service.PassRefinementResult
import com.asterion.compiler.service.PromptClipboard
import com.asterion.compiler.service.RecordingPromptClipboard
import com.asterion.compiler.service.PromptRefinementRule
import com.asterion.compiler.service.RefinementRuleType
import com.asterion.compiler.validation.ValidationReport
import com.asterion.compiler.diagnostics.CompilationLogEntry
import com.asterion.compiler.diagnostics.toPlainText
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetSelection

data class CompilerUiState(
    val destination: AppDestination = AppDestination.STUDIO,
    val settings: AppSettings = AppSettings(),
    val selection: WorksheetSelection = WorksheetSelection(),
    val selectedStrategyId: String? = null,
    val selectedCheckpointId: String? = null,
    val validationReport: ValidationReport = ValidationReport(),
    val validationAttempted: Boolean = false,
    val currentPassIndex: Int = 0,
    val compilation: PromptCompilation? = null,
    val restoredPromptPasses: List<PromptPass> = emptyList(),
    val commentsByPass: Map<PromptPassType, String> = emptyMap(),
    val passHistory: Map<PromptPassType, List<PromptPass>> = emptyMap(),
    val compilationDiagnostics: List<CompilationLogEntry> = emptyList(),
    val notice: String? = null,
) {
    val selectedStrategy: CompilationStrategy?
        get() {
            val strategy = selectedStrategyId?.let(EmbeddedProfiles::strategyById) ?: return null
            val checkpoint = selectedCheckpointId?.let(EmbeddedProfiles::checkpointById) ?: return strategy
            return strategy.copy(checkpointProfile = checkpoint)
        }

    val promptPasses: List<PromptPass>
        get() = compilation?.passes ?: restoredPromptPasses

    val currentPass: PromptPass?
        get() = promptPasses.getOrNull(currentPassIndex)

    val currentPassComment: String
        get() = currentPass?.let { commentsByPass[it.type] }.orEmpty()

    val canDisplayPasses: Boolean
        get() = currentPass != null && (compilation?.status == CompilationStatus.GENERATED || restoredPromptPasses.isNotEmpty())

    val isRestoredSession: Boolean
        get() = compilation == null && restoredPromptPasses.isNotEmpty()

    val compilationStatus: String
        get() = when {
            compilation?.status == CompilationStatus.GENERATED -> "Generated"
            compilation?.status == CompilationStatus.BLOCKED -> "Blocked"
            isRestoredSession -> "Restored"
            validationAttempted -> "Validation blocked"
            else -> "Awaiting compilation"
        }
}

class CompilerViewModel(
    private val sessionRepository: CompilerSessionRepository = InMemoryCompilerSessionRepository(),
    private val settingsRepository: SettingsRepository = InMemorySettingsRepository(),
    private val clipboard: PromptClipboard = RecordingPromptClipboard(),
    private val refinementService: DeterministicPassRefinementService = DeterministicPassRefinementService(),
    private val compilationPipeline: DeterministicCompilationPipeline = DeterministicCompilationPipeline(),
) : ViewModel() {
    var uiState by mutableStateOf<CompilerUiState>(restoreState())
        private set

    fun navigate(destination: AppDestination) {
        uiState = uiState.copy(destination = destination)
    }

    fun addCharacterSheets(sheets: List<WorksheetReference>) {
        val availableSlots = 3 - uiState.selection.characterSheets.size
        val newSheets = sheets
            .filterNot { candidate -> uiState.selection.characterSheets.any { it.sourceIdentifier == candidate.sourceIdentifier } }
            .take(availableSlots)
        val ignoredCount = sheets.size - newSheets.size
        resetForSelection(
            uiState.selection.copy(characterSheets = uiState.selection.characterSheets + newSheets),
            notice = if (ignoredCount > 0) "Only three Character Master Sheets can be selected." else null,
        )
    }

    fun removeCharacterSheet(sheetId: String) {
        resetForSelection(uiState.selection.copy(characterSheets = uiState.selection.characterSheets.filterNot { it.id == sheetId }))
    }

    fun replaceThemeSheet(sheet: WorksheetReference) {
        resetForSelection(uiState.selection.copy(themeSheet = sheet))
    }

    fun clearThemeSheet() {
        resetForSelection(uiState.selection.copy(themeSheet = null))
    }

    fun replaceArtStyleSheet(sheet: WorksheetReference) {
        resetForSelection(uiState.selection.copy(artStyleSheet = sheet))
    }

    fun clearArtStyleSheet() {
        resetForSelection(uiState.selection.copy(artStyleSheet = null))
    }

    fun selectStrategy(strategyId: String) {
        val strategy = EmbeddedProfiles.strategyById(strategyId)
        updateSession(
            clearGeneratedState(
                uiState.copy(
                    selectedStrategyId = strategy?.id,
                    selectedCheckpointId = uiState.selectedCheckpointId ?: strategy?.checkpointProfile?.id,
                ),
            ),
        )
    }

    fun listRefinementRules(): List<PromptRefinementRule> = compilationPipeline.refinementRules()

    fun saveRefinementRule(rule: PromptRefinementRule) {
        compilationPipeline.saveRefinementRule(rule)
        updateSession(uiState.copy(notice = "Refinement rule saved."))
    }

    fun setRefinementRuleEnabled(id: String, enabled: Boolean) {
        compilationPipeline.setRefinementRuleEnabled(id, enabled)
        updateSession(uiState.copy(notice = "Refinement rule ${if (enabled) "enabled" else "disabled"}."))
    }

    fun deleteRefinementRule(id: String) {
        compilationPipeline.deleteRefinementRule(id)
        updateSession(uiState.copy(notice = "Refinement rule deleted."))
    }

    fun reorderRefinementRules(idsInPriorityOrder: List<String>) {
        compilationPipeline.reorderRefinementRules(idsInPriorityOrder)
        updateSession(uiState.copy(notice = "Refinement rule priority updated."))
    }

    fun exportRefinementRules(): String = compilationPipeline.exportRefinementRules()

    fun importRefinementRules(serialized: String) {
        runCatching { compilationPipeline.importRefinementRules(serialized) }
            .onSuccess { updateSession(uiState.copy(notice = "Refinement rules imported.")) }
            .onFailure { updateSession(uiState.copy(notice = "Unable to import refinement rules.")) }
    }

    fun selectCheckpoint(checkpointId: String) {
        val checkpoint = EmbeddedProfiles.checkpointById(checkpointId)
        if (checkpoint == null) {
            updateSession(uiState.copy(notice = "The selected checkpoint is unavailable."))
            return
        }
        val automaticStrategy = EmbeddedProfiles.defaultStrategyForCheckpoint(checkpoint.id)
        updateSession(
            clearGeneratedState(
                uiState.copy(
                    selectedCheckpointId = checkpoint.id,
                    selectedStrategyId = automaticStrategy?.id ?: uiState.selectedStrategyId,
                ),
            ),
        )
    }

    fun compileWorksheetSet(sourcesByIdentifier: Map<String, ByteArray>) {
        val selectedStrategy = uiState.selectedStrategy
        if (selectedStrategy == null) {
            validateWorksheetSet()
            return
        }

        val missingSources = uiState.selection.allSheets.filterNot { it.sourceIdentifier in sourcesByIdentifier }
        if (missingSources.isNotEmpty()) {
            updateSession(
                clearGeneratedState(
                    uiState.copy(
                        validationAttempted = true,
                        notice = "Unable to read ${missingSources.first().displayName}.",
                    ),
                ),
            )
            return
        }

        // Prepare requests on main thread, then perform heavy work on IO dispatcher.
        val worksheetRequests = uiState.selection.allSheets.map { worksheet ->
            ParseRequest(PngWorksheetSource(sourcesByIdentifier.getValue(worksheet.sourceIdentifier)), worksheet)
        }

        updateSession(uiState.copy(notice = "Loading PNG..."))

        viewModelScope.launch {
            updateSession(uiState.copy(notice = "Running OCR..."))

            val result = withContext(Dispatchers.IO) {
                compilationPipeline.compile(
                    WorksheetCompilationRequest(
                        selection = uiState.selection,
                        worksheetRequests = worksheetRequests,
                            strategy = selectedStrategy,
                    )
                )
            }

            when (result) {
                is WorksheetCompilationResult.Generated -> {
                    val passes = result.compilation.passes
                    updateSession(
                        uiState.copy(
                            validationReport = result.compilation.validationReport,
                            validationAttempted = true,
                            currentPassIndex = 0,
                            compilation = result.compilation,
                            restoredPromptPasses = emptyList(),
                            commentsByPass = emptyMap(),
                            passHistory = passes.associate { pass -> pass.type to listOf(pass) },
                            compilationDiagnostics = result.diagnostics,
                            notice = null,
                        ),
                    )
                }

                is WorksheetCompilationResult.Blocked -> {
                    val issueNotice = result.validationReport.issues.firstOrNull()?.message
                        ?: "Compilation blocked. See compilation log for details."
                    updateSession(
                        clearGeneratedState(
                            uiState.copy(
                                validationReport = result.validationReport,
                                validationAttempted = true,
                                compilationDiagnostics = result.diagnostics,
                                notice = issueNotice,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun validateWorksheetSet() {
        val validationReport = ValidationReport()
        updateSession(
            clearGeneratedState(
                uiState.copy(
                    validationReport = validationReport,
                    validationAttempted = true,
                ),
            ),
        )
    }

    fun selectPass(index: Int) {
        if (index in uiState.promptPasses.indices) updateSession(uiState.copy(currentPassIndex = index))
    }

    fun showPreviousPass() {
        selectPass(uiState.currentPassIndex - 1)
    }

    fun showNextPass() {
        selectPass(uiState.currentPassIndex + 1)
    }

    fun updateCurrentPassComment(comment: String) {
        val pass = uiState.currentPass ?: return
        updateSession(uiState.copy(commentsByPass = uiState.commentsByPass + (pass.type to comment)))
    }

    fun regenerateCurrentPass(persistRefinement: Boolean = true) {
        val compilation = uiState.compilation
        val pass = uiState.currentPass
        if (compilation == null || pass == null) {
            updateSession(uiState.copy(notice = "Recompile the restored worksheet session before regenerating a pass."))
            return
        }
        val comment = uiState.commentsByPass[pass.type].orEmpty()
        val precedingPrompt = uiState.promptPasses.getOrNull(uiState.currentPassIndex - 1)?.positivePrompt
        val context = PassRefinementContext(
            targetPass = pass.type,
            previousGeneratedPrompt = precedingPrompt,
            userComment = comment,
            refinementPurpose = "session pass regeneration",
            compilerState = compilation,
            persistRule = persistRefinement,
        )
        when (val result = refinementService.refine(context)) {
            is PassRefinementResult.Generated -> {
                val regenerated = result.compilation.passes.single { it.type == pass.type }
                val updatedHistory = (uiState.passHistory[pass.type].orEmpty() + regenerated)
                    .takeLast(uiState.settings.maximumSessionHistory)
                updateSession(
                    uiState.copy(
                        compilation = result.compilation,
                        restoredPromptPasses = emptyList(),
                        validationReport = result.compilation.validationReport,
                        passHistory = uiState.passHistory + (pass.type to updatedHistory),
                        notice = "${pass.type.displayName} pass regenerated.",
                    ),
                )
            }

            is PassRefinementResult.Rejected -> {
                updateSession(
                    uiState.copy(
                        validationReport = uiState.validationReport + result.validationReport,
                        notice = result.validationReport.issues.firstOrNull()?.message ?: "Unable to regenerate the pass.",
                    ),
                )
            }
        }
    }

    fun copyCurrentPass() {
        val pass = uiState.currentPass
        if (pass == null || pass.generationStatus != PromptGenerationStatus.GENERATED || !pass.hasPrompt) return
        clipboard.copy("Asterion ${pass.type.displayName} prompt", pass.copyableText())
        updateSession(uiState.copy(notice = "${pass.type.displayName} prompt copied."))
    }

    fun copyCompilationLog() {
        if (uiState.compilationDiagnostics.isEmpty()) return
        clipboard.copy("Asterion compilation log", uiState.compilationDiagnostics.toPlainText())
        updateSession(uiState.copy(notice = "Compilation log copied."))
    }

    fun restartStrategy() {
        updateSession(clearGeneratedState(uiState.copy(notice = "Strategy restarted. Worksheet selections are retained.")))
    }

    fun resetCompiler() {
        sessionRepository.clear()
        uiState = CompilerUiState(settings = uiState.settings, notice = "Compiler session reset.")
    }

    fun updateSettings(settings: AppSettings) {
        val normalized = settings.copy(maximumSessionHistory = settings.maximumSessionHistory.coerceIn(1, 50))
        settingsRepository.save(normalized)
        val trimmedHistory = uiState.passHistory.mapValues { (_, history) ->
            history.takeLast(normalized.maximumSessionHistory)
        }
        updateSession(uiState.copy(settings = normalized, passHistory = trimmedHistory))
    }

    fun reportSourceReadFailure() {
        updateSession(uiState.copy(notice = "One or more selected worksheet files could not be read."))
    }

    fun dismissNotice() {
        updateSession(uiState.copy(notice = null))
    }

    private fun restoreState(): CompilerUiState {
        val settings = settingsRepository.load()
        val persisted = sessionRepository.load() ?: return CompilerUiState(settings = settings)
        val restoredPasses = persisted.generatedPasses.map { it.toPromptPass() }
        return CompilerUiState(
            settings = settings,
            selection = persisted.toWorksheetSelection(),
            selectedStrategyId = persisted.strategyId?.takeIf { EmbeddedProfiles.strategyById(it) != null }
                ?: persisted.checkpointId?.let(EmbeddedProfiles::defaultStrategyForCheckpoint)?.id,
            selectedCheckpointId = persisted.checkpointId?.takeIf { EmbeddedProfiles.checkpointById(it) != null },
            validationAttempted = restoredPasses.isNotEmpty(),
            currentPassIndex = persisted.currentPassIndex.coerceIn(0, restoredPasses.lastIndex.coerceAtLeast(0)),
            restoredPromptPasses = restoredPasses,
            commentsByPass = persisted.commentsByPass,
            passHistory = persisted.passHistory.mapValues { (_, history) -> history.map { it.toPromptPass() } },
            notice = if (restoredPasses.isNotEmpty()) "Restored the previous compiler session." else null,
        )
    }

    private fun resetForSelection(selection: WorksheetSelection, notice: String? = null) {
        updateSession(clearGeneratedState(uiState.copy(selection = selection, notice = notice)))
    }

    private fun clearGeneratedState(state: CompilerUiState): CompilerUiState = state.copy(
        validationReport = ValidationReport(),
        validationAttempted = false,
        currentPassIndex = 0,
        compilation = null,
        restoredPromptPasses = emptyList(),
        commentsByPass = emptyMap(),
        passHistory = emptyMap(),
        compilationDiagnostics = emptyList(),
    )

    private fun updateSession(state: CompilerUiState) {
        uiState = state
        sessionRepository.save(
            PersistedCompilerSession(
                characterSheets = state.selection.characterSheets.map { it.toPersisted() },
                themeSheet = state.selection.themeSheet?.toPersisted(),
                artStyleSheet = state.selection.artStyleSheet?.toPersisted(),
                strategyId = state.selectedStrategyId,
                checkpointId = state.selectedCheckpointId,
                currentPassIndex = state.currentPassIndex,
                commentsByPass = state.commentsByPass,
                generatedPasses = state.promptPasses.map { it.toPersisted() },
                passHistory = state.passHistory.mapValues { (_, history) -> history.map { it.toPersisted() } },
                savedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun PromptPass.copyableText(): String = buildString {
        append(positivePrompt)
        if (negativePrompt.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append("Negative prompt: ")
            append(negativePrompt)
        }
    }

    companion object {
        fun factory(container: AsterionAppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CompilerViewModel(
                    sessionRepository = container.sessionRepository,
                    settingsRepository = container.settingsRepository,
                    clipboard = container.clipboard,
                    refinementService = container.refinementService,
                    compilationPipeline = DeterministicCompilationPipeline(refinementMemory = container.refinementMemory),
                )
            }
        }
    }
}