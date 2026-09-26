package com.asterion.compiler.service

import com.asterion.compiler.compiler.PromptCompilation
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class RefinementRuleType {
    REPLACE_PHRASE,
    REPLACE_WORD,
    REMOVE_DESCRIPTOR,
    ADD_DESCRIPTOR,
    REORDER_DESCRIPTORS,
    INCREASE_EMPHASIS,
    DECREASE_EMPHASIS,
    MERGE_DESCRIPTORS,
    SPLIT_DESCRIPTORS,
    PREFERRED_WORDING,
    FORBIDDEN_WORDING,
}

data class PromptRefinementRule(
    val id: String = UUID.randomUUID().toString(),
    val type: RefinementRuleType,
    val phrase: String,
    val replacement: String = "",
    val secondaryPhrase: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val targetPass: PromptPassType? = null,
)

interface PromptRefinementMemory {
    fun list(): List<PromptRefinementRule>
    fun upsert(rule: PromptRefinementRule)
    fun setEnabled(id: String, enabled: Boolean)
    fun delete(id: String)
    fun reorder(idsInPriorityOrder: List<String>)
    fun export(): String
    fun import(serialized: String)
    fun apply(compilation: PromptCompilation): PromptCompilation
}

open class InMemoryPromptRefinementMemory : PromptRefinementMemory {
    private val rules = mutableListOf<PromptRefinementRule>()

    override fun list(): List<PromptRefinementRule> = rules.sortedWith(compareBy<PromptRefinementRule> { it.priority }.thenBy { it.id })

    override fun upsert(rule: PromptRefinementRule) {
        rules.removeAll { it.id == rule.id }
        rules += rule
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        rules.replaceAll { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    override fun delete(id: String) {
        rules.removeAll { it.id == id }
    }

    override fun reorder(idsInPriorityOrder: List<String>) {
        val positions = idsInPriorityOrder.withIndex().associate { it.value to it.index }
        rules.replaceAll { it.copy(priority = positions[it.id] ?: it.priority) }
    }

    override fun export(): String = JSONArray().apply { list().forEach { put(it.toJson()) } }.toString()

    override fun import(serialized: String) {
        val array = JSONArray(serialized)
        rules.clear()
        for (index in 0 until array.length()) rules += promptRefinementRuleFromJson(array.getJSONObject(index))
    }

    override fun apply(compilation: PromptCompilation): PromptCompilation {
        if (compilation.passes.isEmpty()) return compilation
        val transformed = compilation.passes.map { pass ->
            var positive = pass.positivePrompt
            var negative = pass.negativePrompt
            list().filter { it.enabled && (it.targetPass == null || it.targetPass == pass.type) }.forEach { rule ->
                positive = applyRule(positive, rule)
                negative = applyRule(negative, rule)
            }
            pass.copy(positivePrompt = positive, negativePrompt = negative)
        }
        return compilation.copy(passes = transformed)
    }

    private fun applyRule(prompt: String, rule: PromptRefinementRule): String {
        if (prompt.isBlank()) return prompt
        val escaped = Regex.escape(rule.phrase)
        val result = when (rule.type) {
            RefinementRuleType.REPLACE_PHRASE,
            RefinementRuleType.PREFERRED_WORDING -> prompt.replace(Regex(escaped, RegexOption.IGNORE_CASE), rule.replacement)
            RefinementRuleType.REPLACE_WORD -> prompt.replace(Regex("\\b$escaped\\b", RegexOption.IGNORE_CASE), rule.replacement)
            RefinementRuleType.REMOVE_DESCRIPTOR,
            RefinementRuleType.FORBIDDEN_WORDING -> prompt.replace(Regex("(?:^|, )$escaped(?=, |$)", RegexOption.IGNORE_CASE), "")
            RefinementRuleType.ADD_DESCRIPTOR -> if (prompt.split(", ").any { it.equals(rule.phrase, true) }) prompt else "$prompt, ${rule.phrase}"
            RefinementRuleType.INCREASE_EMPHASIS -> emphasize(prompt, rule.phrase, 1.2f)
            RefinementRuleType.DECREASE_EMPHASIS -> emphasize(prompt, rule.phrase, 0.8f)
            RefinementRuleType.MERGE_DESCRIPTORS -> prompt.replace(
                Regex("${Regex.escape(rule.phrase)}\\s*,\\s*${Regex.escape(rule.secondaryPhrase)}", RegexOption.IGNORE_CASE),
                rule.replacement,
            )
            RefinementRuleType.SPLIT_DESCRIPTORS -> prompt.replace(Regex(escaped, RegexOption.IGNORE_CASE), rule.replacement)
            RefinementRuleType.REORDER_DESCRIPTORS -> reorder(prompt, rule.phrase, rule.secondaryPhrase)
        }
        return result.split(", ").map(String::trim).filter(String::isNotBlank).distinctBy(String::lowercase).joinToString(", ")
    }

    private fun emphasize(prompt: String, phrase: String, weight: Float): String =
        prompt.replace(Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE), "($phrase:${weight})")

    private fun reorder(prompt: String, first: String, second: String): String {
        val parts = prompt.split(", ").toMutableList()
        val firstIndex = parts.indexOfFirst { it.equals(first, true) }
        val secondIndex = parts.indexOfFirst { it.equals(second, true) }
        if (firstIndex >= 0 && secondIndex >= 0 && firstIndex > secondIndex) {
            val value = parts.removeAt(firstIndex)
            parts.add(secondIndex, value)
        }
        return parts.joinToString(", ")
    }
}

class PersistentPromptRefinementMemory(
    private val store: () -> String?,
    private val save: (String) -> Unit,
) : InMemoryPromptRefinementMemory() {
    init { store()?.let { runCatching { import(it) } } }

    override fun upsert(rule: PromptRefinementRule) { super.upsert(rule); persist() }
    override fun setEnabled(id: String, enabled: Boolean) { super.setEnabled(id, enabled); persist() }
    override fun delete(id: String) { super.delete(id); persist() }
    override fun reorder(idsInPriorityOrder: List<String>) { super.reorder(idsInPriorityOrder); persist() }
    override fun import(serialized: String) { super.import(serialized); persist() }

    private fun persist() { save(export()) }
}

class JsonFilePromptRefinementMemory(
    private val file: File,
) : InMemoryPromptRefinementMemory() {
    init {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.writeText("[]")
        runCatching { import(file.readText()) }
    }

    override fun upsert(rule: PromptRefinementRule) { super.upsert(rule); persist() }
    override fun setEnabled(id: String, enabled: Boolean) { super.setEnabled(id, enabled); persist() }
    override fun delete(id: String) { super.delete(id); persist() }
    override fun reorder(idsInPriorityOrder: List<String>) { super.reorder(idsInPriorityOrder); persist() }
    override fun import(serialized: String) {
        val parsed = JSONArray(serialized)
        val validated = buildList {
            for (index in 0 until parsed.length()) {
                runCatching { promptRefinementRuleFromJson(parsed.getJSONObject(index)) }
                    .onSuccess(::add)
            }
        }
        super.import(JSONArray().apply { validated.forEach { put(it.toJson()) } }.toString())
        persist()
    }

    private fun persist() {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(export())
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun PromptRefinementRule.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("type", type.name)
    put("phrase", phrase)
    put("replacement", replacement)
    put("secondaryPhrase", secondaryPhrase)
    put("enabled", enabled)
    put("priority", priority)
    put("targetPass", targetPass?.name)
}

private fun promptRefinementRuleFromJson(json: JSONObject): PromptRefinementRule = PromptRefinementRule(
    id = json.optString("id"),
    type = RefinementRuleType.valueOf(json.getString("type")),
    phrase = json.getString("phrase"),
    replacement = json.optString("replacement"),
    secondaryPhrase = json.optString("secondaryPhrase"),
    enabled = json.optBoolean("enabled", true),
    priority = json.optInt("priority", 0),
    targetPass = json.optString("targetPass", "").takeIf(String::isNotBlank)?.let(PromptPassType::valueOf),
)
