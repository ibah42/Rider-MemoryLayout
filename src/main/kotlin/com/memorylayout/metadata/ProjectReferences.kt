package com.memorylayout.metadata

/**
 * Which assemblies to read types from, worked out from the `.csproj` files Unity generates.
 *
 * The files name what the compiler is given, and for the framework that is the wrong thing: a
 * Unity project compiles against `NetStandard/ref/2.1.0/netstandard.dll`, a reference assembly
 * whose `List<T>` has no fields at all and whose `Guid` has one placeholder `int`. Those are
 * replaced with the runtime's own assemblies from the same editor install, which is also where
 * the game's real layout comes from -- Mono's `List<T>` rather than anybody else's.
 */
object ProjectReferences {

    private val HINT_PATH = Regex("<HintPath>([^<]+)</HintPath>")

    private const val NET_STANDARD_DIRECTORY = "/NetStandard/"

    private const val MONO_DIRECTORY = "/MonoBleedingEdge/"

    private const val MONO_LIBRARY_PATH = "MonoBleedingEdge/lib/mono/"

    /** `.../lib/mono/4.7.1-api/`: the .NET Framework profile's reference assemblies. */
    private val FRAMEWORK_REFERENCE_DIRECTORY = Regex("/lib/mono/[^/]+-api/", RegexOption.IGNORE_CASE)

    private val REFERENCE_DIRECTORIES = listOf("/NetStandard/ref/", "/NetStandard/compat/", "/NetStandard/Extensions/")

    /**
     * Where Unity keeps the .NET Framework profile's reference assemblies and their facades --
     * over a hundred files in an editor assembly's project, and not one real field among them.
     */
    private const val UNITY_REFERENCE_DIRECTORY = "/UnityReferenceAssemblies/"

    /**
     * The runtime profiles in the order they are worth trying. The editor and a Mono player run
     * the JIT profile; its field layout is the one a reader means unless they say otherwise.
     */
    private val RUNTIME_PROFILES = listOf("unityjit-win32", "unityjit-macos", "unityjit-linux", "4.5")

    private val RUNTIME_ASSEMBLY_NAMES = listOf("mscorlib.dll", "System.dll", "System.Core.dll")

    /** The project's own scripts, compiled: stale next to the sources the index reads. */
    private const val SCRIPT_ASSEMBLIES_DIRECTORY = "/ScriptAssemblies/"

    /** Editor-only and tens of megabytes; nothing in it is laid out in a running game. */
    private const val EDITOR_ASSEMBLY_PREFIX = "UnityEditor"

    fun hintPaths(projectFileText: String): List<String> {
        return HINT_PATH.findAll(projectFileText)
            .map { match -> unescape(match.groupValues[1].trim()) }
            .toList()
    }

    /**
     * The assemblies to read, runtime ones first so that they win a tie against a same-named type
     * in some package.
     *
     * @param directoryExists asked about the candidate runtime directories
     * @param fileExists asked about the runtime assemblies inside the one chosen
     */
    fun assembliesToRead(
        hintPaths: Collection<String>,
        directoryExists: (String) -> Boolean,
        fileExists: (String) -> Boolean,
    ): List<String> {
        val runtimeRoots = LinkedHashSet<String>()
        val others = ArrayList<String>()
        for (rawPath in hintPaths) {
            val path = normalize(rawPath)
            val root = installRootOfReference(path)
            if (root != null) {
                runtimeRoots.add(root)
                continue
            }
            if (path.contains(SCRIPT_ASSEMBLIES_DIRECTORY, ignoreCase = true)) {
                continue
            }
            if (fileNameOf(path).startsWith(EDITOR_ASSEMBLY_PREFIX, ignoreCase = true)) {
                continue
            }
            others.add(path)
        }
        val result = ArrayList<String>()
        for (root in runtimeRoots) {
            val profile = RUNTIME_PROFILES
                .map { name -> root + MONO_LIBRARY_PATH + name }
                .firstOrNull { directory -> directoryExists(directory) }
                ?: continue
            for (assemblyName in RUNTIME_ASSEMBLY_NAMES) {
                val assemblyPath = "$profile/$assemblyName"
                if (fileExists(assemblyPath)) {
                    result.add(assemblyPath)
                }
            }
        }
        result.addAll(others)
        val seen = HashSet<String>()
        return result.filter { path -> seen.add(path.lowercase()) }
    }

    /**
     * The editor install a reference assembly came from -- the directory holding `NetStandard`
     * and `MonoBleedingEdge` -- or null when the path is an ordinary assembly.
     */
    fun installRootOfReference(path: String): String? {
        val normalized = normalize(path)
        val isNetStandardReference = REFERENCE_DIRECTORIES.any { directory ->
            normalized.contains(directory, ignoreCase = true)
        }
        if (isNetStandardReference) {
            return normalized.substring(0, normalized.indexOf(NET_STANDARD_DIRECTORY, ignoreCase = true) + 1)
        }
        val unityReferences = normalized.indexOf(UNITY_REFERENCE_DIRECTORY, ignoreCase = true)
        if (unityReferences >= 0) {
            return normalized.substring(0, unityReferences + 1)
        }
        if (!FRAMEWORK_REFERENCE_DIRECTORY.containsMatchIn(normalized)) {
            return null
        }
        val monoDirectory = normalized.indexOf(MONO_DIRECTORY, ignoreCase = true)
        if (monoDirectory < 0) {
            // A Mono install outside a Unity editor: there is no Unity runtime to swap in.
            return null
        }
        return normalized.substring(0, monoDirectory + 1)
    }

    fun fileNameOf(path: String): String {
        return normalize(path).substringAfterLast('/')
    }

    private fun normalize(path: String): String {
        return path.replace('\\', '/')
    }

    private fun unescape(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
