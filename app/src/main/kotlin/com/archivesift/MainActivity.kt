package com.archivesift

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts as Pick
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.archivesift.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model by viewModels<SiftModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFD4BBFF), primaryContainer = Color(0xFF482780))
                else lightColorScheme(primary = Color(0xFF6239AE), primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF240B4F))) {
                SiftScreen(model) { path ->
                    val uri = FileProvider.getUriForFile(this, "$packageName.share", path)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("ArchiveSift ZIP", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share ArchiveSift ZIP"))
                }
            }
        }
    }
}

private data class Browser(val matches: List<Entry>, val dirs: List<String>, val visible: List<Entry>)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SiftScreen(m: SiftModel, launchShare: (java.io.File) -> Unit) {
    val zip = rememberLauncherForActivityResult(Pick.OpenDocument()) { it?.let { uri -> m.open(uri, true) } }
    val folder = rememberLauncherForActivityResult(Pick.OpenDocumentTree()) { it?.let { uri -> m.open(uri, false) } }
    val saveZip = rememberLauncherForActivityResult(Pick.CreateDocument("application/zip")) { if (it != null) m.export(it, "zip") else m.pending = emptyList() }
    val saveList = rememberLauncherForActivityResult(Pick.CreateDocument("text/csv")) { if (it != null) m.export(it, "list") else m.pending = emptyList() }
    val saveFolder = rememberLauncherForActivityResult(Pick.OpenDocumentTree()) { if (it != null) m.planFolder(it, m.conflictPolicy) else m.pending = emptyList() }
    val readPreset = rememberLauncherForActivityResult(Pick.OpenDocument()) { it?.let { uri -> m.importPreset(uri, m.importForNext) } }
    val writePreset = rememberLauncherForActivityResult(Pick.CreateDocument("application/json")) { it?.let(m::exportPreset) }
    LaunchedEffect(m.shareFile) { m.shareFile?.let { f ->
        try { launchShare(f) } catch (e: Exception) { m.message = e.message ?: "No app can share this ZIP." }
        m.shareFile = null
    } }
    var tree by rememberSaveable { mutableStateOf(false) }
    var path by rememberSaveable { mutableStateOf("") }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var quickPattern by rememberSaveable { mutableStateOf("") }
    var home by rememberSaveable { mutableStateOf(true) }
    var saveKind by rememberSaveable { mutableStateOf<String?>(null) }
    var presetName by rememberSaveable { mutableStateOf("") }
    val quickRule = remember(quickPattern) { Rule(false, Mode.Regex, quickPattern) }
    val quickError = remember(quickRule) { if (quickPattern.isEmpty()) null else compileRules(listOf(quickRule)).errors[0] }
    val quickValid = quickPattern.isNotEmpty() && quickError == null
    val atHome = home || m.sourceLabel.isEmpty()
    var seenSource by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(m.sourceVersion) {
        if (m.sourceVersion == 0L) seenSource = 0L
        else if (m.sourceVersion != seenSource) { home = false; path = ""; tree = false; quickPattern = ""; seenSource = m.sourceVersion }
    }
    BackHandler(!atHome && !m.busy && !showRules && !showPresets && m.preview == null && m.folderPreview == null) {
        if (tree && path.isNotEmpty()) path = path.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        else home = true
    }
    var showConflicts by rememberSaveable { mutableStateOf(false) }
    val files = m.files; val rules = m.rules; val query = m.search
    val compiled = remember(rules) { compileRules(rules) }
    var result by remember(files, compiled, query, path, tree) { mutableStateOf<Browser?>(null) }
    val browser = result ?: Browser(emptyList(), emptyList(), emptyList())
    val filtering = result == null
    LaunchedEffect(files, compiled, query, path, tree) {
        result = withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val matches = files.filterIndexed { i, item ->
                if (i % 128 == 0) ctx.ensureActive()
                item.path.contains(query.trim(), ignoreCase = true) && compiled.matches(item.path)
            }
            val dirs = sortedSetOf<String>(); val visible = mutableListOf<Entry>()
            if (tree) matches.forEach { entry ->
                if (entry.path.startsWith(path)) {
                    val rest = entry.path.removePrefix(path)
                    if ('/' in rest) dirs += rest.substringBefore('/') else visible += entry
                }
            }
            Browser(matches, dirs.toList(), if (tree) visible else matches)
        }
    }
    val ready = !m.busy && !filtering && compiled.errors.isEmpty()
    fun openSource(archive: Boolean) {
        if (quickPattern.isNotEmpty() && atHome && !quickValid) return
        m.openRules = when {
            quickValid -> listOf(quickRule)
            else -> m.nextPreset?.second.orEmpty()
        }
        if (archive) zip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
        else folder.launch(null)
    }
    fun presets() { showPresets = true; if (!m.busy) m.refreshPresets() }
    fun namePreset(kind: String) { presetName = ""; saveKind = kind }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (atHome) "ArchiveSift" else m.sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!atHome) IconButton(onClick = { home = true }, enabled = !m.busy) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { contentDescription = "Home" })
                    }
                }, actions = {
                    if (atHome) TextButton({ presets() }, enabled = !m.busy) { Text("Presets") }
                    else TextButton({ showRules = true }, enabled = !m.busy) { Text(if (rules.isEmpty()) "Filters" else "Filters (${rules.size})") }
                })
        },
        bottomBar = {
            if (m.busy || m.message.isNotEmpty() || !atHome) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.progress, Modifier.weight(1f)); TextButton(onClick = m::cancel) { Text("Cancel") }
                        }
                    }
                    if (m.message.isNotEmpty()) Text(m.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { m.message = "" })
                    if (!atHome) Button(enabled = ready && m.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        m.preview = files.filter { it.path in m.selected }
                    }) { Text("Preview export · ${m.selected.size} selected") }
                }
            }
        }
    ) { padding ->
        if (atHome) LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceCard("Open ZIP", true, !m.busy && quickError == null, Modifier.weight(1f)) { openSource(true) }
                    SourceCard("Open Folder", false, !m.busy && quickError == null, Modifier.weight(1f)) { openSource(false) }
                }
            }
            item {
                OutlinedTextField(quickPattern, { quickPattern = it.take(1024) }, Modifier.fillMaxWidth(),
                    label = { Text("Quick regex (optional)") }, placeholder = { Text("\\.json$") }, singleLine = true,
                    enabled = !m.busy, isError = quickError != null,
                    supportingText = { Text(quickError ?: "Applies only to the next source unless saved.") },
                    trailingIcon = { IconButton({ namePreset("quick") }, enabled = quickValid && !m.busy) {
                        Text("+", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "Save regex as a named preset" })
                    } })
                if (quickPattern.isEmpty()) m.nextPreset?.let { (label, _) ->
                    TextButton({ m.nextPreset = null }, enabled = !m.busy) { Text("Clear preset: $label", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (quickPattern.isNotEmpty()) TextButton({ quickPattern = "" }, enabled = !m.busy) { Text("Clear regex") }
            }
            if (m.sourceLabel.isNotEmpty()) item { TextButton({ home = false }, enabled = !m.busy) { Text("Back to current files") } }
        } else Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(m.search, { m.search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Search files") },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), enabled = !m.busy)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(selected = !tree, onClick = { tree = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = !m.busy) { Text("Matches") }
                SegmentedButton(selected = tree, onClick = { tree = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = !m.busy) { Text("Folders") }
            }
            val all = browser.matches.isNotEmpty() && browser.matches.all { it.path in m.selected }
            val any = !all && browser.matches.any { it.path in m.selected }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TriStateCheckbox(
                    state = if (all) ToggleableState.On else if (any) ToggleableState.Indeterminate else ToggleableState.Off,
                    onClick = {
                        val paths = browser.matches.mapTo(hashSetOf()) { it.path }
                        m.selected = if (all) m.selected - paths else m.selected + paths
                    }, enabled = ready && browser.matches.isNotEmpty(), modifier = Modifier.semantics { contentDescription = "Select all matches" })
                Column(Modifier.weight(1f)) {
                    Text("Select all matches", style = MaterialTheme.typography.labelLarge)
                    Text(if (filtering) "Filtering…" else "${browser.matches.size} of ${files.size} files", style = MaterialTheme.typography.bodySmall)
                }
                if (m.selected.isNotEmpty()) TextButton({ m.selected = emptySet() }, enabled = !m.busy) { Text("Clear") }
            }
            if (compiled.errors.isNotEmpty()) Text("Fix invalid filters to continue.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            if (tree) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (path.isNotEmpty()) TextButton({ path = path.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }) { Text("Up") }
                Text(if (path.isEmpty()) "Root folder" else path.dropLast(1), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (!filtering && browser.matches.isEmpty()) item { Text("No matching files", Modifier.padding(16.dp)) }
                items(browser.dirs, key = { "dir:$path$it" }) { dir ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = ready) { path += "$dir/" }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(dir, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider()
                }
                items(browser.visible, key = { "file:${it.path}" }) { entry ->
                    val checked = entry.path in m.selected
                    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = ready, role = Role.Checkbox) {
                        m.selected = if (it) m.selected + entry.path else m.selected - entry.path
                    }.heightIn(min = 52.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null, enabled = ready)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(entry.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!tree && '/' in entry.path) Text(entry.path.substringBeforeLast('/'), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(entry.size?.let { android.text.format.Formatter.formatShortFileSize(LocalContext.current, it) } ?: "—",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (showRules) ModalBottomSheet(onDismissRequest = { showRules = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton({ showRules = false; presets() }, enabled = !m.busy) { Text("Presets") }
                TextButton({ showRules = false }) { Text("Done") }
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rules.size) { index ->
                    val rule = rules[index]; val error = compiled.errors[index]
                    fun change(value: Rule) { m.rules = m.rules.toMutableList().also { it[index] = value } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(rule.exclude, { change(rule.copy(exclude = !rule.exclude)) }, { Text(if (rule.exclude) "Exclude" else "Include") }, enabled = !m.busy)
                        FilterChip(rule.mode == Mode.Regex, { change(rule.copy(mode = if (rule.mode == Mode.Glob) Mode.Regex else Mode.Glob)) }, { Text(rule.mode.name) }, enabled = !m.busy)
                        TextButton({ m.rules = m.rules.filterIndexed { i, _ -> i != index } }, enabled = !m.busy) { Text("Remove") }
                    }
                    OutlinedTextField(rule.text, { change(rule.copy(text = it.take(1024))) }, Modifier.fillMaxWidth(), label = { Text("Pattern ${index + 1}") },
                        singleLine = true, isError = error != null, supportingText = if (error == null) null else { { Text(error) } }, enabled = !m.busy)
                }
                item {
                    FlowRow {
                        TextButton({ m.rules += Rule(false, Mode.Regex, "") }, enabled = !m.busy && rules.size < MAX_RULES) { Text("+ Add rule") }
                        TextButton({ namePreset("rules") }, enabled = !m.busy && rules.isNotEmpty() && compiled.errors.isEmpty()) { Text("Save preset") }
                    }
                }
            }
        }
    }
    if (showPresets) ModalBottomSheet(onDismissRequest = { showPresets = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text("Saved presets", style = MaterialTheme.typography.titleLarge)
                TextButton({ m.importForNext = atHome; if (atHome) quickPattern = ""; readPreset.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = !m.busy) { Text("Import JSON") }
                if (m.presetNames.isEmpty() && !m.busy) Text("No saved presets")
            }
            items(m.presetNames, key = { it }) { preset ->
                Text(preset, style = MaterialTheme.typography.titleMedium)
                FlowRow {
                    TextButton({
                        m.loadPreset(preset, forNextSource = atHome)
                        if (atHome) quickPattern = ""
                        showPresets = false
                    }, enabled = !m.busy) { Text(if (atHome) "Use next" else "Apply") }
                    TextButton({ m.presetExportName = preset; writePreset.launch("ArchiveSift-rules.json") }, enabled = !m.busy) { Text("Export JSON") }
                    TextButton({ m.removePreset(preset) }, enabled = !m.busy) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
    saveKind?.let { kind ->
        AlertDialog(onDismissRequest = { saveKind = null }, title = { Text("Name this preset") }, text = {
            OutlinedTextField(presetName, { presetName = it.take(60) }, label = { Text("Preset name") }, singleLine = true)
        }, confirmButton = {
            TextButton({ m.savePreset(presetName, if (kind == "quick") listOf(quickRule) else rules); saveKind = null },
                enabled = !m.busy && presetName.isNotBlank()) { Text("Save") }
        }, dismissButton = { TextButton({ saveKind = null }) { Text("Cancel") } })
    }
    m.preview?.let { entries ->
        AlertDialog(onDismissRequest = { m.preview = null; showConflicts = false }, title = { Text("Export ${entries.size} files") },
            text = {
                Column {
                    Text(if (showConflicts) "If a name already exists, skip it or create a numbered copy. Existing files are not replaced."
                         else "Relative paths are preserved. Selection includes files hidden by filters.")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(entries, key = { it.path }) { entry -> Text("${entry.path} · ${entry.size?.let { "$it B" } ?: "unknown size"}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }, confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (showConflicts) Conflicts.entries.forEach { policy ->
                        Button({ m.conflictPolicy = policy; m.pending = entries; m.preview = null; showConflicts = false; saveFolder.launch(null) }) { Text("${policy.name} conflicts") }
                    } else {
                        Button({ m.pending = entries; m.preview = null; saveZip.launch("ArchiveSift.zip") }) { Text("Export as ZIP") }
                        TextButton({ m.preview = null; m.share(entries) }) { Text("Share ZIP") }
                        TextButton({ showConflicts = true }) { Text("Export to folder") }
                        TextButton({ m.pending = entries; m.preview = null; saveList.launch("ArchiveSift-files.csv") }) { Text("Save file list (CSV)") }
                    }
                }
            }, dismissButton = { TextButton({ if (showConflicts) showConflicts = false else m.preview = null }) { Text("Back") } })
    }
    m.folderPreview?.let { plan ->
        val skipped = plan.moves.count { it.target == null }
        val renamed = plan.moves.count { it.target != null && it.target != it.entry.path }
        AlertDialog(onDismissRequest = { if (!m.busy) m.folderPreview = null }, title = { Text("Review folder export") },
            text = {
                Column {
                    Text("${plan.moves.size - skipped} to write · $renamed renamed · $skipped skipped")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(plan.moves, key = { it.entry.path }) { move ->
                            Text(when (move.target) {
                                null -> "Skip ${move.entry.path} · ${move.reason}"
                                move.entry.path -> move.entry.path
                                else -> "${move.entry.path} → ${move.target}"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }, confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button({ m.confirmFolder(plan) }, enabled = !m.busy && skipped < plan.moves.size) { Text("Write files") }
                    TextButton({
                        m.folderPreview = null
                        val next = if (plan.policy == Conflicts.Skip) Conflicts.Rename else Conflicts.Skip
                        m.planFolder(plan.root, next, plan.entries)
                    }, enabled = !m.busy && (skipped != 0 || renamed != 0)) {
                        Text(if (plan.policy == Conflicts.Skip) "Rename conflicts instead" else "Skip conflicts instead")
                    }
                }
            }, dismissButton = { TextButton({ m.folderPreview = null }, enabled = !m.busy) { Text("Cancel") } })
    }

}

@Composable
private fun SourceCard(title: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = onClick, enabled = enabled, modifier = modifier, colors = CardDefaults.cardColors(
        containerColor = if (primary) colors.primary else colors.primaryContainer,
        contentColor = if (primary) colors.onPrimary else colors.onPrimaryContainer)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("↗", Modifier.align(Alignment.End).clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
