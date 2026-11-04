# BARDO update strategy

## Normal updates

The `Build APK` workflow creates the normal, lightweight application update. It does not download or package the Stable Audio model pack. Each APK receives a monotonically increasing Android `versionCode` based on the GitHub Actions run ID, so Android recognizes it as an update rather than a downgrade.

Install normal updates over the existing BARDO installation. Android package updates preserve the app's private data directory (`filesDir`), including the extracted `stable_audio_model` directory and downloaded language models. Do not uninstall BARDO or clear its storage when updating, or those files will be removed.

## Initial offline package

The `Build full offline APK` workflow is now manual-only (`workflow_dispatch`). It remains available for a clean installation that needs the bundled Stable Audio model pack and native audio libraries. It is deliberately not rebuilt on every source change.

The first generation attempt calls `ModuloInstrumental.cargar()`, which copies the model files from APK assets into `filesDir/stable_audio_model`. Once all model files have been copied successfully, later lightweight APK updates preserve them. If the initial copy never completed, a lightweight update cannot recreate missing bundled assets; the full offline package is then required.

## Important limits

- This is an incremental *application update* strategy: later APKs are small because the large model payload is not rebuilt into them.
- It is not yet a self-updating in-app installer. The user still downloads the APK from the named GitHub Actions run and approves Android's package installation.
- Native executable code changes must be delivered as a signed Android APK update. Model/data packages may be updated separately only after a trusted, signed manifest and stable hosting endpoint are configured.
- A successful CI build does not prove audio generation works on a physical Xiaomi device.
