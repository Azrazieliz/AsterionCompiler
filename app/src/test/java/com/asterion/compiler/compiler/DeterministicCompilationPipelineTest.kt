package com.asterion.compiler.compiler

import com.asterion.compiler.parser.ParseRequest
import com.asterion.compiler.parser.PngWorksheetSource
import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.asterion.compiler.worksheet.WorksheetSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class DeterministicCompilationPipelineTest {
    private val pipeline = DeterministicCompilationPipeline()

    @Test
    fun `canonical worksheet PNGs compile to the same four passes on every run`() {
        val character = worksheet("character", WorksheetRole.CHARACTER)
        val theme = worksheet("theme", WorksheetRole.THEME)
        val artStyle = worksheet("art", WorksheetRole.ART_STYLE)
        val request = WorksheetCompilationRequest(
            selection = WorksheetSelection(listOf(character), theme, artStyle),
            worksheetRequests = listOf(
                parseRequest(character, characterEntries()),
                parseRequest(theme, themeEntries()),
                parseRequest(artStyle, artStyleEntries()),
            ),
            strategy = EmbeddedProfiles.strategies.first(),
        )

        val first = pipeline.compile(request)
        val second = pipeline.compile(request)

        assertTrue("Expected first compilation to generate but got $first", first is WorksheetCompilationResult.Generated)
        assertTrue("Expected second compilation to generate but got $second", second is WorksheetCompilationResult.Generated)
        val firstCompilation = (first as WorksheetCompilationResult.Generated).compilation
        val secondCompilation = (second as WorksheetCompilationResult.Generated).compilation
        assertEquals(CompilationStatus.GENERATED, firstCompilation.status)
        assertEquals(4, firstCompilation.passes.size)
        assertEquals(firstCompilation.passes, secondCompilation.passes)
        assertTrue(firstCompilation.passes.all { it.positivePrompt.isNotBlank() && it.negativePrompt.isNotBlank() })
    }

    @Test
    fun `art style unknown sections are informational and still compile four passes`() {
        val character = worksheet("character", WorksheetRole.CHARACTER)
        val theme = worksheet("theme", WorksheetRole.THEME)
        val artStyle = worksheet("art", WorksheetRole.ART_STYLE)
        val result = pipeline.compile(
            WorksheetCompilationRequest(
                selection = WorksheetSelection(listOf(character), theme, artStyle),
                worksheetRequests = listOf(
                    parseRequest(character, characterEntries()),
                    parseRequest(theme, themeEntries()),
                    ParseRequest(
                        PngWorksheetSource(
                            pngWithText(listOf(
                                "asterion.ocr.transcript" to """
                                    Style Overview
                                    rendering.method: painterly rendering
                                    lighting.lighting: window light
                                    composition.framing: wide frame
                                    composition.subject_placement: centered subject
                                    composition.depth_treatment: deep focus
                                    camera.perspective: eye level
                                    camera.lens: 50mm lens
                                    camera.angle: front angle
                                    Future Grammar Notes
                                    This informational section is intentionally outside the current grammar.
                                """.trimIndent(),
                                "asterion.ocr.confidence" to "0.95",
                            )),
                        ),
                        artStyle,
                    ),
                ),
                strategy = EmbeddedProfiles.strategies.first(),
            ),
        )
        System.err.println("[TEST DEBUG] compilation result = $result")

        assertTrue("Expected generated result but got $result", result is WorksheetCompilationResult.Generated)
        val generated = (result as WorksheetCompilationResult.Generated)
        assertTrue("Art Style parsed worksheet should contain unknown sections", generated.parsedWorksheets.single { it.worksheet.role == WorksheetRole.ART_STYLE }.unknownSections.isNotEmpty())
        val unknownIssues = generated.compilation.validationReport.issues.filter { it.code == "UNKNOWN_WORKSHEET_SECTION" }
        assertTrue(unknownIssues.isNotEmpty())
        assertTrue(unknownIssues.all { it.severity == com.asterion.compiler.validation.ValidationSeverity.INFORMATIONAL })
        assertEquals(4, generated.compilation.passes.size)
    }

    @Test
    fun `art style sheet remains eligible in every slot when sufficient semantic data exists`() {
        val testSheetSource = PngWorksheetSource(
            pngWithText(listOf(
                "asterion.ocr.transcript" to """
                    Style Overview
                    rendering.method: painterly rendering
                    lighting.lighting: window light
                    composition.framing: wide frame
                    composition.subject_placement: centered subject
                    composition.depth_treatment: deep focus
                    camera.perspective: eye level
                    camera.lens: 50mm lens
                    camera.angle: front angle
                    Future Grammar Notes
                    This informational section is intentionally outside the current grammar.
                """.trimIndent(),
                "asterion.ocr.confidence" to "0.95",
            )),
        )

        val validCharacter = worksheet("character", WorksheetRole.CHARACTER)
        val validTheme = worksheet("theme", WorksheetRole.THEME)
        val validArtStyle = worksheet("art_style", WorksheetRole.ART_STYLE)

        val characterSlotRequest = WorksheetCompilationRequest(
            selection = WorksheetSelection(listOf(worksheet("character-test", WorksheetRole.CHARACTER)), validTheme, validArtStyle),
            worksheetRequests = listOf(
                ParseRequest(testSheetSource, worksheet("character-test", WorksheetRole.CHARACTER)),
                parseRequest(validTheme, themeEntries()),
                parseRequest(validArtStyle, artStyleEntries()),
            ),
            strategy = EmbeddedProfiles.strategies.first(),
        )

        val themeSlotRequest = WorksheetCompilationRequest(
            selection = WorksheetSelection(listOf(validCharacter), worksheet("theme-test", WorksheetRole.THEME), validArtStyle),
            worksheetRequests = listOf(
                parseRequest(validCharacter, characterEntries()),
                ParseRequest(testSheetSource, worksheet("theme-test", WorksheetRole.THEME)),
                parseRequest(validArtStyle, artStyleEntries()),
            ),
            strategy = EmbeddedProfiles.strategies.first(),
        )

        val artStyleSlotRequest = WorksheetCompilationRequest(
            selection = WorksheetSelection(listOf(validCharacter), validTheme, worksheet("art-style-test", WorksheetRole.ART_STYLE)),
            worksheetRequests = listOf(
                parseRequest(validCharacter, characterEntries()),
                parseRequest(validTheme, themeEntries()),
                ParseRequest(testSheetSource, worksheet("art-style-test", WorksheetRole.ART_STYLE)),
            ),
            strategy = EmbeddedProfiles.strategies.first(),
        )

        val results = listOf(
            pipeline.compile(characterSlotRequest) to "Character slot",
            pipeline.compile(themeSlotRequest) to "Theme slot",
            pipeline.compile(artStyleSlotRequest) to "Art Style slot",
        )

        results.forEach { (result, slotName) ->
            assertTrue("$slotName should generate, but got $result", result is WorksheetCompilationResult.Generated)
            val generated = result as WorksheetCompilationResult.Generated
            assertTrue("$slotName should still record unknown section telemetry", generated.parsedWorksheets.any { it.unknownSections.isNotEmpty() })
            assertTrue(
                "$slotName should not block on unknown sections",
                generated.compilation.validationReport.issues.none { it.code == "UNKNOWN_WORKSHEET_SECTION" && it.severity != com.asterion.compiler.validation.ValidationSeverity.INFORMATIONAL },
            )
        }
    }

    @Test
    fun `art style slot remains eligible with thirty one unknown sections`() {
        val unknownHeadings = listOf(
            "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen", "Twenty",
            "TwentyOne", "TwentyTwo", "TwentyThree", "TwentyFour", "TwentyFive", "TwentySix", "TwentySeven", "TwentyEight", "TwentyNine", "Thirty",
            "ThirtyOne",
        )
        val informationalSections = unknownHeadings.joinToString("\n") { heading ->
            "Extra Notes $heading\nThis informational section is intentionally outside the current grammar."
        }

        val testSheetSource = PngWorksheetSource(
            pngWithText(listOf(
                "asterion.ocr.transcript" to """
                    Style Overview
                    rendering.method: painterly rendering
                    lighting.lighting: window light
                    composition.framing: wide frame
                    composition.subject_placement: centered subject
                    composition.depth_treatment: deep focus
                    camera.perspective: eye level
                    camera.lens: 50mm lens
                    camera.angle: front angle
                    $informationalSections
                """.trimIndent(),
                "asterion.ocr.confidence" to "0.95",
            )),
        )

        val artStyleSlotRequest = WorksheetCompilationRequest(
            selection = WorksheetSelection(listOf(worksheet("character", WorksheetRole.CHARACTER)), worksheet("theme", WorksheetRole.THEME), worksheet("art-style-test", WorksheetRole.ART_STYLE)),
            worksheetRequests = listOf(
                parseRequest(worksheet("character", WorksheetRole.CHARACTER), characterEntries()),
                parseRequest(worksheet("theme", WorksheetRole.THEME), themeEntries()),
                ParseRequest(testSheetSource, worksheet("art-style-test", WorksheetRole.ART_STYLE)),
            ),
            strategy = EmbeddedProfiles.strategies.first(),
        )

        val result = pipeline.compile(artStyleSlotRequest)
        assertTrue("Art Style slot should generate", result is WorksheetCompilationResult.Generated)
        val generated = result as WorksheetCompilationResult.Generated
        val unknownIssues = generated.compilation.validationReport.issues.filter { it.code == "UNKNOWN_WORKSHEET_SECTION" }
        assertTrue("Art Style slot should record unknown section telemetry", unknownIssues.isNotEmpty())
        assertTrue("Art Style slot unknown sections should be informational", unknownIssues.all { it.severity == com.asterion.compiler.validation.ValidationSeverity.INFORMATIONAL })
        assertEquals(4, generated.compilation.passes.size)
    }

    private fun worksheet(id: String, role: WorksheetRole): WorksheetReference = WorksheetReference(
        id = when (role) {
            WorksheetRole.CHARACTER -> "character-sheet"
            WorksheetRole.THEME -> "theme-sheet"
            WorksheetRole.ART_STYLE -> "art-style-sheet"
        },
        role = role,
        displayName = when (role) {
            WorksheetRole.CHARACTER -> "character.png"
            WorksheetRole.THEME -> "theme.png"
            WorksheetRole.ART_STYLE -> "art_style.png"
        },
        sourceIdentifier = when (role) {
            WorksheetRole.CHARACTER -> "content://worksheets/character"
            WorksheetRole.THEME -> "content://worksheets/theme"
            WorksheetRole.ART_STYLE -> "content://worksheets/art_style"
        },
        mimeType = "image/png",
    )

    private fun parseRequest(
        worksheet: WorksheetReference,
        entries: List<Pair<String, String>>,
    ): ParseRequest<PngWorksheetSource> = ParseRequest(PngWorksheetSource(pngWithText(entries)), worksheet)

    private fun characterEntries(): List<Pair<String, String>> = layoutEntries("character") + listOf(
        "asterion.identity.identifier" to "Ilyra",
        "asterion.identity.role" to "archivist",
        "asterion.identity.anchors" to "white braid|cheek scar",
        "asterion.physical_traits.age_presentation" to "adult",
        "asterion.physical_traits.body_description" to "lean build",
        "asterion.physical_traits.face_description" to "angular face",
        "asterion.physical_traits.hair_description" to "white braid",
        "asterion.physical_traits.distinguishing_features" to "cheek scar",
        "asterion.wardrobe.garments" to "long coat",
        "asterion.wardrobe.materials" to "wool fabric",
        "asterion.wardrobe.accessories" to "silver brooch",
        "asterion.wardrobe.condition" to "weathered",
        "asterion.expression_pose.expression" to "focused",
        "asterion.expression_pose.pose" to "standing",
        "asterion.expression_pose.gesture" to "holding a book",
    )

    private fun themeEntries(): List<Pair<String, String>> = layoutEntries("theme") + listOf(
        "asterion.narrative.premise" to "archive exploration",
        "asterion.setting.location" to "library interior",
        "asterion.setting.era" to "late industrial",
        "asterion.setting.environmental_details" to "dusty shelves|rain-streaked windows",
        "asterion.mood.mood" to "quiet",
        "asterion.palette.primary" to "amber",
    )

    private fun artStyleEntries(): List<Pair<String, String>> = layoutEntries("art_style") + listOf(
        "asterion.medium.medium" to "digital painting",
        "asterion.rendering.descriptors" to "illustrative",
        "asterion.rendering.method" to "painterly rendering",
        "asterion.lighting.lighting" to "window light",
        "asterion.composition.framing" to "wide frame",
        "asterion.composition.subject_placement" to "centered subject",
        "asterion.composition.depth_treatment" to "deep focus",
        "asterion.camera.perspective" to "eye level",
        "asterion.camera.lens" to "50mm lens",
        "asterion.camera.angle" to "front angle",
    )

    private fun layoutEntries(role: String): List<Pair<String, String>> = listOf(
        "asterion.layout" to "asterion-worksheet-v1",
        "asterion.role" to role,
    )

    private fun pngWithText(entries: List<Pair<String, String>>): ByteArray = buildList {
        addAll(PNG_SIGNATURE.toList())
        entries.forEach { (key, value) ->
            addAll(chunk("tEXt", "$key\u0000$value".toByteArray(StandardCharsets.ISO_8859_1)).toList())
        }
        addAll(chunk("IEND", byteArrayOf()).toList())
    }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        return buildList {
            addAll(data.size.toBigEndianBytes().toList())
            addAll(typeBytes.toList())
            addAll(data.toList())
            addAll(crc.toBigEndianBytes().toList())
        }.toByteArray()
    }

    private fun Int.toBigEndianBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte(),
    )

    private fun Long.toBigEndianBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        toByte(),
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}