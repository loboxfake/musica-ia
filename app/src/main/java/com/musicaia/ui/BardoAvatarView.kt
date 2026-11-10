package com.musicaia.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import com.musicaia.R

/**
 * El avatar conserva el artwork oficial de BARDO sin redibujarlo ni añadirle
 * marcos u ornamentos. La animación comunica el estado real del asistente:
 * escucha, lectura, estudio, razonamiento, respuesta o espera.
 *
 * En reposo y con las animaciones del sistema desactivadas permanece estático.
 */
enum class BardoAvatarState(
    val label: String,
    val animated: Boolean,
    internal val pulseScale: Float,
    internal val pulseRotation: Float,
    internal val pulseDurationMs: Long
) {
    IDLE("En reposo", false, 0f, 0f, 0L),
    LISTENING("Recibiendo texto", true, 0.008f, 0.45f, 1900L),
    READING("Leyendo", true, 0.010f, 0.20f, 2100L),
    STUDYING("Estudiando", true, 0.014f, 0.30f, 1750L),
    THINKING("Pensando", true, 0.012f, 0.65f, 2300L),
    RESPONDING("Respondiendo", true, 0.020f, 0.15f, 1050L),
    GENERATING("Creando", true, 0.022f, 0.10f, 950L),
    WAITING("En espera", true, 0.006f, 0f, 2800L),
    SUCCESS("Completado", false, 0f, 0f, 0L),
    ERROR("Atención", false, 0f, 0f, 0L)
}

class BardoAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private var pulseAnimator: ValueAnimator? = null
    private var currentState = BardoAvatarState.IDLE

    init {
        setImageResource(R.mipmap.ic_launcher)
        scaleType = ScaleType.FIT_CENTER
        background = null
        setPadding(0, 0, 0, 0)
        contentDescription = "Icono original de BARDO · En reposo"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setState(next: BardoAvatarState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setState(next) }
            return
        }
        if (currentState == next) return

        currentState = next
        contentDescription = "Icono original de BARDO · ${next.label}"
        animate().cancel()
        syncPulse()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPulse()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncPulse()
    }

    private fun syncPulse() {
        val shouldAnimate = currentState.animated &&
            isAttachedToWindow &&
            visibility == VISIBLE &&
            windowVisibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()

        if (shouldAnimate) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator?.isStarted == true) return
        val state = currentState
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = state.pulseDurationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                if (currentState != state) return@addUpdateListener
                val progress = animator.animatedValue as Float
                scaleX = 1f + state.pulseScale * progress
                scaleY = 1f + state.pulseScale * progress
                rotation = (progress * 2f - 1f) * state.pulseRotation
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        rotation = 0f
        translationX = 0f
        alpha = 1f
    }
}
