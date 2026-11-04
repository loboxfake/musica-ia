package com.musicaia.servicios

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.musicaia.MainActivity
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.Orquestador
import com.musicaia.modulos.ModuloInstrumental
import com.musicaia.modulos.ModuloLetra

/** Tarea local iniciada por el usuario y coordinada por el Orquestador BARDO. */
class BardoTaskService : Service() {
    companion object {
        const val ACTION_START = "com.musicaia.bardo.START_TASK"
        const val ACTION_CANCEL = "com.musicaia.bardo.CANCEL_TASK"
        const val ACTION_UPDATE = "com.musicaia.bardo.TASK_UPDATE"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_LYRICS_MANUAL = "lyrics_manual"
        const val EXTRA_SOLO = "solo_instrumental"
        const val EXTRA_STATE = "task_state"
        const val EXTRA_MESSAGE = "task_message"
        const val EXTRA_AUDIO = "audio_path"
        const val EXTRA_LYRICS = "lyrics"
        const val EXTRA_ELAPSED = "elapsed_seconds"
        const val PREFS = "bardo_background_tasks"
        const val KEY_STATE = "state"
        const val KEY_MESSAGE = "message"
        const val KEY_AUDIO = "audio_path"
        private const val CHANNEL_ID = "bardo_audio_tasks"
        private const val NOTIFICATION_ID = 7314

        fun iniciar(context: android.content.Context, prompt: String, letraManual: String, soloInstrumental: Boolean) {
            require(prompt.isNotBlank()) { "La tarea necesita una descripción." }
            val intent = Intent(context, BardoTaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_LYRICS_MANUAL, letraManual)
                putExtra(EXTRA_SOLO, soloInstrumental)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val orquestador = Orquestador()
    private var ejecutando = false
    private var inicioMs = 0L
    private var modoSolo = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(CHANNEL_ID, "Tareas de BARDO", NotificationManager.IMPORTANCE_LOW)
            canal.description = "Progreso de tareas de audio iniciadas por el usuario"
            canal.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                orquestador.cancelar()
                publicarEstado("running", "Cancelación solicitada; BARDO se detendrá en el siguiente límite entre módulos.")
            }
            ACTION_START -> iniciarTarea(intent, startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun iniciarTarea(intent: Intent, startId: Int) {
        if (ejecutando) return
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
        val letraManual = intent.getStringExtra(EXTRA_LYRICS_MANUAL).orEmpty()
        modoSolo = intent.getBooleanExtra(EXTRA_SOLO, false)
        if (prompt.isBlank()) {
            publicarEstado("failed", "La descripción de la tarea está vacía.")
            stopSelf(startId)
            return
        }

        ejecutando = true
        inicioMs = System.currentTimeMillis()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, "running").putString(KEY_MESSAGE, "BARDO prepara los módulos locales.")
            .remove(KEY_AUDIO).apply()

        val inicial = construirNotificacion("Preparando módulos locales...", 0)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, inicial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, inicial)

        val entrada = when {
            modoSolo -> Dato.Texto(prompt)
            letraManual.isNotBlank() -> Dato.Texto("[LETRA_USUARIO]\n$letraManual\n\n$prompt")
            else -> Dato.Texto(prompt)
        }
        val modulos = mutableListOf<ModuloIA>()
        if (!modoSolo) modulos += ModuloLetra(applicationContext, letraManual.isNotBlank())
        modulos += ModuloInstrumental(applicationContext, prompt)

        orquestador.setListener(object : Orquestador.ProgresoListener {
            override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
                val porcentaje = (progreso * 100f).toInt().coerceIn(0, 100)
                actualizarNotificacion("$paso · $mensaje", porcentaje)
                publicarEstado("running", "$paso · $mensaje")
            }
            override fun onCompletado(resultados: List<Pair<String, Dato>>) = Unit
            override fun onError(mensaje: String) { Log.e("BARDO-TaskService", mensaje) }
        })

        Thread({
            try {
                val resultados = orquestador.ejecutarModulos(modulos, entrada)
                val audio = resultados.lastOrNull()?.second as? Dato.Audio
                    ?: throw IllegalStateException(if (resultados.isEmpty()) "BARDO no recibió resultados del Core." else "El plan terminó sin producir un WAV instrumental válido.")
                val archivo = java.io.File(audio.rutaWav)
                if (!archivo.isFile || archivo.length() <= 44L) throw IllegalStateException("El WAV devuelto no existe o está vacío.")
                val letra = (resultados.firstOrNull { it.first == "letra" }?.second as? Dato.Texto)?.texto.orEmpty()
                val segundos = ((System.currentTimeMillis() - inicioMs) / 1000L).toInt()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_STATE, "completed").putString(KEY_MESSAGE, "Creación terminada.")
                    .putString(KEY_AUDIO, audio.rutaWav).putString("lyrics", letra)
                    .putBoolean("solo", modoSolo).putInt("elapsed", segundos).apply()
                enviarActualizacion("completed", "Creación terminada.", audio.rutaWav, letra, segundos)
                Log.i("BARDO-TaskService", "WAV válido: ${archivo.length()} bytes")
            } catch (error: Throwable) {
                Log.e("BARDO-TaskService", "Falló la tarea de BARDO", error)
                val estado = if (error.message?.contains("cancel", true) == true) "cancelled" else "failed"
                publicarEstado(estado, "${error.javaClass.simpleName}: ${error.message ?: "Error sin detalle"}")
            } finally {
                ejecutando = false
                orquestador.stop()
                android.os.Handler(mainLooper).post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }, "BARDO-Background-Orchestrator").start()
    }

    private fun construirNotificacion(mensaje: String, progreso: Int): Notification {
        val abrir = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancelar = PendingIntent.getService(this, 2, Intent(this, BardoTaskService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("BARDO · tarea en segundo plano")
            .setContentText(mensaje).setContentIntent(abrir).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, progreso, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Solicitar cancelación", cancelar).build()
    }

    private fun actualizarNotificacion(mensaje: String, progreso: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, construirNotificacion(mensaje, progreso))
    }

    private fun publicarEstado(estado: String, mensaje: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, estado).putString(KEY_MESSAGE, mensaje).apply()
        enviarActualizacion(estado, mensaje, "", "", 0)
    }

    private fun enviarActualizacion(estado: String, mensaje: String, audio: String, lyrics: String, elapsed: Int) {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, estado); putExtra(EXTRA_MESSAGE, mensaje); putExtra(EXTRA_AUDIO, audio)
            putExtra(EXTRA_LYRICS, lyrics); putExtra(EXTRA_SOLO, modoSolo); putExtra(EXTRA_ELAPSED, elapsed)
        })
    }

    override fun onDestroy() {
        if (ejecutando) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, "interrupted").putString(KEY_MESSAGE, "Android detuvo el servicio antes de completar la tarea.").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
