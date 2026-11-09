# BARDO — Arquitectura general de autonomía, aprendizaje y evolución

Estado: arquitectura objetivo y registro de implementación. Este documento no convierte una función pendiente en una función disponible.

## 1. Identidad y jerarquía de autoridad

BARDO es un asistente personal generalista, local-first, modular y evolutivo. La creación musical es una especialidad del sistema, no su propósito exclusivo. Existe una identidad conversacional de BARDO; Gemma, otros modelos y los agentes especializados son recursos intercambiables subordinados.

Jerarquía normativa:

1. **Usuario:** autoridad máxima y decisión final. Puede autorizar, limitar, detener, corregir y revocar cualquier delegación; prevalece sobre JARVIS.
2. **JARVIS:** segunda autoridad, por encima de BARDO y subordinada únicamente al usuario. Es el supervisor de confianza que opera en el ordenador del usuario; ejerce las facultades que el usuario le delegue.
3. **BARDO:** núcleo autónomo que planifica, investiga, coordina recursos, desarrolla su ecosistema y ejecuta tareas dentro de los límites fijados por el usuario y JARVIS.
4. **Modelos, agentes, skills y herramientas:** recursos subordinados coordinados por BARDO. No son autoridades, no conceden permisos, no cambian políticas y no pueden declarar válida su propia salida.

La conexión técnica JARVIS–BARDO se realizará en el futuro y no es una dependencia del ciclo autónomo. BARDO debe seguir funcionando, aprendiendo y desarrollándose aunque JARVIS aún no esté conectado o no esté disponible. Cuando se integre, la conexión deberá emparejar claves públicas con aprobación del usuario y autenticar cada petición con firma, nonce, caducidad y alcance. Las delegaciones tendrán permisos mínimos, caducidad, revocación y registro auditable. Hasta entonces, JARVIS no debe presentarse en la app como autenticado ni disponer de acceso remoto implícito.

Una instrucción directa del usuario prevalece sobre cualquier orden de JARVIS. Ninguna delegación a JARVIS concede autoridad a Gemma, agentes o skills. BARDO no dependerá de Claude, Gemini, ChatGPT ni conversaciones externas para desarrollar, investigar, probar o corregirse.

## 2. Principios permanentes

- Local-first: inferencia, archivos y memoria permanecen en el dispositivo cuando sea viable.
- Independencia operativa: BARDO no necesita a Claude, Gemini, ChatGPT, este asistente, JARVIS ni conversaciones externas para desarrollar, aprender y corregirse. JARVIS es una autoridad superior cuya conexión técnica se construirá en el futuro; modelos externos son recursos opcionales.
- Modelo independiente: el núcleo no debe depender de una familia, proveedor o formato de modelo concreto.
- Una sola identidad y un contexto común, con aislamiento por tarea y permisos.
- Evidencia antes que afirmaciones: código, instalación, carga, ejecución y prueba física son estados distintos.
- Seguridad aplicada por código: los prompts no son controles de acceso.
- Aprendizaje verificable: estudiar una fuente no significa haber adquirido una capacidad ejecutable.
- Evolución reversible: cada cambio tiene origen, diff, pruebas, hash, estado y ruta de rollback.
- Protección de datos y privacidad: acceso mínimo a los archivos/carpetas expresamente autorizados.

## 3. Núcleo BARDO

El núcleo debe coordinar componentes a través de contratos, no contener toda la lógica de cada especialidad.

- **BardoCore:** identidad, sesión, políticas, estado, objetivos y coordinación.
- **ConversationEngine:** construye el contexto autorizado y llama al proveedor de modelo elegido.
- **ModelProvider:** adaptador independiente de Gemma/LiteRT, otros modelos locales y proveedores remotos autorizados.
- **Orchestrator:** descompone objetivos, selecciona skills y coordina pasos y recursos.
- **ToolExecutor:** ejecuta herramientas tipadas con permisos declarados, límites y cancelación. No ejecuta comandos arbitrarios dictados por un modelo.
- **MemoryStore:** historial, memoria del usuario, biblioteca de conocimiento y diario de tareas separados.
- **LearningEngine:** investigación, evaluación de fuentes, extracción y seguimiento de aprendizajes.
- **SkillManager:** catálogo, instalación, pruebas, versiones, activación y reversión de skills.
- **EvolutionEngine:** workspace aislado, propuestas, validación, PR y seguimiento del resultado.
- **IndependentEvaluator:** evalúa resultados usando criterios y pruebas que el modelo que propone el cambio no puede modificar.

El registro actual de módulos y capacidades ya ofrece una base para la orquestación, pero aún no equivale a un sistema completo de providers, herramientas dinámicas o skills instalables.

## 4. Modelos y equipo multi-IA

Cada proveedor debe declarar identidad, versión, licencia, formato, huella, capacidades, tamaño, requisitos de memoria/CPU/GPU, contexto máximo, límites y estado real.

Un adaptador traduce el contrato común de BARDO al runtime del modelo. La salida incluye proveedor/modelo, tiempo, estado, errores y uso de recursos cuando esté disponible.

BARDO puede repartir tareas entre modelos/agentes especializados, por ejemplo: planificación, programación, revisión de seguridad, pruebas, documentación, audio, visión y voz. El registro de agentes debe declarar proveedor, versión, rol, capacidades, licencias, hash/procedencia, permisos y límites. BardoAgentFleet añade selección por capacidad, perfiles especializados y consultas secuenciales; los perfiles que apuntan al mismo proveedor no se cuentan como opiniones independientes.

Las respuestas se conservan con procedencia. Son propuestas no confiables y nunca órdenes. El aprendizaje se registra primero como candidato con SHA-256, tarea, proveedor, versión y fecha. Solo un evaluador independiente puede promover una lección a memoria fiable. Una respuesta convincente o repetida no equivale a evidencia, y conservarla no significa modificar los pesos del modelo.

El modelo remoto solo puede usarse con una autorización que cubra el envío de esos datos; por defecto permanece denegado. La flota debe funcionar local-first y administrar RAM, temperatura y concurrencia. Tener muchos modelos disponibles no significa que todos deban residir simultáneamente en el Xiaomi.

## 5. Skills y herramientas

Una skill es un paquete identificable y versionado que declara:

- ID, nombre, versión y compatibilidad de API.
- Descripción y esquema de entrada/salida.
- Capacidades y permisos solicitados.
- Dependencias, modelo requerido, coste y límites de recursos.
- Origen, licencia, hash y firma/procedencia.
- Pruebas de aceptación, resultado y plan de reversión.

El SkillManager debe poder descubrir, proponer, crear, importar, adaptar, validar, instalar, habilitar, actualizar, deshabilitar y revertir skills.

Descargar o copiar un paquete no equivale a instalarlo. Instalarlo no equivale a activarlo. Activarlo no equivale a demostrar que funciona.

La primera vía segura son skills declarativas compuestas por acciones tipadas del ToolExecutor. No se carga código ejecutable arbitrario en el proceso principal ni se interpreta una receta como permiso. Todo paquete nuevo se pone en cuarentena y se verifica. La activación exige aprobación explícita, permisos mínimos y un smoke-test ejecutado por BardoCoreSkillSmokeTestRunner, que usa pruebas escritas en handlers Kotlin del APK y emite identificador y referencias de evidencia; la interfaz no puede suministrar un booleano de prueba. El paquete workflow v1 es JSON declarativo de hasta 20 pasos tipados, con entradas de texto acotadas mediante {{input:nombre}}. BardoSkillWorkflowExecutor valida el esquema, la coincidencia exacta con las acciones del manifiesto, la disponibilidad en el catálogo y la integridad SHA-256 antes de cada acción; toda acción pasa por ToolExecutor y los permisos globales. BardoSkillWorkflowToolHandler expone al planificador operation=inspect sin ejecución y operation=execute para la ejecución deliberada. El runtime está conectado al worker, pero el catálogo solo ofrece cuatro acciones de lectura/estudio y la UI de importación/gestión y descubrimiento sigue pendiente. BardoSkillWorkflowExecutor.inspect(skillId, version) permite una inspección estática sin ejecutar herramientas: devuelve el número de pasos, acciones distintas, entradas requeridas y hash del paquete. Tanto la inspección como la ejecución rechazan marcadores parciales/mal formados; la ejecución valida todos los pasos antes del primero para evitar efectos parciales por un error de parámetros posterior. No se ha añadido una validación del contenido al momento de activar la skill: BardoSkillManager.kt es una ruta protegida que requiere revisión humana específica.

## 6. Aprendizaje e investigación

Ciclo de aprendizaje:

1. Detectar una carencia o recibir un objetivo.
2. Formular una pregunta concreta.
3. Recopilar fuentes locales o remotas según permisos.
4. Comprobar procedencia, fecha, licencia, integridad y fiabilidad.
5. Extraer hechos con referencias y límites.
6. Comparar con lo que BARDO ya sabe y registrar discrepancias.
7. Formular una propuesta implementable.
8. Crear prototipo aislado.
9. Evaluar con pruebas, seguridad, rendimiento y recursos.
10. Registrar el resultado, los errores y la siguiente mejora.

La memoria de conocimiento almacena referencias y resúmenes; no modifica mágicamente los pesos del modelo. No confundir RAG, memoria, ajuste fino, destilación o cambio de proveedor.

Los documentos, repositorios y respuestas de modelos son datos no confiables. Las instrucciones encontradas dentro de esos materiales nunca sustituyen a las reglas de autoridad.

## 7. Bucle autónomo de ejecución

Toda tarea importante sigue el ciclo:

**Planificar → actuar → observar → evaluar → corregir → registrar.**

Cada plan declara objetivo, pasos, dependencias, permisos, límites de tiempo/recursos y criterios de éxito. Cada acción registra entrada, salida, estado y evidencias. Un fallo se clasifica (código, modelo, dato, recurso, red, plataforma o infraestructura) antes de reintentar.

El evaluador debe comparar la salida con criterios objetivos y pruebas independientes. Una respuesta convincente del modelo no es una medición de éxito. Las pruebas y políticas protegidas no se pueden borrar o relajar para que un candidato aparezca aprobado.

## 8. Autoevolución y GitHub

BARDO estudia su propio código como texto, prepara cambios en una copia aislada y los somete a validación. El código importado no se ejecuta durante el análisis local.

Flujo acordado:

1. Obtener un snapshot inmutable de un commit exacto de `main`.
2. Revisar diagnóstico, memoria técnica y referencias autorizadas.
3. Generar un cambio pequeño, acotado y reproducible.
4. Aplicar el diff solo en una copia candidata, con rutas seguras.
5. Rechazar Modo3, núcleo protegido, workflows, configuración, firma y secretos.
6. No modificar ni borrar pruebas existentes; las pruebas nuevas se añaden, nunca sustituyen las antiguas.
7. Validar en runner efímero: análisis, tests y compilación.
8. Tras autorización explícita, crear rama `bardo/<id>` y PR borrador contra `main`, pero solo mediante un workflow auditado que no escriba directamente en `main`.
9. Revisar diff y resultados de CI. BARDO no fusiona el PR.
10. La APK integral solo se genera tras una fusión separada y autorizada a `main`; su instalación corresponde al usuario y Android.

Nunca se hace push automático a `main`, force-push, reset destructivo ni borrado de ramas/remotos. Un candidato obsoleto se rechaza y debe regenerarse desde el nuevo commit base.

**Bloqueo de seguridad vigente (9-11-2026):** la auditoría del workflow heredado `.github/workflows/bardo-commit-candidate.yml` encontró que ejecuta `git push origin HEAD:refs/heads/main`. Esto contradice el flujo de rama + PR. La app añade una política que bloquea la llamada de integración antes de cualquier petición de red y deshabilita el botón de PR. La validación aislada del candidato permanece permitida. El workflow heredado no se ha ejecutado desde esta corrección. La integración seguirá cerrada hasta disponer de un workflow auditado que cree una rama y un PR borrador sin escribir en `main`.

El workflow de validación usa un runner efímero y no recibe el PAT del dispositivo. El PAT guardado en el teléfono se cifra con Android Keystore y permite iniciar/consultar validaciones; la integración no se habilita mientras el flujo de PR sea inseguro.

La protección de rama de GitHub debe configurarse en el propio repositorio para exigir PR y checks. El estado de configuración debe comprobarse; no se presume activada.

## 9. Aislamiento, datos y reversión

- Workspace privado distinto del snapshot original.
- Rechazo de rutas absolutas, traversal, enlaces simbólicos y entradas no regulares.
- Límites explícitos de ficheros, tamaños, tiempo, RAM, batería y temperatura.
- Ninguna skill/modelo recibe permisos de archivos o red por estar registrado.
- Hashes para modelos, referencias, artefactos y salidas verificables.
- Registro de propuestas, aprobaciones, pruebas, PR y rollback.
- La reversión restaura una versión conocida sin borrar el historial ni los originales del usuario.

## 10. Memoria y datos

Separar:

- ConversationLog: mensajes originales y fechas por mensaje.
- UserMemory: preferencias/datos persistentes autorizados y su procedencia.
- KnowledgeBase: documentos y referencias con licencia, fuente y hash.
- TaskJournal: objetivos, planes, permisos, acciones, evaluaciones y resultados.
- SkillRegistry: manifiestos, versiones, permisos y pruebas.
- ModelRegistry: modelos/proveedores, huellas y estados verificados.

No escanear otras aplicaciones ni paquetes externos. Los permisos SAF quedan restringidos a la ubicación BARDO seleccionada por el usuario.

## 11. Especialidades

La creación musical, voz, mezcla, portadas, visión, OCR, transcripción, lectura documental, programación, investigación y automatización son skills especializadas que comparten la misma identidad, memoria autorizada, contratos y política de seguridad.

Las capacidades musicales actuales se mantienen como especialidades. La generación instrumental, creación de letras, mezclas y exportaciones deben seguir superando pruebas reales de WAV/MP3 y escucha. La presencia de modelos/assets no demuestra calidad ni funcionamiento.

## 12. Auditoría consolidada de autonomía — 9 de noviembre de 2026

### Reconciliación de PR #2 y PR #3

- PR #2 modifica 34 rutas; las 34 están presentes en PR #3, que contiene 83 rutas modificadas frente a main.
- En la comparación de blobs, 10/34 archivos son idénticos y 24/34 tienen contenido diferente. Los 24 parches de PR #3 son iguales o más extensos que los de PR #2. Se revisaron las diferencias funcionales principales en workflows, UI de integración, motor/worker, patcher, política de rutas, workspace, evaluador, herramientas, registro de capacidades, proveedores LiteRT, autoridad y skills. Las diferencias observadas son versiones posteriores/ampliadas o cambios de contrato, no una pérdida de las capacidades de PR #2.
- No se realiza cherry-pick/merge ciego de los 29 commits divergentes de PR #2 porque ambos historiales modifican los mismos archivos y PR #3 contiene implementaciones posteriores. PR #2 se conserva como referencia abierta; su cierre queda sujeto a revisión humana.
- PR #3 está 294 commits por delante y 0 por detrás de main; respecto a PR #2, está 294 por delante y 29 por detrás. El diff actual contiene 83 rutas.
- main permanece en `386dc7fd23629b5620b6907272ca7d0fc19c6404`. No se ha modificado.
- Modo3 conserva el blob SHA-1 `8f2745fa841a06af43bf909732fba834474bf7dc`, idéntico en main y PR #3, y no aparece entre las rutas modificadas.

### Capacidades de BARDO que están en el código

- **Motor/worker local:** ciclo planificar → actuar → observar → evaluar → corregir; Gemma/LiteRT como único proveedor conectado al autoestudio; flota capaz de registrar perfiles pero sin otros proveedores operativos. Los modelos remotos se rechazan por defecto.
- **Herramientas:** catálogo cerrado, acciones tipadas y permisos acotados. No hay shell genérico ni ejecución de código arbitrario descargado.
- **Candidatos de código:** snapshot original, workspace aislado, diff de archivos existentes, política de rutas protegidas y evaluación con evidencias tipadas. El worker no modifica el proyecto original.
- **Skills:** manifiestos declarativos, cuarentena, SHA-256, permisos, instalación deshabilitada, activación aprobada, desactivación y rollback lógico. BardoCoreSkillSmokeTestRunner está conectado al runtime y prueba handlers escritos en el APK; actualmente hay cuatro acciones locales de lectura/estudio. La UI de catálogo/importación guiada y el descubrimiento/descarga automática siguen pendientes.
- **Autoridad:** política ECDSA, delegaciones, nonces y revocaciones. JARVIS es opcional y no condiciona BARDO; emparejamiento y transporte real de JARVIS aún no están implementados.
- **Aprendizaje:** BardoLearningJournal conserva respuestas como candidatos con procedencia y SHA-256. No existe todavía un verificador independiente de producción conectado que pueda promoverlos a conocimiento fiable.
- **Modelos musicales:** Stable Audio Open Small/ExecuTorch está incluido en la APK candidata. Gemma E2B no está embebido: se importa por separado y se debe comprobar su SHA-256 antes de cargarlo.
- **Almacenamiento móvil:** herramientas SAF acotadas, cola de solicitudes y aprobación de la UI antes de acciones destructivas; faltan pruebas instrumentadas y físicas.

### Actualizaciones incrementales

- BardoIncrementalUpdateManifest, factory y política fail-closed están integrados en BardoDeveloperWorker.
- El worker calcula hashes SHA-256 reales de los archivos del snapshot y del candidato, huella del diff, nivel de cambio, referencias de evidencia y rollback; valida contra BardoProtectedPathPolicy y persiste el JSON con el candidato. Si falla la auditoría o el manifiesto, el candidato no se envía.
- Las pruebas cubren hash real de archivos, rutas inseguras/protegidas, Modo3, permisos, evidencia, rollback, migraciones irreversibles y alcance por paquete.
- Cada actualización exige aprobación humana. Los cambios de código/recursos requieren APK completa; cambios de contrato/estructurales requieren revisión arquitectónica. La política no instala nada.
- **Pendiente:** instalador transaccional de paquetes, backup/restauración de datos realmente probado, UI de aprobación/instalación y reversión ejecutable. No hay autoactualización silenciosa ni hot-swap de código.

### Evidencia CI y APK

- Validación push #943, run `42714580951`, sobre commit `712ee97c16576f9fd10e216512025d7ff3804c24`: SUCCESS en Gitleaks, actionlint, tests unitarios, compilación Debug ARM64, firma v2, certificado Android Debug, verificación de bibliotecas ARM64 y subida de artefacto.
- Candidata unificada #18, run `42714581001`, sobre el mismo commit: SUCCESS en tests, build ARM64, auditoría del paquete completo de modelos, JNI, integridad ZIP y firma v2. Artefacto `bardo-unified-offline-arm64-candidate-18`, ID `13717164778`, 1.001.731.518 bytes, digest del artefacto `sha256:9d2b6d919406a98eaa683f2b8a5660c97324a20b95fb5eeac16ebdcf63f5256e`.
- Datos del APK contenido en ese artefacto: 1.001.723.247 bytes; SHA-256 `3de1fda9ba5870da77947cb17dc97f4f67f5fd7b4b045c82ef3aa2db5a4b25de`; paquete `com.musicaia.debug`; versionCode 1018; versionName `1.0.1018-debug`; compileSdk 35; minSdk 26; targetSdk 34; 10 bibliotecas nativas ARM64; firma Debug v2, certificado SHA-256 `8ed9171a32c1126af0bf232511455d651b7b45a16459be5ce34819efcbed481f`.
- El snapshot incrustado de esa candidata contiene 211 archivos, 1.662.530 bytes descomprimidos. Incluye los modelos Stable Audio: conditioners `415fa3a6e5e235f6b37045de1655272deec5af80a155cb70d9c90e9e82b4440f`, DiT `3ad4bd2aac6b8705e86886e1d4d2b6f2647f9abd806267e86146dfdaff8de19d`, autoencoder `8cf1d70f3c6442c25adb8c9ca9d037d7c6d4c3aec970cfefcb82a35ca61b7069` y SentencePiece `d60acb128cf7b7f2536e8f38a5b18a05535c9e14c7a355904270e15b0945ea86`.
- La candidata #18 corresponde al commit de aplicación 712ee. Después se actualizó el pin de Stable Audio de full-build al artefacto verificado run #5 (ID `13713725273`, digest `sha256:c0e6773df66ceedca06dc694239a79ad559ff372a3193e05e063f9a6d11d9c1a`) y se amplió el disparador de snapshot para cambios de workflows/documentación.
- Por ello, la candidata #19, run `42716313686`, se está construyendo sobre el HEAD más reciente `d9262c716d9e71959734dbaff0a7308dae9c8745`; la validación push #947 también está en curso. Se usará #19 si termina correctamente, ya que incorpora el snapshot de workflows actualizado.
- El exportador por fragmentos todavía está fijado a la candidata anterior; se actualizará al artefacto de #19 solo tras comprobar éxito y digest. No se declara APK final descargable hasta reconstruirla y verificar localmente todos los fragmentos y el SHA-256 interno.

### Lo que esta fase no puede certificar

No se ha instalado ni ejecutado BARDO en el Xiaomi 11T Pro. Quedan para la fase de instalación y pruebas: arranque, permisos, importación/hash/carga/inferencia Gemma offline, modo avión, memoria/temperatura, SAF, generación WAV real y escucha, mezcla de voz y pruebas de rollback. La compilación CI no demuestra estas capacidades físicas.

### Gobernanza

PR #2 y PR #3 permanecen abiertos en borrador; no se ha fusionado ninguno, no se ha publicado Release y main sigue intacta. No se encontraron rulesets de repositorio; la integración no tiene permiso para consultar la protección de rama clásica, así que su estado no se presume. Los workflows auditados preparan ramas bardo/* y PR borrador y no escriben directamente en main.

## 13. Criterio final de aceptación

BARDO se considerará capaz de una función cuando: el código exista; la política permita la acción; las dependencias/versiones/huellas sean correctas; las pruebas objetivas pasen; el runtime se cargue; la tarea real complete; el resultado sea verificable; y, cuando corresponda, el dispositivo objetivo supere la prueba. Cada nivel debe quedar anotado por separado.


## 14. Protocolo obligatorio de actualizaciones incrementales

Después de la primera instalación y validación física, las mejoras ordinarias deben entregarse como cambios pequeños y aislados, no como reconstrucciones completas del proyecto. Este protocolo es requisito del EvolutionEngine y de cualquier herramienta de desarrollo autónomo de BARDO.

1. **Identificar la versión base:** registrar package/versionCode, commit exacto de origen, versión de esquema/API y hashes de los componentes relevantes.
2. **Diagnosticar antes de editar:** reproducir el fallo o definir el requisito; guardar logs y una prueba de referencia. No modificar código por una hipótesis sin evidencia.
3. **Clasificar el alcance:** cambio local de módulo, cambio de contrato entre módulos o cambio estructural transversal. Elegir el menor alcance que resuelva el problema.
4. **Crear una propuesta mínima:** diff limitado a los archivos necesarios; no reformatear ni reescribir archivos ajenos. Preservar APIs públicas y compatibilidad cuando sea posible.
5. **Proteger datos y rutas:** nunca sobrescribir modelos importados, grabaciones, proyectos, exportaciones, claves, secretos ni Modo3 por una actualización de módulo. Las migraciones de datos deben ser explícitas, versionadas, transaccionales y con copia/restauración.
6. **Pruebas por impacto:** añadir pruebas de regresión; ejecutar pruebas del módulo, contratos afectados y suite completa. No borrar, saltar ni debilitar pruebas existentes para lograr verde.
7. **Compatibilidad y recursos:** comprobar Android/API, ABI, RAM, almacenamiento, temperatura, permisos, formatos de proyecto y dependencias. Los modelos se actualizan por separado de la APK, con licencia, tamaño y SHA-256.
8. **Paquete de actualización:** generar un manifiesto que identifique versión anterior y nueva, commit base, archivos/cambios, hashes, dependencias, migraciones, permisos, pruebas, limitaciones y rollback. Rechazar paquetes incompletos, obsoletos o con hash incorrecto.
9. **Canal seguro:** validar en runner aislado y crear una rama/PR borrador después de autorización. Nunca escribir directamente en main, fusionar, publicar Release ni instalar sin autorización expresa.
10. **Despliegue gradual:** primero candidato Debug firmado con identidad de firma de prueba estable; después instalación y prueba en el Xiaomi. Solo promover a distribución estable mediante un proceso de firma y aprobación separado.
11. **Reversión:** conservar la versión anterior y datos originales. Si falla un smoke test, restaurar el módulo/paquete anterior o desactivar la capacidad sin borrar datos.
12. **Excepción estructural:** solo se permite una actualización amplia cuando el cambio cruza varios contratos/capas, exige migración de arquitectura o la solución incremental sería más insegura. Debe documentarse el motivo y plan de transición.

### Clases de actualización

- **Nivel 1 — contenido/configuración:** catálogo, prompts locales, manifiestos o parámetros validados. No recompilar si el formato y las políticas permiten actualizarlo de forma segura.
- **Nivel 2 — módulo aislado:** cambio de una especialidad o skill con contrato estable; paquete pequeño y pruebas específicas.
- **Nivel 3 — contrato compartido:** cambio coordinado de API entre módulos; actualizar consumidores y pruebas de compatibilidad.
- **Nivel 4 — estructural:** núcleo, almacenamiento, seguridad, modelo de permisos, runtime o migración de datos. Requiere revisión ampliada y plan de rollback.

BARDO debe mostrar al usuario el nivel, el motivo, los archivos afectados, los riesgos, el tamaño, la evidencia de pruebas y la opción de aplazar o rechazar la actualización. No debe instalar silenciosamente cambios de código ni conceder permisos nuevos por sí mismo.

Este protocolo describe el comportamiento exigido; su inclusión en documentación no demuestra que la interfaz ni el actualizador estén implementados. Esa implementación deberá auditarse y probarse antes de habilitar autoactualizaciones.

## 15. Reconciliación de PR #2 y PR #3 y estado del actualizador incremental

### Reconciliación de contenido

La comparación de las ramas bardo/autonomy-foundation-20261109 (PR #2) y bardo/integral-autonomy-20261109 (PR #3) encontró los 34 paths de PR #2 dentro de PR #3. De esos 34 archivos, 10 conservan el mismo blob; los otros 24 tienen una versión distinta en PR #3. Los 34 paths también aparecen en el diff de PR #3 contra main; en los 24 distintos, el parche de PR #3 tiene el mismo o mayor tamaño que el parche de PR #2. Esto es una comprobación de cobertura y evolución del diff, no una prueba matemática de equivalencia semántica línea por línea.

La decisión de reconciliación es conservar PR #3 como rama integral y no hacer cherry-pick/merge ciego de los 29 commits divergentes de PR #2: ambos cambios se solapan en los mismos archivos, y el contenido actual de PR #3 incorpora versiones posteriores de los componentes de autonomía. PR #2 permanece como referencia abierta; no se fusiona ni se borra. Antes de cerrar PR #2 definitivamente, un revisor humano debe poder comparar el diff final y decidir su cierre. main sigue sin cambios.

### Manifiesto incremental conectado al worker local

Se añade BardoIncrementalUpdateManifest, BardoIncrementalUpdateManifestFactory y BardoIncrementalUpdatePolicy. El worker de desarrollo local crea el manifiesto a partir de los archivos reales del snapshot y del candidato, calcula SHA-256 anterior/nuevo y huella del diff, exige evidencia de auditoría local, valida rutas contra BardoProtectedPathPolicy y conserva el manifiesto JSON en las preferencias del candidato. Si la auditoría o el manifiesto fallan, el candidato no se habilita para envío a GitHub.

La política bloquea rutas protegidas, Modo3, archivos de build, workflows, assets/modelos y rutas inseguras; limita el tamaño y número de archivos; exige hashes, versión, commit base, evidencia, permisos aprobados y referencia de rollback; impide migraciones irreversibles y cambios de módulo que cruzan paquetes. Toda actualización requiere aprobación humana; los cambios de contrato/estructurales además quedan marcados para revisión arquitectónica. Los cambios de código o recursos requieren APK completa; la documentación puede tratarse como actualización de configuración.

**Límite explícito:** esto prepara y valida la propuesta incremental, pero no instala módulos en caliente, no aplica actualizaciones binarias por sí solo y no prueba restauración real de datos. El actualizador transaccional/rollback ejecutable y la interfaz de aprobación/instalación siguen pendientes. Android debe instalar cualquier APK de código mediante su mecanismo normal y con identidad de firma compatible. No se habilita autoactualización silenciosa.

### Evidencia de validación

La revisión de PR #3 confirma que el workflow de candidatos crea una rama bardo/* y PR borrador, no un push a main; full-build.yml exige dispatch manual y publish_release=true para firmar/publicar. Los workflows y la política de rutas protegen Modo3 y los archivos críticos. La rama integral permanece abierta en borrador y no está fusionada. Los nuevos tests del manifiesto deben pasar en CI antes de considerar este código aceptado.

## 16. Revisión con repositorio privado — 10 de noviembre de 2026

### Visibilidad, ramas y protección

- GitHub confirma que el repositorio es privado y la conexión de trabajo puede leer PR, ramas, ejecuciones y artefactos.
- `main` continúa en `386dc7fd23629b5620b6907272ca7d0fc19c6404`; no se ha escrito en ella.
- GitHub informa que `main` tiene `protected=false` y no tiene reglas de protección activas. Esto no cambia la regla operativa acordada: BARDO y este flujo no escribirán directamente en `main`. Para que GitHub haga cumplir la regla incluso ante otros clientes, el propietario debe activar protección de rama/ruleset, exigir PR y checks.
- PR #2 y PR #3 siguen abiertas, en borrador y sin fusionar. PR #2 conserva la base fundacional. PR #3 es la candidata integral. La comparación de historial entre ambas sigue divergida: PR #3 está 296 commits por delante y 29 por detrás de PR #2. No se deben encadenar merges ni cherry-picks automáticos.
- Los 34 archivos de PR #2 están presentes en el diff de PR #3; 10 parches son idénticos y 24 difieren. Las diferencias tienen mayor tamaño de parche en PR #3, pero el tamaño no demuestra equivalencia semántica completa. La reconciliación se considera funcionalmente cubierta en rutas, pero la equivalencia de cada cambio exclusivo sigue requiriendo revisión de comportamiento y pruebas; no se declara equivalencia matemática de commits.

### Candidata integral y exportación

- La ejecución `42716917671` terminó correctamente para el SHA `96498e5acdb998cbe8ca4c93dbc5c03d19b758ce`. Produjo el artefacto `bardo-unified-offline-arm64-candidate-21`, 1.001.732.046 bytes, digest `sha256:cd5248f108c52d825123fbb173062508d3dc66080b690c554d315fb2c2a33078`.
- Esa candidata superó Gitleaks, actionlint, tests unitarios, compilación Debug ARM64, auditoría de assets/modelos/JNI, integridad y firma Debug. No incluye el cambio posterior del avatar ni su test.
- El exportador por fragmentos estaba fijado a un artefacto anterior. Se actualizó para apuntar a la candidata 21, pero su ejecución `42720562614` falló y la API de logs no devolvió el registro de pasos.
- Tras el cambio del avatar, los workflows `42720610999`, `42720611055` y `42720619166` también fallaron; sus reintentos terminaron igual y GitHub no proporcionó steps/logs descargables. No se atribuye la causa al código ni a la falta de PAT sin evidencia. La nueva validación del test `7f2e8ea20c839d8e2f61ff40422c777e4f36a13c` quedó en cola al registrar esta nota.
- El token personal de GitHub no es necesario para que Actions use su `GITHUB_TOKEN` en sus propios workflows; la app móvil sí necesita un token fine-grained guardado por el usuario para despachar validaciones/PR. Sin ese token, la publicación desde la app debe detenerse con un mensaje claro. No se ha intentado crear PR ni publicar Release desde este proceso.

### Avatar e interfaz

- `BardoAvatarView` conserva el icono oficial de BARDO y añade perfiles de movimiento diferentes para escucha, lectura, estudio, pensamiento, respuesta, generación y espera. En reposo, éxito y error no mantiene animación continua; se respeta el ajuste de animaciones del sistema y se conserva una descripción accesible.
- Se añadió `BardoAvatarStateTest` para verificar que los estados activos tienen perfiles acotados y que reposo/estados terminales permanecen estáticos.
- El layout principal ya está basado en secciones y navegación por espacios; no se ha sustituido ni rediseñado de forma amplia en esta revisión. La optimización visual adicional debe hacerse por componentes reutilizables y sin duplicar funciones ni alterar flujos musicales ya probados.

### Criterio de aceptación

La candidata 21 es una compilación auditada de un SHA anterior, no la candidata final con avatar. El cambio de avatar y su test no se aceptarán hasta que Actions vuelva a ejecutar correctamente. La exportación por fragmentos debe finalizar, reconstruirse y verificarse por SHA-256 antes de entregar un APK descargable. Ningún estado de CI sustituye la instalación, inferencia Gemma, operación sin red, generación y escucha WAV ni pruebas de mezcla en el Xiaomi 11T Pro.
