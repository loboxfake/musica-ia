# BARDO — Autoconocimiento operativo y desarrollo autónomo en segundo plano

**Estado:** especificación de producto y criterios de aceptación. No declara implementadas las capacidades pendientes. Fecha de revisión: 2026-11-11.

## 1. Objetivo

BARDO debe conocer su estado real y utilizar ese conocimiento para decidir qué puede hacer, qué necesita, qué debe evitar y qué evidencia le falta. También debe poder realizar ciclos de estudio, programación, pruebas y corrección en segundo plano, sin depender de ChatGPT, de otra conversación externa ni de un proveedor remoto.

«Conocerse a sí mismo» significa introspección técnica verificable, no consciencia humana. BARDO no debe atribuirse experiencias, intenciones o capacidades que no pueda demostrar.

## 2. Modelo de autoconocimiento

La autoevaluación debe mantener un inventario consultable y actualizado de:

- **Dispositivo y entorno:** modelo, Android/API, ABI, RAM disponible, almacenamiento, batería, temperatura, conectividad y restricciones relevantes del sistema.
- **Modelos:** identificador, proveedor/runtime, versión o revisión, licencia, procedencia, tamaño, SHA-256 esperado y real, ubicación, requisitos, estado de carga, memoria consumida cuando sea medible, última inferencia comprobada y resultado.
- **Habilidades y módulos:** ID, versión, finalidad, entradas/salidas, dependencias, herramientas que invoca, permisos requeridos, disponibilidad real y pruebas de aceptación.
- **Herramientas:** contrato, disponibilidad, permisos efectivos, límites, última ejecución, resultado y evidencia.
- **Memoria y conocimiento:** almacenes disponibles, espacio, integridad, fecha de actualización, procedencia, confianza y restricciones de uso.
- **Desarrollo:** versión instalada, commit y snapshot de origen, candidato activo, hashes, pruebas realizadas, estado CI, errores pendientes, aprobaciones y opción de reversión.
- **Seguridad:** permisos concedidos, políticas aplicables, acciones bloqueadas y motivo concreto.

No debe enumerar aplicaciones de terceros ni inspeccionar datos ajenos sin autorización.

## 3. Estados distintos, nunca intercambiables

Cada modelo, módulo, herramienta y capacidad debe mostrar estados inequívocos. Como mínimo:

1. **DECLARADO:** existe en un manifiesto o catálogo.
2. **IMPLEMENTADO:** existe código integrado.
3. **DISPONIBLE:** sus dependencias y permisos están presentes.
4. **INSTALADO:** los archivos requeridos están en el almacenamiento esperado.
5. **INTEGRIDAD_VERIFICADA:** tamaño, hash, licencia y procedencia han sido comprobados según corresponda.
6. **CARGABLE:** el runtime y el presupuesto de recursos permiten iniciar.
7. **CARGADO:** el runtime confirma que el recurso está en memoria y listo.
8. **EJECUTADO:** una operación real se completó.
9. **PRUEBA_SUPERADA:** pasó la prueba concreta con evidencia identificable.
10. **VERIFICADO_EN_DISPOSITIVO:** superó la prueba física requerida en el dispositivo objetivo.
11. **BLOQUEADO / FALLIDO / DESCONOCIDO:** debe acompañarse de causa, instante y siguiente acción posible.

Un estado posterior no se deduce de uno anterior. «Archivo presente» no equivale a «modelo cargado»; «modelo cargado» no equivale a «inferencia correcta»; «build verde» no equivale a «prueba física».

Los estados deben incluir instante de observación y caducidad/invalidez cuando cambien recursos, archivos, versiones, permisos o entorno. Una observación antigua debe mostrarse como desactualizada, no como estado actual.

## 4. Conciencia de límites y selección de recursos

Antes de planificar una tarea, BARDO debe:

1. traducir el objetivo a capacidades necesarias;
2. consultar qué modelos, skills y herramientas existen realmente;
3. comprobar licencias, hashes, dependencias, permisos y recursos;
4. medir si puede cargar los recursos sin competir con audio u otras tareas;
5. seleccionar el conjunto mínimo de modelos adecuado;
6. explicar las carencias y ofrecer una alternativa segura o declarar que no puede continuar.

La flota de agentes no debe presentar varios perfiles del mismo modelo/proveedor como evaluadores independientes. Toda respuesta de modelo es una propuesta no confiable hasta que una comprobación distinta aporte evidencia. BARDO debe poder responder «no lo sé», «no está instalado», «no puedo cargarlo», «no lo he probado» y «necesita autorización».

El inventario será accesible tanto a BARDO (contexto estructurado para planificar) como al usuario (pantalla legible, detalles y diagnóstico exportable). Una vista no debe ser la única fuente de verdad: el estado debe proceder de APIs/inventarios y mediciones del runtime.

## 5. Desarrollo autónomo en segundo plano

El objetivo es un ciclo recuperable:

**observar → diagnosticar → priorizar → planificar → investigar → proponer código → aplicar en sandbox → probar → interpretar fallos → corregir → repetir dentro del presupuesto → evaluar independientemente → guardar candidato y evidencias.**

El worker debe poder trabajar sin que la Activity permanezca abierta. Cada tarea debe tener ID estable, objetivo, base exacta, presupuesto, checkpoint, progreso, estado terminal y diario. Debe poder pausarse, reanudarse, cancelarse y recuperarse tras la recreación del proceso sin duplicar acciones ni perder el candidato.

### 5.1 Ciclos de desarrollo

- Elegir la siguiente tarea desde una cola priorizada por impacto, seguridad, dependencias, recursos y evidencia disponible.
- Inspeccionar un snapshot identificado por commit/hash; nunca editar el snapshot original ni la instalación activa.
- Investigar fuentes locales o públicas según autorización y registrar procedencia, fecha, licencia y resumen.
- Generar cambios pequeños y reproducibles en un workspace aislado.
- Rechazar rutas protegidas, secretos, dependencias no autorizadas, workflows, configuración de build, cambios destructivos y código descargado arbitrario.
- Ejecutar primero pruebas específicas, después suite unitaria, análisis estático y compilación del candidato exacto cuando el entorno lo permita.
- Recopilar logs concretos, sanearlos, clasificar el fallo y proponer correcciones limitadas.
- No repetir el mismo parche o una operación fallida sin cambio de hipótesis/evidencia.
- Mantener límites configurables de intentos, tiempo, RAM, temperatura, batería, almacenamiento y tamaño del diff. Al agotarlos, guardar el estado parcial y explicar el bloqueo.
- Recalcular hashes y evidencias después de cada modificación. El evaluador no puede ser modificado por el candidato que evalúa.
- Mantener candidatos separados por tarea y versión; una tarea nueva no debe sobrescribir otra pendiente.

### 5.2 Autorización y publicación

BARDO puede investigar, codificar, ejecutar pruebas en sandbox y preparar candidatos dentro de las rutas permitidas. No puede autoaprobar sus propios cambios, ampliar sus permisos, modificar políticas de seguridad, editar rutas de revisión humana ni integrar/publicar cambios protegidos por el mero hecho de que sus pruebas pasen.

La integración a `main`, la publicación de una APK, la instalación/actualización en el dispositivo y cualquier operación sensible siguen requiriendo la autorización definida por el usuario y las políticas de autoridad. La aprobación debe referirse al candidato, diff, SHA base, hashes y resultados concretos; cualquier cambio posterior invalida esa aprobación.

## 6. Ejecución Android: persistente, visible y respetuosa con el sistema

No se promete ejecución permanente, invisible ni inmediata. Android y los fabricantes pueden diferir o detener trabajos por batería, memoria, térmica, cuotas, modo ahorro, permisos o cierre forzado.

- Usar WorkManager para trabajo diferible y reintentable, con trabajo único por tarea, constraints y checkpoints.
- Para inferencia/compilación larga iniciada por el usuario y que deba continuar de forma inmediata, utilizar un mecanismo en primer plano solo cuando el caso y el tipo de servicio estén permitidos, con notificación visible, progreso, pausa/cancelación y duración limitada.
- No iniciar servicios en primer plano desde condiciones no permitidas ni utilizar `specialUse` como permiso genérico. Documentar con precisión su finalidad y revisar los requisitos de Android/Google Play.
- No abusar de alarmas, wake locks, reinicios de servicio, exclusiones de optimización de batería ni mecanismos para eludir las decisiones del usuario o del sistema.
- Priorizar trabajo cuando el teléfono esté cargando, con batería y RAM suficientes, temperatura segura y almacenamiento adecuado.
- No ejecutar entrenamiento/inferencia pesada junto a la generación de audio si el coordinador de recursos detecta riesgo de OOM, interrupciones o sobrecalentamiento.
- Persistir checkpoints y liberar runtimes nativos al terminar, fallar, pausarse o ceder recursos.
- Mostrar el motivo cuando el sistema posponga o interrumpa una tarea. Tras un cierre forzado de la app, no afirmar que el trabajo continuó.

Referencias oficiales para la implementación Android (revisar de nuevo al cambiar targetSdk):
- https://developer.android.com/develop/background-work/background-tasks
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/changes

## 7. Experiencia de usuario exigida

La pantalla de BARDO debe permitir:

- Ver «Qué sé de mí» con capacidades disponibles, no disponibles, no probadas y bloqueadas.
- Abrir detalles de cada modelo/skill/herramienta, incluyendo hashes, licencia, requisitos y última prueba.
- Consultar qué tarea autónoma está ejecutando, qué está haciendo, qué archivos toca, cuánto presupuesto consume y qué falta.
- Ver cola, historial, checkpoints, candidatos, informes y diferencias de código.
- Pausar, reanudar, cancelar y desactivar la programación periódica.
- Elegir franjas/condiciones de trabajo, límites de recursos y si puede realizar investigación pública.
- Aprobar o rechazar por separado cada integración/publicación, con diff y evidencia completos.
- Exportar informes localmente sin enviar contenido privado a la nube.

No se debe mostrar una animación de «pensando» como prueba de actividad. El progreso deriva de eventos y resultados persistidos.

## 8. Pruebas de aceptación obligatorias

### Autoconocimiento
- Retirar un modelo, corromper su hash, cambiar permisos y reducir recursos: el estado debe cambiar correctamente y explicar la causa.
- Diferenciar modelo importado, íntegro, cargable, cargado, ejecutado y probado.
- Comprobar que la selección de modelo cambia cuando otro no cabe en RAM o falla.
- Detectar herramientas declaradas pero no registradas/ejecutables y skills instaladas pero no habilitadas.
- Marcar diagnósticos caducados después de cambios de versión, modelo o entorno.
- Ejecutar consulta de autoconocimiento sin Internet y sin invocar servicios remotos.

### Desarrollo en segundo plano
- Iniciar una tarea, cerrar la Activity y comprobar que el trabajo permitido continúa o queda correctamente programado.
- Forzar interrupción/recreación del proceso y verificar recuperación desde el último checkpoint sin repetir efectos.
- Probar pausa, cancelación, batería baja, poco almacenamiento, RAM insuficiente, temperatura elevada y conflicto con generación de audio.
- Generar un fallo reproducible, recoger el log exacto, proponer una corrección, ejecutar la prueba de regresión y limitar reintentos.
- Verificar que el candidato nunca modifica el snapshot original, el APK instalado, Modo3 ni rutas protegidas.
- Confirmar que ningún candidato se integra o publica sin aprobación vigente y vinculada a su hash.
- Ejecutar el flujo completo en modo avión cuando los modelos/herramientas necesarios ya estén instalados localmente.
- Probar tiempos reales, consumo térmico, batería y fiabilidad en Xiaomi 11T Pro; CI por sí solo no certifica estas condiciones.

## 9. Estado observado en la base de código (11-11-2026)

La auditoría de `main` en `64f8fb41a38805b51cf83d81ba59c4ab991cf30e` encontró:

- Existe `BardoSelfInventory`, que recoge dispositivo, recursos, modelos, helpers, módulos y capacidades. Está protegido para revisión humana; no se debe duplicar ni sustituir por un inventario ficticio.
- Existe `BardoModelRegistry` con proveedores locales Gemma LiteRT y Qwen2 LiteRT para el autoestudio, con comprobaciones de integridad/recursos. La disponibilidad y calidad real en el Xiaomi siguen dependiendo de pruebas físicas.
- Existe `BardoDeveloperWorker` basado en WorkManager, con autoestudio local, diario, snapshot aislado, selección de modelos, límites, una propuesta conservadora y candidato aislado.
- Existe `BardoAutonomyEngine` con planificar/actuar/observar/evaluar/corregir, herramientas tipadas, guardas de recursos, presupuesto de hasta doce pasos y dos correcciones. La invocación actual del worker es una auditoría de solo lectura.
- La programación periódica descrita en la documentación es cada seis horas y puede ser retrasada o detenida por Android/Xiaomi.
- La propuesta de código queda pendiente de compilación/pruebas; no existe todavía un ciclo demostrado que procese automáticamente logs de CI, corrija, repita validación y continúe hasta agotar un presupuesto.
- La integración de cambios protegidos y publicación no son automáticas. No se declara desarrollador completamente autónomo hasta probar todo el ciclo.

Las políticas de rutas del repositorio bloquean cambios automáticos en componentes centrales, inventario, proveedores, worker, autoridad, skills, herramientas, evaluadores, workflows y Modo3. Cualquier evolución de esas rutas exige el proceso de revisión humana correspondiente. Este documento no cambia dichas políticas ni concede permisos nuevos.

## 9.1 Hallazgos concretos de la auditoría del runtime

La revisión del código de `main` confirma que ya existen estados de proveedor más ricos que los que expone el inventario general:

- `BardoModelProvider` distingue `MODEL_MISSING`, `MODEL_SIZE_MISMATCH`, `RESOURCE_BLOCKED`, `PRESENT_UNVERIFIED` y `READY`; el proveedor valida SHA-256 y recursos antes de cargar.
- `BardoModelRegistry` puede seleccionar Gemma/Qwen2 locales, cargar, inferir y descargar proveedores; las solicitudes remotas están deshabilitadas por defecto.
- `BardoSelfInventory` actualmente expone para cada modelo principalmente `installed`, tamaño y texto descriptivo. No consulta ni presenta de forma estructurada el estado vivo del proveedor, el último uso, la última inferencia ni su resultado. Por ello no debe anunciar «modelo listo» basándose solo en que el archivo tenga el tamaño esperado.
- El inventario detecta clases integradas y registra descripciones estáticas de algunas pruebas. Esas descripciones no deben interpretarse como una prueba reciente de la versión instalada: la evidencia histórica debe conservar versión, fecha y origen, y mostrarse separada de la telemetría actual.
- El worker construye texto de inventario y lo usa como contexto del autoestudio, pero el propio worker y el inventario son rutas protegidas para edición autónoma. La integración de la autoevaluación viva requiere revisión humana y pruebas específicas; no debe duplicarse el inventario en una clase paralela que quede desconectada.

Este hallazgo concreta el primer cambio funcional prioritario: conectar la pantalla/diagnóstico con el estado estructurado y actualizado de cada proveedor, manteniendo la diferencia entre presencia del archivo, integridad, carga, inferencia y pruebas. Después debe abordarse la persistencia y reanudación del ciclo de desarrollo.

## 10. Criterio de finalización

BARDO solo podrá declararse **operativamente autoconsciente de sus capacidades** cuando pueda responder con datos actuales y evidencia qué modelos/skills/herramientas tiene, qué puede cargar y ejecutar ahora, qué está probado, qué no sabe y por qué.

Solo podrá declararse **desarrollador autónomo en segundo plano** cuando complete de forma repetible el ciclo desde objetivo hasta candidato validado y reanudable, con logs, hashes, pruebas, límites, controles de seguridad y aprobación de integración; deberá demostrarlo también tras interrupciones reales del sistema Android y en el Xiaomi 11T Pro.

La independencia de asistentes externos es requisito permanente: el modo local debe seguir funcionando y desarrollándose sin ChatGPT, JARVIS, Claude, Gemini ni APIs remotas. La red puede ser una herramienta opcional autorizada, no un cerebro ni una dependencia operativa.

## 9.2 Hallazgo de seguridad en la validación aislada de candidatos

La comparación de las salvaguardas de main detectó una divergencia entre la política central y la lista duplicada del workflow de validación:

- BardoProtectedPathPolicy.protectedExact contiene 67 rutas; .github/workflows/bardo-candidate-validation.yml mantiene 60 entradas manuales.
- app/src/main/java/com/musicaia/actualizaciones/BardoIncrementalUpdateManifest.kt está clasificada como HUMAN_REVIEW_REQUIRED por la política central y queda dentro del prefijo permitido por el workflow, pero no aparece en la lista de exclusión de este último.
- En consecuencia, el validador de candidatos podría aceptar una modificación de ese manifiesto, aunque BARDO debería bloquearla para revisión humana.
- Las demás diferencias de las listas detectadas están fuera de la allowlist actual del workflow; no se consideran un bypass de esta misma ruta.

Se registró el hallazgo para corrección y seguimiento en [GitHub issue #7](https://github.com/loboxfake/musica-ia/issues/7). La corrección requiere revisión humana porque .github/workflows/ es una ruta protegida. No se ha modificado el workflow, la política central ni el manifiesto. La regresión exigida debe demostrar que todas las rutas protegidas bajo app/src/main/java/ son rechazadas y comprobar expresamente el manifiesto de actualizaciones.

## 9.3 Límite de memoria en la recepción de parches candidatos

La revisión estática del paso de decodificación del workflow de candidatos detectó que se usa gzip.decompress antes de comprobar el máximo de 384 KiB. El límite posterior no evita que una entrada comprimida con una expansión muy grande se materialice primero en memoria y agote recursos del runner.

El hallazgo quedó registrado en [GitHub issue #8](https://github.com/loboxfake/musica-ia/issues/8). La corrección requiere revisión humana porque el workflow vive bajo .github/workflows/. Se recomienda descompresión incremental con límite duro de salida, validación del tamaño codificado y pruebas de entradas truncadas, corruptas, válidas y de expansión excesiva. No se ha modificado el workflow.

## 9.4 El estado AVAILABLE no equivale a inferencia demostrada

La revisión de BardoCapabilityRegistry y BardoSelfInventory encontró capacidades cuyo estado AVAILABLE se deriva de la presencia de assets/bibliotecas y de umbrales preventivos de recursos, sin exigir una carga e inferencia real recientes. Entre ellas están instrumental (assets + AudioGenNative.available), general_chat/lyrics cuando Qwen está presente y sin bloqueo preventivo, y herramientas WAV/mezcla cuyo criterio de aceptación requiere además validar una salida real. Los detalles ya incluyen algunas advertencias, pero el estado resumido puede confundirse con funcionamiento verificado en ese momento.

El hallazgo se registró en [GitHub issue #9](https://github.com/loboxfake/musica-ia/issues/9). La solución requiere revisión humana porque BardoCapabilityRegistry.kt y BardoSelfInventory.kt están protegidos. Se propone que el estado visible distinga integración, presencia, verificación, carga, inferencia y prueba física, y que la evidencia caduque cuando cambie la versión de APK/modelo. No se ha modificado código protegido.

## 9.5 Modo 3: corrección de la mezcla PCM y validación pendiente en dispositivo

La auditoría encontró que la implementación anterior copiaba byte a byte `instrumental.wav` a `modo3_mezcla.wav`, sin incorporar las muestras de `voz_usuario.wav`. El defecto se corrigió en `main` con el commit `9949e27`:

- El adaptador comprueba que estén presentes los dos WAV y delega la lectura, conversión de frecuencia, mezcla PCM16 y control de pico en `MezclaVoz.mezclar`.
- El resultado se escribe primero en un temporal del mismo directorio.
- Se vuelve a leer y validar el WAV generado con `LectorWav`; solo después se sustituye `modo3_mezcla.wav` con un movimiento atómico cuando está soportado por el sistema de archivos.
- Los archivos originales `voz_usuario.wav` e `instrumental.wav` no se modifican. Si la mezcla falla, se conserva cualquier salida anterior y se devuelve un diagnóstico en `Dato.Texto`, no un `Dato.Audio` falso.

Las pruebas añadidas en `MezclaVozTest` verifican las muestras combinadas de ambas fuentes, remuestreo de 22050 a 44100 Hz, duraciones diferentes, limitación de pico, rechazo de un RIFF corrupto, conservación de ambos originales y conservación de la mezcla anterior ante error.

La validación BARDO del commit `bdeed02d4c1805b7aac65414b855f246e6c6e941` terminó correctamente: Gitleaks, actionlint, pruebas unitarias, compilación del candidato ARM64, firma v2 de depuración, comprobación de bibliotecas ARM64 y carga del artefacto. Evidencia: [GitHub Actions run 43078615297](https://github.com/loboxfake/musica-ia/actions/runs/43078615297).

**Pendiente:** instalar el APK candidato y escuchar una mezcla producida con grabación e instrumental reales en el Xiaomi 11T Pro. La compilación y las pruebas sintéticas no sustituyen esa verificación auditiva, por lo que no se declara Modo 3 validado físicamente.


## 9.6 Ensamblado de segmentos: lectura y escritura WAV acotadas

La revisión del ensamblador encontró que el lector PCM16 cargaba cada WAV completo en un `ByteArray` antes de crear el `ShortArray`, y el escritor reservaba otro búfer del tamaño de toda la salida. En dispositivos móviles, esas copias simultáneas podían elevar innecesariamente el pico de memoria.

Se corrigió en los commits `b2b44ed` y posteriores:

- El lector recorre los chunks RIFF con `RandomAccessFile`, valida límites y alineación y decodifica PCM en bloques de 8192 frames.
- El escritor emite la cabecera y bloques PCM de 32 KiB a un temporal; valida el WAV y lo publica mediante movimiento atómico cuando el sistema de archivos lo permite.
- Se aplica un máximo de 120 MiB por archivo, 12 millones de frames por segmento y 16 millones de muestras PCM acumuladas en los segmentos de entrada.
- Se añadieron pruebas para WAV sobredimensionado, límite agregado de memoria y conservación de canales estéreo intercalados.

La protección de memoria reduce las copias temporales, pero no equivale a una garantía de que cualquier duración pueda mezclarse en cualquier dispositivo: los arrays de muestras y el buffer final de mezcla siguen ocupando memoria. La validación de GitHub de estos últimos commits está pendiente de ejecución; no se declara esta revisión aprobada hasta que sus pruebas terminen.
