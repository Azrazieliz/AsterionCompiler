package com.asterion.compiler.service

import com.asterion.compiler.compiler.CheckpointPromptOptimizer
import com.asterion.compiler.compiler.CompilationGraph
import com.asterion.compiler.compiler.CompilationGraphNode
import com.asterion.compiler.compiler.CompilationStatus
import com.asterion.compiler.compiler.DeterministicPassAllocator
import com.asterion.compiler.compiler.DeterministicPromptCompiler
import com.asterion.compiler.compiler.GraphNodeSource
import com.asterion.compiler.compiler.PromptCompiler
import com.asterion.compiler.compiler.PromptCompilation
import com.asterion.compiler.compiler.ReinforcementState
import com.asterion.compiler.compiler.VisualPriority
import com.asterion.compiler.compiler.VisualTranslationEngine
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.validation.CompilationValidationSuite
import com.asterion.compiler.validation.ValidationIssue
import com.asterion.compiler.validation.ValidationReport
import com.asterion.compiler.validation.ValidationSeverity
import com.asterion.compiler.visual.VisualBucket
import java.util.Locale

data class PassRefinementContext(
    val targetPass: PromptPassType,
    val previousGeneratedPrompt: String?,
    val userComment: String,
    val refinementPurpose: String,
    val compilerState: PromptCompilation,
    val persistRule: Boolean = true,
)

sealed interface PassRefinementResult {
    data class Generated(
        val compilation: PromptCompilation,
        val context: PassRefinementContext,
    ) : PassRefinementResult

    data class Rejected(
        val context: PassRefinementContext,
        val validationReport: ValidationReport,
    ) : PassRefinementResult
}

class DeterministicPassRefinementService(
    private val allocator: DeterministicPassAllocator = DeterministicPassAllocator(),
    private val visualTranslator: VisualTranslationEngine = VisualTranslationEngine(),
    private val checkpointOptimizer: CheckpointPromptOptimizer = CheckpointPromptOptimizer(),
    private val promptCompiler: PromptCompiler = DeterministicPromptCompiler(),
    private val validation: CompilationValidationSuite = CompilationValidationSuite(),
    private val refinementMemory: PromptRefinementMemory = InMemoryPromptRefinementMemory(),
) {
    fun refine(context: PassRefinementContext): PassRefinementResult {
        val original = context.compilerState
        if (original.status != CompilationStatus.GENERATED || original.graph == null) {
            return rejected(context, "REFINEMENT_NO_COMPILATION", "Generate a valid compiler session before refining a pass.")
        }
        if (context.userComment.isBlank()) {
            return rejected(context, "REFINEMENT_EMPTY_COMMENT", "Enter a comment before regenerating the pass.")
        }
        val passIndex = original.strategy.passOrder.indexOf(context.targetPass)
        if (passIndex < 0) {
            return rejected(context, "REFINEMENT_UNKNOWN_PASS", "The requested pass is not part of the selected strategy.")
        }
        if (passIndex > 0 && context.previousGeneratedPrompt.isNullOrBlank()) {
            return rejected(context, "REFINEMENT_MISSING_PREVIOUS_PASS", "The preceding pass must be generated before this refinement.")
        }

        val graph = original.graph.deepCopy()
        val targetNodes = graph.nodes.filter { it.passOwnership == context.targetPass }
        if (targetNodes.isEmpty()) {
            return rejected(context, "REFINEMENT_EMPTY_STAGE", "The requested refinement stage has no graph nodes.")
        }

        val directive = RefinementDirective.parse(context.userComment, context.targetPass)
        val changeResult = directive.apply(graph, targetNodes, context)
        if (changeResult is DirectiveApplication.Rejected) {
            return rejected(context, changeResult.code, changeResult.message)
        }
        val changedNodes = (changeResult as DirectiveApplication.Applied).changedNodes
        if (context.persistRule) {
            refinementMemory.upsert(ruleForDirective(directive, targetNodes, context.targetPass))
        }
        allocator.allocate(graph)
        visualTranslator.translate(CompilationGraph(changedNodes.toMutableList()))
        checkpointOptimizer.apply(CompilationGraph(changedNodes.toMutableList()), original.strategy.checkpointProfile)

        var report = validation.validateGraphIntegrity(graph) +
            validation.validateTraceability(original.specification, graph) +
            validation.validateInferenceDetection(graph) +
            validation.validatePassAllocation(graph) +
            validation.validateBucketVerification(graph) +
            validation.validateCheckpointCompatibility(graph, original.strategy.checkpointProfile)
        if (report.hasCriticalErrors) return PassRefinementResult.Rejected(context, report)

        val regeneratedPass = promptCompiler.generate(graph, original.strategy).single { it.type == context.targetPass }
        val passes = original.passes.map { pass ->
            if (pass.type == context.targetPass) regeneratedPass else pass
        }
        val refinedCompilation = refinementMemory.apply(original.copy(passes = passes, graph = graph))
        report += validation.validatePromptIntegrity(refinedCompilation.passes, original.strategy)
        return if (report.hasCriticalErrors) {
            PassRefinementResult.Rejected(context, report)
        } else {
            PassRefinementResult.Generated(
                compilation = original.copy(
                    passes = refinedCompilation.passes,
                    graph = graph,
                    validationReport = original.validationReport + report,
                ),
                context = context,
            )
        }
    }

    private fun rejected(context: PassRefinementContext, code: String, message: String): PassRefinementResult.Rejected =
        PassRefinementResult.Rejected(
            context,
            ValidationReport(
                listOf(
                    ValidationIssue(code, ValidationSeverity.MAJOR, message),
                ),
            ),
        )

    private fun ruleForDirective(
        directive: RefinementDirective,
        targetNodes: List<CompilationGraphNode>,
        targetPass: PromptPassType,
    ): PromptRefinementRule = when (directive) {
        is RefinementDirective.Add -> PromptRefinementRule(
            type = RefinementRuleType.ADD_DESCRIPTOR,
            phrase = directive.descriptor,
            targetPass = targetPass,
        )
        is RefinementDirective.Remove -> PromptRefinementRule(
            type = RefinementRuleType.REMOVE_DESCRIPTOR,
            phrase = targetNodes.first { it.id == directive.nodeId }.descriptor,
            targetPass = targetPass,
        )
        is RefinementDirective.Replace -> PromptRefinementRule(
            type = RefinementRuleType.PREFERRED_WORDING,
            phrase = targetNodes.first { it.id == directive.nodeId }.descriptor,
            replacement = directive.replacement,
            targetPass = targetPass,
        )
        is RefinementDirective.Invalid -> PromptRefinementRule(
            type = RefinementRuleType.ADD_DESCRIPTOR,
            phrase = directive.message,
            targetPass = targetPass,
        )
    }
}

private sealed interface RefinementDirective {
    fun apply(
        graph: CompilationGraph,
        targetNodes: List<CompilationGraphNode>,
        context: PassRefinementContext,
    ): DirectiveApplication

    data class Add(val bucket: VisualBucket, val descriptor: String) : RefinementDirective {
        override fun apply(
            graph: CompilationGraph,
            targetNodes: List<CompilationGraphNode>,
            context: PassRefinementContext,
        ): DirectiveApplication {
            val seedNode = targetNodes.first()
            graph.nodes += CompilationGraphNode(
                id = "comment-${context.targetPass.name.lowercase(Locale.US)}-${graph.nodes.size + 1}",
                source = GraphNodeSource(
                    worksheetId = seedNode.source.worksheetId,
                    specificationPath = "sessionComments.${context.targetPass.name.lowercase(Locale.US)}",
                ),
                priority = VisualPriority.HIGH,
                visualCategory = bucket,
                passOwnership = context.targetPass,
                reinforcementState = ReinforcementState.REINFORCED,
                descriptor = descriptor,
            ).also { node ->
                node.translationRuleIds += "session-comment"
            }
            return DirectiveApplication.Applied(listOf(graph.nodes.last()))
        }
    }

    data class Remove(val nodeId: String) : RefinementDirective {
        override fun apply(
            graph: CompilationGraph,
            targetNodes: List<CompilationGraphNode>,
            context: PassRefinementContext,
        ): DirectiveApplication {
            val match = targetNodes.singleOrNull { it.id == nodeId }
            if (match == null) {
                return DirectiveApplication.Rejected(
                    "REFINEMENT_REMOVE_NOT_FOUND",
                    "No node in the ${context.targetPass.displayName} stage has the requested identifier.",
                )
            }
            graph.nodes.remove(match)
            return DirectiveApplication.Applied(listOf(match))
        }
    }

    data class Replace(val nodeId: String, val replacement: String) : RefinementDirective {
        override fun apply(
            graph: CompilationGraph,
            targetNodes: List<CompilationGraphNode>,
            context: PassRefinementContext,
        ): DirectiveApplication {
            val match = targetNodes.singleOrNull { it.id == nodeId }
            if (match == null) {
                return DirectiveApplication.Rejected(
                    "REFINEMENT_REPLACE_NOT_FOUND",
                    "No node in the ${context.targetPass.displayName} stage has the requested identifier.",
                )
            }
            match.descriptor = replacement
            match.translatedDescriptor = replacement
            match.translationRuleIds += "session-comment"
            match.checkpointAdjustments.clear()
            return DirectiveApplication.Applied(listOf(match))
        }
    }

    data class Invalid(val message: String) : RefinementDirective {
        override fun apply(
            graph: CompilationGraph,
            targetNodes: List<CompilationGraphNode>,
            context: PassRefinementContext,
        ): DirectiveApplication = DirectiveApplication.Rejected("REFINEMENT_INVALID_COMMENT", message)
    }

    companion object {
        fun parse(comment: String, targetPass: PromptPassType): RefinementDirective {
            val normalized = comment.trim()
            val addPrefix = "add "
            val removePrefix = "remove "
            val replacePrefix = "replace "
            return when {
                normalized.startsWith(addPrefix, ignoreCase = true) -> {
                    val content = normalized.substring(addPrefix.length).trim()
                    val separator = content.indexOf('|')
                    if (separator <= 0 || separator >= content.lastIndex) {
                        Invalid("Unable to parse the comment. Enter a free-form refinement instruction.")
                    } else {
                        val bucket = content.take(separator).trim().let(VisualBucket::fromStructuredName)
                        val descriptor = content.drop(separator + 1).trim()
                        when {
                            descriptor.isBlank() -> Invalid("Unable to parse the comment. Enter a free-form refinement instruction.")
                            bucket == null -> Invalid("Unable to parse the comment. Enter a free-form refinement instruction.")
                            DeterministicPassAllocator().expectedOwner(bucket) != targetPass ->
                                Invalid("The comment could not be applied to the requested pass.")

                            else -> Add(bucket, descriptor)
                        }
                    }
                }

                normalized.startsWith(removePrefix, ignoreCase = true) ->
                    normalized.substring(removePrefix.length).trim().takeIf(String::isNotBlank)?.let(::Remove)
                        ?: Invalid("Unable to parse the comment. Enter a free-form refinement instruction.")

                normalized.startsWith(replacePrefix, ignoreCase = true) -> {
                    val content = normalized.substring(replacePrefix.length).trim()
                    val separator = content.indexOf('|')
                    if (separator > 0 && separator < content.lastIndex - 1) {
                        Replace(content.substring(0, separator).trim(), content.substring(separator + 1).trim())
                    } else {
                        Invalid("Unable to parse the comment. Enter a free-form refinement instruction.")
                    }
                }

                else -> Add(defaultBucketFor(targetPass), normalized)
            }
        }

        private fun defaultBucketFor(targetPass: PromptPassType): VisualBucket = when (targetPass) {
            PromptPassType.FOUNDATION -> VisualBucket.IDENTITY
            PromptPassType.MATERIAL -> VisualBucket.MATERIALS
            PromptPassType.SCENE -> VisualBucket.ENVIRONMENT
            PromptPassType.RENDERING -> VisualBucket.RENDERING
        }
    }
}

private sealed interface DirectiveApplication {
    data class Applied(val changedNodes: List<CompilationGraphNode>) : DirectiveApplication

    data class Rejected(val code: String, val message: String) : DirectiveApplication
}

private fun CompilationGraph.deepCopy(): CompilationGraph = CompilationGraph(
    nodes.map { node ->
        CompilationGraphNode(
            id = node.id,
            source = node.source.copy(),
            priority = node.priority,
            visualCategory = node.visualCategory,
            passOwnership = node.passOwnership,
            reinforcementState = node.reinforcementState,
            checkpointAdjustments = node.checkpointAdjustments.toMutableList(),
            descriptor = node.descriptor,
            translatedDescriptor = node.translatedDescriptor,
            translationRuleIds = node.translationRuleIds.toMutableList(),
        )
    }.toMutableList(),
)
