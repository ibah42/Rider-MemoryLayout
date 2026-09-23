# Changelog

## 1.0.4 -- properties and events that take room

Found by a new suite, `PropertyStorageTest`, that goes through every shape of property and event
and asks one question of each: does the compiler give it a backing field?

- **An array auto-property is stored.** `public int[] Values { get; set; }` was dropped from the
  layout: any `[` in the header was taken for an indexer. Only `this[...]` is one now.
- **A field-like event is stored.** `public event Action Died;` is a delegate field the compiler
  writes -- eight bytes in every instance -- and `event` was on the list of modifiers that exclude a
  member, so it vanished and every field after it moved up by eight. An event with `add`/`remove`
  still stores nothing, and a static one still does not count.
- **An abstract property is not stored.** `public abstract float Area { get; }` has automatic-looking
  accessors and no backing field; it was laid out as one. The same now holds for `extern` and for
  the declaring half of a `partial` property.
- Confirmed as already right, and now under test: `get;`/`init;`/`private set;`/`readonly get;`,
  `required`, an initializer after the accessors, `[field: SerializeField]`, generic types, a
  virtual auto-property and its override (two backing fields), computed and expression-bodied
  properties, static ones, indexers, an interface's properties.

## 1.0.3 -- column widths that stay put

- **The numeric columns stay narrow, and a dragged width holds in every tab.** The cause was
  `JTable` itself: `AUTO_RESIZE_LAST_COLUMN` only applies while a column is being dragged. Every
  other layout -- a new tab, a window resize, a scrollbar -- spreads the spare width over all six
  columns in proportion, so a 25-pixel size column came out at 114 (measured on a plain `JTable`
  900 px wide). The widths the plugin set were preferences that got spread away, which also
  looked like "the other tabs ignore my drag". The table now lays itself out: every column but
  the name at exactly its width, the name column taking the rest. A drag also moves the column's
  preference with it, or the next layout would put it back.
- **Numbers take the room of their digits:** three characters minimum for `hex`, `dec`, `sz` and
  `al`, more only when this type's numbers need it -- a dragged width is kept unless a number
  would be clipped. The `size` and `align` titles are now `sz` and `al` so the title fits three
  characters.
- **The type column is wider** -- it fits names up to 64 characters (was 28), and starts at 20 --
  and the name column takes whatever is left.
- **Widths are stored per project**, in the workspace file (`MemoryLayoutProjectSettings`); a
  project with none yet starts from the widths last dragged anywhere. The application-wide value
  moved to a new key (`columnWidths3`) because the old one holds the bloated widths described
  above, and reading it back would have brought them back.

## 1.0.2 -- a row opens its declaration

- **One click on a row shows where the field is declared.** Navigation used to need a double
  click, which the tree also takes as "fold or unfold", so it read as not working. A plain click
  now opens the file at the field and leaves the focus in the window, so the next row can be
  clicked straight away; a double click opens it and moves the focus to the editor. A click with
  Ctrl, Shift or Cmd only changes the selection, as before.
- **The caret lands on the field's name**, not on the start of its statement -- which was its
  first attribute or modifier, often a line above the name. `SourceText.nameOffsetInStatement`
  takes the last match before the declaration ends, so `Vector3 Vector3;` lands on the field and
  not on its type, and a positional record's parameter or an auto-property's name is found too.
- A field read from an assembly (`List<T>._items`, anything from UnityEngine) has no source file,
  and a click on it does nothing.
- The version line jumped from 0.5.2 to 1.0.1 without a changelog entry in between; whatever that
  release contained is not described here.

## 0.5.2 -- a variable opens its type

- **The caret on a variable opens the type it was declared with.** Before, the name under the
  caret was only ever looked up as a type, so `position` or `enemy` found nothing. Now
  `layout/VariableTypes.kt` finds the declaration the way C# scoping would -- the enclosing blocks
  first, then the members of the enclosing types and their base classes -- and opens the type
  written there: locals, parameters, `for`/`foreach`/`using`/`catch` variables, lambda parameters,
  `out` and pattern variables (which leak into the enclosing block, as in C#), fields, properties
  and methods. A name that is itself a type still opens that type.
- **Member access is followed.** `enemy.health.value`, `this.body`, `transform.position`,
  `GetComponent<Rigidbody>()` -- each step is typed from the previous one, a generic owner's
  arguments bound (`Box<Enemy>.value` is an `Enemy`), a generic method's too. For that the metadata
  reader now also reads properties and method return types (`PropertyMap`, `Property`,
  `MethodDef`), written into the synthesized declarations with bodies so the field reader keeps
  ignoring them and layouts do not change.
- **`var` is typed where the text says what it is:** `new T(...)`, a cast, `as T`, `default(T)`,
  `stackalloc`, a literal (`5f` is a `float`, read from the unmasked text because the mask blanks
  literals), a variable or member chain, and `foreach (var x in ...)` over an array, a `List`-like
  collection or a dictionary (`KeyValuePair<K, V>`). Anything else -- `var x = a + b` -- says so in
  a popup instead of guessing.
- **A generic variable opens that instantiation.** `List<int> ids` opens `List<int>`, with `T`
  bound to `int` in every field; its tab is titled that way and is separate from `List<float>`.
  An array opens its element type, `T?` opens `T`.
- Checked with 27 tests of scoping and false positives (`a > x`, `flag ? x : y`, a parameter of
  another method, text in comments and strings) and 4 more through metadata-shaped UnityEngine types.

## 0.5.1 -- preprocessor directives

- **A type below a `#endif` is found again.** `PlayerLoopTimer.playerLoopTiming` showed as
  `PlayerLoopTiming ?` with no size: the enum sits in UniTask's `PlayerLoopHelper.cs`, where the
  `using` block ends in `#endif` right above `namespace Cysharp.Threading.Tasks`. Directives were
  not masked, so `#endif` became the first word of the namespace header, the header no longer
  started with `namespace`, and the whole namespace -- every type in the file -- was skipped.
  `CodeMask` now blanks a directive line whole (`#if`, `#endif`, `#region Fields`, `#pragma ...`)
  whenever the `#` is the first thing on its line. The code between `#if` and `#endif` is still
  read, every branch of it: the mask cannot know which symbols are defined.
- **A byte order mark no longer starts the first statement.** It is not whitespace to Kotlin, so a
  file read from disk that opens with `namespace Foo {` got a header that did not start with the
  word, the same failure as above.
- Tests: a directive above the namespace, a BOM before `namespace` and before `#pragma`, a
  `#region` between fields, and a `#` inside a string or a character literal. All 106 `layout/`
  tests pass; `PlayerLoopHelper.cs` itself now yields `PlayerLoopTiming`,
  `InjectPlayerLoopTimings`, `IPlayerLoopItem` and `PlayerLoopHelper`.

## 0.5.0 -- types without source

- **`string`, `List<T>`, `Guid`, `Vector3` and every other type the project does not declare can be
  opened.** They are read from the metadata of the assemblies the project compiles against: a new
  `metadata/` package reads the ECMA-335 tables (fields, `ClassLayout`, `FieldLayout`, nesting,
  generic parameters, field signatures) and writes each type back out as the C# declaration it was
  compiled from, which the engine then reads like any other source -- `[StructLayout]`,
  `[FieldOffset]`, auto-properties and nested generics included, with no second engine to drift.
  Opening `Color32` now shows `rgba` overlaying `r`, `g`, `b`, `a`; `RaycastHit` is 44 B; a project
  struct holding a `Matrix4x4`, a `Guid` and a `RaycastHit` is sized exactly instead of approximately.
- **The runtime's assemblies, not the ones the compiler is shown.** A Unity project references
  `NetStandard/ref/2.1.0/netstandard.dll` (and `UnityReferenceAssemblies/unity-4.8-api` for the
  editor assemblies): reference assemblies whose `List<T>` has no fields and whose `Guid`,
  `DateTime` and `decimal` are a single placeholder `int`. Laying those out would have produced
  confident, wrong numbers. They are replaced with Mono's own `mscorlib`, `System` and `System.Core`
  from the same editor install (`MonoBleedingEdge/lib/mono/unityjit-*`), which is also the layout
  the game actually runs with. Every other `HintPath` -- UnityEngine modules, package DLLs -- is
  read as it is; `Library/ScriptAssemblies` (stale copies of the project's own code) and
  `UnityEditor*` (editor-only, tens of megabytes) are skipped.
- **The project still answers first.** A name is looked up in the sources, and only when they do
  not declare it in the assemblies. Types written out from metadata name their field types with
  their namespaces, so the BCL's `Dictionary.Entry` never resolves to a project's own `Entry`.
- **A keyword opens its framework type.** With the caret on `string` or `int`, the window shows
  `System.String` or `System.Int32`. For a string, a note says the characters continue in the same
  object after the last field.
- Checked against the real Mono `mscorlib.dll` of Unity 6000.0.62f1: 3022 types read in about
  130 ms, the instance fields of every one of them identical to an independent reader's.

## 0.4.2 -- packages, and partial types

- **Types from pulled-in packages are found.** A package installed through the manifest (a
  registry, a git URL, OpenUPM) has no sources under `Packages/` -- Unity unpacks it into
  `Library/PackageCache`, and the index never looked there: "Assets and Packages" stopped at
  `Packages/`, and "The whole project" skipped all of `Library`. Opening `UniTask` said "No type
  named UniTask in the index" while Rider's own Structure view showed it fine. `Library/PackageCache`
  is now walked under both of those scopes; the rest of `Library` stays ignored.
- **Folders Unity itself skips are skipped.** A name ending in `~` (`Samples~`, `Documentation~`)
  or starting with `.` is never compiled by Unity, and a package's samples redeclare the package's
  types -- reading them would have put a second, dead copy of each into the index.
- **A partial type is one type.** `UniTask` is declared in eleven files, and each was indexed as
  a type of its own: ten of them with no fields at all, so which one a name landed on decided
  whether the layout was right or empty, and the chooser offered eleven identical lines. The parts
  are now read together -- every part's fields, attributes and base list -- so `[StructLayout]` on
  a part with no fields still governs the fields of another, and a base class named on any part
  counts. The chooser shows the type once. Each field remembers its own file, so navigating from a
  row opens the part that declares it. Parts are ordered by file and then position; when more than
  one part of a struct declares fields, a note says that C# leaves their order undefined (CS0282).
  Checked against the real UniTask sources: 11 parts, one with fields, 16 B on x64.

## 0.4.1 -- selection, and an icon

- **Unfolding a selected struct now selects what appeared.** The selection was held by row, so
  the members that came on screen were rows that had never been in it: the struct stayed
  highlighted and its fields did not, and the table and the bricks stopped agreeing about what
  was selected. Folding renumbered the rows below for the same reason. The selection is grown
  again over whatever the tree shows after an expand or a collapse.
- **A shift range grows over its subtrees like a single click does.** Growing ran only when
  exactly one row was picked, so the ends of a shift range stopped at whatever row they landed
  on while a single click swallowed a whole struct -- one gesture selecting a field and its
  members, the other selecting half of them. One rule now covers a click, a shift range and a
  ctrl pick. Ctrl-picked rows grow too, which they did not before.
- **A shift-click extends from where the reader clicked.** Growing a selection moved the anchor
  to the top of the subtree it grew into, and the next shift-click then ran from there instead
  of from the clicked row, over rows nobody asked for. The anchor is put back.
- **The plugin has an icon.** "ML" over three rows of memory cut into fields, padding in orange,
  in the window's own colours; a dark variant for dark themes. It was the grey plug before.

## 0.4.0 -- generics

- **A written `Box<int>` is laid out for real.** The declaration is found by name *and* arity --
  `Box` and `Box<T>` are two different types and may sit side by side -- its parameters are bound
  to the arguments, and the substitution goes all the way down: `T value` inside `Box<int>` is an
  `int`, `Box<double>` is a different size from `Box<int>`, and an argument may itself be generic
  (`Box<Pair<int, byte>>`). A generic class is still one reference, whatever it is instantiated
  with.
- **An open declaration says it has no size.** Opening `Box<T>` on its own declaration is a fair
  thing to do -- that is where the source is -- but `T` is not a type until somebody writes
  `Box<int>`, so its size stays a dash, the layout is marked approximate, and a note says to open
  it on a use instead. A window that quietly showed zero would be inventing a number.
- **A generic the project does not declare is one reference.** `List<int>` lives in the BCL, not
  in the project, so the index cannot find it -- and it used to be sized at nothing, which moved
  every field after it to the wrong offset while the window looked certain about it. Every generic
  collection and delegate in the BCL is a class, and a class field is a pointer. The few generic
  value types that would break that rule -- `Span<T>`, `ReadOnlySpan<T>`, `Memory<T>`,
  `ReadOnlyMemory<T>` -- are sized properly instead, and a project's own generic struct still
  resolves and wins. The header says which fields were sized this way. A plain name the index
  cannot find stays unknown: `Vector3` could be a struct from anywhere, and eight bytes for it
  would be worse than a dash.
- `layout/GenericName.kt`: reading a written name into its parts. The commas that separate
  arguments are only the ones outside every `<>`, or `Dictionary<int, float>` reads as three
  arguments, two of them nonsense.
- The same declaration nested inside itself under a different argument is no longer refused as a
  cycle: `Box<Box<int>>` is an ordinary nesting.
- **The bricks keep four characters of margin on each side.** The number that closes a cache line
  is drawn past the last brick, and with a margin of a few pixels `128` landed on top of `124` and
  was then cut off by the edge.

## 0.3.3 -- selection that keeps up

- **Fixed: selection felt slow.** Nothing was waiting on anything -- both views called
  `scrollRectToVisible` on every selection, including when the row was already under the mouse,
  and the scroll pane animates. Clicking made the view slide somewhere for no reason, which reads
  as lag. Both now scroll only when the target is actually off screen, and the table's selection
  events are ignored while they are still settling.
- **Nothing is lit until something is selected.** The bricks open quiet; a click is what turns
  part of the picture on. A view that starts with every brick at full colour has already answered
  a question nobody asked.
- **Selecting a struct selects its members in the table too**, not only in the bricks. An unfolded
  subtree is a run of consecutive rows, so the table paints it itself.
- **Fixed: picking several rows the way you pick files lit up none of them.** The bricks tracked a
  single node; they now track the set, and every selected row's bytes light up.
- The table's font size is a setting, and so is the size of the offsets and ticks around the
  bricks.
- **Fixed: a class's cache lines were drawn from the wrong place.** The lines were cut from byte
  zero, which is where the *reference* points -- eight bytes into the allocation. The allocation
  begins at the object header, and that is what it is aligned on, so the grid being drawn was one
  that does not exist in memory: a 24-byte object appeared to span two lines with 24 bytes of
  nothing in front of it. The lines are now cut from the header. Neither picture is exact, since
  an allocation is aligned to eight bytes and not to a cache line, but this one does not lie about
  the object's own footprint, and "does this fit in a line" is finally answerable from it. The
  offsets are unchanged -- still measured from the reference, so the first row simply starts at -8.
- **The numeric columns are measured against the type on screen.** A struct whose largest offset
  is 28 gets an offset column the width of `0x1C`; one that runs to 4096 gets six characters of
  it. A fixed width was wrong in both directions at once -- pointless air on a small type, a
  clipped number on a big one -- and it meant dragging a column to read a number the window
  already knew the width of. The widest text is measured rather than counted in characters: the
  table's font is the IDE's, its digits are narrower than its average character, and a character
  count paid for space no digit ever occupies.
- **Fixed: stored column widths came from the table, not from the reader.** The width store was
  written on every margin change, and the table fires those from its own layout -- resizing the
  window, showing a scrollbar -- so every install had a set of widths nobody chose, and since a
  stored width wins over a measured one they were never going to go away. Only a real drag is
  recorded now, and the store starts empty once.
- **The ruler under the bricks starts at zero.** A class begins at its object header, eight bytes
  before the reference, so the scale opened with -8 and -4: negative numbers for the first bytes
  of the very thing being drawn. The whole scale is shifted so byte zero is where the object
  starts. The table still shows the real offsets -- it is the one answering "where is this field",
  and a debugger reports the same.
- **The bricks keep a margin from the edge of their scrolling area.** The first row's outline sat
  flush against the top of the viewport and the last byte's rounded corner against the right, and
  both were being shaved by a pixel -- which reads as a rendering fault rather than as a missing
  margin.
- **A black rule with a character of air on each side now separates `align` from `type`.** A
  column of digits running straight into a column of identifiers read as one smeared column. The
  air is measured in characters, so it stays right when the font size changes.
- **Each cache line now says where it ends.** The ticks stepped through the line and stopped, and
  the reader had to multiply to find the end -- which is the number they were looking at a picture
  to avoid working out.

## 0.3.2 -- the bytes before the object, and labels that fit

- **Fixed: clicking a brick selected nothing.** The click set the selected node directly and then
  asked the view to select it, and the view -- seeing the node it already held -- returned without
  building the set of highlighted bricks. Nothing on screen moved. The click now goes through the
  same path as a selection from the table.
- **A class's object header is drawn.** The row that holds it runs from the cache line below zero,
  with the bytes that belong to the allocator, not to this type, greyed out beside it. Byte zero
  still starts a row, so the cache lines stay on their grid. Its gutter says `header` instead of a
  line number.
- Bricks fade much further towards grey, and the grey now has the window's background mixed into
  it, so an unselected brick reads as translucent rather than as a different colour. The defaults
  came down with it: 64 for the selection, 20 for everything else, 55 for padding on top of that.
- A brick's outline is its own colour half way to black, rather than one flat dark line.
- **Names that do not fit shrink before they wrap, and wrap before they are cut.** A size or two
  smaller first, then two lines, then three, then trimmed from the end. A name is split at word
  boundaries where there is one near the middle: `worldPosition` becomes `world` and `Position`,
  not `worldPo` and `sition`. The font size, how far it may shrink and how many lines it may use
  are settings.
- The cache-line size field is gone: the preset buttons now show which size is in force, which is
  what the field was standing in for.
- The target is two buttons reading `x64` and `x32`. They were toggle actions, which draw the
  platform's own on/off diamond with the text as small print beside it -- backwards, when the text
  is the whole point of the button.

## 0.3.1 -- a class with an interface field, and bricks you can read

- **Fixed: a field whose type lives in an assembly rather than in the project.** `ILogger _logger`
  resolved to nothing, took zero bytes, and left the class claiming 16 bytes of which half was
  padding -- and every offset after such a field would have been wrong while the window looked
  certain about it. A name of the shape `ILogger` is now sized as the one reference it is, by the
  interface-naming convention every C# codebase follows, and the header says the size came from
  the name rather than from a declaration. `IntPtr` and `Int32` are not caught by the rule.
  The negative offset of the object header, which looked like the culprit, was not: it is carried
  correctly through the table, the cache-line arithmetic and the bricks.
- **Contrast is a setting**, three of them: what the selection keeps, what everything else keeps,
  and how far padding fades on top of that. Full saturation everywhere was the real complaint --
  with every brick shouting, none of them answers "where is this field", and dark text on a
  saturated fill is hard work.
- A brick's label is now black or white by what the fill can carry, rather than always dark.
- A name too long for its brick is cut from the end instead of vanishing: `velo` still says which
  field it is, an empty brick says nothing.
- **Selecting a struct lights up every byte it owns**, not just its own row -- the members are the
  bytes, so the group is what the reader meant. Everything outside the selection fades.
- The vendor is `ivv`.

## 0.3.0 -- bricks, and what a class really costs

- **The brick view.** Under the table, the type drawn as bytes: one row is one cache line, one
  rectangle is a field, and a field that reaches past the end of a line is cut there and drawn on
  both rows with the join marked. The tail of the last line says how many bytes the type pays for
  and does not use. Click a name in the table and its bricks light up; click a brick and the table
  selects the field, unfolding whatever was hiding it. The two views scroll apart -- a 64-byte line
  four characters to the byte is wider than any panel -- and the offsets sit in a row header so
  they stay put while the bricks scroll sideways. A slider squeezes the byte down when a type is
  too big to take in at full width.
- `layout/BrickLayout.kt`: which bytes each rectangle covers, with its own tests. Geometry is a
  fact about the type, not a decision about how to draw it. The two views agree because they hold
  the same `LayoutNode` instances and compare them by identity -- an offset is not unique under
  `LayoutKind.Explicit`, a name is not unique across nested structs, and an index path breaks the
  moment padding rows are hidden.
- **Classes are laid out as they actually sit on the heap.** The object header, eight bytes at
  -8 (four at -4 on x86), carrying the sync block index. The method table pointer at 0 -- the type
  handle, with the vtable inside what it points at, because .NET has no per-object vtable pointer
  the way C++ does. Fields from 8, base class first. An allocation never under 24 bytes (12 on
  x86). The header line says what the allocation costs, and the notes say out loud that
  `LayoutKind.Auto` lets the runtime reorder, so the order shown is the declaration's.
- **Tabs close.** The tool window now allows it, so the close button, the middle click and
  "Close Other Tabs" all work; the placeholder comes back when the last one goes.
- **The window's background is a setting**: follow the IDE theme, take the editor's, or pick a
  colour. The editor's is the one worth having -- a panel a shade off the code it is about reads
  as a different application.
- 70 unit tests, run against the files on disk with a standalone `kotlinc`.

## 0.2.0 -- the table becomes readable

- Renamed out of the `com.hitapps` namespace: the package, the Gradle group and the plugin id are
  all `com.memorylayout` now. The id is what an IDE identifies a plugin by, so 0.1.0 has to be
  uninstalled by hand -- to the IDE this is a different plugin, not an update of that one. The
  settings file keeps its name, so what was configured survives.
- The tree moved from the `type` column to `name`: what nests is the path to a member, and the
  four number columns keep the left edge to themselves. Every row also carries a bar in its
  level's colour, so depth survives a narrow column and a three-deep struct.
- Colour by role, and no grey anywhere: blue offsets, green sizes, teal alignments, violet types,
  amber padding, red unresolved. Grey is what the eye skips and the padding rows are the reason
  this window exists.
- Column widths default to the width of what goes in them -- five characters for an offset, not
  the 64 pixels of nothing it had before -- measured in the table's own font rather than pixels.
  Dragging one now moves it in every open tab and is remembered between sessions.
- The target is two buttons reading `64` and `32` instead of one checkbox reading `x86`, it is
  shared by every tab, and it is remembered. Two tabs on different targets were two tabs whose
  numbers could not be compared, which is the opposite of what the tabs are for.
- Cache lines: a size to measure against (32, 64, 128 as buttons, anything else typed in; 64 by
  default, which is x86-64, ARM64 and every console -- 128 is Apple silicon). The header says how
  many lines the type costs and how much of the last one it wastes; a field a line cuts in half is
  marked `split` and its offsets turn colour.
- Reference fields say so. An interface field is one pointer -- 8 bytes on x64, no vtable pointer
  in the field, the dispatch goes through the object's own method table -- so it is marked `ref`,
  has nothing to expand, and the tooltip says why.
- `CacheLineMath` in `layout/`: which lines a range touches, what a boundary cuts, what the last
  line wastes. Pure arithmetic with its own tests, because whether a field is split is a fact
  about the type and not a decision about how to draw it.
- 49 unit tests, run against the files on disk with a standalone `kotlinc`. The UI was not
  compiled this round: the platform jars live in a Gradle cache the session could not reach, and
  no new platform API was used because of it.

## 0.1.0 -- in progress

The layout engine, with no UI yet.

- `layout/`: a pure package with no IntelliJ dependency -- literal masking, type declarations,
  field reading, a size table and the placement engine. 33 unit tests, run against the files on
  disk with a standalone `kotlinc` (see `CLAUDE.md`).
- Sequential, `Pack`, `Size` and explicit layouts; nested structs expanded with absolute offsets;
  enums, `fixed` buffers, auto-properties, positional record parameters, `Nullable<T>`.
- x64 and x86 targets.
- Blittability is reported with the reason, not as a bare flag.
- The project type index: walks `Assets` and `Packages` (configurable), keeps names and places
  rather than contents, re-reads a file when it changes, and prefers an open editor's text over
  what is on disk.
- The window: one tab per type, a header line, and a tree table whose expanders sit in the `type`
  column. Padding rows, unresolved types and auto-property markers each have their own colour, all
  of them in `MemoryLayoutStyle` so the look can be argued with in one file.
- `Memory Layout` in the editor's context menu, on the type under the caret. Several matches open a
  chooser.
- A settings page: target, index scope, padding rows, auto-property markers, expand depth, tabs.
- 38 unit tests. The whole plugin, UI included, compiles against the real platform jars -- see
  `CLAUDE.md` for how, and for the JVM-target trap it uncovered.
