package com.memorylayout.layout

import com.memorylayout.layout.LayoutTestSupport.field
import com.memorylayout.layout.LayoutTestSupport.fieldNames
import com.memorylayout.layout.LayoutTestSupport.layoutOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which properties take room in an instance and which do not.
 *
 * The rule is the compiler's: a property whose accessors it writes itself gets a hidden backing
 * field, and that field is as real as any other; a property whose accessors someone wrote stores
 * nothing of its own. The same goes for events -- a field-like `event` is a delegate field, an
 * event with `add`/`remove` is two methods.
 */
class PropertyStorageTest {

    private fun namesOf(body: String): List<String> {
        return fieldNames(layoutOf("public struct Holder { $body }", "Holder"))
    }

    @Test
    fun anAutoPropertyIsStored() {
        assertEquals(listOf("Value"), namesOf("public int Value { get; set; }"))
        assertTrue(field(layoutOf("public struct Holder { public int Value { get; set; } }", "Holder"), "Value").isAutoProperty)
    }

    @Test
    fun everyAutoAccessorShapeIsStored() {
        assertEquals(listOf("Value"), namesOf("public int Value { get; }"))
        assertEquals(listOf("Value"), namesOf("public int Value { get; init; }"))
        assertEquals(listOf("Value"), namesOf("public int Value { get; private set; }"))
        assertEquals(listOf("Value"), namesOf("public int Value { readonly get; set; }"))
        assertEquals(listOf("Value"), namesOf("public required int Value { get; init; }"))
    }

    @Test
    fun anInitializerDoesNotChangeTheStorage() {
        assertEquals(listOf("Value", "after"), namesOf("public int Value { get; set; } = 5; public byte after;"))
    }

    @Test
    fun aUnitySerializedAutoPropertyIsStored() {
        assertEquals(listOf("Health"), namesOf("[field: SerializeField] public int Health { get; private set; }"))
    }

    @Test
    fun anArrayOrGenericAutoPropertyIsStored() {
        val layout = layoutOf("public class Holder { public int[] Values { get; set; } public List<int> Items { get; } }", "Holder")
        assertEquals(listOf("Values", "Items"), fieldNames(layout).filter { name -> name == "Values" || name == "Items" })
        assertEquals(8, field(layout, "Values").size)
    }

    @Test
    fun aVirtualOrOverridingAutoPropertyIsStored() {
        val layout = layoutOf(
            "public class Base { public virtual int Value { get; set; } } " +
                "public class Derived : Base { public override int Value { get; set; } }",
            "Derived",
        )
        // Both: an overriding auto-property gets a backing field of its own.
        assertEquals(2, layout.nodes.count { node -> node.fieldName == "Value" })
    }

    @Test
    fun aComputedPropertyIsNotStored() {
        assertEquals(listOf("stored"), namesOf("public int stored; public int Twice => stored * 2;"))
        assertEquals(listOf("stored"), namesOf("public int stored; public int Value { get { return stored; } }"))
        assertEquals(listOf("stored"), namesOf("public int stored; public int Value { get => stored; set => stored = value; }"))
    }

    @Test
    fun aStaticAutoPropertyIsNotStored() {
        assertEquals(listOf("stored"), namesOf("public static int Shared { get; set; } public int stored;"))
    }

    @Test
    fun anAbstractPropertyIsNotStored() {
        val layout = layoutOf("public abstract class Shape { public abstract float Area { get; } public int sides; }", "Shape")
        assertEquals(listOf("sides"), fieldNames(layout).filter { name -> name == "sides" || name == "Area" })
    }

    @Test
    fun anIndexerIsNotStored() {
        assertEquals(listOf("stored"), namesOf("public int stored; public int this[int index] { get { return stored; } }"))
    }

    @Test
    fun aFieldLikeEventIsADelegateField() {
        val layout = layoutOf("public class Emitter { public event Action Died; public int count; }", "Emitter")
        assertTrue(fieldNames(layout).contains("Died"))
        assertEquals(8, field(layout, "Died").size)
        assertEquals(16, field(layout, "count").offset)
    }

    @Test
    fun anEventWithAccessorsIsNotStored() {
        val layout = layoutOf(
            "public class Emitter { public event Action Died { add { } remove { } } public int count; }",
            "Emitter",
        )
        assertEquals(listOf("count"), fieldNames(layout).filter { name -> name == "count" || name == "Died" })
    }

    @Test
    fun aStaticEventIsNotStored() {
        val layout = layoutOf("public class Emitter { public static event Action Any; public int count; }", "Emitter")
        assertEquals(listOf("count"), fieldNames(layout).filter { name -> name == "count" || name == "Any" })
    }

    @Test
    fun anInterfaceDeclaresNoStorage() {
        val layout = layoutOf("public interface IShape { float Area { get; } } public struct Holder : IShape { public float Area { get; } }", "Holder")
        assertEquals(listOf("Area"), fieldNames(layout))
    }
}
