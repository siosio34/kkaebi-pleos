@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ai.kkaebi.app.ui

import ai.kkaebi.app.MainViewModel
import ai.kkaebi.app.engine.Gear
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val f by vm.frame.collectAsState()
    val st = vm.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineMedium, color = Tone.fg)
        SectionHeader("깨우기 자극"); Hairline()
        Text("기본은 공조·조명·음성만 써요. 창문과 와이퍼는 켜야 쓰고, 비가 오면 쓰지 않아요. 모든 자극은 3초 안에 끝나고 \"그만\"이라고 말하면 멈춰요.", style = MaterialTheme.typography.bodySmall, color = Tone.fg2, modifier = Modifier.padding(vertical = 8.dp))
        SettingSwitch("시트 통풍", st.seatVent) { v -> vm.updateSettings { it.copy(seatVent = v) } }
        SettingSwitch("운전석 창문 10% 열기", st.window) { v -> vm.updateSettings { it.copy(window = v) } }
        SettingSwitch("와이퍼 1회", st.wiper) { v -> vm.updateSettings { it.copy(wiper = v) } }
        SettingSwitch("지금 비 옴 (창문·와이퍼 생략)", st.rainy) { v -> vm.updateSettings { it.copy(rainy = v) } }
        SectionHeader("차량 연결"); Hairline()
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("연결된 차", style = MaterialTheme.typography.bodyLarge, color = Tone.fg, modifier = Modifier.weight(1f))
            StatusDot(if (vm.isDemo) Tone.caution else Tone.ok, vm.carName)
        }
        Hairline()
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("마이크(STT)", style = MaterialTheme.typography.bodyLarge, color = Tone.fg, modifier = Modifier.weight(1f))
            StatusDot(if (vm.micAvailable) Tone.ok else Tone.caution, if (vm.micAvailable) "사용 가능" else "없음 · 버튼으로 대답")
        }
        Hairline()
        if (vm.isDemo) {
            SectionHeader("데모 조작"); Hairline()
            Text("Pleos 차량 서비스가 없어 야간 고속도로를 흉내 내는 중이에요. '졸음 주입'을 켜면 조향이 가만히 있다가 크게 되잡는 패턴과 속도 출렁임이 커져요.", style = MaterialTheme.typography.bodySmall, color = Tone.fg2, modifier = Modifier.padding(vertical = 8.dp))
            SettingSwitch("졸음 주입 (조향 흔들림·속도 출렁임)", vm.demo()?.drowsy == true) { vm.demoDrowsy(it) }
            SettingSwitch("차량 주의 경고등(DAW) 켜기", vm.demo()?.dawWarning == true) { vm.demoDaw(it) }
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("기어", style = MaterialTheme.typography.bodyLarge, color = Tone.fg, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    listOf(Gear.PARK to "P", Gear.DRIVE to "D").forEachIndexed { i, (g, l) ->
                        SegmentedButton(selected = f.gear == g, onClick = { vm.demoGear(g) }, shape = SegmentedButtonDefaults.itemShape(i, 2),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = Tone.primaryContainer, activeContentColor = Tone.fg, inactiveContainerColor = Tone.container, inactiveContentColor = Tone.fg2, activeBorderColor = Tone.line, inactiveBorderColor = Tone.line)) { Text(l) }
                    }
                }
            }
            Hairline()
        }
        Spacer(Modifier.height(24.dp))
        Text("깨비 0.1 · Pleos Vehicle 2.3.2 · NaviHelper 3.0.5 · TTS/STT 3.3.1 · 서체 Pretendard(OFL)", style = MaterialTheme.typography.labelMedium, color = Tone.fg2)
    }
}

@Composable
private fun SettingSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Tone.fg, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
    Hairline()
}
