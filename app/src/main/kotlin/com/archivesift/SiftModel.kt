package com.archivesift

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.archivesift.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SiftModel(private val app: Application) : AndroidViewModel(app) {
    private val source = AtomicReference<Source?>()
    private val cleared = AtomicBoolean()
    var files by mutableStateOf(emptyList<Entry>()); private set
    var sourceVersion by mutableLongStateOf(0L); private set
    var nextPreset by mutableStateOf<Pair<String, List<Rule>>?>(null)
    var openRules: List<Rule> = emptyList()
    var sourceLabel by mutableStateOf(""); private set
    var search by mutableStateOf("")
    var rules by mutableStateOf(emptyList<Rule>())
    var selected by mutableStateOf(emptySet<String>())
    var preview by mutableStateOf<List<Entry>?>(null)
    var pending by mutableStateOf(emptyList<Entry>())
    var conflictPolicy by mutableStateOf(Conflicts.Skip)
    var folderPreview by mutableStateOf<FolderPreview?>(null)
    var shareFile by mutableStateOf<File?>(null)
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var progress by mutableStateOf(""); private set
    var presetNames by mutableStateOf(emptyList<String>()); private set
    private val presets by lazy { Presets(app) }
    private val resolver get() = app.contentResolver
    private var stop: AtomicBoolean? = null

    fun cancel() { stop?.set(true); progress = "Cancelling…" }
    private fun <T> run(label: String, block: (() -> Unit, (String) -> Unit) -> T, finish: (T) -> Unit) {
        if (busy) return
        busy = true; message = ""; progress = label
        val cancelled = AtomicBoolean(); stop = cancelled
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext(); var last = 0L
                    block({ context.ensureActive(); if (cancelled.get()) throw IOException("Cancelled.") }, { text ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 180 && !cancelled.get()) {
                            last = now
                            viewModelScope.launch { if (stop === cancelled && !cancelled.get()) progress = text }
                        }
                    })
                }
                finish(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Operation failed." }
            finally { busy = false; stop = null }
        }
    }

    fun open(uri: Uri, archive: Boolean) {
        val snapshot = openRules
        run("Opening source…", { check, update ->
            val next = if (archive) openZip(resolver, uri, app.cacheDir, check, update) else openFolder(resolver, uri, check, update)
            try { check() } catch (e: Exception) { next.close(); throw e }
            runCatching { source.getAndSet(next)?.close() }
            if (cleared.get()) runCatching { source.getAndSet(null)?.close() }
            next.files
        }, {
            files = it; selected = emptySet(); preview = null; search = ""
            rules = snapshot; nextPreset = null; openRules = emptyList(); sourceVersion++
            sourceLabel = if (archive) "ZIP archive" else "Local folder"; message = ""
        })
    }

    private fun saveDocument(uri: Uri, check: () -> Unit, write: () -> Unit) {
        try { check(); write(); check() }
        catch (e: Exception) {
            if (!runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) {
                throw IOException("${e.message ?: "Export stopped."} The provider could not remove the partial output. Check your chosen destination.", e)
            }
            throw e
        }
    }

    fun export(uri: Uri, type: String) {
        val entries = pending; pending = emptyList()
        if (entries.isEmpty()) return
        run("Exporting…", { check, update -> when (type) {
            "folder" -> error("Folder exports require destination preview.")
            else -> {
                saveDocument(uri, check) {
                    if (type == "zip") resolver.output(uri).use { writeZip(entries, it, check,
                        { bytes -> update("Exporting ZIP · ${bytes / 1024} KiB") }) }
                    else resolver.output(uri).use { writeFileList(entries, it, check) }
                }
                if (type == "zip") "Saved ZIP with ${entries.size} files." else "Saved CSV for ${entries.size} paths."
            }
        } }, { message = it })
    }

    fun planFolder(uri: Uri, policy: Conflicts, entries: List<Entry> = pending) {
        pending = emptyList()
        if (entries.isEmpty()) return
        run("Checking destination…", { check, update -> previewFolder(resolver, uri, entries, policy, check, update) }, {
            folderPreview = it
        })
    }
    fun confirmFolder(preview: FolderPreview) {
        folderPreview = null
        run("Exporting folder…", { check, update -> exportFolder(resolver, preview, check, update) }, { message = it })
    }

    fun share(entries: List<Entry>) = run("Preparing ZIP to share…", { check, update ->
        val dir = File(app.cacheDir, "share"); dir.mkdirs()
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val f = File.createTempFile("ArchiveSift-", ".zip", dir)
        try {
            f.outputStream().use { writeZip(entries, it, check, { bytes -> update("Preparing ZIP · ${bytes / 1024} KiB") }) }
            check(); f
        } catch (e: Exception) { f.delete(); throw e }
    }, { shareFile = it })

    fun readRegexFile(uri: Uri, finish: (String) -> Unit) = run("Reading regex…", { check, _ ->
        // Allow UTF-8 multibyte text, a BOM and a final CRLF.
        val limit = MAX_REGEX_LENGTH * 4L + 5
        val bytes = ByteArrayOutputStream()
        (resolver.openInputStream(uri) ?: throw IOException("Could not open the regex file.")).use {
            copyBounded(it, bytes, ByteBudget(limit), limit, check)
        }
        val decoded = try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (e: CharacterCodingException) {
            throw IOException("Use a UTF-8 text file containing one regex.", e)
        }
        val pattern = decoded.removePrefix("\uFEFF").let {
            when {
                it.endsWith("\r\n") -> it.dropLast(2)
                it.endsWith("\n") || it.endsWith("\r") -> it.dropLast(1)
                else -> it
            }
        }
        require(pattern.isNotEmpty()) { "The regex file is empty." }
        require(pattern.length <= MAX_REGEX_LENGTH) { "Regex exceeds $MAX_REGEX_LENGTH characters." }
        check()
        pattern
    }, finish)

    fun refreshPresets() = run("Loading presets…", { _, _ -> presets.names() }, { presetNames = it })
    fun savePreset(name: String, snapshot: List<Rule> = rules) {
        run("Saving preset…", { check, _ -> check(); presets.save(name, snapshot); presets.names() }, {
            presetNames = it; message = "Preset saved locally."
        })
    }
    fun loadPreset(name: String, forNextSource: Boolean = false) = run("Loading preset…", { _, _ -> presets.load(name) }, {
        if (forNextSource) nextPreset = name to it else rules = it
        message = if (forNextSource) "Preset selected for the next source." else "Preset applied."
    })
    fun removePreset(name: String) = run("Removing preset…", { _, _ -> presets.remove(name); presets.names() }, { presetNames = it })
    var importForNext = false
    fun importPreset(uri: Uri, forNextSource: Boolean) = run("Importing preset…", { check, _ ->
        val preset = presets.read(uri, check)
        check(); presets.save(preset.first, preset.second); preset to presets.names()
    }, { (preset, names) ->
        if (forNextSource) nextPreset = preset else rules = preset.second
        presetNames = names
        message = if (forNextSource) "Preset imported for the next source." else "Preset imported and applied."
    })
    var presetExportName by mutableStateOf("")
    fun exportPreset(uri: Uri) {
        val name = presetExportName
        run("Saving preset JSON…", { check, _ ->
            saveDocument(uri, check) {
                val text = encodePreset(name, presets.load(name))
                resolver.output(uri).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
        }, { message = "Preset JSON saved." })
    }
    override fun onCleared() {
        cleared.set(true)
        stop?.set(true)
        runCatching { source.getAndSet(null)?.close() }
        super.onCleared()
    }
}
