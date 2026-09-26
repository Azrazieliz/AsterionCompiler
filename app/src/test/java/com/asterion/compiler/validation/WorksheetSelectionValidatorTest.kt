package com.asterion.compiler.validation

import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.asterion.compiler.worksheet.WorksheetSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorksheetSelectionValidatorTest {
    private val validator = WorksheetSelectionValidator()

    @Test
    fun `valid complete PNG selection can compile`() {
        val report = validator.validate(
            WorksheetSelection(
                characterSheets = listOf(sheet("character.png", WorksheetRole.CHARACTER)),
                themeSheet = sheet("theme.png", WorksheetRole.THEME),
                artStyleSheet = sheet("style.png", WorksheetRole.ART_STYLE),
            ),
        )

        assertTrue(report.canCompile)
    }

    @Test
    fun `duplicate or unsupported sheets block compilation`() {
        val repeatedSheet = sheet("character.jpg", WorksheetRole.CHARACTER, "image/jpeg")
        val report = validator.validate(
            WorksheetSelection(
                characterSheets = listOf(repeatedSheet),
                themeSheet = repeatedSheet.copy(role = WorksheetRole.THEME),
                artStyleSheet = sheet("style.png", WorksheetRole.ART_STYLE),
            ),
        )

        assertFalse(report.canCompile)
        assertTrue(report.issues.any { it.code == "UNSUPPORTED_SHEET" })
        assertTrue(report.issues.any { it.code == "DUPLICATE_SHEET" })
    }

    private fun sheet(
        name: String,
        role: WorksheetRole,
        mimeType: String = "image/png",
    ): WorksheetReference = WorksheetReference(
        id = "$name-$role",
        role = role,
        displayName = name,
        sourceIdentifier = "content://worksheets/$name",
        mimeType = mimeType,
    )
}