package com.musicaia.modelo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Lectura conservadora de recursos antes de cargar modelos pesados. */
object BardoResourceBudget {
    data class Snapshot(
        val availableRamBytes: Long,
        val lowMemory: Boolean,
        val batteryPercent: Int?,
        val charging: Boolean,
        val thermalStatus: Int?
    )

    fun inspect(context: Context): Snapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)

        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.getSystemService(PowerManager::class.java).currentThermalStatus
            }.getOrNull()
        } else null

        return Snapshot(memory.availMem, memory.lowMemory, percent, charging, thermal)
    }

    /**
     * Devuelve un bloqueo explicable o null si se puede intentar la carga.
     * El umbral no garantiza que el modelo quepa: es una puerta preventiva.
     */
    fun blockingReason(
        snapshot: Snapshot,
        minimumRamBytes: Long,
        minimumBatteryPercent: Int = 15,
        maximumThermalStatus: Int = PowerManager.THERMAL_STATUS_MODERATE
    ): String? {
        if (snapshot.lowMemory || snapshot.availableRamBytes < minimumRamBytes) {
            val availableMiB = snapshot.availableRamBytes / (1024L * 1024L)
            val requiredMiB = minimumRamBytes / (1024L * 1024L)
            return "RAM libre insuficiente: ${availableMiB} MiB disponibles; BARDO necesita al menos ${requiredMiB} MiB para intentarlo."
        }
        if (snapshot.thermalStatus != null && snapshot.thermalStatus > maximumThermalStatus) {
            return "El teléfono está demasiado caliente (estado térmico ${snapshot.thermalStatus}). BARDO aplaza la carga para proteger el dispositivo."
        }
        if (snapshot.batteryPercent != null && snapshot.batteryPercent < minimumBatteryPercent && !snapshot.charging) {
            return "Batería baja (${snapshot.batteryPercent}%). Conecta el teléfono o espera a que supere el ${minimumBatteryPercent}% antes de cargar un modelo pesado."
        }
        return null
    }

    fun blockingReason(
        context: Context,
        minimumRamBytes: Long,
        minimumBatteryPercent: Int = 15
    ): String? = blockingReason(inspect(context), minimumRamBytes, minimumBatteryPercent)
}
