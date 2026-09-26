package com.asterion.compiler.profiles

import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.worksheet.WorksheetRole

data class GenerationParameters(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Double,
    val sampler: String,
    val seed: Long? = null,
)

data class CheckpointProfile(
    val id: String,
    val displayName: String,
    val baseModelFamily: String,
    val defaultParameters: GenerationParameters,
    val promptOptimization: CheckpointPromptOptimization = CheckpointPromptOptimization(),
)

data class CheckpointPromptOptimization(
    val lexicalReplacements: Map<String, String> = emptyMap(),
    val negativeTerms: List<String> = emptyList(),
    val passNegativeTerms: Map<PromptPassType, List<String>> = emptyMap(),
)

data class GlobalPreferenceProfile(
    val offlineOnly: Boolean,
    val persistentHistoryEnabled: Boolean,
    val exportEnabled: Boolean,
    val promptLanguage: String,
)

data class CompilationStrategy(
    val id: String,
    val displayName: String,
    val checkpointProfile: CheckpointProfile,
    val passOrder: List<PromptPassType>,
    val rolePriority: List<WorksheetRole> = listOf(WorksheetRole.CHARACTER, WorksheetRole.THEME, WorksheetRole.ART_STYLE),
    val maximumTokens: Int = 512,
    val compactDescriptors: Boolean = false,
)

object EmbeddedProfiles {
    private val prefectIllustriousV8 = supportedCheckpoint(
        id = "prefect_illustrious_v8",
        displayName = "prefect_illustrious_v8",
        sampler = "DPM++ 2M Karras",
    )
    private val illustriousV17 = supportedCheckpoint(
        id = "illustrious_v17",
        displayName = "illustrious_v17",
        sampler = "DPM++ 2M Karras",
    )
    private val rinFlanimeV1Turbo = supportedCheckpoint(
        id = "rin_flanime_v1_turbo",
        displayName = "rin_flanime_v1_turbo",
        sampler = "DPM++ 2M Karras",
    )
    private val waiAnimaV1Turbo = supportedCheckpoint(
        id = "wai_anima_v1_turbo",
        displayName = "wai_anima_v1_turbo",
        sampler = "DPM++ 2M Karras",
    )
    private val novaanimeV3Turbo = supportedCheckpoint(
        id = "novaanime_v3_turbo",
        displayName = "novaanime_v3_turbo",
        sampler = "DPM++ SDE Karras",
    )

    val checkpoints: List<CheckpointProfile> = listOf(
        prefectIllustriousV8,
        illustriousV17,
        rinFlanimeV1Turbo,
        waiAnimaV1Turbo,
        novaanimeV3Turbo,
    )

    val globalPreferences = GlobalPreferenceProfile(
        offlineOnly = true,
        persistentHistoryEnabled = false,
        exportEnabled = false,
        promptLanguage = "en",
    )

    val strategies: List<CompilationStrategy> = listOf(
        CompilationStrategy(
            id = "balanced",
            displayName = "Balanced",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
        ),
        CompilationStrategy(
            id = "character-priority",
            displayName = "Character Priority",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
            rolePriority = listOf(WorksheetRole.CHARACTER, WorksheetRole.THEME, WorksheetRole.ART_STYLE),
        ),
        CompilationStrategy(
            id = "theme-priority",
            displayName = "Theme Priority",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
            rolePriority = listOf(WorksheetRole.THEME, WorksheetRole.CHARACTER, WorksheetRole.ART_STYLE),
        ),
        CompilationStrategy(
            id = "style-priority",
            displayName = "Style Priority",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
            rolePriority = listOf(WorksheetRole.ART_STYLE, WorksheetRole.CHARACTER, WorksheetRole.THEME),
        ),
        CompilationStrategy(
            id = "compact-77",
            displayName = "Compact (77 Tokens)",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
            maximumTokens = 77,
            compactDescriptors = true,
        ),
        CompilationStrategy(
            id = "expanded-512",
            displayName = "Expanded (512 Tokens)",
            checkpointProfile = prefectIllustriousV8,
            passOrder = PromptPassType.entries,
            maximumTokens = 512,
        ),
    )

    fun strategyById(id: String): CompilationStrategy? = strategies.firstOrNull { it.id == id }

    fun checkpointById(id: String): CheckpointProfile? = checkpoints.firstOrNull { it.id == id }

    fun defaultStrategyForCheckpoint(checkpointId: String): CompilationStrategy? = when (checkpointId) {
        "prefect_illustrious_v8", "illustrious_v17" -> strategies.first { it.id == "compact-77" }
        "rin_flanime_v1_turbo", "wai_anima_v1_turbo", "novaanime_v3_turbo" -> strategies.first { it.id == "expanded-512" }
        else -> null
    }

    private fun checkpointOptimization(
        lexicalReplacements: Map<String, String>,
        negativeTerms: List<String>,
    ): CheckpointPromptOptimization = CheckpointPromptOptimization(
        lexicalReplacements = lexicalReplacements,
        negativeTerms = negativeTerms,
        passNegativeTerms = mapOf(
            PromptPassType.FOUNDATION to listOf("distorted face", "extra limbs", "crossed eyes"),
            PromptPassType.MATERIAL to listOf("plastic skin", "incorrect fabric texture", "flat materials"),
            PromptPassType.SCENE to listOf("flat background", "floating objects", "inconsistent perspective"),
            PromptPassType.RENDERING to listOf("color banding", "over-sharpened edges", "muddy details"),
        ),
    )

    private fun supportedCheckpoint(id: String, displayName: String, sampler: String): CheckpointProfile = CheckpointProfile(
        id = id,
        displayName = displayName,
        baseModelFamily = "SDXL",
        defaultParameters = GenerationParameters(1024, 1024, 30, 6.5, sampler),
        promptOptimization = checkpointOptimization(
            lexicalReplacements = emptyMap(),
            negativeTerms = listOf("lowres", "blurry", "watermark", "signature"),
        ),
    )
}