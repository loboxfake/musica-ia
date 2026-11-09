# BARDO — Evolución autónoma gobernada

Estado: especificación operativa. Los controles que no estén implementados y probados se marcan como pendientes.

## Autoridad

La jerarquía de gobierno es invariable: usuario > JARVIS > BARDO > modelos/agentes/skills/herramientas. El usuario es la autoridad máxima y la decisión final. JARVIS es la segunda autoridad, está por encima de BARDO y subordinado únicamente al usuario. BARDO dirige su propio desarrollo y coordina los recursos; los modelos, agentes y skills nunca son autoridades.

La conexión técnica JARVIS–BARDO se construirá en el futuro y no es requisito para que BARDO funcione, aprenda o se desarrolle. Hasta entonces no se fingirá que JARVIS está autenticado ni se bloqueará el ciclo autónomo por no tener esa conexión. Cuando se integre, deberá emparejar claves públicas con aprobación del usuario y autenticar cada orden firmada con nonce, caducidad, alcance y registro. La delegación será revocable y no concederá acceso ilimitado al ordenador.

BARDO no dependerá de Claude, Gemini, ChatGPT, este asistente ni conversaciones externas para investigar, programar, probar, corregirse o evolucionar. Otros modelos serán recursos consultivos intercambiables y opcionales.

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


## Implementación actual de la propuesta integral — PR borrador #3

La rama bardo/integral-autonomy-20261109 amplía el primer ciclo autónomo con una flota neutral respecto al proveedor. BardoAgentFleet selecciona perfiles por capacidad, registra proveedor/versión y consulta proveedores secuencialmente para reducir picos de RAM. En el autoestudio actual solo está registrado Gemma/LiteRT como primer proveedor; el contrato permite añadir otros, pero no hay aún varios modelos operativos.

BardoLearningJournal guarda respuestas de modelos como candidatos no verificados, con SHA-256, tarea, proveedor, versión, perfil y fecha. La recuperación de conocimiento fiable solo admite estados verificados. El diario exige un BardoLearningVerifier independiente para promover; todavía no hay un verificador de producción conectado, por lo que hoy las respuestas permanecen como candidatas y no se presentan como aprendizaje confirmado ni como entrenamiento de pesos.

BardoDeveloperWorker ya consulta la flota para elaborar propuestas de código y conserva la procedencia de la respuesta. El planificador del bucle autónomo y el ejecutor de herramientas siguen siendo independientes del proveedor, con Gemma como adaptador actual.

Se añade EngineLiteRT.withSession para reservar el executor durante una secuencia completa de carga e inferencia, evitando que el cierre de otro módulo se intercale entre operaciones de una misma sesión. El flujo de chat, BARDO Developer y letras se ha migrado a sesiones atómicas en esta propuesta; todavía requiere CI y pruebas de concurrencia/runtime.

El ToolExecutor usa un catálogo cerrado. Los planes del modelo son propuestas de datos: IDs de herramientas desconocidos, scopes no autorizados y argumentos fuera de límite se rechazan antes de ejecutar. No hay shell genérico.

El SkillManager permite cuarentena, comprobación de tamaño/SHA-256, instalación deshabilitada y activación con aprobación, permisos y smoke-test declarado. También permite deshabilitar, rollback y retirada. Sigue limitado a paquetes declarativos y acciones registradas; no ejecuta código descargado ni elimina originales del usuario.

El PR no modifica workflows, configuración de build, firma ni secretos; no toca Modo3 y no cambia main. No hay APK nueva ni pruebas físicas asociadas a esta revisión.

## Límites todavía abiertos

La política ECDSA y el ledger de nonces/revocaciones están en el PR, pero no existe interfaz de emparejamiento, distribución segura de claves ni transporte autenticado entre el ordenador y Android. JARVIS no está conectado; su integración futura no puede ser requisito para el funcionamiento autónomo de BARDO.

La flota puede recibir varios proveedores y perfiles, pero el autoestudio solo registra Gemma local. Faltan adaptadores adicionales reales, catálogo de descubrimiento/adquisición de modelos y evaluación comparativa independiente. Los modelos remotos permanecen denegados por defecto y requieren una autorización independiente.

El diario de aprendizaje guarda propuestas no verificadas. Falta conectar un verificador independiente real a resultados CI, benchmarks y evidencias reproducibles para promover lecciones a memoria fiable.

El gestor de skills no descarga paquetes por sí solo ni crea automáticamente handlers: solo admite acciones ya registradas. La UI para explorar fuentes, importar paquetes, revisar licencias y conceder permisos queda pendiente. No hay un BardoSkillSmokeTestRunner de producción conectado, por lo que activar skills está bloqueado hasta implementar y probar ese verificador. Las skills ejecutables siguen bloqueadas.

El evaluador puede clasificar evidencias, pero aún falta garantizar que cada evidencia proviene de resultados verificables y enlazarlo al proceso de integración end-to-end. La autonomía de código no está aún probada como ciclo completo que identifica, desarrolla, ejecuta tests, corrige y vuelve a validar sin ayuda de una conversación externa.

main sigue sin protección configurada en GitHub. La protección de PR/checks debe activarse en el repositorio para que la regla exista también fuera del código de BARDO.
