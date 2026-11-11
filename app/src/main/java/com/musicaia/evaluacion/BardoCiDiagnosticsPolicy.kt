package com.musicaia.evaluacion

/**
 * Keeps the latest CI diagnostic ordered by monotonically increasing GitHub run ID.
 * A successful run may resolve a failure only within the same workflow scope.
 */
object BardoCiDiagnosticsPolicy {
    fun canReplace(currentRunId: Long, incomingRunId: Long): Boolean =
        currentRunId >= 0L && incomingRunId > 0L && incomingRunId >= currentRunId

    fun shouldClear(
        currentRunId: Long,
        currentStage: String,
        successfulRunId: Long,
        successfulStage: String
    ): Boolean =
        currentStage.isNotBlank() &&
            currentStage == successfulStage &&
            canReplace(currentRunId, successfulRunId)
}
