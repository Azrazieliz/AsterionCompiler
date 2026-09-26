package com.asterion.compiler.parser

import com.asterion.compiler.worksheet.ParsedSection
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.ParserMetadata
import com.asterion.compiler.worksheet.UnknownField
import com.asterion.compiler.worksheet.UnknownSection
import com.asterion.compiler.worksheet.WorksheetReference

enum class ParserStrategy {
    OCR,
    FIXED_LAYOUT,
}

data class ParseRequest<Source>(
    val source: Source,
    val worksheet: WorksheetReference,
)

sealed interface ParseResult {
    data class Success(val worksheet: ParsedWorksheet) : ParseResult

    data class Failure(
        val worksheet: WorksheetReference,
        val reason: ParseFailureReason,
        val message: String,
    ) : ParseResult
}

enum class ParseFailureReason {
    UNSUPPORTED_SOURCE,
    LOW_CONFIDENCE,
    INVALID_LAYOUT,
    UNREADABLE_CONTENT,
}

interface WorksheetParser<Source> {
    val strategy: ParserStrategy

    fun parse(request: ParseRequest<Source>): ParseResult
}

interface WorksheetParsingEngine<Source> {
    fun extract(source: Source, worksheet: WorksheetReference): EngineParseResult
}

sealed interface EngineParseResult {
    data class Success(
        val sections: Map<String, ParsedSection>,
        val confidence: Float,
        val revision: String,
        val details: Map<String, String> = emptyMap(),
        val unknownSections: List<UnknownSection> = emptyList(),
        val unknownFields: List<UnknownField> = emptyList(),
    ) : EngineParseResult

    data class Failure(
        val reason: ParseFailureReason,
        val message: String,
    ) : EngineParseResult
}

class OcrWorksheetParser<Source>(
    private val engine: WorksheetParsingEngine<Source>,
) : WorksheetParser<Source> {
    override val strategy: ParserStrategy = ParserStrategy.OCR

    override fun parse(request: ParseRequest<Source>): ParseResult =
        engine.extract(request.source, request.worksheet).toParseResult(request.worksheet, strategy)
}

class FixedLayoutWorksheetParser<Source>(
    private val engine: WorksheetParsingEngine<Source>,
) : WorksheetParser<Source> {
    override val strategy: ParserStrategy = ParserStrategy.FIXED_LAYOUT

    override fun parse(request: ParseRequest<Source>): ParseResult =
        engine.extract(request.source, request.worksheet).toParseResult(request.worksheet, strategy)
}

class WorksheetParserRegistry<Source>(
    private val ocrParser: WorksheetParser<Source>,
    private val fixedLayoutParser: WorksheetParser<Source>,
) {
    fun parserFor(strategy: ParserStrategy): WorksheetParser<Source> = when (strategy) {
        ParserStrategy.OCR -> ocrParser
        ParserStrategy.FIXED_LAYOUT -> fixedLayoutParser
    }
}

private fun EngineParseResult.toParseResult(
    worksheet: WorksheetReference,
    strategy: ParserStrategy,
): ParseResult = when (this) {
    is EngineParseResult.Success -> ParseResult.Success(
        ParsedWorksheet(
            worksheet = worksheet,
            sections = sections,
            confidence = confidence,
            parserMetadata = ParserMetadata(
                strategyId = strategy.name,
                revision = revision,
                details = details,
            ),
            unknownSections = unknownSections,
            unknownFields = unknownFields,
        ),
    )

    is EngineParseResult.Failure -> ParseResult.Failure(
        worksheet = worksheet,
        reason = reason,
        message = message,
    )
}