package com.asterion.compiler.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image as BitmapImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asterion.compiler.app.AppDestination
import com.asterion.compiler.app.AppSettings
import com.asterion.compiler.app.AsterionApplication
import com.asterion.compiler.app.ThemePreference
import com.asterion.compiler.app.ThumbnailSize
import com.asterion.compiler.profiles.EmbeddedProfiles
import com.asterion.compiler.prompt.PromptGenerationStatus
import com.asterion.compiler.prompt.PromptPass
import com.asterion.compiler.service.PromptRefinementRule
import com.asterion.compiler.ui.theme.AsterionPalette
import com.asterion.compiler.ui.theme.AsterionTheme
import com.asterion.compiler.utilities.ImageFilePolicy
import com.asterion.compiler.validation.ValidationIssue
import com.asterion.compiler.validation.ValidationSeverity
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.asterion.compiler.service.AndroidWorksheetSourceReader
import com.asterion.compiler.service.AndroidWorksheetImageLoader
import com.asterion.compiler.service.WorksheetImageLoader
import com.asterion.compiler.service.WorksheetSourceReader
import com.asterion.compiler.diagnostics.CompilationLogEntry
import com.asterion.compiler.diagnostics.toPlainText

@Composable
fun AsterionCompilerApp(viewModel: CompilerViewModel = viewModel()) {
    val state = viewModel.uiState
    val context = LocalContext.current
    val sourceReader: WorksheetSourceReader = (context.applicationContext as? AsterionApplication)
        ?.container
        ?.sourceReader
        ?: AndroidWorksheetSourceReader(context.contentResolver)
    val imageLoader: WorksheetImageLoader = (context.applicationContext as? AsterionApplication)
        ?.container
        ?.imageLoader
        ?: AndroidWorksheetImageLoader(context.contentResolver)
    val characterPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach(sourceReader::persistReadPermission)
        viewModel.addCharacterSheets(uris.map { sourceReader.createReference(it, WorksheetRole.CHARACTER) })
    }
    val themePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            sourceReader.persistReadPermission(it)
            viewModel.replaceThemeSheet(sourceReader.createReference(it, WorksheetRole.THEME))
        }
    }
    val artStylePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            sourceReader.persistReadPermission(it)
            viewModel.replaceArtStyleSheet(sourceReader.createReference(it, WorksheetRole.ART_STYLE))
        }
    }

    AsterionTheme(themePreference = state.settings.themePreference) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 920.dp),
                ) {
                    AppHeader()
                    Spacer(modifier = Modifier.height(22.dp))
                    DestinationNavigation(state.destination, viewModel::navigate)
                    Spacer(modifier = Modifier.height(24.dp))
                    when (state.destination) {
                        AppDestination.STUDIO -> StudioScreen(
                            state = state,
                            imageLoader = imageLoader,
                            onAddCharacters = { characterPicker.launch(arrayOf(ImageFilePolicy.PngMimeType)) },
                            onRemoveCharacter = viewModel::removeCharacterSheet,
                            onChooseTheme = { themePicker.launch(arrayOf(ImageFilePolicy.PngMimeType)) },
                            onClearTheme = viewModel::clearThemeSheet,
                            onChooseArtStyle = { artStylePicker.launch(arrayOf(ImageFilePolicy.PngMimeType)) },
                            onClearArtStyle = viewModel::clearArtStyleSheet,
                            onSelectStrategy = viewModel::selectStrategy,
                            onSelectCheckpoint = viewModel::selectCheckpoint,
                            onCompile = {
                                sourceReader.read(state.selection)
                                    .onSuccess(viewModel::compileWorksheetSet)
                                    .onFailure { viewModel.reportSourceReadFailure() }
                            },
                            onRestart = viewModel::restartStrategy,
                            onReset = viewModel::resetCompiler,
                            onCopy = viewModel::copyCurrentPass,
                            onCopyCompilationLog = viewModel::copyCompilationLog,
                            onPreviousPass = viewModel::showPreviousPass,
                            onNextPass = viewModel::showNextPass,
                            onSelectPass = viewModel::selectPass,
                            onCommentChanged = viewModel::updateCurrentPassComment,
                            onRegenerate = { persist -> viewModel.regenerateCurrentPass(persist) },
                        )

                        AppDestination.HISTORY -> SessionHistoryScreen(state)
                        AppDestination.SETTINGS -> SettingsScreen(
                            settings = state.settings,
                            rules = viewModel.listRefinementRules(),
                            onUpdate = viewModel::updateSettings,
                            onToggleRule = viewModel::setRefinementRuleEnabled,
                            onDeleteRule = viewModel::deleteRefinementRule,
                            onReorderRules = viewModel::reorderRefinementRules,
                            onExportRules = { viewModel.exportRefinementRules() },
                            onImportRules = viewModel::importRefinementRules,
                        )
                        AppDestination.PROMPT_REFINEMENT_MEMORY -> PromptRefinementMemoryScreen(
                            rules = viewModel.listRefinementRules(),
                            onSaveRule = viewModel::saveRefinementRule,
                            onToggleRule = viewModel::setRefinementRuleEnabled,
                            onDeleteRule = viewModel::deleteRefinementRule,
                            onReorderRules = viewModel::reorderRefinementRules,
                            onExportRules = { viewModel.exportRefinementRules() },
                            onImportRules = viewModel::importRefinementRules,
                        )
                    }
                }
            }

            state.notice?.let { notice ->
                NoticeBanner(
                    notice = notice,
                    onDismiss = viewModel::dismissNotice,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(20.dp),
                )
            }
        }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = AsterionPalette.AntiqueGold,
                shape = MaterialTheme.shapes.small,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = AsterionPalette.Ivory,
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(text = "AsterionCompiler", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "LOCAL PROMPT WORKSPACE",
                    style = MaterialTheme.typography.labelLarge,
                    color = AsterionPalette.MutedIvory,
                )
            }
        }
        Surface(
            color = AsterionPalette.Graphite,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = "OFFLINE",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = AsterionPalette.MetallicGold,
            )
        }
    }
}

@Composable
private fun StrategyProgress(state: CompilerUiState) {
    val completedStep = when {
        state.canDisplayPasses -> 4
        state.validationAttempted -> 2
        state.selectedStrategyId != null -> 1
        else -> 0
    }
    val steps = listOf("Sheets", "Strategy", "Validation", "Specification", "Pass")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val isReached = index <= completedStep
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(20.dp),
                    color = if (isReached) AsterionPalette.MetallicGold else AsterionPalette.Graphite,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isReached) AsterionPalette.MatteBlack else AsterionPalette.MutedIvory,
                        )
                    }
                }
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isReached) AsterionPalette.Ivory else AsterionPalette.MutedIvory,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AsterionPalette.MetallicGold,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = AsterionPalette.Outline.copy(alpha = 0.62f))
    }
}

@Composable
private fun WorksheetSelectionSection(
    state: CompilerUiState,
    imageLoader: WorksheetImageLoader,
    onAddCharacters: () -> Unit,
    onRemoveCharacter: (String) -> Unit,
    onChooseTheme: () -> Unit,
    onClearTheme: () -> Unit,
    onChooseArtStyle: () -> Unit,
    onClearArtStyle: () -> Unit,
) {
    SectionHeader(title = "Selected Worksheets", icon = Icons.Filled.Image)
    Spacer(modifier = Modifier.height(12.dp))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WorksheetSlot(
            title = "Character Master Sheets",
            selectionCount = "${state.selection.characterSheets.size}/3 selected",
            actionLabel = "Add PNG",
            onAction = onAddCharacters,
        ) {
            if (state.selection.characterSheets.isEmpty()) {
                EmptyWorksheetLine("No Character Master Sheets selected")
            } else {
                state.selection.characterSheets.forEach { sheet ->
                    SelectedWorksheetRow(
                        sheet = sheet,
                        thumbnailPixels = state.settings.thumbnailSize.pixels,
                        imageLoader = imageLoader,
                        onRemove = { onRemoveCharacter(sheet.id) },
                    )
                }
            }
        }
        WorksheetSlot(
            title = "Theme Sheet",
            selectionCount = if (state.selection.themeSheet == null) "Required" else "Selected",
            actionLabel = if (state.selection.themeSheet == null) "Choose PNG" else "Replace",
            onAction = onChooseTheme,
        ) {
            state.selection.themeSheet?.let { sheet ->
                SelectedWorksheetRow(
                    sheet = sheet,
                    thumbnailPixels = state.settings.thumbnailSize.pixels,
                    imageLoader = imageLoader,
                    onRemove = onClearTheme,
                )
            } ?: EmptyWorksheetLine("No Theme Sheet selected")
        }
        WorksheetSlot(
            title = "Art Style Sheet",
            selectionCount = if (state.selection.artStyleSheet == null) "Required" else "Selected",
            actionLabel = if (state.selection.artStyleSheet == null) "Choose PNG" else "Replace",
            onAction = onChooseArtStyle,
        ) {
            state.selection.artStyleSheet?.let { sheet ->
                SelectedWorksheetRow(
                    sheet = sheet,
                    thumbnailPixels = state.settings.thumbnailSize.pixels,
                    imageLoader = imageLoader,
                    onRemove = onClearArtStyle,
                )
            } ?: EmptyWorksheetLine("No Art Style Sheet selected")
        }
    }
}

@Composable
private fun WorksheetSlot(
    title: String,
    selectionCount: String,
    actionLabel: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = selectionCount,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AsterionPalette.MutedIvory,
                )
            }
            OutlinedButton(onClick = onAction) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(actionLabel)
            }
        }
        content()
    }
}

@Composable
private fun EmptyWorksheetLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AsterionPalette.MutedIvory,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SelectedWorksheetRow(
    sheet: WorksheetReference,
    thumbnailPixels: Int,
    imageLoader: WorksheetImageLoader,
    onRemove: () -> Unit,
) {
    val thumbnail = remember(sheet.sourceIdentifier, thumbnailPixels) {
        imageLoader.loadThumbnail(sheet, thumbnailPixels)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AsterionPalette.Carbon,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, AsterionPalette.Outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (thumbnail == null) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = AsterionPalette.MetallicGold,
                )
            } else {
                BitmapImage(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "Preview of ${sheet.displayName}",
                    modifier = Modifier
                        .size(42.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            }
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = sheet.displayName,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = "Remove ${sheet.displayName}",
                    tint = AsterionPalette.MutedIvory,
                )
            }
        }
    }
}

@Composable
private fun StrategySelectionSection(
    selectedStrategyId: String?,
    onSelectStrategy: (String) -> Unit,
) {
    SectionHeader(title = "Compilation Strategy", icon = Icons.Filled.Tune)
    Spacer(modifier = Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmbeddedProfiles.strategies.forEach { strategy ->
            val selected = strategy.id == selectedStrategyId
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (selected) AsterionPalette.Graphite else AsterionPalette.Carbon,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) AsterionPalette.MetallicGold else AsterionPalette.Outline,
                ),
                onClick = { onSelectStrategy(strategy.id) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onSelectStrategy(strategy.id) })
                    Spacer(modifier = Modifier.size(8.dp))
                    Column {
                        Text(text = strategy.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = strategy.checkpointProfile.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AsterionPalette.MutedIvory,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointSelectionSection(
    selectedCheckpointId: String?,
    onSelectCheckpoint: (String) -> Unit,
) {
    SectionHeader(title = "Checkpoint", icon = Icons.Filled.Tune)
    Spacer(modifier = Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EmbeddedProfiles.checkpoints.forEach { checkpoint ->
            val selected = checkpoint.id == selectedCheckpointId
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (selected) AsterionPalette.Graphite else AsterionPalette.Carbon,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) AsterionPalette.MetallicGold else AsterionPalette.Outline,
                ),
                onClick = { onSelectCheckpoint(checkpoint.id) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onSelectCheckpoint(checkpoint.id) })
                    Spacer(modifier = Modifier.size(8.dp))
                    Column {
                        Text(text = checkpoint.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${checkpoint.baseModelFamily} | ${checkpoint.defaultParameters.steps} steps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AsterionPalette.MutedIvory,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ValidationSection(validationAttempted: Boolean, issues: List<ValidationIssue>) {
    SectionHeader(title = "Validation", icon = Icons.Filled.CheckCircle)
    Spacer(modifier = Modifier.height(12.dp))
    when {
        !validationAttempted -> EmptyWorksheetLine("No validation results")
        issues.isEmpty() -> ValidationResultRow(
            message = "Worksheet set passed selection validation.",
            severity = ValidationSeverity.INFORMATIONAL,
        )

        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            issues.forEach { issue ->
                ValidationResultRow(message = issue.message, severity = issue.severity)
            }
        }
    }
}

@Composable
private fun CompilationLogSection(diagnostics: List<CompilationLogEntry>, onCopyLog: () -> Unit) {
    SectionHeader(title = "Compilation Log", icon = Icons.Filled.History)
    Spacer(modifier = Modifier.height(12.dp))
    if (diagnostics.isEmpty()) {
        EmptyWorksheetLine("No compilation diagnostics available.")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Latest run", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onCopyLog) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Copy Log")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AsterionPalette.Carbon,
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, AsterionPalette.Outline),
        ) {
            SelectionContainer {
                Text(
                    text = diagnostics.toPlainText(),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AsterionPalette.Ivory,
                )
            }
        }
    }
}

@Composable
private fun ValidationResultRow(message: String, severity: ValidationSeverity) {
    val color = when (severity) {
        ValidationSeverity.CRITICAL, ValidationSeverity.MAJOR -> AsterionPalette.Amber
        ValidationSeverity.MINOR -> AsterionPalette.MetallicGold
        ValidationSeverity.INFORMATIONAL -> AsterionPalette.MetallicGold
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AsterionPalette.Carbon,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.62f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (severity == ValidationSeverity.INFORMATIONAL) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.WarningAmber
                },
                contentDescription = null,
                tint = color,
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PromptPassSection(
    state: CompilerUiState,
    onCopy: () -> Unit,
    onPreviousPass: () -> Unit,
    onNextPass: () -> Unit,
    onSelectPass: (Int) -> Unit,
    onCommentChanged: (String) -> Unit,
    onRegenerate: (Boolean) -> Unit,
) {
    SectionHeader(title = "Current Pass", icon = Icons.Filled.AutoAwesome)
    Spacer(modifier = Modifier.height(12.dp))
    var showRefinementScopeDialog by remember { mutableStateOf(false) }
    val pass = state.currentPass
    if (!state.canDisplayPasses || pass == null) {
        EmptyWorksheetLine("Prompt passes are unavailable until validation succeeds.")
        return
    }

    PassHeader(state.currentPassIndex, state.promptPasses.size, pass)
    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.promptPasses.forEachIndexed { index, item ->
            OutlinedButton(onClick = { onSelectPass(index) }) {
                Text(item.type.displayName)
            }
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Compilation status: ${if (pass.generationStatus == PromptGenerationStatus.GENERATED) "Generated" else "Pending"}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (pass.generationStatus == PromptGenerationStatus.GENERATED) {
            AsterionPalette.MetallicGold
        } else {
            AsterionPalette.Amber
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    PromptBlock(label = "Prompt", text = pass.positivePrompt.ifBlank { "No compiled prompt is available." })
    Spacer(modifier = Modifier.height(12.dp))
    PromptBlock(label = "Negative Prompt", text = pass.negativePrompt.ifBlank { "No negative prompt is available." })
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Prompt length: ${pass.positivePrompt.length} characters",
        style = MaterialTheme.typography.bodyMedium,
        color = AsterionPalette.MutedIvory,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "${pass.generationParameters.width} x ${pass.generationParameters.height} | " +
            "${pass.generationParameters.steps} steps | ${pass.generationParameters.sampler}",
        style = MaterialTheme.typography.bodyMedium,
        color = AsterionPalette.MutedIvory,
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value = state.currentPassComment,
        onValueChange = onCommentChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Pass comment") },
        placeholder = { Text("Describe how to refine the prompt for this pass.") },
        minLines = 2,
    )
    val refinementNodes = state.compilation?.graph?.nodesFor(pass.type).orEmpty()
    if (refinementNodes.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = "Available graph nodes", style = MaterialTheme.typography.titleMedium)
        refinementNodes.forEach { node ->
            Text(
                text = "${node.id} | ${node.descriptor}",
                style = MaterialTheme.typography.bodyMedium,
                color = AsterionPalette.MutedIvory,
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onCopy,
            enabled = pass.generationStatus == PromptGenerationStatus.GENERATED && pass.hasPrompt,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Copy Prompt")
        }
        OutlinedButton(
            onClick = { showRefinementScopeDialog = true },
            enabled = pass.generationStatus == PromptGenerationStatus.GENERATED && !state.isRestoredSession,
            modifier = Modifier.weight(1f),
        ) {
            Text("Regenerate")
        }
    }
    if (showRefinementScopeDialog) {
        AlertDialog(
            onDismissRequest = { showRefinementScopeDialog = false },
            title = { Text("Save refinement?") },
            text = { Text("Should this refinement affect only the current prompt or all future prompts?") },
            confirmButton = {
                TextButton(onClick = { showRefinementScopeDialog = false; onRegenerate(true) }) {
                    Text("All future prompts")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefinementScopeDialog = false; onRegenerate(false) }) {
                    Text("Current prompt only")
                }
            },
        )
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onPreviousPass,
            enabled = state.currentPassIndex > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("Previous Pass")
        }
        OutlinedButton(
            onClick = onNextPass,
            enabled = pass.generationStatus == PromptGenerationStatus.GENERATED &&
                state.currentPassIndex < state.promptPasses.lastIndex,
            modifier = Modifier.weight(1f),
        ) {
            Text("Next Pass")
            Spacer(modifier = Modifier.size(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun PassHeader(index: Int, total: Int, pass: PromptPass) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(text = pass.type.displayName, style = MaterialTheme.typography.titleLarge)
            Text(
                text = pass.objective,
                style = MaterialTheme.typography.bodyMedium,
                color = AsterionPalette.MutedIvory,
            )
        }
        Text(
            text = "${index + 1} / $total",
            style = MaterialTheme.typography.labelLarge,
            color = AsterionPalette.MetallicGold,
        )
    }
}

@Composable
private fun PromptBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AsterionPalette.Carbon,
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, AsterionPalette.Outline),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (text.startsWith("No ")) AsterionPalette.MutedIvory else AsterionPalette.Ivory,
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(notice: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 520.dp),
        color = AsterionPalette.Graphite,
        shape = MaterialTheme.shapes.small,
        border = androidx.compose.foundation.BorderStroke(1.dp, AsterionPalette.Amber),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = AsterionPalette.Amber)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = notice, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun DestinationNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar(containerColor = AsterionPalette.Carbon) {
        listOf(
            AppDestination.STUDIO to Icons.Filled.AutoAwesome,
            AppDestination.HISTORY to Icons.Filled.History,
            AppDestination.SETTINGS to Icons.Filled.Settings,
            AppDestination.PROMPT_REFINEMENT_MEMORY to Icons.Filled.Tune,
        ).forEach { (destination, icon) ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { Icon(icon, contentDescription = destination.name.lowercase()) },
                label = {
                    Text(
                        when (destination) {
                            AppDestination.STUDIO -> "Studio"
                            AppDestination.HISTORY -> "History"
                            AppDestination.SETTINGS -> "Settings"
                            AppDestination.PROMPT_REFINEMENT_MEMORY -> "Refinement Memory"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun StudioScreen(
    state: CompilerUiState,
    imageLoader: WorksheetImageLoader,
    onAddCharacters: () -> Unit,
    onRemoveCharacter: (String) -> Unit,
    onChooseTheme: () -> Unit,
    onClearTheme: () -> Unit,
    onChooseArtStyle: () -> Unit,
    onClearArtStyle: () -> Unit,
    onSelectStrategy: (String) -> Unit,
    onSelectCheckpoint: (String) -> Unit,
    onCompile: () -> Unit,
    onRestart: () -> Unit,
    onReset: () -> Unit,
    onCopy: () -> Unit,
    onCopyCompilationLog: () -> Unit,
    onPreviousPass: () -> Unit,
    onNextPass: () -> Unit,
    onSelectPass: (Int) -> Unit,
    onCommentChanged: (String) -> Unit,
    onRegenerate: (Boolean) -> Unit,
) {
    var confirmationAction by remember { mutableStateOf<StudioConfirmationAction?>(null) }
    StrategyProgress(state)
    Spacer(modifier = Modifier.height(28.dp))
    WorksheetSelectionSection(
        state = state,
        imageLoader = imageLoader,
        onAddCharacters = onAddCharacters,
        onRemoveCharacter = onRemoveCharacter,
        onChooseTheme = onChooseTheme,
        onClearTheme = onClearTheme,
        onChooseArtStyle = onChooseArtStyle,
        onClearArtStyle = onClearArtStyle,
    )
    Spacer(modifier = Modifier.height(28.dp))
    StrategySelectionSection(selectedStrategyId = state.selectedStrategyId, onSelectStrategy = onSelectStrategy)
    Spacer(modifier = Modifier.height(20.dp))
    CheckpointSelectionSection(selectedCheckpointId = state.selectedCheckpointId, onSelectCheckpoint = onSelectCheckpoint)
    Spacer(modifier = Modifier.height(20.dp))
    Button(onClick = onCompile, modifier = Modifier.fillMaxWidth(), contentPadding = ButtonDefaults.ContentPadding) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null)
        Spacer(modifier = Modifier.size(8.dp))
        Text(if (state.isRestoredSession) "Recompile Worksheets" else "Validate and Compile")
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { confirmationAction = StudioConfirmationAction.RESTART },
            modifier = Modifier.weight(1f),
        ) { Text("Restart") }
        OutlinedButton(
            onClick = { confirmationAction = StudioConfirmationAction.RESET },
            modifier = Modifier.weight(1f),
        ) { Text("Reset") }
    }
    Spacer(modifier = Modifier.height(16.dp))
    ValidationSection(validationAttempted = state.validationAttempted, issues = state.validationReport.issues)
    Spacer(modifier = Modifier.height(28.dp))
    CompilationLogSection(
        diagnostics = state.compilationDiagnostics,
        onCopyLog = onCopyCompilationLog,
    )
    Spacer(modifier = Modifier.height(24.dp))
    PromptPassSection(
        state = state,
        onCopy = onCopy,
        onPreviousPass = onPreviousPass,
        onNextPass = onNextPass,
        onSelectPass = onSelectPass,
        onCommentChanged = onCommentChanged,
        onRegenerate = onRegenerate,
    )
    confirmationAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmationAction = null },
            title = { Text(if (action == StudioConfirmationAction.RESTART) "Restart strategy?" else "Reset compiler?") },
            text = {
                Text(
                    if (action == StudioConfirmationAction.RESTART) {
                        "Generated prompts and comments will be cleared. Worksheet selections remain available."
                    } else {
                        "All saved worksheet selections, prompts, comments, and session history will be cleared."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (action == StudioConfirmationAction.RESTART) onRestart() else onReset()
                        confirmationAction = null
                    },
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { confirmationAction = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class StudioConfirmationAction {
    RESTART,
    RESET,
}

@Composable
private fun SessionHistoryScreen(state: CompilerUiState) {
    SectionHeader(title = "Session History", icon = Icons.Filled.History)
    Spacer(modifier = Modifier.height(12.dp))
    if (state.passHistory.isEmpty()) {
        EmptyWorksheetLine("No generated prompt history in this session.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        state.passHistory.entries.sortedBy { it.key.ordinal }.forEach { (passType, revisions) ->
            Text(text = passType.displayName, style = MaterialTheme.typography.titleMedium)
            revisions.forEachIndexed { revisionIndex, pass ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AsterionPalette.Carbon,
                    shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AsterionPalette.Outline),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Revision ${revisionIndex + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = AsterionPalette.MetallicGold,
                        )
                        SelectionContainer {
                            Text(text = pass.positivePrompt, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: AppSettings,
    rules: List<PromptRefinementRule>,
    onUpdate: (AppSettings) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onReorderRules: (List<String>) -> Unit,
    onExportRules: () -> String,
    onImportRules: (String) -> Unit,
) {
    SectionHeader(title = "Settings", icon = Icons.Filled.Settings)
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "Theme", style = MaterialTheme.typography.titleMedium)
    ThemePreference.entries.forEach { preference ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = settings.themePreference == preference,
                onClick = { onUpdate(settings.copy(themePreference = preference)) },
            )
            Text(text = preference.name.lowercase().replaceFirstChar(Char::uppercase))
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "Worksheet Thumbnail Size", style = MaterialTheme.typography.titleMedium)
    ThumbnailSize.entries.forEach { thumbnailSize ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = settings.thumbnailSize == thumbnailSize,
                onClick = { onUpdate(settings.copy(thumbnailSize = thumbnailSize)) },
            )
            Text(text = thumbnailSize.name.lowercase().replaceFirstChar(Char::uppercase))
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "Prompt History Capacity", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { onUpdate(settings.copy(maximumSessionHistory = settings.maximumSessionHistory - 1)) },
            enabled = settings.maximumSessionHistory > 1,
        ) { Text("Decrease") }
        Text(text = settings.maximumSessionHistory.toString(), style = MaterialTheme.typography.titleLarge)
        OutlinedButton(
            onClick = { onUpdate(settings.copy(maximumSessionHistory = settings.maximumSessionHistory + 1)) },
            enabled = settings.maximumSessionHistory < 50,
        ) { Text("Increase") }
    }
    Spacer(modifier = Modifier.height(20.dp))
    Text(text = "Prompt Refinement Memory", style = MaterialTheme.typography.titleMedium)
    rules.forEachIndexed { index, rule ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Checkbox(checked = rule.enabled, onCheckedChange = { onToggleRule(rule.id, it) })
            Text(text = "${rule.type.name}: ${rule.phrase}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = {
                if (index > 0) onReorderRules(rules.map { it.id }.toMutableList().also { it[index] = it[index - 1]; it[index - 1] = rule.id })
            }, enabled = index > 0) { Text("Up") }
            TextButton(onClick = { onDeleteRule(rule.id) }) { Text("Delete") }
        }
    }
    var importText by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importText = onExportRules() }) { Text("Export") }
        OutlinedButton(onClick = { onImportRules(importText) }, enabled = importText.isNotBlank()) { Text("Import") }
    }
    OutlinedTextField(
        value = importText,
        onValueChange = { importText = it },
        label = { Text("Rule JSON") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PromptRefinementMemoryScreen(
    rules: List<PromptRefinementRule>,
    onSaveRule: (PromptRefinementRule) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onReorderRules: (List<String>) -> Unit,
    onExportRules: () -> String,
    onImportRules: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var enabledOnly by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var editingRule by remember { mutableStateOf<PromptRefinementRule?>(null) }
    val visibleRules = rules.filter { rule ->
        (!enabledOnly || rule.enabled) &&
            (query.isBlank() || listOf(rule.type.name, rule.phrase, rule.replacement).any { it.contains(query, true) })
    }

    SectionHeader(title = "Prompt Refinement Memory", icon = Icons.Filled.Tune)
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search rules") })
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(enabledOnly, { enabledOnly = it })
        Text("Enabled rules only")
    }
    visibleRules.forEachIndexed { index, rule ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Checkbox(rule.enabled, { onToggleRule(rule.id, it) })
            Text("${rule.type.name}: ${rule.phrase}", modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { editingRule = rule }) { Text("Edit") }
            TextButton(onClick = { onDeleteRule(rule.id) }) { Text("Delete") }
            TextButton(
                onClick = {
                    val ids = rules.map { it.id }.toMutableList()
                    if (index > 0) { ids[index] = ids[index - 1]; ids[index - 1] = rule.id; onReorderRules(ids) }
                },
                enabled = index > 0,
            ) { Text("Up") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { importText = onExportRules() }) { Text("Export JSON") }
        OutlinedButton(onClick = { onImportRules(importText) }, enabled = importText.isNotBlank()) { Text("Import JSON") }
    }
    OutlinedTextField(importText, { importText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Rule JSON") })

    editingRule?.let { rule ->
        var phrase by remember(rule.id) { mutableStateOf(rule.phrase) }
        var replacement by remember(rule.id) { mutableStateOf(rule.replacement) }
        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("Edit refinement rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rule.type.name)
                    OutlinedTextField(phrase, { phrase = it }, label = { Text("Phrase") })
                    OutlinedTextField(replacement, { replacement = it }, label = { Text("Replacement") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveRule(rule.copy(phrase = phrase, replacement = replacement))
                    editingRule = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingRule = null }) { Text("Cancel") } },
        )
    }
}