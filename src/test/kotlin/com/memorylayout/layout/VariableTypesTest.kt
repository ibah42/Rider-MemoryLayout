package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableTypesTest {

    /** The caret goes where the `|` is; the marker itself is not part of the source. */
    private fun typeAt(sourceWithCaret: String): VariableType? {
        val caret = sourceWithCaret.indexOf(CARET)
        val source = sourceWithCaret.replace(CARET.toString(), "")
        val masked = CodeMask.of(source)
        return VariableTypes.at(masked, source, caret, FILE_ID, SourceTypeLookup(masked, FILE_ID))
    }

    private fun declaredType(sourceWithCaret: String): String {
        val result = typeAt(sourceWithCaret)
        if (result !is VariableType.Declared) {
            throw AssertionError("expected a declared type, got $result")
        }
        return result.typeName
    }

    @Test
    fun readsALocal() {
        assertEquals("Vector3", declaredType("class A { void M() { Vector3 position = default; pos|ition.x = 1; } }"))
    }

    @Test
    fun readsTheDeclarationItself() {
        assertEquals("Vector3", declaredType("class A { void M() { Vector3 posi|tion; } }"))
    }

    @Test
    fun readsAParameter() {
        assertEquals("Rigidbody", declaredType("class A { void Move(Rigidbody body) { bo|dy.AddForce(); } }"))
    }

    @Test
    fun readsAFieldDeclaredAfterTheUse() {
        assertEquals("int", declaredType("class A { void M() { heal|th++; } private int health; }"))
    }

    @Test
    fun keepsGenericArguments() {
        assertEquals("List<int>", declaredType("class A { List<int> ids; void M() { i|ds.Clear(); } }"))
        assertEquals(
            "Dictionary<int, Enemy>",
            declaredType("class A { void M() { var map = new Dictionary<int, Enemy>(); ma|p.Clear(); } }"),
        )
    }

    @Test
    fun opensTheElementOfAnArray() {
        assertEquals("Enemy", declaredType("class A { Enemy[] enemies; void M() { enem|ies = null; } }"))
        assertEquals("Enemy", declaredType("class A { Enemy[,]? grid; void M() { gri|d = null; } }"))
    }

    @Test
    fun infersVarFromNew() {
        assertEquals("Enemy", declaredType("class A { void M() { var enemy = new Enemy(); ene|my.Hit(); } }"))
    }

    @Test
    fun infersVarFromALiteral() {
        assertEquals("float", declaredType("class A { void M() { var speed = 5f; spe|ed *= 2; } }"))
        assertEquals("string", declaredType("class A { void M() { var name = \"x\"; na|me = null; } }"))
        assertEquals("long", declaredType("class A { void M() { var count = 10L; cou|nt++; } }"))
    }

    @Test
    fun infersVarFromACastAndAnAs() {
        assertEquals("Enemy", declaredType("class A { void M(object o) { var e = (Enemy)o; |e.Hit(); } }"))
        assertEquals("Enemy", declaredType("class A { void M(object o) { var e = o as Enemy; |e.Hit(); } }"))
    }

    @Test
    fun infersVarFromAMethodThatReturnsAType() {
        assertEquals("Enemy", declaredType("class A { Enemy Spawn() { return null; } void M() { var s = Spawn(); |s.Hit(); } }"))
    }

    @Test
    fun saysWhenVarCannotBeTyped() {
        val result = typeAt("class A { void M() { var total = Compute(1) + 2; tot|al++; } }")
        assertTrue(result is VariableType.Unknown)
    }

    @Test
    fun readsTheElementOfAForeach() {
        assertEquals("Enemy", declaredType("class A { List<Enemy> all; void M() { foreach (var e in all) { |e.Hit(); } } }"))
        assertEquals(
            "System.Collections.Generic.KeyValuePair<int, Enemy>",
            declaredType("class A { Dictionary<int, Enemy> map; void M() { foreach (var pair in map) { pa|ir.Key; } } }"),
        )
    }

    @Test
    fun findsTheLocalOfTheRightMethod() {
        val source = "class A { void First() { Enemy x = null; } void Second() { Vector3 x = default; |x.y = 1; } }"
        assertEquals("Vector3", declaredType(source))
    }

    @Test
    fun aParameterOfAnotherMethodIsOutOfScope() {
        val source = "class A { float x; void First(Enemy x) { } void Second() { |x = 1; } }"
        assertEquals("float", declaredType(source))
    }

    @Test
    fun aTypeNameIsNotAVariable() {
        assertNull(typeAt("class A { void M() { Ene|my e = null; } }"))
    }

    @Test
    fun aComparisonIsNotAGeneric() {
        assertEquals("float", declaredType("class A { float limit; void M(int a) { if (a > lim|it) { } } }"))
    }

    @Test
    fun aTernaryIsNotANullable() {
        assertEquals("Enemy", declaredType("class A { Enemy first; void M(bool flag) { var y = flag ? fir|st : null; } }"))
    }

    @Test
    fun followsAMemberChain() {
        val source = """
            class Enemy { public Health health; }
            struct Health { public float value; }
            class A { Enemy enemy; void M() { enemy.health.val|ue = 0; } }
        """.trimIndent()
        assertEquals("float", declaredType(source))
    }

    @Test
    fun followsThis() {
        assertEquals("Health", declaredType("class A { Health health; void M() { this.hea|lth = default; } }"))
    }

    @Test
    fun anOutVariableLeaksIntoTheBlock() {
        val source = "class A { void M() { if (map.TryGetValue(key, out Enemy found)) { } fou|nd.Hit(); } }"
        assertEquals("Enemy", declaredType(source))
    }

    @Test
    fun readsALambdaParameter() {
        assertEquals("Enemy", declaredType("class A { void M() { Run((Enemy e) => |e.Hit()); } }"))
    }

    @Test
    fun readsAForVariable() {
        assertEquals("int", declaredType("class A { void M() { for (int i = 0; i < 4; i++) { Use(|i); } } }"))
    }

    @Test
    fun readsAnExpressionBodiedProperty() {
        assertEquals("int", declaredType("class A { int Count => 5; void M() { Use(Cou|nt); } }"))
    }

    @Test
    fun findsAFieldOfTheBaseClass() {
        val source = """
            class Actor { protected Vector3 position; }
            class Enemy : Actor { void M() { posi|tion.x = 0; } }
        """.trimIndent()
        assertEquals("Vector3", declaredType(source))
    }

    @Test
    fun bindsTheOwnersArguments() {
        val source = """
            class Box<T> { public T value; }
            class A { Box<Enemy> box; void M() { box.val|ue = null; } }
        """.trimIndent()
        assertEquals("Enemy", declaredType(source))
    }

    @Test
    fun bindsAGenericMethodsArguments() {
        val source = """
            class Component { public T GetComponent<T>() { return default; } }
            class Player : Component { void M() { var body = GetComponent<Rigidbody>(); bo|dy.Sleep(); } }
        """.trimIndent()
        assertEquals("Rigidbody", declaredType(source))
    }

    @Test
    fun ignoresCommentsAndStrings() {
        val source = "class A { // Enemy target\n Vector3 target; void M() { string s = \"Enemy target\"; tar|get.x = 1; } }"
        assertEquals("Vector3", declaredType(source))
    }

    companion object {
        private const val CARET = '|'

        private const val FILE_ID = "Test.cs"
    }
}
