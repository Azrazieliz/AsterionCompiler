package com.asterion.compiler.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class JsonFilePromptRefinementMemoryTest {
    @Test
    fun `rules are written as JSON and restored by a new memory instance`() {
        val directory = Files.createTempDirectory("asterion-refinement").toFile()
        val file = directory.resolve("prompt_refinement_rules.json")
        val first = JsonFilePromptRefinementMemory(file)
        first.upsert(
            PromptRefinementRule(
                id = "rule-1",
                type = RefinementRuleType.PREFERRED_WORDING,
                phrase = "rough light",
                replacement = "soft illumination",
            ),
        )

        assertTrue(file.readText().startsWith("["))
        assertEquals("soft illumination", JsonFilePromptRefinementMemory(file).list().single().replacement)
    }
}
