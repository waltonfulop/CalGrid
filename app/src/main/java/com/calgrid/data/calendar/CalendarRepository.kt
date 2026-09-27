package com.calgrid.data.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat
import com.calgrid.model.EventInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CalendarInfo(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val visible: Boolean,
)

/** Reads calendars and expanded event instances from the Android calendar provider. */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun getCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
        )
        val result = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            Calendars.CONTENT_URI, projection, null, null,
            "${Calendars.ACCOUNT_NAME} ASC, ${Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                result += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "",
                    accountName = c.getString(2) ?: "",
                    accountType = c.getString(3) ?: "",
                    color = c.getInt(4),
                    visible = c.getInt(5) == 1,
                )
            }
        }
        result
    }

    /**
     * Returns event instances (recurring events expanded) overlapping [startMillis, endMillis).
     * [calendarIds] null means every calendar that is visible in the system calendar app.
     */
    suspend fun getInstances(
        startMillis: Long,
        endMillis: Long,
        calendarIds: Set<Long>?,
    ): List<EventInstance> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        if (calendarIds != null && calendarIds.isEmpty()) return@withContext emptyList()

        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMillis)
            ContentUris.appendId(it, endMillis)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.CALENDAR_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
            Instances.EVENT_LOCATION,
        )
        val selection: String
        val args: Array<String>?
        if (calendarIds == null) {
            selection = "${Instances.VISIBLE} = 1"
            args = null
        } else {
            selection = "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",") { "?" }})"
            args = calendarIds.map { it.toString() }.toTypedArray()
        }
        // Declined invitations are hidden, like in Google Calendar.
        val fullSelection =
            "($selection) AND ${Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}"

        val result = mutableListOf<EventInstance>()
        context.contentResolver.query(uri, projection, fullSelection, args, "${Instances.BEGIN} ASC")
            ?.use { c ->
                while (c.moveToNext()) {
                    result += EventInstance(
                        eventId = c.getLong(0),
                        calendarId = c.getLong(1),
                        title = c.getString(2).orEmpty(),
                        begin = c.getLong(3),
                        end = c.getLong(4),
                        allDay = c.getInt(5) == 1,
                        color = c.getInt(6),
                        location = c.getString(7),
                    )
                }
            }
        result
    }

    /** Asks every synced calendar account to sync now (used by the widget's refresh button). */
    suspend fun requestSync() = withContext(Dispatchers.IO) {
        val accounts = getCalendars()
            .filter { it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            .map { Account(it.accountName, it.accountType) }
            .distinct()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        accounts.forEach { account ->
            try {
                ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
            } catch (e: Exception) {
                Log.w("CalendarRepository", "requestSync failed for ${account.type}", e)
            }
        }
    }
}
