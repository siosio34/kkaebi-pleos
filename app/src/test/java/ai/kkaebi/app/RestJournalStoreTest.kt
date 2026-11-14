package ai.kkaebi.app

import ai.kkaebi.app.rest.RestJournalStore
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RestJournalStoreTest {
    @Test fun independentLaneTransactionsRetainAllExistingAndNewRows() {
        var stored = "old-unresolved"
        val store = RestJournalStore({stored}, {stored = it})
        val pool = Executors.newFixedThreadPool(3); val start = CountDownLatch(1)
        try {
            val tasks = (0..2).map { lane -> pool.submit {
                start.await()
                repeat(100) { index -> store.update { previous -> Thread.yield(); "$previous,$lane:$index" } }
            } }
            start.countDown(); tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            val entries = store.snapshot().split(',')
            assertEquals(301, entries.size); assertEquals(301, entries.toSet().size)
            assertEquals("old-unresolved", entries.first())
        } finally { pool.shutdownNow() }
    }
    @Test fun failedTransactionDoesNotOverwritePreviousRecoveryEvidence() {
        var stored = "old-unresolved"
        val store = RestJournalStore({stored}, { error("durable write denied") })
        assertTrue(runCatching { store.update { "$it,new" } }.isFailure)
        assertEquals("old-unresolved", store.snapshot())
    }
}
