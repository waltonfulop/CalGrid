package com.calgrid.ui

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calgrid.R
import com.calgrid.auth.GoogleAuthManager
import com.calgrid.container
import com.calgrid.settings.TasksAccountState
import com.calgrid.sync.SyncScheduler
import com.calgrid.sync.WidgetUpdater
import com.calgrid.widget.CalGridWidgetReceiver
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenTasks: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val account by container.appPrefs.tasksAccount.collectAsStateWithLifecycle(initialValue = null)
    var hasCalendarPermission by remember { mutableStateOf(container.calendarRepository.hasPermission()) }
    var authError by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasCalendarPermission = container.calendarRepository.hasPermission()
        if (account?.enabled == true) SyncScheduler.requestTasksSync(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCalendarPermission = granted
        if (granted) {
            SyncScheduler.ensureScheduled(context)
            scope.launch { WidgetUpdater.updateAll(context) }
        }
    }

    suspend fun onAuthorized(result: AuthorizationResult) {
        container.appPrefs.setSignedIn(GoogleAuthManager.emailOf(result))
        authError = null
        SyncScheduler.requestTasksSync(context)
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            // Even a cancelled flow may carry the real failure status in its data.
            val parsed = if (res.data != null) runCatching { container.auth.resultFromIntent(res.data) } else null
            when {
                res.resultCode == Activity.RESULT_OK && parsed?.getOrNull() != null -> onAuthorized(parsed.getOrThrow())
                parsed?.exceptionOrNull() != null -> authError = describeAuthError(parsed.exceptionOrNull()!!)
                else -> authError = "A hozzáférés nem lett megadva (a Google-ablak kód nélkül zárult be). " +
                    "Ha nem te léptél ki: a Google-fiókodnak szerepelnie kell a Cloud projekt „Test users” listáján, " +
                    "és az Android OAuth kliens SHA-1-ének egyeznie kell ezzel: ${signingSha1(context) ?: "?"}."
            }
            Log.w("CalGrid", "Tasks authorization: resultCode=${res.resultCode}, error=$authError", parsed?.exceptionOrNull())
        }
    }

    fun connectTasks() = scope.launch {
        try {
            val result = container.auth.authorize()
            val pending = result.pendingIntent
            if (result.hasResolution() && pending != null) {
                authLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } else {
                onAuthorized(result)
            }
        } catch (e: Exception) {
            authError = describeAuthError(e)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(context.getString(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Naptár") {
                if (hasCalendarPermission) {
                    Text("A widget a telefonon szinkronizált összes naptárat látja, és magától frissül.")
                } else {
                    Text("A widgetnek olvasási hozzáférés kell a naptárakhoz.")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) }) {
                        Text("Hozzáférés megadása")
                    }
                }
            }

            SectionCard("Google Tasks") {
                TasksAccountSection(
                    account = account,
                    error = authError,
                    onConnect = { connectTasks() },
                    onSyncNow = { SyncScheduler.requestTasksSync(context) },
                    onOpenTasks = onOpenTasks,
                    onDisconnect = {
                        scope.launch {
                            container.appPrefs.setSignedOut()
                            container.tasksRepository.clearLocal()
                            WidgetUpdater.updateAll(context)
                        }
                    },
                )
            }

            SectionCard("Widget") {
                Text("Tartsd lenyomva a kezdőképernyőt, válaszd a Widgetek menüt, és keresd meg a CalGrid widgetet. A beállításait a widget ⚙ gombjával nyitod meg.")
                val manager = AppWidgetManager.getInstance(context)
                if (manager.isRequestPinAppWidgetSupported) {
                    OutlinedButton(onClick = {
                        manager.requestPinAppWidget(ComponentName(context, CalGridWidgetReceiver::class.java), null, null)
                    }) { Text("Widget hozzáadása") }
                }
            }
        }
    }
}

@Composable
private fun TasksAccountSection(
    account: TasksAccountState?,
    error: String?,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenTasks: () -> Unit,
    onDisconnect: () -> Unit,
) {
    if (account == null) return
    if (!account.enabled) {
        Text("Csatlakoztasd a Google-fiókodat, hogy a feladataid megjelenjenek a widgetben.")
        Button(onClick = onConnect) { Text("Csatlakozás") }
        val sha1 = signingSha1(LocalContext.current)
        if (sha1 != null) {
            Text("Az app SHA-1 lenyomata (a Cloud Console-ba): $sha1", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        Text(account.email?.let { "Csatlakoztatva: $it" } ?: "Csatlakoztatva")
        val lastSync = if (account.lastSyncMillis > 0) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(account.lastSyncMillis))
        } else {
            "még nem volt"
        }
        Text("Utolsó szinkron: $lastSync", style = MaterialTheme.typography.bodySmall)
        account.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onConnect) { Text("Újracsatlakozás") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpenTasks) { Text("Feladatok") }
            OutlinedButton(onClick = onSyncNow) { Text("Szinkron") }
            TextButton(onClick = onDisconnect) { Text("Leválasztás") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun describeAuthError(e: Throwable): String {
    val api = e as? ApiException ?: e.cause as? ApiException
    return when (api?.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Az OAuth kliens nincs beállítva ehhez az apphoz (package név + SHA-1). Lásd a README-t."
        CommonStatusCodes.NETWORK_ERROR -> "Nincs hálózati kapcsolat."
        CommonStatusCodes.CANCELED -> "A hozzáférés nem lett megadva (megszakítva)."
        null -> "Nem sikerült csatlakozni: ${e.message ?: e.javaClass.simpleName}"
        else -> "Nem sikerült csatlakozni (kód: ${api.statusCode}, " +
            "${CommonStatusCodes.getStatusCodeString(api.statusCode)}): ${api.message.orEmpty()}"
    }
}

/** SHA-1 of the certificate this build is signed with, in the format the Google Cloud console expects. */
private fun signingSha1(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
    MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrNull()
