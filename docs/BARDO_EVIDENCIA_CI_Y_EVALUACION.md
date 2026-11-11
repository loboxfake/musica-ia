# Evidencia de CI y evaluación de candidaturas BARDO

## Regla de interpretación

Una ejecución verde acredita únicamente los pasos que realmente ejecutó el workflow y que el adaptador puede verificar. No acredita por sí sola la instalación, el arranque, la inferencia local, el uso offline ni la generación/escucha de audio en un teléfono.

Este documento distingue tres flujos que no deben confundirse.

## 1. Validación de un candidato aislado

Workflow: \`.github/workflows/bardo-candidate-validation.yml\`

Se inicia manualmente desde la pantalla de desarrollo de BARDO, con un ID de candidato, SHA base y diff empaquetado. Comprueba que \`main\` sigue en el SHA base, aplica el parche en un runner efímero, rechaza rutas protegidas/no permitidas y cambios que no sean modificaciones de archivos existentes, ejecuta Gitleaks y \`testDebugUnitTest assembleDebug\`, y publica un informe de validación.

Este flujo:
- No crea una rama ni un pull request.
- No realiza commits ni modifica \`main\`.
- No publica un APK como artefacto de salida; publica un informe de validación del candidato.
- No ejecuta el lint de actionlint, la verificación de firma v2 ni el chequeo de ABI ARM64 del workflow principal.

Que esta validación termine correctamente significa que ese diff, aplicado a ese SHA, pasó las comprobaciones incluidas en este workflow. No significa que haya sido integrado ni probado físicamente.

El workflow legacy \`bardo-commit-candidate.yml\` está deshabilitado. La integración a \`main\` desde BARDO también permanece bloqueada por \`BardoGitHubIntegrationPolicy.MAIN_COMMIT_WORKFLOW_ENABLED = false\`. No se debe interpretar la presencia del archivo \`bardo-main-commit-candidate.yml\` como una función disponible en la aplicación.

## 2. Validación del código integrado en main y APK ARM64

Workflow: \`.github/workflows/bardo-validation.yml\`

Se ejecuta con \`push\` a \`main\` o mediante \`workflow_dispatch\`. Su job \`validate\` incluye:
- Escaneo del historial completo con Gitleaks.
- Lint de los workflows con actionlint.
- \`testDebugUnitTest assembleDebug -PbardoTargetAbi=arm64-v8a\`.
- Comprobación de que existe el APK.
- Verificación de la firma APK v2 y del certificado Android Debug.
- Comprobación de que las bibliotecas nativas incluidas son exclusivamente ARM64.
- Subida del APK como artefacto de Actions, identificado por número de ejecución y con retención temporal.

El artefacto \`bardo-debug-arm64-candidate-{run_number}\` es una compilación Debug para evaluación, no una Release. La asociación del artefacto a un run y SHA concretos ayuda a establecer su procedencia, pero no sustituye una auditoría independiente del contenido ni una instalación real.

Workflow adicional: \`.github/workflows/android.yml\` (\`Build APK (manual)\`). También ejecuta tests y \`assembleDebug\`, comprueba la existencia del APK y sube \`bardo-debug-apk\`. No contiene las comprobaciones explícitas de firma v2 ni ABI ARM64 del workflow \`bardo-validation.yml\`; no se deben atribuirle esos resultados.

## 3. Adaptador y evaluador de evidencias

\`BardoCiEvidenceAdapter\` interpreta respuestas de GitHub Actions para el workflow exacto \`.github/workflows/bardo-validation.yml\`. Comprueba repositorio, rama \`main\`, evento admitido, SHA, estado del run, job único \`validate\`, nombres/estados/conclusiones de pasos y artefacto no expirado asociado al mismo run y SHA, con digest de formato SHA-256.

Solo produce evidencia CI de:
- \`GITLEAKS\`
- \`ACTIONLINT\`
- \`UNIT_TESTS\`
- \`DEBUG_BUILD\`

No produce evidencia para \`APK_AUDIT\`, instalación, Gemma, operación offline, generación o escucha de WAV, ni mezcla de voz del usuario.

\`BardoCandidateEvaluationPipeline\` combina esas evidencias con las evidencias locales y las entrega a \`BardoIndependentEvaluator\`. La API del pipeline y del adaptador dispone de pruebas unitarias, pero **en el estado actual no está conectada a una ruta de ejecución de la interfaz Android**. No debe presentarse como una evaluación automática ya operativa en BARDO. La pantalla GitHub actual consulta y muestra estados/resúmenes de Actions; no genera a partir de ellos un informe completo \`BardoEvaluationReport\`.

El evaluador distingue:
- \`INCOMPLETE\`: faltan evidencias CI obligatorias.
- \`REJECTED\`: existe un control fallido o evidencia con procedencia no válida.
- \`READY_FOR_HUMAN_REVIEW\`: CI completa y evidencias locales requeridas, pendiente de revisión humana.
- \`DEVICE_VERIFIED\`: además están presentes los siete controles físicos obligatorios con origen \`DEVICE_TEST\`.

Un CI verde no puede producir por sí solo \`DEVICE_VERIFIED\`.

## 4. Auditoría local del candidato

\`BardoLocalCandidateEvidenceCollector\` verifica de forma local y de solo lectura:
- Que snapshot y candidato estén dentro del workspace privado permitido, sin enlaces simbólicos.
- Que \`BARDO_SOURCE_COMMIT.txt\` coincida con el SHA base esperado.
- Que el inventario de archivos coincida con las rutas declaradas y la política de edición autónoma.
- Que reaplicar el diff en una copia temporal produzca los mismos SHA-256 en los archivos afectados.
- Que Modo3 no forme parte del parche y conserve su Git-blob SHA-1 esperado.
- Que el recorrido y los tamaños permanezcan dentro de límites establecidos.

Los tests del colector usan fixtures sintéticos para el contenido de Modo3. La comprobación de la huella real depende de que BARDO tenga un snapshot completo y auténtico del repositorio. Además, la pantalla GitHub vuelve a ejecutar el colector antes de enviar un candidato; no confía solo en el booleano persistido de una auditoría anterior.

La auditoría local habilita, cuando pasa, la solicitud de validación aislada; no aprueba el parche ni prueba el dispositivo.

## 5. Estado y límites conocidos

- BARDO puede realizar autoestudio local mediante WorkManager cuando el usuario lo activa, existe un modelo de texto local íntegro y Android concede las condiciones de ejecución. El trabajo puede sobrevivir al cierre de la Activity; Android/Xiaomi puede retrasarlo o detenerlo por restricciones del sistema.
- El Worker analiza el código y puede preparar propuestas en un workspace aislado. No compila por sí mismo cada candidato en un ciclo autónomo, no repara automáticamente hasta conseguir una compilación verde, no integra automáticamente a \`main\` y no instala una APK nueva.
- El envío a GitHub requiere el flujo de interfaz, el PAT fine-grained guardado en Android Keystore y la acción explícita del usuario. No se permite que el modelo envíe código de proyectos importados ni datos privados del dispositivo.
- La aprobación de integración a \`main\` sigue deshabilitada en la política de la aplicación.
- No hay en el repositorio un productor operativo de evidencias físicas que permita marcar el Xiaomi como verificado.

## 6. Evidencias que siguen siendo independientes

Para declarar el producto probado en el Xiaomi 11T Pro aún hacen falta, como mínimo:
1. Instalar la APK identificada por el SHA/artefacto exacto que se vaya a evaluar y comprobar que arranca.
2. Verificar inferencia real del modelo local que BARDO declare activo, incluida su integridad, carga y respuesta.
3. Repetir las funciones necesarias en modo avión para confirmar el funcionamiento offline.
4. Generar un WAV real, reproducirlo y escucharlo; probar la mezcla de voz del usuario con los archivos reales de Modo3.
5. Revisar logs de ejecución y comprobar estabilidad térmica/RAM en el dispositivo.

Las evidencias de usuario/dispositivo deben conservar su origen y referencia. No deben inferirse a partir de un build verde, de la presencia de bibliotecas nativas ni de una pantalla que indique que una tarea terminó.

## Última ejecución

Actualizar esta sección únicamente después de consultar la API de GitHub para la ejecución concreta. Registrar SHA, workflow, run ID, conclusión y digest del artefacto si existe. No reutilizar un digest de otra ejecución ni convertir la salida de un workflow en evidencia de otro.
