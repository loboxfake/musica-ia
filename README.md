# BARDO

**Powered by Stability AI**

BARDO is a local-first Android personal assistant and creative workspace. Its on-device instrumental generation currently uses Stable Audio Open Small exported for ExecuTorch. The model files and their accompanying license are packaged as application assets; see `app/src/main/assets/NOTICE.txt` and `assets/audio_model/LICENSE` inside the APK.

BARDO does not claim to generate sung vocals. MiVoz remains a future capability.

Build and device validation are separate: a successful GitHub Actions build does not certify behavior on a physical Xiaomi 11T Pro.


## Integración y desarrollo

- Requisitos de autoconocimiento operativo y desarrollo autónomo en segundo plano: `docs/BARDO_AUTOCONOCIMIENTO_Y_DESARROLLO_CONTINUO.md`.

- Rama canónica: `main`.
- La compilación instalable y auditada se realiza con `.github/workflows/full-build.yml`.
- La validación de código, pruebas unitarias y escaneo de secretos se ejecuta con `.github/workflows/bardo-validation.yml`.
- El modelo Stable Audio y sus assets se preparan mediante `.github/workflows/prepare-audio-model.yml`.
- Gemma local ya descargado por el usuario debe reutilizarse desde `Descargas/BARDO/Modelos`; no se descarga de nuevo si el archivo verificado está disponible.
