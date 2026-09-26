package com.asterion.compiler.compiler

import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.specification.InternalVisualSpecification
import com.asterion.compiler.specification.VisualConstraints
import com.asterion.compiler.visual.VisualBucket
import com.asterion.compiler.worksheet.WorksheetRole

enum class VisualPriority {
    CORE,
    HIGH,
    STANDARD,
    SUPPORTING,
}

enum class ReinforcementState {
    PRIMARY,
    REINFORCED,
    SUPPRESSED,
}

data class GraphNodeSource(
    val worksheetId: String,
    val specificationPath: String,
)

data class CheckpointAdjustment(
    val checkpointId: String,
    val originalWording: String,
    val optimizedWording: String,
)

class CompilationGraphNode(
    var id: String,
    var source: GraphNodeSource,
    var priority: VisualPriority,
    var visualCategory: VisualBucket,
    var passOwnership: PromptPassType? = null,
    var reinforcementState: ReinforcementState = ReinforcementState.PRIMARY,
    var checkpointAdjustments: MutableList<CheckpointAdjustment> = mutableListOf(),
    var descriptor: String,
    var translatedDescriptor: String = descriptor,
    var translationRuleIds: MutableList<String> = mutableListOf(),
)

class CompilationGraph(
    val nodes: MutableList<CompilationGraphNode>,
) {
    fun nodesFor(pass: PromptPassType): List<CompilationGraphNode> =
        nodes.filter { it.passOwnership == pass }
}

interface CompilationGraphBuilder {
    fun build(specification: InternalVisualSpecification): CompilationGraph
}

class DeterministicCompilationGraphBuilder : CompilationGraphBuilder {
    override fun build(specification: InternalVisualSpecification): CompilationGraph {
        val nodes = mutableListOf<CompilationGraphNode>()
        var sequence = 1
        fun add(
            source: GraphNodeSource,
            path: String,
            bucket: VisualBucket,
            priority: VisualPriority,
            descriptor: String,
            reinforcementState: ReinforcementState = ReinforcementState.PRIMARY,
        ) {
            descriptor.trim().takeIf(String::isNotBlank)?.let { text ->
                nodes += CompilationGraphNode(
                    id = "node-${sequence.toString().padStart(4, '0')}-${bucket.wireName}",
                    source = source.copy(specificationPath = path),
                    priority = priority,
                    visualCategory = bucket,
                    reinforcementState = reinforcementState,
                    descriptor = text,
                )
                sequence += 1
            }
        }

        specification.characters.forEachIndexed { index, character ->
            val source = specification.sourceFor(WorksheetRole.CHARACTER, index)
            val root = "characters[$index]"
            add(source, "$root.identifier", VisualBucket.IDENTITY, VisualPriority.CORE, character.identifier)
            add(source, "$root.role", VisualBucket.IDENTITY, VisualPriority.HIGH, character.role)
            character.identityAnchors.forEachIndexed { anchorIndex, anchor ->
                add(source, "$root.identityAnchors[$anchorIndex]", VisualBucket.IDENTITY, VisualPriority.HIGH, anchor)
            }
            add(source, "$root.physicalTraits.agePresentation", VisualBucket.BODY, VisualPriority.HIGH, character.physicalTraits.agePresentation)
            add(source, "$root.physicalTraits.bodyDescription", VisualBucket.BODY, VisualPriority.CORE, character.physicalTraits.bodyDescription)
            add(source, "$root.physicalTraits.faceDescription", VisualBucket.FACE, VisualPriority.CORE, character.physicalTraits.faceDescription)
            add(source, "$root.physicalTraits.hairDescription", VisualBucket.HAIR, VisualPriority.CORE, character.physicalTraits.hairDescription)
            character.physicalTraits.distinguishingFeatures.forEachIndexed { featureIndex, feature ->
                add(source, "$root.physicalTraits.distinguishingFeatures[$featureIndex]", VisualBucket.FACE, VisualPriority.HIGH, feature)
            }
            character.wardrobe.primaryGarments.forEachIndexed { garmentIndex, garment ->
                add(source, "$root.wardrobe.primaryGarments[$garmentIndex]", VisualBucket.OUTFIT, VisualPriority.CORE, garment)
            }
            character.wardrobe.materials.forEachIndexed { materialIndex, material ->
                add(source, "$root.wardrobe.materials[$materialIndex]", VisualBucket.MATERIALS, VisualPriority.HIGH, material)
            }
            character.wardrobe.accessories.forEachIndexed { accessoryIndex, accessory ->
                add(source, "$root.wardrobe.accessories[$accessoryIndex]", VisualBucket.ACCESSORIES, VisualPriority.STANDARD, accessory)
            }
            add(source, "$root.wardrobe.condition", VisualBucket.APPEARANCE_STATE, VisualPriority.STANDARD, character.wardrobe.condition)
            add(source, "$root.expressionAndPose.expression", VisualBucket.POSE, VisualPriority.HIGH, character.expressionAndPose.expression)
            add(source, "$root.expressionAndPose.pose", VisualBucket.POSE, VisualPriority.CORE, character.expressionAndPose.pose)
            add(source, "$root.expressionAndPose.gesture", VisualBucket.POSE, VisualPriority.HIGH, character.expressionAndPose.gesture)
            character.supplementalDescriptors.forEachIndexed { descriptorIndex, supplemental ->
                add(source, "$root.supplementalDescriptors[$descriptorIndex]", supplemental.bucket, VisualPriority.STANDARD, supplemental.descriptor)
            }
            addConstraints(source, "$root.constraints", character.constraints, ::add)
        }

        val themeSource = specification.sourceFor(WorksheetRole.THEME)
        add(themeSource, "theme.narrativePremise", VisualBucket.ENVIRONMENT, VisualPriority.SUPPORTING, specification.theme.narrativePremise)
        add(themeSource, "theme.location", VisualBucket.ENVIRONMENT, VisualPriority.CORE, specification.theme.location)
        add(themeSource, "theme.era", VisualBucket.ENVIRONMENT, VisualPriority.STANDARD, specification.theme.era)
        add(themeSource, "theme.mood", VisualBucket.ATMOSPHERIC_EFFECTS, VisualPriority.STANDARD, specification.theme.mood)
        specification.theme.environmentalDetails.forEachIndexed { index, detail ->
            add(themeSource, "theme.environmentalDetails[$index]", VisualBucket.ENVIRONMENT, VisualPriority.HIGH, detail)
        }
        specification.theme.palette.forEachIndexed { index, color ->
            add(themeSource, "theme.palette[$index]", VisualBucket.RENDERING, VisualPriority.STANDARD, "${color.role} ${color.name}")
        }
        specification.theme.supplementalDescriptors.forEachIndexed { index, supplemental ->
            add(themeSource, "theme.supplementalDescriptors[$index]", supplemental.bucket, VisualPriority.STANDARD, supplemental.descriptor)
        }
        addConstraints(themeSource, "theme.constraints", specification.theme.constraints, ::add)

        val artStyleSource = specification.sourceFor(WorksheetRole.ART_STYLE)
        add(artStyleSource, "artStyle.medium", VisualBucket.RENDERING, VisualPriority.HIGH, specification.artStyle.medium)
        specification.artStyle.aestheticDescriptors.forEachIndexed { index, descriptor ->
            add(artStyleSource, "artStyle.aestheticDescriptors[$index]", VisualBucket.RENDERING, VisualPriority.STANDARD, descriptor)
        }
        add(artStyleSource, "artStyle.renderingMethod", VisualBucket.RENDERING, VisualPriority.CORE, specification.artStyle.renderingMethod)
        add(artStyleSource, "artStyle.lighting", VisualBucket.LIGHTING, VisualPriority.CORE, specification.artStyle.lighting)
        add(artStyleSource, "artStyle.composition.framing", VisualBucket.COMPOSITION, VisualPriority.CORE, specification.artStyle.composition.framing)
        add(artStyleSource, "artStyle.composition.subjectPlacement", VisualBucket.COMPOSITION, VisualPriority.HIGH, specification.artStyle.composition.subjectPlacement)
        add(artStyleSource, "artStyle.composition.depthTreatment", VisualBucket.COMPOSITION, VisualPriority.HIGH, specification.artStyle.composition.depthTreatment)
        add(artStyleSource, "artStyle.camera.perspective", VisualBucket.PERSPECTIVE, VisualPriority.CORE, specification.artStyle.camera.perspective)
        add(artStyleSource, "artStyle.camera.lens", VisualBucket.CAMERA, VisualPriority.HIGH, specification.artStyle.camera.lens)
        add(artStyleSource, "artStyle.camera.angle", VisualBucket.CAMERA, VisualPriority.HIGH, specification.artStyle.camera.angle)
        specification.artStyle.supplementalDescriptors.forEachIndexed { index, supplemental ->
            add(artStyleSource, "artStyle.supplementalDescriptors[$index]", supplemental.bucket, VisualPriority.STANDARD, supplemental.descriptor)
        }
        addConstraints(artStyleSource, "artStyle.constraints", specification.artStyle.constraints, ::add)

        specification.continuity.recurringMotifs.forEachIndexed { index, motif ->
            add(themeSource, "continuity.recurringMotifs[$index]", VisualBucket.ATMOSPHERIC_EFFECTS, VisualPriority.SUPPORTING, motif, ReinforcementState.REINFORCED)
        }
        specification.continuity.prohibitedDrift.forEachIndexed { index, descriptor ->
            add(themeSource, "continuity.prohibitedDrift[$index]", VisualBucket.APPEARANCE_STATE, VisualPriority.STANDARD, descriptor, ReinforcementState.SUPPRESSED)
        }
        addConstraints(themeSource, "continuity.constraints", specification.continuity.constraints, ::add)

        return CompilationGraph(nodes)
    }

    private fun addConstraints(
        source: GraphNodeSource,
        path: String,
        constraints: VisualConstraints,
        add: (GraphNodeSource, String, VisualBucket, VisualPriority, String, ReinforcementState) -> Unit,
    ) {
        constraints.requiredTerms.sorted().forEachIndexed { index, term ->
            add(source, "$path.requiredTerms[$index]", VisualBucket.VISIBILITY, VisualPriority.HIGH, term, ReinforcementState.REINFORCED)
        }
        constraints.forbiddenTerms.sorted().forEachIndexed { index, term ->
            add(source, "$path.forbiddenTerms[$index]", VisualBucket.VISIBILITY, VisualPriority.HIGH, term, ReinforcementState.SUPPRESSED)
        }
    }
}

class DeterministicPassAllocator {
    fun allocate(graph: CompilationGraph): CompilationGraph {
        graph.nodes.forEach { node ->
            node.passOwnership = expectedOwner(node.visualCategory)
        }
        return graph
    }

    fun expectedOwner(bucket: VisualBucket): PromptPassType = when (bucket) {
        VisualBucket.IDENTITY,
        VisualBucket.FACE,
        VisualBucket.HAIR,
        VisualBucket.EYES,
        VisualBucket.BODY,
        VisualBucket.POSE,
        VisualBucket.COMPOSITION,
        VisualBucket.CAMERA,
        VisualBucket.PERSPECTIVE,
        VisualBucket.VISIBILITY,
        VisualBucket.APPEARANCE_STATE,
        -> PromptPassType.FOUNDATION

        VisualBucket.SKIN,
        VisualBucket.SPECIES,
        VisualBucket.OUTFIT,
        VisualBucket.ACCESSORIES,
        VisualBucket.WEAPONS,
        VisualBucket.MATERIALS,
        VisualBucket.LIQUIDS,
        -> PromptPassType.MATERIAL

        VisualBucket.ENVIRONMENT,
        VisualBucket.ARCHITECTURE,
        VisualBucket.LIGHTING,
        VisualBucket.ATMOSPHERIC_EFFECTS,
        -> PromptPassType.SCENE

        VisualBucket.RENDERING,
        VisualBucket.CHECKPOINT_OPTIMIZATIONS,
        -> PromptPassType.RENDERING
    }
}

private fun InternalVisualSpecification.sourceFor(role: WorksheetRole, index: Int = 0): GraphNodeSource {
    val roleSourceIds = provenance.sourceSheetIdsByRole[role].orEmpty()
    val worksheetId = roleSourceIds.getOrNull(index)
        ?: roleSourceIds.firstOrNull()
        ?: provenance.sourceSheetIds.firstOrNull()
        ?: "untraced"
    return GraphNodeSource(worksheetId, "")
}