package com.fonopt.app

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
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

    /** Las [limit] apps que más espacio ocupan. Requiere acceso a uso. */
    fun topApps(context: Context, limit: Int = 10): List<AppStorage> {
        val pm = context.packageManager
        val statsManager = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val packages = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()

        return packages.mapNotNull { pkg ->
            try {
                val s = statsManager.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                AppStorage(
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(),
                    packageName = pkg,
                    appBytes = s.appBytes,
                    dataBytes = s.dataBytes,
                    cacheBytes = s.cacheBytes,
                )
            } catch (e: Exception) {
                null // app sin datos accesibles: la saltamos
            }
        }.sortedByDescending { it.totalBytes }.take(limit)
    }
}
