package ai.kkaebi.app.ui

import ai.kkaebi.app.MainViewModel
import ai.kkaebi.app.engine.Level
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun ReportScreen(vm: MainViewModel) {
    val log = vm.log
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("트립 리포트", style = MaterialTheme.typography.headlineMedium, color = Tone.fg)
        if (log == null || log.points.isEmpty()) { Text("졸음 모드를 켜고 달리면 점수 기록이 여기 쌓여요.", style = MaterialTheme.typography.bodyMedium, color = Tone.fg2, modifier = Modifier.padding(top = 8.dp)); return }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Stat("운전", "${log.minutes}분", Modifier.weight(1f)); Stat("최고 점수", "${log.peak}", Modifier.weight(1f)); Stat("질문 / 대답", "${log.prompts} / ${log.answered}", Modifier.weight(1f))
            Stat("무응답", "${log.missed}", Modifier.weight(1f)); Stat("깨우기", "${log.escalations}", Modifier.weight(1f)); Stat("휴식", "${log.rests}", Modifier.weight(1f))
        }
        SectionHeader("졸음 점수 흐름"); Hairline()
        val pts = log.points
        Canvas(Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp)) {
            val w = size.width; val h = size.height
            // 단계 눈금선 40/55/70 (레드존은 danger)
            listOf(40 to Tone.caution, 55 to Tone.caution, 70 to Tone.danger).forEach { (v, c) -> val y = h - h * v / 100f; drawLine(c.copy(alpha = .5f), Offset(0f, y), Offset(w, y), 1.5f) }
            val t0 = pts.first().atMs; val t1 = maxOf(pts.last().atMs, t0 + 1)
            val path = Path()
            pts.forEachIndexed { i, p -> val x = w * (p.atMs - t0) / (t1 - t0).toFloat(); val y = h - h * p.score / 100f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, Tone.primary, style = Stroke(3f, cap = StrokeCap.Round))
            pts.filter { it.level == Level.L3 }.forEach { p -> val x = w * (p.atMs - t0) / (t1 - t0).toFloat(); drawLine(Tone.danger, Offset(x, h), Offset(x, h - 10f), 2f) }
        }
        SectionHeader("기록", "${log.events.size}"); Hairline()
        log.events.forEach { e -> KeyValue(e.text, java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(e.atMs))) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.displaySmall, color = Tone.fg)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tone.fg2)
    }
}
