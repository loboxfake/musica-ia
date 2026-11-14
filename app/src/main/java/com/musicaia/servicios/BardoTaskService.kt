package com.musicaia.servicios

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.musicaia.MainActivity
import com.musicaia.memoria.BardoActivityStore
import com.musicaia.modelo.Dato
import com.musicaia.modelo.ModuloIA
import com.musicaia.modelo.Orquestador
import com.musicaia.modelo.BardoCapabilityRegistry
import com.musicaia.modelo.BardoResourceBudget
import com.musicaia.modulos.ModuloInstrumental
import com.musicaia.modulos.ModuloLetra
import com.musicaia.modulos.Mp3Exporter
import com.musicaia.modulos.BardoWavHeaderValidator
import com.musicaia.modulos.BardoAtomicFilePublisher
import java.util.concurrent.atomic.AtomicBoolean

/** Tarea local iniciada por el usuario y coordinada por el Orquestador BARDO. */
class BardoTaskService : Service() {
    companion object {
        const val ACTION_START = "com.musicaia.bardo.START_TASK"
        const val ACTION_CANCEL = "com.musicaia.bardo.CANCEL_TASK"
        const val ACTION_UPDATE = "com.musicaia.bardo.TASK_UPDATE"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_LYRICS_MANUAL = "lyrics_manual"
        const val EXTRA_SOLO = "solo_instrumental"
        const val EXTRA_QUICK_TEST = "quick_test"
        const val EXTRA_USER_VOICE_MIX = "user_voice_mix_available"
        const val EXTRA_USER_VOICE_WAV_PATH = "user_voice_wav_path"
        const val EXTRA_USER_VOICE_MIXED = "user_voice_already_mixed"
        const val EXTRA_STATE = "task_state"
        const val EXTRA_MESSAGE = "task_message"
        const val EXTRA_AUDIO = "audio_path"
        const val EXTRA_MP3 = "mp3_path"
        const val EXTRA_LYRICS = "lyrics"
        const val EXTRA_ELAPSED = "elapsed_seconds"
        const val PREFS = "bardo_background_tasks"
        const val KEY_STATE = "state"
        const val KEY_MESSAGE = "message"
        const val KEY_AUDIO = "audio_path"
        const val KEY_MP3 = "mp3_path"
        const val KEY_USER_VOICE_MIXED = "user_voice_already_mixed"
        const val KEY_HEARTBEAT = "heartbeat"
        private const val CHANNEL_ID = "bardo_audio_tasks"
        private const val NOTIFICATION_ID = 7314

        fun iniciar(
            context: android.content.Context,
            prompt: String,
            letraManual: String,
            soloInstrumental: Boolean,
            quickTest: Boolean = false,
            userVoiceMixAvailable: Boolean = false,
            userVoiceWavPath: String? = null
        ) {
            require(prompt.isNotBlank()) { "La tarea necesita una descripción." }
            val intent = Intent(context, BardoTaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_LYRICS_MANUAL, letraManual)
                putExtra(EXTRA_SOLO, soloInstrumental)
                putExtra(EXTRA_QUICK_TEST, quickTest)
                putExtra(EXTRA_USER_VOICE_MIX, userVoiceMixAvailable)
                putExtra(EXTRA_USER_VOICE_WAV_PATH, userVoiceWavPath)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val orquestador = Orquestador()
    @Volatile private var ejecutando = false
    @Volatile private var latestStartId = 0
    @Volatile private var activeTaskId = ""
    private val taskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "BARDO-Task-Worker").apply { isDaemon = true }
    }
    private var inicioMs = 0L
    private var taskId = ""
    private var modoSolo = false
    private var pruebaRapida = false
    private var totalPasosAudio = 20
    private val cancelacionSolicitada = AtomicBoolean(false)
    // Serializa la última comprobación de cancelación con el commit terminal.
    private val completionLock = Any()
    // El latido no debe depender del hilo principal: la inferencia nativa puede
    // saturar CPU durante decenas de segundos y provocar falsos "sin vida".
    private val heartbeatExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { tarea ->
        Thread(tarea, "BARDO-Heartbeat").apply { isDaemon = true }
    }
    private var heartbeatFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val heartbeatFile by lazy { java.io.File(filesDir, "bardo_task_heartbeat") }

    private fun iniciarLatido() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate({
            if (ejecutando) {
                runCatching { heartbeatFile.writeText(System.currentTimeMillis().toString()) }
                    .onFailure { Log.w("BARDO-TaskService", "No se pudo actualizar el latido", it) }
            }
        }, 5L, 5L, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun detenerLatido() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

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
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (!ejecutando) {
                    // Puede llegar el toque de cancelar justo después de terminar el worker
                    // y antes de que su cierre en el hilo principal retire la notificación.
                    if (activeTaskId.isBlank()) stopSelfResult(startId)
                } else {
                    val cancellationAccepted = synchronized(completionLock) {
                        val completionAlreadyCommitted =
                            getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_STATE, "") == "completed"
                        if (completionAlreadyCommitted) {
                            false
                        } else {
                            cancelacionSolicitada.set(true)
                            true
                        }
                    }
                    if (cancellationAccepted) {
                        orquestador.cancelar()
                        publicarEstado("running", "Cancelación solicitada. BARDO terminará la operación nativa en curso y se detendrá de forma segura.")
                    } else {
                        Log.i("BARDO-TaskService", "Se ignoró una cancelación recibida después de completar la tarea.")
                    }
                }
            }
            ACTION_START -> iniciarTarea(intent, startId)
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun iniciarTarea(intent: Intent, startId: Int) {
        if (ejecutando) {
            publicarEstado("running", "BARDO ya está ejecutando una tarea. La nueva solicitud no se ha iniciado.")
            return
        }
        val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty().trim()
        val letraManual = intent.getStringExtra(EXTRA_LYRICS_MANUAL).orEmpty()
        modoSolo = intent.getBooleanExtra(EXTRA_SOLO, false)
        pruebaRapida = intent.getBooleanExtra(EXTRA_QUICK_TEST, false)
        if (prompt.isBlank()) {
            publicarEstado("failed", "La descripción de la tarea está vacía.")
            stopSelfResult(startId)
            return
        }
        val requestedVoiceMix = intent.getBooleanExtra(EXTRA_USER_VOICE_MIX, false)
        val userVoiceWavPath = intent.getStringExtra(EXTRA_USER_VOICE_WAV_PATH)
            ?.trim()?.takeIf { it.isNotEmpty() }
        val voiceFile = userVoiceWavPath?.let { java.io.File(it) }
        val voicePathBlock = BardoUserVoiceMixPolicy.blockReason(
            instrumentalOnly = modoSolo,
            advertisedAvailable = requestedVoiceMix,
            voicePath = userVoiceWavPath,
            fileExists = voiceFile?.isFile == true,
            fileLength = voiceFile?.length() ?: 0L
        )
        if (voicePathBlock != null) {
            publicarEstado("failed", voicePathBlock)
            stopSelfResult(startId)
            return
        }
        val userVoiceMixAvailable = BardoUserVoiceMixPolicy.shouldMix(modoSolo, userVoiceWavPath)
        if (!BardoCapabilityRegistry.manualLyricsCanProceed(modoSolo, letraManual, userVoiceMixAvailable)) {
            val mensaje = BardoCapabilityRegistry.manualLyricsBlockReason()
            val blockedId = java.util.UUID.randomUUID().toString()
            runCatching {
                BardoActivityStore.record(this, blockedId, "cancion", "blocked", "sintesis_vocal_no_disponible", mensaje, 0)
            }.onFailure { Log.e("BARDO-TaskService", "No se pudo registrar el bloqueo", it) }
            publicarEstado("failed", mensaje)
            stopSelfResult(startId)
            return
        }

        ejecutando = true
        taskId = java.util.UUID.randomUUID().toString()
        val taskToken = taskId
        activeTaskId = taskToken
        val taskStartId = startId
        cancelacionSolicitada.set(false)
        inicioMs = System.currentTimeMillis()
        totalPasosAudio = 20

        // Promoción inmediata: no se inspeccionan modelos ni se toca disco antes
        // de cumplir el plazo de startForegroundService().
        try {
            val inicial = construirNotificacion("Preparando módulos locales...", 0)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, inicial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, inicial)
            }
        } catch (error: Exception) {
            ejecutando = false
            val mensaje = "Android no permitió iniciar el servicio de audio en primer plano: " +
                (error.message ?: error.javaClass.simpleName)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATE, "failed").putString(KEY_MESSAGE, mensaje).apply()
            runCatching {
                BardoActivityStore.record(this, taskToken, if (modoSolo) "instrumental" else "cancion", "failed", "foreground_service", mensaje, 0)
            }
            Log.e("BARDO-TaskService", mensaje, error)
            stopSelfResult(startId)
            return
        }

        runCatching { heartbeatFile.writeText(inicioMs.toString()) }
            .onFailure { Log.w("BARDO-TaskService", "No se pudo iniciar el latido", it) }
        iniciarLatido()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, "running")
            .putString(KEY_MESSAGE, "BARDO prepara los módulos locales.")
            .putLong("started_at", inicioMs)
            .putInt("process_pid", android.os.Process.myPid())
            .remove(KEY_AUDIO).remove(KEY_MP3).remove(KEY_USER_VOICE_MIXED).apply()

        val entrada = when {
            modoSolo -> Dato.Texto(prompt)
            letraManual.isNotBlank() -> Dato.Texto("[LETRA_USUARIO]\n$letraManual\n\n[CONTEXTO_CREACION]\n$prompt")
            else -> Dato.Texto(prompt)
        }

        // El hash SHA-256 de modelos grandes y la creación del plan se ejecutan
        // en el worker, nunca en el hilo principal del proceso :audio.
        try {
            taskExecutor.execute {
                try {
                    recordTaskProgressSafely(
                        record = {
                            BardoActivityStore.record(
                                this, taskToken,
                                if (modoSolo) "instrumental" else "cancion",
                                "running", "inicio",
                                "BARDO ha iniciado una tarea de creación musical.", 0
                            )
                        },
                        onFailure = { error ->
                            Log.e("BARDO-TaskService", "No se pudo registrar el inicio; la tarea continúa.", error)
                        }
                    )
                    if (cancelacionSolicitada.get()) throw java.util.concurrent.CancellationException("Cancelación solicitada antes de preparar módulos.")

                    val plan = com.musicaia.modelo.BardoModuleCatalog.createMusicPlan(
                        context = applicationContext,
                        instrumentalOnly = modoSolo,
                        manualLyrics = letraManual.isNotBlank(),
                        prompt = prompt,
                        quickTest = pruebaRapida,
                        onSegmentProgress = { actual, total, detalle ->
                            totalPasosAudio = total.coerceAtLeast(1)
                            val transcurrido = ((System.currentTimeMillis() - inicioMs) / 1000L).coerceAtLeast(0L)
                            val mensaje = "INSTRUMENTAL $actual/$total · ${transcurrido}s · $detalle"
                            actualizarNotificacion(mensaje, ((actual * 90f) / total.coerceAtLeast(1)).toInt().coerceIn(0, 90))
                            publicarEstado("running", mensaje)
                        },
                        isCancellationRequested = { cancelacionSolicitada.get() }
                    )
                    if (cancelacionSolicitada.get()) throw java.util.concurrent.CancellationException("Cancelación solicitada durante la preparación.")

                    val modulos = plan.modules
                    recordTaskProgressSafely(
                        record = {
                            BardoActivityStore.record(
                                this, taskToken,
                                if (modoSolo) "instrumental" else "cancion",
                                "running", "plan_seleccionado",
                                "Módulos seleccionados desde el registro de capacidades: " +
                                    plan.selectedModuleIds.joinToString(" → ") + ".", 8
                            )
                        },
                        onFailure = { error ->
                            Log.e("BARDO-TaskService", "No se pudo registrar el plan; la tarea continúa.", error)
                        }
                    )

                    orquestador.setListener(object : Orquestador.ProgresoListener {
                        override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
                            val porcentaje = (progreso * 100f).toInt().coerceIn(0, 100)
                            actualizarNotificacion("$paso · $mensaje", porcentaje)
                            publicarEstado("running", "$paso · $mensaje")
                        }
                        override fun onCompletado(resultados: List<Pair<String, Dato>>) = Unit
                        override fun onError(mensaje: String) { Log.e("BARDO-TaskService", mensaje) }
                    })

                    val resultados = orquestador.ejecutarModulos(modulos, entrada)
                    if (cancelacionSolicitada.get()) throw java.util.concurrent.CancellationException("Tarea cancelada por el usuario.")
                    val audio = resultados.lastOrNull()?.second as? Dato.Audio
                        ?: throw IllegalStateException(if (resultados.isEmpty()) "BARDO no recibió resultados del Core." else "El plan terminó sin producir un WAV instrumental válido.")
                    val archivoInstrumental = java.io.File(audio.rutaWav)
                    val cabeceraInstrumental = BardoWavHeaderValidator.inspect(archivoInstrumental)
                    if (cabeceraInstrumental.dataBytes <= 0L) throw IllegalStateException("El WAV instrumental no contiene muestras PCM válidas.")

                    val letra = (resultados.firstOrNull { it.first == "letra" }?.second as? Dato.Texto)?.texto.orEmpty()
                    val segundos = ((System.currentTimeMillis() - inicioMs) / 1000L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val biblioteca = java.io.File(filesDir, "biblioteca")
                    if (!biblioteca.exists() && !biblioteca.mkdirs()) throw IllegalStateException("No se pudo crear la carpeta Archivo de BARDO.")
                    val marca = System.currentTimeMillis()
                    val estiloOriginal = Regex("(?i)Estilo\\s*:\\s*([^\\n.]+)").find(prompt)?.groupValues?.get(1) ?: prompt.take(36)
                    val nombreBase = BardoLibraryFileNaming.createBaseName(marca, estiloOriginal)
                    if (userVoiceMixAvailable) {
                        val resourceBlock = BardoResourceBudget.blockingReason(
                            applicationContext,
                            1536L * 1024L * 1024L
                        )
                        check(resourceBlock == null) {
                            "No se mezclará la voz para proteger el teléfono. " +
                                (resourceBlock ?: "Presupuesto de memoria no disponible.")
                        }
                    }
                    val archivo = BardoUserVoiceMixPipeline.prepareWav(
                        instrumentalWav = archivoInstrumental,
                        voiceWav = if (userVoiceMixAvailable && userVoiceWavPath != null) {
                            java.io.File(userVoiceWavPath)
                        } else {
                            null
                        },
                        outputWav = java.io.File(biblioteca, "$nombreBase.wav"),
                        isCancellationRequested = { cancelacionSolicitada.get() }
                    )
                    val cabecera = BardoWavHeaderValidator.inspect(archivo)
                    if (cabecera.dataBytes <= 0L) throw IllegalStateException("El WAV final no contiene muestras PCM válidas.")
                    if (userVoiceMixAvailable) {
                        Log.i("BARDO-TaskService", "Voz propia mezclada en WAV real: ${archivo.length()} bytes")
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_AUDIO, archivo.absolutePath).apply()
                    val detalleVoz = if (userVoiceMixAvailable) " con voz propia mezclada" else ""
                    val mensajeMp3 = "WAV PCM16 validado$detalleVoz (${cabecera.sampleRate} Hz, ${cabecera.channels} canales, ${cabecera.dataBytes} bytes de audio). Preparando MP3 320 kbps para Archivo…"
                    actualizarNotificacion(mensajeMp3, 95)
                    publicarEstado("running", mensajeMp3)
                    if (!Mp3Exporter.available) throw IllegalStateException("El WAV se generó, pero el codificador MP3 nativo no está disponible en esta APK.")

                    val mp3 = java.io.File(biblioteca, "$nombreBase.mp3")
                    val temporal = java.io.File(biblioteca, "$nombreBase.mp3.part")
                    val codificado = Mp3Exporter.encodeValidatedWavToMp3(archivo.absolutePath, temporal.absolutePath, 320)
                    if (!codificado || !temporal.isFile || temporal.length() < 1024L) {
                        temporal.delete()
                        throw IllegalStateException("Falló la conversión WAV a MP3 320 kbps o el archivo resultante no es válido.")
                    }
                    if (cancelacionSolicitada.get()) {
                        temporal.delete()
                        throw java.util.concurrent.CancellationException("Cancelación solicitada durante la exportación.")
                    }
                    try {
                        BardoAtomicFilePublisher.publish(temporal, mp3)
                    } catch (error: Exception) {
                        temporal.delete()
                        throw IllegalStateException(
                            "El MP3 se generó, pero no se pudo publicar de forma segura en Archivo.",
                            error
                        )
                    }

                    val mp3DurationMs = runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(mp3.absolutePath)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        } finally { retriever.release() }
                    }.getOrDefault(0L)
                    val minimumMp3DurationMs = if (pruebaRapida) 1_500L else 180_000L
                    if (mp3DurationMs < minimumMp3DurationMs) {
                        mp3.delete()
                        throw IllegalStateException(if (pruebaRapida) "El MP3 de prueba no alcanza 1,5 segundos." else "El MP3 se guardó, pero Android no pudo validar una duración de al menos 3 minutos.")
                    }

                    val mensajeValidado = "INSTRUMENTAL $totalPasosAudio/$totalPasosAudio · MP3 320 kbps validado (${mp3DurationMs / 1000L}s, ${mp3.length()} bytes)"
                    actualizarNotificacion(mensajeValidado, 100)
                    publicarEstado("running", mensajeValidado)
                    if (letra.isNotBlank()) {
                        BardoAtomicFilePublisher.writeTextAndPublish(
                            letra,
                            java.io.File(biblioteca, "$nombreBase.txt")
                        )
                    }
                    val duracion = mp3DurationMs / 1000L
                    val mensajeFinal = "MP3 guardado en Archivo · ${duracion / 60} min ${duracion % 60}s · ${mp3.length()} bytes · 320 kbps"
                    synchronized(completionLock) {
                        try {
                            BardoTaskLifecyclePolicy.ensureCanCommitCompletion(cancelacionSolicitada.get())
                        } catch (cancelled: java.util.concurrent.CancellationException) {
                            // La tarea no se confirma como completada: retirar sus
                            // artefactos recién creados para no dejar una falsa canción final.
                            mp3.delete()
                            java.io.File(biblioteca, "$nombreBase.txt").delete()
                            archivo.delete()
                            throw cancelled
                        }
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_STATE, "completed").putString(KEY_MESSAGE, mensajeFinal)
                            .putString(KEY_AUDIO, archivo.absolutePath).putString(KEY_MP3, mp3.absolutePath).putString("lyrics", letra)
                            .putBoolean(KEY_USER_VOICE_MIXED, userVoiceMixAvailable)
                            .putBoolean("solo", modoSolo).putInt("elapsed", segundos).apply()
                    }
                    recordTaskProgressSafely(
                        record = {
                            BardoActivityStore.record(
                                this,
                                taskToken,
                                if (modoSolo) "instrumental" else "cancion",
                                "completed",
                                "validacion",
                                mensajeFinal,
                                100,
                                mp3.absolutePath
                            )
                        },
                        onFailure = { error ->
                            // El archivo ya está validado y publicado: un fallo del
                            // historial auxiliar no debe convertir una creación válida
                            // en un estado de tarea fallida.
                            Log.e(
                                "BARDO-TaskService",
                                "La tarea terminó, pero no se pudo guardar el evento final en el historial.",
                                error
                            )
                        }
                    )
                    enviarActualizacion("completed", mensajeFinal, archivo.absolutePath, letra, segundos, mp3.absolutePath, userVoiceMixAvailable)
                    actualizarNotificacion(mensajeFinal, 100)
                    Log.i("BARDO-TaskService", "WAV PCM16 válido: ${archivo.length()} bytes; MP3 válido: ${mp3.length()} bytes")
                } catch (error: Throwable) {
                    Log.e("BARDO-TaskService", "Falló la tarea de BARDO", error)
                    val estado = BardoTaskLifecyclePolicy.terminalState(cancelacionSolicitada.get(), error)
                    val detalleError = error.message ?: "Error sin detalle"
                    val mensaje = "${error.javaClass.simpleName}: $detalleError"
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, estado).putString(KEY_MESSAGE, mensaje).apply()
                    runCatching {
                        BardoActivityStore.record(this, taskToken, if (modoSolo) "instrumental" else "cancion", estado,
                            if (estado == "cancelled") "cancelacion" else "fallo", mensaje, null)
                    }.onFailure { Log.e("BARDO-TaskService", "No se pudo guardar el resultado de la tarea", it) }
                    enviarActualizacion(estado, mensaje, "", "", 0)
                } finally {
                    detenerLatido()
                    runCatching { heartbeatFile.delete() }
                    runCatching { orquestador.stop() }
                    // Mantener el bloqueo hasta completar toda la limpieza evita
                    // que una segunda tarea herede el cierre de la anterior.
                    ejecutando = false
                    android.os.Handler(mainLooper).post {
                        if (BardoTaskLifecyclePolicy.mayFinalize(activeTaskId, taskToken, ejecutando)) {
                            activeTaskId = ""
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(latestStartId.coerceAtLeast(taskStartId))
                        }
                    }
                }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            ejecutando = false
            detenerLatido()
            runCatching { heartbeatFile.delete() }
            val mensaje = "El ejecutor local de tareas no está disponible: " + (error.message ?: error.javaClass.simpleName)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, "failed").putString(KEY_MESSAGE, mensaje).apply()
            Log.e("BARDO-TaskService", mensaje, error)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
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
        val progreso = Regex("""INSTRUMENTAL (\d+)/(\d+)""").find(mensaje)?.let { match ->
            val actual = match.groupValues[1].toIntOrNull() ?: 0
            val total = match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            (actual * 100 / total).coerceIn(0, 99)
        }
        val etapa = when {
            mensaje.contains("MP3", true) -> "exportacion"
            mensaje.contains("segment", true) || mensaje.contains("INSTRUMENTAL", true) -> "generacion_audio"
            mensaje.contains("Gemma", true) || mensaje.contains("letra", true) -> "letra"
            else -> "orquestacion"
        }
        recordTaskProgressSafely(
            record = {
                BardoActivityStore.record(
                    this,
                    taskId.ifBlank { "task-unknown" },
                    if (modoSolo) "instrumental" else "cancion",
                    estado,
                    etapa,
                    mensaje,
                    progreso
                )
            },
            onFailure = { error ->
                // El historial es auxiliar: un disco lleno o un JSON no disponible
                // nunca debe abortar la generación de audio ni impedir la UI.
                Log.e("BARDO-TaskService", "No se pudo guardar el progreso; la tarea continúa.", error)
            }
        )
        enviarActualizacion(estado, mensaje, "", "", 0)
    }

    private fun enviarActualizacion(estado: String, mensaje: String, audio: String, lyrics: String, elapsed: Int, mp3: String = "", userVoiceAlreadyMixed: Boolean = false) {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, estado); putExtra(EXTRA_MESSAGE, mensaje); putExtra(EXTRA_AUDIO, audio)
            putExtra(EXTRA_LYRICS, lyrics); putExtra(EXTRA_SOLO, modoSolo); putExtra(EXTRA_ELAPSED, elapsed)
            putExtra(EXTRA_MP3, mp3); putExtra(EXTRA_USER_VOICE_MIXED, userVoiceAlreadyMixed)
        })
    }

    override fun onDestroy() {
        detenerLatido()
        heartbeatExecutor.shutdownNow()
        cancelacionSolicitada.set(true)
        orquestador.cancelar()
        taskExecutor.shutdownNow()
        if (ejecutando) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, "interrupted").putString(KEY_MESSAGE, "Android detuvo el servicio antes de completar la tarea.").apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
