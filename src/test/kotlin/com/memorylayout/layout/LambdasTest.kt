package com.memorylayout.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LambdasTest {

    /** The caret goes where the `|` is; the marker is not part of the source. */
    private fun siteAt(sourceWithCaret: String): LambdaSite? {
        val caret = sourceWithCaret.indexOf(CARET)
        val source = sourceWithCaret.replace(CARET.toString(), "")
        val masked = CodeMask.of(source)
        return Lambdas.at(masked, source, caret, FILE_ID, SourceTypeLookup(masked, FILE_ID))
    }

    private fun site(sourceWithCaret: String): LambdaSite {
        val site = siteAt(sourceWithCaret)
        assertNotNull("no lambda at the caret", site)
        return site!!
    }

    private fun capturedNames(site: LambdaSite): List<String> {
        return site.captured.map { variable -> variable.name }
    }

    @Test
    fun capturesALocal() {
        val result = site("class Player { void Update() { float factor = 2; Apply(x =|> x * factor); } }")
        assertEquals(listOf("factor"), capturedNames(result))
        assertEquals("Update", result.memberName)
        assertEquals("Player", result.containerQualifiedName)
        assertEquals(listOf("x"), result.parameterNames)
        assertFalse(result.usesInstance)
    }

    @Test
    fun capturesAParameterAndSeesAnInstanceField() {
        val result = site("class Player { int count; void Log(string name) { Run(() |=> { Print(name); count++; }); } }")
        assertEquals(listOf("name"), capturedNames(result))
        assertTrue(result.usesInstance)
    }

    @Test
    fun aLambdaThatCapturesNothing() {
        val result = site("class Player { void Update() { Apply(x =>| x * 2); } }")
        assertTrue(result.captured.isEmpty())
        assertFalse(result.usesInstance)
    }

    @Test
    fun aLocalOfTheLambdaIsNotCaptured() {
        val result = site("class Player { void Update() { Run(() =|> { int local = 1; Use(local); }); } }")
        assertTrue(result.captured.isEmpty())
    }

    @Test
    fun aNestedLambdasParameterIsNotCaptured() {
        val result = site("class Player { void Update() { Make(a =|> b => a + b); } }")
        assertTrue(result.captured.isEmpty())
    }

    @Test
    fun thisAndMemberAccess() {
        assertTrue(site("class Player { int count; void Update() { Run(() =|> this.count++); } }").usesInstance)
        val throughOther = site("class Player { int count; void Update(Player other) { Run(() =|> other.count++); } }")
        assertEquals(listOf("other"), capturedNames(throughOther))
        assertFalse(throughOther.usesInstance)
    }

    @Test
    fun aNamedArgumentIsNotARead() {
        val result = site("class Player { void Update() { int n = 3; Run(() =|> Spawn(count: n)); } }")
        assertEquals(listOf("n"), capturedNames(result))
    }

    @Test
    fun callingTheMemberOrALocalFunctionIsNotACapture() {
        val recursive = site("class P { static string Name(Type t) { return Join(t.Args.Select(a =|> Name(a))); } }")
        assertTrue(recursive.captured.isEmpty())
        val local = site("class P { void Update() { int Twice(int v) { return v * 2; } Run(x =|> Twice(x)); } }")
        assertTrue(local.captured.isEmpty())
        val held = site("class P { void Update() { Action done = null; Run(() =|> done()); } }")
        assertEquals(listOf("done"), capturedNames(held))
    }

    @Test
    fun anAnonymousMethod() {
        val result = site("class Player { void Update() { int total = 0; Each(del|egate (int v) { total += v; }); } }")
        assertEquals(listOf("total"), capturedNames(result))
        assertEquals(listOf("v"), result.parameterNames)
    }

    @Test
    fun namesTheMemberTheWayTheCompilerDoes() {
        assertEquals(".ctor", site("class Player { Player(int hp) { Run(() =|> Use(hp)); } }").memberName)
        assertEquals(".ctor", site("class Player { Action tick = () =|> Tick(); }").memberName)
        assertEquals(".cctor", site("class Player { static Action tick = () =|> Tick(); }").memberName)
        assertEquals(
            "get_Getter",
            site("class Player { Func<int> Getter { get { int k = 1; return () =|> k; } } }").memberName,
        )
        assertEquals("Spawn", site("class Player { void Spawn<T>(int count) { Run(() =|> Use(count)); } }").memberName)
    }

    @Test
    fun anExpressionBodyIsNotALambda() {
        assertEquals(null, siteAt("class Player { int[] items; int Count =|> items.Length; }"))
        assertEquals(null, siteAt("class Player { int count; void Reset() =|> count = 0; }"))
        assertEquals(null, siteAt("class Player { int Twice(int x) =|> x * 2; }"))
    }

    @Test
    fun aSwitchArmIsNotALambda() {
        assertEquals(null, siteAt("class Player { string Name(int x) { return x switch { 1 =|> \"one\", _ => \"many\" }; } }"))
        assertEquals(null, siteAt("class Player { string Name(object o) { return o switch { Enemy e =|> e.Name, _ => \"\" }; } }"))
    }

    @Test
    fun anArmOnAnEnumMemberOrADiscardIsNotALambda() {
        val source = "class P { int Amount(Kind kind, int n) { return kind switch { Kind.Coins =|> n, _ => 0 }; } }"
        assertEquals(null, siteAt(source))
        assertEquals(null, siteAt("class P { int Amount(Kind kind) { return kind switch { Kind.Coins => 1, _ =|> 0 }; } }"))
    }

    @Test
    fun aLambdaInsideASwitchArmIsStillALambda() {
        val result = site("class P { Func<int> Make(int k, int n) { return k switch { 1 => () =|> n, _ => null }; } }")
        assertEquals(listOf("n"), capturedNames(result))
    }

    @Test
    fun aConstantIsNotCaptured() {
        val result = site("class P { void M() { const float power = 0.3f; float speed = 2; Run(() =|> Shake(power, speed)); } }")
        assertEquals(listOf("speed"), capturedNames(result))
    }

    @Test
    fun theCaretOnAVariableIsNotOnTheLambda() {
        assertFalse(Lambdas.isAt(CodeMask.of("class P { void U() { Apply(x => x * factor); } }"), "class P { void U() { Apply(x => x * fac".length))
    }

    companion object {
        private const val CARET = '|'

        private const val FILE_ID = "Player.cs"
    }
}
