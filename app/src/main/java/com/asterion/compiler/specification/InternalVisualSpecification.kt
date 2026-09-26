package com.asterion.compiler.specification

import com.asterion.compiler.visual.VisualBucket
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.WorksheetRole

data class InternalVisualSpecification(
    val characters: List<CharacterVisualSpecification>,
    val theme: ThemeVisualSpecification,
    val artStyle: ArtStyleVisualSpecification,
    val continuity: ContinuitySpecification,
    val provenance: SpecificationProvenance,
)

data class CharacterVisualSpecification(
    val identifier: String,
    val role: String,
    val physicalTraits: PhysicalTraits,
    val wardrobe: WardrobeSpecification,
    val expressionAndPose: ExpressionAndPoseSpecification,
    val identityAnchors: List<String>,
    val constraints: VisualConstraints = VisualConstraints(),
    val supplementalDescriptors: List<SupplementalVisualDescriptor> = emptyList(),
)

data class PhysicalTraits(
    val agePresentation: String,
    val bodyDescription: String,
    val faceDescription: String,
    val hairDescription: String,
    val distinguishingFeatures: List<String>,
)

data class WardrobeSpecification(
    val primaryGarments: List<String>,
    val materials: List<String>,
    val accessories: List<String>,
    val condition: String,
)

data class ExpressionAndPoseSpecification(
    val expression: String,
    val pose: String,
    val gesture: String,
)

data class ThemeVisualSpecification(
    val narrativePremise: String,
    val location: String,
    val era: String,
    val mood: String,
    val environmentalDetails: List<String>,
    val palette: List<ColorDirective>,
    val constraints: VisualConstraints = VisualConstraints(),
    val supplementalDescriptors: List<SupplementalVisualDescriptor> = emptyList(),
)

data class ArtStyleVisualSpecification(
    val medium: String,
    val aestheticDescriptors: List<String>,
    val renderingMethod: String,
    val lighting: String,
    val composition: CompositionDirective,
    val camera: CameraDirective,
    val constraints: VisualConstraints = VisualConstraints(),
    val supplementalDescriptors: List<SupplementalVisualDescriptor> = emptyList(),
)

data class CompositionDirective(
    val framing: String,
    val subjectPlacement: String,
    val depthTreatment: String,
)

data class CameraDirective(
    val perspective: String,
    val lens: String,
    val angle: String,
)

data class ColorDirective(
    val name: String,
    val role: String,
)

data class SupplementalVisualDescriptor(
    val bucket: VisualBucket,
    val descriptor: String,
)

data class ContinuitySpecification(
    val recurringMotifs: List<String>,
    val prohibitedDrift: List<String>,
    val constraints: VisualConstraints = VisualConstraints(),
)

data class VisualConstraints(
    val requiredTerms: Set<String> = emptySet(),
    val forbiddenTerms: Set<String> = emptySet(),
)

data class SpecificationProvenance(
    val sourceSheetIds: List<String>,
    val parserStrategies: Set<String>,
    val minimumParserConfidence: Float,
    val sourceSheetIdsByRole: Map<WorksheetRole, List<String>> = emptyMap(),
)

interface VisualSpecificationAssembler {
    fun assemble(parsedWorksheets: List<ParsedWorksheet>): VisualSpecificationAssembly
}

sealed interface VisualSpecificationAssembly {
    data class Success(val specification: InternalVisualSpecification) : VisualSpecificationAssembly

    data class Failure(val messages: List<String>) : VisualSpecificationAssembly
}