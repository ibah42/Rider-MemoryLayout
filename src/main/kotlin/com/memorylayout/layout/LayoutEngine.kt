package com.memorylayout.layout

import kotlin.math.max
import kotlin.math.min

/**
 * Places the fields of a declaration in memory and reports where each one landed.
 *
 * The rules are the CLR's: fields sit in declaration order, each at the next offset its own
 * alignment allows, the whole struct is as aligned as its most demanding field, and its size is
 * rounded up to that. `Pack` lowers every alignment to at most its value, `Size` raises the total,
 * `LayoutKind.Explicit` replaces the walk with the offsets the attribute gives.
 *
 * What comes out is a tree of byte ranges, not table rows: see [LayoutNode].
 */
class LayoutEngine(
    private val target: LayoutTarget,
    private val lookup: TypeLookup,
) {

    /**
     * @param typeArguments what the declaration's type parameters are bound to -- `int` for a
     *   `List<int>` variable -- or empty for the open declaration
     * @param elementCount the reader's `n` for a type that ends in repeating elements -- a
     *   `string`'s characters -- or [RepeatTail.UNKNOWN_COUNT]
     */
    fun layoutOf(
        type: DeclaredType,
        typeArguments: List<String> = emptyList(),
        elementCount: Int = RepeatTail.UNKNOWN_COUNT,
    ): TypeLayout {
        val declaration = type.declaration
        val arguments = GenericName.bind(declaration.genericParameters, typeArguments)
        val result: StructResult
        if (declaration.isValueType) {
            result = buildStruct(type, HashSet(), 0, arguments)
        } else {
            result = buildObject(type, HashSet(), arguments, elementCount)
        }
        val displayName: String
        if (arguments.isEmpty()) {
            displayName = declaration.displayName
        } else {
            displayName = declaration.name + "<" + typeArguments.joinToString(", ") + ">"
        }
        val confidence: LayoutConfidence
        if (result.isRuntimeDefined) {
            confidence = LayoutConfidence.RUNTIME_DEFINED
        } else if (result.hasUnresolved) {
            confidence = LayoutConfidence.APPROXIMATE
        } else {
            confidence = LayoutConfidence.EXACT
        }
        return TypeLayout(
            displayName = displayName,
            qualifiedName = declaration.qualifiedName,
            target = target,
            declaredLayoutKind = FieldReader.readLayoutAttribute(declaration).kind,
            size = result.size,
            alignment = result.alignment,
            paddingBytes = paddingBytesOf(result.nodes),
            nodes = result.nodes,
            confidence = confidence,
            blittableProblems = result.blittableProblems,
            notes = type.notes + result.notes + PartialTypes.notesFor(type),
            repeat = result.repeat,
        )
    }

    /**
     * `T[]` as it sits on the heap in Mono and IL2CPP: one allocation holding the vtable, the
     * monitor, the bounds pointer (null for a one-dimensional array), the length, and then every
     * element inline, from 0x20 on x64 and 0x10 on x86. An element that is a class is a reference
     * here; its object is somewhere else.
     *
     * @param context where the element's name was written, for resolving it
     */
    fun layoutOfArray(
        elementTypeName: String,
        context: LookupContext,
        elementCount: Int = RepeatTail.UNKNOWN_COUNT,
    ): TypeLayout {
        val pointerSize = target.pointerSize
        val nodes = ArrayList<LayoutNode>()
        val notes = ArrayList<String>()
        nodes.add(runtimeNode(0, pointerSize, VTABLE_TYPE, VTABLE_NAME))
        nodes.add(runtimeNode(pointerSize, pointerSize, MONITOR_TYPE, MONITOR_NAME))
        nodes.add(runtimeNode(pointerSize * 2, pointerSize, ARRAY_BOUNDS_TYPE, ARRAY_BOUNDS_NAME))
        nodes.add(runtimeNode(pointerSize * 3, pointerSize, ARRAY_LENGTH_TYPE, ARRAY_LENGTH_NAME))
        val dataOffset = roundUp(pointerSize * ARRAY_HEADER_POINTERS, ALLOCATION_ALIGNMENT)
        val element = placeField(
            FieldDeclaration(typeName = elementTypeName, name = ELEMENT_NAME),
            context,
            HashSet(),
            1,
        )
        val displayName = elementTypeName + ARRAY_SUFFIX
        if (!element.isResolved) {
            nodes.add(
                LayoutNode(
                    kind = NodeKind.UNRESOLVED,
                    offset = dataOffset,
                    size = 0,
                    alignment = 1,
                    typeName = elementTypeName,
                    fieldName = ELEMENT_NAME,
                )
            )
            notes.add("The element type $elementTypeName could not be resolved, so its size is unknown")
            return TypeLayout(
                displayName = displayName,
                qualifiedName = displayName,
                target = target,
                declaredLayoutKind = DeclaredLayoutKind.SEQUENTIAL,
                size = dataOffset,
                alignment = pointerSize,
                paddingBytes = 0,
                nodes = nodes,
                confidence = LayoutConfidence.APPROXIMATE,
                notes = notes,
            )
        }
        val alignment = max(element.alignment, 1)
        val stride = roundUp(element.size, alignment)
        val tail = RepeatTail(
            offset = dataOffset,
            stride = stride,
            elementTypeName = elementTypeName,
            extraElements = 0,
            elementCount = elementCount,
        )
        nodes.add(
            repeatNode(
                tail = tail,
                alignment = alignment,
                fieldName = "[0 … ${tail.countText})",
                elementChildren = shiftNodes(element.children, dataOffset),
                isReference = element.isReference,
            )
        )
        val size = finishRepeat(nodes, tail)
        notes.add(
            "One allocation: vtable, monitor, bounds (null for a one-dimensional array), length, then " +
                "the elements inline from $dataOffset, $stride B apart"
        )
        if (element.isReference) {
            notes.add(
                "Every element is a $pointerSize B reference: the $elementTypeName objects themselves " +
                    "are separate allocations"
            )
        }
        val confidence: LayoutConfidence
        if (element.isGuess) {
            confidence = LayoutConfidence.APPROXIMATE
        } else {
            confidence = LayoutConfidence.EXACT
        }
        return TypeLayout(
            displayName = displayName,
            qualifiedName = displayName,
            target = target,
            declaredLayoutKind = DeclaredLayoutKind.SEQUENTIAL,
            size = size,
            alignment = pointerSize,
            paddingBytes = paddingBytesOf(nodes),
            nodes = nodes,
            confidence = confidence,
            notes = notes,
            repeat = tail,
        )
    }

    /**
     * The row standing for every element: one element's worth of bytes while the count is
     * unknown, all of them once it is known, element `[0]` as its children.
     */
    private fun repeatNode(
        tail: RepeatTail,
        alignment: Int,
        fieldName: String,
        elementChildren: List<LayoutNode>,
        isReference: Boolean,
        declarationOffset: Int = -1,
        fileId: String = "",
    ): LayoutNode {
        val size: Int
        if (tail.isCountKnown) {
            size = tail.stride * (tail.elementCount + tail.extraElements)
        } else {
            size = tail.stride
        }
        return LayoutNode(
            kind = NodeKind.REPEAT,
            offset = tail.offset,
            size = size,
            alignment = alignment,
            typeName = tail.elementTypeName,
            fieldName = fieldName,
            declarationOffset = declarationOffset,
            fileId = fileId,
            isReference = isReference,
            children = elementChildren,
            repeatStride = tail.stride,
            repeatCountText = tail.countText,
        )
    }

    /**
     * The size of an object ending in [tail]. With the count known it is the whole allocation,
     * rounded up to the collector's 8 bytes with the rounding shown as padding; without it, the
     * object holding only the elements the runtime always adds.
     */
    private fun finishRepeat(nodes: MutableList<LayoutNode>, tail: RepeatTail): Int {
        if (!tail.isCountKnown) {
            return tail.offset + tail.stride * tail.extraElements
        }
        val end = tail.offset + tail.stride * (tail.elementCount + tail.extraElements)
        val size = roundUp(end, ALLOCATION_ALIGNMENT)
        if (size > end) {
            nodes.add(paddingNode(end, size - end))
        }
        return size
    }

    /** What one declaration turned into, with its children already at their absolute offsets. */
    private class StructResult(
        val size: Int,
        val alignment: Int,
        val nodes: List<LayoutNode>,
        val hasUnresolved: Boolean,
        val isRuntimeDefined: Boolean,
        val blittableProblems: List<String>,
        val notes: List<String>,
        val repeat: RepeatTail? = null,
    )

    /** What one field turned into, with its children still relative to the field's own start. */
    private class FieldPlacement(
        val size: Int,
        val alignment: Int,
        val children: List<LayoutNode>,
        val isResolved: Boolean,
        val isRuntimeDefined: Boolean = false,
        val isReference: Boolean = false,

        /** The size came from the shape of the name, not from a declaration anybody read. */
        val isGuess: Boolean = false,
        val blittableProblems: List<String> = emptyList(),
    )

    /**
     * A class instance, as it sits on the heap -- in Unity, which means Mono or IL2CPP.
     *
     * Three things make it a different job from a struct, and all three are invisible in the
     * source:
     *
     * - **The object header is two pointers at the start of the object**, where the reference
     *   points: the vtable (Mono's `MonoVTable*`, IL2CPP's `Il2CppClass*`), which is the type's
     *   identity and its dispatch table both, then the monitor, which carries the lock and, once
     *   asked for, the hash code. Fields begin after them, at 0x10 on x64 and 0x8 on x86. This is
     *   not CoreCLR, which keeps its sync block before the reference.
     * - **The base class comes first.** `Enemy : Actor` puts `Actor`'s fields right after the
     *   header, then its own.
     * - **Each class's own fields go in two passes** unless the class says
     *   `[StructLayout(LayoutKind.Sequential)]` or `Explicit`: the references first, so that the
     *   collector finds them together, then everything else -- each pass in declaration order,
     *   each field at its own alignment. That is Mono's `mono_class_layout_fields`; it does not
     *   sort by size.
     *
     * A `string` is the one class whose instance does not end at its last field: its characters
     * follow `_firstChar`, so it gets no tail padding and its size is that of the empty string.
     */
    private fun buildObject(
        type: DeclaredType,
        visiting: MutableSet<String>,
        arguments: Map<String, String>,
        elementCount: Int,
    ): StructResult {
        val declaration = type.declaration
        val attribute = FieldReader.readLayoutAttribute(declaration)
        val chain = baseChain(type, visiting)
        val nodes = ArrayList<LayoutNode>()
        val notes = ArrayList<String>()
        val problems = ArrayList<String>()
        val pointerSize = target.pointerSize
        nodes.add(runtimeNode(0, pointerSize, VTABLE_TYPE, VTABLE_NAME))
        nodes.add(runtimeNode(pointerSize, pointerSize, MONITOR_TYPE, MONITOR_NAME))
        var offset = pointerSize * HEADER_POINTERS
        var maxAlignment = pointerSize
        var unresolvedCount = 0
        var reordered = false
        val objectFields = ArrayList<FieldDeclaration>()
        val objectPlacements = ArrayList<Pair<FieldDeclaration, FieldPlacement>>()
        for (holder in chain) {
            val context = LookupContext(
                namespaceName = holder.declaration.namespaceName,
                containerNames = holder.declaration.containerNames + holder.declaration.name,
                fileId = holder.fileId,
            )
            val holderAttribute = FieldReader.readLayoutAttribute(holder.declaration)
            val placed = ArrayList<Pair<FieldDeclaration, FieldPlacement>>()
            for (declared in FieldReader.readFields(holder)) {
                val field = substituted(declared, arguments)
                placed.add(Pair(field, placeField(field, context, visiting, 1)))
            }
            val ordered = runtimeOrderOf(placed, holderAttribute.kind)
            if (ordered != placed) {
                reordered = true
            }
            for ((field, shape) in ordered) {
                objectFields.add(field)
                objectPlacements.add(Pair(field, shape))
                val alignment = capAlignment(shape.alignment, attribute.pack)
                val aligned = roundUp(offset, alignment)
                if (aligned > offset) {
                    nodes.add(paddingNode(offset, aligned - offset))
                }
                nodes.add(fieldNode(field, shape, aligned, holder.fileId))
                collectProblems(field, shape, problems)
                if (!shape.isResolved) {
                    unresolvedCount++
                }
                offset = aligned + shape.size
                maxAlignment = max(maxAlignment, alignment)
            }
        }
        val size: Int
        var repeat: RepeatTail? = null
        val firstCharIndex = nodes.indexOfLast { node -> node.kind == NodeKind.FIELD }
        if (declaration.qualifiedName == STRING_TYPE && firstCharIndex >= 0) {
            // The characters run on from `_firstChar`, which is the first of them: what looked
            // like two bytes of tail padding is the string. The runtime adds a terminating zero.
            val firstChar = nodes[firstCharIndex]
            val tail = RepeatTail(
                offset = firstChar.offset,
                stride = firstChar.size,
                elementTypeName = firstChar.typeName,
                extraElements = 1,
                elementCount = elementCount,
            )
            nodes[firstCharIndex] = repeatNode(
                tail = tail,
                alignment = firstChar.alignment,
                fieldName = STRING_CHARS_NAME,
                elementChildren = emptyList(),
                isReference = false,
                declarationOffset = firstChar.declarationOffset,
                fileId = firstChar.fileId,
            )
            size = finishRepeat(nodes, tail)
            repeat = tail
        } else {
            size = max(roundUp(offset, maxAlignment), pointerSize * HEADER_POINTERS)
            if (size > offset) {
                nodes.add(paddingNode(offset, size - offset))
            }
        }
        notes.add(
            "Mono and IL2CPP start every object with a $pointerSize B vtable pointer and a " +
                "$pointerSize B monitor; the allocation is $size B, rounded up to 8 by the collector"
        )
        if (reordered) {
            notes.add(
                "A class is laid out by the runtime (LayoutKind.Auto): references first, then the " +
                    "rest, each in declaration order -- the order Mono uses, which is why it differs " +
                    "from the source"
            )
        } else {
            notes.add(
                "A class is laid out by the runtime (LayoutKind.Auto) unless it says otherwise: " +
                    "references first, then the rest, each in declaration order"
            )
        }
        if (chain.size > 1) {
            notes.add("Fields of " + chain.dropLast(1).joinToString(", ") { holder ->
                holder.declaration.name
            } + " come first, as the base class")
        }
        addInterfaceNotes(objectFields, notes)
        addUnfoundGenericNotes(objectPlacements, notes)
        addOpenGenericNote(declaration, arguments, notes)
        addCommonNotes(notes, attribute, unresolvedCount)
        return StructResult(
            size = size,
            alignment = maxAlignment,
            nodes = nodes,
            hasUnresolved = unresolvedCount > 0,
            isRuntimeDefined = true,
            blittableProblems = problems,
            notes = notes,
            repeat = repeat,
        )
    }

    /**
     * The order Mono gives one class's own fields: references first, then everything else, each
     * group in declaration order. A class that asked for `Sequential` or `Explicit` keeps its order.
     */
    private fun runtimeOrderOf(
        placed: List<Pair<FieldDeclaration, FieldPlacement>>,
        kind: DeclaredLayoutKind,
    ): List<Pair<FieldDeclaration, FieldPlacement>> {
        if (kind != DeclaredLayoutKind.AUTO) {
            return placed
        }
        val references = placed.filter { pair -> pair.second.isReference }
        val rest = placed.filterNot { pair -> pair.second.isReference }
        return references + rest
    }

    /** The declaration and everything it inherits from, most-base first. */
    private fun baseChain(type: DeclaredType, visiting: MutableSet<String>): List<DeclaredType> {
        val chain = ArrayList<DeclaredType>()
        var current: DeclaredType? = type
        val seen = HashSet<String>()
        while (current != null && chain.size < MAX_NESTING_DEPTH) {
            val key = current.fileId + "#" + current.declaration.qualifiedName
            if (!seen.add(key)) {
                break
            }
            chain.add(current)
            current = baseClassOf(current, visiting)
        }
        chain.reverse()
        return chain
    }

    /**
     * The base class in a base list, or null when there is none.
     *
     * The list holds interfaces too and the source does not say which is which, so every name is
     * resolved and the first one that turns out to be a class wins. An interface in that position
     * adds no fields, which is the whole reason it has to be told apart.
     */
    private fun baseClassOf(type: DeclaredType, visiting: MutableSet<String>): DeclaredType? {
        val declaration = type.declaration
        val context = LookupContext(
            namespaceName = declaration.namespaceName,
            containerNames = declaration.containerNames,
            fileId = type.fileId,
        )
        for (name in splitBaseList(declaration.baseListText)) {
            val resolved = lookup.resolve(name, context) ?: continue
            if (resolved.declaration.kind == TypeKind.CLASS ||
                resolved.declaration.kind == TypeKind.RECORD_CLASS
            ) {
                return resolved
            }
        }
        return null
    }

    /** `IReadOnly<int>, Actor, IDisposable` split on the commas that are not inside a `<>`. */
    private fun splitBaseList(baseListText: String): List<String> {
        if (baseListText.isBlank()) {
            return emptyList()
        }
        val names = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (character in baseListText) {
            when (character) {
                '<' -> {
                    depth++
                    current.append(character)
                }
                '>' -> {
                    depth--
                    current.append(character)
                }
                ',' -> {
                    if (depth == 0) {
                        names.add(current.toString().trim())
                        current.setLength(0)
                    } else {
                        current.append(character)
                    }
                }
                else -> {
                    current.append(character)
                }
            }
        }
        names.add(current.toString().trim())
        return names.filter { name ->
            name.isNotEmpty()
        }
    }

    private fun runtimeNode(offset: Int, size: Int, typeName: String, fieldName: String): LayoutNode {
        return LayoutNode(
            kind = NodeKind.RUNTIME,
            offset = offset,
            size = size,
            alignment = size,
            typeName = typeName,
            fieldName = fieldName,
        )
    }

    private fun buildStruct(
        type: DeclaredType,
        visiting: MutableSet<String>,
        depth: Int,
        arguments: Map<String, String>,
    ): StructResult {
        val declaration = type.declaration
        val attribute = FieldReader.readLayoutAttribute(declaration)
        val fields = FieldReader.readFields(type).map { declared ->
            substituted(declared, arguments)
        }
        val context = LookupContext(
            namespaceName = declaration.namespaceName,
            containerNames = declaration.containerNames + declaration.name,
            fileId = type.fileId,
        )
        val placements = ArrayList<Pair<FieldDeclaration, FieldPlacement>>(fields.size)
        for (field in fields) {
            placements.add(Pair(field, placeField(field, context, visiting, depth)))
        }
        if (attribute.kind == DeclaredLayoutKind.EXPLICIT) {
            return buildExplicit(declaration, attribute, placements, type.fileId, arguments)
        }
        return buildSequential(declaration, attribute, placements, type.fileId, arguments)
    }

    /** The field as this instantiation sees it: `T value` inside `Box<int>` is `int value`. */
    private fun substituted(field: FieldDeclaration, arguments: Map<String, String>): FieldDeclaration {
        if (arguments.isEmpty()) {
            return field
        }
        return field.copy(typeName = GenericName.substitute(field.typeName, arguments))
    }

    /**
     * Says that a type parameter has no size, which is why the sizes below are dashes.
     *
     * Opening `Box<T>` itself is a fair thing to do -- it is where the declaration is -- but `T`
     * is not a type until somebody writes `Box<int>`, and a window that quietly showed zero would
     * be inventing a number.
     */
    private fun addOpenGenericNote(
        declaration: TypeDeclaration,
        arguments: Map<String, String>,
        notes: MutableList<String>,
    ) {
        if (declaration.genericParameters.isEmpty() || arguments.isNotEmpty()) {
            return
        }
        val parameters = declaration.genericParameters.joinToString(", ")
        notes.add(
            "$parameters has no size here: open ${declaration.name}<...> on a use such as " +
                "${declaration.name}<int> to see real numbers"
        )
    }

    private fun buildSequential(
        declaration: TypeDeclaration,
        attribute: StructLayoutAttribute,
        placements: List<Pair<FieldDeclaration, FieldPlacement>>,
        fileId: String,
        arguments: Map<String, String>,
    ): StructResult {
        val nodes = ArrayList<LayoutNode>()
        val problems = ArrayList<String>()
        val notes = ArrayList<String>()
        var offset = 0
        var maxAlignment = 1
        var unresolvedCount = 0
        var runtimeDefined = attribute.kind == DeclaredLayoutKind.AUTO
        for (placement in placements) {
            val field = placement.first
            val shape = placement.second
            val alignment = capAlignment(shape.alignment, attribute.pack)
            val aligned = roundUp(offset, alignment)
            if (aligned > offset) {
                nodes.add(paddingNode(offset, aligned - offset))
            }
            nodes.add(fieldNode(field, shape, aligned, fileId))
            collectProblems(field, shape, problems)
            if (!shape.isResolved) {
                unresolvedCount++
            }
            if (shape.isRuntimeDefined) {
                runtimeDefined = true
            }
            offset = aligned + shape.size
            maxAlignment = max(maxAlignment, alignment)
        }
        var size = roundUp(offset, maxAlignment)
        if (placements.isEmpty()) {
            // An empty struct still occupies a byte, or nothing could have an address of its own.
            size = max(size, EMPTY_STRUCT_SIZE)
        }
        if (attribute.declaredSize > size) {
            notes.add("Size = ${attribute.declaredSize} raises the total from $size")
            size = attribute.declaredSize
        }
        if (size > offset) {
            nodes.add(paddingNode(offset, size - offset))
        }
        addInterfaceNotes(placements.map { placement -> placement.first }, notes)
        addUnfoundGenericNotes(placements, notes)
        addOpenGenericNote(declaration, arguments, notes)
        addCommonNotes(notes, attribute, unresolvedCount)
        return StructResult(
            size = size,
            alignment = maxAlignment,
            nodes = nodes,
            hasUnresolved = unresolvedCount > 0,
            isRuntimeDefined = runtimeDefined || !declaration.isValueType,
            blittableProblems = problems,
            notes = notes,
        )
    }

    private fun buildExplicit(
        declaration: TypeDeclaration,
        attribute: StructLayoutAttribute,
        placements: List<Pair<FieldDeclaration, FieldPlacement>>,
        fileId: String,
        arguments: Map<String, String>,
    ): StructResult {
        val nodes = ArrayList<LayoutNode>()
        val problems = ArrayList<String>()
        val notes = ArrayList<String>()
        var maxAlignment = 1
        var unresolvedCount = 0
        var missingOffsets = 0
        val ordered = placements.sortedBy { placement -> max(placement.first.explicitOffset, 0) }
        var previousEnd = 0
        var highestEnd = 0
        for (placement in ordered) {
            val field = placement.first
            val shape = placement.second
            if (field.explicitOffset == FieldDeclaration.NO_EXPLICIT_OFFSET) {
                missingOffsets++
            }
            val offset = max(field.explicitOffset, 0)
            if (offset > previousEnd) {
                nodes.add(paddingNode(previousEnd, offset - previousEnd))
            }
            nodes.add(fieldNode(field, shape, offset, fileId).copy(overlapsPrevious = offset < previousEnd))
            collectProblems(field, shape, problems)
            if (!shape.isResolved) {
                unresolvedCount++
            }
            val alignment = capAlignment(shape.alignment, attribute.pack)
            maxAlignment = max(maxAlignment, alignment)
            previousEnd = max(previousEnd, offset + shape.size)
            highestEnd = max(highestEnd, offset + shape.size)
        }
        var size = roundUp(highestEnd, maxAlignment)
        if (placements.isEmpty()) {
            size = max(size, EMPTY_STRUCT_SIZE)
        }
        if (attribute.declaredSize > size) {
            notes.add("Size = ${attribute.declaredSize} raises the total from $size")
            size = attribute.declaredSize
        }
        if (size > highestEnd) {
            nodes.add(paddingNode(highestEnd, size - highestEnd))
        }
        if (missingOffsets > 0) {
            notes.add("$missingOffsets field(s) carry no [FieldOffset] and are shown at 0")
        }
        addUnfoundGenericNotes(placements, notes)
        addOpenGenericNote(declaration, arguments, notes)
        addCommonNotes(notes, attribute, unresolvedCount)
        return StructResult(
            size = size,
            alignment = maxAlignment,
            nodes = nodes,
            hasUnresolved = unresolvedCount > 0,
            isRuntimeDefined = !declaration.isValueType,
            blittableProblems = problems,
            notes = notes,
        )
    }

    /**
     * Says out loud where a size came from a naming convention rather than from a declaration.
     *
     * The reader has to be able to tell a measured number from a guessed one, and the guess is
     * worth making: see [TypeSizeTable.looksLikeInterface].
     */
    private fun addInterfaceNotes(fields: List<FieldDeclaration>, notes: MutableList<String>) {
        val guessed = fields.filter { field ->
            TypeSizeTable.looksLikeInterface(TypeMatching.simpleName(field.typeName))
        }
        if (guessed.isEmpty()) {
            return
        }
        val names = guessed.joinToString(", ") { field ->
            field.name
        }
        notes.add("$names: sized as a reference because the type name says interface")
    }

    /** The generics the index could not find, which were taken to be classes. */
    private fun addUnfoundGenericNotes(
        placements: List<Pair<FieldDeclaration, FieldPlacement>>,
        notes: MutableList<String>,
    ) {
        val guessed = placements.filter { placement ->
            placement.second.isGuess
        }
        if (guessed.isEmpty()) {
            return
        }
        val names = guessed.joinToString(", ") { placement ->
            placement.first.name
        }
        notes.add("$names: not declared in the project, and sized as the one reference a generic class is")
    }

    private fun addCommonNotes(notes: MutableList<String>, attribute: StructLayoutAttribute, unresolvedCount: Int) {
        if (attribute.pack != StructLayoutAttribute.PACK_UNSET) {
            notes.add("Pack = ${attribute.pack} caps every field's alignment")
        }
        if (unresolvedCount > 0) {
            notes.add("$unresolvedCount field type(s) could not be resolved; offsets after them are a guess")
        }
    }

    private fun collectProblems(
        field: FieldDeclaration,
        shape: FieldPlacement,
        problems: MutableList<String>,
    ) {
        for (problem in shape.blittableProblems) {
            problems.add("${field.name}: $problem")
        }
    }

    private fun fieldNode(
        field: FieldDeclaration,
        shape: FieldPlacement,
        offset: Int,
        fileId: String,
    ): LayoutNode {
        val kind: NodeKind
        if (shape.isResolved) {
            kind = NodeKind.FIELD
        } else {
            kind = NodeKind.UNRESOLVED
        }
        // A field of a partial type knows its own file; the type's file is only right for one part.
        val fieldFileId: String
        if (field.fileId.isEmpty()) {
            fieldFileId = fileId
        } else {
            fieldFileId = field.fileId
        }
        return LayoutNode(
            kind = kind,
            offset = offset,
            size = shape.size,
            alignment = shape.alignment,
            typeName = typeNameOf(field),
            fieldName = field.name,
            declarationOffset = field.declarationOffset,
            fileId = fieldFileId,
            isAutoProperty = field.isAutoProperty,
            isReference = shape.isReference,
            children = shiftNodes(shape.children, offset),
        )
    }

    private fun typeNameOf(field: FieldDeclaration): String {
        if (field.fixedBufferLength > 0) {
            return "${field.typeName}[${field.fixedBufferLength}]"
        }
        return field.typeName
    }

    private fun paddingNode(offset: Int, size: Int): LayoutNode {
        return LayoutNode(
            kind = NodeKind.PADDING,
            offset = offset,
            size = size,
            alignment = 1,
            typeName = PADDING_TYPE_NAME,
            fieldName = "",
        )
    }

    private fun shiftNodes(nodes: List<LayoutNode>, delta: Int): List<LayoutNode> {
        if (delta == 0 || nodes.isEmpty()) {
            return nodes
        }
        return nodes.map { node ->
            node.copy(
                offset = node.offset + delta,
                children = shiftNodes(node.children, delta),
            )
        }
    }

    private fun placeField(
        field: FieldDeclaration,
        context: LookupContext,
        visiting: MutableSet<String>,
        depth: Int,
    ): FieldPlacement {
        if (field.fixedBufferLength > 0) {
            return placeFixedBuffer(field)
        }
        val typeName = field.typeName
        val known = TypeSizeTable.metricsFor(typeName, target)
        if (known != null) {
            return metricsPlacement(known)
        }
        if (typeName.endsWith(NULLABLE_SUFFIX)) {
            return placeNullable(typeName.dropLast(1).trim(), context, visiting, depth)
        }
        return placeDeclaredType(typeName, context, visiting, depth)
    }

    private fun placeFixedBuffer(field: FieldDeclaration): FieldPlacement {
        val element = TypeSizeTable.metricsFor(field.typeName, target)
            ?: return unresolvedPlacement()
        return FieldPlacement(
            size = element.size * field.fixedBufferLength,
            alignment = element.alignment,
            children = emptyList(),
            isResolved = true,
            blittableProblems = listOfNotNull(element.blittableProblem),
        )
    }

    /**
     * `T?` over a value type is `Nullable<T>`: a flag then the value, laid out sequentially, which
     * is why an `int?` costs eight bytes rather than five.
     */
    private fun placeNullable(
        innerTypeName: String,
        context: LookupContext,
        visiting: MutableSet<String>,
        depth: Int,
    ): FieldPlacement {
        val inner = placeField(
            FieldDeclaration(typeName = innerTypeName, name = NULLABLE_VALUE_NAME),
            context,
            visiting,
            depth,
        )
        if (!inner.isResolved) {
            return unresolvedPlacement()
        }
        if (inner.isReference) {
            // `Exception?`, `string?`: a nullable reference type is an annotation for the
            // compiler, not a Nullable<T>. In memory it is the same one pointer, null or not.
            return inner
        }
        val alignment = max(inner.alignment, 1)
        val valueOffset = roundUp(1, alignment)
        val size = roundUp(valueOffset + inner.size, alignment)
        val children = ArrayList<LayoutNode>()
        children.add(
            LayoutNode(
                kind = NodeKind.FIELD,
                offset = 0,
                size = 1,
                alignment = 1,
                typeName = "bool",
                fieldName = NULLABLE_FLAG_NAME,
            )
        )
        if (valueOffset > 1) {
            children.add(paddingNode(1, valueOffset - 1))
        }
        children.add(
            LayoutNode(
                kind = NodeKind.FIELD,
                offset = valueOffset,
                size = inner.size,
                alignment = inner.alignment,
                typeName = innerTypeName,
                fieldName = NULLABLE_VALUE_NAME,
                children = shiftNodes(inner.children, valueOffset),
            )
        )
        if (size > valueOffset + inner.size) {
            children.add(paddingNode(valueOffset + inner.size, size - valueOffset - inner.size))
        }
        return FieldPlacement(
            size = size,
            alignment = alignment,
            children = children,
            isResolved = true,
            blittableProblems = listOf(NULLABLE_PROBLEM) + inner.blittableProblems,
        )
    }

    private fun placeDeclaredType(
        typeName: String,
        context: LookupContext,
        visiting: MutableSet<String>,
        depth: Int,
    ): FieldPlacement {
        val resolved = lookup.resolve(typeName, context) ?: return unfoundPlacement(typeName)
        val declaration = resolved.declaration
        when (declaration.kind) {
            TypeKind.ENUM -> {
                val underlying = TypeSizeTable.metricsFor(FieldReader.readEnumUnderlyingType(declaration), target)
                    ?: return unresolvedPlacement()
                return metricsPlacement(underlying)
            }
            TypeKind.CLASS, TypeKind.RECORD_CLASS, TypeKind.INTERFACE -> {
                return metricsPlacement(TypeSizeTable.referenceMetrics(target))
            }
            else -> {
                val arguments = GenericName.bind(
                    declaration.genericParameters,
                    GenericName.argumentsOf(typeName),
                )
                return placeNestedStruct(resolved, visiting, depth, arguments)
            }
        }
    }

    private fun placeNestedStruct(
        resolved: DeclaredType,
        visiting: MutableSet<String>,
        depth: Int,
        arguments: Map<String, String>,
    ): FieldPlacement {
        // The arguments are part of the key: `Box<int>` inside `Box<float>` is not a cycle, and
        // refusing it would be refusing a perfectly ordinary nesting.
        val key = resolved.fileId + "#" + resolved.declaration.qualifiedName +
            "<" + arguments.values.joinToString(",") + ">"
        if (depth >= MAX_NESTING_DEPTH || key in visiting) {
            // A struct cannot really contain itself; seeing one means the source is mid-edit or
            // the name resolved to the wrong declaration. Either way, stop rather than recurse.
            return unresolvedPlacement()
        }
        visiting.add(key)
        val nested = buildStruct(resolved, visiting, depth + 1, arguments)
        visiting.remove(key)
        return FieldPlacement(
            size = nested.size,
            alignment = nested.alignment,
            children = nested.nodes,
            isResolved = !nested.hasUnresolved,
            isRuntimeDefined = nested.isRuntimeDefined,
            blittableProblems = nested.blittableProblems,
        )
    }

    private fun metricsPlacement(metrics: TypeMetrics): FieldPlacement {
        return FieldPlacement(
            size = metrics.size,
            alignment = metrics.alignment,
            children = emptyList(),
            isResolved = true,
            isReference = metrics.isReferenceType,
            blittableProblems = listOfNotNull(metrics.blittableProblem),
        )
    }

    /**
     * What a name the index could not find is worth.
     *
     * For a generic, one reference. Every generic collection and delegate in the BCL is a class,
     * the value types that would break that rule are named in [TypeSizeTable], and a project's own
     * generic struct would have resolved -- so what is left is a class, and a class field is a
     * pointer. The alternative is what this used to do: call it unknown, give it no bytes at all,
     * and silently move every field after it to the wrong offset.
     *
     * For a plain name, still unknown. `Vector3` might be a struct from anywhere, and guessing
     * eight bytes for it would be worse than a dash.
     */
    private fun unfoundPlacement(typeName: String): FieldPlacement {
        if (GenericName.arityOf(typeName) == 0) {
            return unresolvedPlacement()
        }
        val metrics = TypeSizeTable.referenceMetrics(target)
        return FieldPlacement(
            size = metrics.size,
            alignment = metrics.alignment,
            children = emptyList(),
            isResolved = true,
            isReference = true,
            isGuess = true,
            blittableProblems = listOfNotNull(metrics.blittableProblem),
        )
    }

    private fun unresolvedPlacement(): FieldPlacement {
        return FieldPlacement(
            size = 0,
            alignment = 1,
            children = emptyList(),
            isResolved = false,
        )
    }

    private fun capAlignment(alignment: Int, pack: Int): Int {
        if (pack == StructLayoutAttribute.PACK_UNSET) {
            return max(alignment, 1)
        }
        return max(min(alignment, pack), 1)
    }

    private fun roundUp(value: Int, alignment: Int): Int {
        if (alignment <= 1) {
            return value
        }
        return (value + alignment - 1) / alignment * alignment
    }

    private fun paddingBytesOf(nodes: List<LayoutNode>): Int {
        var total = 0
        for (node in nodes) {
            if (node.kind == NodeKind.PADDING) {
                total += node.size
                continue
            }
            total += paddingBytesOf(node.children)
        }
        return total
    }

    companion object {
        const val PADDING_TYPE_NAME = "padding"

        private const val EMPTY_STRUCT_SIZE = 1

        private const val MAX_NESTING_DEPTH = 16

        private const val NULLABLE_SUFFIX = "?"

        private const val NULLABLE_FLAG_NAME = "hasValue"

        private const val NULLABLE_VALUE_NAME = "value"

        private const val NULLABLE_PROBLEM = "Nullable<T> carries a flag alongside the value"

        private const val VTABLE_TYPE = "vtable*"

        private const val VTABLE_NAME = "vtable"

        private const val MONITOR_TYPE = "monitor*"

        private const val MONITOR_NAME = "monitor"

        /** The vtable and the monitor: the smallest object is these two and nothing else. */
        private const val HEADER_POINTERS = 2

        private const val STRING_TYPE = "System.String"

        private const val STRING_CHARS_NAME = "chars"

        private const val ARRAY_BOUNDS_TYPE = "bounds*"

        private const val ARRAY_BOUNDS_NAME = "bounds"

        private const val ARRAY_LENGTH_TYPE = "uintptr"

        private const val ARRAY_LENGTH_NAME = "max_length"

        /** vtable, monitor, bounds, length: the elements start after these four pointers. */
        private const val ARRAY_HEADER_POINTERS = 4

        private const val ARRAY_SUFFIX = "[]"

        private const val ELEMENT_NAME = "[0]"

        /** What the collector rounds every allocation up to. */
        private const val ALLOCATION_ALIGNMENT = 8
    }
}
