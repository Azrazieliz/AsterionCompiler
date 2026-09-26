package com.asterion.compiler.compiler

import com.asterion.compiler.profiles.CheckpointProfile
import com.asterion.compiler.profiles.CompilationStrategy
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassCatalog
import com.asterion.compiler.prompt.PromptPassType
import java.util.Locale

class VisualTranslationEngine {
    fun translate(graph: CompilationGraph): CompilationGraph {
        graph.nodes.forEach { node ->
            val translated = applyLexicalReplacements(node.descriptor, visualReplacements)
            if (translated != node.descriptor) {
                node.translationRuleIds += "visual-lexical-optimization"
                node.translatedDescriptor = translated
            }
        }
        return graph
    }

    companion object {
        internal fun applyLexicalReplacements(
            descriptor: String,
            replacements: Map<String, String>,
        ): String = replacements.entries
            .sortedByDescending { it.key.length }
            .fold(descriptor.trim()) { current, (source, target) ->
                current.replace(Regex("\\b${Regex.escape(source)}\\b", RegexOption.IGNORE_CASE), target)
            }
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ';', ':', '.')

        private val visualReplacements: Map<String, String> = linkedMapOf(
            "physiognomy" to "facial features",
            "anthropometry" to "body proportions",
            "cutaneous" to "skin",
            "dermal" to "skin",
            "ocular" to "eyes",
            "textile" to "fabric",
            "chromatic" to "color",
            "specular response" to "specular highlights",
        )
    }
}

class CheckpointPromptOptimizer {
    fun apply(graph: CompilationGraph, profile: CheckpointProfile): CompilationGraph {
        graph.nodes.forEach { node ->
            val optimized = VisualTranslationEngine.applyLexicalReplacements(
                node.translatedDescriptor,
                profile.promptOptimization.lexicalReplacements,
            )
            if (optimized != node.translatedDescriptor) {
                node.checkpointAdjustments += CheckpointAdjustment(profile.id, node.translatedDescriptor, optimized)
                node.translationRuleIds += "checkpoint-${profile.id}"
                node.translatedDescriptor = optimized
            }
        }
        return graph
    }
}

class DeterministicPromptGenerator {
    fun generate(graph: CompilationGraph, strategy: CompilationStrategy): List<PromptPass> =
        PromptPassCatalog.createFor(strategy).map { template ->
            val allPassNodes = graph.nodesFor(template.type)
            val passNodes = allPassNodes
                .filter { it.reinforcementState != ReinforcementState.SUPPRESSED }
                .sortedWith(compareBy<CompilationGraphNode> {
                    strategy.rolePriority.indexOfFirst { role -> role.name.equals(roleFor(it), ignoreCase = true) }
                        .let { index -> if (index < 0) strategy.rolePriority.size else index }
                }.thenBy { it.priority.ordinal })
            val positiveDescriptors = passNodes
                .map { it.translatedDescriptor }
                .distinctBy { compactKey(it) }
            val positivePrompt = fitTokenBudget(positiveDescriptors, strategy)
            template.copy(
                positivePrompt = positivePrompt,
                negativePrompt = (
                    strategy.checkpointProfile.promptOptimization.negativeTerms +
                        strategy.checkpointProfile.promptOptimization.passNegativeTerms[template.type].orEmpty() +
                        allPassNodes.filter { it.reinforcementState == ReinforcementState.SUPPRESSED }.map { it.translatedDescriptor }
                    )
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase(Locale.US) }
                    .joinToString(", "),
                generationStatus = PromptGenerationStatus.GENERATED,
            )
        }

    private fun fitTokenBudget(descriptors: List<String>, strategy: CompilationStrategy): String {
        val result = mutableListOf<String>()
        var count = 0
        descriptors.forEach { descriptor ->
            val words = descriptor.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.isEmpty()) return@forEach
            if (count + words.size <= strategy.maximumTokens) {
                result += if (strategy.compactDescriptors) compactDescriptor(descriptor) else descriptor.trim()
                count += words.size
            }
        }
        return result.joinToString(", ")
    }

    private fun compactDescriptor(value: String): String = value
        .replace(Regex("\\b(very|highly|extremely|visually|overall)\\b\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun compactKey(value: String): String = compactDescriptor(value).lowercase(Locale.US)

    private fun roleFor(node: CompilationGraphNode): String = when {
        node.source.specificationPath.startsWith("characters[") -> "CHARACTER"
        node.source.specificationPath.startsWith("theme.") -> "THEME"
        node.source.specificationPath.startsWith("artStyle.") -> "ART_STYLE"
        else -> ""
    }
}