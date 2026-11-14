package ai.kkaebi.app.engine

/** 대답을 요구하는 질문 (연구: 듣기만 < 따라 말하기 < 질문에 답하기) */
data class Question(val kind: String, val q: String, val a: List<String>) {
    /** 정답이 정해진 질문이면 맞았는지, 아니면 뭐라도 말했으면 참 */
    fun accepts(text: String): Boolean = a.isEmpty() || a.any { text.replace(" ", "").contains(it) }
}

/** 질문 순서: 같은 종류가 연달아 나오지 않게, 졸음 단계가 높을수록 생각을 요구하는 암산·퀴즈 위주 */
class QuestionPicker(private val bank: List<Question>, seed: Long = System.nanoTime()) {
    private val rnd = java.util.Random(seed)
    private var lastKind = ""
    private val used = ArrayDeque<Question>()

    fun next(level: Level): Question {
        val prefer = when (level) { Level.L0 -> listOf("talk", "quiz", "word", "joke"); Level.L1 -> listOf("quiz", "word", "math"); else -> listOf("math", "quiz") }
        val pool = bank.filter { it.kind in prefer && it.kind != lastKind && it !in used }.ifEmpty { bank.filter { it !in used } }.ifEmpty { used.clear(); bank }
        val q = pool[rnd.nextInt(pool.size)]
        lastKind = q.kind; used.addLast(q); while (used.size > bank.size / 2) used.removeFirst()
        return q
    }

    companion object {
        /** 단계별 말 거는 간격 (demo=true면 시연용으로 짧게) */
        fun intervalMs(level: Level, demo: Boolean): Long = when (level) {
            Level.L0 -> if (demo) 25_000 else 180_000
            Level.L1 -> if (demo) 15_000 else 75_000
            Level.L2 -> if (demo) 10_000 else 40_000
            Level.L3 -> if (demo) 10_000 else 30_000
            Level.REST -> Long.MAX_VALUE
        }
        const val ANSWER_TIMEOUT_MS = 7_000L
        fun praise(correct: Boolean, latencyMs: Long): String = when {
            !correct -> "음, 아닌 것 같은데요. 괜찮아요, 다음 문제 갈게요."
            latencyMs < 2500 -> "빠르네요! 정신 또렷하네요."
            latencyMs < 5000 -> "맞아요. 조금 느려졌는데 괜찮죠?"
            else -> "맞긴 한데 대답이 많이 늦었어요. 졸린 거 아니에요?"
        }
    }
}
