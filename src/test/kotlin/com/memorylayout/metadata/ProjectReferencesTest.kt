package com.memorylayout.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectReferencesTest {

    private val editor = "C:/Program Files/Unity/6000.0.62f1/Editor/Data/"

    @Test
    fun readsHintPaths() {
        val text = """
            <Reference Include="netstandard">
              <HintPath>C:\Unity\Editor\Data\NetStandard\ref\2.1.0\netstandard.dll</HintPath>
            </Reference>
            <Reference Include="Odd"><HintPath>D:\A &amp; B\Odd.dll</HintPath></Reference>
        """.trimIndent()
        assertEquals(
            listOf("C:\\Unity\\Editor\\Data\\NetStandard\\ref\\2.1.0\\netstandard.dll", "D:\\A & B\\Odd.dll"),
            ProjectReferences.hintPaths(text),
        )
    }

    @Test
    fun replacesReferenceAssembliesWithTheRuntime() {
        val hintPaths = listOf(
            "C:\\Program Files\\Unity\\6000.0.62f1\\Editor\\Data\\NetStandard\\ref\\2.1.0\\netstandard.dll",
            "C:\\Program Files\\Unity\\6000.0.62f1\\Editor\\Data\\NetStandard\\compat\\2.1.0\\shims\\netfx\\mscorlib.dll",
            "C:\\Program Files\\Unity\\6000.0.62f1\\Editor\\Data\\Managed\\UnityEngine\\UnityEngine.CoreModule.dll",
            "C:\\Program Files\\Unity\\6000.0.62f1\\Editor\\Data\\Managed\\UnityEngine\\UnityEditor.CoreModule.dll",
            "C:\\Game\\Library\\ScriptAssemblies\\Game.Core.dll",
            "C:\\Program Files\\Unity\\6000.0.62f1\\Editor\\Data\\Managed\\UnityEngine\\UnityEngine.CoreModule.dll",
        )
        val runtime = editor + "MonoBleedingEdge/lib/mono/unityjit-win32"
        val result = ProjectReferences.assembliesToRead(
            hintPaths,
            { directory -> directory == runtime },
            { path -> path.startsWith(runtime) && !path.endsWith("System.Core.dll") },
        )
        assertEquals(
            listOf(
                "$runtime/mscorlib.dll",
                "$runtime/System.dll",
                editor + "Managed/UnityEngine/UnityEngine.CoreModule.dll",
            ),
            result,
        )
    }

    @Test
    fun recognisesTheFrameworkProfilesReferenceAssemblies() {
        val path = editor + "MonoBleedingEdge/lib/mono/4.7.1-api/mscorlib.dll"
        assertEquals(editor, ProjectReferences.installRootOfReference(path))
        assertNull(ProjectReferences.installRootOfReference(editor + "Managed/UnityEngine/UnityEngine.dll"))
        assertNull(ProjectReferences.installRootOfReference("/usr/lib/mono/4.7.1-api/mscorlib.dll"))
        val facade = editor + "UnityReferenceAssemblies/unity-4.8-api/Facades/System.Runtime.dll"
        assertEquals(editor, ProjectReferences.installRootOfReference(facade))
    }
}
