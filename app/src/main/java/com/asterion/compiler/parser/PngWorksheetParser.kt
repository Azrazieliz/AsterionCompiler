package com.asterion.compiler.parser

import com.asterion.compiler.diagnostics.CompilationLogEntry
import com.asterion.compiler.diagnostics.CompilationTrace
import com.asterion.compiler.worksheet.ParsedSection
import com.asterion.compiler.worksheet.UnknownField
import com.asterion.compiler.worksheet.UnknownSection
import android.graphics.BitmapFactory
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

private const val LayoutKey = "asterion.layout"
private const val RoleKey = "asterion.role"
private const val FieldPrefix = "asterion."
private const val OcrPrefix = "asterion.ocr."
private const val LayoutRevision = "asterion-worksheet-v1"

private enum class WorksheetType(val role: WorksheetRole, val displayName: String) {
    CHARACTER(WorksheetRole.CHARACTER, "Character Master Sheet"),
    THEME(WorksheetRole.THEME, "Theme Sheet"),
    ART_STYLE(WorksheetRole.ART_STYLE, "Art Style Sheet");

    companion object {
        fun fromRoleName(roleName: String?): WorksheetType? = roleName?.lowercase(Locale.US)?.let { normalized ->
            values().firstOrNull { it.role.name.lowercase(Locale.US) == normalized || it.displayName.lowercase(Locale.US).contains(normalized) }
        }

        fun fromRole(role: WorksheetRole): WorksheetType = values().first { it.role == role }
    }
}

internal interface OcrTextRecognizer {
    fun recognizeText(bytes: ByteArray): Result<OcrRecognitionResult>
}

internal data class OcrRecognitionResult(
    val transcript: String,
    val confidence: Float,
    val source: String,
)

data class PngWorksheetSource(val bytes: ByteArray)

class DeterministicWorksheetParsingPipeline(
    private val fixedLayoutParser: WorksheetParser<PngWorksheetSource> = FixedLayoutWorksheetParser(
        SemanticFixedLayoutEngine(),
    ),
    private val ocrParser: WorksheetParser<PngWorksheetSource> = OcrWorksheetParser(
        SemanticOcrEngine(),
    ),
) {
    private fun logParserResult(label: String, result: ParseResult): CompilationLogEntry {
        return when (result) {
            is ParseResult.Success -> CompilationLogEntry.pass(
                "Parser result",
                "$label SUCCEEDED (${result.worksheet.parserMetadata.strategyId}, confidence=${result.worksheet.confidence})",
                result.worksheet.parserMetadata.details.entries.map { (key, value) -> CompilationLogEntry.info(key, value) },
            )
            is ParseResult.Failure -> CompilationLogEntry.fail(
                "Parser result",
                "$label FAILED (${result.reason.name})",
                listOf(CompilationLogEntry.info("Reason", result.message)),
            )
        }
    }

    fun parse(request: ParseRequest<PngWorksheetSource>): ParseResult {
        val fixedResult = fixedLayoutParser.parse(request)
        if (fixedResult is ParseResult.Success) return fixedResult

        val ocrResult = ocrParser.parse(request)
        return ocrResult
    }

    fun parseAll(requests: List<ParseRequest<PngWorksheetSource>>): List<ParseResult> = requests.map(::parse)

    private fun aggregateFailures(
        fixed: ParseResult.Failure,
        ocr: ParseResult.Failure,
    ): ParseResult.Failure {
        val combinedMessage = buildString {
            append("Fixed-layout parser failed: ${fixed.message}")
            append(" | OCR parser failed: ${ocr.message}")
        }
        val reason = listOf(fixed.reason, ocr.reason).minByOrNull { it.priority } ?: fixed.reason
        return ParseResult.Failure(
            worksheet = fixed.worksheet,
            reason = reason,
            message = combinedMessage,
        )
    }
}

class SemanticFixedLayoutEngine : WorksheetParsingEngine<PngWorksheetSource> {
    override fun extract(source: PngWorksheetSource, worksheet: WorksheetReference): EngineParseResult {
        val metadata = PngTextMetadataReader.read(source.bytes).getOrElse { error ->
            return EngineParseResult.Failure(ParseFailureReason.UNREADABLE_CONTENT, error.message ?: "Unreadable PNG metadata.")
        }

        val canonicalMetadata = metadata.canonicalMap().getOrElse { error ->
            return EngineParseResult.Failure(ParseFailureReason.INVALID_LAYOUT, error.message ?: "Ambiguous worksheet metadata.")
        }

        val contentFields = canonicalMetadata
            .filterKeys { it.startsWith(FieldPrefix) && !it.startsWith(OcrPrefix) }
            .toList()

        if (contentFields.isEmpty()) {
            return EngineParseResult.Failure(ParseFailureReason.INVALID_LAYOUT, "The worksheet contains no structured layout fields.")
        }

        val declaredRole = canonicalMetadata[RoleKey]
        val detectedWorksheetType = detectWorksheetTypeFromStructuredFields(contentFields.map { it.first }.toSet())
            ?: WorksheetType.fromRole(worksheet.role)
        if (declaredRole != null) {
            val declaredType = WorksheetType.fromRoleName(declaredRole)
            if (declaredType != null && declaredType.role != worksheet.role) {
                // Previously this was treated as a fatal layout error. Relax this
                // assumption: preserve the parsed metadata but mark it in the
                // section metadata so callers can warn rather than fail.
            }
        }

        val path = canonicalMetadata[LayoutKey] ?: "semantic-fixed-layout-v1"
        val structuredFields = contentFields.mapNotNull { (key, value) ->
            parseStructuredMetadataEntry(key, value)
        }

        if (structuredFields.isEmpty()) {
            return EngineParseResult.Failure(ParseFailureReason.INVALID_LAYOUT, "The structured worksheet metadata did not contain recognizable semantic fields.")
        }

        val sections = structuredFields
            .groupBy { it.section }
            .toSortedMap()
            .mapValues { (_, fields) ->
                ParsedSection(
                    name = fields.first().section,
                    values = fields.associate { it.field to it.value }.toSortedMap(),
                    confidence = 1f,
                    heading = null,
                    metadata = mapOf(
                        "detectedWorksheetType" to detectedWorksheetType.displayName,
                        "layoutConfidence" to "1.0",
                    ),
                )
            }

        return EngineParseResult.Success(
            sections = sections,
            confidence = 1f,
            revision = path,
        )
    }
}

internal class SemanticOcrEngine(
    private val ocrTextRecognizer: OcrTextRecognizer = MlKitOcrTextRecognizer(),
) : WorksheetParsingEngine<PngWorksheetSource> {
    override fun extract(source: PngWorksheetSource, worksheet: WorksheetReference): EngineParseResult {
        val metadataTranscript = readEmbeddedOcrTranscript(source.bytes)
        val ocrResult = if (metadataTranscript != null) {
            CompilationTrace.info("Parser", "Using embedded OCR transcript metadata")
            metadataTranscript
        } else {
            CompilationTrace.info("Parser", "Metadata parser result: FAIL")
            CompilationTrace.info("Parser", "Was ML Kit OCR invoked? YES")
            val recognition = ocrTextRecognizer.recognizeText(source.bytes)

            recognition.getOrElse { error ->
                return EngineParseResult.Failure(ParseFailureReason.UNREADABLE_CONTENT, "Unable to extract worksheet text via OCR: ${error.message}")
            }
        }

        val transcript = ocrResult.transcript
        CompilationTrace.info("Parser", "OCR completed")
        CompilationTrace.info("Parser", "Recognized text length: ${transcript.length}")
        CompilationTrace.info("Parser", "Confidence: ${ocrResult.confidence}")
        CompilationTrace.info("Parser", "Was the OCR text passed into the semantic parser? YES")

        if (transcript.isBlank()) {
            return EngineParseResult.Failure(ParseFailureReason.UNREADABLE_CONTENT, "OCR did not extract any worksheet text.")
        }

        val confidence = ocrResult.confidence
        val parseResult = parseSemanticTranscript(transcript)

        val sectionCount = parseResult.sections.size
        val fieldCount = parseResult.sections.values.sumOf { it.size }
        CompilationTrace.info("Parser", "Semantic parser result: Sections found=$sectionCount, Fields found=$fieldCount")

        if (parseResult.sections.isEmpty() && parseResult.unknownSections.isEmpty() && parseResult.unknownFields.isEmpty()) {
            return EngineParseResult.Failure(ParseFailureReason.UNREADABLE_CONTENT, "The OCR transcript did not yield any semantic worksheet sections or recognizable semantic fields.")
        }

        val detectedWorksheetType = detectWorksheetTypeFromStructuredFields(parseResult.sectionKeys)
            ?: WorksheetType.fromRole(worksheet.role)

        val sections = parseResult.sections
            .mapValues { (sectionName, values) ->
                ParsedSection(
                    name = sectionName,
                    values = values.toSortedMap(),
                    confidence = confidence,
                    heading = parseResult.headings[sectionName],
                    metadata = mapOf(
                        "detectedWorksheetType" to detectedWorksheetType.displayName,
                        "ocrSource" to ocrResult.source,
                        "ocrConfidence" to confidence.toString(),
                        "unknownHeadings" to parseResult.unknownSections.joinToString(", ") { it.heading },
                    ),
                )
            }

        return EngineParseResult.Success(
            sections = sections,
            confidence = confidence,
            revision = "semantic-ocr-v1",
            details = mapOf(
                "ocrSource" to ocrResult.source,
                "ocrConfidence" to confidence.toString(),
            ),
            unknownSections = parseResult.unknownSections,
            unknownFields = parseResult.unknownFields,
        )
    }
}

private class MlKitOcrTextRecognizer : OcrTextRecognizer {
    override fun recognizeText(bytes: ByteArray): Result<OcrRecognitionResult> = runCatching {
        // Ensure OCR (which uses ML Kit Tasks.await) never runs on the caller thread.
        // Offload the blocking ML Kit call to a short-lived background executor.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("Unable to decode worksheet PNG for OCR.")
        CompilationTrace.info("OCR", "Bitmap loaded")

        val image = InputImage.fromBitmap(bitmap, 0)

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            CompilationTrace.info("OCR", "OCR started (background thread)")
            val callable = java.util.concurrent.Callable<com.google.mlkit.vision.text.Text> {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                // Tasks.await is blocking — running inside executor avoids main-thread calls.
                Tasks.await(recognizer.process(image), 15, TimeUnit.SECONDS)
            }

            val future = executor.submit(callable)
            val visionText = future.get(20, TimeUnit.SECONDS)
            CompilationTrace.info("OCR", "OCR completed")

            val transcript = visionText.text.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("OCR did not detect any text in the worksheet image.")

            OcrRecognitionResult(transcript = transcript, confidence = 0.8f, source = "live")
        } finally {
            executor.shutdownNow()
        }
    }
}

private data class SemanticTranscriptParseResult(
    val sections: Map<String, Map<String, String>>,
    val headings: Map<String, String>,
    val unknownSections: List<UnknownSection>,
    val unknownFields: List<UnknownField>,
    val sectionKeys: Set<String>,
)

private fun parseSemanticTranscript(transcript: String): SemanticTranscriptParseResult {
    val lines = transcript.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    var activeSection: String? = null
    val headings = mutableMapOf<String, String>()
    val unknownSections = mutableListOf<UnknownSection>()
    val unknownFields = mutableListOf<UnknownField>()
    val sectionEntries = mutableMapOf<String, MutableMap<String, String>>()

    var pendingUnknownHeading: String? = null
    var pendingUnknownText = mutableListOf<String>()
    var pendingUnknownStartLine: Int? = null
    var pendingUnknownCapturedKnownField = false

    fun finalizePendingUnknownSection() {
        if (pendingUnknownHeading != null && pendingUnknownText.isNotEmpty() && !pendingUnknownCapturedKnownField) {
            unknownSections += UnknownSection(
                heading = pendingUnknownHeading!!,
                rawText = pendingUnknownText.joinToString("\n"),
                lineRange = pendingUnknownStartLine?.let { start -> start..(start + pendingUnknownText.size - 1) },
            )
        }
        pendingUnknownHeading = null
        pendingUnknownText = mutableListOf()
        pendingUnknownStartLine = null
        pendingUnknownCapturedKnownField = false
    }

    lines.forEachIndexed { index, line ->
        val parseOutcome = parseTranscriptFieldLine(line, activeSection)
        when (parseOutcome) {
            is FieldParseOutcome.RecognizedField -> {
                if (pendingUnknownHeading != null && !pendingUnknownCapturedKnownField) {
                    // The prior unknown block was a renamed or decorative heading for a known field.
                    pendingUnknownHeading = null
                    pendingUnknownText = mutableListOf()
                    pendingUnknownStartLine = null
                }
                activeSection = parseOutcome.section
                sectionEntries.getOrPut(parseOutcome.section) { mutableMapOf() }[parseOutcome.field] = parseOutcome.value
            }
            is FieldParseOutcome.UnknownField -> {
                if (pendingUnknownHeading == null) {
                    pendingUnknownHeading = line
                    pendingUnknownStartLine = index + 1
                }
                pendingUnknownText.add(line)
                unknownFields += UnknownField(
                    rawKey = parseOutcome.rawKey,
                    rawValue = parseOutcome.rawValue,
                    lineIndex = index + 1,
                    inferredSection = parseOutcome.inferredSection,
                )
            }
            null -> {
                val parsedHeading = WorksheetSemanticGrammar.matchSectionHeading(line)
                if (parsedHeading != null) {
                    if (pendingUnknownHeading != null) finalizePendingUnknownSection()
                    activeSection = parsedHeading
                    headings.putIfAbsent(parsedHeading, line)
                } else if (looksLikeUnknownHeading(line)) {
                    if (pendingUnknownHeading != null) finalizePendingUnknownSection()
                    activeSection = null
                    pendingUnknownHeading = line
                    pendingUnknownStartLine = index + 1
                } else if (activeSection != null) {
                    val fallbackField = WorksheetSemanticGrammar.defaultFieldForSection(activeSection)
                    if (fallbackField != null) {
                        sectionEntries.getOrPut(activeSection) { mutableMapOf() }
                            .putIfAbsent(fallbackField, line)
                    } else {
                        if (pendingUnknownHeading == null) {
                            pendingUnknownHeading = line
                            pendingUnknownStartLine = index + 1
                        }
                        pendingUnknownText.add(line)
                    }
                } else {
                    if (pendingUnknownHeading == null) {
                        pendingUnknownHeading = line
                        pendingUnknownStartLine = index + 1
                    }
                    pendingUnknownText.add(line)
                }
            }
        }

        if (parseOutcome is FieldParseOutcome.RecognizedField && pendingUnknownHeading != null) {
            pendingUnknownCapturedKnownField = true
        }
    }

    if (pendingUnknownHeading != null) finalizePendingUnknownSection()

    return SemanticTranscriptParseResult(
        sections = sectionEntries,
        headings = headings,
        unknownSections = unknownSections,
        unknownFields = unknownFields,
        sectionKeys = sectionEntries.keys,
    )
}

private sealed interface FieldParseOutcome {
    data class RecognizedField(val section: String, val field: String, val value: String) : FieldParseOutcome
    data class UnknownField(val rawKey: String, val rawValue: String, val inferredSection: String?) : FieldParseOutcome
}

private fun parseTranscriptFieldLine(line: String, activeSection: String?): FieldParseOutcome? {
    val delimiterIndex = line.indexOf('=').takeIf { it >= 0 } ?: line.indexOf(':').takeIf { it >= 0 } ?: -1
    if (delimiterIndex <= 0) return null

    val rawKey = line.substring(0, delimiterIndex).trim()
    val rawValue = line.substring(delimiterIndex + 1).trim().takeIf(String::isNotBlank) ?: return null
    val sectionCandidate = rawKey.substringBefore('.', rawKey).takeIf { rawKey.contains('.') }
    val fieldCandidate = if (rawKey.contains('.')) rawKey.substringAfter('.', rawKey) else rawKey

    val field = WorksheetSemanticGrammar.matchFieldName(activeSection, rawKey)
        ?: WorksheetSemanticGrammar.matchFieldName(activeSection, fieldCandidate)
        ?: WorksheetSemanticGrammar.globalFieldMatch(rawKey)
        ?: WorksheetSemanticGrammar.globalFieldMatch(fieldCandidate)

    if (field != null) {
        val section = WorksheetSemanticGrammar.matchSectionNameForKey(sectionCandidate ?: "")
            ?: WorksheetSemanticGrammar.sectionForField(field)
        return FieldParseOutcome.RecognizedField(section, field, rawValue)
    }

    val inferredSection = WorksheetSemanticGrammar.matchSectionNameForKey(sectionCandidate ?: "")
        ?: activeSection
        ?: WorksheetSemanticGrammar.sectionForField(WorksheetSemanticGrammar.globalFieldMatch(fieldCandidate ?: "") ?: "")
        .takeIf(String::isNotBlank)

    return FieldParseOutcome.UnknownField(rawKey = rawKey, rawValue = rawValue, inferredSection = inferredSection)
}

private fun parseTranscriptLineEntry(
    line: String,
    activeSection: String?,
): Triple<String, String, String>? {
    val delimiterIndex = line.indexOf('=').takeIf { it >= 0 } ?: line.indexOf(':').takeIf { it >= 0 } ?: -1
    if (delimiterIndex > 0) {
        val rawKey = line.substring(0, delimiterIndex).trim()
        val value = line.substring(delimiterIndex + 1).trim().takeIf(String::isNotBlank) ?: return null
        val candidateSection = activeSection ?: WorksheetSemanticGrammar.matchSectionHeading(rawKey)
        val field = WorksheetSemanticGrammar.matchFieldName(candidateSection, rawKey)
            ?: WorksheetSemanticGrammar.globalFieldMatch(rawKey)
            ?: return null
        val section = when {
            candidateSection != null && WorksheetSemanticGrammar.matchFieldName(candidateSection, rawKey) != null -> candidateSection
            else -> WorksheetSemanticGrammar.sectionForField(field)
        }
        return Triple(section, field, value)
    }

    return null
}

private fun detectWorksheetTypeFromStructuredFields(fieldKeys: Set<String>): WorksheetType? {
    val sections = fieldKeys
        .mapNotNull { WorksheetSemanticGrammar.matchSectionNameForKey(it) }
        .toSet()

    return when {
        WorksheetSemanticGrammar.characterSectionSet.intersect(sections).isNotEmpty() -> WorksheetType.CHARACTER
        WorksheetSemanticGrammar.artStyleSectionSet.intersect(sections).isNotEmpty() -> WorksheetType.ART_STYLE
        WorksheetSemanticGrammar.themeSectionSet.intersect(sections).isNotEmpty() -> WorksheetType.THEME
        else -> null
    }
}

private object WorksheetSemanticGrammar {
    private data class FieldDefinition(
        val canonicalName: String,
        val aliases: List<String>,
    )

    private data class SectionDefinition(
        val canonicalName: String,
        val headingAliases: List<String>,
        val fields: List<FieldDefinition>,
    )

    private val sections = listOf(
        SectionDefinition(
            canonicalName = "identity",
            headingAliases = listOf("identity", "character", "profile", "who are you", "name"),
            fields = listOf(
                FieldDefinition("identifier", listOf("identifier", "name", "character name", "title")),
                FieldDefinition("role", listOf("role", "occupation", "profession", "class")),
                FieldDefinition("anchors", listOf("anchors", "anchor", "anchor points", "key traits", "key features")),
            ),
        ),
        SectionDefinition(
            canonicalName = "physical_traits",
            headingAliases = listOf("physical traits", "appearance", "physical", "body", "build", "features"),
            fields = listOf(
                FieldDefinition("age_presentation", listOf("age presentation", "age", "age range")),
                FieldDefinition("body_description", listOf("body description", "body", "build", "physique")),
                FieldDefinition("face_description", listOf("face description", "face", "facial features")),
                FieldDefinition("hair_description", listOf("hair description", "hair", "hairstyle", "hair style")),
                FieldDefinition("distinguishing_features", listOf("distinguishing features", "features", "marks", "scar", "scars", "distinguishing")),
            ),
        ),
        SectionDefinition(
            canonicalName = "wardrobe",
            headingAliases = listOf("wardrobe", "clothing", "costume", "outfit", "garments", "apparel"),
            fields = listOf(
                FieldDefinition("garments", listOf("garments", "outfit", "clothes", "costume", "wardrobe")),
                FieldDefinition("materials", listOf("materials", "fabric", "material")),
                FieldDefinition("accessories", listOf("accessories", "accessory", "jewelry", "props")),
                FieldDefinition("condition", listOf("condition", "wear", "state", "quality")),
            ),
        ),
        SectionDefinition(
            canonicalName = "expression_pose",
            headingAliases = listOf("expression", "pose", "gesture", "stance", "emotion"),
            fields = listOf(
                FieldDefinition("expression", listOf("expression", "mood", "emotion")),
                FieldDefinition("pose", listOf("pose", "stance", "body position", "position")),
                FieldDefinition("gesture", listOf("gesture", "action", "movement")),
            ),
        ),
        SectionDefinition(
            canonicalName = "narrative",
            headingAliases = listOf("narrative", "story", "premise", "concept", "idea", "storyline"),
            fields = listOf(
                FieldDefinition("premise", listOf("premise", "concept", "story", "summary", "logline")),
            ),
        ),
        SectionDefinition(
            canonicalName = "setting",
            headingAliases = listOf("setting", "environment", "location", "scene", "world", "background"),
            fields = listOf(
                FieldDefinition("location", listOf("location", "place", "setting")),
                FieldDefinition("era", listOf("era", "time", "period", "age")),
                FieldDefinition("environmental_details", listOf("environmental details", "environment", "background details", "details")),
            ),
        ),
        SectionDefinition(
            canonicalName = "mood",
            headingAliases = listOf("mood", "atmosphere", "tone", "vibe", "feeling"),
            fields = listOf(
                FieldDefinition("mood", listOf("mood", "atmosphere", "tone", "vibe", "feeling")),
            ),
        ),
        SectionDefinition(
            canonicalName = "palette",
            headingAliases = listOf("palette", "colors", "color scheme", "hues"),
            fields = listOf(
                FieldDefinition("primary", listOf("primary", "main", "base color", "primary color")),
                FieldDefinition("secondary", listOf("secondary", "accent", "supporting color", "secondary color")),
            ),
        ),
        SectionDefinition(
            canonicalName = "medium",
            headingAliases = listOf("medium", "media", "art medium", "material library", "materials library"),
            fields = listOf(
                FieldDefinition("medium", listOf("medium", "media", "art medium")),
            ),
        ),
        SectionDefinition(
            canonicalName = "lighting",
            headingAliases = listOf("lighting", "light", "illumination", "shadows"),
            fields = listOf(
                FieldDefinition("lighting", listOf("lighting", "light", "illumination", "shadows")),
            ),
        ),
        SectionDefinition(
            canonicalName = "rendering",
            headingAliases = listOf(
                "rendering",
                "style",
                "finish",
                "visual style",
                "look",
                "morphological grammar",
                "morphological grammar overview",
                "rendering grammar",
                "rendering grammar overview",
                "style overview",
                "style identity",
                "style fingerprints",
                "style variation envelope",
                "prompt extraction layer",
                "confidence assessment",
                "failure cases",
                "certification",
            ),
            fields = listOf(
                FieldDefinition("descriptors", listOf("descriptors", "description", "style", "visual descriptors")),
                FieldDefinition("method", listOf("method", "technique", "process")),
            ),
        ),
        SectionDefinition(
            canonicalName = "composition",
            headingAliases = listOf("composition", "layout", "framing", "arrangement", "detail hierarchy", "composition behaviour"),
            fields = listOf(
                FieldDefinition("framing", listOf("framing", "frame", "crop", "composition")),
                FieldDefinition("subject_placement", listOf("subject placement", "placement", "position")),
                FieldDefinition("depth_treatment", listOf("depth treatment", "depth", "focus", "spacing")),
            ),
        ),
        SectionDefinition(
            canonicalName = "camera",
            headingAliases = listOf("camera", "lens", "perspective", "shot", "angle"),
            fields = listOf(
                FieldDefinition("perspective", listOf("perspective", "view", "angle")),
                FieldDefinition("lens", listOf("lens", "focal length", "lens type")),
                FieldDefinition("angle", listOf("angle", "camera angle", "view angle")),
            ),
        ),
        SectionDefinition(
            canonicalName = "continuity",
            headingAliases = listOf("continuity", "motifs", "recurring", "prohibited", "drift"),
            fields = listOf(
                FieldDefinition("recurring_motifs", listOf("recurring motifs", "motifs", "repeating elements")),
                FieldDefinition("prohibited_drift", listOf("prohibited drift", "avoid", "forbidden", "do not")),
            ),
        ),
    )

    val characterSectionSet = setOf("identity", "physical_traits", "wardrobe", "expression_pose")
    val themeSectionSet = setOf("narrative", "setting", "mood", "palette", "continuity")
    val artStyleSectionSet = setOf("medium", "lighting", "rendering", "composition", "camera", "palette")

    private val canonicalSectionByAlias: Map<String, String> = sections
        .flatMap { section -> section.headingAliases.map { normalizeText(it) to section.canonicalName } }
        .toMap()

    private val canonicalFieldByAlias: Map<String, Pair<String, String>> = sections
        .flatMap { section ->
            section.fields.flatMap { field ->
                field.aliases.map { alias -> normalizeText(alias) to (section.canonicalName to field.canonicalName) }
            }
        }
        .toMap()

    private val sectionFields: Map<String, Map<String, FieldDefinition>> = sections
        .associate { section -> section.canonicalName to section.fields.associateBy { it.canonicalName } }

    private val fieldToSection: Map<String, String> = sections
        .flatMap { section -> section.fields.map { it.canonicalName to section.canonicalName } }
        .toMap()

    fun matchSectionHeading(value: String): String? {
        val normalized = normalizeText(value)

        return canonicalSectionByAlias.entries
            .firstOrNull { (alias, _) -> normalized == alias || normalized.containsPhrase(alias) }
            ?.value
    }

    fun matchSectionNameForKey(rawKey: String): String? {
        val normalized = normalizeText(rawKey.removePrefix(FieldPrefix))
        return canonicalSectionByAlias.entries
            .firstOrNull { (alias, _) -> normalized == alias || normalized.containsPhrase(alias) }
            ?.value
    }

    fun matchFieldName(section: String?, rawKey: String): String? {
        val normalized = normalizeText(rawKey)
        section?.let {
            val fields = sectionFields[it] ?: emptyMap()
            fields.forEach { (field, definition) ->
                if (normalizeText(field) == normalized || definition.aliases.any { alias -> normalized == normalizeText(alias) }) {
                    return field
                }
            }

            return fields.entries
                .flatMap { (field, definition) ->
                    definition.aliases.mapNotNull { alias ->
                        normalizeText(alias).takeIf { normalized.containsPhrase(alias) }?.tokens()?.size?.let { aliasTokenCount ->
                            aliasTokenCount to field
                        }
                    }
                }
                .maxByOrNull { it.first }
                ?.second
        }

        return globalFieldMatch(rawKey)
    }

    fun globalFieldMatch(rawKey: String): String? {
        val normalized = normalizeText(rawKey)
        val fullMatch = canonicalFieldByAlias.entries
            .firstOrNull { (alias, _) -> normalized == alias }
            ?.value
            ?.second
        if (fullMatch != null) return fullMatch

        return canonicalFieldByAlias.entries
            .mapNotNull { (alias, sectionField) ->
                normalizeText(alias).takeIf { normalized.containsPhrase(alias) }?.tokens()?.size?.let { aliasTokenCount ->
                    aliasTokenCount to sectionField
                }
            }
            .maxByOrNull { it.first }
            ?.second
            ?.second
    }

    fun sectionForField(field: String): String = fieldToSection[field] ?: ""

    fun defaultFieldForSection(section: String): String? = when (section) {
        "identity" -> "identifier"
        "physical_traits" -> "body_description"
        "wardrobe" -> "garments"
        "expression_pose" -> "expression"
        "narrative" -> "premise"
        "setting" -> "environmental_details"
        "mood" -> "mood"
        "palette" -> "primary"
        "medium" -> "medium"
        "lighting" -> "lighting"
        "rendering" -> "descriptors"
        "composition" -> "framing"
        "camera" -> "perspective"
        "continuity" -> "recurring_motifs"
        else -> null
    }
}

private fun parseStructuredMetadataEntry(key: String, value: String): StructuredField? {
    val normalizedKey = key.lowercase(Locale.US)
    if (!normalizedKey.startsWith(FieldPrefix)) return null
    val remainder = normalizedKey.removePrefix(FieldPrefix)
    val sectionCandidate: String?
    val fieldCandidate: String?

    if (remainder.contains('.')) {
        val parts = remainder.split('.', limit = 2)
        sectionCandidate = parts[0]
        fieldCandidate = parts[1]
    } else {
        sectionCandidate = null
        fieldCandidate = remainder
    }

    val section = WorksheetSemanticGrammar.matchSectionNameForKey(sectionCandidate ?: fieldCandidate ?: "")
        ?: WorksheetSemanticGrammar.matchSectionNameForKey(fieldCandidate ?: "")
        ?: WorksheetSemanticGrammar.sectionForField(fieldCandidate ?: "")
    val field = WorksheetSemanticGrammar.matchFieldName(section.takeIf(String::isNotBlank), fieldCandidate ?: "")
        ?: WorksheetSemanticGrammar.globalFieldMatch(fieldCandidate ?: "")
        ?: return null

    return StructuredField(section = section, field = field, value = value)
}

private fun normalizeText(value: String?): String = value
    ?.lowercase(Locale.US)
    ?.replace(Regex("[^a-z0-9 ]+"), " ")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    .orEmpty()

private fun String.tokens(): List<String> = split(Regex("\\s+")).filter(String::isNotBlank)

private fun String.containsPhrase(phrase: String): Boolean {
    val normalizedSource = normalizeText(this)
    val phraseTokens = normalizeText(phrase).tokens()
    if (phraseTokens.isEmpty()) return false

    val sourceTokens = normalizedSource.tokens()
    if (phraseTokens.size > sourceTokens.size) return false

    return sourceTokens.windowed(phraseTokens.size).any { it == phraseTokens }
}

private fun looksLikeUnknownHeading(value: String): Boolean {
    val words = value.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return words.size in 1..6 && words.all { word -> word.firstOrNull()?.isUpperCase() == true }
}

private fun parseConfidence(value: String?): Float = value?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.8f

private fun readEmbeddedOcrTranscript(bytes: ByteArray): OcrRecognitionResult? {
    val metadata = PngTextMetadataReader.read(bytes).getOrNull()?.canonicalMap()?.getOrNull() ?: return null
    val transcript = metadata["asterion.ocr.transcript"]?.takeIf(String::isNotBlank) ?: return null
    val confidence = parseConfidence(metadata["asterion.ocr.confidence"])
    return OcrRecognitionResult(transcript = transcript, confidence = confidence, source = "embedded")
}

private val ParseFailureReason.priority: Int
    get() = when (this) {
        ParseFailureReason.UNSUPPORTED_SOURCE -> 0
        ParseFailureReason.UNREADABLE_CONTENT -> 1
        ParseFailureReason.INVALID_LAYOUT -> 2
        ParseFailureReason.LOW_CONFIDENCE -> 3
    }

private data class StructuredField(
    val section: String,
    val field: String,
    val value: String,
)

private data class PngTextMetadata(
    val width: Int,
    val height: Int,
    val entries: List<Pair<String, String>>,
) {
    fun canonicalMap(): Result<Map<String, String>> = runCatching {
        val result = linkedMapOf<String, String>()
        entries.forEach { (key, value) ->
            val normalizedKey = key.trim().lowercase(Locale.US)
            require(normalizedKey !in result) { "The PNG contains duplicate metadata key $normalizedKey." }
            result[normalizedKey] = value.trim()
        }
        result
    }
}

private object PngTextMetadataReader {
    private val pngSignature = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

    fun read(bytes: ByteArray): Result<PngTextMetadata> = runCatching {
        require(bytes.size >= pngSignature.size && bytes.copyOfRange(0, pngSignature.size).contentEquals(pngSignature)) {
            "The selected file is not a valid PNG."
        }

        val metadata = mutableListOf<Pair<String, String>>()
        var cursor = pngSignature.size
        var reachedEnd = false
        var width = 0
        var height = 0
        while (cursor < bytes.size) {
            require(bytes.size - cursor >= 12) { "The PNG contains a truncated chunk." }
            val length = bytes.readInt(cursor)
            require(length >= 0 && length <= bytes.size - cursor - 12) { "The PNG chunk length is invalid." }
            val type = bytes.copyOfRange(cursor + 4, cursor + 8).toString(StandardCharsets.US_ASCII)
            val dataStart = cursor + 8
            val dataEnd = dataStart + length
            val data = bytes.copyOfRange(dataStart, dataEnd)
            val expectedCrc = bytes.readUnsignedInt(dataEnd)
            val actualCrc = CRC32().apply {
                update(type.toByteArray(StandardCharsets.US_ASCII))
                update(data)
            }.value
            require(expectedCrc == actualCrc) { "The PNG $type chunk failed integrity verification." }

            if (type == "IHDR") {
                require(length >= 8) { "IHDR chunk is malformed." }
                width = data.readInt(0)
                height = data.readInt(4)
            }

            parseTextChunk(type, data)?.let(metadata::add)
            cursor = dataEnd + 4
            if (type == "IEND") {
                reachedEnd = true
                break
            }
        }
        require(reachedEnd) { "The PNG is missing its end chunk." }
        PngTextMetadata(width = width, height = height, entries = metadata)
    }

    private fun parseTextChunk(type: String, data: ByteArray): Pair<String, String>? = when (type) {
        "tEXt" -> data.readTextChunk(StandardCharsets.ISO_8859_1)
        "zTXt" -> data.readCompressedTextChunk()
        "iTXt" -> data.readInternationalTextChunk()
        else -> null
    }
}

private fun ByteArray.readTextChunk(charset: java.nio.charset.Charset): Pair<String, String> {
    val separator = indexOf(0)
    require(separator > 0) { "A PNG text chunk is malformed." }
    return String(copyOfRange(0, separator), StandardCharsets.ISO_8859_1) to
        String(copyOfRange(separator + 1, size), charset)
}

private fun ByteArray.readCompressedTextChunk(): Pair<String, String> {
    val separator = indexOf(0)
    require(separator > 0 && separator + 1 < size && this[separator + 1].toInt() == 0) { "A compressed PNG text chunk is malformed." }
    val value = InflaterInputStream(ByteArrayInputStream(copyOfRange(separator + 2, size))).readBytes()
    return String(copyOfRange(0, separator), StandardCharsets.ISO_8859_1) to String(value, StandardCharsets.ISO_8859_1)
}

private fun ByteArray.readInternationalTextChunk(): Pair<String, String> {
    val keywordEnd = indexOf(0)
    require(keywordEnd > 0 && keywordEnd + 3 <= size) { "An international PNG text chunk is malformed." }
    val compressionFlag = this[keywordEnd + 1].toInt()
    val compressionMethod = this[keywordEnd + 2].toInt()
    require(compressionFlag in 0..1 && compressionMethod == 0) { "An international PNG text chunk uses an unsupported compression mode." }
    val languageStart = keywordEnd + 3
    val languageEnd = indexOf(0, languageStart)
    require(languageEnd >= languageStart) { "An international PNG text chunk has no language separator." }
    val translatedStart = languageEnd + 1
    val translatedEnd = indexOf(0, translatedStart)
    require(translatedEnd >= translatedStart) { "An international PNG text chunk has no translated-keyword separator." }
    val textBytes = copyOfRange(translatedEnd + 1, size)
    val value = if (compressionFlag == 1) {
        InflaterInputStream(ByteArrayInputStream(textBytes)).readBytes()
    } else {
        textBytes
    }
    return String(copyOfRange(0, keywordEnd), StandardCharsets.ISO_8859_1) to String(value, StandardCharsets.UTF_8)
}

private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readUnsignedInt(offset: Int): Long = readInt(offset).toLong() and 0xFFFF_FFFFL

private fun ByteArray.indexOf(value: Byte, startIndex: Int = 0): Int {
    for (index in startIndex until size) {
        if (this[index] == value) return index
    }
    return -1
}

private fun WorksheetRole.wireName(): String = when (this) {
    WorksheetRole.CHARACTER -> "character"
    WorksheetRole.THEME -> "theme"
    WorksheetRole.ART_STYLE -> "art_style"
}

