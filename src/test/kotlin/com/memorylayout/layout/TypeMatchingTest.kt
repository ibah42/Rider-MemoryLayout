package com.memorylayout.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a written name is matched against a declaration, arity included. */
class TypeMatchingTest {

    private val qualifiedName = "ZLogger.Entries.MessageLogState"

    @Test
    fun aBareNameDoesNotMatchAGenericDeclarationExactly() {
        assertFalse(TypeMatching.matches("MessageLogState", qualifiedName, 1, "MessageLogState"))
    }

    @Test
    fun aBareNameOpensTheGenericDeclarationOfThatName() {
        // The caret on `MessageLogState` in `public struct MessageLogState<TPayload>`.
        assertTrue(TypeMatching.matchesOpenDeclaration("MessageLogState", qualifiedName, 1, "MessageLogState"))
        assertTrue(TypeMatching.matchesOpenDeclaration("MessageLogState", qualifiedName, 1, "ZLogger.Entries.MessageLogState"))
    }

    @Test
    fun theOpenMatchIsOnlyForBareNamesAndGenericDeclarations() {
        assertFalse(TypeMatching.matchesOpenDeclaration("MessageLogState", qualifiedName, 1, "MessageLogState<int>"))
        assertFalse(TypeMatching.matchesOpenDeclaration("MessageLogState", qualifiedName, 0, "MessageLogState"))
        assertFalse(TypeMatching.matchesOpenDeclaration("MessageLogState", qualifiedName, 1, "Other.MessageLogState"))
    }
}
