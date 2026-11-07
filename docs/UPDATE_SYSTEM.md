# BARDO update strategy

## Normal updates

The `Build APK` workflow is validation-only: it runs unit tests and a lightweight compile, but does not publish an installable release. Only `Build full offline APK` publishes the installable offline package. This avoids presenting a lightweight APK without the native audio runtime as the final app.

Install normal updates over the existing BARDO installation. Android package updates preserve the app's private data directory (`filesDir`), including the extracted `stable_audio_model` directory and downloaded language models. Do not uninstall BARDO or clear its storage when updating, or those files will be removed.

## Initial offline package

The `Build full offline APK` workflow can be started manually (`workflow_dispatch`) and runs on relevant pushes to the canonical `main` branch only. It packages the exact triggering `main` commit, retrieves the pinned Stable Audio model pack from a successful preparation run on `main`, compiles the native runtime, runs tests and audits the APK before publishing a prerelease asset. After publication, it verifies that `main` is the only active branch; this check is read-only and never deletes branch references.

The first generation attempt calls `ModuloInstrumental.cargar()`, which copies the model files from APK assets into `filesDir/stable_audio_model`. Once all model files have been copied successfully, later lightweight APK updates preserve them. If the initial copy never completed, a lightweight update cannot recreate missing bundled assets; the full offline package is then required.

## Important limits

- This is an incremental *application update* strategy: later APKs are small because the large model payload is not rebuilt into them.
- It is not yet a self-updating in-app installer. The user still downloads the APK from the named GitHub Actions run and approves Android's package installation.
- Native executable code changes must be delivered as a signed Android APK update. Model/data packages may be updated separately only after a trusted, signed manifest and stable hosting endpoint are configured.
- A successful CI build does not prove audio generation works on a physical Xiaomi device.

## CI publication contract

- `BARDO / tests + APK compilation` reports the lightweight unit-test and compile result; it does not publish an installable APK.
- `BARDO / full offline APK audit` reports the complete native/model build and APK-content audit.
- `BARDO / full offline APK release` reports whether the audited full APK was published to GitHub Releases.
- A release is installable only after the full-offline audit succeeds. A green lightweight compile is not a substitute.
- A successful release still requires a real playback check on the target phone before audio generation is considered verified.
