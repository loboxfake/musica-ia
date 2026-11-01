package com.musicaia

import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.musicaia.modulos.*
import com.musicaia.modelo.*
import java.io.File

class MainActivity : AppCompatActivity(), Orquestador.ProgresoListener {
    private val TAG = "MainActivity"
    private lateinit var btnCrear: Button
    private lateinit var btnReproducir: Button
    private lateinit var tvEstado: TextView
    private lateinit var tvResultado: TextView
    private lateinit var editTema: EditText
    private lateinit var editEstilo: EditText
    private lateinit var imgPortada: ImageView
    private val orquestador = Orquestador()
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCrear = findViewById(R.id.btnCrear)
        btnReproducir = findViewById(R.id.btnReproducir)
        tvEstado = findViewById(R.id.estado)
        tvResultado = findViewById(R.id.textoResultado)
        editTema = findViewById(R.id.editTema)
        editEstilo = findViewById(R.id.editEstilo)
        imgPortada = findViewById(R.id.imgPortada)

        orquestador.setListener(this)

        btnCrear.setOnClickListener {
            try {
                if (orquestador.isRunning) return@setOnClickListener
                iniciarCreacion()
            } catch (e: Exception) {
                Log.e(TAG, "Error botón Crear", e)
                tvEstado.text = "Error: ${e.message}"
                tvResultado.text = "Fallo: ${e.message}"
            }
        }

        btnReproducir.setOnClickListener {
            try {
                val ruta = tvResultado.tag?.toString()
                if (ruta != null && File(ruta).exists()) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(ruta)
                        prepare()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reproducir", e)
                Toast.makeText(this, "No se pudo reproducir", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun iniciarCreacion() {
        btnCrear.isEnabled = false
        btnReproducir.visibility = android.view.View.GONE
        imgPortada.visibility = android.view.View.GONE
        tvEstado.text = "Iniciando..."
        tvResultado.text = ""

        val tema = editTema.text.toString().ifBlank { "tema: pop alegre" }
        val estilo = editEstilo.text.toString().ifBlank { "estilo: verano" }
        val entradaInicial = Dato.Texto("Tema: $tema Estilo: $estilo")

        val modulos = listOf(
            ModuloLetraStub(),
            ModuloInstrumentalStub(this),
            ModuloVozStub(this),
            ModuloPortadaStub(this),
            ModuloMezclaStub(this)
        )

        Thread {
            try {
                val resultado = orquestador.ejecutarModulos(modulos, entradaInicial)
                runOnUiThread {
                    btnCrear.isEnabled = true
                    if (resultado != null) {
                        tvEstado.text = "¡Completado!"
                        when (resultado) {
                            is Dato.Texto -> {
                                tvResultado.text = "Letra:\n${resultado.texto}"
                                tvResultado.tag = null
                            }
                            is Dato.Audio -> {
                                tvResultado.text = "Audio: ${resultado.rutaWav} (${resultado.frecuenciaMuestreo} Hz)"
                                tvResultado.tag = resultado.rutaWav
                                btnReproducir.visibility = android.view.View.VISIBLE
                            }
                            is Dato.Imagen -> {
                                tvResultado.text = "Portada: ${resultado.rutaPng}"
                                tvResultado.tag = null
                                imgPortada.visibility = android.view.View.VISIBLE
                                imgPortada.setImageURI(android.net.Uri.fromFile(java.io.File(resultado.rutaPng)))
                            }
                        }
                    } else {
                        tvEstado.text = "Sin resultado"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ejecución", e)
                runOnUiThread {
                    btnCrear.isEnabled = true
                    tvEstado.text = "Error"
                    tvResultado.text = "Error: ${e.message}"
                }
            }
        }.start()
    }

    override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
        runOnUiThread { tvEstado.text = "$paso ($mensaje)" }
    }

    override fun onCompletado(resultado: Dato) {
        runOnUiThread {
            btnCrear.isEnabled = true
            tvEstado.text = "¡Completado!"
        }
    }

    override fun onError(mensaje: String) {
        runOnUiThread {
            btnCrear.isEnabled = true
            tvEstado.text = "Error"
            tvResultado.text = "Error: $mensaje"
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val dir = getExternalFilesDir(null)
            if (dir != null) {
                val f = File(dir, "audio_ultimo.wav")
                // Guardar referencia si aplica
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error", e)
        }
    }
}