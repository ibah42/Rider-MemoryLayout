package com.memorylayout.index

/**
 * What ties a closure class read from a compiled assembly back to the lambda on screen.
 *
 * The class's fields are named after the captured variables but carry no position -- an assembly
 * knows nothing of the source -- so each row is pointed at the declaration of its variable here,
 * which is where a click on it goes.
 *
 * @param lambdaOffset where the lambda itself starts in [fileUrl]: what the summary row points at
 * @param declarationOffsets field name to the offset of the captured variable's declaration
 */
data class ClosureSource(
    val fileUrl: String,
    val lambdaOffset: Int,
    val title: String,
    val declarationOffsets: Map<String, Int>,
    val notes: List<String>,
)
