package com.calgrid.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.calgrid.sync.SyncScheduler
import com.calgrid.ui.theme.CalGridTheme
import com.calgrid.widget.WidgetIntents
import java.time.LocalDate

/** Transparent dialog behind the widget's + button: new calendar event or new task. */
class AddChooserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val today = LocalDate.now()
        val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today

        setContent {
            CalGridTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text("Új bejegyzés") },
                    text = {
                        Column {
                            TextButton(onClick = { open(WidgetIntents.newEvent(date, today)) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Naptáresemény")
                            }
                            TextButton(onClick = {
                                addTask()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Feladat")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = ::finish) { Text("Mégse") } },
                )
            }
        }
    }

    private fun open(target: Intent) {
        startActivity(target)
        finish()
    }

    /** Google Tasks when installed, otherwise CalGrid's own editor. */
    private fun addTask() {
        try {
            startActivity(WidgetIntents.googleTasks())
            SyncScheduler.requestTasksSyncSoon(this)
        } catch (e: ActivityNotFoundException) {
            startActivity(WidgetIntents.newTaskInApp(this))
        }
        finish()
    }

    companion object {
        const val EXTRA_DATE = "com.calgrid.extra.DATE"
    }
}
