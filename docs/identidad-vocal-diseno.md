# FASE 3 — Identidad Vocal (Diseño Real, No Simulado)

Estado actual verificado (2026-11-03):
- ModuloVoz usa ToneGenerator (tonos procedurales, no voz cantada ni clonada).
- No existe módulo de análisis de voz hablada (formantes/timbre/pitch).
- No existe modelo RVC/So-Vits-SVC ni archivo de identidad vocal cargado.
- No hay modelo de canto con identidad vocal integrado (Stable Audio Open es instrumental, no vocal).
- Modo3MezclaVozUsuario preservado; declara explícitamente que no hay modelo de canto cargado.
- Xiaomi 11T Pro: Snapdragon 888, 8GB RAM, Android 13 — capaz de correr modelos ligeros locales (ONNX/TFLite) pero no grandes (7B-13B) sin cuantización.

Distinción técnica obligatoria (no confundir):
A) ANÁLISIS / ADAPTACIÓN / CLONACIÓN DE VOZ HABLADA — toma WAV de voz hablada, extrae características (pitch, formantes, timbre, ritmo de habla), guarda representación local (archivo de características + metadata de versión), permite comparar versiones.
B) GENERACIÓN DE CANTO CON IDENTIDAD VOCAL — toma letra + instrumental + modelo de identidad (A) + modelo de canto; genera WAV cantado con esa voz.

Qué existe hoy y qué falta:
- Existe: GrabadoraWav (WAV mono 16-bit 44100 Hz real), LectorWav (lectura real), MezclaVoz (mezcla real), ModuloInstrumental (WAV generado por tonos — no IA), AudioUtil (escritura WAV correcta).
- Falta: (A1) módulo de extracción de características vocales; (A2) almacenamiento de identidad vocal con versionado; (B1) modelo de canto con entrada de identidad vocal; (B2) integración de A→B en pipeline.

Opciones reales compatibles con Xiaomi 11T Pro / Android (no inventadas, verificada por especificación del repo):
- RVC (Retrieval-based Voice Conversion): modelo pequeño (~50-200MB cuantizado), corre en GPU/CPU de móvil, requiere archivo de modelo voz + archivo de referencia. No integrado.
- So-Vits-SVC: similar, requiere entrenamiento con datos de voz hablada + canto de referencia.
- TFLite/ONNX: para análisis de características (pitch/formantes) en dispositivo, sin red pesada.
- No es posible declarar "clonación real" sin (A1) integrado y probado con archivo de voz hablada grabado en el teléfono.

Plan de integración real (ordenado, no simulado):
1. Nuevo módulo: AnalizadorVoz (entrada: WAV grabado por GrabadoraWav; salida: archivo de características + metadata de versión + comparación con versiones previas). Usa análisis de señal real (FFT, formantes) — no cambió de tono ni filtro.
2. Almacenamiento local: directorio `voz_modelos/` con versiones (`v1/`, `v2/`, ...) y archivo de metadatos (`versiones.json`). Recuperable y comparable.
3. Nuevo módulo: GeneradorCantoConIdentidad (entrada: letra + instrumental + modelo seleccionado; salida: WAV cantado). Requiere modelo exterior (RVC/So-Vits-SVC) integrable vía archivo `.pth`/`.pt`. No se declara funcionando hasta que se cargue y se pruebe con archivo real.
4. Selección en MainActivity: agregar campo/selector de voz guardada (`btnSeleccionarVozGuardada`) conectado al flujo `crearCancion()`; si no hay modelo cargado, mensaje claro: "No hay identidad vocal cargada. Graba tu voz hablada primero (Modo 3 / grabadora) para crearla."
5. No sustituir por tonos, plantillas ni filtros. Si no está cargado el modelo, no se afirma que existe canto IA ni generación vocal.

Restricciones mantenidas:
- No modificar Modo3MezclaVozUsuario.kt.
- No hacer commit/push.
- No afirmar voz cantada generada por IA mientras no esté cargado el modelo.
- Pruebas requeridas en Xiaomi 11T Pro: grabación de voz hablada → análisis → creación de modelo → generación de canto → reproducción → comparación de versiones → exportación.
