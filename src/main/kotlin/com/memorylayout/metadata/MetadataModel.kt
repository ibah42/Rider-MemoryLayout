package com.memorylayout.metadata

import com.memorylayout.layout.DeclaredLayoutKind

enum class MetadataTypeKind {
    CLASS,
    STRUCT,
    ENUM,
    INTERFACE,
}

/**
 * An instance field as the metadata declares it.
 *
 * @param typeName the field's type written as C#, namespaces included, so that it resolves to the
 *   same declaration whichever lookup answers for it
 * @param explicitOffset the `[FieldOffset]`, or [NO_EXPLICIT_OFFSET]
 * @param isAutoPropertyBackingField the compiler's field behind an auto-property; [name] is then
 *   the property's name, which is what a reader recognises
 */
data class MetadataField(
    val name: String,
    val typeName: String,
    val explicitOffset: Int = NO_EXPLICIT_OFFSET,
    val isAutoPropertyBackingField: Boolean = false,
) {
    companion object {
        const val NO_EXPLICIT_OFFSET = -1
    }
}

/**
 * A property or a method, kept for its type and nothing else: what `transform.position` or
 * `GetComponent<Rigidbody>()` evaluates to, so that the window can follow a member access through
 * a type nobody has the source of.
 *
 * @param genericParameters a method's own type parameters, which its return type may name
 */
data class MetadataMember(
    val name: String,
    val typeName: String,
    val isMethod: Boolean,
    val genericParameters: List<String> = emptyList(),
)

/**
 * A type read out of an assembly: its identity, how the runtime is told to lay it out, and its
 * instance fields in metadata order -- which for a sequential struct is the layout order.
 *
 * @param name without the arity suffix: `List`, not ``List`1``
 * @param genericParameters every parameter the metadata gives, including those a nested type
 *   inherits from its container -- `List<T>.Enumerator` has one, and it is the container's `T`
 */
data class MetadataType(
    val assemblyName: String,
    val namespaceName: String,
    val name: String,
    val containerNames: List<String>,
    val genericParameters: List<String>,
    val kind: MetadataTypeKind,
    val isPublic: Boolean,
    val baseTypeName: String,
    val layoutKind: DeclaredLayoutKind,
    val pack: Int,
    val classSize: Int,
    val fields: List<MetadataField>,

    /** Properties and methods; they take no room in an instance and never reach the layout. */
    val members: List<MetadataMember> = emptyList(),
) {
    val qualifiedName: String
        get() {
            val parts = ArrayList<String>()
            if (namespaceName.isNotEmpty()) {
                parts.add(namespaceName)
            }
            parts.addAll(containerNames)
            parts.add(name)
            return parts.joinToString(".")
        }

    companion object {
        const val UNSET = 0
    }
}
