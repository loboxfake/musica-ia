#!/bin/bash
set -euo pipefail

REPO="loboxfake/musica-ia"
TOKEN_FILE="$HOME/.git-credentials"
DEST_DIR="/sdcard/Download/app música/apk"

# 1) Leer token sin imprimirlo nunca
TOKEN=""
if [[ -f "$TOKEN_FILE" ]]; then
  while IFS= read -r line; do
    if [[ "$line" == *"https://github.com/"* ]]; then
      TOKEN="${line#*https://github.com/}"
      TOKEN="${TOKEN%@*}"
      break
    fi
  done < "$TOKEN_FILE"
fi

if [[ -z "$TOKEN" ]]; then
  echo "ERROR: no se encontró token en $TOKEN_FILE"
  exit 1
fi

# 2) SHA del último commit local
SHA=$(git rev-parse HEAD)
echo "Esperando build para commit: $SHA"

# 3) Consultar API cada 20s, hasta 10 min (30 consultas)
MAX_ATTEMPTS=30
ATTEMPT=1
RUN_ID=""

while [[ $ATTEMPT -le $MAX_ATTEMPTS ]]; do
  echo "[$ATTEMPT/$MAX_ATTEMPTS] Consultando GitHub Actions..."
  RESPONSE=$(curl -s -H "Authorization: token $TOKEN" \
    "https://api.github.com/repos/$REPO/actions/runs?head_sha=$SHA")
  
  # Buscar el primer run que coincida con el SHA
  RUN_ID=$(echo "$RESPONSE" | jq -r '.runs[0].id // empty')
  STATUS=$(echo "$RESPONSE" | jq -r '.runs[0].status // empty')
  CONCLUSION=$(echo "$RESPONSE" | jq -r '.runs[0].conclusion // empty')
  
  if [[ -z "$RUN_ID" ]]; then
    echo "Aún no hay run para este SHA. Reintentando en 20s..."
    sleep 20
    ATTEMPT=$((ATTEMPT + 1))
    continue
  fi

  echo "Run ID: $RUN_ID | Status: $STATUS | Conclusion: $CONCLUSION"

  case "$STATUS" in
    completed)
      if [[ "$CONCLUSION" == "success" ]]; then
        echo "BUILD EXITOSO. Descargando artefacto app-debug..."
        mkdir -p "$DEST_DIR"
        # Descargar el artefacto app-debug
        ARTIFACT_URL="https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts"
        ARTIFACT_ID=$(curl -s -H "Authorization: token $TOKEN" "$ARTIFACT_URL" \
          | jq -r '.artifacts[] | select(.name == "app-debug") | .id // empty')
        if [[ -z "$ARTIFACT_ID" ]]; then
          echo "ERROR: no se encontró el artefacto app-debug"
          exit 1
        fi
        curl -s -L -H "Authorization: token $TOKEN" \
          -H "Accept: application/vnd.github.v3+json" \
          "https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip" \
          -o /tmp/app-debug.zip
        unzip -o /tmp/app-debug.zip -d "$DEST_DIR"
        echo "APK descargada en: $DEST_DIR"
        exit 0
      else
        echo "BUILD FALLIDO. Descargando logs..."
        JOBS_URL="https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/jobs"
        JOB_ID=$(curl -s -H "Authorization: token $TOKEN" "$JOBS_URL" \
          | jq -r '.jobs[0].id // empty')
        if [[ -n "$JOB_ID" ]]; then
          LOGS_URL="https://api.github.com/repos/$REPO/actions/jobs/$JOB_ID/logs"
          curl -s -L -H "Authorization: token $TOKEN" "$LOGS_URL" -o /tmp/build.log
          echo "--- Líneas que empiezan por 'e: ' ---"
          grep '^e: ' /tmp/build.log || true
          echo "--- Bloque 'What went wrong' ---"
          awk '/What went wrong/{flag=1} flag{print} /^$/{if(flag) exit}' /tmp/build.log || true
        fi
        exit 1
      fi
      ;;
    queued|in_progress)
      sleep 20
      ATTEMPT=$((ATTEMPT + 1))
      ;;
    *)
      echo "Estado inesperado: $STATUS. Reintentando..."
      sleep 20
      ATTEMPT=$((ATTEMPT + 1))
      ;;
  esac
done

echo "TIMEOUT: se alcanzó el máximo de intentos sin que el build terminara."
exit 1