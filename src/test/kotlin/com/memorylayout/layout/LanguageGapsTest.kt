package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.fieldNames
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C# the engine does not read correctly yet. Every test states what Mono lays out on x64 -- the
 * right answer, not the current one -- so each one is red until its construct is handled.
 *
 * Found by laying out a few hundred lines of ordinary Unity-era C# (C# 9) and comparing. Grouped
 * by what goes wrong: a field that disappears is worse than one that is mis-sized, because every
 * offset after it moves and nothing on screen says so.
 */
class LanguageGapsTest {

    private fun hasUnresolved(layout: TypeLayout): Boolean {
        return layout.nodes.any { node -> node.kind == NodeKind.UNRESOLVED }
    }

    // --- Fields that disappear: an initializer with braces or an arrow ------------------------

    @Test
    fun aFieldWithACollectionInitializerIsStillAField() {
        val layout = layoutOf(
            "class Init { System.Collections.Generic.List<int> items = new() { 1, 2 }; int after; }",
            "Init",
        )
        assertTrue(field(layout, "items").isReference)
        assertEquals(16, field(layout, "items").offset)
        assertEquals(24, field(layout, "after").offset)
    }

    @Test
    fun aFieldInitializedWithABlockLambdaIsStillAField() {
        val layout = layoutOf("class Init { System.Action act = () => { Do(); }; int after; }", "Init")
        assertTrue(field(layout, "act").isReference)
        assertEquals(24, field(layout, "after").offset)
    }

    @Test
    fun aFieldInitializedWithAnExpressionLambdaIsStillAField() {
        // The `=>` belongs to the lambda on the right of `=`: this is a field, not an
        // expression-bodied property.
        val layout = layoutOf("class Init { System.Func<int, int> f = x => x * 2; int after; }", "Init")
        assertTrue(field(layout, "f").isReference)
        assertEquals(24, field(layout, "after").offset)
    }

    @Test
    fun aFieldInitializedWithAnAnonymousMethodIsStillAField() {
        val layout = layoutOf("class Init { System.Action a = delegate { Do(); }; int after; }", "Init")
        assertTrue(field(layout, "a").isReference)
        assertEquals(24, field(layout, "after").offset)
    }

    @Test
    fun aFieldInitializedWithASwitchExpressionIsStillAField() {
        val layout = layoutOf("class Init { int x = y switch { 1 => 2, _ => 3 }; int after; }", "Init")
        assertEquals(16, field(layout, "x").offset)
        assertEquals(20, field(layout, "after").offset)
    }

    // --- Members that are read as fields, or fields read as junk ------------------------------

    @Test
    fun anExpressionBodiedPropertyWithAnObjectInitializerStoresNothing() {
        // `=> new Box { A = 1 }.A` -- the brace used to be taken for a body, and the `.A` after it
        // became a field of its own.
        val layout = layoutOf(
            """
            class Box { public int A; }
            struct Reader { int P => new Box { A = 1 }.A; int after; }
            """,
            "Reader",
        )
        assertEquals(listOf("after"), fieldNames(layout))
    }

    @Test
    fun anExplicitInterfaceAutoPropertyHasABackingField() {
        // `int IHas.Value { get; set; }` is an auto-property like any other: the compiler stores
        // it in a field of its own.
        val layout = layoutOf(
            """
            interface IHas { int Value { get; set; } }
            struct Impl : IHas { int IHas.Value { get; set; } int after; }
            """,
            "Impl",
        )
        assertFalse(hasUnresolved(layout))
        assertEquals(8, layout.size)
        assertEquals(4, field(layout, "after").offset)
    }

    @Test
    fun verbatimIdentifiersAreFieldNames() {
        val layout = layoutOf("struct Keywords { public int @class; public int @event; }", "Keywords")
        assertFalse(hasUnresolved(layout))
        assertEquals(listOf("class", "event"), fieldNames(layout).map { name -> name.removePrefix("@") })
        assertEquals(8, layout.size)
    }

    // --- Types the engine does not know how to size -------------------------------------------

    @Test
    fun aNestedDelegateTypeIsAReference() {
        val layout = layoutOf(
            "struct Holder { public delegate void Handler(int x); public Handler h; int after; }",
            "Holder",
        )
        assertTrue(field(layout, "h").isReference)
        assertEquals(8, field(layout, "h").size)
        assertEquals(8, field(layout, "after").offset)
    }

    @Test
    fun aTupleIsAValueTupleInline() {
        // `(int, float)` is ValueTuple<int, float>: a struct, eight bytes in place, not a pointer.
        val layout = layoutOf("struct Tuples { (int, float) pair; (int id, byte flag) named; byte tail; }", "Tuples")
        assertFalse(field(layout, "pair").isReference)
        assertEquals(8, field(layout, "pair").size)
        assertEquals(8, field(layout, "named").offset)
        assertEquals(8, field(layout, "named").size)
        assertEquals(16, field(layout, "tail").offset)
    }

    @Test
    fun anArrayOfTuplesIsAReference() {
        val layout = layoutOf("class Grid { (int, int)[] cells; int after; }", "Grid")
        assertTrue(field(layout, "cells").isReference)
        assertEquals(24, field(layout, "after").offset)
    }

    @Test
    fun aFunctionPointerIsOnePointer() {
        val layout = layoutOf("unsafe struct Callbacks { delegate*<int, void> fn; byte tail; }", "Callbacks")
        assertEquals(8, field(layout, "fn").size)
        assertEquals(8, field(layout, "tail").offset)
        assertEquals(16, layout.size)
    }

    @Test
    fun aNestedTypeOfAGenericTypeIsTheNestedType() {
        // `List<int>.Enumerator` is the shape: a struct nested in a generic class. It is the
        // struct, laid out in place, not a reference to the outer class.
        val layout = layoutOf(
            """
            class Outer<T> { public struct Inner { public T value; public byte tag; } }
            struct Holder { Outer<long>.Inner inner; }
            """,
            "Holder",
        )
        assertFalse(field(layout, "inner").isReference)
        assertEquals(16, field(layout, "inner").size)
    }

    @Test
    fun aUsingAliasNamesTheTypeItAliases() {
        val layout = layoutOf(
            """
            using Alias = Game.Point;
            namespace Game
            {
                struct Point { int x; int y; }
                struct Holder { Alias p; byte tail; }
            }
            """,
            "Holder",
        )
        assertFalse(hasUnresolved(layout))
        assertEquals(8, field(layout, "p").size)
        assertEquals(8, field(layout, "tail").offset)
    }

    @Test
    fun aGenericBaseClassHasItsArgumentsBound() {
        // `Hero : Actor<Stats>` inherits `TStats stats` as `Stats stats`.
        val layout = layoutOf(
            """
            class Actor<TStats> { public TStats stats; }
            struct Stats { public long hp; }
            class Hero : Actor<Stats> { public int level; }
            """,
            "Hero",
        )
        assertFalse(hasUnresolved(layout))
        assertEquals(16, field(layout, "stats").offset)
        assertEquals(8, field(layout, "stats").size)
        assertEquals(24, field(layout, "level").offset)
    }

    // --- Layout rules not applied -------------------------------------------------------------

    @Test
    fun anExplicitLayoutClassHonoursItsFieldOffsets() {
        // FieldOffset in a class counts from the end of the object header.
        val layout = layoutOf(
            """
            using System.Runtime.InteropServices;
            [StructLayout(LayoutKind.Explicit)]
            class Overlay { [FieldOffset(0)] int a; [FieldOffset(0)] float b; }
            """,
            "Overlay",
        )
        assertEquals(16, field(layout, "a").offset)
        assertEquals(16, field(layout, "b").offset)
        assertTrue(field(layout, "b").overlapsPrevious)
    }

    // --- Preprocessor: every branch is kept, so a field is laid out two or three times ---------

    @Test
    fun anIfElsePairLaysOutOneBranch() {
        // The mask cannot know which symbols are defined. Taking the first branch -- the editor's
        // own, usually -- is a guess; laying out both is never right.
        val layout = layoutOf(
            """
            struct Debug
            {
            #if UNITY_EDITOR
                public int debugId;
            #else
                public short debugId;
            #endif
                public byte tail;
            }
            """,
            "Debug",
        )
        assertEquals(listOf("debugId", "tail"), fieldNames(layout))
        assertEquals(4, field(layout, "tail").offset)
    }

    @Test
    fun anIfElifElseChainLaysOutOneBranch() {
        val layout = layoutOf(
            """
            struct Chain
            {
                public int a;
            #if !NET_STANDARD
                public int b;
            #elif UNITY_EDITOR
                public long b;
            #else
                public short b;
            #endif
            }
            """,
            "Chain",
        )
        assertEquals(listOf("a", "b"), fieldNames(layout))
        assertEquals(8, layout.size)
    }
}
