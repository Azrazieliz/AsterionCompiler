package com.asterion.compiler.compiler

import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.prompt.PromptPassType
import com.asterion.compiler.service.DeterministicPassRefinementService
import com.asterion.compiler.service.PassRefinementContext
import com.asterion.compiler.service.PassRefinementResult
import com.asterion.compiler.specification.ArtStyleVisualSpecification
import com.asterion.compiler.specification.CameraDirective
import com.asterion.compiler.specification.CharacterVisualSpecification
import com.asterion.compiler.specification.ColorDirective
import com.asterion.compiler.specification.CompositionDirective
import com.asterion.compiler.specification.ContinuitySpecification
import com.asterion.compiler.specification.ExpressionAndPoseSpecification
import com.asterion.compiler.specification.InternalVisualSpecification
import com.asterion.compiler.specification.PhysicalTraits
import com.asterion.compiler.specification.SpecificationProvenance
import com.asterion.compiler.specification.SupplementalVisualDescriptor
import com.asterion.compiler.specification.ThemeVisualSpecification
import com.asterion.compiler.specification.WardrobeSpecification
import com.asterion.compiler.visual.VisualBucket
import com.asterion.compiler.worksheet.WorksheetRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicPromptCompilerTest {
    private val compiler = DeterministicPromptCompiler()
    private val refinementService = DeterministicPassRefinementService()

    @Test
    fun `compiler produces stable allocated traceable four pass prompts`() {
        val request = CompilationRequest(specification(), EmbeddedProfiles.strategies.first())

        val first = compiler.compile(request)
        val second = compiler.compile(request)

        assertEquals(CompilationStatus.GENERATED, first.status)
        assertEquals(first.passes, second.passes)
        assertEquals(request.strategy.passOrder, first.passes.map { it.type })
        assertTrue(first.passes.all { it.positivePrompt.isNotBlank() })
        assertFalse(first.validationReport.hasCriticalErrors)
        assertTrue(first.graph!!.nodes.all { it.source.worksheetId in request.specification.provenance.sourceSheetIds })
        assertTrue(first.graph.nodes.all { it.passOwnership == DeterministicPassAllocator().expectedOwner(it.visualCategory) })
        assertTrue(first.graph.nodes.any { it.descriptor == "physiognomy" && it.translatedDescriptor == "facial features" })
        assertTrue(
            first.graph.nodes.any { node ->
                node.descriptor == "dramatic illumination" &&
                    node.translatedDescriptor == "dramatic lighting" &&
                    node.checkpointAdjustments.singleOrNull()?.checkpointId == request.strategy.checkpointProfile.id
            },
        )
        assertTrue(first.passes.first().positivePrompt.contains("facial features"))

        val mutableNode = first.graph.nodes.first()
        mutableNode.descriptor = "comment-ready descriptor"
        assertEquals("comment-ready descriptor", mutableNode.descriptor)
    }

    @Test
    fun `critical specification coverage errors block compilation`() {
        val incomplete = specification().copy(characters = emptyList())

        val result = compiler.compile(CompilationRequest(incomplete, EmbeddedProfiles.strategies.first()))

        assertEquals(CompilationStatus.BLOCKED, result.status)
        assertTrue(result.validationReport.hasCriticalErrors)
        assertTrue(result.validationReport.issues.any { it.code == "MISSING_SPECIFICATION_COVERAGE" })
    }

    @Test
    fun `comment refinement regenerates only its owned pass deterministically`() {
        val original = compiler.compile(CompilationRequest(specification(), EmbeddedProfiles.strategies.first()))
        val context = PassRefinementContext(
            targetPass = PromptPassType.SCENE,
            previousGeneratedPrompt = original.passes.single { it.type == PromptPassType.MATERIAL }.positivePrompt,
            userComment = "add environment | amber candlelight",
            refinementPurpose = "test",
            compilerState = original,
        )

        val first = refinementService.refine(context) as PassRefinementResult.Generated
        val second = refinementService.refine(context) as PassRefinementResult.Generated

        assertEquals(first.compilation.passes, second.compilation.passes)
        assertEquals(
            original.passes.filterNot { it.type == PromptPassType.SCENE },
            first.compilation.passes.filterNot { it.type == PromptPassType.SCENE },
        )
        assertTrue(
            first.compilation.passes
                .single { it.type == PromptPassType.SCENE }
                .positivePrompt
                .contains("amber candlelight"),
        )
    }

    @Test
    fun `malformed replacement comment is rejected without graph inference`() {
        val original = compiler.compile(CompilationRequest(specification(), EmbeddedProfiles.strategies.first()))
        val result = refinementService.refine(
            PassRefinementContext(
                targetPass = PromptPassType.FOUNDATION,
                previousGeneratedPrompt = null,
                userComment = "replace node-0004-hair",
                refinementPurpose = "test",
                compilerState = original,
            ),
        )

        assertTrue(result is PassRefinementResult.Rejected)
        assertTrue((result as PassRefinementResult.Rejected).validationReport.issues.any {
            it.code == "REFINEMENT_INVALID_COMMENT"
        })
    }

    private fun specification(): InternalVisualSpecification = InternalVisualSpecification(
        characters = listOf(
            CharacterVisualSpecification(
                identifier = "Ilyra",
                role = "archivist",
                physicalTraits = PhysicalTraits(
                    agePresentation = "adult",
                    bodyDescription = "lean build",
                    faceDescription = "physiognomy",
                    hairDescription = "white braid",
                    distinguishingFeatures = listOf("small cheek scar"),
                ),
                wardrobe = WardrobeSpecification(
                    primaryGarments = listOf("long coat"),
                    materials = listOf("wool fabric"),
                    accessories = listOf("silver brooch"),
                    condition = "weathered",
                ),
                expressionAndPose = ExpressionAndPoseSpecification("focused", "standing", "holding a book"),
                identityAnchors = listOf("white braid", "cheek scar"),
                supplementalDescriptors = listOf(
                    SupplementalVisualDescriptor(VisualBucket.SKIN, "pale skin"),
                    SupplementalVisualDescriptor(VisualBucket.LIQUIDS, "rainwater"),
                ),
            ),
        ),
        theme = ThemeVisualSpecification(
            narrativePremise = "archive exploration",
            location = "library interior",
            era = "late industrial",
            mood = "quiet",
            environmentalDetails = listOf("dusty shelves", "rain-streaked windows"),
            palette = listOf(ColorDirective("amber", "primary")),
            supplementalDescriptors = listOf(
                SupplementalVisualDescriptor(VisualBucket.ARCHITECTURE, "iron gallery"),
                SupplementalVisualDescriptor(VisualBucket.ATMOSPHERIC_EFFECTS, "suspended dust"),
            ),
        ),
        artStyle = ArtStyleVisualSpecification(
            medium = "digital painting",
            aestheticDescriptors = listOf("illustrative"),
            renderingMethod = "painterly rendering",
            lighting = "dramatic illumination",
            composition = CompositionDirective("wide frame", "centered subject", "deep focus"),
            camera = CameraDirective("eye level", "50mm lens", "front angle"),
            supplementalDescriptors = listOf(
                SupplementalVisualDescriptor(VisualBucket.CHECKPOINT_OPTIMIZATIONS, "high fidelity"),
            ),
        ),
        continuity = ContinuitySpecification(
            recurringMotifs = listOf("brass sigils"),
            prohibitedDrift = listOf("modern clothing"),
        ),
        provenance = SpecificationProvenance(
            sourceSheetIds = listOf("character-sheet", "theme-sheet", "art-style-sheet"),
            parserStrategies = setOf("FIXED_LAYOUT"),
            minimumParserConfidence = 1f,
            sourceSheetIdsByRole = mapOf(
                WorksheetRole.CHARACTER to listOf("character-sheet"),
                WorksheetRole.THEME to listOf("theme-sheet"),
                WorksheetRole.ART_STYLE to listOf("art-style-sheet"),
            ),
        ),
    )
}