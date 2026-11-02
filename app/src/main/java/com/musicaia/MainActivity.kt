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
import com.musicaia.modelo.Orquestador
import java.io.File
import java.text.Normalizer
import com.musicaia.modulos.ModuloMezcla

class MainActivity : AppCompatActivity() {
 private val etiqueta = "MusicaIA"
 private val handler = Handler(Looper.getMainLooper())
 private var segundos = 0
 private var ocupado = false
 private var player: MediaPlayer? = null
 private var rutaAudio: String? = null
 private var rutaVoz: String? = null
 private var grabadora: GrabadoraWav? = null
 private val orquestador = Orquestador()

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
 if (concedido) alternarGrabacion() else tvEstadoVoz.text = "Sin permiso de micrófono."
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

 btnEnviar.setOnClickListener { enviarConversacion() }
 btnGrabar.setOnClickListener { solicitarMicrofonoYAlternar() }
 btnElegirVoz.setOnClickListener { seleccionarArchivoVoz() }
 btnCrear.setOnClickListener { crearCancion() }
 btnReproducir.setOnClickListener { reproducir() }
 btnGuardar.setOnClickListener { guardarCancion() }

 if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
 == PackageManager.PERMISSION_GRANTED) {
 btnGrabar.isEnabled = true
 } else {
 btnGrabar.isEnabled = true
 btnGrabar.setOnClickListener { solicitarMicrofonoYAlternar() }
 }

 actualizarUI()
 }

 private fun solicitarMicrofonoYAlternar() {
 if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
 == PackageManager.PERMISSION_GRANTED) {
 alternarGrabacion()
 } else {
 permisoMicro.launch(Manifest.permission.RECORD_AUDIO)
 }
 }

 private fun alternarGrabacion() {
 if (grabadora != null) {
 grabadora!!.detener()
 grabadora = null
 btnGrabar.text = "Grabar mi voz"
 tvEstadoVoz.text = "Grabación terminada."
 return
 }
 val dir = getExternalFilesDir(null) ?: filesDir
 val ruta = File(dir, "voz_grabada.wav").absolutePath
 grabadora = GrabadoraWav(ruta)
 try {
 grabadora!!.iniciar()
 btnGrabar.text = "Detener"
 tvEstadoVoz.text = "Grabando..."
 } catch (e: Exception) {
 tvEstadoVoz.text = "Error al grabar: ${e.message}"
 grabadora = null
 }
 }

 private fun copiarVoz(uri: Uri) {
 try {
 val nombre = "voz_usuario.wav"
 val destino = File(getExternalFilesDir(null), nombre)
 contentResolver.openInputStream(uri)?.use { entrada ->
 FileOutputStream(destino).use { salida -> entrada.copyTo(salida) }
 }
 rutaVoz = destino.absolutePath
 tvEstadoVoz.text = "Voz cargada: $nombre"
 } catch (e: Exception) {
 tvEstadoVoz.text = "No se pudo cargar el archivo de voz."
 }
 }

 private fun seleccionarArchivoVoz() {
 selectorVoz.launch(arrayOf("audio/wav", "audio/*"))
 }

 private fun enviarConversacion() {
 val texto = chatEntrada.text.toString().trim()
 if (texto.isEmpty()) return
 respuestas.add(texto)
 chatEntrada.text.clear()
 val indice = respuestas.size - 1
 val pregunta = if (indice < preguntas.size) preguntas[indice] else "¿Algo más?"
 val respuesta = if (indice == 0) "Entendido. Hablemos de ${texto.lowercase()}" else if (indice == 1) "Perfecto, un estilo ${texto.lowercase()} suena bien." else "Gracias. Seguimos construyendo la canción."
 chatLog.append("\nTú: $texto\nMusicaIA: $respuesta")
 if (indice >= preguntas.size - 1) {
 btnCrear.isEnabled = true
 tvEstado.text = "Conversación completa. Puedes crear la canción."
 }
 }

 private fun actualizarUI() {
 btnCrear.isEnabled = true
 }

 private fun guardarFallo(error: Throwable) {
 try {
 val dir = getExternalFilesDir(null) ?: return
 File(dir, "crash.txt").writeText(
 "Crash: ${error.javaClass.simpleName}: ${error.message}\n" +
 error.stackTrace.take(5).joinToString("\n")
 )
 } catch (_: Exception) { }
 }

 private fun mostrarFalloPrevio() {
 try {
 val dir = getExternalFilesDir(null) ?: return
 val f = File(dir, "crash.txt")
 if (f.exists()) {
 tvResultado.text = "Fallo anterior:\n" + f.readText()
 f.delete()
 }
 } catch (_: Exception) { }
 }

 override fun onDestroy() {
 super.onDestroy()
 handler.removeCallbacks(reloj)
 try { grabadora?.detener() } catch (_: Exception) { }
 try { player?.release() } catch (_: Exception) { }
 }

 private fun crearCancion() {
 if (ocupado) return
 ocupado = true
 btnCrear.isEnabled = false
 btnReproducir.visibility = View.GONE
 imgPortada.visibility = View.GONE
 progreso.visibility = View.VISIBLE
 tvEstado.text = "Creando..."
 tvResultado.text = ""
 segundos = 0
 tvTiempo.text = "Tiempo: 0s"
 tvEstadoVoz.text = if (rutaVoz != null) "Voz seleccionada" else "Sin voz: se creará solo el instrumental."
 handler.removeCallbacks(reloj)
 handler.postDelayed(reloj, 1000)

 val tema = editTema.text.toString().trim().ifEmpty { "pop alegre" }
 val estilo = editEstilo.text.toString().trim().ifEmpty { "verano" }
 val entrada = Dato.Texto("Tema: $tema. Estilo: $estilo")

 val usarModo3 = rutaVoz != null && File(rutaVoz!!).exists() && rutaInstrumental != null && File(rutaInstrumental!!).exists()
 if (usarModo3) {
 try {
 val modo3 = MezclaVoz
 val paso = modo3.mezclar(rutaVoz!!, rutaInstrumental!!, File(filesDir, "modo3_mezcla.wav").absolutePath)
 rutaAudio = paso.rutaWav
 runOnUiThread { mostrarResultado(listOf("modo3" to paso)) }
 detenerReloj()
 ocupado = false
 btnCrear.isEnabled = true
 progreso.visibility = View.GONE
 } catch (e: Exception) {
 detenerReloj()
 ocupado = false
 btnCrear.isEnabled = true
 progreso.visibility = View.GONE
 tvEstado.text = "Error en mezcla: ${e.message}"
 tvResultado.text = "No se pudo mezclar los archivos. Verifica que sean WAV válidos de 16 bits mono."
 }
 return
 }

 Thread {
 try {
 val pasos = orquestador.ejecutarModulos(modulos, entrada)
 runOnUiThread { mostrarResultado(pasos) }
 } catch (e: Exception) {
 Log.e(etiqueta, "Error en la creación", e)
 runOnUiThread { mostrarError(e) }
 } finally {
 detenerReloj()
 ocupado = false
 btnCrear.isEnabled = true
 progreso.visibility = View.GONE
 }
 }.start()
 }

 private fun detenerReloj() {
 handler.removeCallbacks(reloj)
 progreso.visibility = View.GONE
 btnCrear.isEnabled = true
 tvEstado.text = "Completado en ${segundos}s"
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
 if (player == null) {
 player = MediaPlayer().apply {
 setDataSource(applicationContext, Uri.fromFile(File(ruta)))
 prepare()
 start()
 setOnCompletionListener { stop(); reset() }
 }
 } else if (!player!!.isPlaying) {
 player!!.start()
 }
 } catch (e: Exception) {
 tvEstado.text = "No se pudo reproducir: ${e.message}"
 }
 }

 private fun guardarCancion() {
 val origen = rutaAudio
 if (origen == null) {
 tvEstado.text = "No hay canción para guardar."
 return
 }
 val nombre = "musicaia_" + System.currentTimeMillis() + ".wav"
 selectorGuardar.launch(nombre)
 }
}
EOF
echo "MainActivity.kt reemplazado (versión completa con 3 modos)"