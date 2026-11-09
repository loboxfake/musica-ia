# BARDO — Evolución autónoma gobernada

Estado: especificación operativa. Los controles que no estén implementados y probados se marcan como pendientes.

## Autoridad

El usuario es la autoridad final. JARVIS, que opera en el ordenador del usuario, es supervisor de confianza únicamente dentro de la delegación explícita que reciba. El usuario puede anular una orden de JARVIS. BARDO, Gemma, otros modelos, agentes, skills y herramientas no pueden concederse permisos ni cambiar la jerarquía.

La futura conexión JARVIS–BARDO debe emparejar claves públicas con aprobación del usuario y autenticar cada orden firmada con nonce, caducidad, alcance y registro. La delegación debe poder revocarse. Mientras ese protocolo no esté implementado y probado, BARDO no debe fingir que JARVIS está autenticado ni obedecer una orden externa solo porque diga proceder de JARVIS.

## Ciclo de evolución

1. **Observar:** recopilar solo diagnósticos locales permitidos.
2. **Diagnosticar:** separar datos medidos, hipótesis y limitaciones.
3. **Proponer:** identificar el problema, beneficio, riesgo, permisos, ficheros y pruebas.
4. **Preparar:** trabajar en copia aislada; nunca modificar el original ni la APK instalada.
5. **Validar:** ejecutar pruebas y compilación reproducibles en un runner efímero.
6. **Solicitar aprobación:** mostrar diff, checks y riesgos antes de crear la rama/PR.
7. **Abrir PR:** crear rama `bardo/<id>` y PR borrador. No escribir ni fusionar `main`.
8. **Evaluar:** revisar CI y resultados; corregir fallos en candidato nuevo.
9. **Publicar:** APK integral solo después de una fusión separada y autorizada a `main`.
10. **Revertir:** conservar versión conocida y ruta de recuperación.

## Estados válidos

`draft`, `proposed`, `authorised`, `candidate`, `validated`, `pull_request_open`, `merged`, `device_verified`, `rejected`, `failed`, `reverted`.

No saltar de propuesta a función disponible. La ausencia de respuesta no autoriza. Una autorización cubre solo el candidato, ficheros, operaciones y alcance mostrados.

## Seguridad del candidato

- Rutas absolutas, traversal y enlaces simbólicos se rechazan.
- El workspace tiene límites de cantidad y tamaño.
- Modo3 y el núcleo protegido requieren una autorización humana independiente; Modo3 se mantiene bloqueado en esta línea.
- Las pruebas existentes son inmutables; las nuevas se añaden, nunca sustituyen las antiguas.
- Workflows, scripts de CI, build, firma, secretos y protecciones no son editables por el ciclo autónomo.
- No ejecutar código importado ni ejecutar scripts proporcionados por modelos.
- El runner de validación no recibe el PAT del teléfono ni secretos privados.
- El token del dispositivo se guarda con Android Keystore y permisos mínimos para Actions.
- No force-push, reset destructivo ni borrado remoto.

## Seguridad de skills

Cada skill debe declarar ID/versiones/API, capacidades, permisos, origen, licencia, SHA-256, dependencias, límites y pruebas. Los paquetes descargados entran en cuarentena. Se comprueba integridad y compatibilidad antes de instalar; se prueba aislada; la activación persistente requiere autorización. La primera implementación debe preferir skills declarativas de acciones tipadas; no cargar código externo arbitrario en el proceso principal.

## Evaluador independiente

El modelo que propone no puede modificar las pruebas, el evaluador ni la política que juzga su propuesta. El evaluador comprobará tests, compilación, rutas, huellas, dependencias, permisos y criterios objetivos. La puntuación del propio modelo es solo una opinión, no evidencia de éxito.

## Límites de Android

Una aplicación Android ordinaria no puede sustituir silenciosamente su APK instalada ni eludir la firma y el instalador del sistema. Una compilación verde no equivale a prueba física. La instalación y la escucha de WAV deben realizarse en el Xiaomi 11T Pro antes de declarar el funcionamiento validado.
