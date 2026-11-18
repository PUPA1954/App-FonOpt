package com.fonopt.app

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager

/** Resumen del almacenamiento interno del teléfono. */
data class StorageSummary(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val usedFraction: Float
        get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

/** Espacio que ocupa una app (programa + datos + caché). */
data class AppStorage(
    val label: String,
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    /** Última vez que se usó (ms desde 1970). 0 = sin uso en la ventana consultada. */
    val lastUsedMillis: Long = 0L,
    val isSystem: Boolean = false,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

object StorageScanner {

    /** Total y libre del almacenamiento interno. No necesita permisos. */
    fun summary(context: Context): StorageSummary {
        val stats = context.getSystemService(StorageStatsManager::class.java)
        return StorageSummary(
            totalBytes = stats.getTotalBytes(StorageManager.UUID_DEFAULT),
            freeBytes = stats.getFreeBytes(StorageManager.UUID_DEFAULT),
        )
    }

    /** ¿El usuario ya activó "Acceso a uso" para esta app? */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Días de historial de uso que consultamos. */
    const val USAGE_WINDOW_DAYS = 90

    /** Días sin abrir una app para considerarla candidata a desinstalar. */
    const val UNUSED_AFTER_DAYS = 30

    /** Todas las apps con icono de lanzador, con su tamaño y último uso. Requiere acceso a uso. */
    fun scanApps(context: Context): List<AppStorage> {
        val pm = context.packageManager
        val statsManager = context.getSystemService(StorageStatsManager::class.java)
        val usageManager = context.getSystemService(UsageStatsManager::class.java)
        val user = Process.myUserHandle()

        val now = System.currentTimeMillis()
        val windowStart = now - USAGE_WINDOW_DAYS * DAY_MS
        val usage = usageManager.queryAndAggregateUsageStats(windowStart, now)

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val packages = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()

        return packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val s = statsManager.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                val lastUsed = usage[pkg]?.lastTimeUsed ?: 0L
                AppStorage(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = pkg,
                    appBytes = s.appBytes,
                    dataBytes = s.dataBytes,
                    cacheBytes = s.cacheBytes,
                    // Si el sistema no tiene registro dentro de la ventana, queda en 0.
                    lastUsedMillis = if (lastUsed >= windowStart) lastUsed else 0L,
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            } catch (e: Exception) {
                null // app sin datos accesibles: la saltamos
            }
        }
    }

    /** Las [limit] apps que más espacio ocupan. */
    fun topApps(apps: List<AppStorage>, limit: Int = 10): List<AppStorage> =
        apps.sortedByDescending { it.totalBytes }.take(limit)

    /**
     * Apps instaladas por el usuario que llevan [UNUSED_AFTER_DAYS] días sin abrirse,
     * ordenadas de más a menos espacio (así lo que más libera aparece primero).
     */
    fun unusedApps(context: Context, apps: List<AppStorage>, limit: Int = 15): List<AppStorage> {
        val cutoff = System.currentTimeMillis() - UNUSED_AFTER_DAYS * DAY_MS
        return apps
            .filter { !it.isSystem && it.packageName != context.packageName }
            .filter { it.lastUsedMillis < cutoff }
            .sortedByDescending { it.totalBytes }
            .take(limit)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
