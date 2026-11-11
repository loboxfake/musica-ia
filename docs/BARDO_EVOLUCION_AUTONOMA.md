# BARDO — Evolución autónoma gobernada

**Estado:** especificación del objetivo y registro del estado realmente implementado en \`main\`. Una capacidad no se considera operativa solo porque figure en el diseño, en un catálogo o en una propuesta del modelo.

## 1. Autoridad y límites permanentes

La jerarquía de gobierno prevista es: usuario > JARVIS > BARDO > modelos/agentes/skills/herramientas. El usuario conserva la decisión final. JARVIS todavía no está conectado técnicamente; no se fingirá autenticación, emparejamiento ni recepción de órdenes de JARVIS.

BARDO no debe depender de ChatGPT, Claude, Gemini ni otra conversación externa para funcionar o aprender. Los modelos son proveedores consultivos subordinados, intercambiables y opcionales. La inferencia de autoestudio configurada en el Worker usa exclusivamente modelos locales, con \`allowRemoteModels = false\`.

Ningún modelo puede ampliar por sí mismo sus permisos, reescribir el evaluador, cambiar las rutas protegidas, aprobar una operación o convertir una hipótesis en evidencia.

## 2. Objetivo de evolución completo

El objetivo futuro de BARDO es un ciclo controlado:

1. **Observar:** recoger solo inventario y diagnósticos locales autorizados.
2. **Diagnosticar:** separar mediciones, hechos del código, hipótesis y datos aún no disponibles.
3. **Priorizar:** seleccionar una tarea pequeña según valor, recursos, riesgo y dependencias.
4. **Proponer:** identificar archivos, diff, criterio de éxito, riesgos y pruebas.
5. **Preparar:** aplicar el cambio en una copia aislada; nunca en el APK instalado ni en el snapshot original.
6. **Validar:** ejecutar pruebas y compilación reproducibles sobre el candidato exacto.
7. **Corregir:** usar el resultado real de las pruebas para preparar una nueva propuesta acotada.
8. **Evaluar:** recalcular evidencia de procedencia independiente; el modelo no se evalúa a sí mismo.
9. **Solicitar autorización:** mostrar el diff, las rutas, los riesgos y los resultados antes de cualquier integración/publicación.
10. **Publicar y recuperar:** producir la APK integral desde el commit identificado y conservar una vía de reversión.

**Este ciclo completo todavía no está implementado.** En particular, la ejecución que genera propuestas no compila por sí sola cada candidato ni repara de manera autónoma hasta conseguir un resultado verde.

## 3. Implementación real en main

### 3.1 Autoestudio persistente en Android

\`BardoDeveloperActivity\` puede activar una tarea inmediata o programar \`BardoDeveloperWorker\` mediante WorkManager cada seis horas. El trabajo periódico exige que el teléfono esté cargando, con batería suficiente y almacenamiento disponible. Android/Xiaomi puede retrasarlo o detenerlo; forzar la detención de la aplicación impide confiar en la continuidad del trabajo.

El Worker:
- Usa un snapshot privado de BARDO y registra el origen del snapshot.
- Comprueba que la generación de audio no esté activa antes de iniciar el autoestudio.
- Captura el inventario y comprueba los recursos del dispositivo.
- Selecciona proveedores locales Gemma LiteRT y Qwen2 LiteRT según presencia, integridad y capacidad disponible; no descarga modelos automáticamente.
- Aplica límites de RAM, duración, pasos y correcciones; si los recursos no son seguros, bloquea o solicita reintento.
- Ejecuta tareas con herramientas tipadas del catálogo cerrado. No expone una shell genérica ni ejecuta código del proyecto inspeccionado.
- Conserva informes y memoria en el almacenamiento privado; la exportación SAF a Descargas/BARDO es secundaria y puede fallar sin perder la copia privada.

El trabajo puede sobrevivir al cierre de la Activity gracias a WorkManager. Esto no equivale a que ChatGPT trabaje en segundo plano ni garantiza que el sistema Android lo ejecute inmediatamente.

### 3.2 Motor de tareas planificar–actuar–observar–evaluar

\`BardoAutonomyEngine\` admite planes acotados con un máximo de doce pasos y dos correcciones, presupuesto temporal y guardas de recursos. Cada paso debe declarar un criterio literal de salida no vacío y verificable; si falta, el plan se bloquea antes de ejecutar herramientas. Una respuesta no vacía, por sí sola, ya no basta para declarar un paso superado.

El Worker usa este motor para una tarea de estudio de solo lectura: inventario, referencias y auditoría estática. Esa invocación no autoriza cambios en los archivos. El diario local registra estados y resultados sin guardar argumentos ni contenidos privados completos.

El motor genérico puede actuar sobre otras herramientas tipadas que estén registradas y permitidas, pero eso no implica que el Worker tenga habilitadas operaciones de compilación, instalación, publicación o modificación de \`main\`.

### 3.3 Propuestas de código aisladas

Después de la auditoría estática, \`BardoDeveloperWorker\` consulta la flota local y solicita como máximo una corrección pequeña y conservadora. El modelo debe devolver un diff unificado; si no hay diff aplicable, no se aplica ningún cambio.

El sistema:
- Verifica que los extractos de código citados coincidan literalmente con el snapshot cuando usa el verificador de fuente.
- Extrae el diff sin arrastrar el bloque de evidencia ni explicaciones posteriores.
- Rechaza rutas protegidas, archivos nuevos/renombrados, dependencias, cambios de build, workflows y secretos.
- Aplica el parche a una copia aislada con \`BardoPatchApplier\`.
- Ejecuta una auditoría estática posterior y \`BardoLocalCandidateEvidenceCollector\`, que revisa el SHA de base, inventario, reaplicación del diff, hashes de los archivos y la protección de Modo3.
- Guarda el candidato y el informe en el workspace privado. La propuesta queda marcada como pendiente de compilación y pruebas.

**No se modifica el código instalado, no se modifica el snapshot original y no se declara que el diff funciona solo porque se haya aplicado a la copia.**

### 3.4 Envío remoto de un candidato

La pantalla GitHub puede enviar manualmente un diff BARDO elegible a \`.github/workflows/bardo-candidate-validation.yml\`. Exige procedencia BARDO autorizada, contexto libre de datos privados, auditoría local recalculada y un PAT fine-grained guardado cifrado en Android Keystore.

Ese workflow ejecuta validación aislada en un runner efímero: confirma el SHA base, aplica el parche, comprueba rutas, ejecuta Gitleaks y \`testDebugUnitTest assembleDebug\`, y produce un informe. No crea ramas, no crea PR, no realiza commits y no modifica \`main\`.

La ejecución remota no ocurre automáticamente desde el Worker: actualmente requiere la acción desde la interfaz. Tampoco existe un ciclo automático que lea cada fallo de CI, genere otro candidato, vuelva a enviarlo y repita hasta conseguir éxito.

### 3.5 Build integrado en main

\`.github/workflows/bardo-validation.yml\` valida el código que ya está integrado en \`main\`: escanea el historial, ejecuta actionlint, tests y compilación ARM64, comprueba la firma APK v2/certificado Debug y que las bibliotecas nativas sean ARM64, y publica un artefacto Debug.

Ese workflow prueba el commit integrado concreto; no convierte automáticamente una propuesta aislada en una modificación de \`main\`. El artefacto es una compilación Debug, no una Release, y CI no sustituye la instalación ni la prueba física.

### 3.6 Integración en main, PR y publicación

La política \`BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED\` está habilitada. Desde la pantalla GitHub, el usuario puede autorizar expresamente un único commit directo a \`main\` después de que el candidato exacto haya superado la validación aislada. El workflow vuelve a comprobar el ID de candidato, la ejecución de validación, el SHA base, el hash del diff y la lista de archivos; repite Gitleaks, tests y compilación ARM64, comprueba que \`main\` no haya avanzado y utiliza un push normal sin force. Después solicita la validación completa del nuevo SHA de \`main\`. Esta integración no se ejecuta automáticamente ni crea ramas, PR o Releases. El workflow legacy \`bardo-commit-candidate.yml\` está deshabilitado.

En esta línea no hay un flujo operativo de creación de PR, fusión automática ni publicación de Release. No se debe activar la integración a \`main\` hasta disponer de un flujo revisado que no pueda sobrescribir trabajo y mantenga autorización explícita. La configuración de protección de ramas en GitHub debe verificarse en el propio repositorio; no se presupone a partir de una política Kotlin.

## 4. Memoria y aprendizaje

\`BardoLearningJournal\` conserva aportaciones de modelos como candidatas con procedencia. El verificador de extractos puede confirmar que un texto coincide literalmente con una región del snapshot oficial; eso demuestra procedencia textual, no que la interpretación o recomendación del modelo sea verdadera.

No se deben presentar respuestas de modelos como aprendizaje verificado ni como entrenamiento de pesos. La promoción de conocimiento fiable exige un verificador independiente adecuado para cada tipo de afirmación, por ejemplo resultados reproducibles de tests o evidencias revisadas. La evaluación global de código todavía no se usa como gate operativo de promoción en el Worker.

## 5. Evaluación independiente y evidencias

\`BardoIndependentEvaluator\` distingue evidencia local, CI y dispositivo mediante orígenes y referencias tipadas. Sus estados son:
- \`INCOMPLETE\`: falta evidencia obligatoria.
- \`REJECTED\`: un control falla o su procedencia no es válida.
- \`READY_FOR_HUMAN_REVIEW\`: controles CI/locales completos; requiere revisión humana.
- \`DEVICE_VERIFIED\`: además están presentes todas las pruebas físicas obligatorias.

\`BardoCiEvidenceAdapter\` valida los datos originales de ejecución, jobs/pasos y artefactos del workflow principal \`bardo-validation.yml\`. La pantalla GitHub, después de confirmar que el commit del candidato está en \`main\`, busca la ejecución que corresponde exactamente al SHA integrado, obtiene esos tres documentos API y conecta el adaptador con \`BardoCandidateEvaluationPipeline\` y la auditoría local recalculada. Muestra el estado del evaluador, controles superados/fallidos/pendientes y los problemas de procedencia. La validación aislada del parche (\`bardo-candidate-validation.yml\`) no se confunde con la validación del commit integrado. CI completo solo alcanza \`READY_FOR_HUMAN_REVIEW\`; no produce \`DEVICE_VERIFIED\` sin las siete pruebas físicas independientes.

La evidencia CI nunca puede demostrar por sí sola:
- instalación y arranque en el Xiaomi;
- inferencia real de Gemma/Qwen en el dispositivo;
- operación en modo avión;
- generación WAV real, reproducción y escucha;
- mezcla de la grabación/voz del usuario con los archivos reales de Modo3.

Esas pruebas deben tener referencias de origen \`DEVICE_TEST\` y realizarse en el dispositivo objetivo. No se infieren de bibliotecas incluidas, del estado de un botón, ni de un build verde.

## 6. Seguridad de candidatos y archivos protegidos

- Rutas absolutas, traversal, enlaces simbólicos y rutas fuera del workspace se rechazan.
- Hay límites de archivos, profundidad y tamaño.
- Modo3 permanece protegido por ruta y huella Git-blob SHA-1. Esta línea no autoriza modificarlo.
- El extractor de diffs, el router del flujo guiado, la política del avance musical, el motor de autonomía y las políticas de evidencia CI requieren revisión humana.
- Las pruebas existentes, evaluadores, políticas, workflows, firma, secretos y configuración de build no son editables por el generador autónomo.
- Los proyectos ZIP importados se trabajan localmente; no son candidatos elegibles para enviarse al repositorio BARDO.
- No se ejecuta código importado ni scripts suministrados por modelos.
- No se hacen force-push, reset destructivo ni borrado remoto.

## 7. Skills declarativas

Las skills entran primero en cuarentena, con esquema, licencia, tamaño y SHA-256 comprobados. Instalación y activación son operaciones separadas; la activación requiere autorización, compatibilidad, permisos y smoke-tests de confianza escritos en la APK.

Los workflows de skills son declarativos y solo pueden llamar acciones registradas mediante el ToolExecutor. No se ejecutan scripts externos ni código descargado arbitrario. El catálogo actual dispone de un conjunto limitado de herramientas tipadas locales; una skill no puede crear por sí misma nuevos handlers nativos. La adquisición automática de nuevas acciones/capacidades sigue pendiente.

## 8. JARVIS y control remoto

La conexión técnica JARVIS–BARDO no está implementada. Antes de habilitarla se requiere emparejamiento de claves públicas con consentimiento, autenticación de cada orden firmada, nonce, caducidad, alcance mínimo, registro y revocación. No se concederá acceso ilimitado al ordenador ni se ejecutarán órdenes no autenticadas.

JARVIS es una integración futura y no debe ser requisito para que BARDO funcione localmente.

## 9. Próximos hitos verificables

1. (Conectado en la interfaz) Mostrar una evaluación determinista con datos API originales de la validación de \`main\` para el SHA integrado y la auditoría local recalculada. La pantalla distingue CI pendiente/fallido y revisión humana pendiente; no inventa pruebas de dispositivo.
2. Implementar un ciclo de candidato que recopile logs concretos de CI, genere una corrección acotada y repita la validación aislada, sin tocar \`main\` automáticamente.
3. Demostrar el ciclo en un conjunto de fallos reproducibles y pruebas de regresión, con límites de tiempo/RAM y un punto de parada seguro.
4. Mantener la decisión de integración/publicación separada y autorizada; primero validar un flujo de PR seguro antes de habilitarlo.
5. Probar la APK exacta y todas las funciones críticas físicamente en el Xiaomi 11T Pro.

Hasta completar esos hitos, BARDO es un sistema con autoestudio local, planificación tipada y generación/auditoría de candidatos aislados, pero **todavía no un desarrollador completamente autónomo que pueda mantener MusicalIA sin supervisión**.
