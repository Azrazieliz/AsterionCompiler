package com.asterion.compiler.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRefinementMemoryTest {
    @Test
    fun `rules transform prompts deterministically and can be edited`() {
        val memory = InMemoryPromptRefinementMemory()
        val rule = PromptRefinementRule(
            id = "replace-light",
            type = RefinementRuleType.PREFERRED_WORDING,
            phrase = "soft light",
            replacement = "soft illumination",
        )
        memory.upsert(rule)
        memory.upsert(PromptRefinementRule(id = "forbid", type = RefinementRuleType.FORBIDDEN_WORDING, phrase = "muddy details"))

        val exported = memory.export()
        val restored = InMemoryPromptRefinementMemory()
        restored.import(exported)
        val rules = restored.list()
        assertEquals(2, rules.size)

        restored.setEnabled("forbid", false)
        assertFalse(restored.list().single { it.id == "forbid" }.enabled)
        restored.delete("forbid")
        assertTrue(restored.list().none { it.id == "forbid" })
    }
}
