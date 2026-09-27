package com.calgrid.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

enum class WidgetLayout { MONTH, AGENDA, BOTH }

@Serializable
data class WidgetConfig(
    /** null = every calendar visible in the system calendar app. */
    val calendarIds: Set<Long>? = null,
    /** null = every task list. */
    val taskListIds: Set<String>? = null,
    val layout: WidgetLayout = WidgetLayout.BOTH,
    val agendaDays: Int = 14,
    /** ISO day number (1 = Monday) of the first column in the month grid. */
    val firstDayIso: Int = DayOfWeek.MONDAY.value,
    val showTasks: Boolean = true,
    val showUndatedTasks: Boolean = true,
    val showCompletedTasks: Boolean = false,
    /** Background opacity in percent, 0..100. */
    val backgroundOpacity: Int = 100,
) {
    val firstDayOfWeek: DayOfWeek get() = DayOfWeek.of(firstDayIso)
}

private val Context.widgetConfigStore by preferencesDataStore(name = "widget_configs")
private val Context.appPrefsStore by preferencesDataStore(name = "app_prefs")

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class WidgetConfigStore(private val context: Context) {

    private fun key(appWidgetId: Int) = stringPreferencesKey("widget_$appWidgetId")

    suspend fun get(appWidgetId: Int): WidgetConfig {
        val raw = context.widgetConfigStore.data.first()[key(appWidgetId)] ?: return WidgetConfig()
        return runCatching { json.decodeFromString<WidgetConfig>(raw) }.getOrDefault(WidgetConfig())
    }

    suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        context.widgetConfigStore.edit { it[key(appWidgetId)] = json.encodeToString(WidgetConfig.serializer(), config) }
    }

    suspend fun delete(appWidgetId: Int) {
        context.widgetConfigStore.edit { it.remove(key(appWidgetId)) }
    }
}

data class TasksAccountState(
    val enabled: Boolean,
    val email: String?,
    val lastSyncMillis: Long,
    val lastError: String?,
)

class AppPrefs(private val context: Context) {

    private object Keys {
        val tasksEnabled = booleanPreferencesKey("tasks_enabled")
        val email = stringPreferencesKey("account_email")
        val lastSync = longPreferencesKey("last_sync")
        val lastError = stringPreferencesKey("last_error")
    }

    val tasksAccount: Flow<TasksAccountState> = context.appPrefsStore.data.map { it.toState() }

    suspend fun tasksAccountNow(): TasksAccountState = tasksAccount.first()

    suspend fun setSignedIn(email: String?) = context.appPrefsStore.edit {
        it[Keys.tasksEnabled] = true
        if (email != null) it[Keys.email] = email else it.remove(Keys.email)
        it.remove(Keys.lastError)
    }

    suspend fun setSignedOut() = context.appPrefsStore.edit {
        it[Keys.tasksEnabled] = false
        it.remove(Keys.email)
        it.remove(Keys.lastError)
        it.remove(Keys.lastSync)
    }

    suspend fun recordSync(error: String?) = context.appPrefsStore.edit {
        if (error == null) {
            it[Keys.lastSync] = System.currentTimeMillis()
            it.remove(Keys.lastError)
        } else {
            it[Keys.lastError] = error
        }
    }

    private fun Preferences.toState() = TasksAccountState(
        enabled = this[Keys.tasksEnabled] ?: false,
        email = this[Keys.email],
        lastSyncMillis = this[Keys.lastSync] ?: 0L,
        lastError = this[Keys.lastError],
    )
}
