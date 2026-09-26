package com.asterion.compiler.diagnostics

object CompilationTrace {
    private val currentTrace = ThreadLocal<MutableList<CompilationLogEntry>?>()

    fun start() {
        currentTrace.set(mutableListOf())
    }

    fun end(): List<CompilationLogEntry> {
        val entries = currentTrace.get().orEmpty()
        currentTrace.remove()
        return entries
    }

    fun log(entry: CompilationLogEntry) {
        currentTrace.get()?.add(entry)
    }

    fun info(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()) {
        log(CompilationLogEntry.info(stageName, messageText, detailsList))
    }

    fun pass(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()) {
        log(CompilationLogEntry.pass(stageName, messageText, detailsList))
    }

    fun warning(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()) {
        log(CompilationLogEntry.warning(stageName, messageText, detailsList))
    }

    fun fail(stageName: String, messageText: String, detailsList: List<CompilationLogEntry> = emptyList()) {
        log(CompilationLogEntry.fail(stageName, messageText, detailsList))
    }
}
