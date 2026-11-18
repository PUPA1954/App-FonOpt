package com.fonopt.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Se incrementa en onResume para volver a escanear al regresar de Ajustes.
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StorageScreen(refreshKey)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }
}

private fun size(context: Context, bytes: Long): String =
    Formatter.formatFileSize(context, bytes)

@Composable
private fun StorageScreen(refreshKey: Int) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf<StorageSummary?>(null) }
    var hasAccess by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppStorage>>(emptyList()) }
    var unused by remember { mutableStateOf<List<AppStorage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshKey) {
        loading = true
        withContext(Dispatchers.IO) {
            summary = StorageScanner.summary(context)
            hasAccess = StorageScanner.hasUsageAccess(context)
            val all = if (hasAccess) StorageScanner.scanApps(context) else emptyList()
            apps = StorageScanner.topApps(all)
            unused = StorageScanner.unusedApps(context, all)
        }
        loading = false
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("FonOpt", style = MaterialTheme.typography.headlineMedium)
            Text("Diagnóstico de almacenamiento", style = MaterialTheme.typography.bodyMedium)
        }

        summary?.let { s ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Almacenamiento interno", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.usedFraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Usado: ${size(context, s.usedBytes)} de ${size(context, s.totalBytes)}")
                        Text("Libre: ${size(context, s.freeBytes)}")
                        if (s.usedFraction > 0.85f) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "⚠️ Más del 85 % lleno: esto suele volver lento el teléfono.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        if (!hasAccess && !loading) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Falta un permiso", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Para ver cuánto ocupa cada app, activa \"Acceso a uso\" " +
                                "para FonOpt en Ajustes. La app solo lee tamaños, " +
                                "nunca el contenido de tus archivos.",
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) { Text("Abrir Ajustes") }
                    }
                }
            }
        }

        if (hasAccess) {
            item {
                Text("Apps que más ocupan", style = MaterialTheme.typography.titleMedium)
            }
            items(apps, key = { it.packageName }) { app ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "App ${size(context, app.appBytes)} · " +
                                    "Datos ${size(context, app.dataBytes)} · " +
                                    "Caché ${size(context, app.cacheBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(size(context, app.totalBytes), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Apps que podrías desinstalar",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Llevan más de ${StorageScanner.UNUSED_AFTER_DAYS} días sin abrirse " +
                        "(historial de los últimos ${StorageScanner.USAGE_WINDOW_DAYS} días). " +
                        "Ordenadas por el espacio que liberarías.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (unused.isEmpty() && !loading) {
                item { Text("No encontré apps sin uso. ¡Bien!") }
            }
            items(unused, key = { "unused-" + it.packageName }) { app ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${lastUsedText(app.lastUsedMillis)} · Liberarías " +
                                    size(context, app.totalBytes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")),
                            )
                        }) { Text("Desinstalar") }
                    }
                }
            }
        }
    }
}

private fun lastUsedText(lastUsedMillis: Long): String {
    if (lastUsedMillis <= 0L) return "Sin uso en ${StorageScanner.USAGE_WINDOW_DAYS}+ días"
    val days = (System.currentTimeMillis() - lastUsedMillis) / (24L * 60 * 60 * 1000)
    return "Último uso: hace $days días"
}
