package com.musicaia

import android.os.PowerManager
import com.musicaia.modelo.BardoResourceBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BardoResourceBudgetTest {
    @Test
    fun blocksWhenRamIsBelowTheRequestedBudget() {
        val reason = BardoResourceBudget.blockingReason(
            BardoResourceBudget.Snapshot(512L * 1024L * 1024L, false, 80, true, 0),
            minimumRamBytes = 1024L * 1024L * 1024L
        )
        assertEquals(true, reason?.contains("RAM libre insuficiente"))
    }

    @Test
    fun blocksWhenBatteryIsLowAndDeviceIsNotCharging() {
        val reason = BardoResourceBudget.blockingReason(
            BardoResourceBudget.Snapshot(5L * 1024L * 1024L * 1024L, false, 9, false, 0),
            minimumRamBytes = 1024L * 1024L * 1024L
        )
        assertEquals(true, reason?.contains("Batería baja"))
    }

    @Test
    fun blocksWhenThermalStatusIsSevere() {
        val reason = BardoResourceBudget.blockingReason(
            BardoResourceBudget.Snapshot(5L * 1024L * 1024L * 1024L, false, 80, false, PowerManager.THERMAL_STATUS_SEVERE),
            minimumRamBytes = 1024L * 1024L * 1024L
        )
        assertEquals(true, reason?.contains("demasiado caliente"))
    }

    @Test
    fun allowsWorkWhenAllBudgetsAreSafe() {
        val reason = BardoResourceBudget.blockingReason(
            BardoResourceBudget.Snapshot(5L * 1024L * 1024L * 1024L, false, 80, false, PowerManager.THERMAL_STATUS_LIGHT),
            minimumRamBytes = 1024L * 1024L * 1024L
        )
        assertNull(reason)
    }
}
