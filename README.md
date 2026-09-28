# Memory Layout

A plugin for Rider that shows how a C# struct actually sits in memory: every field with its
offset and size, and the padding bytes between them -- the same picture the C++ memory layout
view gives, for C#.

```
Vertex · 28 B · align 4 · 6 B padding (21%) · blittable

hex     dec   size  align  type       name
0x00      0     12      4  Vector3    position     ▸
0x0C     12      1      1  bool       visible
0x0D     13      3      –  padding    (3 bytes)
0x10     16      4      4  float      weight
0x14     20      2      2  ushort     flags
0x16     22      2      –  padding    (tail)
```

Nothing runs and no debugger is needed: the layout is computed from the source text.

## Why it exists

In Unity the size of a struct is not a detail. It decides how much a `NativeArray` costs, whether
a job's data fits a cache line, and whether the thing can be blitted at all. The compiler knows
all of this and shows none of it.

## What it understands

- `LayoutKind.Sequential` (the default for a struct), `Pack`, `Size`
- `LayoutKind.Explicit` with `[FieldOffset]`, including deliberate overlaps
- nested structs, expanded in place with absolute offsets
- enums, down to their underlying type
- `fixed` buffers, auto-properties and positional record parameters, which all take real space
- `partial` types, read together from every file that declares a part
- the closure a lambda captures into, with the caret on its `=>` -- read from the project's
  compiled assemblies in `Library/ScriptAssemblies`
- types with no source in the project -- `string`, `List<T>`, `Guid`, `Vector3`, a package's
  DLL -- read from the metadata of the runtime's own assemblies (see below)
- `const` and `static` members, which take none
- references, `bool` and `char`: sized correctly, and reported as the reasons a struct is not
  blittable
- x64 and x86, which differ in the size of every reference and `nint`

## What it cannot know

Rider's C# semantic model lives on the ReSharper backend and is out of reach from a frontend
plugin, so type names are resolved by this plugin's own text index over the project. A name it
cannot find is shown as unresolved and the whole layout is marked **approximate** rather than
quietly guessing. A class, or an explicit `LayoutKind.Auto`, is marked **runtime-defined**: the
runtime is free to reorder those fields, and what is drawn is the sequential model.

## Checking the numbers against the runtime

Every expected value in the tests is a claim about the CLR. The way to check a claim is a C#
program over the same shapes:

```csharp
Console.WriteLine(Unsafe.SizeOf<Vertex>());
Console.WriteLine(Marshal.OffsetOf<Vertex>(nameof(Vertex.weight)));
```

If it disagrees with the plugin, the plugin is wrong until proven otherwise.
