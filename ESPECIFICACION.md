# MusicaIA: especificación

## Objetivo
App Android que crea música con y sin voz, letras de canciones y portadas de álbum o de canción, reuniendo varios modelos de IA que interactúan. Todo el cálculo ocurre en el móvil, sin nube al usarla. Los modelos se pueden actualizar o añadir en el futuro sin rehacer la app.

## Restricciones
- Android, minSdk 26. El móvil es un Xiaomi 11T Pro (Snapdragon 888, GPU Adreno 660, NPU Hexagon 780, 8 GB de RAM, a veces menos de 2 GB libres) y 46 GB de almacenamiento libre.
- Un solo modelo cargado a la vez. Los modelos no van en el APK: se descargan desde la app, con confirmación del usuario y verificación SHA-256.
- La compilación se hace solo en GitHub Actions. Cada APK debe poder instalarse encima del anterior (firma fija).
- Cada modelo declara su licencia y si permite uso comercial.

## Requisitos
- Canciones de 3 minutos como mínimo y sin máximo (por tramos enlazados con fundidos).
- Música instrumental y con voz; letras; portadas.
- Mostrar el tiempo transcurrido y un tiempo estimado real, medido en el propio móvil.
- Catálogo de modelos actualizable (catalogo.json) con un manifiesto por modelo.

## Fases (no pasar a la siguiente sin build verde y prueba en el móvil)
1. Base: módulos de prueba, orquestador, interfaz, APK instalable y firma fija.
2. Letras: modelo de lenguaje pequeño y cuantizado (1 a 3B) que quepa en la RAM libre.
3. Instrumental: Stable Audio 3 Small (hasta 2 min) u otro; después encadenar tramos hasta 3 min o más.
4. Portada: modelo de imagen pequeño en el móvil.
5. Voz: módulo experimental de síntesis de canto y, si el hardware lo permite, un modelo de canciones con voz.
6. Catálogo y actualización de modelos, mezcla final y pulido de la interfaz.

## Reglas de trabajo
- Una fase cada vez. Antes de elegir un modelo, comprobar tamaño, licencia y si funciona en Android con CPU, y anotar en docs/mediciones.md la RAM, el tiempo y el resultado de cada prueba.
- No afirmar que algo está hecho sin comprobarlo (build verde, grep o prueba).
- Si un modelo no cabe en la RAM, avisar y proponer una alternativa más pequeña.

## Modos de creación
La app tendrá tres formas de crear, todas terminando en un «encargo» (ContextoProyecto) que ejecuta el orquestador:
1. Conversación: el usuario habla con la IA (modelo de lenguaje local), que le pregunta género, ánimo, tema, idioma, tipo de voz y duración y, cuando el usuario da la orden final, genera el encargo.
2. Letra + indicaciones: el usuario escribe o pega la letra completa y un texto con cómo quiere que suene (estilo, tempo, instrumentos, ánimo). En este modo la IA no escribe la letra.
3. Con voz propia: el usuario sube un archivo de audio con su voz, que se guarda como referencia y se usa en el módulo de voz.
Los modos pueden combinarse. ContextoProyecto incluirá: letraProporcionadaPorUsuario (Boolean), indicacionesEstilo (String), idioma, duracionSegundos y rutaVozReferencia (String?). Solo se usa audio del propio usuario o de alguien que haya dado su permiso.
