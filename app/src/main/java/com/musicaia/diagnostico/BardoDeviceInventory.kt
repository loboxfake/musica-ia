package com.musicaia.diagnostico

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * Inventario local y de solo lectura del dispositivo.
 * Respeta package visibility: solo enumera aplicaciones que Android expone
 * mediante una actividad MAIN/LAUNCHER. No requiere QUERY_ALL_PACKAGES.
 */
object BardoDeviceInventory {
    data class AppEntry(
        val label: String,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val systemApp: Boolean
    )

    data class Snapshot(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val supportedAbis: List<String>,
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val lowMemory: Boolean,
        val totalStorageBytes: Long,
        val availableStorageBytes: Long,
        val apps: List<AppEntry>
    ) {
        val summary: String
            get() = buildString {
                append("Fabricante/modelo: ").append(manufacturer).append(' ').append(model).append('\n')
                append("Android: ").append(androidRelease).append(" (API ").append(sdk).append(")\n")
                append("ABI: ").append(supportedAbis.joinToString().ifBlank { "no declarada" }).append('\n')
                append("RAM total/libre: ").append(gib(totalRamBytes)).append(" / ").append(gib(availableRamBytes)).append(" GiB")
                append(if (lowMemory) " · Android indica memoria baja" else "").append('\n')
                append("Almacenamiento interno total/libre: ").append(gib(totalStorageBytes)).append(" / ").append(gib(availableStorageBytes)).append(" GiB")
            }
    }

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return Snapshot(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "Desconocido" },
            model = Build.MODEL.orEmpty().ifBlank { "Desconocido" },
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            lowMemory = memory.lowMemory,
            totalStorageBytes = stat.totalBytes,
            availableStorageBytes = stat.availableBytes,
            apps = launchableApps(app)
        )
    }

    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            val packageName = activity.packageName ?: return@mapNotNull null
            val packageInfo = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            }.getOrNull() ?: return@mapNotNull null
            val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
            AppEntry(
                label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { packageName },
                packageName = packageName,
                versionName = packageInfo.versionName,
                versionCode = versionCode,
                systemApp = (packageInfo.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            )
        }.distinctBy { it.packageName }
            .sortedWith(compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
    }

    private fun gib(bytes: Long): String = String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0 * 1024.0))
}
