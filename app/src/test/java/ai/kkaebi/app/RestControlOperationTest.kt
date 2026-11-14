package ai.kkaebi.app

import ai.kkaebi.app.rest.RestControlOperation
import org.junit.Assert.*
import org.junit.Test

class RestControlOperationTest {
    @Test fun newOperationClearsPreviousResultsBeforeWorkStarts() {
        val old = RestControlOperation().begin("apply", "first").results("first", "{applied}").finish("first", true)
        val next = old.begin("restore", "second")
        assertEquals("second", next.id)
        assertEquals("restore", next.operation)
        assertEquals("running", next.phase)
        assertEquals("{}", next.resultsJson)
    }

    @Test fun staleResultsAndCompletionCannotOverwriteCurrentOperation() {
        val current = RestControlOperation().begin("restore", "new")
        assertEquals(current, current.results("old", "{old-result}"))
        assertEquals(current, current.finish("old", true))
    }

    @Test fun interruptedOperationIsTerminalAndKeepsOnlyItsOwnPartialResults() {
        val interrupted = RestControlOperation().begin("apply", "current").results("current", "{partial}").finish("current", false)
        assertEquals("failed", interrupted.phase)
        assertEquals("{partial}", interrupted.resultsJson)
        assertEquals(interrupted, interrupted.results("current", "{late-success}"))
        assertEquals(interrupted, interrupted.finish("current", true))
    }

    @Test fun emptySelectionAndEmptyJournalCompleteWithoutStayingRunning() {
        for (operation in listOf("apply", "restore")) {
            val running = RestControlOperation().begin(operation, operation)
            val completed = running.finish(operation, RestControlOperation.allConfirmed(operation, emptyList()))
            assertEquals("complete", completed.phase)
            assertEquals("{}", completed.resultsJson)
        }
    }

    @Test fun partialFailureAndPendingRestorationAreNotCompletion() {
        assertTrue(RestControlOperation.allConfirmed("apply", listOf("applied")))
        assertFalse(RestControlOperation.allConfirmed("apply", listOf("applied", "failed")))
        assertFalse(RestControlOperation.allConfirmed("restore", listOf("restored", "pendingRestore")))
        assertTrue(RestControlOperation.allConfirmed("restore", listOf("restored", "manualPreserved")))
    }

    @Test fun rejectedRequestHasFreshIdentityAndNoOldResult() {
        val previous = RestControlOperation().begin("apply", "before").results("before", "{old}").finish("before", true)
        val rejected = previous.begin("apply", "rejected").finish("rejected", false)
        assertEquals("rejected", rejected.id)
        assertEquals("failed", rejected.phase)
        assertEquals("{}", rejected.resultsJson)
    }
}
