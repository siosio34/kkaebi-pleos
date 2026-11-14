package ai.kkaebi.app.ui

import ai.kkaebi.app.MainViewModel
import ai.kkaebi.app.Tab
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun App(vm: MainViewModel) {
    KkaebiTheme {
        BackHandler(enabled = vm.tab != Tab.DRIVE) { vm.tab = Tab.DRIVE }
        Box(Modifier.fillMaxSize().background(Tone.bg).safeDrawingPadding()) {
            Row(Modifier.fillMaxSize()) {
                val colors = NavigationRailItemDefaults.colors(selectedIconColor = Tone.bg, selectedTextColor = Tone.fg, indicatorColor = Tone.primary, unselectedIconColor = Tone.fg2, unselectedTextColor = Tone.fg2)
                NavigationRail(containerColor = Tone.surface, contentColor = Tone.fg) {
                    NavigationRailItem(vm.tab == Tab.DRIVE, { vm.tab = Tab.DRIVE }, { Icon(Icons.Rounded.DirectionsCar, null) }, label = { Text("주행") }, colors = colors)
                    NavigationRailItem(vm.tab == Tab.REPORT, { vm.tab = Tab.REPORT }, { Icon(Icons.Rounded.Assessment, null) }, label = { Text("리포트") }, colors = colors)
                    NavigationRailItem(vm.tab == Tab.SETTINGS, { vm.tab = Tab.SETTINGS }, { Icon(Icons.Rounded.Tune, null) }, label = { Text("설정") }, colors = colors)
                }
                Box(Modifier.fillMaxSize()) {
                    when (vm.tab) { Tab.DRIVE -> DriveScreen(vm); Tab.REPORT -> ReportScreen(vm); Tab.SETTINGS -> SettingsScreen(vm) }
                }
            }
        }
    }
}
