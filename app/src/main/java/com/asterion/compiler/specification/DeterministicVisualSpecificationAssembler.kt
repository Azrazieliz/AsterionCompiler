package com.asterion.compiler.specification

import com.asterion.compiler.visual.VisualBucket
import com.asterion.compiler.worksheet.ParsedSection
import com.asterion.compiler.worksheet.ParsedWorksheet
import com.asterion.compiler.worksheet.WorksheetRole

class DeterministicVisualSpecificationAssembler : VisualSpecificationAssembler {
    override fun assemble(parsedWorksheets: List<ParsedWorksheet>): VisualSpecificationAssembly = runCatching {
        val byRole = parsedWorksheets.groupBy { it.worksheet.role }
        require(byRole[WorksheetRole.CHARACTER].orEmpty().isNotEmpty()) { "No Character Master Sheet was parsed." }
        require(byRole[WorksheetRole.THEME].orEmpty().size == 1) { "Exactly one parsed Theme Sheet is required." }
        require(byRole[WorksheetRole.ART_STYLE].orEmpty().size == 1) { "Exactly one parsed Art Style Sheet is required." }

        val characters = byRole.getValue(WorksheetRole.CHARACTER)
            .sortedBy { it.worksheet.id }
            .map(::assembleCharacter)
        val themeWorksheet = byRole.getValue(WorksheetRole.THEME).single()
        val artStyleWorksheet = byRole.getValue(WorksheetRole.ART_STYLE).single()

        InternalVisualSpecification(
            characters = characters,
            theme = assembleTheme(themeWorksheet),
            artStyle = assembleArtStyle(artStyleWorksheet),
            continuity = assembleContinuity(themeWorksheet, artStyleWorksheet),
            provenance = SpecificationProvenance(
                sourceSheetIds = parsedWorksheets.map { it.worksheet.id }.sorted(),
                parserStrategies = parsedWorksheets.map { it.parserMetadata.strategyId }.toSortedSet(),
                minimumParserConfidence = parsedWorksheets.minOf { it.confidence },
                sourceSheetIdsByRole = byRole.mapValues { (_, worksheets) -> worksheets.map { it.worksheet.id }.sorted() },
            ),
        )
    }.fold(
        onSuccess = { VisualSpecificationAssembly.Success(it) },
        onFailure = { VisualSpecificationAssembly.Failure(listOf(it.message ?: "Unable to assemble the visual specification.")) },
    )

    private fun assembleCharacter(worksheet: ParsedWorksheet): CharacterVisualSpecification {
        val fields = worksheet.sectionMap()
        val consumed = mutableSetOf<String>()
        fun value(section: String, field: String): String = fields.requireValue(section, field).also {
            consumed += "$section.$field"
        }
        fun values(section: String, field: String): List<String> = fields.optionalList(section, field).also {
            if (fields.containsKey(section to field)) consumed += "$section.$field"
        }

        return CharacterVisualSpecification(
            identifier = value("identity", "identifier"),
            role = value("identity", "role"),
            physicalTraits = PhysicalTraits(
                agePresentation = value("physical_traits", "age_presentation"),
                bodyDescription = value("physical_traits", "body_description"),
                faceDescription = value("physical_traits", "face_description"),
                hairDescription = value("physical_traits", "hair_description"),
                distinguishingFeatures = values("physical_traits", "distinguishing_features"),
            ),
            wardrobe = WardrobeSpecification(
                primaryGarments = values("wardrobe", "garments"),
                materials = values("wardrobe", "materials"),
                accessories = values("wardrobe", "accessories"),
                condition = value("wardrobe", "condition"),
            ),
            expressionAndPose = ExpressionAndPoseSpecification(
                expression = value("expression_pose", "expression"),
                pose = value("expression_pose", "pose"),
                gesture = value("expression_pose", "gesture"),
            ),
            identityAnchors = values("identity", "anchors"),
            supplementalDescriptors = fields.supplementalDescriptors(consumed),
        )
    }

    private fun assembleTheme(worksheet: ParsedWorksheet): ThemeVisualSpecification {
        val fields = worksheet.sectionMap()
        val consumed = mutableSetOf<String>()
        fun value(section: String, field: String): String = fields.requireValue(section, field).also {
            consumed += "$section.$field"
        }
        fun values(section: String, field: String): List<String> = fields.optionalList(section, field).also {
            if (fields.containsKey(section to field)) consumed += "$section.$field"
        }
        val palette = fields.entries
            .filter { it.key.first == "palette" }
            .sortedBy { it.key.second }
            .map { (key, color) ->
                consumed += "${key.first}.${key.second}"
                ColorDirective(name = color, role = key.second)
            }

        return ThemeVisualSpecification(
            narrativePremise = value("narrative", "premise"),
            location = value("setting", "location"),
            era = value("setting", "era"),
            mood = value("mood", "mood"),
            environmentalDetails = values("setting", "environmental_details"),
            palette = palette,
            supplementalDescriptors = fields.supplementalDescriptors(consumed),
        )
    }

    private fun assembleArtStyle(worksheet: ParsedWorksheet): ArtStyleVisualSpecification {
        val fields = worksheet.sectionMap()
        val consumed = mutableSetOf<String>()
        fun value(section: String, field: String): String = fields.requireValue(section, field).also {
            consumed += "$section.$field"
        }
        fun values(section: String, field: String): List<String> = fields.optionalList(section, field).also {
            if (fields.containsKey(section to field)) consumed += "$section.$field"
        }

        return ArtStyleVisualSpecification(
            medium = fields.optionalValue("medium", "medium"),
            aestheticDescriptors = values("rendering", "descriptors"),
            renderingMethod = value("rendering", "method"),
            lighting = value("lighting", "lighting"),
            composition = CompositionDirective(
                framing = value("composition", "framing"),
                subjectPlacement = value("composition", "subject_placement"),
                depthTreatment = value("composition", "depth_treatment"),
            ),
            camera = CameraDirective(
                perspective = value("camera", "perspective"),
                lens = value("camera", "lens"),
                angle = value("camera", "angle"),
            ),
            supplementalDescriptors = fields.supplementalDescriptors(consumed),
        )
    }

    private fun assembleContinuity(
        themeWorksheet: ParsedWorksheet,
        artStyleWorksheet: ParsedWorksheet,
    ): ContinuitySpecification {
        val themeFields = themeWorksheet.sectionMap()
        val artStyleFields = artStyleWorksheet.sectionMap()
        return ContinuitySpecification(
            recurringMotifs = themeFields.optionalList("continuity", "recurring_motifs") +
                artStyleFields.optionalList("continuity", "recurring_motifs"),
            prohibitedDrift = themeFields.optionalList("continuity", "prohibited_drift") +
                artStyleFields.optionalList("continuity", "prohibited_drift"),
        )
    }
}

private fun ParsedWorksheet.sectionMap(): Map<Pair<String, String>, String> = sections
    .toSortedMap()
    .flatMap { (sectionName, section) ->
        section.values.toSortedMap().map { (fieldName, value) ->
            (sectionName to fieldName) to value.trim()
        }
    }
    .toMap()

private fun Map<Pair<String, String>, String>.requireValue(section: String, field: String): String =
    get(section to field)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing required $section.$field value.")

private fun Map<Pair<String, String>, String>.optionalValue(section: String, field: String): String =
    get(section to field)?.trim().orEmpty()

private fun Map<Pair<String, String>, String>.optionalList(section: String, field: String): List<String> =
    get(section to field)
        ?.split('|')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()

private fun Map<Pair<String, String>, String>.supplementalDescriptors(
    consumed: Set<String>,
): List<SupplementalVisualDescriptor> = entries
    .filter { (key, value) -> value.isNotBlank() && "${key.first}.${key.second}" !in consumed }
    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
    .flatMap { (key, value) ->
        val bucket = VisualBucket.fromStructuredName(key.first)
            ?: VisualBucket.fromStructuredName(key.second)
            ?: throw IllegalArgumentException("Unsupported visual field ${key.first}.${key.second} has no visual bucket.")
        value.split('|').map(String::trim).filter(String::isNotBlank).map { descriptor ->
            SupplementalVisualDescriptor(bucket, descriptor)
        }
    }