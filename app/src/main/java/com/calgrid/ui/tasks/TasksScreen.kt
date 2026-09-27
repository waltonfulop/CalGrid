package com.calgrid.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calgrid.container
import com.calgrid.data.tasks.TaskEntity
import com.calgrid.data.tasks.orderWithSubtasks
import com.calgrid.sync.SyncScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    listId: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onNewTask: () -> Unit,
) {
    val context = LocalContext.current
    val repo = context.container.tasksRepository
    val lists by repo.observeLists().collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by repo.observeTasks(listId).collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var showCompleted by remember { mutableStateOf(false) }

    val open = orderWithSubtasks(tasks.filter { !it.completed })
    val done = tasks.filter { it.completed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lists.firstOrNull { it.id == listId }?.title.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Vissza") }
                },
                actions = {
                    IconButton(onClick = { SyncScheduler.requestTasksSync(context) }) {
                        Icon(Icons.Default.Refresh, "Szinkron")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewTask) { Icon(Icons.Default.Add, "Új feladat") }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(open, key = { it.first.id }) { (task, depth) ->
                TaskListRow(
                    task = task,
                    depth = depth,
                    onToggle = { scope.launch { repo.setCompleted(task.id, !task.completed) } },
                    onClick = { onOpenTask(task.id) },
                )
            }
            if (done.isNotEmpty()) {
                item(key = "done-header") {
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Befejezett (${done.size})") },
                        trailingContent = { Text(if (showCompleted) "▲" else "▼") },
                        modifier = Modifier.clickable { showCompleted = !showCompleted },
                    )
                }
                if (showCompleted) {
                    items(done, key = { it.id }) { task ->
                        TaskListRow(
                            task = task,
                            depth = 0,
                            onToggle = { scope.launch { repo.setCompleted(task.id, !task.completed) } },
                            onClick = { onOpenTask(task.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListRow(task: TaskEntity, depth: Int, onToggle: () -> Unit, onClick: () -> Unit) {
    val due = task.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val overdue = due != null && !task.completed && due.isBefore(LocalDate.now())
    ListItem(
        leadingContent = { Checkbox(checked = task.completed, onCheckedChange = { onToggle() }) },
        headlineContent = {
            Text(
                task.title.ifBlank { "(cím nélkül)" },
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
            )
        },
        supportingContent = due?.let {
            {
                Text(
                    it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.padding(start = (depth * 24).dp).clickable(onClick = onClick),
    )
}
