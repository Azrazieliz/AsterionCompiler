package com.asterion.compiler.utilities

object ImageFilePolicy {
    const val PngMimeType = "image/png"

    fun isSupportedPng(fileName: String, mimeType: String): Boolean =
        mimeType.equals(PngMimeType, ignoreCase = true) || fileName.endsWith(".png", ignoreCase = true)
}