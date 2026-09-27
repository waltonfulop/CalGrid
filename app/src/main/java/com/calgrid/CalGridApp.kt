package com.calgrid

import android.app.Application
import android.content.Context
import com.calgrid.auth.GoogleAuthManager
import com.calgrid.data.calendar.CalendarRepository
import com.calgrid.data.tasks.TasksApi
import com.calgrid.data.tasks.TasksDb
import com.calgrid.data.tasks.TasksRepository
import com.calgrid.settings.AppPrefs
import com.calgrid.settings.WidgetConfigStore
import com.calgrid.sync.SyncScheduler
import com.calgrid.sync.WidgetUpdater

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val calendarRepository = CalendarRepository(appContext)
    val widgetConfigStore = WidgetConfigStore(appContext)
    val appPrefs = AppPrefs(appContext)
    val auth = GoogleAuthManager(appContext)
    val tasksRepository = TasksRepository(
        db = TasksDb.create(appContext),
        api = TasksApi(auth),
        onLocalChange = {
            WidgetUpdater.updateAll(appContext)
            SyncScheduler.requestTasksSync(appContext)
        },
    )
}

class CalGridApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.ensureScheduled(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as CalGridApp).container
