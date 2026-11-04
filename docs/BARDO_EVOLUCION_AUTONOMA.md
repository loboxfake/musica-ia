# BARDO — Arquitectura de evolución autónoma gobernada

Estado: especificación de diseño; no representa funciones ya implementadas.

## Objetivo

BARDO debe poder detectar problemas y oportunidades, formular propuestas de mejora, preparar cambios, ejecutar pruebas y presentar resultados. La iniciativa de analizar y proponer es autónoma; la autorización para aplicar cambios que alteren el comportamiento, los datos o la instalación corresponde siempre al usuario.

## Ciclo de evolución

1. **Observar:** recopilar diagnósticos locales explícitamente permitidos: errores, tiempos, uso de memoria, resultados de pruebas y comentarios del usuario. No recopilar contenido privado ajeno al ámbito autorizado.
2. **Diagnosticar:** separar hechos medidos, hipótesis y limitaciones. No presentar una hipótesis como un fallo confirmado.
3. **Proponer:** cada propuesta incluye identificador, objetivo, problema, beneficio esperado, impacto, riesgos, permisos solicitados, archivos/áreas afectadas, pruebas y plan de reversión.
4. **Esperar autorización:** estados válidos: borrador, pendiente, autorizada, rechazada, aplicada, verificada, revertida. El silencio nunca autoriza.
5. **Preparar:** crear un cambio aislado y reproducible, sin tocar componentes protegidos ni datos del usuario.
6. **Validar:** ejecutar pruebas unitarias, análisis estático, compilación, auditoría de dependencias, integridad y pruebas de regresión.
7. **Presentar:** mostrar diff, resultados reales, limitaciones y artefactos. Una compilación verde no equivale a validación en dispositivo.
8. **Activar:** las actualizaciones de APK requieren firma compatible y consentimiento/acción de instalación del sistema Android.
9. **Revertir:** conservar versión anterior y datos; si falla una comprobación, no activar el cambio y ofrecer reversión.

## Separación de permisos

- **Autónomo sin aprobación:** inspección local autorizada, análisis, diagnóstico, ideas, borradores y propuestas.
- **Autorización expresa por cambio:** edición de código, incorporación o actualización de módulos, migraciones, cambios de configuración persistente y uso de permisos nuevos.
- **Confirmación reforzada:** borrar datos, sustituir modelos, cambiar identidad/firma, exportar datos privados, modificar protecciones o instalar una APK.
- La autorización para una propuesta no concede permisos distintos de los descritos en ella.

## Seguridad de módulos

- Manifiesto versionado con identificador, versión, API mínima/máxima, capacidades solicitadas, origen, licencia y SHA-256.
- Verificar integridad y firma antes de aceptar un paquete.
- Rechazar módulos incompatibles, no firmados o con permisos superiores a los autorizados.
- No ejecutar código descargado arbitrario dentro del proceso principal. Preferir APIs declarativas y módulos compilados/firmados.
- Mantener registro local append-only de propuestas, autorizaciones, pruebas, activaciones y reversiones.
- Mantener una copia recuperable de la última versión funcional y migraciones de datos reversibles.

## Límites técnicos y de plataforma

- Un proceso Android no puede sustituir silenciosamente la APK instalada ni eludir el mecanismo de firma/instalación del sistema.
- La generación de parches en el teléfono no implica que BARDO pueda compilar una APK: eso requiere una toolchain disponible y recursos suficientes. Si se usa un entorno externo de compilación, se declarará como dependencia y se verificará el artefacto.
- La autonomía no implica acceso irrestricto a archivos, red, micrófono, cuentas ni credenciales.
- BARDO debe funcionar en modo degradado y explicar qué capacidades no están disponibles si falta un modelo o una herramienta.

## Criterios de aceptación

- Ningún cambio se aplica sin autorización explícita asociada a un diff identificable.
- Ningún módulo se activa sin validación de manifiesto, integridad y permisos.
- Fallos de pruebas bloquean la activación.
- Cada cambio tiene evidencia reproducible y ruta de reversión.
- No se declara validado en Xiaomi 11T Pro hasta instalarlo y probarlo físicamente.
- Las funciones musicales, MP3, voz e IA se describen según pruebas reales, nunca por el mero hecho de existir código o un artefacto.
