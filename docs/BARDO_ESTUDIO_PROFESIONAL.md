# BARDO · Estudio musical profesional integrado

Estado: objetivo de producto y hoja de ruta; **no representa funciones ya implementadas ni calidad ya demostrada**.
Ámbito: una capacidad especializada coordinada por el único BARDO Core / Orquestador, con una sola conversación, identidad y memoria.

## Visión

BARDO evolucionará hasta ser un compañero de producción musical: el usuario y BARDO analizan juntos una canción, acuerdan el resultado deseado, prueban alternativas y refinan el audio hasta aprobarlo. BARDO debe poder asesorar y ejecutar operaciones técnicas, pero explicar qué hizo, conservar el original y permitir comparar cada versión.

La meta es calidad profesional y resultados consistentes. No se prometerá «calidad extrema» por marketing: cada función se habilitará solo cuando tenga modelos/licencias adecuados, controles de calidad, mediciones y pruebas auditivas reproducibles en dispositivos reales.

## Experiencia de trabajo

1. **Escuchar y diagnosticar:** importar audio, identificar formato, duración, canales, frecuencia de muestreo, clipping, ruido, silencios y problemas probables. Separar mediciones objetivas de apreciaciones musicales.
2. **Acordar el objetivo:** BARDO pregunta solo lo que falte, ofrece alternativas explicadas y confirma el plan antes de aplicar cambios destructivos.
3. **Trabajar sobre una sesión no destructiva:** original de solo lectura, pistas derivadas, regiones, marcadores, historial de operaciones y versiones. Cada operación conserva parámetros para deshacer/repetir.
4. **Previsualizar:** escuchar A/B con igual loudness cuando proceda; comparar original y procesado, solo/solo, mezcla y stems.
5. **Aprobar y exportar:** exportar únicamente tras validar duración, sincronización, clipping, continuidad, canales, sample rate, bit depth, metadatos y reproducción del archivo.

## Equipo técnico que BARDO debe coordinar

### A. Separación de fuentes (stems)
- Extraer voz principal, coros, instrumental y, según modelo, batería, bajo y otros instrumentos.
- Investigar modelos especializados para guitarra, piano y otros stems; no asumir que todos los modelos los separan limpiamente.
- Comparar más de un modelo cuando el hardware lo permita; elegir por calidad del stem concreto y no por un único ranking global.
- Controles de intensidad/mezcla de stems, alineación temporal, inversión de fase y reconstrucción de mezcla.
- Medir filtraciones, artefactos, pérdida de transitorios y diferencia entre mezcla reconstruida y original.
- Informar de límites: una separación IA puede dejar residuos, alterar timbre o perder elementos; nunca prometer aislamiento perfecto.

Candidatos de investigación (no integrados ni aprobados automáticamente):
- Demucs / Hybrid Demucs para stems de voz, batería, bajo y otros. El repositorio original de Meta indica que ya no está mantenido activamente; evaluar forks, versiones, licencia, compatibilidad y reproducibilidad antes de adoptarlo.
- Modelos UVR/MDX/VR y herramientas como audio-separator para comparar separadores especializados. Auditar individualmente cada peso, licencia y requisito de runtime.
- No depender de servicios en la nube como ruta predeterminada. Si algún modelo no cabe o no rinde en el Xiaomi 11T Pro, comunicarlo y estudiar una alternativa local/por lotes sin simular que se ejecutó en el teléfono.

### B. Edición vocal de precisión
- Selección por muestra, milisegundo, compás, golpe, palabra y región; zoom de forma de onda y espectrograma.
- Cortes con crossfade, eliminación de clics, respiraciones y ruidos con preescucha y protección de consonantes/transitorios.
- Alineación de dobles y coros; corrección de timing por regiones y edición de envolvente.
- Afinación de tono con detección de notas, escala configurable, velocidad, transición, vibrato y preservación de formantes; siempre con modo natural y comparación con original.
- De-esser, reducción de ruido, reducción de reverberación y control de resonancias; procesado moderado y ajustable, sin destruir la voz.
- Separar claramente edición de una grabación existente de generación o clonación de voz. Cualquier clonación futura exige consentimiento, derechos y controles específicos.

### C. Mezcla y restauración
- Cadena configurable por pista: ganancia, filtro, EQ paramétrica, compresión, puerta/expansor, de-esser, saturación, reverberación, delay, panorama y automatización.
- Procesamiento de 32-bit float internamente cuando sea viable; evitar clipping intermedio y conversiones innecesarias.
- Sidechain, buses, grupos, envíos y automatización de volumen/pan/efectos.
- Restauración de ruido, zumbido, pops y clics con controles para evitar artefactos.
- Medición de LUFS integrado/short-term, true peak, RMS, crest factor, correlación estéreo y espectro.

### D. Masterización y entregables
- Perfiles seleccionables (streaming, vídeo, CD, instrumental, demo) con objetivos explicados, nunca valores universales obligatorios.
- EQ tonal, control dinámico, saturación opcional, limitador true-peak, imagen estéreo y control de loudness.
- Exportación WAV PCM 16/24/32-bit float cuando sea compatible, MP3 con bitrate seleccionable y FLAC si se incorpora un encoder probado.
- Exportar stems sincronizados desde el mismo punto cero, mezcla final, instrumental, acapella y versiones alternativas.
- Informe adjunto con formato, sample rate, bit depth, bitrate, loudness, true peak, duración, hash y advertencias.

### E. Asesor musical colaborativo
- Analizar estructura, tonalidad, tempo, dinámica, instrumentación, densidad y evolución de la canción.
- Proponer arreglos, transiciones, armonías, capas, variaciones y correcciones; separar opinión artística de medición técnica.
- Explicar cada propuesta con motivo y efecto esperado; escuchar la decisión del usuario y ajustar el plan.
- Mantener un cuaderno de sesión con decisiones, versiones, referencias autorizadas y notas del usuario.
- Nunca cambiar letra, melodía, interpretación o mezcla final sin autorización de ese cambio.

## Arquitectura técnica

- Un único orquestador BARDO y un registro de capacidades; «equipo de estudio» son roles/herramientas internas, no agentes con chats, identidades o memorias separados.
- Contratos comunes de tarea: entrada, formato, parámetros, presupuesto RAM/CPU, cancelación, progreso, artefactos, métricas, errores y procedencia.
- Motor de audio separado de la UI; procesamiento pesado en workers/servicio, actualizaciones UI solo en main thread.
- Pipeline PCM con formatos explícitos, buffers acotados, streaming por bloques y procesamiento por regiones para evitar cargar canciones completas en RAM.
- Sesiones y proyectos guardados en carpeta BARDO con originales, derivados, metadatos, snapshots y recuperación; permisos SAF y no asumir acceso directo a carpetas ajenas.
- Perfil de hardware: detectar RAM disponible, CPU, almacenamiento y temperatura/estado térmico; limitar concurrencia y ofrecer cancelación. En el Xiaomi 11T Pro no ejecutar a la vez modelos pesados de texto y audio si la memoria no lo permite.
- Modelos con manifiesto: origen, licencia, versión, SHA-256, tamaño, arquitectura, backend, RAM pico medida, calidad medida y compatibilidad. Descargar/importar, verificar y activar en pasos separados.
- Las funciones profesionales deben funcionar localmente siempre que sea técnicamente posible. Una función que requiera nube debe identificarse claramente y permanecer desactivada hasta consentimiento.

## Seguridad, derechos y recuperación

- No sobrescribir nunca el original importado.
- No procesar material si el usuario no tiene derecho a utilizarlo o no confirma que está autorizado cuando sea necesario.
- Mantener registro de cada operación y permitir deshacer/revertir.
- Validar rutas, límites de tamaño, formatos y archivos temporales; no ejecutar contenido importado.
- No subir audio, letras ni modelos privados a servicios externos por defecto.
- No guardar ni usar una voz de referencia para clonación sin consentimiento explícito y verificable.

## Plan de implementación y puertas de calidad

1. **Base fiable:** corregir generación WAV, ensamblado, exportación MP3/compartir, progreso, cancelación, errores y biblioteca. Pruebas unitarias + pruebas de archivos reales.
2. **Editor no destructivo:** sesión, pistas, waveform, selección sample-accurate, marcadores, regiones, undo/redo, A/B y exportación reproducible.
3. **Medición y DSP:** analizadores, EQ, dinámica, fades, crossfades, de-esser y restauración clásica con pruebas numéricas.
4. **Separación de stems:** benchmark de varios modelos en un conjunto autorizado; medir calidad, RAM, tiempo, temperatura y artefactos. Prototipo aislado antes de integrar.
5. **Edición vocal avanzada:** alineación, pitch/time editing y reducción de ruido/reverb; validar con voces diversas y escuchar ciegamente resultados.
6. **Mezcla/master:** buses, automatización, cadenas por pista, medición y presets explicables; comparación de traducción en distintos sistemas.
7. **Asesor musical:** integrar análisis y propuestas en el chat principal con contexto de sesión y aprobación por operación.
8. **Prueba de producto:** pruebas prolongadas en Xiaomi 11T Pro, reproducción de cada artefacto, recuperación tras interrupciones y auditoría de licencias.

## Criterios para declarar una función «lista»

- Código compilado y tests relevantes verdes en el SHA exacto.
- Modelo y librerías con licencia y hash verificados.
- Medición de memoria, tiempo y estabilidad en el dispositivo objetivo.
- Archivo de salida no vacío, cabecera y metadatos válidos, duración correcta y reproducción escuchada.
- A/B documentado y ausencia de regresiones críticas.
- Limitaciones conocidas comunicadas al usuario.
- La compilación CI por sí sola no acredita calidad sonora ni funcionamiento real en el teléfono.

## Referencias técnicas iniciales

- Demucs README: https://github.com/facebookresearch/demucs — describe Hybrid Demucs y stems; el propio proyecto avisa de mantenimiento limitado.
- Audio Separator: https://github.com/nomadkaraoke/python-audio-separator — integra familias MDX-Net, VR Architecture, Demucs y MDXC; revisar pesos/licencias por separado.
- Audacity Vocal Reduction and Isolation: https://manual.audacityteam.org/man/vocal_reduction_and_isolation.html — referencia útil para entender las limitaciones de la separación basada solo en centro estéreo.
- Estas referencias son material de investigación, no dependencias integradas ni aval de calidad para BARDO.
