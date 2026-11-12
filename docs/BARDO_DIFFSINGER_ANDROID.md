# BARDO · Integración local DiffSinger para Android

## Estado de esta rama

Esta rama añade la primera capa Android del motor:

- ONNX Runtime Android 1.31.1, fijado en Gradle.
- Inspector seguro de paquetes DiffSinger: dsconfig.yaml, acústico ONNX, inventario de fonemas, diccionario español dsdict-es.yaml/dsdict.yaml, tabla de idiomas, modelos auxiliares declarados, modelo ONNX del vocoder y compatibilidad de sample_rate/hop_size/FFT/mel.
- Rechazo de rutas que escapen del directorio de la voz y de nombres de paquetes de vocoder inseguros.
- Prueba de carga de la sesión ONNX acústica con límites conservadores de memoria y cierre determinista de recursos.
- Registro separado del runtime ONNX y de la síntesis cantada.

## Límites explícitos

Esto NO es todavía un sintetizador cantado completo. Abrir el grafo acústico no ejecuta inferencia. Faltan portar el fonemizador/G2P español, el constructor de tensores y máscaras DiffSinger, las inferencias acústica y del vocoder, y el ensamblado WAV. El inspector ya exige que la voicebank aporte el diccionario fonético español y valida la existencia y compatibilidad declarada de los modelos. Por eso `voice_synthesis` permanece `NOT_IMPLEMENTED` y no se habilita el flujo de creación con voz IA.

La rama activa V2 de OpenUtau Mobile es una referencia técnica Apache-2.0, pero su propia matriz clasifica DiffSinger Android como condicional y su README advierte que la rama está en desarrollo. No se copia ni redistribuye ninguna voicebank de terceros en esta integración. Cada voz deberá tener licencia de uso y redistribución comprobada, además de consentimiento cuando corresponda.

## Siguiente puerta de aceptación

1. CI compila y ejecuta pruebas del inspector.
2. Añadir un paquete DiffSinger de prueba con licencia verificable.
3. Abrir acústico y vocoder ONNX en ARM64.
4. Ejecutar una inferencia completa de frase con fonemas españoles y obtener WAV PCM.
5. Medir memoria, tiempo, temperatura y estabilidad en Xiaomi 11T Pro.
6. Solo entonces elevar el estado de síntesis y conectarlo al orquestador.

Referencias:
- OpenUtau Mobile V2: https://github.com/vocoder712/OpenUtauMobile/tree/dev
- DiffSinger support: https://www.openutau.com/DiffSinger-support/
- ONNX Runtime Android: https://onnxruntime.ai/docs/install/
