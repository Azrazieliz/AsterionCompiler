package com.asterion.compiler.diagnostics

enum class CompilationLogOutcome {
    PASS,
    WARNING,
    FAIL,
    INFO,
}

data class CompilationLogEntry(
    val stageName: String,
    val messageText: String,
    val logOutcome: CompilationLogOutcome = CompilationLogOutcome.INFO,
    val detailEntries: List<CompilationLogEntry> = emptyList(),
) {
    companion object {
        fun pass(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()): CompilationLogEntry {
            return CompilationLogEntry(stageName, messageText, CompilationLogOutcome.PASS, detailsList)
        }

        fun warning(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()): CompilationLogEntry {
            return CompilationLogEntry(stageName, messageText, CompilationLogOutcome.WARNING, detailsList)
        }

        fun fail(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()): CompilationLogEntry {
            return CompilationLogEntry(stageName, messageText, CompilationLogOutcome.FAIL, detailsList)
        }

        fun info(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()): CompilationLogEntry {
            return CompilationLogEntry(stageName, messageText, CompilationLogOutcome.INFO, detailsList)
        }
    }
}

fun List<CompilationLogEntry>.toPlainText(indentation: Int = 0): String = buildString {
    for (entry in this@toPlainText) {
        val prefix = when (entry.logOutcome) {
            CompilationLogOutcome.PASS -> "✓"
            CompilationLogOutcome.WARNING -> "⚠"
            CompilationLogOutcome.FAIL -> "✗"
            CompilationLogOutcome.INFO -> "•"
        }
        append("    ".repeat(indentation))
        append(prefix)
        append(" ")
        append(entry.stageName)
        append(": ")
        append(entry.messageText)
        appendLine()
        if (entry.detailEntries.isNotEmpty()) append(entry.detailEntries.toPlainText(indentation + 1))
    }
}
