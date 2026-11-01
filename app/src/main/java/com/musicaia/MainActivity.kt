package com.musicaia

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.musicaia.modulos.*
import com.musicaia.modelo.*
import java.io.File
import java.io.PrintWriter

class MainActivity : AppCompatActivity(), Orquestador.ProgresoListener {

    private val TAG = "MainActivity"
    private lateinit var btnCrear: Button
    private lateinit var btnReproducir: Button
    private lateinit var tvEstado: TextView
    private lateinit var tvResultado: TextView
    private lateinit var editTema: EditText
    private lateinit var editEstilo: EditText
    private lateinit var imgPortada: ImageView
    private lateinit var progreso: ProgressBar
    private lateinit var tvTiempo: TextView
    private val orquestador = Orquestador()
    private val handler = Handler(android.os.Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var segundos: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            segundos++
            tvTiempo.text = "Tiempo: ${segundos}s"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            crashHandlerSaved = Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                if (crashHandlerSaved != null) crashHandlerSaved(e)
                else Log.e(TAG, "No prev handler")
                val dir = getExternalFilesDir(null)
                    val crashFile = java.io.File(dir ?: return@setDefaultUncaughtExceptionHandler, "crash.txt")
                    val st = e.stackTrace.take(5).joinToString("\n") { it.toString() }
                    crashFile.writeText("Crash: ${e.javaClass.simpleName}: ${e.message}\n$st")
                } catch (ex: Exception) { Log.e(TAG, "crash save fail", ex) }
            }
        } catch (ex: Exception) { Log.e(TAG, "handler fail", ex) }

        setContentView(R.layout.activity_main)

        btnCrear = findViewById(R.id.btnCrear)
        btnReproducir = findViewById(R.id.btnReproducir)
        tvEstado = findViewById(R.id.estado)
        tvResultado = findViewById(R.id.textoResultado)
        editTema = findViewById(R.id.editTema)
        editEstilo = findViewById(R.id.editEstilo)
        imgPortada = findViewById(R.id.imgPortada)
        progreso = findViewById(R.id.progreso)
        tvTiempo = findViewById(R.id.tiempo)


        // Leer crash previo y mostrarlo
        try {
            val crashFile = java.io.File(getExternalFilesDir(null), "crash.txt")
            if (crashFile.exists()) {
                tvResultado.text = "Crash previo:\n" + crashFile.readText()
                crashFile.delete()
            }
        } catch (ex: Exception) { Log.e(TAG, "crash read fail", ex) }

        // Leer crash previo al abrir
        try {
            val crashFile = java.io.File(getExternalFilesDir(null) ?: return, "crash.txt")
            if (crashFile.exists()) {
                val msg = crashFile.readText()
                tvEstado.text = "Crash prev: \${msg.take(200)}"
                crashFile.delete()
            }
        } catch (ex: Exception) { Log.e(TAG, "crash read fail", ex) }

        orquestador.setListener(this)

        btnCrear.setOnClickListener {
            tvEstado.text = "Botón pulsado"
            Log.d("MusicaIA", "click: ${System.currentTimeMillis()}")
            try {
                if (orquestador.isRunning) return@setOnClickListener
                iniciarCreacion()
            } catch (e: Exception) {
                Log.e(TAG, "Error botón Crear", e)
                tvEstado.text = "Error: ${e.message}"
            } finally {
                btnCrear.isEnabled = true
            }
        }

        btnReproducir.setOnClickListener {
            try {
                val ruta = tvResultado.tag?.toString()
                if (ruta != null && File(ruta).exists()) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply { setDataSource(ruta); prepare(); start() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reproducir", e)
                Toast.makeText(this, "No se pudo reproducir", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun iniciarCreacion() {
        segundos = 0
        btnCrear.isEnabled = false
        btnReproducir.visibility = android.view.View.GONE
        imgPortada.visibility = android.view.View.GONE
        progreso.visibility = android.view.View.VISIBLE
        tvTiempo.visibility = android.view.View.VISIBLE
        tvTiempo.text = "Tiempo: 0s"
        tvResultado.text = ""
        tvEstado.text = "Iniciando..."
        tvEstado.text = tvEstado.text.toString() + " (Estimado: ~10 s)"

        val tema = editTema.text.toString().ifBlank { "pop alegre" }
        val estilo = editEstilo.text.toString().ifBlank { "verano" }
        val entradaInicial = Dato.Texto("Tema: $tema Estilo: $estilo")

        val modulos = listOf(
            ModuloLetraStub(), ModuloInstrumentalStub(this),
            ModuloVozStub(this), ModuloPortadaStub(this), ModuloMezclaStub(this)
        )

        handler.postDelayed(timerRunnable, 1000)

        Thread {
            try {
                val resultados = orquestador.ejecutarModulos(modulos, entradaInicial)
                runOnUiThread {
                    handler.removeCallbacks(timerRunnable)
                    progreso.visibility = android.view.View.GONE
                    imprimirResultados(resultados)
                    estadoCompletado()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ejecución", e)
                runOnUiThread {
                    handler.removeCallbacks(timerRunnable)
                    progreso.visibility = android.view.View.GONE
                    tvEstado.text = "Error"
                    tvResultado.text = "Error: ${e.message}"
                    btnCrear.isEnabled = true
                }
            }
        }.start()
    }

    private fun imprimirResultados(resultados: List<Pair<String, Dato>>) {
        for ((id, dato) in resultados) {
            when (dato) {
                is Dato.Texto -> Log.d("MusicaIA", "Resultado $id: letra con ${dato.texto.length} chars")
                is Dato.Audio -> {
                    Log.d("MusicaIA", "Resultado $id: audio ${dato.rutaWav}")
                    tvResultado.tag = dato.rutaWav
                    tvResultado.text = "Audio: ${dato.rutaWav} (${dato.frecuenciaMuestreo} Hz)"
                    tvResultado.tag = dato.rutaWav
                    btnReproducir.visibility = android.view.View.VISIBLE
                }
                is Dato.Imagen -> {
                    Log.d("MusicaIA", "Resultado $id: portada ${dato.rutaPng}")
                    tvResultado.tag = null
                    tvResultado.text = "Portada: ${dato.rutaPng}"
                    tvResultado.tag = null
                    imgPortada.visibility = android.view.View.VISIBLE
                    imgPortada.setImageURI(android.net.Uri.fromFile(java.io.File(dato.rutaPng)))
                }
            }
        }
    }

    override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
        runOnUiThread { tvEstado.text = "$paso ($mensaje)" }
    }

    override fun onCompletado(resultados: List<Pair<String, Dato>>) {
        runOnUiThread {
            estadoCompletado()
        }
    }

    private fun estadoCompletado() {
        btnCrear.isEnabled = true
        val elapsed = segundos.toDouble()
        val totalEsperado = 10.0 // 5 módulos × 2 s, default
        tvEstado.text = "¡Completado! (${String.format("%.1f", elapsed)}s / ~${totalEsperado}s)"
    }

    override fun onError(mensaje: String) {
        runOnUiThread {
            btnCrear.isEnabled = true
            tvEstado.text = "Error"
            tvResultado.text = "Error: $mensaje"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        timerRunnable = null
        mediaPlayer?.release()
        try {
            getExternalFilesDir(null)?.let { dir ->
                dir.listFiles { file -> file.name == "portada_*.png" || file.name == "silencio_*.wav" }.forEach { it.delete() }
            }
        } catch (e: Exception) { Log.e(TAG, "cleanup fail", e) }
    }

    inner class TimerTaskImpl : Runnable {
        override fun run() {
            if (segundos == 0 || !orquestador.isRunning) return
            val s = segundos
            tvTiempo.text = "Tiempo: $s s"
            handler.postDelayed(this, 1000L)
        }
    }
}