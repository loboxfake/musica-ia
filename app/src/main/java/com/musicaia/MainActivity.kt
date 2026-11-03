package com.musicaia

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.musicaia.modelo.Dato
import com.musicaia.modulos.GrabadoraWav
import com.musicaia.modulos.LectorWav
import com.musicaia.modulos.MezclaVoz
import com.musicaia.modulos.ModuloInstrumental
import com.musicaia.modulos.ModuloLetra
import com.musicaia.modulos.ModuloPortada
import java.io.File
import java.text.Normalizer

class MainActivity : AppCompatActivity() {
    private val etiqueta = "MusicaIA"
    private val handler = Handler(Looper.getMainLooper())
    private var segundos = 0
    private var ocupado = false
    private var player: MediaPlayer? = null
    private var rutaAudio: String? = null
    private var rutaVoz: String? = null
    private var grabadora: GrabadoraWav? = null
    private var rutaGrabacion: String? = null

    private lateinit var grupoModo: RadioGroup
    private lateinit var panelConversacion: View
    private lateinit var panelLetra: View
    private lateinit var chatLog: TextView
    private lateinit var chatEntrada: EditText
    private lateinit var btnEnviar: Button
    private lateinit var editLetra: EditText
    private lateinit var editIndicaciones: EditText
    private lateinit var btnGrabar: Button
    private lateinit var btnElegirVoz: Button
    private lateinit var tvEstadoVoz: TextView
    private lateinit var btnCrear: Button
    private lateinit var btnReproducir: Button
    private lateinit var btnGuardar: Button
    private lateinit var tvEstado: TextView
    private lateinit var tvTiempo: TextView
    private lateinit var tvResultado: TextView
    private lateinit var imgPortada: ImageView
    private lateinit var progreso: ProgressBar

    private val preguntas = listOf(
        "¿De qué quieres que trate la canción?",
        "¿Qué estilo o género quieres? (pop, rock, balada, electrónica...)",
        "¿Qué ánimo? (alegre, triste, épico, tranquilo...)",
        "¿Qué ritmo? (lento, medio o rápido)",
        "¿En qué idioma? (español, inglés...)"
    )
    private val respuestas = mutableListOf<String>()

    private val reloj = object : Runnable {
        override fun run() {
            segundos++
            tvTiempo.text = "Tiempo: ${segundos}s"
            handler.postDelayed(this, 1000)
        }
    }

    private val permisoMicro =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                alternarGrabacion()
            } else {
                tvEstadoVoz.text = "Sin permiso de micrófono."
            }
        }

    private val selectorVoz =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) copiarVoz(uri)
        }

    private val selectorGuardar =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            val origen = rutaAudio
            if (uri != null && origen != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { salida ->
                        File(origen).inputStream().use { it.copyTo(salida) }
                    }
                    tvEstado.text = "Canción guardada."
                } catch (e: Exception) {
                    tvEstado.text = "No se pudo guardar: ${e.message}"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grupoModo = findViewById(R.id.grupoModo)
        panelConversacion = findViewById(R.id.panelConversacion)
        panelLetra = findViewById(R.id.panelLetra)
        chatLog = findViewById(R.id.chatLog)
        chatEntrada = findViewById(R.id.chatEntrada)
        btnEnviar = findViewById(R.id.btnEnviar)
        editLetra = findViewById(R.id.editLetra)
        editIndicaciones = findViewById(R.id.editIndicaciones)
        btnGrabar = findViewById(R.id.btnGrabar)
        btnElegirVoz = findViewById(R.id.btnElegirVoz)
        tvEstadoVoz = findViewById(R.id.estadoVoz)
        btnCrear = findViewById(R.id.btnCrear)
        btnReproducir = findViewById(R.id.btnReproducir)
        btnGuardar = findViewById(R.id.btnGuardar)
        tvEstado = findViewById(R.id.estado)
        tvTiempo = findViewById(R.id.tiempo)
        tvResultado = findViewById(R.id.textoResultado)
        imgPortada = findViewById(R.id.imgPortada)
        progreso = findViewById(R.id.progreso)

        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            guardarFallo(error)
            anterior?.uncaughtException(hilo, error)
        }
        mostrarFalloPrevio()

        grupoModo.setOnCheckedChangeListener { _, id ->
            panelConversacion.visibility = if (id == R.id.modoConversacion) View.VISIBLE else View.GONE
            panelLetra.visibility = if (id == R.id.modoLetra) View.VISIBLE else View.GONE
        }
        btnEnviar.setOnClickListener { enviarRespuesta() }
        btnGrabar.setOnClickListener { pulsarGrabar() }
        btnElegirVoz.setOnClickListener { selectorVoz.launch(arrayOf("audio/*")) }
        btnCrear.setOnClickListener { crear() }
        btnReproducir.setOnClickListener { reproducir() }
        btnGuardar.setOnClickListener { selectorGuardar.launch("cancion_musicaia.wav") }

        iniciarConversacion()
    }

    // ---------- Conversación guiada (por reglas, sin IA) ----------

    private fun iniciarConversacion() {
        respuestas.clear()
        chatLog.text = "Asistente: Vamos a preparar tu canción.\nAsistente: ${preguntas[0]}"
    }

    private fun enviarRespuesta() {
        val texto = chatEntrada.text.toString().trim()
        if (texto.isEmpty()) return
        chatEntrada.setText("")
        chatLog.append("\nTú: $texto")
        if (texto.equals("cambiar", ignoreCase = true)) {
            iniciarConversacion()
            return
        }
        if (respuestas.size >= preguntas.size) {
            chatLog.append("\nAsistente: Ya tengo todo. Pulsa «Crear canción» o escribe «cambiar».")
            return
        }
        respuestas.add(texto)
        if (respuestas.size < preguntas.size) {
            chatLog.append("\nAsistente: ${preguntas[respuestas.size]}")
        } else {
            chatLog.append(
                "\nAsistente: Perfecto. Resumen: tema «${respuestas[0]}», estilo ${respuestas[1]}, " +
                    "ánimo ${respuestas[2]}, ritmo ${respuestas[3]}, idioma ${respuestas[4]}." +
                    "\nAsistente: Pulsa «Crear canción» cuando quieras, o escribe «cambiar» para empezar de nuevo."
            )
        }
    }

    // ---------- Voz del usuario ----------

    private fun pulsarGrabar() {
        val permiso = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permiso == PackageManager.PERMISSION_GRANTED) {
            alternarGrabacion()
        } else {
            permisoMicro.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun alternarGrabacion() {
        val actual = grabadora
        if (actual == null) {
            val dir = getExternalFilesDir(null) ?: filesDir
            val ruta = File(dir, "voz_usuario.wav").absolutePath
            val g = GrabadoraWav(ruta)
            try {
                g.iniciar()
                grabadora = g
                rutaGrabacion = ruta
                btnGrabar.text = "Parar grabación"
                tvEstadoVoz.text = "Grabando... canta o habla cerca del micrófono."
            } catch (e: Exception) {
                tvEstadoVoz.text = "No se pudo grabar: ${e.message}"
            }
        } else {
            actual.detener()
            grabadora = null
            btnGrabar.text = "Grabar mi voz"
            rutaVoz = rutaGrabacion
            tvEstadoVoz.text = "Voz guardada. Se mezclará con el instrumental."
        }
    }

    private fun copiarVoz(uri: Uri) {
        try {
            val destino = File(getExternalFilesDir(null) ?: filesDir, "voz_usuario.wav")
            val entrada = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("no se pudo abrir el archivo")
            entrada.use { ent -> destino.outputStream().use { sal -> ent.copyTo(sal) } }
            LectorWav.leer(destino.absolutePath)
            rutaVoz = destino.absolutePath
            tvEstadoVoz.text = "Archivo de voz cargado. Se mezclará con el instrumental."
        } catch (e: Exception) {
            rutaVoz = null
            tvEstadoVoz.text = "No sirve ese archivo (${e.message}). Usa un WAV de 16 bits o «Grabar mi voz»."
        }
    }

    // ---------- Crear la canción ----------

    private fun sinTildes(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun crear() {
        if (ocupado) return
        if (grabadora != null) {
            tvEstado.text = "Para primero la grabación de voz."
            return
        }
        val tema: String
        val indicaciones: String
        var letraUsuario: String? = null
        if (grupoModo.checkedRadioButtonId == R.id.modoConversacion) {
            if (respuestas.size < preguntas.size) {
                tvEstado.text = "Termina primero la conversación (responde a todas las preguntas)."
                return
            }
            tema = respuestas[0]
            indicaciones = respuestas.subList(1, 5).joinToString(", ")
        } else {
            val l = editLetra.text.toString().trim()
            if (l.isEmpty()) {
                tvEstado.text = "Escribe o pega tu letra."
                return
            }
            letraUsuario = l
            tema = l.lines().first().trim().take(40)
            indicaciones = editIndicaciones.text.toString().trim().ifEmpty { "pop" }
        }
        val entrada = "Tema: $tema. Estilo: ${sinTildes(indicaciones)}"
        val letraFija = letraUsuario
        val vozRuta = rutaVoz

        prepararUi()
        Thread {
            try {
                val letra: String = letraFija ?: run {
                    val m = ModuloLetra(this)
                    m.cargar()
                    val r = m.ejecutar(Dato.Texto(entrada))
                    m.descargar()
                    (r as? Dato.Texto)?.texto ?: ""
                }

                val inst = ModuloInstrumental(this)
                inst.cargar()
                val audio = inst.ejecutar(Dato.Texto(entrada)) as? Dato.Audio
                    ?: throw IllegalStateException("El instrumental no devolvió audio")
                inst.descargar()

                var rutaFinal = audio.rutaWav
                var nota = "Solo instrumental (sin tu voz)."
                if (vozRuta != null) {
                    val salida = File(getExternalFilesDir(null) ?: filesDir, "cancion_con_voz.wav").absolutePath
                    MezclaVoz.mezclar(vozRuta, audio.rutaWav, salida)
                    rutaFinal = salida
                    nota = "Instrumental mezclado con tu voz."
                }

                var rutaPortada: String? = null
                try {
                    val p = ModuloPortada(this)
                    if (p.cargar()) {
                        rutaPortada = (p.ejecutar(Dato.Texto(letra)) as? Dato.Imagen)?.rutaPng
                        p.descargar()
                    }
                } catch (e: Exception) {
                    Log.e(etiqueta, "Portada", e)
                }

                val portadaFinal = rutaPortada
                val audioFinal = rutaFinal
                val notaFinal = nota
                runOnUiThread { mostrar(letra, audioFinal, portadaFinal, notaFinal) }
            } catch (e: Throwable) {
                Log.e(etiqueta, "Error en la creación", e)
                runOnUiThread { mostrarError(e) }
            }
        }.start()
    }

    private fun prepararUi() {
        ocupado = true
        btnCrear.isEnabled = false
        btnReproducir.visibility = View.GONE
        btnGuardar.visibility = View.GONE
        imgPortada.visibility = View.GONE
        progreso.visibility = View.VISIBLE
        progreso.isIndeterminate = true
        tvTiempo.visibility = View.VISIBLE
        tvEstado.text = "Creando..."
        tvResultado.text = ""
        segundos = 0
        tvTiempo.text = "Tiempo: 0s"
        handler.removeCallbacks(reloj)
        handler.postDelayed(reloj, 1000)
    }

    private fun terminarUi() {
        handler.removeCallbacks(reloj)
        progreso.visibility = View.GONE
        btnCrear.isEnabled = true
        ocupado = false
    }

    private fun mostrar(letra: String, rutaFinal: String, rutaPortada: String?, nota: String) {
        terminarUi()
        rutaAudio = rutaFinal
        btnReproducir.visibility = View.VISIBLE
        btnGuardar.visibility = View.VISIBLE
        if (rutaPortada != null && File(rutaPortada).exists()) {
            imgPortada.setImageURI(Uri.fromFile(File(rutaPortada)))
            imgPortada.visibility = View.VISIBLE
        }
        tvResultado.text = "Letra:\n$letra\n\n$nota\nArchivo: $rutaFinal"
        tvEstado.text = "Completado en ${segundos}s"
    }

    private fun mostrarError(e: Throwable) {
        terminarUi()
        tvEstado.text = "Error"
        tvResultado.text = "Error: ${e.javaClass.simpleName}: ${e.message}\n" +
            e.stackTrace.take(5).joinToString("\n")
    }

    private fun reproducir() {
        val ruta = rutaAudio ?: return
        try {
            player?.release()
            player = MediaPlayer().apply { setDataSource(ruta); prepare(); start() }
        } catch (e: Exception) {
            Log.e(etiqueta, "Error al reproducir", e)
            tvEstado.text = "No se pudo reproducir: ${e.message}"
        }
    }

    private fun guardarFallo(error: Throwable) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            File(dir, "crash.txt").writeText(
                "Crash: ${error.javaClass.simpleName}: ${error.message}\n" +
                    error.stackTrace.take(5).joinToString("\n")
            )
        } catch (e: Exception) {
            Log.e(etiqueta, "No se pudo guardar el fallo", e)
        }
    }

    private fun mostrarFalloPrevio() {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val f = File(dir, "crash.txt")
            if (f.exists()) {
                tvResultado.text = "Fallo anterior:\n" + f.readText()
                f.delete()
            }
        } catch (e: Exception) {
            Log.e(etiqueta, "No se pudo leer el fallo previo", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reloj)
        player?.release()
    }
}
