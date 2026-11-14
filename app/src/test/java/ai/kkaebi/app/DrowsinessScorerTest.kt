package ai.kkaebi.app

import ai.kkaebi.app.engine.DrowsinessScorer
import ai.kkaebi.app.engine.Frame
import ai.kkaebi.app.engine.Gear
import ai.kkaebi.app.engine.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DrowsinessScorerTest {
    private var clock = 1_700_000_000_000L   // 고정 시각 (오후, 시간대 가중 없음)
    private fun scorer() = DrowsinessScorer(baselineMs = 10_000, now = { clock })
    private fun drive(s: DrowsinessScorer, seconds: Int, wobble: Float, seed: Int = 1, speedNoise: Float = 1f) {
        val r = Random(seed)
        repeat(seconds * 4) {
            clock += 250
            val steer = (r.nextFloat() - 0.5f) * wobble
            s.onFrame(Frame(clock, 100f + (r.nextFloat() - 0.5f) * speedNoise, steer, Gear.DRIVE, false))
        }
    }

    @Test fun `평소대로 달리면 점수가 낮다`() {
        val s = scorer(); s.start(clock)
        drive(s, 60, wobble = 2f)
        assertTrue(s.baselineReady); assertTrue("score=${s.score}", s.score < 25); assertEquals(Level.L0, s.level)
    }

    @Test fun `조향 흔들림이 기준선보다 커지면 점수가 오른다`() {
        val s = scorer(); s.start(clock)
        drive(s, 30, wobble = 2f)
        val calm = s.score
        drive(s, 40, wobble = 14f, seed = 7, speedNoise = 20f)
        assertTrue("calm=$calm now=${s.score}", s.score > calm + 20)
    }

    @Test fun `무응답 두 번이면 L2 이상`() {
        val s = scorer(); s.start(clock)
        drive(s, 30, wobble = 2f)
        s.onMissed(clock); s.onMissed(clock)
        assertTrue("score=${s.score}", s.score >= 28)
        drive(s, 20, wobble = 12f, seed = 3, speedNoise = 20f)
        assertTrue("level=${s.level} score=${s.score}", s.level.ordinal >= Level.L2.ordinal)
    }

    @Test fun `차량 주의 경고등이 켜지면 바로 위험 단계`() {
        val s = scorer(); s.start(clock)
        drive(s, 20, wobble = 2f)
        clock += 250
        s.onFrame(Frame(clock, 100f, 0f, Gear.DRIVE, false, driverAttentionWarning = true))
        assertTrue(s.score >= 70); assertEquals(Level.L3, s.level)
    }

    @Test fun `히스테리시스 - 내려갈 때는 더 낮아야 단계가 떨어진다`() {
        assertEquals(Level.L2, DrowsinessScorer.nextLevel(Level.L0, 56))
        assertEquals(Level.L2, DrowsinessScorer.nextLevel(Level.L2, 50))
        assertEquals(Level.L1, DrowsinessScorer.nextLevel(Level.L2, 44))
        assertEquals(Level.L3, DrowsinessScorer.nextLevel(Level.L1, 71))
        assertEquals(Level.L3, DrowsinessScorer.nextLevel(Level.L3, 56))
        assertEquals(Level.L2, DrowsinessScorer.nextLevel(Level.L3, 54))
    }

    @Test fun `쉬고 나면 점수가 초기화된다`() {
        val s = scorer(); s.start(clock)
        drive(s, 30, wobble = 2f); s.onMissed(clock); s.onMissed(clock); s.onMissed(clock)
        assertTrue("score=${s.score}", s.score >= 30)
        s.onRested(clock)
        assertEquals(0, s.score); assertEquals(Level.L0, s.level); assertTrue(s.baselineReady)
    }
}
