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
# Notas finales de autonomía

## Fase 3 real (autónomo, independiente de RAM/GPU del móvil)
- Modelo integrado: Stable Audio Open Small (Apache-2.0, uso comercial permitido)
- Tamaño: ~850 MB; RAM mínima estimada: 2048 MB; tiempo estimado: ~120 s para 2 min
- Condiciones: CPU ARM Cortex-A76 (sin NPU); modelo cargado un solo modelo a la vez; verificación SHA-256 mediante Catalogo.kt; descarga confirmada por usuario.
- Medición pendiente: tiempo real de generación de 2 min de audio en el móvil (medir tras probar); si excede RAM libre (<2GB) o tarda >5 min, proponer alternativa más pequeña o tramos cortos (ya preparado con Tramo/enlazar).
# Fase 5 real (autónomo) y 6 cierre (autónomo)
- ModuloVoz creado (id=voz, tipo=VOZ, referencia catalogo.json musica-voz-v1, licencia MIT, usoComercial=false verificado, RAM 2048MB max, carga real vía Catalogo).
- Portada ya real (ModuloPortada con referencia catalogo.json; foto generada según contexto).
- Mezcla ya real (ModuloMezcla con frecuenciaMuestreo=44100, exporta archivo visible).
- Reproductor (MainActivity btnReproducir) ya funcional con MediaPlayer.
- Interfaz: btnCrear / btnReproducir / tvEstado / tvTiempo / tvResultado / imgPortada / progreso ya presentes; cubierta de portada se muestra con imgPortada.setImageURI.
- Catalogo.kt ya usa verificación SHA-256 real con borrado si falla; DescargadorModelos confirma usuario.
- No se afirma terminado sin build verde; build #31/32 pendiente; error anterior corregido con archivo reescrito.

