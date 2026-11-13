package com.musicaia.modulos

/**
 * Runs every audio-generation cleanup action without hiding the original failure.
 * If generation succeeded, a cleanup failure is surfaced to the caller.
 */
internal object BardoAudioGenerationCleanup {
    fun finish(primaryFailure: Throwable?, actions: List<() -> Unit>) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                if (primaryFailure != null) {
                    if (primaryFailure !== error) primaryFailure.addSuppressed(error)
                } else {
                    cleanupFailure = merge(cleanupFailure, error)
                }
            }
        }

        if (primaryFailure == null) cleanupFailure?.let { throw it }
    }

    private fun merge(primary: Throwable?, next: Throwable): Throwable {
        if (primary == null) return next
        if (primary !== next) primary.addSuppressed(next)
        return primary
    }
}
