# BARDO · Diseño posterior (no implementado en este ciclo)

Este documento registra decisiones de diseño para una fase futura. No habilita funciones nuevas ni modifica el núcleo actual.

## Evolución en cuatro niveles

1. **Interfaz por archivo**: importar/exportar una descripción declarativa y validada de la interfaz. Solo puede cambiar componentes visuales permitidos; no puede ejecutar código ni alterar navegación protegida o lógica de audio.
2. **Skills como recetas**: manifiestos declarativos con entradas, salidas, permisos, límites de tiempo/memoria y versión. Ejecución en entorno limitado, sin acceso arbitrario a archivos, red o procesos. Cada skill necesita pruebas y registro de auditoría.
3. **Investigación autorizada**: acceso a Internet únicamente tras acción explícita del usuario. Guardar fuente, fecha, hash, extracto y licencia/condiciones; el contenido externo se trata como dato no confiable y nunca como instrucciones de sistema.
4. **Parches vía GitHub**: BARDO puede preparar una propuesta en una rama aislada y ejecutar pruebas, pero no hacer merge, publicar Releases ni cambiar el núcleo sin aprobación expresa del usuario.

### Núcleo protegido y recuperación

- Núcleo, permisos, almacenamiento de credenciales, validadores, firma y workflows de Release quedan fuera del alcance de skills y parches automáticos.
- Cada cambio propuesto se aplica a una copia aislada y pasa pruebas unitarias, análisis estático, auditoría de dependencias y build.
- Guardar snapshot previo, diff, logs, hashes y resultado de pruebas.
- Rechazar cambios si fallan las pruebas o se altera un archivo protegido.
- Rollback mediante restauración del snapshot verificado, sin reescribir ni borrar historia remota.
- La aprobación del usuario es obligatoria para integrar, firmar, publicar o instalar.

## MiVoz

La voz cantada personalizada sigue **pendiente y no implementada**. Una grabación de referencia o un archivo importado no equivale a clonación de voz ni a canto generado. No se debe presentar como funcional hasta contar con un modelo, consentimiento, salvaguardas, pruebas y resultados audibles reales.

## Evaluación preliminar: Stable Audio 3 Small-Music

**Resultado: candidato prometedor para una fase experimental separada; no integrar todavía.**

- El modelo oficial Small-Music declara 433 millones de parámetros, inferencia CPU y duración máxima de 120 segundos. La familia Stable Audio 3 usa un autoencoder SAME y admite texto-a-audio, edición y continuación. Fuente oficial: https://github.com/Stability-AI/stable-audio-3
- Stability AI publica una ruta optimizada LiteRT/TFLite para CPU ARM con XNNPACK; la variante pequeña tiene una escalera cuantizada y el README de esa ruta describe alrededor de 0,8 GB de RAM para el DiT pequeño. Guía oficial: https://github.com/Stability-AI/stable-audio-3/tree/main/optimized/tflite
- La ruta optimizada publicada es **LiteRT/TFLite, no ExecuTorch**. No hay evidencia suficiente de un paquete ExecuTorch oficial listo para Android. Por tanto, no es un reemplazo directo del JNI AudioGenNative actual.
- El modelo requiere revisar la Stability AI Community License y los términos de Gemma aplicables al codificador de texto. La tarjeta del modelo indica explícitamente que redistribuye componentes sujetos a Gemma Terms of Use. Tarjeta oficial: https://huggingface.co/stabilityai/stable-audio-3-small-music
- El soporte CPU ARM y el tamaño del modelo lo hacen razonable para una prueba de concepto en el Xiaomi 11T Pro, pero no demuestran rendimiento, memoria pico, temperatura, autonomía ni calidad en ese teléfono.

### Pruebas necesarias antes de decidir

1. Verificar licencia y condiciones de redistribución para la app.
2. Exportar/descargar checkpoints oficiales y comprobar hashes.
3. Crear un prototipo aislado con LiteRT/TFLite y XNNPACK ARM, sin sustituir Stable Audio Open Small.
4. Medir RAM pico, tiempo, temperatura, consumo y fallos en el Xiaomi 11T Pro para 5 s, 30 s y 120 s.
5. Comparar musicalidad, continuidad, seguimiento de BPM, silencios y calidad de mezcla frente al motor actual.
6. Decidir después de escuchar los resultados en el teléfono.

No se ha descargado, convertido, integrado ni ejecutado Stable Audio 3 Small en este ciclo.
