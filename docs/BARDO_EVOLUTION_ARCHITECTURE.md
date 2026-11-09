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

La primera vía segura son skills declarativas compuestas por acciones tipadas del ToolExecutor. No se carga código ejecutable arbitrario en el proceso principal ni se interpreta una receta como permiso. Todo paquete nuevo se pone en cuarentena y se verifica. La activación exige aprobación explícita, permisos mínimos y un smoke-test ejecutado por BardoCoreSkillSmokeTestRunner, que usa pruebas escritas en handlers Kotlin del APK y emite identificador y referencias de evidencia; la interfaz no puede suministrar un booleano de prueba. El paquete workflow v1 es JSON declarativo de hasta 20 pasos tipados, con entradas de texto acotadas mediante {{input:nombre}}. BardoSkillWorkflowExecutor valida el esquema, la coincidencia exacta con las acciones del manifiesto, la disponibilidad en el catálogo y la integridad SHA-256 antes de cada acción; toda acción pasa por ToolExecutor y los permisos globales. BardoSkillWorkflowToolHandler expone workflows completos al planificador. El runtime está conectado al worker, pero el catálogo solo ofrece cuatro acciones de lectura/estudio y la UI de importación/gestión y descubrimiento sigue pendiente. BardoSkillWorkflowExecutor.inspect(skillId, version) permite una inspección estática sin ejecutar herramientas: devuelve el número de pasos, acciones distintas, entradas requeridas y hash del paquete. Tanto la inspección como la ejecución rechazan marcadores parciales/mal formados; la ejecución valida todos los pasos antes del primero para evitar efectos parciales por un error de parámetros posterior. No se ha añadido una validación del contenido al momento de activar la skill: BardoSkillManager.kt es una ruta protegida que requiere revisión humana específica.

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
8. Tras autorización explícita, crear rama `bardo/<id>` y PR borrador contra `main`.
9. Revisar diff y resultados de CI. BARDO no fusiona el PR.
10. La APK integral solo se genera tras una fusión separada y autorizada a `main`; su instalación corresponde al usuario y Android.

Nunca se hace push automático a `main`, force-push, reset destructivo ni borrado de ramas/remotos. Un candidato obsoleto se rechaza y debe regenerarse desde el nuevo commit base.

El workflow que valida candidatos usa un runner efímero y no recibe el PAT del dispositivo. La rama y el PR se crean con el token efímero de GitHub Actions. El PAT guardado en el teléfono sirve para consultar/encolar workflows y se cifra con Android Keystore.

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

## 12. Estado de implementación al 9 de noviembre de 2026

### Base que ya existía antes de esta rama

Conversación e historial local, biblioteca de conocimiento, inventario de capacidades, catálogo de módulos, integración LiteRT-LM/Gemma, autoestudio estático con propuesta de diff, puente GitHub y módulos musicales/documentales. Cada capacidad conserva el nivel de evidencia declarado en el inventario técnico; no se infiere funcionamiento físico por la presencia de código.

### Propuesta de implementación actual — rama bardo/integral-autonomy-20261109, PR borrador #3

La propuesta de código contiene estos subsistemas, aún pendientes de CI propio, revisión e integración:

- **Autoridad:** BardoAuthorityPolicy y ledger local ECDSA de delegaciones, nonces y revocaciones. No hay todavía emparejamiento real, interfaz ni canal de JARVIS; la conexión es futura y no condiciona la autonomía de BARDO.
- **Núcleo autónomo:** ciclo planificar → actuar → observar → evaluar → corregir, límites de tarea y diario privado. El catálogo de herramientas es cerrado y no existe shell genérico.
- **Modelos:** contrato BardoModelProvider y registro local-first con Gemma/LiteRT como adaptador inicial.
- **Flota multi-IA:** BardoAgentFleet admite proveedores y perfiles especializados, selecciona por capacidad, registra proveedor/versión y consulta modelos secuencialmente. El autoestudio actual conecta solo Gemma; no se afirma que haya una flota de modelos adicionales operativa.
- **Aprendizaje:** BardoLearningJournal guarda respuestas de agentes como candidatos no verificados con SHA-256 y procedencia. Hace falta conectar un verificador independiente de producción; sin él no se promueve ningún candidato a conocimiento fiable.
- **Skills:** BardoSkillManager y BardoSkillManifest admiten paquetes declarativos, cuarentena, integridad, permisos, instalación deshabilitada, aprobación, desactivación y rollback. No ejecutan código descargado.
- **Seguridad y autoevolución:** BardoProtectedPathPolicy, BardoWorkspaceManager, BardoPatchApplier y BardoIndependentEvaluator con pruebas añadidas de forma monotónica.
- **Sesiones LiteRT:** EngineLiteRT.withSession reserva el executor para una operación completa; chat, letras y BARDO Developer se han migrado en la propuesta para evitar que otro módulo cierre el runtime entre carga e inferencia.
- **Documentación:** se actualizan los documentos de arquitectura y evolución con la jerarquía Usuario > JARVIS > BARDO, conexión JARVIS futura e independencia de conversaciones externas.

### Límites y pruebas pendientes

- El PR no modifica workflows, Gradle, firma, secretos ni Modo3; tampoco modifica main ni publica APK.
- El PR #3 todavía no tiene una ejecución de CI propia visible sobre su último SHA. El CI anterior de otra rama no valida esta propuesta.
- No hay aún proveedor adicional a Gemma conectado al worker, ni catálogo completo de adquisición automática de modelos.
- El diario de aprendizaje conserva candidatos, pero no existe aún verificador de aprendizaje de producción.
- Skills instalables siguen restringidas a acciones declarativas ya registradas; falta interfaz, descubrimiento/adquisición y evaluación independiente real de cada nueva skill.
- El evaluador necesita evidencias enlazadas a resultados auténticos de Actions y pruebas físicas; un llamador no debe poder declarar un control como superado sin evidencia.
- La conexión JARVIS es futura y opcional desde el punto de vista operativo; el orden de autoridad sí queda definido desde ahora.
- No hay APK nueva, inferencia comprobada, funcionamiento offline ni pruebas auditivas en Xiaomi 11T Pro asociadas a este PR.
- main sigue sin protección efectiva en GitHub. Deben configurarse las reglas de PR/checks en el repositorio.

## 13. Criterio final de aceptación

BARDO se considerará capaz de una función cuando: el código exista; la política permita la acción; las dependencias/versiones/huellas sean correctas; las pruebas objetivas pasen; el runtime se cargue; la tarea real complete; el resultado sea verificable; y, cuando corresponda, el dispositivo objetivo supere la prueba. Cada nivel debe quedar anotado por separado.
