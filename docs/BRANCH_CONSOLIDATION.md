# BARDO — Registro de ramas y consolidación

Última revisión: 2026-11-13. Rama canónica: `main`.

## Regla de integración

- `main` es la única línea de producto que debe alimentar la APK unificada.
- Las ramas `bardo/*`, `feature/*` y `fix/*` son espacios de trabajo o históricos; no son APK independientes ni líneas de producto paralelas.
- Todo cambio útil debe volver a `main` mediante una PR revisada y validada, o mediante el flujo de consolidación/autorización ya definido en la arquitectura.
- Nunca se debe fusionar una rama antigua completa solo porque exista: primero comparar su diff con `main`, comprobar si ya está integrado, revisar conflictos y rutas protegidas, y validar los cambios exclusivos.
- No borrar ramas históricas ni reescribirlas automáticamente. Mantenerlas como evidencia/rollback hasta que el propietario autorice una limpieza.
- Mantener una sola tanda activa de integración por área para evitar ramas que se pisan. Se puede desarrollar otra área en paralelo si parte del SHA actualizado de `main` y tiene límites de archivos claros.
- Antes de abrir una PR, comprobar `base...head`, el SHA base, la lista de archivos y el hash de `Modo3MezclaVozUsuario.kt`.

## Foto del repositorio en esta revisión

- `main` antes de la mejora de remuestreo: `12d9751a04711d4e4c42565d126f6e428c3f369a`.
- PR #24, análisis musical offline: integrada en `main` el 2026-11-13.
- PR #25, remuestreo FIR anti-alias y correcciones de análisis dinámico: integrada mediante squash en `6e9054e0b089252f3e236e412b4cd71403074fb5`.
- Validación PR #25 en HEAD `e301dc4a245770e39e2dd1b63f1cefba896e3168`: dos ejecuciones del workflow completadas con `success`.
- SHA/blob protegido de `Modo3MezclaVozUsuario.kt` comprobado antes de la integración: `8f5a34817891e1ec3b83f414acc9497c124b33e2`.

## Ramas históricas

Las ramas de PR #1–#24 que constan como fusionadas son antecedentes; sus cambios integrados ya forman parte de `main`. Las ramas que no aparecen como head de una PR fusionada se consideran **pendientes de auditoría**, no trabajo automáticamente perdido ni trabajo automáticamente integrado. En particular, ramas antiguas de DiffSinger, autonomía y validación de APK deben compararse con `main` antes de reutilizar cualquier commit.

## Siguiente paso de consolidación

La siguiente tanda de endurecimiento de importación de audio parte de `main` en `6e9054e`. Su alcance es el conversor PCM compartido por el decodificador Android, con tests JVM de PCM 8/16/24/32-bit y float. No toca la UI, generación, voz ni Modo 3. Después de validar, integrar y volver a comprobar el hash protegido, la rama se cierra como trabajo integrado y la siguiente tanda debe partir del nuevo SHA de `main`.

## Estados que no deben confundirse

1. Código escrito en una rama.
2. PR abierta y revisada.
3. Tests y compilación completados en el SHA exacto.
4. Cambio integrado en `main`.
5. APK unificada generada desde ese SHA.
6. APK instalada y ejecutada en el Xiaomi 11T Pro.
7. MP3 reales, generación, mezcla y reproducción comprobados en el dispositivo.

Solo los estados demostrados se pueden anunciar como completados.
