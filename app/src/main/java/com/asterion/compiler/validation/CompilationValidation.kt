package com.asterion.compiler.validation

import com.asterion.compiler.compiler.CompilationGraph
import com.asterion.compiler.compiler.DeterministicPassAllocator
import com.asterion.compiler.profiles.CheckpointProfile
import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.profiles.CompilationStrategy
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.specification.InternalVisualSpecification

class CompilationValidationSuite(
    private val allocator: DeterministicPassAllocator = DeterministicPassAllocator(),
) {
    fun validateSpecificationCoverage(specification: InternalVisualSpecification): ValidationReport {
        val missingPaths = mutableListOf<String>()
        if (specification.characters.isEmpty()) missingPaths += "characters"
        specification.characters.forEachIndexed { index, character ->
            val root = "characters[$index]"
            if (character.identifier.isBlank()) missingPaths += "$root.identifier"
            if (character.role.isBlank()) missingPaths += "$root.role"
            if (character.physicalTraits.bodyDescription.isBlank()) missingPaths += "$root.physicalTraits.bodyDescription"
            if (character.physicalTraits.faceDescription.isBlank()) missingPaths += "$root.physicalTraits.faceDescription"
            if (character.physicalTraits.hairDescription.isBlank()) missingPaths += "$root.physicalTraits.hairDescription"
            if (character.expressionAndPose.pose.isBlank()) missingPaths += "$root.expressionAndPose.pose"
            if (character.wardrobe.primaryGarments.isEmpty()) missingPaths += "$root.wardrobe.primaryGarments"
        }
        if (specification.theme.location.isBlank()) missingPaths += "theme.location"
        if (specification.theme.environmentalDetails.isEmpty()) missingPaths += "theme.environmentalDetails"
        if (specification.artStyle.lighting.isBlank()) missingPaths += "artStyle.lighting"
        if (specification.artStyle.renderingMethod.isBlank()) missingPaths += "artStyle.renderingMethod"
        if (specification.artStyle.composition.framing.isBlank()) missingPaths += "artStyle.composition.framing"
        if (specification.artStyle.camera.perspective.isBlank()) missingPaths += "artStyle.camera.perspective"

        return ValidationReport(missingPaths.map { path ->
            criticalIssue("MISSING_SPECIFICATION_COVERAGE", "The Internal Visual Specification is missing $path.")
        })
    }

    fun validateGraphIntegrity(graph: CompilationGraph): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (graph.nodes.isEmpty()) issues += criticalIssue("EMPTY_COMPILATION_GRAPH", "The Compilation Graph has no visual nodes.")
        graph.nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { id ->
            issues += criticalIssue("DUPLICATE_GRAPH_NODE", "The Compilation Graph contains duplicate node $id.")
        }
        graph.nodes.forEach { node ->
            if (node.descriptor.isBlank()) issues += criticalIssue("EMPTY_GRAPH_DESCRIPTOR", "Graph node ${node.id} has no descriptor.")
            if (node.source.worksheetId.isBlank() || node.source.worksheetId == "untraced") {
                issues += criticalIssue("UNTRACED_GRAPH_NODE", "Graph node ${node.id} has no source worksheet.")
            }
            if (node.source.specificationPath.isBlank()) {
                issues += criticalIssue("MISSING_GRAPH_SPECIFICATION_PATH", "Graph node ${node.id} has no specification path.")
            }
            if (node.passOwnership == null) issues += criticalIssue("UNALLOCATED_GRAPH_NODE", "Graph node ${node.id} has no pass owner.")
        }
        return ValidationReport(issues)
    }

    fun validateTraceability(specification: InternalVisualSpecification, graph: CompilationGraph): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (specification.provenance.sourceSheetIds.isEmpty()) {
            issues += criticalIssue("MISSING_SPECIFICATION_PROVENANCE", "The Internal Visual Specification has no source worksheet IDs.")
        }
        if (specification.provenance.parserStrategies.isEmpty()) {
            issues += criticalIssue("MISSING_PARSER_PROVENANCE", "The Internal Visual Specification has no parser strategy provenance.")
        }
        graph.nodes.filterNot { it.source.worksheetId in specification.provenance.sourceSheetIds }.forEach { node ->
            issues += criticalIssue("INVALID_GRAPH_TRACEABILITY", "Graph node ${node.id} does not reference a selected worksheet.")
        }
        return ValidationReport(issues)
    }

    fun validateInferenceDetection(graph: CompilationGraph): ValidationReport = ValidationReport(
        graph.nodes
            .filter { it.translatedDescriptor != it.descriptor && it.translationRuleIds.isEmpty() }
            .map { node -> criticalIssue("UNTRACED_TRANSLATION", "Graph node ${node.id} has wording not produced by a lexical optimization rule.") },
    )

    fun validatePassAllocation(graph: CompilationGraph): ValidationReport = ValidationReport(
        graph.nodes
            .filter { node -> node.passOwnership != allocator.expectedOwner(node.visualCategory) }
            .map { node -> criticalIssue("INVALID_PASS_ALLOCATION", "Graph node ${node.id} is not owned by its deterministic pass.") },
    )

    fun validateBucketVerification(graph: CompilationGraph): ValidationReport = ValidationReport(
        graph.nodes
            .filter { it.visualCategory.wireName.isBlank() }
            .map { node -> criticalIssue("UNDEFINED_VISUAL_BUCKET", "Graph node ${node.id} has no visual bucket.") },
    )

    fun validateCheckpointCompatibility(graph: CompilationGraph, checkpoint: CheckpointProfile): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (EmbeddedProfiles.checkpoints.none { it.id == checkpoint.id }) {
            issues += criticalIssue("UNSUPPORTED_CHECKPOINT", "${checkpoint.displayName} is not an embedded checkpoint profile.")
        }
        graph.nodes.flatMap { it.checkpointAdjustments }.filterNot { it.checkpointId == checkpoint.id }.forEach {
            issues += criticalIssue("CHECKPOINT_ADJUSTMENT_MISMATCH", "A graph node contains an adjustment for a different checkpoint.")
        }
        return ValidationReport(issues)
    }

    fun validatePromptIntegrity(passes: List<PromptPass>, strategy: CompilationStrategy): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()
        if (passes.map { it.type } != strategy.passOrder) {
            issues += criticalIssue("PROMPT_PASS_ORDER", "Generated prompt passes do not match the strategy order.")
        }
        passes.forEach { pass ->
            if (pass.generationStatus != PromptGenerationStatus.GENERATED || pass.positivePrompt.isBlank()) {
                issues += criticalIssue("INCOMPLETE_PROMPT_PASS", "The ${pass.type.displayName} pass was not generated.")
            }
            if (pass.positivePrompt.endsWith('.') || pass.positivePrompt.contains("..")) {
                issues += criticalIssue("INVALID_PROMPT_SYNTAX", "The ${pass.type.displayName} positive prompt does not use prompt syntax.")
            }
            if (pass.negativePrompt.duplicateTerms().isNotEmpty()) {
                issues += criticalIssue("REDUNDANT_NEGATIVE_PROMPT", "The ${pass.type.displayName} negative prompt repeats terms.")
            }
        }
        return ValidationReport(issues)
    }
}

private fun criticalIssue(code: String, message: String): ValidationIssue =
    ValidationIssue(code, ValidationSeverity.CRITICAL, message)

private fun String.duplicateTerms(): Set<String> = split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
    .groupBy { it.lowercase() }
    .filterValues { it.size > 1 }
    .keys