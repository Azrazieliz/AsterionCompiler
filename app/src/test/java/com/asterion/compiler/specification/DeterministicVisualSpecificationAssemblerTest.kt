package com.asterion.compiler.specification

import com.asterion.compiler.worksheet.ParsedSection
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.ParserMetadata
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicVisualSpecificationAssemblerTest {
    private val assembler = DeterministicVisualSpecificationAssembler()

    @Test
    fun `assembler maps structured worksheets into a traceable specification`() {
        val result = assembler.assemble(listOf(characterSheet(), themeSheet(), artStyleSheet()))

        assertTrue(result is VisualSpecificationAssembly.Success)
        val specification = (result as VisualSpecificationAssembly.Success).specification
        assertEquals("Ilyra", specification.characters.single().identifier)
        assertEquals("silver brooch", specification.characters.single().supplementalDescriptors.single().descriptor)
        assertEquals(listOf("art", "character", "theme"), specification.provenance.sourceSheetIds)
        assertEquals("wide frame", specification.artStyle.composition.framing)
    }

    private fun characterSheet(): ParsedWorksheet = parsed(
        id = "character",
        role = WorksheetRole.CHARACTER,
        sections = mapOf(
            "identity" to mapOf("identifier" to "Ilyra", "role" to "archivist", "anchors" to "white braid|scar"),
            "physical_traits" to mapOf(
                "age_presentation" to "adult",
                "body_description" to "lean build",
                "face_description" to "angular face",
                "hair_description" to "white braid",
                "distinguishing_features" to "scar",
            ),
            "wardrobe" to mapOf(
                "garments" to "long coat",
                "materials" to "wool",
                "accessories" to "brooch",
                "condition" to "weathered",
            ),
            "expression_pose" to mapOf("expression" to "focused", "pose" to "standing", "gesture" to "holding a book"),
            "accessories" to mapOf("primary" to "silver brooch"),
        ),
    )

    private fun themeSheet(): ParsedWorksheet = parsed(
        id = "theme",
        role = WorksheetRole.THEME,
        sections = mapOf(
            "narrative" to mapOf("premise" to "archive exploration"),
            "setting" to mapOf("location" to "library", "era" to "late industrial", "environmental_details" to "dusty shelves"),
            "mood" to mapOf("mood" to "quiet"),
            "palette" to mapOf("primary" to "amber"),
        ),
    )

    private fun artStyleSheet(): ParsedWorksheet = parsed(
        id = "art",
        role = WorksheetRole.ART_STYLE,
        sections = mapOf(
            "medium" to mapOf("medium" to "digital painting"),
            "rendering" to mapOf("descriptors" to "textured", "method" to "painterly"),
            "lighting" to mapOf("lighting" to "window light"),
            "composition" to mapOf("framing" to "wide frame", "subject_placement" to "centered", "depth_treatment" to "deep focus"),
            "camera" to mapOf("perspective" to "eye level", "lens" to "50mm", "angle" to "front angle"),
        ),
    )

    private fun parsed(
        id: String,
        role: WorksheetRole,
        sections: Map<String, Map<String, String>>,
    ): ParsedWorksheet = ParsedWorksheet(
        worksheet = WorksheetReference(id, role, "$id.png", "content://worksheets/$id", "image/png"),
        sections = sections.mapValues { (name, values) -> ParsedSection(name, values, 1f) },
        confidence = 1f,
        parserMetadata = ParserMetadata("FIXED_LAYOUT", "asterion-worksheet-v1"),
    )
}