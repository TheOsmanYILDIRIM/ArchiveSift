package com.archivesift.core

enum class Conflicts { Skip, Rename }
data class TargetDoc(val id: String, val name: String, val folder: Boolean)
data class FolderMove(val entry: Entry, val target: String?, val reason: String? = null)
data class FolderTarget(val path: String, val docId: String?)
data class FolderTargets(val moves: List<FolderMove>, val directories: Map<String, FolderTarget>, val knownIds: Set<String>)

private fun renamed(name: String, index: Int, directory: Boolean): String {
    val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    return if (dot < 0) "$name ($index)" else "${name.take(dot)} ($index)${name.substring(dot)}"
}

fun planFolderTargets(rootId: String, entries: List<Entry>, policy: Conflicts,
                      children: (String) -> List<TargetDoc>, check: () -> Unit = {}, update: (String) -> Unit = {}): FolderTargets {
    val paths = PathIndex()
    val root = FolderTarget("", rootId)
    val bySource = mutableMapOf<String, FolderTarget?>("" to root)
    val byTarget = mutableMapOf("" to root)
    val knownIds = hashSetOf(rootId)
    val taken = mutableMapOf<String, java.util.TreeMap<String, MutableList<TargetDoc>>>()
    var inspected = 0
    val suffixes = mutableMapOf<Pair<String, String>, Int>()
    fun names(parent: FolderTarget) = taken.getOrPut(parent.path) {
        java.util.TreeMap<String, MutableList<TargetDoc>>(String.CASE_INSENSITIVE_ORDER).apply {
            parent.docId?.let { id -> children(id).forEach { doc ->
                check(); require(++inspected <= MAX_ENTRIES * 2) { "Destination metadata limit exceeded." }
                knownIds += doc.id
                getOrPut(doc.name) { mutableListOf() }.add(doc)
            } }
        }
    }
    fun reserve(parent: FolderTarget, original: String, folder: Boolean): String? {
        val names = names(parent); var name = original
        if (names.containsKey(name)) {
            if (policy == Conflicts.Skip) return null
            val key = parent.path to original; var i = suffixes[key] ?: 2
            do { check(); name = renamed(original, i++, folder) } while (names.containsKey(name))
            suffixes[key] = i
        }
        // An empty list reserves a name for one planned output; it is not an existing folder.
        names[name] = mutableListOf()
        return name
    }
    fun directory(sourcePath: String): FolderTarget? {
        if (sourcePath in bySource) return bySource[sourcePath]
        val parent = directory(sourcePath.substringBeforeLast('/', ""))
        val dir = parent?.let {
            val original = sourcePath.substringAfterLast('/')
            val existing = names(it)[original]?.singleOrNull()?.takeIf { doc -> doc.folder && doc.name == original }
            val name = if (existing != null) original else reserve(it, original, true)
            name?.let { n ->
                val path = if (it.path.isEmpty()) n else "${it.path}/$n"
                FolderTarget(path, existing?.id).also { d -> byTarget[path] = d }
            }
        }
        bySource[sourcePath] = dir
        return dir
    }
    val moves = entries.mapIndexed { i, entry ->
        check(); require(safePath(entry.path) == entry.path); paths.add(entry.path, false)
        if (i % 128 == 0) update("Checking destination · $i paths")
        val parent = directory(entry.path.substringBeforeLast('/', ""))
        val name = parent?.let { reserve(it, entry.path.substringAfterLast('/'), false) }
        if (name == null) FolderMove(entry, null, if (parent == null) "Folder name conflict" else "File name conflict")
        else FolderMove(entry, if (parent.path.isEmpty()) name else "${parent.path}/$name")
    }
    return FolderTargets(moves, byTarget, knownIds)
}
