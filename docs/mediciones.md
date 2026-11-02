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
