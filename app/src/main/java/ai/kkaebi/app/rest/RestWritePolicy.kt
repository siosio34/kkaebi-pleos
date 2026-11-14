package ai.kkaebi.app.rest

/** A missing legacy dispatch flag is unknown: it must retain the recovery journal. */
object RestWritePolicy {
    fun knownUndispatched(dispatched: Boolean?): Boolean = dispatched == false

    /** Sleep starts with the seat; power-on precedes climate dependencies and power-off stays last. */
    fun orderedKeys(targets: Map<String, Double?>, restoring: Boolean = false): List<String> = targets.keys.sortedBy { key ->
        if(restoring) when(key) {
            "brightness" -> 0
            "hvacPower" -> if(targets[key] == 1.0) 1 else 99
            "temperature" -> 2
            "fan" -> 3
            "seatVentilation" -> 4
            "blind" -> 5
            "seatRecline" -> 6
            "window" -> 0
            else -> 7
        } else when(key) {
            "seatRecline" -> 0
            "blind" -> 1
            "window" -> 2
            "hvacPower" -> if(targets[key] == 1.0) 2 else 99
            "temperature" -> 3
            "fan" -> 4
            "seatVentilation" -> 5
            "brightness" -> 6
            else -> 7
        }
    }

    /** Expiry invalidates automatic authority, never discards recovery evidence. */
    fun autoRestoreOwned(owner: String?, expectedOwner: String, createdAt: Long, now: Long): Boolean =
        expectedOwner.isNotBlank() && owner == expectedOwner && createdAt > 0 && now - createdAt in 0..7_200_000
}

/** A restore may be interrupted after the SDK accepts it. Prior apply confirmation then expires. */
data class RestJournalConfirmation(val appliedConfirmed: Boolean, val restoreDispatched: Boolean = false) {
    fun beginRestoreDispatch() = copy(appliedConfirmed = false, restoreDispatched = true)
}
