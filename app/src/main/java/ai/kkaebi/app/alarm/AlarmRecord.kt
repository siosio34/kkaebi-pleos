package ai.kkaebi.app.alarm

/** Persisted deadlines are monotonic and valid only for the originating boot. */
data class AlarmRecord(
    val state: String = "idle", val id: String = "", val requestId: String = "",
    val bootCount: Int = -1, val elapsedDeadline: Long = 0, val wallDeadline: Long = 0,
    val message: String = "알람을 설정해 주세요"
)
object AlarmPolicy {
    fun validRequest(minutes: Int, requestId: String) = minutes in setOf(10, 15, 20, 25, 30) && requestId.matches(Regex("[A-Za-z0-9_-]{1,64}"))
    fun active(record: AlarmRecord) = record.state == "scheduled" || record.state == "ringing"
    fun matches(record: AlarmRecord, id: String) = id.isNotEmpty() && id == record.id
    fun sameBoot(record: AlarmRecord, currentBoot: Int) = currentBoot >= 0 && record.bootCount == currentBoot
    fun remaining(record: AlarmRecord, elapsedNow: Long, currentBoot: Int): Long =
        if (sameBoot(record, currentBoot) && record.state == "scheduled") (record.elapsedDeadline - elapsedNow).coerceAtLeast(0) else 0
    fun canFire(record: AlarmRecord, id: String, elapsedNow: Long, currentBoot: Int) =
        matches(record, id) && record.state == "scheduled" && sameBoot(record, currentBoot) && elapsedNow >= record.elapsedDeadline
    fun canRestoreOnOpen(record: AlarmRecord, elapsedNow: Long, currentBoot: Int) =
        record.state == "scheduled" && sameBoot(record, currentBoot) && elapsedNow < record.elapsedDeadline
}
