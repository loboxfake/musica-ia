# Evidencia de CI y evaluación de candidaturas BARDO

## Objetivo

`BardoCiEvidenceAdapter` convierte respuestas JSON de GitHub Actions en cuatro evidencias tipadas. `BardoCandidateEvaluationPipeline` las entrega al evaluador independiente junto con las auditorías locales y las pruebas de dispositivo aportadas por productores internos.

El adaptador no interpreta explicaciones del modelo ni acepta un simple texto que diga «CI correcto».

## Comprobaciones de procedencia

Antes de crear evidencia, el adaptador exige:

- Repositorio base y repositorio de origen iguales a `loboxfake/musica-ia`.
- Rama `bardo/*` y evento `push` o `pull_request`.
- Workflow exacto `.github/workflows/bardo-validation.yml`.
- SHA-1 Git de 40 caracteres coincidente con el commit que se quiere evaluar.
- Ejecución completada, job único `validate` y pasos identificados por sus nombres exactos.
- Artefacto `bardo-debug-candidate-{run_number}` no expirado, con tamaño positivo, mismo ID de ejecución, mismo SHA y digest de formato `sha256:<64 hex>`.

La evidencia conserva una referencia URL canónica a la ejecución. Si el workflow sigue pendiente o la procedencia no coincide, el adaptador no crea evidencia.

## Qué demuestra

| Check | Fuente verificada |
|---|---|
| `GITLEAKS` | Paso de escaneo completo del historial |
| `ACTIONLINT` | Paso de lint de workflows |
| `UNIT_TESTS` | Paso Gradle `testDebugUnitTest assembleDebug` |
| `DEBUG_BUILD` | Compilación, comprobación de APK y subida de artefacto asociadas al mismo run/SHA |

El workflow Gradle ejecuta tests y compilación en un único paso, por lo que el adaptador conserva esa misma granularidad.

## Qué no demuestra

La evidencia de GitHub Actions **no** demuestra:

- Auditoría de contenido/firma del APK (`APK_AUDIT`).
- Instalación y arranque en un teléfono.
- Carga o inferencia real de Gemma.
- Funcionamiento offline.
- Generación, lectura o mezcla de WAV.
- Pruebas físicas en Xiaomi 11T Pro.

El adaptador no emite ningún check de dispositivo. El pipeline solo devuelve `READY_FOR_HUMAN_REVIEW` cuando los ocho checks obligatorios de CI están cubiertos y no hay fallos; la validación física sigue siendo una puerta independiente.

## Estado de integración

La clase adaptadora y el pipeline tienen tests unitarios. En esta revisión todavía no se ha conectado su invocación al ciclo de `BardoDeveloperWorker` ni a una pantalla de promoción. No se presenta por tanto como un gate operativo automático ni como aprobación de candidaturas. Esa integración requiere revisar el flujo de desarrollo protegido y validar el consumo de la API con la credencial de repositorio del usuario.

La creación del PR, su fusión y la publicación Release siguen siendo acciones separadas; este código no las ejecuta.
