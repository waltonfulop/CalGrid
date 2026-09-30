package com.calgrid.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.calgrid.ui.tasks.TaskEditorScreen
import com.calgrid.ui.tasks.TaskListsScreen
import com.calgrid.ui.tasks.TasksScreen
import com.calgrid.ui.theme.CalGridTheme

class MainActivity : ComponentActivity() {

    /** Route requested by a widget tap, consumed once the nav graph is up. */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute = routeFor(intent)
        setContent {
            CalGridTheme {
                AppNavigation(pendingRoute) { pendingRoute = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pendingRoute = it }
    }

    private fun routeFor(intent: Intent?): String? = when {
        intent == null -> null
        intent.getBooleanExtra(EXTRA_NEW_TASK, false) -> Routes.newTask(null, fromWidget = true)
        intent.hasExtra(EXTRA_TASK_ID) -> Routes.task(intent.getStringExtra(EXTRA_TASK_ID)!!, fromWidget = true)
        else -> null
    }

    companion object {
        const val EXTRA_TASK_ID = "com.calgrid.extra.TASK_ID"
        const val EXTRA_NEW_TASK = "com.calgrid.extra.NEW_TASK"
    }
}

object Routes {
    const val HOME = "home"
    const val LISTS = "lists"
    const val TASKS = "tasks/{listId}"
    const val TASK = "task/{taskId}?fromWidget={fromWidget}"
    const val NEW_TASK = "newtask?listId={listId}&fromWidget={fromWidget}"

    fun tasks(listId: String) = "tasks/$listId"
    fun task(taskId: String, fromWidget: Boolean = false) = "task/$taskId?fromWidget=$fromWidget"
    fun newTask(listId: String?, fromWidget: Boolean = false) =
        "newtask?fromWidget=$fromWidget" + if (listId == null) "" else "&listId=$listId"
}

@Composable
private fun AppNavigation(pendingRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val activity = LocalContext.current as ComponentActivity

    /** Editors opened from the widget hand back to the home screen instead of the app. */
    fun closeEditor(fromWidget: Boolean) {
        nav.popBackStack()
        if (fromWidget) activity.moveTaskToBack(true)
    }

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            nav.navigate(pendingRoute)
            onRouteConsumed()
        }
    }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(onOpenTasks = { nav.navigate(Routes.LISTS) })
        }
        composable(Routes.LISTS) {
            TaskListsScreen(
                onBack = { nav.popBackStack() },
                onOpenList = { nav.navigate(Routes.tasks(it)) },
            )
        }
        composable(Routes.TASKS, arguments = listOf(navArgument("listId") { type = NavType.StringType })) { entry ->
            val listId = entry.arguments?.getString("listId").orEmpty()
            TasksScreen(
                listId = listId,
                onBack = { nav.popBackStack() },
                onOpenTask = { nav.navigate(Routes.task(it)) },
                onNewTask = { nav.navigate(Routes.newTask(listId)) },
            )
        }
        composable(
            Routes.TASK,
            arguments = listOf(
                navArgument("taskId") { type = NavType.StringType },
                navArgument("fromWidget") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            TaskEditorScreen(
                taskId = entry.arguments?.getString("taskId"),
                initialListId = null,
                onDone = { closeEditor(entry.arguments?.getBoolean("fromWidget") == true) },
            )
        }
        composable(
            Routes.NEW_TASK,
            arguments = listOf(
                navArgument("listId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("fromWidget") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            TaskEditorScreen(
                taskId = null,
                initialListId = entry.arguments?.getString("listId"),
                onDone = { closeEditor(entry.arguments?.getBoolean("fromWidget") == true) },
            )
        }
    }
}
