package com.example.olavogascanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ScannerAppScreen()
                }
            }
        }
    }
}

@Composable
fun ScannerAppScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("olavoga_prefs", Context.MODE_PRIVATE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var searchQuery by remember { mutableStateOf(prefs.getString("search_query", "olavoga") ?: "olavoga") }
    var tgToken by remember { mutableStateOf(prefs.getString("tg_token", "") ?: "") }
    var tgChatId by remember { mutableStateOf(prefs.getString("tg_chat_id", "") ?: "") }
    var metaToken by remember { mutableStateOf(prefs.getString("meta_token", "") ?: "") }
    var isScheduled by remember { mutableStateOf(prefs.getBoolean("is_scheduled", false)) }
    var lastScan by remember { mutableStateOf(prefs.getString("last_scan", "Brak") ?: "Brak") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Olavoga Radar 📱",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Skaner OLX & Meta Ads z powiadomieniami Push i raportem na Telegram.",
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Szukana fraza") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = tgToken,
            onValueChange = { tgToken = it },
            label = { Text("Telegram Bot Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = tgChatId,
            onValueChange = { tgChatId = it },
            label = { Text("Telegram Chat ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = metaToken,
            onValueChange = { metaToken = it },
            label = { Text("Meta Ads API Token (opcjonalnie)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Raport codzienny (w tle)", fontWeight = FontWeight.SemiBold)
                    Text("Push + Telegram co 24h", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = isScheduled,
                    onCheckedChange = { enabled ->
                        savePrefs(prefs, searchQuery, tgToken, tgChatId, metaToken, enabled)
                        isScheduled = enabled
                        toggleDailyWorker(context, enabled)
                    }
                )
            }
        }

        Button(
            onClick = {
                savePrefs(prefs, searchQuery, tgToken, tgChatId, metaToken, isScheduled)
                runOneTimeScan(context)
                Toast.makeText(context, "Skanowanie w toku! Sprawdź Push i Telegram.", Toast.LENGTH_LONG).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Zapisz i wyślij raport TERAZ")
        }

        OutlinedButton(
            onClick = {
                val url = "https://www.facebook.com/ads/library/?active_status=active&ad_type=all&country=PL&q=$searchQuery"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Otwórz Meta Ads Library ($searchQuery)")
        }

        TextButton(
            onClick = {
                prefs.edit().remove("seen_ids").apply()
                Toast.makeText(context, "Wyczyszczono historię widzianych ofert.", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Resetuj bazę zapamiętanych ofert")
        }

        Text(
            text = "Ostatni skan: $lastScan",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun savePrefs(
    prefs: android.content.SharedPreferences,
    query: String,
    token: String,
    chatId: String,
    metaToken: String,
    scheduled: Boolean
) {
    prefs.edit()
        .putString("search_query", query.trim())
        .putString("tg_token", token.trim())
        .putString("tg_chat_id", chatId.trim())
        .putString("meta_token", metaToken.trim())
        .putBoolean("is_scheduled", scheduled)
        .apply()
}

private fun runOneTimeScan(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val workRequest = OneTimeWorkRequestBuilder<ScannerWorker>()
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context).enqueue(workRequest)
}

private fun toggleDailyWorker(context: Context, enable: Boolean) {
    val workManager = WorkManager.getInstance(context)
    if (enable) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyRequest = PeriodicWorkRequestBuilder<ScannerWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "OlavogaDailyScanner",
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyRequest
        )
        Toast.makeText(context, "Włączono codzienny raport w tle!", Toast.LENGTH_SHORT).show()
    } else {
        workManager.cancelUniqueWork("OlavogaDailyScanner")
        Toast.makeText(context, "Wyłączono automatyczny raport.", Toast.LENGTH_SHORT).show()
    }
}
