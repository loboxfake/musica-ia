#!/bin/bash
# Copia proyecto a /sdcard/Download/app música, excluyendo .git, con subcarpeta apk
mkdir -p "/sdcard/Download/app música/apk"
cp -r /workspace/MusicaIA/* "/sdcard/Download/app música/" 2>/dev/null || true
# Excluir .git si se copió por error
rm -rf "/sdcard/Download/app música/.git"
echo "Sincronizado en /sdcard/Download/app música/ (sin .git) + subcarpeta apk/"
