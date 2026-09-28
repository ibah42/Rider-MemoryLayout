package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.fieldNames
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the scanner has to survive before the engine gets to count anything. */
class TypeScannerTest {

    private fun scan(source: String): List<TypeDeclaration> {
        return TypeScanner.scan(CodeMask.of(source))
    }

    @Test
    fun aBraceInsideAnAttributeDoesNotEndTheNamespace() {
        // Unity.Collections' NativeList<T> is declared exactly like this; the `}` of the array
        // initializer used to end the namespace block and drop every type after it.
        val declarations = scan(
            """
            namespace Unity.Collections
            {
                public interface IIndexable<T> where T : unmanaged
                {
                    int Length { get; set; }
                }

                [GenerateTestsForBurstCompatibility(GenericTypeArguments = new [] { typeof(int) })]
                public unsafe struct NativeList<T>
                    : INativeDisposable
                    , INativeList<T>
                    where T : unmanaged
                {
                    internal UnsafeList<T>* m_ListData;
                }

                public struct After
                {
                    public int value;
                }
            }
            """
        )
        assertEquals(
            listOf("IIndexable", "NativeList", "After"),
            declarations.map { declaration -> declaration.name },
        )
    }

    @Test
    fun aParameterNamedRecordIsNotADeclaration() {
        val declarations = scan(
            """
            struct Store
            {
                public void Save(Record record)
                {
                    Write(record);
                }

                public int value;
            }
            """
        )
        assertEquals(listOf("Store"), declarations.map { declaration -> declaration.name })
    }

    @Test
    fun aMethodBodyIsNotSearchedForFields() {
        val layout = layoutOf(
            """
            struct WithMethod
            {
                public int value;

                public void Reset()
                {
                    int local = 0;
                    value = local;
                }
            }
            """,
            "WithMethod",
        )
        assertEquals(listOf("value"), fieldNames(layout))
    }

    @Test
    fun fileScopedNamespaceQualifiesEverythingBelowIt() {
        val declarations = scan(
            """
            namespace Game.Data;

            struct Chunk
            {
                public int index;
            }
            """
        )
        assertEquals("Game.Data.Chunk", declarations.single().qualifiedName)
    }

    @Test
    fun blockNamespaceAndNestedTypesBuildTheQualifiedName() {
        val declarations = scan(
            """
            namespace Game
            {
                public struct Outer
                {
                    public struct Inner
                    {
                        public int value;
                    }
                }
            }
            """
        )
        assertEquals(
            listOf("Game.Outer", "Game.Outer.Inner"),
            declarations.map { declaration -> declaration.qualifiedName },
        )
    }

    @Test
    fun aNestedStructResolvesByItsSimpleName() {
        val layout = layoutOf(
            """
            namespace Game
            {
                public struct Outer
                {
                    public struct Inner
                    {
                        public int value;
                        public byte flag;
                    }

                    public Inner inner;
                    public byte header;
                }
            }
            """,
            "Outer",
        )
        assertEquals(8, field(layout, "inner").size)
        assertEquals(12, layout.size)
    }

    @Test
    fun aGenericStructIsReportedWithItsParameters() {
        val declarations = scan("struct Box<T> where T : struct { T value; }")
        assertEquals("Box<T>", declarations.single().displayName)
        assertEquals(listOf("T"), declarations.single().genericParameters)
    }

    @Test
    fun aGenericParameterHasNoKnownSize() {
        val layout = layoutOf("struct Box<T> where T : struct { T value; int count; }", "Box")
        assertEquals(LayoutConfidence.APPROXIMATE, layout.confidence)
        assertEquals(NodeKind.UNRESOLVED, field(layout, "value").kind)
    }

    @Test
    fun theStructLayoutAttributeIsReadOffTheDeclaration() {
        val declarations = scan(
            """
            [StructLayout(LayoutKind.Explicit, Size = 8, Pack = 2)]
            struct Union
            {
                [FieldOffset(0)] public int value;
            }
            """
        )
        val attribute = FieldReader.readLayoutAttribute(declarations.single())
        assertEquals(DeclaredLayoutKind.EXPLICIT, attribute.kind)
        assertEquals(8, attribute.declaredSize)
        assertEquals(2, attribute.pack)
    }

    @Test
    fun bracesInVerbatimAndRawStringsDoNotOpenBlocks() {
        val source = "struct Text\n{\n    string a = @\"} { \"\" }\";\n    string b = \"\"\"{ } \"\"\";\n    int value;\n}\n"
        val declarations = scan(source)
        assertEquals(listOf("Text"), declarations.map { declaration -> declaration.name })
        val layout = layoutOf(source, "Text")
        assertEquals(listOf("a", "b", "value"), fieldNames(layout))
    }

    @Test
    fun anInterpolatedStringDoesNotHideAField() {
        val layout = layoutOf(
            "struct Message\n{\n    string text = \$\"{value} items\";\n    int value;\n}\n",
            "Message",
        )
        assertEquals(listOf("text", "value"), fieldNames(layout))
    }

    @Test
    fun aCommentedOutBraceDoesNotEndTheType() {
        val declarations = scan(
            """
            struct Commented
            {
                /* } */
                public int value;
            }
            """
        )
        assertEquals(1, declarations.size)
        assertTrue(declarations.single().bodyEnd > declarations.single().bodyStart)
    }

    // PlayerLoopHelper.cs in UniTask: `#endif` right above `namespace` hid every type in the file,
    // PlayerLoopTiming among them, so a field of that enum type came out unresolved.
    @Test
    fun aDirectiveAboveTheNamespaceDoesNotHideItsTypes() {
        val declarations = scan(
            """
            #if UNITY_EDITOR
            using UnityEditor;
            #endif

            namespace Game.Loop
            {
                public enum Timing
                {
                    Update = 0,
            #if UNITY_2020_2_OR_NEWER
                    TimeUpdate = 1,
            #endif
                }
            }
            """
        )
        assertEquals(listOf("Game.Loop.Timing"), declarations.map { declaration -> declaration.qualifiedName })
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheNamespace() {
        val declarations = scan("\uFEFFnamespace Game\n{\n    struct Point { int x; }\n}\n")
        assertEquals(listOf("Game.Point"), declarations.map { declaration -> declaration.qualifiedName })
    }

    @Test
    fun aByteOrderMarkInFrontOfADirectiveStillStartsTheLine() {
        val declarations = scan("\uFEFF#pragma warning disable CS1591\nnamespace Game\n{\n    struct Point { int x; }\n}\n")
        assertEquals(listOf("Game.Point"), declarations.map { declaration -> declaration.qualifiedName })
    }

    @Test
    fun aRegionBetweenFieldsIsNotPartOfTheNextField() {
        val layout = layoutOf(
            """
            struct Sample
            {
                #region Position
                public float x;
                public float y;
                #endregion
                public int flags;
            }
            """,
            "Sample",
        )
        assertEquals(listOf("x", "y", "flags"), fieldNames(layout))
    }

    @Test
    fun aHashInsideAStringIsNotADirective() {
        val layout = layoutOf(
            "struct Tag\n{\n    string text = \"#if\";\n    char mark = '#';\n    int value;\n}\n",
            "Tag",
        )
        assertEquals(listOf("text", "mark", "value"), fieldNames(layout))
    }
}
