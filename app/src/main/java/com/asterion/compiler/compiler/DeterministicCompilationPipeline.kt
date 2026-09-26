package com.asterion.compiler.compiler

import com.asterion.compiler.parser.DeterministicWorksheetParsingPipeline
import com.asterion.compiler.parser.ParseRequest
import com.asterion.compiler.parser.ParseResult
import com.asterion.compiler.parser.PngWorksheetSource
import com.asterion.compiler.profiles.CompilationStrategy
import com.asterion.compiler.service.InMemoryPromptRefinementMemory
import com.asterion.compiler.service.PromptRefinementMemory
import com.asterion.compiler.specification.DeterministicVisualSpecificationAssembler
import com.asterion.compiler.specification.VisualSpecificationAssembler
import com.asterion.compiler.specification.VisualSpecificationAssembly
import com.asterion.compiler.validation.ValidationIssue
import com.asterion.compiler.validation.ValidationPipeline
import com.asterion.compiler.validation.ValidationReport
import com.asterion.compiler.diagnostics.CompilationLogEntry
import com.asterion.compiler.diagnostics.CompilationTrace
import com.asterion.compiler.validation.ValidationSeverity
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.WorksheetSelection

data class WorksheetCompilationRequest(
    val selection: WorksheetSelection,
    val worksheetRequests: List<ParseRequest<PngWorksheetSource>>,
    val strategy: CompilationStrategy,
)

sealed interface WorksheetCompilationResult {
    data class Generated(
        val parsedWorksheets: List<ParsedWorksheet>,
        val compilation: PromptCompilation,
        val diagnostics: List<CompilationLogEntry>,
    ) : WorksheetCompilationResult

    data class Blocked(
        val parsedWorksheets: List<ParsedWorksheet>,
        val validationReport: ValidationReport,
        val diagnostics: List<CompilationLogEntry>,
    ) : WorksheetCompilationResult
}

class DeterministicCompilationPipeline(
    private val parser: DeterministicWorksheetParsingPipeline = DeterministicWorksheetParsingPipeline(),
    private val assembler: VisualSpecificationAssembler = DeterministicVisualSpecificationAssembler(),
    private val refinementMemory: PromptRefinementMemory = InMemoryPromptRefinementMemory(),
    private val compiler: PromptCompiler = DeterministicPromptCompiler(refinementMemory = refinementMemory),
    private val validationPipeline: ValidationPipeline = ValidationPipeline(),
) {
    fun refinementRules() = refinementMemory.list()

    fun saveRefinementRule(rule: com.asterion.compiler.service.PromptRefinementRule) = refinementMemory.upsert(rule)

    fun setRefinementRuleEnabled(id: String, enabled: Boolean) = refinementMemory.setEnabled(id, enabled)

    fun deleteRefinementRule(id: String) = refinementMemory.delete(id)

    fun reorderRefinementRules(idsInPriorityOrder: List<String>) = refinementMemory.reorder(idsInPriorityOrder)

    fun exportRefinementRules(): String = refinementMemory.export()

    fun importRefinementRules(serialized: String) = refinementMemory.import(serialized)

    fun compile(request: WorksheetCompilationRequest): WorksheetCompilationResult {
        CompilationTrace.start()
        val diagnostics = mutableListOf<CompilationLogEntry>()
        diagnostics += CompilationLogEntry.info("Pipeline", "Compilation started")
        CompilationTrace.info("Pipeline", "Entered compileWorksheetSet()")

        println("[ART_STYLE_TRACE] START compile selection=${request.selection.allSheets.map { it.id + ":" + it.role }}")
        println("[ART_STYLE_TRACE] START strategy=${request.strategy.id}")
        println("[ART_STYLE_TRACE] START hasArtStyle=${request.selection.artStyleSheet != null}")

        var validationReport = validationPipeline.validateSelection(request.selection)
        println("[ART_STYLE_TRACE] selectionValidation issues=${validationReport.issues.size} ${validationReport.countsBySeverity()} hasCritical=${validationReport.hasCriticalErrors}")
        val expectedWorksheetIds = request.selection.allSheets.map { it.id }.sorted()
        val requestedWorksheetIds = request.worksheetRequests.map { it.worksheet.id }.sorted()
        if (expectedWorksheetIds != requestedWorksheetIds) {
            val mismatchIssue = ValidationIssue(
                code = "WORKSHEET_SOURCE_MISMATCH",
                severity = ValidationSeverity.CRITICAL,
                message = "The selected worksheets do not match the worksheet sources supplied for compilation.",
            )
            validationReport += ValidationReport(listOf(mismatchIssue))
            diagnostics += CompilationLogEntry.fail(
                "Selection",
                "Worksheet source mismatch detected",
                listOf(
                    CompilationLogEntry.info("Expected worksheet IDs", expectedWorksheetIds.joinToString(", ")),
                    CompilationLogEntry.info("Requested worksheet IDs", requestedWorksheetIds.joinToString(", ")),
                ),
            )
        } else {
            diagnostics += CompilationLogEntry.pass("Selection", "Worksheet selection validated successfully")
        }

        if (validationReport.hasCriticalErrors) {
            println("[DEBUG] Pipeline blocked before parsing due to critical selection issues: ${validationReport.issues}")
            diagnostics += CompilationTrace.end()
            diagnostics += CompilationLogEntry.fail("Pipeline", "Compilation blocked before parsing due to critical selection issues")
            return WorksheetCompilationResult.Blocked(emptyList(), validationReport, diagnostics)
        }

        diagnostics += CompilationLogEntry.info("Parser", "Parsing worksheets")
        CompilationTrace.info("Compiler", "Trying metadata parser...")
        val parseResults = parser.parseAll(request.worksheetRequests.sortedBy { it.worksheet.id })
        val parsedWorksheets = parseResults.filterIsInstance<ParseResult.Success>().map { it.worksheet }

        diagnostics += CompilationLogEntry.info("Parser", "parseAll returned ${parseResults.size} results")
        println("[ART_STYLE_TRACE] PARSE total=${parseResults.size} parsed=${parsedWorksheets.size} successful=${parseResults.count { it is ParseResult.Success }} failed=${parseResults.count { it is ParseResult.Failure }} unknownSections=${parsedWorksheets.sumOf { it.unknownSections.size }} unknownFields=${parsedWorksheets.sumOf { it.unknownFields.size }}")

        parseResults.forEach { result ->
            when (result) {
                is ParseResult.Success -> {
                    val parsed = result.worksheet
                    val display = parsed.worksheet.displayName
                    diagnostics += CompilationLogEntry.pass(
                        "Parsing $display",
                        "Parsed using ${parsed.parserMetadata.strategyId} with confidence ${parsed.confidence}",
                        parsed.parserMetadata.details.entries.map { (key, value) ->
                            CompilationLogEntry.info(key, value)
                        },
                    )
                }
                is ParseResult.Failure -> {
                    val display = result.worksheet.displayName
                    diagnostics += CompilationLogEntry.fail(
                        "Parsing $display",
                        result.message,
                        listOf(CompilationLogEntry.info("Failure reason", result.reason.name)),
                    )
                }
            }
        }

        val parseIssues = parseResults.filterIsInstance<ParseResult.Failure>().map { failure ->
            ValidationIssue(
                code = "PARSER_${failure.reason.name}",
                severity = ValidationSeverity.CRITICAL,
                message = failure.message,
                worksheetId = failure.worksheet.id,
            )
        }
        validationReport += ValidationReport(parseIssues) + validationPipeline.validateParsedWorksheets(parsedWorksheets)
        println("[ART_STYLE_TRACE] parsedWorksheetValidation issues=${validationReport.issues.size} ${validationReport.countsBySeverity()}")
        if (validationReport.issues.isNotEmpty()) {
            diagnostics += CompilationLogEntry.info("Validation", "Applying validation rules")
            validationReport.issues.forEach { issue ->
                diagnostics += when (issue.severity) {
                    ValidationSeverity.CRITICAL -> CompilationLogEntry.fail("Validation issue", "${issue.code}: ${issue.message}")
                    ValidationSeverity.MAJOR -> CompilationLogEntry.warning("Validation issue", "${issue.code}: ${issue.message}")
                    ValidationSeverity.MINOR, ValidationSeverity.INFORMATIONAL -> CompilationLogEntry.info("Validation issue", "${issue.code}: ${issue.message}")
                }
            }
        } else {
            diagnostics += CompilationLogEntry.pass("Validation", "No validation issues detected")
        }

        if (validationReport.hasCriticalErrors) {
            println("[DEBUG] Pipeline blocked after validation due to critical issues: ${validationReport.issues}")
            diagnostics += CompilationTrace.end()
            diagnostics += CompilationLogEntry.fail("Pipeline", "Compilation blocked after validation due to critical issues")
            return WorksheetCompilationResult.Blocked(parsedWorksheets, validationReport, diagnostics)
        }

        diagnostics += CompilationLogEntry.info("Specification assembly", "Assembling visual specification")
        val specification = when (val assembly = assembler.assemble(parsedWorksheets)) {
            is VisualSpecificationAssembly.Success -> {
                diagnostics += CompilationLogEntry.pass("Specification assembly", "Specification assembled successfully")
                assembly.specification
            }
            is VisualSpecificationAssembly.Failure -> {
                diagnostics += CompilationTrace.end()
                diagnostics += CompilationLogEntry.fail(
                    "Specification assembly",
                    "Assembly failed",
                    assembly.messages.map { message -> CompilationLogEntry.info("Assembly message", message) },
                )
                validationReport += ValidationReport(
                    assembly.messages.map { message ->
                        ValidationIssue("SPECIFICATION_ASSEMBLY_FAILED", ValidationSeverity.CRITICAL, message)
                    },
                )
                return WorksheetCompilationResult.Blocked(parsedWorksheets, validationReport, diagnostics)
            }
        }

        diagnostics += CompilationLogEntry.info("Compilation", "Generating prompts")
        val compilation = compiler.compile(CompilationRequest(specification, request.strategy))
        validationReport += compilation.validationReport
        println("[ART_STYLE_TRACE] COMPILATION validation issues=${compilation.validationReport.issues.size} ${compilation.validationReport.countsBySeverity()} status=${compilation.status}")
        compilation.validationReport.issues.forEach { issue ->
            diagnostics += when (issue.severity) {
                ValidationSeverity.CRITICAL -> CompilationLogEntry.fail("Compilation issue", "${issue.code}: ${issue.message}")
                ValidationSeverity.MAJOR -> CompilationLogEntry.warning("Compilation issue", "${issue.code}: ${issue.message}")
                ValidationSeverity.MINOR, ValidationSeverity.INFORMATIONAL -> CompilationLogEntry.info("Compilation issue", "${issue.code}: ${issue.message}")
            }
        }

        return if (compilation.status == CompilationStatus.GENERATED && !validationReport.hasCriticalErrors) {
            println("[ART_STYLE_TRACE] BLOCKING_CONDITION=none")
            println("[ART_STYLE_TRACE] GENERATION_ALLOWED=true")
            diagnostics += CompilationTrace.end()
            diagnostics += CompilationLogEntry.pass("Pipeline", "Compilation succeeded")
            WorksheetCompilationResult.Generated(parsedWorksheets, compilation.copy(validationReport = validationReport), diagnostics)
        } else {
            val blockingCodes = validationReport.issues.filter { it.severity == com.asterion.compiler.validation.ValidationSeverity.CRITICAL }.map { it.code }
            val blockReason = when {
                blockingCodes.isNotEmpty() -> blockingCodes.joinToString(",")
                compilation.status != CompilationStatus.GENERATED -> "COMPILED_BUT_BLOCKED"
                else -> "UNKNOWN"
            }
            println("[ART_STYLE_TRACE] BLOCKING_CONDITION=$blockReason")
            println("[ART_STYLE_TRACE] GENERATION_ALLOWED=false")
            println("[ART_STYLE_TRACE] FINAL validation issues=${validationReport.issues.size} ${validationReport.countsBySeverity()} status=${compilation.status}")
            diagnostics += CompilationTrace.end()
            diagnostics += CompilationLogEntry.fail("Pipeline", "Compilation blocked after prompt generation")
            WorksheetCompilationResult.Blocked(parsedWorksheets, validationReport, diagnostics)
        }
    }
}