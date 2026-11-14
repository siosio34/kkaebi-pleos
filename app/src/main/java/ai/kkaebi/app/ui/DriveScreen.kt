package ai.kkaebi.app.ui

import ai.kkaebi.app.MainViewModel
import ai.kkaebi.app.Phase
import ai.kkaebi.app.engine.Gear
import ai.kkaebi.app.engine.Level
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 주행 화면: 왼쪽 얼굴 + 점수 띠(글랜서블), 오른쪽 상태·문답·행동. 주행 중 움직이는 것은 눈꺼풀 전환뿐 */
@Composable
fun DriveScreen(vm: MainViewModel) {
    val s by vm.session.collectAsState()
    val f by vm.frame.collectAsState()
    val running = vm.running
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("깨비", style = MaterialTheme.typography.headlineMedium, color = Tone.fg)
                StatusDot(if (vm.isDemo) Tone.caution else Tone.ok, vm.carName)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Face(if (running) s.level else Level.REST, size = 168.dp)
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(if (running) "${s.score}" else "—", style = MaterialTheme.typography.displayLarge, color = if (running) levelColor(s.level) else Tone.fg2)
                    Text(if (running) s.level.label else "졸음 모드 꺼짐", style = MaterialTheme.typography.titleLarge, color = Tone.fg)
                    Text(if (!running) "켜면 평소 운전을 본 뒤 가끔 말을 걸어요" else if (!s.baselineReady) "평소 운전을 보는 중" else "운전 ${s.driveMinutes}분 · 무응답 ${s.missed}", style = MaterialTheme.typography.bodyMedium, color = Tone.fg2)
                }
            }
            Spacer(Modifier.height(20.dp))
            ScoreBand(if (running) s.score else 0, if (running) levelColor(s.level) else Tone.fg2)
            Spacer(Modifier.weight(1f))
            when (s.phase) {
                Phase.RESTING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TonalButton("그만 쉬고 출발", Modifier.weight(1f)) { vm.skipRest() } }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(if (running) "졸음 모드 끄기" else "졸음 모드 켜기", Modifier.weight(1.2f), enabled = vm.ready) { vm.toggle() }
                    if (running) TonalButton("휴식 시작", Modifier.weight(1f), enabled = f.gear == Gear.PARK) { vm.startRest() }
                }
            }
        }
        VerticalDivider(color = Tone.line)
        Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp)) {
            if (s.phase == Phase.RESTING || s.phase == Phase.WAKEUP) RestPane(s.restRemainingSec, s.restSteps, s.phase)
            else {
                Text(s.statusLine, style = MaterialTheme.typography.titleLarge, color = Tone.fg)
                SectionHeader("차 신호", top = 20); Hairline()
                KeyValue("속도", f.speedKmh?.let { "${it.toInt()} km/h" } ?: "—")
                KeyValue("조향", f.steeringDeg?.let { "%.1f°".format(it) } ?: "—")
                KeyValue("기어", when (f.gear) { Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.UNKNOWN -> "—" }, if (f.gear == Gear.PARK) Tone.ok else Tone.fg)
                KeyValue("동승석", if (f.passengerSeated) "탑승" else "비어 있음")
                SectionHeader("마지막 문답", top = 24); Hairline()
                Text(s.lastQuestion ?: "아직 말 걸지 않았어요", style = MaterialTheme.typography.bodyLarge, color = Tone.fg, modifier = Modifier.padding(top = 10.dp))
                Text(buildString { append(s.lastAnswer ?: "—"); s.lastLatencyMs?.let { append("  · ${it}ms") } }, style = MaterialTheme.typography.bodyMedium, color = Tone.fg2, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                if (s.phase == Phase.LISTENING && !vm.micAvailable) {
                    Text("마이크가 없는 환경이라 버튼으로 대답을 흉내 내요.", style = MaterialTheme.typography.labelMedium, color = Tone.caution)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("대답했어요", Modifier.weight(1f)) { vm.answer("응 알아요", 1800) }
                        TonalButton("늦게 대답", Modifier.weight(1f)) { vm.answer("음… 뭐더라", 5200) }
                        TonalButton("무응답", Modifier.weight(1f)) { vm.answer(null, -1) }
                    }
                }
                if (s.restArea != null) {
                    SectionHeader("쉼터 안내", top = 24); Hairline()
                    KeyValue(s.restArea!!.name, s.restAreaM?.let { "%.1f km".format(it / 1000) } ?: "", Tone.primary)
                    KeyValue("주차면 · 화장실", "${s.restArea!!.parking}면 · ${if (s.restArea!!.toilet) "있음" else "없음"}")
                }
                if (s.lastActions.isNotEmpty()) {
                    SectionHeader("깨우기", top = 24); Hairline()
                    s.lastActions.forEach { KeyValue(it, "완료", Tone.ok) }
                }
            }
        }
    }
}

@Composable
private fun RestPane(remainingSec: Int, steps: List<String>, phase: Phase) {
    Text(if (phase == Phase.WAKEUP) "일어날 시간이에요" else "쉬는 중", style = MaterialTheme.typography.titleLarge, color = Tone.fg)
    Spacer(Modifier.height(8.dp))
    Text("%d:%02d".format(remainingSec / 60, remainingSec % 60), style = MaterialTheme.typography.displayLarge, color = Tone.primary)
    Text(if (phase == Phase.WAKEUP) "조명을 서서히 밝히고 등받이를 세우고 환기해요" else "창문·문 잠금, 블라인드, 공조 23°C, 조명 끔, 등받이, 저음 소음", style = MaterialTheme.typography.bodyMedium, color = Tone.fg2)
    SectionHeader(if (phase == Phase.WAKEUP) "복귀 단계" else "휴식 설정", top = 24); Hairline()
    steps.forEach { KeyValue(it, "완료", Tone.ok) }
}
