package ai.kkaebi.app.gleo

/** Validate before narrowing to Int; JSONObject.optInt can wrap oversized Long values. */
object NativeInputPolicy {
    fun integer(value: Any?, minimum: Int, maximum: Int): Int? {
        val number=(value as? Number)?.toDouble() ?: return null
        return if(number.isFinite() && number % 1.0 == 0.0 && number >= minimum && number <= maximum) number.toInt() else null
    }
    fun temperature(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 18.0..26.0 }
}
