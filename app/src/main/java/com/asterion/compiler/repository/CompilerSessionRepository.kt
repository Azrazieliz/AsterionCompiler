package com.asterion.compiler.repository

import android.content.Context
import com.asterion.compiler.app.PersistedCompilerSession
import com.asterion.compiler.app.PersistedPromptPass
import com.asterion.compiler.app.PersistedWorksheetReference
import com.asterion.compiler.profiles.GenerationParameters
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.worksheet.WorksheetRole
import org.json.JSONArray
import org.json.JSONObject

interface CompilerSessionRepository {
    fun load(): PersistedCompilerSession?

    fun save(session: PersistedCompilerSession)

    fun clear()
}

class InMemoryCompilerSessionRepository : CompilerSessionRepository {
    private var session: PersistedCompilerSession? = null

    override fun load(): PersistedCompilerSession? = session

    override fun save(session: PersistedCompilerSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }
}

class SharedPreferencesCompilerSessionRepository(context: Context) : CompilerSessionRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun load(): PersistedCompilerSession? = preferences.getString(SessionKey, null)?.let { raw ->
        runCatching { decode(raw) }.getOrNull()
    }

    override fun save(session: PersistedCompilerSession) {
        preferences.edit().putString(SessionKey, encode(session).toString()).apply()
    }

    override fun clear() {
        preferences.edit().remove(SessionKey).apply()
    }

    private fun encode(session: PersistedCompilerSession): JSONObject = JSONObject().apply {
        put("characters", session.characterSheets.toJsonArray(::worksheetToJson))
        put("theme", session.themeSheet?.let(::worksheetToJson))
        put("artStyle", session.artStyleSheet?.let(::worksheetToJson))
        put("strategyId", session.strategyId)
        put("checkpointId", session.checkpointId)
        put("currentPassIndex", session.currentPassIndex)
        put("savedAtEpochMillis", session.savedAtEpochMillis)
        put("comments", JSONObject().apply {
            session.commentsByPass.forEach { (pass, comment) -> put(pass.name, comment) }
        })
        put("passes", session.generatedPasses.toJsonArray(::promptToJson))
        put("history", JSONObject().apply {
            session.passHistory.forEach { (pass, history) ->
                put(pass.name, history.toJsonArray(::promptToJson))
            }
        })
    }

    private fun decode(raw: String): PersistedCompilerSession {
        val json = JSONObject(raw)
        return PersistedCompilerSession(
            characterSheets = json.getJSONArray("characters").toList(::worksheetFromJson),
            themeSheet = json.optJSONObject("theme")?.let(::worksheetFromJson),
            artStyleSheet = json.optJSONObject("artStyle")?.let(::worksheetFromJson),
            strategyId = json.optString("strategyId", "").takeIf(String::isNotBlank),
            checkpointId = json.optString("checkpointId", "").takeIf(String::isNotBlank),
            currentPassIndex = json.optInt("currentPassIndex", 0),
            commentsByPass = json.optJSONObject("comments")?.let { comments ->
                PromptPassType.entries.mapNotNull { pass ->
                    comments.optString(pass.name, "").takeIf(String::isNotBlank)?.let { pass to it }
                }.toMap()
            }.orEmpty(),
            generatedPasses = json.getJSONArray("passes").toList(::promptFromJson),
            passHistory = json.optJSONObject("history")?.let { history ->
                PromptPassType.entries.mapNotNull { pass ->
                    history.optJSONArray(pass.name)?.toList(::promptFromJson)?.let { pass to it }
                }.toMap()
            }.orEmpty(),
            savedAtEpochMillis = json.optLong("savedAtEpochMillis", 0L),
        )
    }

    private fun worksheetToJson(worksheet: PersistedWorksheetReference): JSONObject = JSONObject().apply {
        put("id", worksheet.id)
        put("role", worksheet.role.name)
        put("displayName", worksheet.displayName)
        put("sourceIdentifier", worksheet.sourceIdentifier)
        put("mimeType", worksheet.mimeType)
    }

    private fun worksheetFromJson(json: JSONObject): PersistedWorksheetReference = PersistedWorksheetReference(
        id = json.getString("id"),
        role = WorksheetRole.valueOf(json.getString("role")),
        displayName = json.getString("displayName"),
        sourceIdentifier = json.getString("sourceIdentifier"),
        mimeType = json.getString("mimeType"),
    )

    private fun promptToJson(prompt: PersistedPromptPass): JSONObject = JSONObject().apply {
        put("type", prompt.type.name)
        put("objective", prompt.objective)
        put("positivePrompt", prompt.positivePrompt)
        put("negativePrompt", prompt.negativePrompt)
        put("status", prompt.generationStatus.name)
        put("width", prompt.generationParameters.width)
        put("height", prompt.generationParameters.height)
        put("steps", prompt.generationParameters.steps)
        put("cfgScale", prompt.generationParameters.cfgScale)
        put("sampler", prompt.generationParameters.sampler)
        put("seed", prompt.generationParameters.seed)
    }

    private fun promptFromJson(json: JSONObject): PersistedPromptPass = PersistedPromptPass(
        type = PromptPassType.valueOf(json.getString("type")),
        objective = json.getString("objective"),
        positivePrompt = json.getString("positivePrompt"),
        negativePrompt = json.getString("negativePrompt"),
        generationParameters = GenerationParameters(
            width = json.getInt("width"),
            height = json.getInt("height"),
            steps = json.getInt("steps"),
            cfgScale = json.getDouble("cfgScale"),
            sampler = json.getString("sampler"),
            seed = json.takeIf { !it.isNull("seed") }?.getLong("seed"),
        ),
        generationStatus = PromptGenerationStatus.valueOf(json.getString("status")),
    )

    private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray = JSONArray().also { array ->
        forEach { array.put(transform(it)) }
    }

    private fun <T> JSONArray.toList(transform: (JSONObject) -> T): List<T> = buildList {
        for (index in 0 until length()) add(transform(getJSONObject(index)))
    }

    private companion object {
        const val PreferencesName = "asterion_compiler_session"
        const val SessionKey = "compiler_session_v1"
    }
}