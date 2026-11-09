# Evidencia de CI y evaluación de candidaturas BARDO

## Objetivo

`BardoCiEvidenceAdapter` convierte respuestas JSON de GitHub Actions en cuatro evidencias tipadas. `BardoCandidateEvaluationPipeline` las entrega al evaluador independiente junto con las auditorías locales y las pruebas de dispositivo aportadas por productores internos.

El adaptador no interpreta explicaciones del modelo ni acepta un simple texto que diga «CI correcto».

## Comprobaciones de procedencia

Antes de crear evidencia, el adaptador exige:

- Repositorio base y repositorio de origen iguales a `loboxfake/musica-ia`.
- Rama `bardo/*` y evento `push`, `pull_request` o `workflow_dispatch`. Este último se admite porque `bardo-commit-candidate.yml` dispara deliberadamente `bardo-validation.yml` sobre la rama candidata; no se aceptan otras ramas ni workflows.
- Workflow exacto `.github/workflows/bardo-validation.yml`.
- SHA Git de 40 caracteres coincidente con el commit que se quiere evaluar.
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

- Auditoría del contenido/firma del APK (`APK_AUDIT`).
- Instalación y arranque en un teléfono.
- Carga o inferencia real de Gemma.
- Funcionamiento offline.
- Generación, lectura o mezcla de WAV.
- Pruebas físicas en Xiaomi 11T Pro.

El adaptador no emite ningún check de dispositivo. El pipeline solo devuelve `READY_FOR_HUMAN_REVIEW` cuando los ocho checks obligatorios de CI están cubiertos y no hay fallos; la validación física sigue siendo una puerta independiente.

## Estado de integración

El productor de auditoría local está conectado a las dos rutas que preparan candidaturas:

- `BardoDeveloperWorker`, para propuestas creadas por el autoestudio en segundo plano.
- `BardoDeveloperActivity`, para propuestas aplicadas manualmente desde BARDO Developer.

Ambas rutas mantienen el candidato en el workspace privado aunque falle la auditoría, pero solo guardan los datos habilitantes para GitHub cuando los cuatro checks locales están presentes exactamente una vez, todos pasan y sus referencias tienen formato/origen local válido. La pantalla vuelve a exigir el indicador persistido de auditoría superada antes de abrir la integración GitHub. La ausencia de evidencia o un error del productor bloquean el envío.

El adaptador y `BardoCandidateEvaluationPipeline` están conectados a `BardoGitHubDevelopmentActivity`. Tras la creación del PR borrador, la pantalla consulta el SHA actual de su rama, localiza exclusivamente la ejecución `workflow_dispatch` de `bardo-validation.yml` para esa rama y ese mismo SHA, descarga run/jobs/artifacts desde la API y recalcula la auditoría local desde el workspace privado. La evaluación solo puede alcanzar `READY_FOR_HUMAN_REVIEW`; nunca marca el dispositivo como verificado por CI.

La consulta se limita al repositorio autorizado y al token fine-grained guardado en Android Keystore. Si no aparece el run, el SHA ha cambiado, el artefacto no coincide, la evidencia local ha sido alterada o la API no es concluyente, la pantalla informa de pendiente/error y no declara aprobación. Todavía no hay un productor operativo de evidencias físicas ni un gate que pueda declarar el dispositivo verificado. El gate local no se confunde con CI ni sustituye la revisión humana.

La creación del PR, su fusión y la publicación Release siguen siendo acciones separadas. BARDO puede preparar una rama y un PR borrador, pero no fusiona ni publica una Release.

## Productor local de evidencias

`BardoLocalCandidateEvidenceCollector` añade una verificación local de solo lectura sobre el snapshot y el candidato:

- Comprueba que snapshot y candidato son carpetas directas del workspace privado y rechaza enlaces simbólicos.
- Contrasta `BARDO_SOURCE_COMMIT.txt` con el SHA de base proporcionado.
- Comprueba que el inventario exacto del candidato coincide con las rutas declaradas y con la política de edición autónoma.
- Reaplica el diff a un segundo candidato temporal y compara SHA-256 de los ficheros afectados con el candidato original.
- Calcula el Git-blob SHA-1 real de Modo3 desde el snapshot y comprueba que esa ruta no está en el candidato ni en el diff.
- Limita recorrido, profundidad, número/tamaño de archivos y elimina la copia temporal tras la comparación.

Los tests usan un Modo3 sintético y un SHA de fixture; no alteran ni dependen del contenido privado real de Modo3. La prueba real de la huella fijada sigue dependiendo del snapshot íntegro que BARDO recibe del proyecto.

El gate `isReadyForCiSubmission` exige exactamente los cuatro controles locales obligatorios, sin duplicados, todos superados y con procedencia local válida. La auditoría se refleja en el informe; un candidato que no pase se conserva para inspección privada, pero se borran los datos de envío y el botón GitHub permanece deshabilitado. Este gate habilita únicamente la petición de CI, no aprueba el parche ni demuestra compilación o funcionamiento en dispositivo.

## Integración de candidaturas y publicación

El workflow `bardo-commit-candidate.yml` no escribe en `main`. Después de verificar una ejecución de validación satisfactoria, su reporte y el SHA-256 del diff, comprueba de nuevo que `main` sigue en el mismo commit, aplica el parche en un runner efímero, repite `testDebugUnitTest assembleDebug`, publica únicamente una rama `bardo/<candidate-id>` y crea un PR borrador. A continuación solicita una ejecución de validación sobre esa rama. No fusiona el PR ni publica una Release.

La pantalla Android conserva una confirmación explícita antes de despachar el workflow. El Fine-grained PAT del usuario solo se usa desde el dispositivo para consultar/activar Actions y revisar el PR; el workflow utiliza el `GITHUB_TOKEN` de ejecución con permisos declarados.

`full-build.yml` puede compilar y subir su artefacto de Actions, pero la publicación GitHub Release exige una ejecución manual `workflow_dispatch` con `publish_release=true`. El valor por defecto es `false` y un push a `main` no publica una Release.

## Última evidencia GitHub verificada — 9 de noviembre de 2026

- Rama: `bardo/integral-autonomy-20261109`.
- Commit: `ecc593e6f9ddbaaf808c445a893d1b75b72bf32b`.
- Run #849 (`pull_request`), ID `42659400800`: SUCCESS.
- Job `validate`, ID `129306559425`: todos los pasos completados con SUCCESS, incluidos Gitleaks, actionlint, tests + build Debug, comprobación del APK y subida del artefacto.
- Artefacto `bardo-debug-candidate-849`, ID `13692984994`, 107.859.774 bytes; ZIP digest SHA-256 `157ff8fba29593fd095912553b7f126e56625d21dd0dfd3a05ef0f41a0397f23`. Expira el 23-11-2026.
- Run #848 (push) también completado con SUCCESS. El ZIP #848 fue auditado localmente contra su digest de GitHub; no atribuir esa auditoría al APK/ZIP #849.
- La variante Debug usa `applicationIdSuffix = ".debug"` y `versionNameSuffix = "-debug"`; el paquete Debug queda separado de Release (`com.musicaia.debug` frente a `com.musicaia`).

Esta evidencia CI no equivale a instalación, APK auditada del run #849, inferencia Gemma, funcionamiento offline, generación WAV ni prueba física en Xiaomi 11T Pro. El PR #3 continúa abierto, en borrador y sin fusionar; main no se ha modificado.
