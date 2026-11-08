package com.musicaia.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import com.musicaia.R

/**
 * El avatar ES el icono oficial completo de BARDO.
 *
 * No dibuja marcos, puntos, fondos ni ornamentos nuevos. La actividad solo
 * cambia el texto accesible y aplica un pulso de escala casi imperceptible
 * al artwork original. En reposo el icono permanece completamente estático.
 */
enum class BardoAvatarState(
    val label: String,
    val animated: Boolean
) {
    IDLE("En reposo", false),
    LISTENING("Recibiendo texto", true),
    READING("Leyendo", true),
    STUDYING("Estudiando", true),
    THINKING("Pensando", true),
    RESPONDING("Respondiendo", true),
    GENERATING("Creando", true),
    WAITING("En espera", true),
    SUCCESS("Completado", false),
    ERROR("Atención", false)
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

        if (next.animated) {
            syncPulse()
        } else {
            stopPulse()
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .start()
        }
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
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.018f).apply {
            duration = 1450L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                scaleX = scale
                scaleY = scale
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
    }
}
