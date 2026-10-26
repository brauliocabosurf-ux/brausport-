# BRAU SPORTS+

Reproductor IPTV personal para Google TV. Carga una lista M3U (o XSPF) y la muestra como un servicio de tele:
categorías, eventos en vivo, favoritos, búsqueda y cambio de canal con el control.

> Uso personal. El repositorio no incluye ninguna lista ni canal; la URL de la lista solo se guarda en la tele.

## Funciones

- **🔴 En vivo hoy:** partidos y eventos agrupados por deporte (Liga MX, Europa, Sudamérica, NFL, NBA, F1, Más fútbol).
- **Canales fijos:** Deportes MX, Deportes Latam, USA en español, Brasil, TV abierta, Cine y series, con logos locales.
- **Duplicados juntos:** el mismo canal o partido de varias fuentes aparece en una sola tarjeta.
- **Cambio automático de fuente:** si una fuente falla o se congela 20 s, pasa a la siguiente. Después de 120 s sin
  poder reproducir, se detiene y muestra **«Reintentar»**.
- **Vista dividida:** video (~70 %) con la lista de canales a la derecha, o pantalla completa.
- **Favoritos y búsqueda.** Al abrir, arranca en el último canal que viste.

## Controles

| En la pantalla de canales | |
|---|---|
| Flechas | Moverte entre categorías y canales |
| OK | Ver el canal |
| Mantener OK | Agregar o quitar de favoritos ★ |

| Viendo un canal | |
|---|---|
| ▲ ▼ (vista dividida) | Moverte por la lista de la derecha |
| OK (vista dividida) | Cambiar a ese canal; OK sobre el que suena = pantalla completa |
| ▲ / CH+ (pantalla completa) | Canal siguiente |
| ▼ / CH- (pantalla completa) | Canal anterior |
| ◀ / ▶ | Cambiar de fuente (cuando el canal tiene varias) |
| OK con «Reintentar» visible | Reintentar el canal |
| Atrás | De pantalla completa a vista dividida; de ahí, a la lista |

## Estructura

```
app/src/main/java/com/brau/sportsplus/
├── MainActivity.kt, PlayerActivity.kt, Adapters.kt   Pantallas (Views/XML + RecyclerView)
├── AppState.kt, Store.kt, LogoResources.kt           Estado, preferencias y logos locales
├── net/        SafeHttp (único cliente OkHttp), descarga de la lista
├── player/     SafeMediaSources (Media3 con la política de red)
└── core/       Lógica pura, sin Android (probada en JVM)
    ├── net/        Política de URLs y direcciones, DNS y sockets protegidos
    ├── playlist/   Parsers M3U/XSPF, clasificación (Catalog), modelos
    ├── storage/    Lista guardada con reemplazo atómico
    ├── load/       Repositorio: carga, recarga y publicación de la lista
    └── player/     Presupuesto de reintentos y temporizadores de recuperación
```

Para ajustar la clasificación, solo se editan las listas de palabras en `core/playlist/Catalog.kt`.

## Seguridad

Detalle completo en **[SECURITY.md](SECURITY.md)**. En resumen:

- Solo HTTPS, con una única excepción HTTP para el servidor de la lista (`108.175.13.7`, en `gradle.properties`).
- Bloquea redes locales y privadas, también en redirecciones, DNS y dentro de los HLS (manifiestos, segmentos y claves).
- No descarga logos de internet; las listas tienen límites de tamaño y de formato.
- El Release solo se firma con la llave correcta; si la huella no coincide, el build falla.

## Subir a GitHub

**Nunca subas** `SECRETOS-NO-SUBIR.txt` ni el keystore (`*.keystore`, `*.jks`). Ya están en `.gitignore`.

```
git init
git add .
git update-index --chmod=+x gradlew      # importante si subes desde Windows
git commit -m "BRAU SPORTS+"
git branch -M main
git remote add origin https://github.com/<usuario>/<repo>.git
git push -u origin main
```

Antes del primer push, revisa con `git status` que no aparezcan los secretos.

## Compilar y publicar

**Configuración (una sola vez):**

1. En GitHub → Settings → Secrets and variables → Actions, crea los secrets `KEYSTORE_PASSWORD` y
   `KEYSTORE_BASE64` con los valores de `SECRETOS-NO-SUBIR.txt`.
2. En Actions, ejecuta a mano **«Generar verificación de dependencias»**. Descarga el
   `verification-metadata.xml` que produce, revísalo y súbelo a `gradle/`.

**En cada cambio a `main`**, el workflow corre las pruebas, lint, la compilación Release y la verificación de firma,
del Network Security Config y del SHA-256.

**Para publicar:** en Actions, ejecuta **«Compilar y publicar APK»** con `publicar = true`. Solo se publica si pasaron
todas las comprobaciones y existe `gradle/verification-metadata.xml`. El enlace siempre es el mismo:

```
https://github.com/<usuario>/<repo>/releases/latest/download/BrauSportsPlus.apk
```

**En tu compu** (Android Studio o terminal, JDK 17):

```
bash scripts/generate-test-tls.sh                           # certificado local de laboratorio
./gradlew testDebugUnitTest lintDebug assembleDebug           # versión de prueba, sin llave
BRAU_KEYSTORE_FILE=ruta/brau.keystore BRAU_KEYSTORE_PASSWORD=... \
  ./gradlew assembleRelease                                     # Release firmado
```

**Verificar un APK descargado:**

```
sha256sum -c BrauSportsPlus.apk.sha256
apksigner verify --print-certs BrauSportsPlus.apk   # la huella SHA-256 debe ser la de gradle.properties
```

## Instalar en la Google TV

1. En la tele, instala **Downloader** (de AFTVnews) desde la Play Store.
2. Ajustes → Sistema → Acerca de → presiona 7 veces «Compilación del SO» para activar las opciones de desarrollador.
3. Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas → activa **Downloader**.
4. En Downloader escribe el enlace del APK, descarga e instala.

Para actualizar, repite el paso 4; la app conserva tu lista y tus favoritos. Después de instalar, desactiva
«Fuentes desconocidas» para Downloader.

## Notas

- Si una lista nueva falla, la app conserva la anterior.
- El servidor de la lista ve tu IP. Si te importa, usa una VPN en la tele.
- Historial de cambios en [CAMBIOS.md](CAMBIOS.md); licencias de terceros en [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
