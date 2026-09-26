package com.asterion.compiler.worksheet

enum class WorksheetRole {
    CHARACTER,
    THEME,
    ART_STYLE,
}

data class WorksheetReference(
    val id: String,
    val role: WorksheetRole,
    val displayName: String,
    val sourceIdentifier: String,
    val mimeType: String,
)

data class WorksheetSelection(
    val characterSheets: List<WorksheetReference> = emptyList(),
    val themeSheet: WorksheetReference? = null,
    val artStyleSheet: WorksheetReference? = null,
) {
    val allSheets: List<WorksheetReference>
        get() = characterSheets + listOfNotNull(themeSheet, artStyleSheet)
}

data class ParsedWorksheet(
    val worksheet: WorksheetReference,
    val sections: Map<String, ParsedSection>,
    val confidence: Float,
    val parserMetadata: ParserMetadata,
    val unknownSections: List<UnknownSection> = emptyList(),
    val unknownFields: List<UnknownField> = emptyList(),
)

data class ParsedSection(
    val name: String,
    val values: Map<String, String>,
    val confidence: Float,
    val heading: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class UnknownSection(
    val heading: String,
    val rawText: String,
    val lineRange: IntRange? = null,
)

data class UnknownField(
    val rawKey: String,
    val rawValue: String,
    val lineIndex: Int,
    val inferredSection: String? = null,
)

data class ParserMetadata(
    val strategyId: String,
    val revision: String,
    val details: Map<String, String> = emptyMap(),
)