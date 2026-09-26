package com.asterion.compiler.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VisualBucketTest {
    @Test
    fun `all required bucket names resolve deterministically`() {
        val requiredBuckets = listOf(
            "identity",
            "face",
            "hair",
            "eyes",
            "body",
            "skin",
            "species",
            "outfit",
            "accessories",
            "weapons",
            "pose",
            "composition",
            "camera",
            "perspective",
            "environment",
            "architecture",
            "lighting",
            "materials",
            "liquids",
            "atmospheric_effects",
            "rendering",
            "checkpoint_optimizations",
        )

        requiredBuckets.forEach { name ->
            val bucket = requireNotNull(VisualBucket.fromStructuredName(name))
            assertEquals(name, bucket.wireName)
        }
        assertEquals(null, VisualBucket.fromStructuredName("undefined_bucket"))
    }
}