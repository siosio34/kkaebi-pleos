package ai.kkaebi.app.engine

/** 트립 리포트용 기록 */
class TripLog {
    data class Point(val atMs: Long, val score: Int, val level: Level)
    data class Event(val atMs: Long, val text: String)
    val points = mutableListOf<Point>()
    val events = mutableListOf<Event>()
    var prompts = 0; var answered = 0; var missed = 0; var escalations = 0; var rests = 0
    var startedAt = 0L

    fun start(at: Long) { points.clear(); events.clear(); prompts = 0; answered = 0; missed = 0; escalations = 0; rests = 0; startedAt = at }
    fun sample(at: Long, score: Int, level: Level) { if (points.isEmpty() || at - points.last().atMs >= 2000) points += Point(at, score, level) }
    fun event(at: Long, text: String) { events += Event(at, text) }
    val peak: Int get() = points.maxOfOrNull { it.score } ?: 0
    val minutes: Int get() = if (points.isEmpty()) 0 else ((points.last().atMs - startedAt) / 60_000).toInt()
}
