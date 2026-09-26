package com.asterion.compiler.visual

import java.util.Locale

enum class VisualBucket(val wireName: String) {
    IDENTITY("identity"),
    FACE("face"),
    HAIR("hair"),
    EYES("eyes"),
    BODY("body"),
    SKIN("skin"),
    SPECIES("species"),
    OUTFIT("outfit"),
    ACCESSORIES("accessories"),
    WEAPONS("weapons"),
    POSE("pose"),
    COMPOSITION("composition"),
    CAMERA("camera"),
    PERSPECTIVE("perspective"),
    VISIBILITY("visibility"),
    APPEARANCE_STATE("appearance_state"),
    ENVIRONMENT("environment"),
    ARCHITECTURE("architecture"),
    LIGHTING("lighting"),
    MATERIALS("materials"),
    LIQUIDS("liquids"),
    ATMOSPHERIC_EFFECTS("atmospheric_effects"),
    RENDERING("rendering"),
    CHECKPOINT_OPTIMIZATIONS("checkpoint_optimizations"),
    ;

    companion object {
        fun fromStructuredName(value: String): VisualBucket? {
            val normalized = value.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}