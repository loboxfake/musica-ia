package com.musicaia.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.musicaia.R

/**
 * Avatar funcional de BARDO basado en el recurso launcher real.
 *
 * Las animaciones son breves y de bajo coste: solo hay un pulso suave mientras
 * existe una actividad observable y se detienen al ocultar/desacoplar la vista.
 */
enum class BardoAvatarState(
    val label: String,
    val color: Int,
    val animated: Boolean
) {
    IDLE("En reposo", Color.rgb(78, 209, 241), false),
    LISTENING("Recibiendo texto", Color.rgb(78, 209, 241), true),
    READING("Leyendo", Color.rgb(42, 91, 187), true),
    STUDYING("Estudiando", Color.rgb(115, 69, 215), true),
    THINKING("Pensando", Color.rgb(115, 69, 215), true),
    RESPONDING("Respondiendo", Color.rgb(78, 209, 241), true),
    GENERATING("Creando", Color.rgb(217, 184, 107), true),
    WAITING("En espera", Color.rgb(169, 184, 210), true),
    SUCCESS("Completado", Color.rgb(83, 190, 139), false),
    ERROR("Atención", Color.rgb(238, 104, 104), false)
}

class BardoAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val icon = ImageView(context)
    private val activityDot = View(context)
    private var pulseAnimator: ValueAnimator? = null
    private var currentState = BardoAvatarState.IDLE

    init {
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        clipChildren = false
        clipToPadding = false
        background = ring(currentState.color)

        icon.setImageResource(R.mipmap.ic_launcher_foreground)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.setPadding(dp(2), dp(2), dp(2), dp(2))
        icon.contentDescription = null
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        activityDot.background = dot(currentState.color)
        addView(
            activityDot,
            LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(1)
                bottomMargin = dp(1)
            }
        )

        contentDescription = "Avatar de BARDO · En reposo"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setState(next: BardoAvatarState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setState(next) }
            return
        }
        if (currentState == next) return

        currentState = next
        background = ring(next.color)
        activityDot.background = dot(next.color)
        contentDescription = "Avatar de BARDO · ${next.label}"

        syncPulse()
        animate().cancel()
        animate()
            .scaleX(if (next.animated) 1.035f else 1f)
            .scaleY(if (next.animated) 1.035f else 1f)
            .setDuration(220L)
            .start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
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
            visibility == View.VISIBLE &&
            windowVisibility == View.VISIBLE
        if (shouldAnimate) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator?.isStarted == true) return
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.035f).apply {
            duration = 1200L
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
        scaleX = 1f
        scaleY = 1f
    }

    private fun ring(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.rgb(7, 13, 25))
        setStroke(dp(2), color)
    }

    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.rgb(7, 13, 25))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
