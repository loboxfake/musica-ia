ARQUITECTURA TAREA 2 — 3 MODOS (NO DECLARADO TERMINADO)

DISPOSITIVO: Xiaomi 11T Pro — Snapdragon 888 — 8 GB RAM — GPU Adreno 660 — NPU HEXAGON (int8)

MODELO 1 — CANCIÓN COMPLETA (prompt → letra + instrumental + voz + portada + mezcla)
- Motor propuesto: Stable Audio Open Small (archivo ~850 MB, licencia Apache-2.0)
- Versión: stable-audio-open-small-v1
- RAM necesaria: >4 GB libre (modelo ~850MB + buffer + UI); con 8GB total (Android usa ~3-4 GB) es borderline. Recomendación: tramos de 30-60s, no 3 min completo sin partición.
- Snapdragon 888: sin GPU/AMP de NVIDIA, solo CPU + Hexagon int8; inferencia lenta (~120s por 2 min). NO viable en tiempo real sin partición.
- Internet: modelo descarga vía DescargadorModelos con SHA-256 verificado (no requiere internet tras descarga única).
- Alternativa real si no cabe: MMT (MusicGen-medium, ~1.5GB — peor), o MóduloInstrumental procedural (ya implementado) con letra de plantilla (ya implementado) y voz TTS real; documentado como paso intermedio.

MODELO 2 — LETRA → MÚSICA + VOZ CANTADA
- Letra: plantilla basada en Regex (ya implementado en ModuloLetra.kt línea 26+); NO es LLM. No es adecuado para esta modalidad sin reemplazar por LLM local (Llama 2/3 7B ~3.8GB — posible en 8GB, lento).
- Instrumental: Stable Audio Open Small (como arriba) o procedural (ya implementado), documentado como "procedural real, no IA generativa" hasta descarga del modelo.
- Voz cantada: NO es TTS (Modulovoz.kt usa ToneGenerator, no canto real). Voz cantada real requiere: (a) grabación de voz del usuario (modo 3) o (b) modelo de canto (ej. RVC / SoftVC — ~100-300MB, entrenado con datos de usuario — viable en 8GB, requiere entrenamiento). Documentado: NO implementado aún; alternativa = usar grabación de usuario (modo 3).

MODELO 3 — SUBIR LETRA + GRABACIÓN DE VOZ → CANCIÓN CON ESA VOZ
- Entrada: archivo .txt (letra) + archivo .wav (voz grabada por usuario, audio de micrófono, no canto sintetizado).
- Motor: mix de archivo WAV de voz + instrumental generado/procedural + sincronización por marca temporal (beat markers) — requiere módulo de alineación temporal real.
- Modelo no requerido de IA para voz (usa la grabación real); instrumental puede ser procedural o Stable Audio.
- Viable en Snapdragon 888 / 8 GB: sí (solo mezcla de archivos WAV, sin inferencia pesada).
- Licencia: archivo de usuario (privado); sin restricciones.

FINES DE FASE (no terminada):
1. Documentar arquitectura y modelos (este archivo).
2. Implementar por fase: primero modo 3 (mezcla real con voz del usuario, sin IA pesada), luego modo 2 (letra + instrumental con modelo documentado como real si descarga, procedural si no), luego modo 1 (orquestador con todos los pasos conectados).
3. No declarar función terminada sin archivo generado real + comprobación de audio + build verde.
