# Medidas de hardware (móvil)
- RAM total: ~7 GB (libre variable, a veces <2 GB)
- CPU: ARM Cortex-A76, 7 núcleos, sin NPU útil
- Almacenamiento: 46 GB libre
- Modelo cargado a la vez
- Compilación solo en GitHub Actions; APK debe instalarse encima del anterior (firma fija verificada)

## Fase 1 (Base) - verificado
- APK instalable con debug.keystore fijo (mismo hash de firma)
- Interfaz: botón Crear/Reproducir + reloj tiempo real
- Orquestador: ejecutarModulos con listener nullable (?.)
- Build: verde (run #20, f68ea19... success)

## Fase 2a - inicio (contexto compartido + descargador)
- Pendiente: medir qué sistema de lenguaje cabe en RAM libre antes de elegir modelo.

## Fase 2a medida (autónoma, 2026-11-02 09:05 UTC)
- ContextoProyecto creado (letraProporcionadaPorUsuario, indicacionesEstilo, idioma, duracionSegundos, rutaVozReferencia)
- DescargadorModelos creado (confirma usuario + verificación SHA-256 + descarga)
## Fase 2b - medición pendiente en móvil
- Modelo candidato: lenguaje pequeño cuantizado 1-3B
- RAM libre variable (2-7 GB, a veces <2 GB)
- Protocolo: cargar modelo pequeño, medir RAM, tiempo de carga, generar letra corta; anotar resultado exacto en docs/mediciones.md antes de pasar a 2c.
- No se elige modelo sin comprobar licencia, tamaño y resultado en CPU del móvil.
- Commit dcb9373; push verde; esperar_build.sh iniciado (timeout 20s, bucle de polling activo contra run #21)
- No se afirma "hecho" sin compilar: verificado grep + git push; build de GitHub Actions pendiente de completar.
# Fase 2c (autónoma, sin depender de RAM/GPU del móvil)
- Modelo de lenguaje no cargado aún (Phase 2b pendiente en dispositivo); ModuloLetra implementado como estructura que recibe contexto y genera letra estructurada.
- No se afirma resultado final sin ejecutar en móvil con modelo real.

## Fase 3 - Instrumental (hasta 2 min, autónomo)
- Modelo candidato: Stable Audio 3 Small (o equivalente pequeño en CPU)
- Restricción: un modelo cargado a la vez; hasta ~2 min por generación
- Medida pendiente: tiempo de generación de 2 min de música en CPU ARM Cortex-A76
- Si tarda más de lo aceptable, proponer alternativa más pequeña o tramos más cortos
- Licencia y soporte Android sin NPU: comprobar antes de cargar
## Fase 4 - Canciones de 3 min o más (autónomo, independiente de RAM/GPU)
- Método: generar por tramos (ej. 60-90 s) y enlazar con fundidos (crossfade)
- Restricción: un solo modelo cargado; tramos se procesan secuencialmente para no exceder RAM
- Tiempo estimado: variable según CPU; medir en móvil cuando sea posible
- No se afirma resultado sin medir tiempo real de generación y fundido


## Fase 5 - Portada real (autónomo)
- Modelo candidato: modelo de imagen pequeño en móvil (CPU)
- Tiempo estimado: minutos por portada (CPU sin NPU)
- Medida pendiente: tiempo real de generación + tamaño de imagen de salida
- No se afirma sin medir en móvil
