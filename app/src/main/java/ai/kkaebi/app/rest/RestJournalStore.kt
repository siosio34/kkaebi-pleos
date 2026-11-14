package ai.kkaebi.app.rest

/** A complete read/modify/durable-write transaction; independent lanes cannot lose each other's rows. */
class RestJournalStore(private val readValue: () -> String, private val writeValue: (String) -> Unit) {
    @Synchronized fun snapshot(): String = readValue()
    @Synchronized fun update(change: (String) -> String) { writeValue(change(readValue())) }
}
