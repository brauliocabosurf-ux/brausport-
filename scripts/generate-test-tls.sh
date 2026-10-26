#!/usr/bin/env bash
set -euo pipefail

# Credencial efímera para los servidores TLS locales de las pruebas, nunca para firmar el APK.
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TLS_FILE="$PROJECT_DIR/app/src/test/resources/tls/test-server.p12"
mkdir -p "$(dirname -- "$TLS_FILE")"
if [ -e "$TLS_FILE" ]; then
  echo "Ya existe el certificado TLS de pruebas; se conserva."
  exit 0
fi
umask 077
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -sigalg SHA384withRSA \
  -dname "CN=127.0.0.1,O=SOLO PRUEBAS" -ext "SAN=IP:127.0.0.1,DNS:localhost" \
  -validity 3650 -storetype PKCS12 -keystore "$TLS_FILE" \
  -storepass test-only -keypass test-only -noprompt
