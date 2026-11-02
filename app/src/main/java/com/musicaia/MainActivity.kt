package com.musicaia

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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.musicaia.modelo.*
import com.musicaia.modulos.*
import java.io.File

class MainActivity : AppCompatActivity() {
 private val etiqueta = "MusicaIA"
 private val orquestador = Orquestador()
 private val handler = Handler(Looper.getMainLooper())
 private var segundos = 0
 private var player: MediaPlayer? = null
 private var rutaAudio: String? = null

 private lateinit var btnCrear: Button
 private lateinit var btnReproducir: Button
 private lateinit var tvEstado: TextView
 private lateinit var tvTiempo: TextView
 private lateinit var tvResultado: TextView
 private lateinit var editTema: EditText
 private lateinit var editEstilo: EditText
 private lateinit var imgPortada: ImageView
 private lateinit var progreso: ProgressBar

 private val reloj = object : Runnable {
 override fun run() {
 segundos++
 tvTiempo.text = "Tiempo: ${segundos}s"
 handler.postDelayed(this, 1000)
 }
 }

 override fun onCreate(savedInstanceState: Bundle?) {
 super.onCreate(savedInstanceState)
 setContentView(R.layout.activity_main)
 btnCrear = findViewById(R.id.btnCrear)
 btnReproducir = findViewById(R.id.btnReproducir)
 tvEstado = findViewById(R.id.estado)
 tvTiempo = findViewById(R.id.tiempo)
 tvResultado = findViewById(R.id.textoResultado)
 editTema = findViewById(R.id.editTema)
 editEstilo = findViewById(R.id.editEstilo)
 imgPortada = findViewById(R.id.imgPortada)
 progreso = findViewById(R.id.progreso)

 val anterior = Thread.getDefaultUncaughtExceptionHandler()
 Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
 guardarFallo(error)
 anterior?.uncaughtException(hilo, error)
 }
 mostrarFalloPrevio()

 btnCrear.setOnClickListener {
 tvEstado.text = "Botón pulsado"
 Log.d(etiqueta, "click")
 if (!orquestador.isRunning) crear()
 }
 btnReproducir.setOnClickListener { reproducir() }
 }

 private fun crear() {
 btnCrear.isEnabled = false
 btnReproducir.visibility = View.GONE
 imgPortada.visibility = View.GONE
 progreso.visibility = View.VISIBLE
 tvTiempo.visibility = View.VISIBLE
 tvEstado.text = "Creando..."
 tvResultado.text = ""
 segundos = 0
 tvTiempo.text = "Tiempo: 0s"
 handler.removeCallbacks(reloj)
 handler.postDelayed(reloj, 1000)

 val tema = editTema.text.toString().trim().ifEmpty { "pop alegre" }
 val estilo = editEstilo.text.toString().trim().ifEmpty { "verano" }
 val entrada = Dato.Texto("Tema: $tema. Estilo: $estilo")
 val modulos = listOf(
 ModuloLetra(this), ModuloInstrumental(this),
 ModuloVoz(this), ModuloPortada(this), ModuloMezcla(this)
 )

 Thread {
 try {
 val pasos = orquestador.ejecutarModulos(modulos, entrada)
 runOnUiThread { mostrarResultado(pasos) }
 } catch (e: Exception) {
 Log.e(etiqueta, "Error en la creación", e)
 runOnUiThread { mostrarError(e) }
 }
 }.start()
 }

 private fun detenerReloj() {
 handler.removeCallbacks(reloj)
 progreso.visibility = View.GONE
 btnCrear.isEnabled = true
 }

 private fun mostrarResultado(pasos: List<Pair<String, Dato>>) {
 detenerReloj()
 val sb = StringBuilder()
 for ((id, dato) in pasos) {
 when (dato) {
 is Dato.Texto -> sb.append("[$id]\n${dato.texto}\n\n")
 is Dato.Audio -> {
 sb.append("[$id] Audio: ${dato.rutaWav}\n\n")
 rutaAudio = dato.rutaWav
 btnReproducir.visibility = View.VISIBLE
 }
 is Dato.Imagen -> {
 sb.append("[$id] Portada: ${dato.rutaPng}\n\n")
 imgPortada.setImageURI(Uri.fromFile(File(dato.rutaPng)))
 imgPortada.visibility = View.VISIBLE
 }
 }
 }
 tvResultado.text = sb.toString()
 tvEstado.text = "Completado en ${segundos}s"
 }

 private fun mostrarError(e: Exception) {
 detenerReloj()
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
 } catch (_: Exception) {
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
 } catch (_: Exception) {
 }
 }

 private fun exportarCancion() {
 val dirDest = File("/sdcard/Download/app música/apk/")
 dirDest.mkdirs()
 val fuente = File(getExternalFilesDir(null), "mezcla_final.wav")
 if (fuente.exists()) {
 fuente.copyTo(File(dirDest, "musica_ia_cancion_final.wav"), overwrite = true)
 tvEstado.text = "Exportado a ${dirDest.absolutePath}"
 }
 }

 override fun onDestroy() {
 super.onDestroy()
 handler.removeCallbacks(reloj)
 player?.release()
 }
}