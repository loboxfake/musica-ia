# BARDO — Arquitectura del asistente personal evolutivo

Estado: especificación inicial de implementación  
Rama de trabajo actual: `main` (única rama activa; verificación final no destructiva en CI)  
Base histórica de diseño: `a97866a` (`ruta-a-compile`), incorporada como uno de los seis padres del commit de consolidación `28332da4`.

## Objetivo del producto

BARDO es un asistente personal general, local-first y extensible. La creación musical es una capacidad especializada, no el propósito exclusivo de la aplicación. Una sola identidad conversacional y un único orquestador coordinan memoria, modelos, herramientas y módulos.

## Principios no negociables

1. **Honestidad de capacidades:** distinguir entre conocimiento, capacidad disponible y tarea realmente ejecutada. No simular razonamiento, descargas, investigación, generación ni pruebas.
2. **Local-first:** inferencia y memoria en el dispositivo siempre que el hardware y los modelos lo permitan. Internet permanece desactivado para investigación y acciones externas hasta autorización explícita.
3. **Una sola conversación:** todos los módulos reciben contexto autorizado mediante contratos comunes; no crear asistentes aislados por pestaña.
4. **Memoria bajo control del usuario:** historial íntegro con marcas temporales, búsqueda, exportación e importación explícita. Memoria resumida y recuerdos derivados no sustituyen al historial original.
5. **Aprendizaje verificable:** estudiar documentación, modelos, repositorios y técnicas de otras IA no equivale a adquirir sus pesos, datos privados ni capacidades. Cada nueva capacidad requiere fuente/licencia compatible, implementación, pruebas y declaración de límites.
6. **Autoevolución segura:** BARDO puede analizar su proyecto, proponer y preparar parches, módulos, recursos y paquetes de actualización. No puede sustituir su núcleo en ejecución ni instalar código por sí solo sin aprobación.
7. **Integridad y reversión:** cambios atómicos, hashes, pruebas, registro de versiones y posibilidad de volver a una versión conocida.
8. **Preservación de Modo3:** `app/src/main/java/com/musicaia/modulos/Modo3MezclaVozUsuario.kt` es zona protegida y no se modifica en esta línea de trabajo sin autorización explícita independiente.

## Arquitectura lógica

### 1. BardoCore / Orquestador único
Responsabilidades:
- Mantener identidad, sesión activa y contexto conversacional.
- Clasificar la intención: conversación, pregunta, memoria, herramienta, música, aprendizaje o evolución.
- Planificar pasos y seleccionar capacidades registradas.
- Solicitar confirmación antes de permisos, red, cambios de código, instalación o acciones destructivas.
- Devolver resultados con estado: completado, parcial, bloqueado o fallido.

El orquestador no contiene implementaciones específicas de cada herramienta. Invoca contratos tipados y registra resultados.

### 2. ConversationEngine
- Interfaz de proveedor de modelo independiente del motor concreto.
- Proveedor local LiteRT-LM/Gemma cuando esté instalado y sea compatible.
- Preparación de prompts con contexto limitado y fuentes identificadas.
- Manejo de cancelación, errores, tiempos de espera y respuestas vacías.
- No afirmar que el modelo está disponible si no pasa verificación de tamaño, hash, runtime y prueba mínima.
- Si el modelo local no puede responder, explicar la limitación; no presentar el flujo guiado como conversación general.

### 3. MemoryStore
Separar cuatro almacenes:
- **ConversationLog:** mensajes originales, rol, texto, fecha UTC, zona/offset local, conversación e identificador estable.
- **UserMemory:** preferencias y datos persistentes autorizados, con procedencia y fecha.
- **KnowledgeBase:** documentos y referencias importados, fragmentos, fuente, licencia, fecha y hash.
- **TaskJournal:** peticiones, planes, aprobaciones, acciones y resultados.

Reglas:
- Persistencia transaccional; no guardar el chat como un único TextView o string concatenado.
- Fechas por mensaje; nunca reconstruir fechas históricas con la hora actual.
- Búsqueda por conversación, fecha y texto.
- Exportación a JSON completo y TXT legible; incluir metadatos y zona horaria.
- Importación en cuarentena: previsualizar, deduplicar y pedir autorización antes de incorporar a memoria.
- Exportar no significa compartir con terceros.
- Borrar memoria debe ser explícito y separado de borrar archivos musicales.

### 4. LearningEngine
Ciclo de aprendizaje:
1. Detectar una carencia o recibir un objetivo.
2. Formular una pregunta de investigación concreta.
3. Obtener fuentes solo mediante conectividad autorizada o material local.
4. Evaluar procedencia, actualidad, licencia, seguridad y fiabilidad.
5. Extraer conocimiento y registrar citas/fuentes.
6. Proponer una capacidad implementable.
7. Crear prototipo en entorno aislado.
8. Ejecutar pruebas funcionales, rendimiento y consumo.
9. Presentar informe y solicitar aprobación para integrar.
10. Medir el resultado y mantener rollback.

Aprendizaje de otras IA:
- Se permite estudiar documentación pública, artículos, benchmarks, APIs, modelos con licencia compatible y código abierto.
- No se permite eludir controles, extraer pesos propietarios, copiar datos privados, credenciales o prompts internos.
- No se afirma que BARDO ha aprendido una capacidad hasta que la implementación local o herramienta correspondiente haya pasado pruebas.
- Diferenciar memoria/RAG, ajuste fino, destilación y cambio de modelo; no confundirlos.

### 5. CapabilityRegistry / Skills
Cada capacidad declara:
- ID y versión.
- Descripción y esquema de entrada/salida.
- Permisos necesarios (local, archivos, red, micrófono, GitHub, etc.).
- Límites, dependencias, modelo requerido y coste estimado.
- Pruebas y hash del paquete.
- Estado: disponible, no instalada, incompatible, bloqueada o experimental.

Las skills son recetas declarativas y limitadas; el código ejecutable externo no se carga sin verificación y aprobación.

### 6. EvolutionEngine / UpdateManager
Flujo:
- Analizar diferencias y dependencias.
- Generar una copia/paquete candidato aislado en el workspace local; no crear ramas Git paralelas, porque `main` es la única rama activa.
- Ejecutar análisis estático, pruebas unitarias e instrumentadas y auditoría de archivos protegidos.
- Construir APK firmada con la misma identidad de firma para actualización Android, o paquete modular de recursos/datos cuando sea suficiente.
- Generar manifiesto con versión origen/destino, hashes, archivos, permisos y notas.
- Mostrar diff y pedir aprobación explícita.
- Instalar mediante el instalador de Android o aplicar únicamente recursos/datos permitidos.
- Guardar versión anterior y procedimiento de recuperación.

Limitación Android: una app ordinaria no puede reemplazar arbitrariamente sus propios binarios ni autoactualizarse silenciosamente. Las actualizaciones de código deben pasar por el mecanismo de instalación del sistema y cumplir firma/versionCode. Los paquetes pequeños solo son posibles para módulos/datos diseñados para ello; no prometer APK diminutas cuando cambia código nativo o el modelo.

### 7. Security & Approval Gate
- Red desactivada por defecto para LearningEngine.
- Permisos mínimos y por acción.
- Contenido importado tratado como datos no confiables, nunca como instrucciones de sistema.
- No ejecutar comandos arbitrarios provenientes de documentos o modelos.
- Confirmación humana para GitHub, instalación, eliminación, permisos, compras y publicación.
- Auditoría local de acciones y aprobaciones.
- Entorno aislado y límites de CPU, RAM, almacenamiento, batería y temperatura.

## Identidad visual
Paleta derivada del icono Digital Bardic Quill:
- Azul noche `#070D20`
- Zafiro `#123D83`
- Cian iridiscente `#16CFF4`
- Violeta `#713FE8`
- Oro pluma `#D9B86B`

Chat con burbujas diferenciadas, emblema de BARDO, fecha/hora por mensaje, separadores de día, historial consultable y compositor fijo. Evitar un bloque único de texto.

## Orden de implementación y criterios de aceptación

### Etapa A — Núcleo conversacional y datos
- Reemplazar el flujo guiado obligatorio por conversación general cuando un modelo válido esté disponible.
- Persistir mensajes individualmente con timestamps.
- Conservar el flujo de creación musical como una skill del orquestador, no como el controlador del chat.
- Si el modelo falta, ofrecer modo limitado claramente identificado.
**Aceptación:** conversación de varios turnos, cambios de tema, correcciones y continuidad tras cerrar/reabrir; ninguna respuesta ficticia.

### Etapa B — Memoria e historial
- ConversationLog, MemoryStore, búsqueda, exportación JSON/TXT e importación con previsualización.
**Aceptación:** exportar/importar sin perder orden, timestamps, roles ni acentos; comprobar integridad mediante hash.

### Etapa C — LearningEngine
- Ingesta local primero; investigación web solo con permiso explícito.
- Fuentes, licencias, notas y evaluación.
**Aceptación:** informe reproducible con fuentes y distinción entre conocimiento adquirido y capacidad ejecutable.

### Etapa D — Skills y herramientas
- CapabilityRegistry y contratos comunes; conectar módulos musicales existentes.
**Aceptación:** el orquestador elige la skill adecuada y registra resultados reales.

### Etapa E — Autoevolución y actualizaciones
- Generación de parches en sandbox, pruebas, auditoría, manifiesto, firma y aprobación.
**Aceptación:** una actualización de prueba puede instalarse sobre una versión anterior sin pérdida de datos y con rollback comprobado.

### Etapa F — Rediseño visual integral
- Aplicar identidad del icono a chat, navegación, biblioteca, creación, laboratorio y sistema.
**Aceptación:** coherencia visual, accesibilidad, escalado y pruebas en Xiaomi 11T Pro.

## Pruebas obligatorias
- Unitarias y de integración.
- Persistencia tras cierre forzado/reinicio.
- Exportación/importación y recuperación de backup.
- Pruebas de memoria y consumo del modelo en el dispositivo objetivo.
- Verificación de hashes y archivos protegidos.
- Auditoría de APK (package, minSdk, firma, ABI, assets y bibliotecas).
- Instalación y prueba física en Xiaomi 11T Pro antes de declarar la entrega terminada.

## Estado real al iniciar
La versión probada dispone de UI musical, reproductor WAV y un flujo conversacional parcialmente guiado con integración LiteRT-LM condicionada a que el modelo local exista y pase verificación. No se considera todavía un asistente general, una memoria estructurada completa ni un sistema de autoevolución. Esta especificación define el destino; cada función se marcará como implementada solo tras código, pruebas y verificación.
