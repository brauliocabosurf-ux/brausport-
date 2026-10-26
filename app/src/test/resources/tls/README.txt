test-server.p12 se genera localmente con: bash scripts/generate-test-tls.sh
Es un certificado AUTOFIRMADO SOLO PARA PRUEBAS (127.0.0.1 y localhost).
Contraseña de laboratorio: test-only. Está ignorado por Git y CI lo genera antes de probar.
No se empaqueta en el APK (src/test) y no tiene relación con la llave de firma.
