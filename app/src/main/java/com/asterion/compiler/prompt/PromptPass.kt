package com.asterion.compiler.prompt

import com.asterion.compiler.profiles.GenerationParameters
import com.asterion.compiler.profiles.CompilationStrategy

enum class PromptPassType(val displayName: String) {
    FOUNDATION("Foundation"),
    MATERIAL("Material"),
    SCENE("Scene"),
    RENDERING("Rendering"),
}

enum class PromptGenerationStatus {
    PENDING,
    GENERATED,
    BLOCKED,
}

data class PromptPass(
    val type: PromptPassType,
    val objective: String,
    val positivePrompt: String = "",
    val negativePrompt: String = "",
    val generationParameters: GenerationParameters,
    val generationStatus: PromptGenerationStatus = PromptGenerationStatus.PENDING,
) {
    val hasPrompt: Boolean
        get() = positivePrompt.isNotBlank()
}

object PromptPassCatalog {
    fun createFor(strategy: CompilationStrategy): List<PromptPass> = strategy.passOrder.map { type ->
        PromptPass(
            type = type,
            objective = objectiveFor(type),
            generationParameters = strategy.checkpointProfile.defaultParameters,
        )
    }

    private fun objectiveFor(type: PromptPassType): String = when (type) {
        PromptPassType.FOUNDATION -> "Establish the character identity and visual anchors."
        PromptPassType.MATERIAL -> "Establish garment, surface, and texture direction."
        PromptPassType.SCENE -> "Establish setting, composition, and narrative context."
        PromptPassType.RENDERING -> "Establish lighting, rendering, and finishing direction."
    }
}