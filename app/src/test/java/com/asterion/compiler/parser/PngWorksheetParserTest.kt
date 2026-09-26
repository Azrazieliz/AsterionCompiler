package com.asterion.compiler.parser

import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class PngWorksheetParserTest {
    private val parser = DeterministicWorksheetParsingPipeline()

    @Test
    fun `semantic fixed layout parser handles reordered sections and additional descriptors`() {
        val source = PngWorksheetSource(
            pngWithText(
                "asterion.layout" to "asterion-worksheet-v2",
                "asterion.role" to "character",
                "asterion.wardrobe.garments" to "long coat|high collar|pocket watch",
                "asterion.identity.identifier" to "Ilyra",
                "asterion.physical_traits.face_description" to "angular face",
            ),
        )

        val result = parser.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))

        assertTrue(result is ParseResult.Success)
        val parsed = (result as ParseResult.Success).worksheet
        assertEquals(ParserStrategy.FIXED_LAYOUT.name, parsed.parserMetadata.strategyId)
        assertEquals("Ilyra", parsed.sections.getValue("identity").values.getValue("identifier"))
        assertEquals("angular face", parsed.sections.getValue("physical_traits").values.getValue("face_description"))
        assertEquals("long coat|high collar|pocket watch", parsed.sections.getValue("wardrobe").values.getValue("garments"))
    }

    @Test
    fun `semantic fixed layout parser accepts optional sections omitted`() {
        val source = PngWorksheetSource(
            pngWithText(
                "asterion.layout" to "asterion-worksheet-v2",
                "asterion.role" to "character",
                "asterion.identity.identifier" to "Ilyra",
                "asterion.physical_traits.face_description" to "angular face",
            ),
        )

        val result = parser.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))

        assertTrue(result is ParseResult.Success)
        val parsed = (result as ParseResult.Success).worksheet
        assertFalse(parsed.sections.containsKey("wardrobe"))
        assertEquals("Ilyra", parsed.sections.getValue("identity").values.getValue("identifier"))
    }

    @Test
    fun `semantic ocr parser recognizes renamed headings and order independent sections`() {
        val source = PngWorksheetSource(
            pngWithText(
                "asterion.ocr.transcript" to buildString {
                    appendLine("Character Profile")
                    appendLine("identifier : Ilyra")
                    appendLine("Wardrobe Details")
                    appendLine("garments = long coat|high collar")
                    appendLine("Physical Traits")
                    appendLine("face_description: angular face")
                },
                "asterion.ocr.confidence" to "0.9",
            ),
        )

        val result = parser.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))

        assertTrue(result is ParseResult.Success)
        val parsed = (result as ParseResult.Success).worksheet
        assertEquals(ParserStrategy.OCR.name, parsed.parserMetadata.strategyId)
        assertEquals(
            "Unexpected parsed sections: ${parsed.sections.keys}",
            setOf("identity", "physical_traits", "wardrobe"),
            parsed.sections.keys,
        )
        assertEquals(
            "identity section missing or wrong field values: ${parsed.sections}",
            "Ilyra",
            parsed.sections["identity"]?.values?.get("identifier"),
        )
        assertEquals(
            "physical_traits section missing or wrong field values: ${parsed.sections}",
            "angular face",
            parsed.sections["physical_traits"]?.values?.get("face_description"),
        )
        assertEquals(
            "wardrobe section missing or wrong field values: ${parsed.sections}",
            "long coat|high collar",
            parsed.sections["wardrobe"]?.values?.get("garments"),
        )
    }

    @Test
    fun `semantic ocr parser preserves unknown sections and validation emits informational warning`() {
        val source = PngWorksheetSource(
            pngWithText(
                "asterion.ocr.transcript" to buildString {
                    appendLine("Extra Notes")
                    appendLine("Some notes about pose and story.")
                    appendLine("Wardrobe")
                    appendLine("garments = long coat")
                    appendLine("Physical Traits")
                    appendLine("face_description: angular face")
                },
                "asterion.ocr.confidence" to "0.9",
            ),
        )

        val parseResult = parser.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))
        assertTrue(parseResult is ParseResult.Success)
        val parsed = (parseResult as ParseResult.Success).worksheet
        assertEquals(1, parsed.unknownSections.size)
        assertEquals("Extra Notes", parsed.unknownSections.first().heading)
        assertTrue(parsed.unknownSections.first().rawText.contains("Some notes about pose and story."))

        val validationReport = com.asterion.compiler.validation.ValidationPipeline()
            .validateParsedWorksheets(listOf(parsed))

        assertTrue(validationReport.issues.any { it.code == "UNKNOWN_WORKSHEET_SECTION" && it.severity == com.asterion.compiler.validation.ValidationSeverity.INFORMATIONAL })
    }

    @Test
    fun `fixed layout parser wins when semantic fixed metadata is present alongside OCR transcript`() {
        val source = PngWorksheetSource(
            pngWithText(
                "asterion.layout" to "asterion-worksheet-v2",
                "asterion.role" to "character",
                "asterion.identity.identifier" to "Ilyra",
                "asterion.physical_traits.face_description" to "angular face",
                "asterion.ocr.transcript" to "identity.identifier = Ilyra\nphysical_traits.face_description = angular face",
                "asterion.ocr.confidence" to "0.8",
            ),
        )

        val result = parser.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))

        assertTrue(result is ParseResult.Success)
        assertEquals(ParserStrategy.FIXED_LAYOUT.name, (result as ParseResult.Success).worksheet.parserMetadata.strategyId)
    }

    @Test
    fun `ocr parser uses fallback live OCR when png has no embedded metadata`() {
        val liveOcrPipeline = DeterministicWorksheetParsingPipeline(
            fixedLayoutParser = FixedLayoutWorksheetParser(SemanticFixedLayoutEngine()),
            ocrParser = OcrWorksheetParser(SemanticOcrEngine(TestOcrRecognizer())),
        )

        val source = PngWorksheetSource(pngWithText())
        val result = liveOcrPipeline.parse(ParseRequest(source, worksheet(WorksheetRole.CHARACTER)))

        assertTrue(result is ParseResult.Success)
        val parsed = (result as ParseResult.Success).worksheet
        assertEquals(ParserStrategy.OCR.name, parsed.parserMetadata.strategyId)
        assertEquals("Ilyra", parsed.sections.getValue("identity").values.getValue("identifier"))
        assertEquals("angular face", parsed.sections.getValue("physical_traits").values.getValue("face_description"))
        assertEquals("long coat|high collar", parsed.sections.getValue("wardrobe").values.getValue("garments"))
        assertEquals("live", parsed.parserMetadata.details.getValue("ocrSource"))
    }

    private class TestOcrRecognizer : OcrTextRecognizer {
        override fun recognizeText(bytes: ByteArray): Result<OcrRecognitionResult> = Result.success(
            OcrRecognitionResult(
                transcript = buildString {
                    appendLine("Character Profile")
                    appendLine("identifier = Ilyra")
                    appendLine("Wardrobe")
                    appendLine("garments: long coat|high collar")
                    appendLine("Physical Traits")
                    appendLine("face_description = angular face")
                },
                confidence = 0.92f,
                source = "live",
            ),
        )
    }

    private fun worksheet(role: WorksheetRole) = WorksheetReference(
        id = "character",
        role = role,
        displayName = "character.png",
        sourceIdentifier = "content://worksheets/character",
        mimeType = "image/png",
    )

    private fun pngWithText(vararg entries: Pair<String, String>): ByteArray = buildList {
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