package ai.kkaebi.app.ui

import ai.kkaebi.app.engine.Level
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun levelColor(l: Level): Color = when (l) { Level.L0 -> Tone.ok; Level.L1 -> Tone.caution; Level.L2 -> Tone.caution; Level.L3 -> Tone.danger; Level.REST -> Tone.primary }

/**
 * 깨비 얼굴: 원 하나, 눈 둘, 입 하나. 상태에 따라 눈꺼풀(0=감음 ~ 1=크게 뜸)과 입만 바뀐다. 300ms 페이드 하나뿐.
 * 괜찮음=둥근 눈+미소, 주의=반쯤 감긴 눈+직선 입, 위험=크게 뜬 눈+벌린 입, 휴식=감은 눈+작은 미소
 */
@Composable
fun Face(level: Level, size: Dp = 200.dp, modifier: Modifier = Modifier) {
    val open by animateFloatAsState(when (level) { Level.L0 -> 0.75f; Level.L1 -> 0.45f; Level.L2 -> 0.35f; Level.L3 -> 1f; Level.REST -> 0f }, tween(300), label = "eyes")
    val smile by animateFloatAsState(when (level) { Level.L0 -> 1f; Level.L1 -> 0.2f; Level.L2 -> 0f; Level.L3 -> -1f; Level.REST -> 0.5f }, tween(300), label = "mouth")
    val ring = levelColor(level)
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(r, r)
        val stroke = r * 0.045f
        drawCircle(ring, r - stroke / 2, c, style = Stroke(stroke))             // 상태 띠
        drawCircle(Tone.container, r * 0.84f, c)                                 // 얼굴
        val eyeY = c.y - r * 0.12f; val eyeDx = r * 0.3f; val eyeR = r * 0.11f
        for (dx in listOf(-eyeDx, eyeDx)) {
            val ex = c.x + dx
            if (open <= 0.08f) {
                drawArc(Tone.fg, 20f, 140f, false, Offset(ex - eyeR, eyeY - eyeR * 0.6f), Size(eyeR * 2, eyeR * 1.2f), style = Stroke(stroke, cap = StrokeCap.Round))
            } else {
                val h = eyeR * 2 * open
                drawOval(Tone.fg, Offset(ex - eyeR, eyeY - h / 2), Size(eyeR * 2, h))
                if (open > 0.9f) drawCircle(Tone.container, eyeR * 0.3f, Offset(ex, eyeY))  // 크게 뜬 눈의 동공 하이라이트
            }
        }
        val mouthY = c.y + r * 0.3f; val mw = r * 0.5f
        when {
            smile > 0.1f -> drawArc(Tone.fg, 20f, 140f, false, Offset(c.x - mw / 2, mouthY - mw * 0.5f * smile), Size(mw, mw * smile), style = Stroke(stroke, cap = StrokeCap.Round))
            smile < -0.5f -> drawOval(Tone.fg, Offset(c.x - mw * 0.22f, mouthY - mw * 0.22f), Size(mw * 0.44f, mw * 0.5f))
            else -> drawLine(Tone.fg, Offset(c.x - mw / 2, mouthY), Offset(c.x + mw / 2, mouthY), stroke, StrokeCap.Round)
        }
    }
}
