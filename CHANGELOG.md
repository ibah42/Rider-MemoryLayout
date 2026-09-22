# Changelog

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
