package com.musicaia

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.musicaia.modulos.*
import com.musicaia.modelo.*
import com.musicaia.modelo.Orquestador.ProgresoListener

class MainActivity : AppCompatActivity(), ProgresoListener {
    private val TAG = "MainActivity"
    private lateinit var btnCrear: Button
    private lateinit var tvEstado: TextView
    private lateinit var tvResultado: TextView
    private val orquestador = Orquestador()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCrear = findViewById(R.id.btnCrear)
        tvEstado = findViewById(R.id.estado)
        tvResultado = findViewById(R.id.textoResultado)

        orquestador.setListener(this)

        btnCrear.setOnClickListener {
            if (!orquestador.isRunning) { // Simplificación
                iniciarCreacion()
            }
        }
    }

    private fun iniciarCreacion() {
        btnCrear.isEnabled = false
        tvEstado.text = "Iniciando..."
        tvResultado.text = ""

        // Crear módulos stub
        val modulos = listOf(
            ModuloLetraStub(),
            ModuloInstrumentalStub(this),
            ModuloVozStub(this),
            ModuloPortadaStub(this),
            ModuloMezclaStub(this)
        )

        // Dato inicial (tema/estilo vacío para prueba)
        val entradaInicial = Dato.Texto("tema: pop alegre, estilo: verano")

        // Ejecutar en hilo separado
        Thread {
            val resultado = orquestador.ejecutarModulos(modulos, entradaInicial)
            if (resultado != null) {
                runOnUiThread {
                    btnCrear.isEnabled = true
                    tvEstado.text = "¡Completado!"
                    when (resultado) {
                        is Dato.Texto -> tvResultado.text = "Letra generada:\n${resultado.texto}"
                        is Dato.Audio -> tvResultado.text = "Audio generado: ${resultado.rutaWav}"
                        is Dato.Imagen -> tvResultado.text = "Imagen generada: ${resultado.rutaPng}"
                    }
                }
            }
        }.start()
    }

    // Implementación de ProgresoListener
    override fun onProgreso(paso: String, progreso: Float, mensaje: String) {
        runOnUiThread {
            tvEstado.text = "$paso ($mensaje)"
        }
    }

    override fun onCompletado(resultado: Dato) {
        runOnUiThread {
            btnCrear.isEnabled = true
            tvEstado.text = "¡Completado!"
            when (resultado) {
                is Dato.Texto -> tvResultado.text = "Letra generada:\n${resultado.texto}"
                is Dato.Audio -> tvResultado.text = "Audio generado: ${resultado.rutaWav} (${resultado.frecuenciaMuestreo} Hz)"
                is Dato.Imagen -> tvResultado.text = "Imagen generada: ${resultado.rutaPng}"
            }
            Toast.makeText(this, "Creación finalizada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(mensaje: String) {
        runOnUiThread {
            btnCrear.isEnabled = true
            tvEstado.text = "Error"
            tvResultado.text = "Error: $mensaje"
            Toast.makeText(this, "Error: $mensaje", Toast.LENGTH_LONG).show()
        }
    }
}