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


## Implementación actual de la fase BARDO

La rama bardo/autonomy-foundation-20261109 introduce el primer bucle operativo local de planificación, ejecución de acciones tipadas, observación, evaluación y corrección. El worker de autoestudio lo utiliza con el proveedor Gemma/LiteRT local y herramientas de lectura limitadas al inventario BARDO, biblioteca importada, snapshot estático y diagnósticos CI.

Las tareas se guardan en un diario JSONL privado. El diario registra eventos, identificadores y resultados resumidos; no guarda el contenido completo de argumentos/salidas. La ejecución tiene límites de tiempo, pasos y correcciones.

El ToolExecutor usa un catálogo cerrado. Los planes de Gemma son propuestas de datos: un ID de herramienta desconocido, un scope no autorizado o unos argumentos fuera de límite se rechazan antes de ejecutar. No hay shell genérico.

El SkillManager puede poner paquetes declarativos en cuarentena, comprobar tamaño y SHA-256, instalarlos deshabilitados y habilitarlos solo con aprobación, permisos y smoke-test. También permite deshabilitar, revertir a una versión probada y retirar un paquete del almacén BARDO. No carga código ejecutable descargado y no elimina el original del usuario.

El flujo de desarrollo GitHub prepara ramas bardo/* y PR borrador después de una autorización explícita. No escribe ni fusiona main. La compilación de APK completa solo firma y publica cuando un usuario inicia manualmente el workflow y marca publish_release=true.

## Límites todavía abiertos

El canal criptográfico User/JARVIS aún carece de interfaz de emparejamiento, distribución segura de claves y transporte autenticado entre el ordenador y Android. El verificador ECDSA y el ledger están añadidos, pero sin ese emparejamiento no hay que presentar órdenes remotas de JARVIS como activas.

El bucle actual investiga localmente. No se ha añadido aún un cliente de búsqueda web general ni una red de agentes remotos. La coordinación multi-IA existe como objetivo y contratos iniciales, no como flota de agentes comprobada.

El gestor de skills no descarga paquetes por sí solo ni inventa handlers: solo admite acciones que ya estén registradas. La integración UI para explorar fuentes, importar, revisar licencias y conceder permisos queda pendiente.

main no está protegida en la configuración actual de GitHub. La protección de PR/checks debe activarse en el repositorio para que la regla exista también fuera del código de BARDO.
