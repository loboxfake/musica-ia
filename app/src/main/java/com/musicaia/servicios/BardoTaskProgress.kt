package com.musicaia.servicios

/**
 * La escritura del timeline es telemetría local, no parte del resultado creativo.
 * Los fallos ordinarios de almacenamiento se notifican, pero no interrumpen la tarea.
 * Los errores fatales de la VM no se absorben.
 */
internal fun recordTaskProgressSafely(
    record: () -> Unit,
    onFailure: (Exception) -> Unit
) {
    try {
        record()
    } catch (error: Exception) {
        onFailure(error)
    }
}
