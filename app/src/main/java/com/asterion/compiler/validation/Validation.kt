package com.asterion.compiler.validation

import com.asterion.compiler.specification.InternalVisualSpecification
import com.asterion.compiler.specification.VisualConstraints
import com.asterion.compiler.utilities.ImageFilePolicy
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.WorksheetSelection

enum class ValidationSeverity {
    CRITICAL,
    MAJOR,
    MINOR,
    INFORMATIONAL,
}

data class ValidationIssue(
    val code: String,
    val severity: ValidationSeverity,
    val message: String,
    val worksheetId: String? = null,
)

data class ValidationReport(val issues: List<ValidationIssue> = emptyList()) {
    val hasCriticalErrors: Boolean
        get() = issues.any { it.severity == ValidationSeverity.CRITICAL }

    val canCompile: Boolean
        get() = !hasCriticalErrors

    fun countsBySeverity(): Map<ValidationSeverity, Int> = issues.groupingBy { it.severity }.eachCount()

    operator fun plus(other: ValidationReport): ValidationReport = ValidationReport(issues + other.issues)
}

fun interface Validator<in Input> {
    fun validate(input: Input): ValidationReport
}

class WorksheetSelectionValidator : Validator<WorksheetSelection> {
    override fun validate(input: WorksheetSelection): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        when {
            input.characterSheets.isEmpty() -> issues += critical("MISSING_CHARACTER_SHEETS", "Select one to three Character Master Sheets.")
            input.characterSheets.size > 3 -> issues += critical("TOO_MANY_CHARACTER_SHEETS", "A maximum of three Character Master Sheets is supported.")
        }

        if (input.themeSheet == null) {
            issues += critical("MISSING_THEME_SHEET", "Select exactly one Theme Sheet.")
        }
        if (input.artStyleSheet == null) {
            issues += critical("MISSING_ART_STYLE_SHEET", "Select exactly one Art Style Sheet.")
        }

        input.allSheets
            .filterNot { ImageFilePolicy.isSupportedPng(it.displayName, it.mimeType) }
            .forEach {
                issues += critical(
                    code = "UNSUPPORTED_SHEET",
                    message = "${it.displayName} is not a PNG worksheet.",
                    worksheetId = it.id,
                )
            }

        input.allSheets
            .groupBy { it.sourceIdentifier }
            .filterValues { it.size > 1 }
            .values
            .flatten()
            .forEach {
                issues += critical(
                    code = "DUPLICATE_SHEET",
                    message = "${it.displayName} is selected more than once.",
                    worksheetId = it.id,
                )
            }

        return ValidationReport(issues)
    }
}

class ParsedWorksheetValidator(
    private val minimumConfidence: Float = 0.75f,
) : Validator<List<ParsedWorksheet>> {
    override fun validate(input: List<ParsedWorksheet>): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        input.forEach { worksheet ->
            if (worksheet.confidence < minimumConfidence) {
                issues += ValidationIssue(
                    code = "LOW_PARSER_CONFIDENCE",
                    severity = ValidationSeverity.MAJOR,
                    message = "${worksheet.worksheet.displayName} was parsed with low confidence.",
                    worksheetId = worksheet.worksheet.id,
                )
            }

            if (worksheet.unknownSections.isNotEmpty()) {
                issues += ValidationIssue(
                    code = "UNKNOWN_WORKSHEET_SECTION",
                    severity = ValidationSeverity.INFORMATIONAL,
                    message = "${worksheet.worksheet.displayName} contains ${worksheet.unknownSections.size} unknown worksheet section(s); recorded as informational telemetry only.",
                    worksheetId = worksheet.worksheet.id,
                )
            }

            if (worksheet.unknownFields.isNotEmpty()) {
                issues += ValidationIssue(
                    code = "UNKNOWN_WORKSHEET_FIELD",
                    severity = ValidationSeverity.INFORMATIONAL,
                    message = "${worksheet.worksheet.displayName} contains ${worksheet.unknownFields.size} unknown worksheet field(s); recorded as informational telemetry only.",
                    worksheetId = worksheet.worksheet.id,
                )
            }

        }

        return ValidationReport(issues)
    }

}

class VisualSpecificationValidator : Validator<InternalVisualSpecification> {
    override fun validate(input: InternalVisualSpecification): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        val duplicateCharacters = input.characters.groupBy { it.identifier.trim().lowercase() }
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.size > 1 }

        duplicateCharacters.keys.forEach { identifier ->
            issues += ValidationIssue(
                code = "CONFLICTING_CHARACTER_SPECIFICATION",
                severity = ValidationSeverity.MAJOR,
                message = "Character identifier $identifier appears in multiple specifications.",
            )
        }

        val constraints = input.characters.map { it.constraints } + listOf(
            input.theme.constraints,
            input.artStyle.constraints,
            input.continuity.constraints,
        )
        conflictingTerms(constraints).forEach { term ->
            issues += ValidationIssue(
                code = "CONFLICTING_SPECIFICATION",
                severity = ValidationSeverity.CRITICAL,
                message = "$term is both required and forbidden.",
            )
        }

        return ValidationReport(issues)
    }

    private fun conflictingTerms(constraints: List<VisualConstraints>): Set<String> {
        val required = constraints.flatMap { it.requiredTerms }.map { it.lowercase() }.toSet()
        val forbidden = constraints.flatMap { it.forbiddenTerms }.map { it.lowercase() }.toSet()
        return required intersect forbidden
    }
}

class ValidationPipeline(
    private val selectionValidator: Validator<WorksheetSelection> = WorksheetSelectionValidator(),
    private val parsedWorksheetValidator: Validator<List<ParsedWorksheet>> = ParsedWorksheetValidator(),
    private val specificationValidator: Validator<InternalVisualSpecification> = VisualSpecificationValidator(),
) {
    fun validateSelection(selection: WorksheetSelection): ValidationReport = selectionValidator.validate(selection)

    fun validateParsedWorksheets(parsedWorksheets: List<ParsedWorksheet>): ValidationReport =
        parsedWorksheetValidator.validate(parsedWorksheets)

    fun validateSpecification(specification: InternalVisualSpecification): ValidationReport =
        specificationValidator.validate(specification)
}

private fun critical(
    code: String,
    message: String,
    worksheetId: String? = null,
): ValidationIssue = ValidationIssue(code, ValidationSeverity.CRITICAL, message, worksheetId)