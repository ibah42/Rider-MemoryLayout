package com.memorylayout.index

import com.memorylayout.layout.LookupContext
import com.memorylayout.layout.TypeKind
import com.memorylayout.metadata.ClosureMatch
import com.memorylayout.metadata.Closures
import com.memorylayout.metadata.MetadataFormatException
import com.memorylayout.metadata.MetadataReader
import com.memorylayout.metadata.MetadataType
import com.memorylayout.metadata.MetadataTypeKind
import com.memorylayout.metadata.MetadataTypeLookup
import com.memorylayout.metadata.ProjectReferences
import com.memorylayout.layout.DeclaredType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException

/**
 * The types of the assemblies the project compiles against, read from their metadata.
 *
 * Loaded once, on first use, off the UI thread: every caller already runs in the background, and
 * reading a few dozen assemblies takes a moment the first time and nothing after that. The files
 * are read through `java.io` rather than the VFS on purpose -- they live in the editor install and
 * in `Library`, outside anything the IDE is asked to watch.
 */
@Service(Service.Level.PROJECT)
class RuntimeAssemblyService(private val project: Project) {

    private val lock = Any()

    @Volatile
    private var lookup: MetadataTypeLookup? = null

    @Volatile
    private var readAssemblies: List<String> = emptyList()

    /** The lookup, loading it first if nobody has yet. Blocks: never call on the UI thread. */
    fun lookup(): MetadataTypeLookup {
        val loaded = lookup
        if (loaded != null) {
            return loaded
        }
        synchronized(lock) {
            val again = lookup
            if (again != null) {
                return again
            }
            val fresh = load()
            lookup = fresh
            return fresh
        }
    }

    /** Forgets what was read; the next question reads the assemblies again. */
    fun reset() {
        synchronized(lock) {
            lookup = null
            readAssemblies = emptyList()
        }
    }

    val assemblies: List<String>
        get() = readAssemblies

    /** A closure class found in the project's own compiled assemblies, and which file it came from. */
    class CompiledClosure(val match: ClosureMatch, val assemblyPath: String, val assemblyModified: Long)

    @Volatile
    private var scriptLookup: MetadataTypeLookup? = null

    private var scriptTypes: List<MetadataType> = emptyList()

    private var scriptStamps: Map<String, Long> = emptyMap()

    /**
     * The types of `Library/ScriptAssemblies`: the project's own code as Unity last compiled it.
     *
     * Never mixed into [lookup] -- for a type with source, the source is the truth and these are a
     * possibly stale copy. They are read for what only a compiler knows: the closure classes it
     * generated. Read again whenever a file there changes, which is every Unity recompile.
     */
    fun scriptAssemblyLookup(): MetadataTypeLookup {
        synchronized(lock) {
            val stamps = scriptAssemblyStamps()
            val loaded = scriptLookup
            if (loaded != null && stamps == scriptStamps) {
                return loaded
            }
            val types = ArrayList<MetadataType>()
            for (path in stamps.keys) {
                val file = File(path)
                try {
                    types.addAll(MetadataReader.read(file.readBytes(), file.name))
                } catch (failure: IOException) {
                    LOG.info("Memory Layout: cannot read $path: ${failure.message}")
                } catch (failure: MetadataFormatException) {
                    LOG.info("Memory Layout: $path is not a managed assembly: ${failure.message}")
                }
            }
            val fresh = MetadataTypeLookup(types)
            scriptTypes = types
            scriptStamps = stamps
            scriptLookup = fresh
            return fresh
        }
    }

    /** The closure class a lambda was compiled into, or null when the compiled code has none. */
    fun findClosure(containerQualifiedName: String, memberName: String, capturedNames: List<String>): CompiledClosure? {
        scriptAssemblyLookup()
        val types: List<MetadataType>
        val stamps: Map<String, Long>
        synchronized(lock) {
            types = scriptTypes
            stamps = scriptStamps
        }
        val match = Closures.find(types, containerQualifiedName, memberName, capturedNames) ?: return null
        val path = stamps.keys.firstOrNull { candidate -> File(candidate).name == match.type.assemblyName } ?: ""
        return CompiledClosure(match, path, stamps[path] ?: 0L)
    }

    fun entryOfClosure(closure: CompiledClosure, source: ClosureSource): IndexedType {
        return entryOf(closure.match.type).copy(closure = source)
    }

    private fun scriptAssemblyStamps(): Map<String, Long> {
        val basePath = project.basePath ?: return emptyMap()
        val directory = File(basePath, SCRIPT_ASSEMBLIES_PATH)
        val files = directory.listFiles { candidate ->
            candidate.isFile && candidate.name.endsWith(ASSEMBLY_EXTENSION, ignoreCase = true)
        } ?: return emptyMap()
        val stamps = LinkedHashMap<String, Long>()
        for (file in files.sortedBy { candidate -> candidate.name }) {
            stamps[file.path] = file.lastModified()
        }
        return stamps
    }

    /** The written name as index entries, so the window can open a type that has no source. */
    fun candidates(typeName: String, context: LookupContext): List<IndexedType> {
        return lookup().rankedCandidates(typeName, context).map { type -> entryOf(type) }
    }

    fun isRuntimeEntry(entry: IndexedType): Boolean {
        return entry.fileUrl.startsWith(MetadataTypeLookup.FILE_ID_PREFIX)
    }

    fun declaredTypeOf(entry: IndexedType): DeclaredType? {
        val runtimeType = lookup().typeWithFileId(entry.fileUrl)
        if (runtimeType != null) {
            return lookup().declaredTypeOf(runtimeType)
        }
        val compiled = scriptAssemblyLookup()
        val compiledType = compiled.typeWithFileId(entry.fileUrl) ?: return null
        return compiled.declaredTypeOf(compiledType)
    }

    private fun entryOf(type: MetadataType): IndexedType {
        return IndexedType(
            simpleName = type.name,
            arity = type.genericParameters.size,
            qualifiedName = type.qualifiedName,
            namespaceName = type.namespaceName,
            containerNames = type.containerNames,
            kind = kindOf(type.kind),
            fileUrl = MetadataTypeLookup.fileIdOf(type),
            filePresentableName = type.assemblyName,
            declarationOffset = NO_DECLARATION_OFFSET,
        )
    }

    private fun kindOf(kind: MetadataTypeKind): TypeKind {
        return when (kind) {
            MetadataTypeKind.CLASS -> {
                TypeKind.CLASS
            }
            MetadataTypeKind.STRUCT -> {
                TypeKind.STRUCT
            }
            MetadataTypeKind.ENUM -> {
                TypeKind.ENUM
            }
            MetadataTypeKind.INTERFACE -> {
                TypeKind.INTERFACE
            }
        }
    }

    private fun load(): MetadataTypeLookup {
        val paths = assemblyPaths()
        val types = ArrayList<MetadataType>()
        val read = ArrayList<String>()
        for (path in paths) {
            val file = File(path)
            try {
                types.addAll(MetadataReader.read(file.readBytes(), file.name))
                read.add(path)
            } catch (failure: IOException) {
                LOG.info("Memory Layout: cannot read $path: ${failure.message}")
            } catch (failure: MetadataFormatException) {
                // Native plugins sit next to managed ones under Plugins/ and share the extension.
                LOG.info("Memory Layout: $path is not a managed assembly: ${failure.message}")
            }
        }
        readAssemblies = read
        return MetadataTypeLookup(types)
    }

    private fun assemblyPaths(): List<String> {
        val basePath = project.basePath ?: return emptyList()
        val projectFiles = File(basePath).listFiles { candidate ->
            candidate.isFile && candidate.name.endsWith(PROJECT_FILE_EXTENSION, ignoreCase = true)
        } ?: return emptyList()
        val hintPaths = LinkedHashSet<String>()
        for (projectFile in projectFiles) {
            try {
                hintPaths.addAll(ProjectReferences.hintPaths(projectFile.readText()))
            } catch (failure: IOException) {
                LOG.info("Memory Layout: cannot read ${projectFile.path}: ${failure.message}")
            }
        }
        return ProjectReferences.assembliesToRead(
            hintPaths,
            { directory -> File(directory).isDirectory },
            { path -> File(path).isFile },
        )
    }

    companion object {
        private val LOG = Logger.getInstance(RuntimeAssemblyService::class.java)

        private const val PROJECT_FILE_EXTENSION = ".csproj"

        private const val SCRIPT_ASSEMBLIES_PATH = "Library/ScriptAssemblies"

        private const val ASSEMBLY_EXTENSION = ".dll"

        private const val NO_DECLARATION_OFFSET = -1

        fun getInstance(project: Project): RuntimeAssemblyService {
            return project.service()
        }
    }
}
