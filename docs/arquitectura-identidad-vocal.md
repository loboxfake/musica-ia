# Arquitectura de Identidad Vocal y Canto — Recomendación basada en evidencia (2026-11-03)

Evidencia del entorno (verificado, no inventado):
- Dispositivo objetivo: Xiaomi 11T Pro (Snapdragon 888, Adreno 660, Hexagon 780, 8GB RAM, ~2GB libres habitualmente, Android 13).
- Compilación: no disponible localmente (falta JDK/SDK); vía reproducible = GitHub Actions (.github/workflows/android.yml, JDK 17, Gradle 8.10.2).
- Módulos actuales: GrabadoraWav (WAV mono 16b 44100 Hz real), LectorWav (lectura robusta), MezclaVoz (mezcla real PCM), ModuloInstrumental (WAV tonal, no IA pesada), ModuloVoz (ToneGenerator procedural, no modelo cargado), Modo3 (mezcla voz+instrumental, declara sin modelo canto cargado).
- No hay modelo RVC/So-Vits-SVC cargado ni archivo de identidad vocal.

Comparación de alternativas reales para A (análisis/clonación voz hablada) y B (canto con identidad):

| Opción | Tipo | Tamaño estimado | Licencia | Offline Android | Snapdragon 888 8GB | Estado en MusicalIA |
|---|---|---|---|---|---|---|
| RVC (Retrieval-based VC) | A + B (con modelo de canto) | ~50-200MB cuantizado (.pth/.pt) | MIT / proyectos open (depende del modelo específico) | Sí (ONNX/TFLite o Python embebido) | Posible con cuantización y límite de segmento; requiere cuidado con RAM libre ~2GB | NO integrado; requiere archivo de modelo + archivo de referencia de voz hablada |
| So-Vits-SVC | A + B | Similar a RVC (~50-150MB) | MIT (proyecto base) | Sí | Similar restricciones RAM | NO integrado |
| TFLite/ONNX (análisis de pitch/formantes) | A solo | <10MB | Apache 2.0 / MIT | Sí | Sí, muy ligero | NO integrado; podría ser paso 1 de A |
| Stable Audio Open (instrumental) | Instrumental solo | ~1-2GB (no para voz) | Open | Posible con adaptación | Límite RAM; se usa para instrumental, no canto | Integrado (referencia en catalogo) |
| Llama 3 7B (letra/conversación) | Texto | ~4GB+ cuantizado | Llama 3 Community License | Posible en 8GB con cuantización extrema | Difícil con RAM libre ~2GB; mejor usar 1-3B | Referenciado en especificación; no cargado |
| ToneGenerator (actual ModuloVoz) | Procedural, no clonación | 0MB | Android SDK | Sí | Sí | PRESENTE — NO es sustituto de clonación; debe mantenerse como fallback, no como identidad |

Recomendación concreta (separada, no confunda A y B):

A — Identidad vocal (análisis de voz hablada, guardado local, versiones):
- Implementar con análisis de señal real (FFT/formantes/pitch) en Android nativo (no modelo grande).
- Salida: archivo de características (`voz_modelos/v1/caracteristicas.json` o `.npy` simple) + metadatos de versión.
- No requiere red; funciona offline; compatible con 8GB/2GB libres.
- No afirma clonación perfecta; documenta que es representación de características, no modelo de canto completo.

B — Generación de canto con identidad vocal:
- Requiere modelo externo (RVC o similar) cargado como archivo `.pth`/`.pt` en `assets/` o descargado vía `DescargadorModelos`.
- Condición de ejecución: solo si `modeloVocalCargado == true`; si no, mensaje claro al usuario: "Selecciona una identidad vocal guardada o graba tu voz hablada primero".
- No usar ToneGenerator como reemplazo; usar como fallback solo si no hay identidad cargada.
- Versión de modelo guardado localmente (`voz_modelos/v1/`, `v2/`) para comparar y recuperar.

Siguientes pasos ordenados (sin inventar éxito):
1. Confirmar compilación vía GitHub Actions (no local, por falta JDK/SDK).
2. Probar WAV generado por ModuloInstrumental corregido en dispositivo (reproducción MediaPlayer) para validar cabecera.
3. Implementar A (análisis de voz hablada de GrabadoraWav) con archivo de características local.
4. Integrar carga de modelo RVC/So-Vits-SVC como archivo opcional; que falle claramente si falta.
5. Conectar selector de identidad vocal en MainActivity (sin inventar vistas que no existan; usar flujo existente con mensaje claro si no hay modelo).
6. No afirmar canto IA ni clonación hasta que el archivo de modelo esté cargado y probado con archivo de voz real del Xiaomi 11T Pro.
