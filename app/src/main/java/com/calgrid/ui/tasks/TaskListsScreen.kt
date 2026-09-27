package com.calgrid.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calgrid.container
import com.calgrid.data.tasks.TaskListEntity
import com.calgrid.sync.SyncScheduler
import com.calgrid.sync.WidgetUpdater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListsScreen(onBack: () -> Unit, onOpenList: (String) -> Unit) {
    val context = LocalContext.current
    val repo = context.container.tasksRepository
    val lists by repo.observeLists().collectAsStateWithLifecycle(initialValue = emptyList())
    val counts by repo.observeOpenCounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<TaskListEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TaskListEntity?>(null) }

    /** List operations go straight to the server, so report failures. */
    fun runOnline(block: suspend () -> Unit) = scope.launch {
        try {
            block()
            WidgetUpdater.updateAll(context)
        } catch (e: Exception) {
            snackbar.showSnackbar("Nem sikerült: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feladatlisták") },
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
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Új lista") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (lists.isEmpty()) {
            Text(
                "Még nincsenek listák. Ha most csatlakoztál, várj a szinkronra.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(lists, key = { it.id }) { list ->
                val open = counts.firstOrNull { it.listId == list.id }?.open ?: 0
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(list.title) },
                    supportingContent = { Text("$open nyitott feladat") },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Műveletek") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Átnevezés") }, onClick = { menu = false; editing = list })
                                DropdownMenuItem(text = { Text("Törlés") }, onClick = { menu = false; deleting = list })
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpenList(list.id) },
                )
            }
        }
    }

    if (creating) {
        TitleDialog(title = "Új lista", initial = "", onDismiss = { creating = false }) { name ->
            creating = false
            runOnline { repo.createList(name) }
        }
    }
    editing?.let { list ->
        TitleDialog(title = "Lista átnevezése", initial = list.title, onDismiss = { editing = null }) { name ->
            editing = null
            runOnline { repo.renameList(list.id, name) }
        }
    }
    deleting?.let { list ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Törlöd a(z) „${list.title}” listát?") },
            text = { Text("A lista összes feladata is törlődik a Google Tasksből.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; runOnline { repo.deleteList(list.id) } }) { Text("Törlés") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Mégse") } },
        )
    }
}

@Composable
fun TitleDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Mentés") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégse") } },
    )
}
