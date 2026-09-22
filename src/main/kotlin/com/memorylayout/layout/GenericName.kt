package com.memorylayout.layout

/**
 * Reading a written generic name: `Box<Pair<int, float>>` into `Box` and one argument.
 *
 * Arity is part of the name in C# -- `Box` and `Box<T>` are two different types that may sit in
 * the same namespace -- so a name cannot be matched against a declaration without counting the
 * arguments first. And an argument list nests, so the commas that separate arguments are only the
 * ones outside every `<>`; splitting on every comma turns `Dictionary<int, float>` into three
 * arguments, two of them nonsense.
 */
object GenericName {

    /** The arguments as written, or empty when the name carries none. */
    fun argumentsOf(typeName: String): List<String> {
        val open = typeName.indexOf('<')
        if (open < 0) {
            return emptyList()
        }
        val close = typeName.lastIndexOf('>')
        if (close <= open) {
            return emptyList()
        }
        return splitTopLevel(typeName.substring(open + 1, close))
    }

    fun arityOf(typeName: String): Int {
        return argumentsOf(typeName).size
    }

    /** `Dictionary<int, List<string>>` has two arguments, not three. */
    fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        for (character in text) {
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
                        parts.add(current.toString().trim())
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
        parts.add(current.toString().trim())
        return parts.filter { part ->
            part.isNotEmpty()
        }
    }

    /**
     * The name with its type parameters replaced by what they were bound to.
     *
     * Whole identifiers only, so `T` in `T[]`, in `Box<T>` and in `Pair<T, U>` are all replaced
     * and `Transform` is left alone. A parameter with no binding is left as it was written, and
     * resolving it then fails, which is what puts a dash in the size column: the size of `T` is
     * not a number until somebody writes `Box<int>`.
     */
    fun substitute(typeName: String, arguments: Map<String, String>): String {
        if (arguments.isEmpty() || typeName.isEmpty()) {
            return typeName
        }
        val result = StringBuilder(typeName.length)
        var index = 0
        while (index < typeName.length) {
            val character = typeName[index]
            if (!character.isLetter() && character != '_') {
                result.append(character)
                index++
                continue
            }
            var end = index
            while (end < typeName.length && (typeName[end].isLetterOrDigit() || typeName[end] == '_')) {
                end++
            }
            val word = typeName.substring(index, end)
            result.append(arguments[word] ?: word)
            index = end
        }
        return result.toString()
    }

    /** Binds a declaration's parameters to the arguments a use wrote, as far as both go. */
    fun bind(parameters: List<String>, arguments: List<String>): Map<String, String> {
        if (parameters.isEmpty() || arguments.isEmpty()) {
            return emptyMap()
        }
        val bound = LinkedHashMap<String, String>()
        for (index in parameters.indices) {
            if (index >= arguments.size) {
                break
            }
            bound[parameters[index]] = arguments[index]
        }
        return bound
    }
}
