package ai.kkaebi.app.rest

/** Exact SDK enum names, not a UI-invented global area. Persisted with every write. */
data class RestControlArea(val kind: String, val names: List<String>) {
    val id: String get() = "$kind:${names.sorted().joinToString("+")}"
    val scope: String get() = when {
        kind == "window" -> "window"
        names == listOf("ROW_1_LEFT") -> "driver"
        else -> "cabin"
    }
    fun authorized(key: String, cabinConsent: Boolean): Boolean = when (key) {
        "seatRecline", "seatVentilation" -> kind == "seat" && scope == "driver"
        "blind" -> kind == "window" && names.size == 1
        "temperature", "fan", "brightness", "hvacPower" -> kind == "seat" &&
            "ROW_1_LEFT" in names && (scope == "driver" || cabinConsent)
        else -> false
    }
}
