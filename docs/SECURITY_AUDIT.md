# Auditoría de seguridad de BARDO

Estado: revisión parcial; no declarar el repositorio limpio todavía.

## Hallazgo confirmado

- El repositorio contiene un archivo raíz llamado debug.keystore.
- El historial incluye el commit 8dd7f885495e25d0dc264a0335a297c7105d6a10, que añadió ese archivo al control de versiones y configuró Gradle para firmar con él.
- app/build.gradle.kts contiene credenciales fijas de firma de depuración.
- Como el repositorio es público, esa clave debe considerarse comprometida aunque sea una clave de depuración. No es una credencial de Developer Core, pero sí material de firma.
- El archivo de clave privada no forma parte de los assets declarados de la APK. La firma de Android incluye información pública del certificado, no la clave privada.
- Rotar la clave impedirá actualizar encima la aplicación firmada con la clave anterior. Antes de rotarla hay que preparar y probar una copia de seguridad/restauración de datos del usuario. No se debe borrar la app instalada ni perder sus datos sin autorización.

## Búsquedas realizadas

Se buscaron en los mensajes/commits accesibles del repositorio prefijos habituales de tokens (GitHub, OpenAI, Google y Hugging Face), además de referencias a credenciales de API. Esas búsquedas no devolvieron coincidencias. Esta consulta no sustituye un escaneo exhaustivo de cada blob de todo el historial.

## Controles añadidos

- tools/audit_apk_secrets.py examina entradas de la APK y el snapshot de código empaquetado buscando patrones habituales de tokens y archivos de firma.
- El workflow integral ejecuta ese control antes de publicar la Release.
- El control falla si encuentra material de firma empaquetado o tokens que coincidan con los patrones definidos.

## Pendiente obligatorio

1. Preparar exportación/restauración completa de datos locales.
2. Acordar la migración desde la firma de depuración comprometida a una clave nueva custodiada fuera del repositorio (por ejemplo, un secreto de GitHub Actions).
3. Retirar la clave del árbol actual y planificar la limpieza segura del historial únicamente con una estrategia aprobada que no borre trabajo remoto ni deje al usuario sin datos.
4. Ejecutar un escaneo histórico completo y revisar manualmente cualquier hallazgo.

Hasta completar esos puntos, BARDO no debe describirse como si tuviera una firma de distribución segura. No se debe publicar una APK como versión de producción con la clave comprometida.
