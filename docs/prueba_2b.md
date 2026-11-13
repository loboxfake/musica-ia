# Prueba Fase 2b: modelo de lenguaje en CPU (móvil)

Objetivo: medir RAM libre, tiempo y resultado al cargar un modelo de lenguaje pequeño (1-3B cuantizado) sin NPU.

## Hardware medido (autónomo, 2026-11-02 09:13 UTC)
- RAM total: ~7 GB
- RAM libre aproximada (variable): 2-7 GB; a veces <2 GB
- CPU: ARM Cortex-A76, 7 núcleos
- Almacenamiento libre: 46 GB

## Modelo candidato (a confirmar licencia y tamaño)
- Tipo: lenguaje pequeño cuantizado (1-3B parámetros)
- Uso: letras según tema y estilo
- Restricción: un solo modelo cargado a la vez

## Protocolo de prueba (a ejecutar en móvil)
1. Verificar RAM libre antes de cargar (`Runtime.getRuntime().freeMemory()` aproximado o lectura del sistema).
2. Intentar cargar modelo de 1B (más pequeño). Si falla por memoria, bajar a <1B o usar cuantización más agresiva.
3. Medir tiempo de carga.
4. Generar letra corta (tema y estilo de ejemplo). Medir tiempo.
5. Anotar en docs/mediciones.md RAM consumida, tiempo, calidad de letra, licencia del modelo.

## Estado
- Pendiente ejecución en móvil.
- No se afirma sin medir.
