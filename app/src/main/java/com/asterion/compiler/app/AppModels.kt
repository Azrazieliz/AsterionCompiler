package com.asterion.compiler.app

import com.asterion.compiler.profiles.GenerationParameters
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.asterion.compiler.worksheet.WorksheetSelection

enum class AppDestination {
    STUDIO,
    HISTORY,
    SETTINGS,
    PROMPT_REFINEMENT_MEMORY,
}

enum class ThemePreference {
    DARK,
    SYSTEM,
}

enum class ThumbnailSize(val pixels: Int) {
    COMPACT(96),
    STANDARD(144),
    LARGE(208),
}

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.DARK,
    val thumbnailSize: ThumbnailSize = ThumbnailSize.STANDARD,
    val maximumSessionHistory: Int = 12,
)

data class PersistedWorksheetReference(
    val id: String,
    val role: WorksheetRole,
    val displayName: String,
    val sourceIdentifier: String,
    val mimeType: String,
)

data class PersistedPromptPass(
    val type: PromptPassType,
    val objective: String,
    val positivePrompt: String,
    val negativePrompt: String,
    val generationParameters: GenerationParameters,
    val generationStatus: PromptGenerationStatus,
)

data class PersistedCompilerSession(
    val characterSheets: List<PersistedWorksheetReference>,
    val themeSheet: PersistedWorksheetReference?,
    val artStyleSheet: PersistedWorksheetReference?,
    val strategyId: String?,
    val checkpointId: String?,
    val currentPassIndex: Int,
    val commentsByPass: Map<PromptPassType, String>,
    val generatedPasses: List<PersistedPromptPass>,
    val passHistory: Map<PromptPassType, List<PersistedPromptPass>> = emptyMap(),
    val savedAtEpochMillis: Long,
)

fun WorksheetReference.toPersisted(): PersistedWorksheetReference = PersistedWorksheetReference(
    id = id,
    role = role,
    displayName = displayName,
    sourceIdentifier = sourceIdentifier,
    mimeType = mimeType,
)

fun PersistedWorksheetReference.toWorksheetReference(): WorksheetReference = WorksheetReference(
    id = id,
    role = role,
    displayName = displayName,
    sourceIdentifier = sourceIdentifier,
    mimeType = mimeType,
)

fun WorksheetSelection.toPersisted(): Triple<List<PersistedWorksheetReference>, PersistedWorksheetReference?, PersistedWorksheetReference?> = Triple(
    first = characterSheets.map(WorksheetReference::toPersisted),
    second = themeSheet?.toPersisted(),
    third = artStyleSheet?.toPersisted(),
)

fun PersistedCompilerSession.toWorksheetSelection(): WorksheetSelection = WorksheetSelection(
    characterSheets = characterSheets.map(PersistedWorksheetReference::toWorksheetReference),
    themeSheet = themeSheet?.toWorksheetReference(),
    artStyleSheet = artStyleSheet?.toWorksheetReference(),
)

fun PromptPass.toPersisted(): PersistedPromptPass = PersistedPromptPass(
    type = type,
    objective = objective,
    positivePrompt = positivePrompt,
    negativePrompt = negativePrompt,
    generationParameters = generationParameters,
    generationStatus = generationStatus,
)

fun PersistedPromptPass.toPromptPass(): PromptPass = PromptPass(
    type = type,
    objective = objective,
    positivePrompt = positivePrompt,
    negativePrompt = negativePrompt,
    generationParameters = generationParameters,
    generationStatus = generationStatus,
)