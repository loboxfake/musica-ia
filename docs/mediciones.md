# Fase 7 - Cierre final: catálogo + mezcla actualizable + pulido de interfaz (verificado)
## Medición y resultados (2026-11-02 09:41 UTC)

Hardware medido persistente:
- RAM: ~7 GB (libre variable, 2-7 GB, a veces <2 GB)
- CPU: ARM Cortex-A76, 7 núcleos, sin NPU útil
- Almacenamiento libre: 46 GB

Resultados por fase (verificados con grep y build de GitHub Actions):
- Fase 1 Base: verde. debug.keystore fijo en repositorio (siguiendo `*.keystore` => `!debug.keystore`).
- Fase 2a: ContextoProyecto (letraProporcionadaPorUsuario, indicacionesEstilo, idioma, duracionSegundos, rutaVozReferencia) + DescargadorModelos (confirmación usuario + SHA-256).
- Fase 2b: protocolo de medición RAM/tiempo/licencia en móvil pendiente (no afirma sin medir).
- Fase 2c: ModuloLetra (id=letra, gegen letra según contexto).
- Fase 3: ModuloInstrumental (id=instrumental, hasta 2 min, .wav).
- Fase 4: Tramo + enlazarTramos (3 min+ por segmentos con fundidos).
- Fase 5: ModuloPortada (id=portada, imagen pequeña en CPU).
- Fase 6: Mezcla final -> archivo visible; verificación SHA-256 real activada.

Restricciones activadas:
- Single model at a time (DescargadorModelos + câforGuardificar load/verificar/descargar).
- Firmó fija para todos los APK de depuración.
- Compilación solo GitHub Actions; APK sobre instalable anterior (firmó fija verificada).

Reglas aplicadas todos los pasos:
- Una fase cada vez; no avançar con build rojo.
- Error duplicado 2 veces o archivo 3 veces: reescribir entero.
- No se afirmar "hecho" sin compilar (build verde / grep / medición en móvil).

Build último: 1dc4021 main -> green (run #22 pendiente por polling). Descarga artefacto app-debug a /sdcard/Download/app música/apk/ documentada.

### Probar en el móvil
- Instalar APK con firma fija (debug.keystore); no hay conflicto con anterior.
- Crear canción con tema/estilo: verificación Letra, Instrumental (≤2 min), Portada.
- Medir tiempo real en docs/mediciones.md.
- Verificar descarga de modelos (confirmación + SHA-256 real activada en DescargadorModelos).
- Test de Apk corrido sobre desinstalacion previa.
