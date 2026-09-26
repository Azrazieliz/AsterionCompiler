package com.asterion.compiler.compiler

import com.asterion.compiler.profiles.CompilationStrategy
import com.asterion.compiler.service.InMemoryPromptRefinementMemory
import com.asterion.compiler.service.PromptRefinementMemory
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.specification.InternalVisualSpecification
import com.asterion.compiler.validation.CompilationValidationSuite
import com.asterion.compiler.validation.ValidationPipeline
import com.asterion.compiler.validation.ValidationReport

data class CompilationRequest(
    val specification: InternalVisualSpecification,
    val strategy: CompilationStrategy,
) {
}

data class PromptCompilation(
    val specification: InternalVisualSpecification,
    val strategy: CompilationStrategy,
    val passes: List<PromptPass>,
    val graph: CompilationGraph? = null,
    val validationReport: ValidationReport = ValidationReport(),
    val status: CompilationStatus = CompilationStatus.BLOCKED,
) {
}

enum class CompilationStatus {
    BLOCKED,
    GENERATED,
}

interface PromptCompiler {
    fun compile(request: CompilationRequest): PromptCompilation
    fun generate(graph: CompilationGraph, strategy: CompilationStrategy): List<PromptPass>
}

class DeterministicPromptCompiler(
    private val graphBuilder: CompilationGraphBuilder = DeterministicCompilationGraphBuilder(),
    private val passAllocator: DeterministicPassAllocator = DeterministicPassAllocator(),
    private val visualTranslator: VisualTranslationEngine = VisualTranslationEngine(),
    private val checkpointOptimizer: CheckpointPromptOptimizer = CheckpointPromptOptimizer(),
    private val promptGenerator: DeterministicPromptGenerator = DeterministicPromptGenerator(),
    private val validationPipeline: ValidationPipeline = ValidationPipeline(),
    private val compilationValidation: CompilationValidationSuite = CompilationValidationSuite(),
    private val refinementMemory: PromptRefinementMemory = InMemoryPromptRefinementMemory(),
) : PromptCompiler {
    override fun compile(request: CompilationRequest): PromptCompilation {
        var validationReport = validationPipeline.validateSpecification(request.specification) +
            compilationValidation.validateSpecificationCoverage(request.specification)
        if (validationReport.hasCriticalErrors) return blocked(request, validationReport)

        val graph = graphBuilder.build(request.specification)
        passAllocator.allocate(graph)
        visualTranslator.translate(graph)
        checkpointOptimizer.apply(graph, request.strategy.checkpointProfile)

        validationReport += compilationValidation.validateGraphIntegrity(graph) +
            compilationValidation.validateTraceability(request.specification, graph) +
            compilationValidation.validateInferenceDetection(graph) +
            compilationValidation.validatePassAllocation(graph) +
            compilationValidation.validateBucketVerification(graph) +
            compilationValidation.validateCheckpointCompatibility(graph, request.strategy.checkpointProfile)
        if (validationReport.hasCriticalErrors) return blocked(request, validationReport, graph)

        val passes = promptGenerator.generate(graph, request.strategy)
        val refined = refinementMemory.apply(
            PromptCompilation(request.specification, request.strategy, passes, graph = graph),
        )
        validationReport += compilationValidation.validatePromptIntegrity(refined.passes, request.strategy)
        return if (validationReport.hasCriticalErrors) {
            blocked(request, validationReport, graph)
        } else {
            PromptCompilation(
                specification = request.specification,
                strategy = request.strategy,
                passes = refined.passes,
                graph = graph,
                validationReport = validationReport,
                status = CompilationStatus.GENERATED,
            )
        }
    }

    override fun generate(graph: CompilationGraph, strategy: CompilationStrategy): List<PromptPass> =
        promptGenerator.generate(graph, strategy)

    private fun blocked(
        request: CompilationRequest,
        validationReport: ValidationReport,
        graph: CompilationGraph? = null,
    ): PromptCompilation = PromptCompilation(
        specification = request.specification,
        strategy = request.strategy,
        passes = emptyList(),
        graph = graph,
        validationReport = validationReport,
        status = CompilationStatus.BLOCKED,
    )
}