#!/usr/bin/env bash
# Build BARDO locally without GitHub Actions. Run from any directory.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
log() { printf '\n==> %s\n' "$*"; }

[[ -f gradlew && -f settings.gradle.kts && -d app/src/main ]] || fail "No parece la raíz del proyecto BARDO."
command -v java >/dev/null 2>&1 || fail "Instala JDK 17 y configura JAVA_HOME/PATH."
JAVA_VERSION="$(java -version 2>&1 | awk -F '[".]' '/version/ {print $2; exit}')"
[[ "$JAVA_VERSION" == "17" ]] || fail "Se requiere JDK 17; detectado: ${JAVA_VERSION:-desconocido}."
[[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]] || fail "Configura ANDROID_HOME o ANDROID_SDK_ROOT con Android SDK instalado."

PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1; then PYTHON="$candidate"; break; fi
done
[[ -n "$PYTHON" ]] || fail "Instala Python 3 para generar y auditar los recursos del icono."

log "Comprobando Pillow y generando Digital Bardic Quill (5 densidades)"
if ! "$PYTHON" -c 'from PIL import Image' >/dev/null 2>&1; then
  "$PYTHON" -m pip install --user Pillow || fail "No se pudo instalar Pillow. Instálalo manualmente y repite."
fi
"$PYTHON" tools/generate_launcher_mipmaps.py

chmod +x ./gradlew
MODE="${1:-standard}"
VERSION_CODE="${BARDO_VERSION_CODE:-1}"
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || fail "BARDO_VERSION_CODE debe ser numérico."

case "$MODE" in
  standard)
    log "Ejecutando pruebas unitarias y compilación Android debug"
    ./gradlew --no-daemon --stacktrace testDebugUnitTest assembleDebug "-PbardoVersionCode=$VERSION_CODE"
    ;;
  full)
    EXECUTORCH_SRC="${EXECUTORCH_SRC:-}"
    [[ -n "$EXECUTORCH_SRC" && -d "$EXECUTORCH_SRC" ]] || fail "Para full define EXECUTORCH_SRC apuntando al checkout de ExecuTorch fijado por el proyecto."
    MODEL_DIR="app/src/main/assets/audio_model"
    for file in conditioners_model.pte dit_model.pte autoencoder_model.pte spiece.model LICENSE; do
      [[ -s "$MODEL_DIR/$file" ]] || fail "Falta el modelo local: $MODEL_DIR/$file"
    done
    log "Ejecutando pruebas y compilación completa con motor de audio nativo offline"
    ./gradlew --no-daemon --stacktrace testDebugUnitTest assembleDebug \
      -PwithAudioNative=true "-PbardoVersionCode=$VERSION_CODE" "-PetSrcPath=$EXECUTORCH_SRC"
    ;;
  *)
    fail "Uso: ./build_apk.sh [standard|full]"
    ;;
esac

APK="$(find app/build/outputs/apk/debug -maxdepth 1 -type f -name '*.apk' -print -quit)"
[[ -n "$APK" && -s "$APK" ]] || fail "Gradle terminó sin producir una APK válida."

log "Auditando APK, recursos del icono y (si procede) motor offline"
"$PYTHON" - "$APK" "$MODE" <<'PY'
import hashlib, pathlib, sys, zipfile
apk = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
required = [
    "res/mipmap-mdpi-v4/ic_launcher.webp",
    "res/mipmap-hdpi-v4/ic_launcher.webp",
    "res/mipmap-xhdpi-v4/ic_launcher.webp",
    "res/mipmap-xxhdpi-v4/ic_launcher.webp",
    "res/mipmap-xxxhdpi-v4/ic_launcher.webp",
    "res/mipmap-mdpi-v4/ic_launcher_foreground.webp",
    "res/mipmap-hdpi-v4/ic_launcher_foreground.webp",
    "res/mipmap-xhdpi-v4/ic_launcher_foreground.webp",
    "res/mipmap-xxhdpi-v4/ic_launcher_foreground.webp",
    "res/mipmap-xxxhdpi-v4/ic_launcher_foreground.webp",
    "res/mipmap-anydpi-v26/ic_launcher.xml",
    "res/mipmap-anydpi-v26/ic_launcher_round.xml",
]
if mode == "full":
    required += [
        "assets/audio_model/conditioners_model.pte",
        "assets/audio_model/dit_model.pte",
        "assets/audio_model/autoencoder_model.pte",
        "assets/audio_model/spiece.model",
        "assets/audio_model/LICENSE",
        "lib/arm64-v8a/libaudiogen.so",
        "lib/arm64-v8a/libmp3encoder.so",
        "lib/arm64-v8a/libmp3lame.so",
    ]
with zipfile.ZipFile(apk) as z:
    corrupt = z.testzip()
    if corrupt:
        raise SystemExit(f"APK dañada en entrada: {corrupt}")
    names = set(z.namelist())
    missing = [name for name in required if name not in names]
    if missing:
        raise SystemExit("Faltan recursos requeridos: " + ", ".join(missing))
    empty = [name for name in required if z.getinfo(name).file_size <= 0]
    if empty:
        raise SystemExit("Entradas vacías: " + ", ".join(empty))
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
report = [
    f"APK={apk.resolve()}",
    f"BYTES={apk.stat().st_size}",
    f"SHA256={digest}",
    f"MODE={mode}",
    f"REQUIRED_ENTRIES_OK={len(required)}",
]
pathlib.Path(str(apk) + ".audit.txt").write_text("\n".join(report) + "\n")
print("\n".join(report))
PY

log "Compilación y auditoría local terminadas"
printf 'APK: %s\n' "$APK"
printf 'Auditoría: %s.audit.txt\n' "$APK"
