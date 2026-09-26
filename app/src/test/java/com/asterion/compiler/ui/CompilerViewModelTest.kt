package com.asterion.compiler.ui

import com.asterion.compiler.app.AppSettings
import com.asterion.compiler.app.PersistedCompilerSession
import com.asterion.compiler.app.toPersisted
import com.asterion.compiler.profiles.GenerationParameters
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.repository.InMemoryCompilerSessionRepository
import com.asterion.compiler.repository.InMemorySettingsRepository
import com.asterion.compiler.service.RecordingPromptClipboard
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerViewModelTest {
    @Test
    fun `restores the current session and copies the restored prompt through the clipboard service`() {
        val sessionRepository = InMemoryCompilerSessionRepository()
        val settingsRepository = InMemorySettingsRepository(AppSettings(maximumSessionHistory = 3))
        val clipboard = RecordingPromptClipboard()
        val worksheet = WorksheetReference(
            id = "character",
            role = WorksheetRole.CHARACTER,
            displayName = "character.png",
            sourceIdentifier = "content://worksheets/character",
            mimeType = "image/png",
        )
        val pass = prompt()
        sessionRepository.save(
            PersistedCompilerSession(
                characterSheets = listOf(worksheet.toPersisted()),
                themeSheet = null,
                artStyleSheet = null,
                strategyId = "balanced",
                checkpointId = "rin_flanime_v1_turbo",
                currentPassIndex = 0,
                commentsByPass = mapOf(PromptPassType.FOUNDATION to "make the face more angular"),
                generatedPasses = listOf(pass.toPersisted()),
                passHistory = mapOf(PromptPassType.FOUNDATION to listOf(pass.toPersisted())),
                savedAtEpochMillis = 1L,
            ),
        )

        val viewModel = CompilerViewModel(sessionRepository, settingsRepository, clipboard)

        assertTrue(viewModel.uiState.isRestoredSession)
        assertEquals("rin_flanime_v1_turbo", viewModel.uiState.selectedCheckpointId)
        assertEquals("make the face more angular", viewModel.uiState.currentPassComment)
        assertEquals(worksheet, viewModel.uiState.selection.characterSheets.single())

        viewModel.copyCurrentPass()

        assertTrue(clipboard.copiedText!!.contains("angular face"))
        assertTrue(clipboard.copiedText!!.contains("Negative prompt: lowres"))
    }

    @Test
    fun `restart retains worksheets and persists the cleared prompt session`() {
        val sessionRepository = InMemoryCompilerSessionRepository()
        val worksheet = WorksheetReference(
            id = "theme",
            role = WorksheetRole.THEME,
            displayName = "theme.png",
            sourceIdentifier = "content://worksheets/theme",
            mimeType = "image/png",
        )
        sessionRepository.save(
            PersistedCompilerSession(
                characterSheets = emptyList(),
                themeSheet = worksheet.toPersisted(),
                artStyleSheet = null,
                strategyId = "balanced",
                checkpointId = "prefect_illustrious_v8",
                currentPassIndex = 0,
                commentsByPass = emptyMap(),
                generatedPasses = listOf(prompt().toPersisted()),
                savedAtEpochMillis = 1L,
            ),
        )
        val viewModel = CompilerViewModel(sessionRepository)

        viewModel.restartStrategy()

        assertEquals(worksheet, viewModel.uiState.selection.themeSheet)
        assertFalse(viewModel.uiState.canDisplayPasses)
        assertTrue(sessionRepository.load()!!.generatedPasses.isEmpty())
    }

    private fun prompt(): PromptPass = PromptPass(
        type = PromptPassType.FOUNDATION,
        objective = "Foundation",
        positivePrompt = "angular face, white braid",
        negativePrompt = "lowres, blurry",
        generationParameters = GenerationParameters(1024, 1024, 30, 6.5, "DPM++ 2M Karras"),
        generationStatus = PromptGenerationStatus.GENERATED,
    )
}
