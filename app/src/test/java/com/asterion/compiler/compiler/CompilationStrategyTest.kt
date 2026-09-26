package com.asterion.compiler.compiler

import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.prompt.PromptPassType
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationStrategyTest {
    @Test
    fun `compact strategy keeps each generated pass within 77 tokens`() {
        val graph = CompilationGraph((1..120).map { index ->
            CompilationGraphNode(
                id = "node-$index",
                source = GraphNodeSource("style", "artStyle.descriptor[$index]"),
                priority = VisualPriority.SUPPORTING,
                visualCategory = com.asterion.compiler.visual.VisualBucket.RENDERING,
                passOwnership = PromptPassType.RENDERING,
                descriptor = "descriptor $index with several visual terms",
            )
        }.toMutableList())

        val pass = DeterministicPromptGenerator().generate(
            graph,
            EmbeddedProfiles.strategies.first { it.id == "compact-77" },
        ).single { it.type == PromptPassType.RENDERING }

        assertTrue(pass.positivePrompt.split(Regex("\\s+")).count { it.isNotBlank() } <= 77)
    }

    @Test
    fun `priority strategy ranks requested worksheet role first`() {
        val graph = CompilationGraph(mutableListOf(
            CompilationGraphNode("theme", GraphNodeSource("theme", "theme.location"), VisualPriority.CORE, com.asterion.compiler.visual.VisualBucket.ENVIRONMENT, PromptPassType.SCENE, descriptor = "theme first"),
            CompilationGraphNode("character", GraphNodeSource("character", "characters[0].identifier"), VisualPriority.CORE, com.asterion.compiler.visual.VisualBucket.IDENTITY, PromptPassType.SCENE, descriptor = "character first"),
        ))

        val pass = DeterministicPromptGenerator().generate(
            graph,
            EmbeddedProfiles.strategies.first { it.id == "theme-priority" },
        ).single { it.type == PromptPassType.SCENE }

        assertTrue(pass.positivePrompt.startsWith("theme first"))
    }
}
