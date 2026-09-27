package com.calgrid.ui.config

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.calgrid.container
import com.calgrid.data.calendar.CalendarInfo
import com.calgrid.data.tasks.TaskListEntity
import com.calgrid.settings.WidgetConfig
import com.calgrid.settings.WidgetLayout
import com.calgrid.sync.SyncScheduler
import com.calgrid.sync.WidgetUpdater
import com.calgrid.ui.theme.CalGridTheme
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/** Launched when a widget is placed (optional) and from the widget's ⚙ button. */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setContent {
            CalGridTheme {
                ConfigScreen(appWidgetId = appWidgetId, onSave = ::save)
            }
        }
    }

    private fun save(config: WidgetConfig) {
        lifecycleScope.launch {
            container.widgetConfigStore.save(appWidgetId, config)
            SyncScheduler.ensureScheduled(this@WidgetConfigActivity)
            WidgetUpdater.updateAll(this@WidgetConfigActivity)
            setResult(RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(appWidgetId: Int, onSave: (WidgetConfig) -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val container = activity.container
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<WidgetConfig?>(null) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var taskLists by remember { mutableStateOf<List<TaskListEntity>>(emptyList()) }
    var tasksConnected by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(container.calendarRepository.hasPermission()) }

    suspend fun reloadSources() {
        calendars = container.calendarRepository.getCalendars()
        taskLists = container.tasksRepository.lists()
        tasksConnected = container.appPrefs.tasksAccountNow().enabled
    }

    LaunchedEffect(Unit) {
        config = container.widgetConfigStore.get(appWidgetId)
        reloadSources()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        scope.launch { reloadSources() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget beállításai") },
                actions = {
                    IconButton(onClick = { config?.let(onSave) }, enabled = config != null) {
                        Icon(Icons.Default.Check, "Mentés")
                    }
                },
            )
        },
    ) { padding ->
        val current = config
        if (current != null) {
            fun update(block: WidgetConfig.() -> WidgetConfig) {
                config = current.block()
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Section("Elrendezés")
                ChipRow(
                    options = listOf(WidgetLayout.BOTH to "Mindkettő", WidgetLayout.MONTH to "Naptár", WidgetLayout.AGENDA to "Ütemezés"),
                    selected = current.layout,
                ) { update { copy(layout = it) } }
                Text(
                    "„Mindkettő” esetén széles widgetben egymás mellett, magasban egymás alatt jelenik meg a havi nézet és az ütemezés; kis méretben csak az ütemezés.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Section("Ütemezés hossza")
                ChipRow(
                    options = listOf(3 to "3 nap", 7 to "1 hét", 14 to "2 hét", 30 to "1 hónap"),
                    selected = current.agendaDays,
                ) { update { copy(agendaDays = it) } }

                Section("A hét első napja")
                ChipRow(
                    options = listOf(DayOfWeek.MONDAY.value to "Hétfő", DayOfWeek.SUNDAY.value to "Vasárnap"),
                    selected = current.firstDayIso,
                ) { update { copy(firstDayIso = it) } }

                Section("Háttér átlátszatlansága: ${current.backgroundOpacity}%")
                Slider(
                    value = current.backgroundOpacity.toFloat(),
                    onValueChange = { v -> update { copy(backgroundOpacity = (v / 10).toInt() * 10) } },
                    valueRange = 0f..100f,
                    steps = 9,
                )

                HorizontalDivider()
                Section("Naptárak")
                if (!hasPermission) {
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("Naptár-hozzáférés megadása")
                    }
                } else {
                    SwitchRow("Minden látható naptár", current.calendarIds == null) { all ->
                        update { copy(calendarIds = if (all) null else calendars.filter { it.visible }.map { it.id }.toSet()) }
                    }
                    if (current.calendarIds != null) {
                        calendars.groupBy { it.accountName }.forEach { (account, cals) ->
                            Text(account, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            cals.forEach { cal ->
                                val checked = cal.id in current.calendarIds
                                CheckRow(cal.name, checked, color = Color(cal.color)) {
                                    update {
                                        val ids = calendarIds.orEmpty()
                                        copy(calendarIds = if (checked) ids - cal.id else ids + cal.id)
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Section("Google Tasks")
                if (!tasksConnected) {
                    Text(
                        "A feladatokhoz előbb csatlakoztasd a Google-fiókodat a CalGrid appban.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    SwitchRow("Feladatok megjelenítése", current.showTasks) { update { copy(showTasks = it) } }
                    if (current.showTasks) {
                        SwitchRow("Határidő nélküli feladatok", current.showUndatedTasks) { update { copy(showUndatedTasks = it) } }
                        SwitchRow("Befejezett feladatok", current.showCompletedTasks) { update { copy(showCompletedTasks = it) } }
                        SwitchRow("Minden lista", current.taskListIds == null) { all ->
                            update { copy(taskListIds = if (all) null else taskLists.map { it.id }.toSet()) }
                        }
                        if (current.taskListIds != null) {
                            taskLists.forEach { list ->
                                val checked = list.id in current.taskListIds
                                CheckRow(list.title, checked) {
                                    update {
                                        val ids = taskListIds.orEmpty()
                                        copy(taskListIds = if (checked) ids - list.id else ids + list.id)
                                    }
                                }
                            }
                        }
                    }
                }

                Button(onClick = { onSave(current) }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Text("Mentés")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, color: Color? = null, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        if (color != null) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}
