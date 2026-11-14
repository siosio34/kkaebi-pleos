package ai.kkaebi.app

import ai.kkaebi.app.engine.Action
import ai.kkaebi.app.engine.Escalation
import ai.kkaebi.app.engine.Level
import ai.kkaebi.app.engine.Question
import ai.kkaebi.app.engine.QuestionPicker
import ai.kkaebi.app.engine.RestArea
import ai.kkaebi.app.engine.RestAreas
import ai.kkaebi.app.engine.StimulusSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    @Test fun `기본 설정에서는 창문·와이퍼를 쓰지 않는다`() {
        val l2 = Escalation.plan(Level.L2, StimulusSettings())
        assertTrue(l2.any { it is Action.Climate }); assertTrue(l2.any { it is Action.SeatVent }); assertFalse(l2.any { it is Action.Window })
        val l3 = Escalation.plan(Level.L3, StimulusSettings())
        assertTrue(l3.first() is Action.WarnTone); assertTrue(l3.last() is Action.GuideToRestArea); assertFalse(l3.any { it is Action.WiperOnce })
    }
    @Test fun `창문·와이퍼를 켜도 비 오면 안 쓴다`() {
        val on = StimulusSettings(window = true, wiper = true)
        assertTrue(Escalation.plan(Level.L2, on).any { it is Action.Window })
        assertTrue(Escalation.plan(Level.L3, on).any { it is Action.WiperOnce })
        assertFalse(Escalation.plan(Level.L2, on.copy(rainy = true)).any { it is Action.Window })
        assertFalse(Escalation.plan(Level.L3, on.copy(rainy = true)).any { it is Action.WiperOnce })
    }
    @Test fun `L0와 휴식에는 깨우기가 없다`() { assertTrue(Escalation.plan(Level.L0, StimulusSettings()).isEmpty()); assertTrue(Escalation.plan(Level.REST, StimulusSettings()).isEmpty()) }

    @Test fun `질문은 같은 종류가 연달아 나오지 않고 단계가 높으면 암산 위주`() {
        val bank = listOf(Question("talk", "a", listOf()), Question("quiz", "b", listOf("x")), Question("math", "c", listOf("1")), Question("math", "d", listOf("2")), Question("word", "e", listOf("e")))
        val p = QuestionPicker(bank, seed = 1)
        val kinds = (1..6).map { p.next(Level.L0).kind }
        kinds.zipWithNext().forEach { (a, b) -> assertTrue("$a,$b", a != b || bank.count { it.kind == a } > 1) }
        val hi = (1..4).map { p.next(Level.L3).kind }
        assertTrue(hi.all { it == "math" || it == "quiz" })
    }
    @Test fun `정답 판정 - 정해진 답이 없으면 뭐라도 말하면 통과`() {
        assertTrue(Question("math", "q", listOf("43", "사십삼")).accepts("사십 삼이요"))
        assertFalse(Question("math", "q", listOf("43")).accepts("몰라요"))
        assertTrue(Question("talk", "q", listOf()).accepts("음 글쎄"))
    }
    @Test fun `간격은 단계가 높을수록 짧다`() {
        assertTrue(QuestionPicker.intervalMs(Level.L0, false) > QuestionPicker.intervalMs(Level.L1, false))
        assertTrue(QuestionPicker.intervalMs(Level.L1, false) > QuestionPicker.intervalMs(Level.L2, false))
    }

    private val areas = listOf(
        RestArea("A(북쪽 30km)", 37.07, 127.20, "경부", 10, true), RestArea("B(남쪽 12km)", 36.69, 127.20, "경부", 10, false), RestArea("C(남쪽 3km)", 36.77, 127.20, "경부", 10, true),
    )
    @Test fun `진행 방향 앞쪽에서 가장 가까운 쉼터를 고른다`() {
        val south = RestAreas.nearest(areas, 36.80, 127.20, headingDeg = 180.0)!!
        assertEquals("C(남쪽 3km)", south.first.name); assertTrue(south.second in 2500.0..4000.0)
        val north = RestAreas.nearest(areas, 36.80, 127.20, headingDeg = 0.0)!!
        assertEquals("A(북쪽 30km)", north.first.name)
        assertNotNull(RestAreas.nearest(areas, 36.80, 127.20, null))
    }
}
