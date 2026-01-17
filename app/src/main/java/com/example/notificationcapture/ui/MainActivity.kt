package com.example.notificationcapture.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.notificationcapture.NotificationCapture
import com.example.notificationcapture.NotificationSource
import com.example.notificationcapture.db.CapturedNotification
import com.example.notificationcapture.service.CaptureService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val capture = remember { NotificationCapture.get() }
    val scope = rememberCoroutineScope()

    val isRunning by CaptureService.isRunning.collectAsStateWithLifecycle()
    val isSuspended by CaptureService.isSuspended.collectAsStateWithLifecycle()
    val notifications by capture.notifications.collectAsStateWithLifecycle(emptyList())
    val connectedProviders by capture.connectedProviders.collectAsStateWithLifecycle(emptySet())

    var hasPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasPermission = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification Capture") },
                actions = {
                    // Status indicator
                    val color = if (isRunning) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    Badge(containerColor = color) {
                        Text(if (isRunning) "ON" else "OFF")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Permission Card
            if (!hasPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Notification Access Required",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Grant permission to capture notifications")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) {
                            Text("Open Settings")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Suspend/Resume Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuspended) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            if (isSuspended) "Capture Paused" else "Capture Active",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isSuspended) "Notifications are not being captured"
                            else "All notifications are being captured",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = !isSuspended,
                        onCheckedChange = { enabled ->
                            CaptureService.setSuspended(!enabled)
                        }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard("Total", notifications.size.toString())
                StatCard("Local", notifications.count { it.source == "LOCAL" }.toString())
                StatCard("Remote", notifications.count { it.source != "LOCAL" }.toString())
            }

            Spacer(Modifier.height(16.dp))

            // Connected Providers
            Text("Remote Providers", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotificationSource.entries.filter { it != NotificationSource.LOCAL }.forEach { source ->
                    val connected = source in connectedProviders
                    FilterChip(
                        selected = connected,
                        onClick = {
                            if (connected) {
                                scope.launch { capture.disconnectProvider(source) }
                            } else {
                                // Would open OAuth flow
                            }
                        },
                        label = { Text(source.name) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Notifications List
            Text(
                "Recent Notifications (${notifications.size})",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(notifications.take(50)) { notification ->
                    NotificationCard(notification)
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NotificationCard(notification: CapturedNotification) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    notification.packageName.substringAfterLast('.'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    dateFormat.format(Date(notification.postTime)),
                    style = MaterialTheme.typography.labelSmall
                )
            }

            notification.title?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }

            notification.text?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Status badges
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Badge { Text(notification.type) }
                Badge { Text(notification.source) }
                if (notification.wasCancelled) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                        Text("Cancelled")
                    }
                }
                if (notification.markedRead) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                        Text("Read")
                    }
                }
            }
        }
    }
}
