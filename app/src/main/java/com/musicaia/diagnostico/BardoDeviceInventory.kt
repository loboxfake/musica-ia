package com.musicaia.diagnostico

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * Inventario local y de solo lectura del dispositivo.
 *
 * Android limita la visibilidad de paquetes. Se consultan aplicaciones con
 * actividad MAIN/LAUNCHER, receptores declarados de ACTION_ASSIST y el titular
 * de ROLE_ASSISTANT. No se usa QUERY_ALL_PACKAGES.
 */
object BardoDeviceInventory {
    data class AppEntry(
        val label: String,
        val packageName: String,
        val versionName: String?,
        val versionCode: Long,
        val systemApp: Boolean
    )

    data class AssistantEntry(
        val label: String,
        val packageName: String,
        val activeAssistantRole: Boolean,
        val handlesAssistAction: Boolean
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
        val apps: List<AppEntry>,
        val assistantApps: List<AssistantEntry>,
        val probableAiApps: List<AppEntry>
    ) {
        val summary: String
            get() = buildString {
                append("Fabricante/modelo: ").append(manufacturer).append(' ').append(model).append('\\n')
                append("Android: ").append(androidRelease).append(" (API ").append(sdk).append(")\\n")
                append("ABI: ").append(supportedAbis.joinToString().ifBlank { "no declarada" }).append('\\n')
                append("RAM total/libre: ").append(gib(totalRamBytes)).append(" / ").append(gib(availableRamBytes)).append(" GiB")
                append(if (lowMemory) " · Android indica memoria baja" else "").append('\\n')
                append("Almacenamiento interno total/libre: ").append(gib(totalStorageBytes)).append(" / ").append(gib(availableStorageBytes)).append(" GiB").append('\\n')
                append("Apps lanzables visibles: ").append(apps.size).append(" · asistentes Android declarados: ").append(assistantApps.size)
                append(" · posibles apps IA por nombre: ").append(probableAiApps.size)
            }
    }

    fun snapshot(context: Context): Snapshot {
        val app = context.applicationContext
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val launchable = launchableApps(app)
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
            apps = launchable,
            assistantApps = assistantApps(app, launchable),
            probableAiApps = probableAiApps(launchable)
        )
    }

    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(pm, intent).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            appEntry(pm, packageName, info.loadLabel(pm)?.toString())
        }.distinctBy { it.packageName }
            .sortedWith(compareBy<AppEntry> { it.label.lowercase(Locale.ROOT) }.thenBy { it.packageName })
    }

    private fun assistantApps(context: Context, launchableApps: List<AppEntry>): List<AssistantEntry> {
        val pm = context.packageManager
        val activeHolders = activeAssistantRoleHolders(context)
        val handlers = queryActivities(pm, Intent(Intent.ACTION_ASSIST))
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
        val packages = activeHolders + handlers
        val labels = launchableApps.associate { it.packageName to it.label }
        return packages.map { packageName ->
            val label = labels[packageName] ?: runCatching {
                val appInfo = getApplicationInfo(pm, packageName)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
            AssistantEntry(
                label = label.ifBlank { packageName },
                packageName = packageName,
                activeAssistantRole = packageName in activeHolders,
                handlesAssistAction = packageName in handlers
            )
        }.sortedWith(compareBy<AssistantEntry> { !it.activeAssistantRole }.thenBy { it.label.lowercase(Locale.ROOT) })
    }

    private fun activeAssistantRoleHolders(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT < 29) return emptySet()
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return emptySet()
        return runCatching {
            if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                roleManager.getRoleHolders(RoleManager.ROLE_ASSISTANT).toSet()
            } else {
                emptySet()
            }
        }.getOrDefault(emptySet())
    }

    private fun probableAiApps(apps: List<AppEntry>): List<AppEntry> {
        val indicators = listOf(
            "chatgpt", "openai", "gemini", "copilot", "claude", "perplexity",
            "grok", "deepseek", "qwen", "mistral", "notebooklm", "character.ai",
            "poe", "bixby", "alexa", "meta ai", "metaai"
        )
        return apps.filter { app ->
            val identity = (app.label + " " + app.packageName).lowercase(Locale.ROOT)
            indicators.any(identity::contains) || Regex("(^|[^a-z])ai([^a-z]|$)").containsMatchIn(identity)
        }
    }

    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    private fun appEntry(pm: PackageManager, packageName: String, resolvedLabel: String? = null): AppEntry? {
        val packageInfo = runCatching { getPackageInfo(pm, packageName) }.getOrNull() ?: return null
        val appInfo = packageInfo.applicationInfo
        val label = resolvedLabel?.trim().takeUnless { it.isNullOrBlank() }
            ?: runCatching { pm.getApplicationLabel(getApplicationInfo(pm, packageName)).toString() }.getOrDefault(packageName)
        return AppEntry(
            label = label,
            packageName = packageName,
            versionName = packageInfo.versionName,
            versionCode = packageVersionCode(packageInfo),
            systemApp = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        )
    }

    private fun getApplicationInfo(pm: PackageManager, packageName: String): android.content.pm.ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }

    private fun getPackageInfo(pm: PackageManager, packageName: String): android.content.pm.PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun gib(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0 * 1024.0))
}
