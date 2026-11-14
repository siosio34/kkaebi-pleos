package ai.kkaebi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Tone.line)

@Composable
fun SectionHeader(text: String, trailing: String? = null, top: Int = 28) {
    Row(Modifier.fillMaxWidth().padding(top = top.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Tone.fg)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelLarge, color = Tone.fg2)
    }
}

@Composable
fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** 시그니처 무브: 졸음 점수 띠. 0~100 세그먼트 25개, 단계 눈금 40/55/70, 70 이상은 레드존 */
@Composable
fun ScoreBand(score: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(25) { i ->
                val from = i * 4
                val on = score > from
                val red = from >= 68
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(if (on) color else if (red) Tone.danger.copy(alpha = .22f) else Tone.container))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf(0 to "0", 40 to "주의 40", 55 to "깨움 55", 70 to "쉼터 70").forEach { (v, l) ->
                Box(Modifier.weight(if (v == 0) 40f else if (v == 40) 15f else if (v == 55) 15f else 30f)) { Text(l, style = MaterialTheme.typography.labelSmall, color = Tone.fg2) }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(onClick, modifier.height(56.dp), enabled = enabled, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(containerColor = if (danger) Tone.danger else Tone.primary, contentColor = Tone.bg, disabledContainerColor = Tone.container, disabledContentColor = Tone.fg2)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
@Composable
fun TonalButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.height(56.dp), enabled = enabled, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Tone.container, contentColor = Tone.fg, disabledContainerColor = Tone.surface, disabledContentColor = Tone.fg2)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = Tone.fg) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tone.fg2, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor)
    }
    Hairline()
}
