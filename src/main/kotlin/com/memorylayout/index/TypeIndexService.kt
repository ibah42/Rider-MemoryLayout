package com.memorylayout.index

import com.memorylayout.layout.CodeMask
import com.memorylayout.layout.DeclaredType
import com.memorylayout.layout.TypeDeclaration
import com.memorylayout.layout.TypeKind
import com.memorylayout.layout.TypeMatching
import com.memorylayout.layout.TypeScanner
import com.memorylayout.settings.IndexScope
import com.memorylayout.settings.MemoryLayoutSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * One type declaration, remembered by where it lives rather than by its contents.
 *
 * The fields are read back out of the file when somebody actually asks for the layout, which keeps
 * the index small enough to hold a whole Unity project.
 */
data class IndexedType(
    val simpleName: String,

    /** How many type parameters the declaration takes: `Box` and `Box<T>` are different types. */
    val arity: Int,
    val qualifiedName: String,
    val namespaceName: String,
    val containerNames: List<String>,
    val kind: TypeKind,
    val fileUrl: String,
    val filePresentableName: String,
    val declarationOffset: Int,
)

/**
 * Knows which C# types the project declares and where.
 *
 * It exists because Rider's own answer to that question lives on the ReSharper backend, out of
 * reach of a frontend plugin. What it holds is deliberately thin -- a name, a place -- and the
 * text is masked again on demand, with a small cache for the files being looked at right now.
 */
@Service(Service.Level.PROJECT)
class TypeIndexService(private val project: Project) : Disposable {

    private val lock = Any()

    private val entriesBySimpleName = HashMap<String, MutableList<IndexedType>>()

    /** Masked text of the files recently asked about, newest last. */
    private val maskedCache = LinkedHashMap<String, CachedFile>()

    @Volatile
    private var indexed = false

    @Volatile
    private var indexing = false

    private class CachedFile(val modificationStamp: Long, val masked: String)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    handleFileEvents(events)
                }
            },
        )
    }

    val isReady: Boolean
        get() = indexed

    /**
     * Builds the index unless it is already there.
     *
     * @param onReady run on a background thread once the index can answer questions
     */
    fun buildIfNeeded(onReady: () -> Unit) {
        if (indexed) {
            onReady()
            return
        }
        if (indexing) {
            return
        }
        indexing = true
        object : Task.Backgroundable(project, INDEXING_TITLE, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    build(indicator)
                    indexed = true
                    onReady()
                } finally {
                    indexing = false
                }
            }
        }.queue()
    }

    fun rebuild(onReady: () -> Unit) {
        synchronized(lock) {
            entriesBySimpleName.clear()
            maskedCache.clear()
        }
        indexed = false
        buildIfNeeded(onReady)
    }

    /** Every declaration whose name could be what was written, best candidates left to the caller. */
    fun candidates(typeName: String): List<IndexedType> {
        val simpleName = TypeMatching.simpleName(typeName)
        synchronized(lock) {
            return entriesBySimpleName[simpleName]?.toList() ?: emptyList()
        }
    }

    /** Reads the declaration back out of its file, ready for the engine. */
    fun declaredTypeOf(entry: IndexedType): DeclaredType? {
        val file = VirtualFileManager.getInstance().findFileByUrl(entry.fileUrl) ?: return null
        val masked = maskedSourceOf(file) ?: return null
        val declarations = TypeScanner.scan(masked)
        val declaration = declarations.firstOrNull { candidate ->
            candidate.declarationOffset == entry.declarationOffset && candidate.name == entry.simpleName
        } ?: declarations.firstOrNull { candidate -> candidate.qualifiedName == entry.qualifiedName }
        if (declaration == null) {
            return null
        }
        return DeclaredType(declaration, masked, entry.fileUrl)
    }

    /**
     * The masked text of a file, from the editor's document when one is open, so that a layout
     * reflects what is on screen rather than what was last saved.
     */
    fun maskedSourceOf(file: VirtualFile): String? {
        val stamp = currentStampOf(file)
        synchronized(lock) {
            val cached = maskedCache[file.url]
            if (cached != null && cached.modificationStamp == stamp) {
                return cached.masked
            }
        }
        val text = readText(file) ?: return null
        val masked = CodeMask.of(text)
        synchronized(lock) {
            maskedCache[file.url] = CachedFile(stamp, masked)
            while (maskedCache.size > MASKED_CACHE_SIZE) {
                val oldest = maskedCache.keys.first()
                maskedCache.remove(oldest)
            }
        }
        return masked
    }

    /** Re-reads one file; used both while building and when the file changes on disk. */
    fun indexFile(file: VirtualFile) {
        val masked = maskedSourceOf(file) ?: return
        val declarations = TypeScanner.scan(masked)
        synchronized(lock) {
            removeEntriesOf(file.url)
            for (declaration in declarations) {
                addEntry(declaration, file)
            }
        }
    }

    private fun build(indicator: ProgressIndicator) {
        val files = ReadAction.compute<List<VirtualFile>, RuntimeException> { collectSourceFiles() }
        indicator.isIndeterminate = false
        var processed = 0
        for (file in files) {
            indicator.checkCanceled()
            indexFile(file)
            processed++
            indicator.fraction = processed.toDouble() / files.size.toDouble()
        }
    }

    private fun addEntry(declaration: TypeDeclaration, file: VirtualFile) {
        val entry = IndexedType(
            simpleName = declaration.name,
            arity = declaration.genericParameters.size,
            qualifiedName = declaration.qualifiedName,
            namespaceName = declaration.namespaceName,
            containerNames = declaration.containerNames,
            kind = declaration.kind,
            fileUrl = file.url,
            filePresentableName = file.name,
            declarationOffset = declaration.declarationOffset,
        )
        entriesBySimpleName.getOrPut(entry.simpleName) { ArrayList() }.add(entry)
    }

    private fun removeEntriesOf(fileUrl: String) {
        val emptyNames = ArrayList<String>()
        for (entry in entriesBySimpleName) {
            entry.value.removeAll { indexed -> indexed.fileUrl == fileUrl }
            if (entry.value.isEmpty()) {
                emptyNames.add(entry.key)
            }
        }
        for (name in emptyNames) {
            entriesBySimpleName.remove(name)
        }
    }

    private fun handleFileEvents(events: List<VFileEvent>) {
        if (!indexed) {
            return
        }
        for (event in events) {
            val file = event.file ?: continue
            if (!isSourceFile(file)) {
                continue
            }
            synchronized(lock) {
                maskedCache.remove(file.url)
            }
            if (!file.isValid) {
                synchronized(lock) {
                    removeEntriesOf(file.url)
                }
                continue
            }
            indexFile(file)
        }
    }

    private fun collectSourceFiles(): List<VirtualFile> {
        val files = ArrayList<VirtualFile>()
        for (root in rootsToWalk()) {
            VfsUtilCore.iterateChildrenRecursively(
                root,
                { candidate -> !isIgnoredDirectory(candidate) },
                { candidate ->
                    if (isSourceFile(candidate)) {
                        files.add(candidate)
                    }
                    true
                },
            )
        }
        return files
    }

    private fun rootsToWalk(): List<VirtualFile> {
        val baseDirectory = project.baseDirectory() ?: return emptyList()
        val scope = MemoryLayoutSettings.getInstance().indexScope
        if (scope == IndexScope.WHOLE_PROJECT) {
            return listOf(baseDirectory)
        }
        val roots = ArrayList<VirtualFile>()
        val assets = baseDirectory.findChild(ASSETS_DIRECTORY)
        if (assets != null) {
            roots.add(assets)
        }
        if (scope == IndexScope.ASSETS_AND_PACKAGES) {
            val packages = baseDirectory.findChild(PACKAGES_DIRECTORY)
            if (packages != null) {
                roots.add(packages)
            }
        }
        if (roots.isEmpty()) {
            // Not a Unity project after all: walking the whole thing beats indexing nothing.
            return listOf(baseDirectory)
        }
        return roots
    }

    private fun Project.baseDirectory(): VirtualFile? {
        val path = basePath ?: return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        return !file.isDirectory && file.extension.equals(SOURCE_EXTENSION, ignoreCase = true)
    }

    private fun isIgnoredDirectory(file: VirtualFile): Boolean {
        if (!file.isDirectory) {
            return false
        }
        return file.name in IGNORED_DIRECTORIES
    }

    private fun currentStampOf(file: VirtualFile): Long {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        if (document != null) {
            return document.modificationStamp
        }
        return file.modificationStamp
    }

    private fun readText(file: VirtualFile): String? {
        return ReadAction.compute<String?, RuntimeException> {
            val document = FileDocumentManager.getInstance().getCachedDocument(file)
            if (document != null) {
                return@compute document.immutableCharSequence.toString()
            }
            try {
                VfsUtilCore.loadText(file)
            } catch (failure: java.io.IOException) {
                null
            }
        }
    }

    override fun dispose() {
        synchronized(lock) {
            entriesBySimpleName.clear()
            maskedCache.clear()
        }
    }

    companion object {
        private const val INDEXING_TITLE = "Indexing C# types"

        private const val SOURCE_EXTENSION = "cs"

        private const val ASSETS_DIRECTORY = "Assets"

        private const val PACKAGES_DIRECTORY = "Packages"

        /** Unity and build leftovers: thousands of files, none of them the project's own sources. */
        private val IGNORED_DIRECTORIES = setOf(
            "Library", "Temp", "Logs", "obj", "bin", "Build", "Builds",
            ".git", ".idea", ".vs", ".plastic", "node_modules",
        )

        private const val MASKED_CACHE_SIZE = 32

        fun getInstance(project: Project): TypeIndexService {
            return project.service()
        }
    }
}
