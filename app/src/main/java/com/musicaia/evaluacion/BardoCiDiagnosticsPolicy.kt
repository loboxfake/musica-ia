package com.musicaia.evaluacion

/**
 * Keeps the latest CI diagnostic ordered by monotonically increasing GitHub run ID.
 * A later successful run resolves older failures; an older run cannot overwrite
 * or resurrect diagnostics after a newer run has already been processed.
 */
object BardoCiDiagnosticsPolicy {
    fun canReplace(currentRunId: Long, incomingRunId: Long): Boolean =
        currentRunId >= 0L && incomingRunId > 0L && incomingRunId >= currentRunId
}
