# Instructions for AI agents

Ivan's rules. Read them before writing code, not after the review.

The "Code style" section is not tied to this project — copy it into other repositories as is.

---

## Code style

### 1. Do not abbreviate names

Whole words. Always.

```kotlin
// wrong
private var i = 0
private var n = text.length
private var q = 0
val fm = graphics.fontMetrics
fun forExtension(ext: String)

// right
private var position = 0
private var textLength = text.length
private var quoteRun = 0
val fontMetrics = graphics.fontMetrics
fun forExtension(extension: String)
```

This is strictest for class members: fields, properties, constants. They are read far away from
their declaration, and `n` means nothing there.

A short name is acceptable in exactly one case — a genuine loop counter (`index`, `count`).
Even then a meaningful name is better: `lineIndex`, `columnCount`.

Function parameters are whole words too, including the parameters of an `override`. Renaming a
parameter inherited from the base class is allowed and encouraged: `g: Graphics` → `graphics`,
`e: AnActionEvent` → `event`.

### 2. No single-line bodies

The body of an `if`, `else`, `for`, `while` or `when` always goes on its own line and always in
braces. Even when it is a single `return`.

```kotlin
// wrong
if (editor.isDisposed) return
if (bracketDepth > 0) bracketDepth--
for (inlay in inlays) Disposer.dispose(inlay)

// right
if (editor.isDisposed) {
    return
}
if (bracketDepth > 0) {
    bracketDepth--
}
for (inlay in inlays) {
    Disposer.dispose(inlay)
}
```

The same applies to `if` used as an expression. A one-line ternary saves nothing but readability:

```kotlin
// wrong
position += if (hasNext) 2 else 1
val indentStart = if (isContinuation) statementLineStartOffset else lineStartOffset
presentation.text = if (isEnabled) "on" else "off"

// right
if (hasNext) {
    position += 2
} else {
    position++
}

val indentStart: Int
if (isContinuation) {
    indentStart = statementLineStartOffset
} else {
    indentStart = lineStartOffset
}
```

Do not save vertical space. Screen space is cheaper than reading time.

Exceptions where a single line is fine: `?:` for an early exit, `?.let { }`, expression-body
functions with no branching (`fun columnWidth(): Int = ...`), and single-line `when` branches
that call exactly one function with no conditions.

### 3. `when` takes a subject

If every branch compares the same value, that value goes into the header.

```kotlin
// wrong
when {
    current == '\\' -> advanceOverEscape()
    current == '"' -> closeStringLiteral()
    dollars > 0 && current == '{' -> handleInterpolationBrace()
    else -> position++
}

// right
when (current) {
    '\\' -> {
        advanceOverEscape()
    }
    '"' -> {
        closeStringLiteral()
    }
    '{' -> {
        stepInterpolationBraceOrSkip()   // the condition moved inside
    }
    else -> {
        position++
    }
}
```

A compound condition is not a reason to fall back to `when {}` — move it inside the branch or
into a separate function with a descriptive name. Do not use the guard syntax
(`'{' if dollars > 0 ->`): it only arrived in Kotlin 2.1, and pinning the language version for
the sake of one line is not worth it.

### 4. Braces are always written out

Even where the language allows omitting them. The physical position of the opening brace follows
the language convention (end of line in Kotlin): Ivan reads code through Allman View, which shows
Allman visually, so the file itself does not need to change.

### 5. Magic numbers become named constants

```kotlin
// wrong
HighlighterLayer.LAST + 100
ColorUtil.mix(foreground, background, 0.55)

// right
HighlighterLayer.LAST + DIM_LAYER_OFFSET
ColorUtil.mix(foreground, background, DIM_BALANCE)
```

### 6. Comments explain "why", not "what"

What the code does is visible in the code. A comment carries what is not visible: why this path
was chosen, what breaks with the obvious alternative, where the trap is.

```kotlin
// useless
// increase the position by two
position += 2

// useful
// Do not step over the line break, or the line markup is lost.
```

Comments and UI text are written in English.

---

## Working on a task

### Plan first, then code

For a non-trivial task, start with the analysis: which APIs, which risks, what to check first,
what the fallback is. The plan is stated before the first line is written.

List the risks honestly, marking which one is the main one and how to check it quickly.

### Verify, do not claim

Never write "done" about something that was never run. If something cannot be checked, say so
plainly and give the reason.

The order is:

1. Logic that can be detached from the framework gets detached and covered by tests. That is how
   `scan/BraceScanner.kt` is built in this project: zero IntelliJ dependencies, so it runs
   anywhere.
2. Run the tests against the code that is actually on disk, not against your own copy.
3. When a full build is unavailable, at least compile and sort the errors by kind, separating
   "missing jars" from the real ones. See "Verifying without a full build" below for the exact
   recipe used in a sandboxed session with no Gradle/Maven access.
4. Cover edge cases right away: string literals, comments, escaping, multi-line constructs,
   unbalanced input.

### Do not guess at APIs

Look up library signatures and behaviour in the documentation or the sources instead of recalling
them. Behaviour such as "is the region created already collapsed?" is exactly what gets recalled
wrong.

### Admit mistakes directly

If an earlier piece of advice turned out to be wrong, say so in the first sentence and explain
why. Do not blur it or bury it.

### Keep a version log

Every time `version` in `build.gradle.kts` is bumped, add an entry to `CHANGELOG.md` in the same
turn -- newest version at the top, written for whoever reads it later (Ivan, or another agent
picking this project back up), not just "bumped version". Say what changed and, where it matters,
why -- a fix names the symptom it removes, a feature names what it now does. A version bump
without a changelog entry, or a changelog entry without a version bump, is an incomplete turn:
they are the same action and always happen together.

### Verifying without a full build

Gradle needs network access a sandboxed session may not have (Maven Central and the Gradle Plugin
Portal have both been seen blocked). Do not silently skip verification -- say so, then fall back to
this, which works and has been used on this repository:

1. Fetch a standalone compiler once: `kotlin-compiler-2.4.20.zip` from JetBrains' GitHub releases
   (`github.com` is reachable when `repo1.maven.org` is not). Unpack it outside the repository.
2. `org.junit.Test` is an annotation and `org.junit.Assert` is two static methods. Write minimal
   stand-ins for both plus a reflection runner (find the `@Test` methods, invoke each on a fresh
   instance, count passes and failures), and compile the **real, unmodified** files from `src/`
   against them:

   ```
   kotlinc -nowarn -d out stubs/JUnitStubs.kt stubs/Runner.kt \
       src/main/kotlin/com/memorylayout/layout/*.kt \
       src/test/kotlin/com/memorylayout/layout/*.kt
   java -cp out:<kotlinc>/lib/kotlin-stdlib.jar RunnerKt \
       com.memorylayout.layout.LayoutEngineTest \
       com.memorylayout.layout.TypeScannerTest
   ```

3. This verifies `layout/` for real.

### Compiling the IntelliJ-dependent code without Gradle

The platform's own jars are already on this machine, inside the Rider distribution the Gradle
build downloaded. Unpacking them takes seconds and turns "not compiler-checked" into checked:

```
unzip -q "$GRADLE_HOME/caches/modules-2/files-2.1/rider/JetBrains.Rider/<version>/<hash>/JetBrains.Rider-<version>-win.zip" \
    "lib/*.jar" -d <scratch>
kotlinc -nowarn -jvm-target 21 -cp "$(ls <scratch>/lib/*.jar | tr '\n' ':')" -d out \
    src/main/kotlin/com/memorylayout/*/*.kt
```

Two things this taught, both worth keeping:

- **The platform's bytecode is built for JVM target 25**, while this plugin targets 21. Calling a
  platform **inline** function therefore fails the build with "cannot inline bytecode built with
  JVM target 25". That is why `MemoryLayoutConfigurable` implements `MutableProperty` by hand
  instead of using the DSL's inline `bind(getter, setter)`. When something in the UI DSL refuses
  to compile, check whether it is inline before rewriting anything else.
- **An API question is answered by the compiler or by the class file, never from memory.** A
  one-file probe that imports and calls the API settles it in seconds; `unzip -p <jar> <Class>.class
  | strings` shows the method names and signatures when the question is which overload exists.

Running platform classes is a different matter: they need a JVM 25, which a sandbox may not have.
Compile-checking does not.

---

## About this project

A Rider plugin that shows the memory layout of a C# struct: every field with its offset and size,
and the padding between them, the way the C++ memory layout view does. What it is for is in
`README.md`.

The shape of the thing:

- **`layout/` is pure and has no IntelliJ dependency.** Masking literals, finding declarations,
  reading fields, sizing types, placing them -- all of it is a function of the source text and
  runs in a plain unit test. Anything that needs an `Editor`, a `Project` or a tool window lives
  outside that package. This is the same split that keeps `scan/` testable in Allman View, and it
  is the reason the recipe above works at all.
- **There is no semantic model to lean on.** In Rider the C# syntax tree lives on the ReSharper
  backend and a frontend plugin cannot reach it, so every type name is resolved by this plugin's
  own text index. That is the ceiling on accuracy, and it is why a layout carries a
  `LayoutConfidence` instead of pretending to be the truth.
- **The engine hands back a tree of byte ranges, not table rows.** `LayoutNode` children carry
  **absolute** offsets. The table is one view of that tree; a cache-line view and anything else
  drawn later read the same tree rather than asking the engine for a second shape. Keep view
  decisions -- colours, column order, wording -- out of `layout/`.
- **Never let `JTable` lay the columns out.** Outside a drag, `JTable.doLayout` spreads the spare
  width over every column in proportion whatever the resize mode, which is what kept undoing the
  narrow numeric columns. `LayoutTreeTable.layoutColumns` does it instead, from each column's
  preferred width, and a drag writes the width back into the preference. Column widths are stored
  per project (`MemoryLayoutProjectSettings`), the application value being only the default.
- **The target, the cache line and the column widths live in `ui/MemoryLayoutViewState.kt`,
  not in a panel.** Every tab listens and every change is written to the settings on the spot. Two
  tabs on different targets would be two tabs whose numbers cannot be compared, which is the
  opposite of what the tabs are for; the same goes for a column dragged in one tab and not the
  other. A new shared view option belongs there, with a `Listener` callback, not as a field of
  `MemoryLayoutPanel`.
- **The bricks are a second view of the same tree, not a second model.** `layout/BrickLayout.kt`
  cuts the tree into rectangles -- one row per cache line, a field crossing a line cut in two --
  and `ui/BrickView.kt` paints them. The table and the bricks agree because they are handed the
  same `LayoutNode` instances and compare them with `===`. Do not reach for an offset, a name or
  an index path as the identity of a row: an offset repeats under `LayoutKind.Explicit`, a name
  repeats across nested structs, and an index path stops matching the moment padding rows are
  hidden. All three were tried; identity is what survives.
- **A class is not a struct with a different keyword.** Eight bytes of object header at -8 (the
  sync block index), the method table pointer at 0, fields from 8, base class first, and a
  24-byte floor on the allocation. The size reported is measured from the reference so every
  other offset in the engine stays positive; the header is named in the notes instead. There is
  no per-object vtable pointer in .NET -- the method table pointer is the type handle and the
  vtable lives inside it, so anything that calls it a vtable pointer is wrong.
- **Cache lines are cut from where the object really begins, not from zero.** Zero is the
  reference, which is eight bytes into the allocation; the allocation starts at the header and is
  what alignment applies to. Cutting from zero draws a grid that is not in memory. `BrickLayout`
  takes the lowest offset in the layout as its origin for exactly this reason, and
  `cacheLineText` measures the span from there. The offsets themselves stay reference-relative,
  which is what every debugger reports and what the table shows.
- **`metadata/` is pure too, and writes C# rather than building layouts.** It reads the ECMA-335
  tables of an assembly and turns each type back into the declaration it was compiled from
  (`MetadataSourceWriter`), so the engine reads BCL and UnityEngine types exactly the way it reads
  source. Do not grow a second path through the engine for metadata: anything the writer cannot
  express as C# is a gap in the writer. Field types are written namespace-qualified on purpose --
  the project's index answers first (`CompositeTypeLookup`), and a bare `Entry` would find the
  project's own. `MetadataReaderTest` needs a real runtime `mscorlib.dll`, which cannot be
  committed: point `MEMORY_LAYOUT_MSCORLIB` at
  `<Editor>/Data/MonoBleedingEdge/lib/mono/unityjit-win32/mscorlib.dll`.
- **Never read the framework from the assemblies the `.csproj` names.** Unity hands the compiler
  reference assemblies (`NetStandard/ref`, `UnityReferenceAssemblies/*-api`); their private fields
  are gone and their structs are one placeholder `int`. `ProjectReferences` swaps them for Mono's
  runtime assemblies from the same install. ReSharper's backend reads the reference ones, which is
  why its model is not the source of fields for these types either.
- **A variable's type is found by scoping, not by the nearest match.** `VariableTypes` walks back
  from the use and accepts a declaration only if its scope reaches the use: the enclosing brace
  block, or for parentheses of a method, lambda, `for`, `foreach`, `using` or `catch` the body that
  follows them; an `out`/pattern variable inside a call or an `if` belongs to the enclosing block.
  A `?` or `*` counts as part of a type only when written attached (`int?`), which is what keeps
  `flag ? x : y` and `a * b` from reading as declarations; `a > x` is rejected by the backwards
  angle match stopping at operators. Each of these has a test -- keep it that way.
- **`bool` and `char` are sized but not blittable.** `TypeMetrics.blittableProblem` is how that is
  reported; a size alone would quietly lie to anyone building a `NativeArray`.
- **`indexOfTopLevel` tests the match before counting the character.** Otherwise the `(` that opens
  a parameter list is hidden by the group it just opened, `record` in `void Save(Record record)`
  reads as a type declaration, and a positional record's parameters are never found. There is a
  test for each of those three.
- **Numbers are claims about the CLR, not about this code.** When an expected value changes, the
  question is which of the two is wrong. The cross-check is a C# program using
  `Unsafe.SizeOf<T>()` and `Marshal.OffsetOf` over the same shapes -- see `README.md`.

### Versions

Change these together, they are linked:

| | version | why |
|---|---|---|
| Gradle | 9.7.0 | the IJ plugin 2.x needs 9.0+, and running on JDK 25 needs 9.1+ |
| Kotlin | 2.4.20 | full support for Gradle 7.6.3-9.7.0 |
| IntelliJ Platform Gradle Plugin | 2.19.0 | |
| toolchain | JDK 21 | the 2026.x platform runs on JBR 21 |

### What not to commit

`.intellijPlatform/` -- the cache with the unpacked IDE and the sandbox, about a gigabyte. Also
`build/`, `.gradle/`, `.kotlin/` and built zips. The wrapper (`gradle-wrapper.jar`), on the other
hand, does belong in the repository.
