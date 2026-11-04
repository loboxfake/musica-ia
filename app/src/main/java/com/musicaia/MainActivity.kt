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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.musicaia.modelo.Dato
import com.musicaia.modulos.GrabadoraWav
import com.musicaia.modulos.LectorWav
import com.musicaia.modulos.MezclaVoz
import com.musicaia.modulos.ModuloLetra
import com.musicaia.modelo.Orquestador
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import com.musicaia.modulos.ModuloMezcla
import com.musicaia.modulos.AudioGenNative
import com.musicaia.modulos.Mp3Exporter
import com.musicaia.modulos.ModuloInstrumental
import com.musicaia.modulos.DescargadorModelos
import com.musicaia.modulos.litert.EngineLiteRT
import com.musicaia.modulos.litert.LiteRTLMModule

class MainActivity : AppCompatActivity() {
 private val etiqueta = "MusicaIA"
 private val handler = Handler(Looper.getMainLooper())
 private var segundos = 0
 private var ocupado = false
 private var player: MediaPlayer? = null
 private var rutaAudio: String? = null
 private var rutaExportacion: String? = null
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
 private lateinit var btnDescargarModelo: Button
 private lateinit var estadoModelo: TextView
 private lateinit var progresoModelo: ProgressBar
 private var descargaEnCurso = false

 private var rutaInstrumental: String? = null

 private val datosConver = mutableMapOf<String, String>()
 private val camposRequeridos = listOf("tema", "estilo", "ambiente", "ritmo", "idioma")
 private val camposOpcionales = listOf("tipo", "vozFuente")
 private val notaIA = "La conversación recoge tus preferencias; la letra se genera con un modelo de lenguaje local en el teléfono."

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
 registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
 val origen = rutaExportacion
 if (uri != null && origen != null) {
 try {
 val salida = contentResolver.openOutputStream(uri)
     ?: throw IllegalStateException("No se pudo abrir el destino de exportación.")
 salida.use { destino ->
 File(origen).inputStream().use { it.copyTo(destino) }
 }
 tvEstado.text = "Canción exportada en MP3 a 320 kbps."
 } catch (e: Exception) {
 tvEstado.text = "No se pudo guardar el MP3: ${e.message}"
 } finally {
 File(origen).delete()
 rutaExportacion = null
 }
 } else {
 origen?.let { File(it).delete() }
 rutaExportacion = null
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
 btnDescargarModelo = findViewById(R.id.btnDescargarModelo)
 estadoModelo = findViewById(R.id.estadoModelo)
 progresoModelo = findViewById(R.id.progresoModelo)

 val anterior = Thread.getDefaultUncaughtExceptionHandler()
 Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
 guardarFallo(error)
 anterior?.uncaughtException(hilo, error)
 }
 mostrarFalloPrevio()

 grupoModo.setOnCheckedChangeListener { _, id ->
 panelConversacion.visibility = if (id == R.id.modoConversacion) View.VISIBLE else View.GONE
 panelLetra.visibility = if (id == R.id.modoLetra) View.VISIBLE else View.GONE
 actualizarUI()
 actualizarEstadoModelo()
 if (chatLog.text.isNullOrBlank()) chatLog.text = "MusicaIA: Vamos a preparar tu canción. ¿De qué quieres que trate?"
 editLetra.addTextChangedListener { actualizarUI() }
 }

 btnEnviar.setOnClickListener { enviarConversacion() }
 btnDescargarModelo.setOnClickListener { confirmarDescargaModelo() }
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
    chatEntrada.text.clear()

    if (panelLetra.visibility == View.VISIBLE && editLetra.text.toString().trim().isNotEmpty()) {
        chatLog.append("\nTú: $texto\nMusicaIA: Estás en modo 'Mi letra'. Puedes seguir editando tu letra y después crearla.")
        return
    }
    if (texto.equals("mi letra", ignoreCase = true) || texto.contains("texto propio", ignoreCase = true)) {
        panelConversacion.visibility = View.GONE
        panelLetra.visibility = View.VISIBLE
        chatLog.append("\nTú: $texto\nMusicaIA: Modo 'Mi letra' activado. Escribe o pega tu letra.")
        actualizarUI()
        return
    }

    val lower = texto.lowercase()
    if (lower in listOf("crear", "crear canción", "crear cancion", "sí", "si", "continuar")) {
        val faltan = camposRequeridos.filter { datosConver[it].isNullOrBlank() }
        if (faltan.isNotEmpty()) {
            chatLog.append("\nTú: $texto\nMusicaIA: Antes necesito: ${faltan.joinToString(", ")}. ${preguntaParaCampo(faltan.first())}")
            return
        }
        camposOpcionales.filter { datosConver[it].isNullOrBlank() }.forEach { datosConver[it] = "no especificado" }
        btnCrear.isEnabled = true
        tvEstado.text = "Datos completos. Pulsa Crear."
        chatLog.append("\nTú: $texto\nMusicaIA: Perfecto. Ya tengo los datos. Puedes crear la letra.")
        return
    }

    val etiqueta = Regex("(?i)^(tema|estilo|ambiente|ritmo|idioma|tipo|voz)\\s*[:=]\\s*(.+)$").matchEntire(texto)
    val cambio = Regex("(?i)^(?:cambiar|cambia|modificar|modifica)\\s+(tema|estilo|ambiente|ritmo|idioma|tipo|voz)\\s+(?:a|por)\\s+(.+)$").matchEntire(texto)
    val campoExplicito = cambio?.groupValues?.get(1)?.lowercase()
        ?: etiqueta?.groupValues?.get(1)?.lowercase()
    val valorExplicito = cambio?.groupValues?.get(2) ?: etiqueta?.groupValues?.get(2)
    val campo = when (campoExplicito) {
        "voz" -> "vozFuente"
        else -> campoExplicito
    } ?: (camposRequeridos + camposOpcionales).firstOrNull { datosConver[it].isNullOrBlank() }

    if (campo == null) {
        chatLog.append("\nTú: $texto\nMusicaIA: Ya están todos los campos. Escribe 'crear' o indica, por ejemplo, 'cambiar tema a ...'.")
        return
    }

    var valor = (valorExplicito ?: texto).trim()
    if (campo == "tipo" && lower == "ambas") valor = "ambas (voz e instrumental)"
    if (campo == "tipo" && (lower == "ninguna" || lower == "solo instrumental")) valor = "solo instrumental"
    if (campo == "vozFuente" && lower == "ninguna") valor = "ninguna"
    if (campo == "vozFuente" && (lower.contains("archivo") || lower.contains("fichero"))) valor = "archivo de voz"
    if (campo == "vozFuente" && lower.contains("grabar")) valor = "voz grabada"
    datosConver[campo] = valor

    val siguiente = (camposRequeridos + camposOpcionales).firstOrNull { datosConver[it].isNullOrBlank() }
    val mensaje = if (siguiente != null) {
        "Anotado en '$campo': $valor. ${preguntaParaCampo(siguiente)}"
    } else {
        btnCrear.isEnabled = true
        tvEstado.text = "Datos completos. Pulsa Crear."
        val resumen = (camposRequeridos + camposOpcionales).joinToString("\n") { "- $it: ${datosConver[it]}" }
        "Resumen de la canción:\n$resumen\n$notaIA\nEscribe 'crear' o cambia un campo indicando su nombre."
    }
    chatLog.append("\nTú: $texto\nMusicaIA: $mensaje")
}

 private fun preguntaParaCampo(campo: String): String = when (campo) {
    "tema" -> "¿De qué quieres que trate la canción?"
    "estilo" -> "¿Qué estilo musical prefieres (pop, rock, rap, balada, electrónica...)?"
    "ambiente" -> "¿Qué ambiente o emoción buscas?"
    "ritmo" -> "¿Qué ritmo prefieres: lento, medio o rápido?"
    "idioma" -> "¿En qué idioma quieres la letra?"
    "tipo" -> "¿Quieres voz, instrumental o ambas?"
    "vozFuente" -> "¿Usarás una voz grabada, un archivo de voz o ninguna?"
    else -> "¿Qué dato quieres añadir?"
}

 private fun actualizarUI() {
     btnCrear.isEnabled = editLetra.text.toString().isNotBlank() ||
         camposRequeridos.all { !datosConver[it].isNullOrBlank() }
 }

 private fun actualizarEstadoModelo() {
     val file = LiteRTLMModule.modelFile(this)
     val disponible = file.isFile && file.length() == LiteRTLMModule.EXPECTED_SIZE_BYTES
     estadoModelo.text = if (disponible) {
         "Modelo local descargado. Se verificará SHA-256 antes de cargarlo."
     } else {
         "Modelo de letras no descargado. La descarga inicial requiere Internet; después funciona sin nube."
     }
 }

 private fun confirmarDescargaModelo() {
     if (descargaEnCurso) return
     AlertDialog.Builder(this)
         .setTitle("Descargar modelo local")
         .setMessage("Se descargará Gemma 4 E2B (2,6 GB) al almacenamiento privado de la app. La descarga se puede reanudar y se comprobará tamaño y SHA-256. Después, la generación de letras se ejecutará en el teléfono sin nube.")
         .setNegativeButton("Cancelar", null)
         .setPositiveButton("Descargar") { _, _ -> iniciarDescargaModelo() }
         .show()
 }

 private fun iniciarDescargaModelo() {
     if (descargaEnCurso) return
     descargaEnCurso = true
     btnDescargarModelo.isEnabled = false
     progresoModelo.visibility = View.VISIBLE
     progresoModelo.progress = 0
     estadoModelo.text = "Descargando modelo local..."
     Thread {
         val destino = LiteRTLMModule.modelFile(this)
         val correcto = DescargadorModelos.descargar(
             url = LiteRTLMModule.MODEL_URL,
             destino = destino,
             shaEsperado = LiteRTLMModule.EXPECTED_SHA256,
             confirmar = true,
             progreso = { descargado, total ->
                 if (total > 0L) {
                     val porcentaje = ((descargado * 1000L) / total).toInt().coerceIn(0, 1000)
                     runOnUiThread { progresoModelo.progress = porcentaje }
                 }
             },
             tamanoEsperadoBytes = LiteRTLMModule.EXPECTED_SIZE_BYTES
         )
         runOnUiThread {
             descargaEnCurso = false
             btnDescargarModelo.isEnabled = true
             progresoModelo.visibility = View.GONE
             if (correcto) {
                 estadoModelo.text = "Modelo descargado y SHA-256 verificado. Ya puedes generar letras sin Internet."
             } else {
                 estadoModelo.text = "No se pudo validar la descarga. Comprueba espacio libre y conexión; puedes reintentar."
             }
         }
     }.start()
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
 EngineLiteRT.cerrarAsync()
 }

 private fun crearCancion() {
 if (ocupado) return
 val letraManual = editLetra.text.toString().trim()
 val modelo = LiteRTLMModule.modelFile(this)
 if (letraManual.isBlank() && (!modelo.isFile || modelo.length() != LiteRTLMModule.EXPECTED_SIZE_BYTES)) {
     tvEstado.text = "Primero descarga el modelo local de letras (498 MB)."
     return
 }
 if (panelConversacion.visibility == View.VISIBLE &&
     camposRequeridos.any { datosConver[it].isNullOrBlank() }) {
     tvEstado.text = "Completa tema, estilo, ambiente, ritmo e idioma antes de crear."
     return
 }
 ocupado = true
 btnCrear.isEnabled = false
 btnReproducir.visibility = View.GONE
 rutaAudio = null
 rutaInstrumental = null
 imgPortada.visibility = View.GONE
 progreso.visibility = View.VISIBLE
 tvEstado.text = "Creando..."
 tvResultado.text = ""
 segundos = 0
 tvTiempo.text = "Tiempo: 0s"
 tvEstadoVoz.text = if (rutaVoz != null) "Voz seleccionada. El perfil y el canto IA aún no están integrados." else if (AudioGenNative.available) "Instrumental IA local disponible; canto IA pendiente." else "Instrumental y canto IA local pendientes en esta compilación."
 handler.removeCallbacks(reloj)
 handler.postDelayed(reloj, 1000)

 val tema = datosConver["tema"]?.trim()?.ifEmpty { "pop alegre" } ?: "pop alegre"
 val estilo = datosConver["estilo"]?.trim()?.ifEmpty { "pop" } ?: "pop"
 val ambiente = datosConver["ambiente"] ?: ""
 val ritmo = datosConver["ritmo"] ?: ""
 val idioma = datosConver["idioma"] ?: ""
 val tipo = datosConver["tipo"] ?: ""
 val vozFuente = datosConver["vozFuente"] ?: ""
 val instrucciones = editIndicaciones.text.toString().trim()
 val entradaTexto = buildString {
     append("Tema: $tema.")
     append(" Estilo: $estilo.")
     if (ambiente.isNotEmpty()) append(" Ambiente: $ambiente.")
     if (ritmo.isNotEmpty()) append(" Ritmo: $ritmo.")
     if (idioma.isNotEmpty()) append(" Idioma: $idioma.")
     if (tipo.isNotEmpty()) append(" Tipo: $tipo.")
     if (vozFuente.isNotEmpty()) append(" Voz: $vozFuente.")
     if (instrucciones.isNotEmpty()) append(" Indicaciones: $instrucciones.")
 }
 val entrada = Dato.Texto(if (letraManual.isNotEmpty()) "[LETRA_USUARIO]\n$letraManual\n\n$entradaTexto" else entradaTexto)

 val modulos = mutableListOf<com.musicaia.modelo.ModuloIA>()
 modulos.add(ModuloLetra(this, letraManual.isNotEmpty()))
 if (AudioGenNative.available) {
     modulos.add(ModuloInstrumental(this, entradaTexto))
 }
 Thread {
 try {
 val pasos = orquestador.ejecutarModulos(modulos, entrada)
 runOnUiThread { mostrarResultado(pasos) }
 } catch (e: Exception) {
 Log.e(etiqueta, "Error en la creación", e)
 runOnUiThread { mostrarError(e) }
 } finally {
 runOnUiThread {
 detenerReloj()
 ocupado = false
 btnCrear.isEnabled = true
 progreso.visibility = View.GONE
 }
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
 if (id == "instrumental") rutaInstrumental = dato.rutaWav
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
 val tieneInstrumental = pasos.any { it.first == "instrumental" && it.second is Dato.Audio }
 tvEstado.text = when {
     tieneInstrumental && editLetra.text.toString().isNotBlank() -> "Letra propia e instrumental IA local generados en ${segundos}s. Canto IA y portada pendientes."
     tieneInstrumental -> "Letra e instrumental IA local generados en ${segundos}s. Canto IA y portada pendientes."
     editLetra.text.toString().isNotBlank() -> "Letra propia cargada en ${segundos}s. Instrumental, canto IA y portada pendientes."
     else -> "Letra generada con IA local en ${segundos}s. Instrumental, canto IA y portada pendientes."
 }
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
 if (!Mp3Exporter.available) {
 tvEstado.text = "El codificador MP3 no está disponible en esta compilación."
 return
 }

 val nombre = "musicaia_" + System.currentTimeMillis() + ".mp3"
 val temporal = File(cacheDir, nombre)
 btnGuardar.isEnabled = false
 tvEstado.text = "Preparando MP3 de alta calidad (320 kbps)..."
 Thread({
     val correcto = runCatching {
         Mp3Exporter.encodeWavToMp3(origen, temporal.absolutePath, 320)
     }.getOrDefault(false) && temporal.isFile && temporal.length() > 128L

     runOnUiThread {
         btnGuardar.isEnabled = true
         if (isFinishing || isDestroyed) {
             temporal.delete()
             return@runOnUiThread
         }
         if (correcto) {
             rutaExportacion = temporal.absolutePath
             selectorGuardar.launch(nombre)
         } else {
             temporal.delete()
             tvEstado.text = "No se pudo crear el MP3. El WAV original se conserva."
         }
     }
 }, "MusicaIA-MP3-Export").start()
 }
}
