package com.calgrid.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calgrid.container
import com.calgrid.data.tasks.TaskFields
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Edits [taskId], or creates a new task when it is null (in [initialListId] or the first list). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(taskId: String?, initialListId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.tasksRepository
    val scope = rememberCoroutineScope()
    val lists by repo.observeLists().collectAsStateWithLifecycle(initialValue = emptyList())

    var loaded by remember { mutableStateOf(taskId == null) }
    var missing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var completed by remember { mutableStateOf(false) }
    var listId by remember { mutableStateOf(initialListId) }
    var pickingDate by remember { mutableStateOf(false) }

    LaunchedEffect(taskId) {
        if (taskId == null) return@LaunchedEffect
        val task = repo.task(taskId)
        if (task == null) {
            missing = true
        } else {
            title = task.title
            notes = task.notes.orEmpty()
            due = task.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            completed = task.completed
            listId = task.listId
        }
        loaded = true
    }
    if (listId == null && lists.isNotEmpty()) listId = lists.first().id

    fun fields() = TaskFields(title.trim(), notes.trim().ifEmpty { null }, due?.toString(), completed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (taskId == null) "Új feladat" else "Feladat") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vissza") }
                },
                actions = {
                    if (taskId != null && !missing) {
                        IconButton(onClick = { scope.launch { repo.deleteTask(taskId); onDone() } }) {
                            Icon(Icons.Default.Delete, "Törlés")
                        }
                    }
                    IconButton(
                        enabled = loaded && !missing && title.isNotBlank() && listId != null,
                        onClick = {
                            scope.launch {
                                if (taskId == null) repo.createTask(listId!!, fields()) else repo.updateTask(taskId, fields())
                                onDone()
                            }
                        },
                    ) { Icon(Icons.Default.Check, "Mentés") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                missing -> Text("Ez a feladat már nem létezik (lehet, hogy közben szinkronizálódott vagy törölték).")
                lists.isEmpty() -> Text("Nincs feladatlista. Csatlakoztasd a Google Tasks fiókot a kezdőképernyőn.")
                loaded -> {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Cím") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes, onValueChange = { notes = it },
                        label = { Text("Jegyzet") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    if (taskId == null) {
                        ListPicker(lists.map { it.id to it.title }, listId) { listId = it }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingDate = true }) {
                            Text(due?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Határidő")
                        }
                        if (due != null) TextButton(onClick = { due = null }) { Text("Határidő törlése") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = completed, onCheckedChange = { completed = it })
                        Text("Kész")
                    }
                }
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Mégse") } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPicker(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Lista") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
