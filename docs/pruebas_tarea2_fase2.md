PRUEBAS TAREA 2 — FASE 2 (Modo 3: voz usuario + instrumental)
Estado: IMPLEMENTADO, NO DECLARADO TERMINADO
- Modelo de inferencia: NINGUNO cargado para voz cantada (no hay modelo RVC/SoftVC cargado; límite 8GB de RAM impide carga de modelos de canto grandes; documento esto explícitamente)
- Motor: mezcla de archivos WAV (procedural instrumental + grabación de usuario)
- Archivo entrada: /sdcard/Android/data/com.musicaia/files/voz_usuario.wav (requerido, debe ser >0 bytes; si no existe, Modo3MezclaVozUsuario retorna mensaje documentado, no archivo vacío)
- Archivo salida esperado: modo3_mezcla.wav (generado por FileOutputStream real)
- Tamaño/duración: depende de entrada; con instrumental 120s + voz 30s → mezcla ~150s (real, no plantilla)
- Validación técnica: archivo WAV debe contener cabecera RIFF + datos PCM; reproducible con MediaPlayer
- Prueba de escucha: pendiente; requiere archivo de voz real del usuario (modo 3, paso de usuario)
- Resultado real en Android: botón CREAR activa Modo3MezclaVozUsuario; si no hay voz_usuario.wav, muestra mensaje documentado; no inventa archivo vacío ni afirma "cantado"
- Compromiso: no se declara "voz cantada" sin grabación real; no se declara "IA integrada" sin ejecución de motor real; se conserva funcionalidad existente (ModuloInstrumental, ModuloLetra, etc.)
