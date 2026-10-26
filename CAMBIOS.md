# Corrección de compilación Android

- `compileSdk` 37, AGP 9.1.1 y Gradle 9.3.1 con suma oficial: OkHttp Android 5.5.1 exige API 37 al compilar.
- Se mantienen `minSdk` 24, `targetSdk` 36 y Kotlin 2.3.21 externo con las opciones de compatibilidad de AGP.
- La validación completa se ejecuta en GitHub Actions; no se desactivan pruebas, lint ni controles de firma.

# Preparación para GitHub Actions

- El certificado TLS de laboratorio se genera en cada runner con `scripts/generate-test-tls.sh`.
- No se publica `src/test/resources/tls/test-server.p12`; queda ignorado por Git.
- Ambos workflows generan el recurso antes de las pruebas. La firma de Release conserva los Secrets y la huella esperada.

# Limpieza antes de subir a GitHub

| Archivo | Cambio |
|---|---|
| `PlayerActivity.kt` | Constantes con nombre (`INFO_HIDE_MS`, `FOCUS_OFFSET_PX`), helper `looksLikeHls()` en lugar de la comprobación duplicada, campos de vista agrupados y documentación actualizada. Sin cambios de comportamiento. |
| `core/storage/PlaylistStore.kt` | Se quitó `clear()` (borrado incondicional sin uso; solo queda `clearIfVersion()`). |
| `core/player/RecoveryTimers.kt` | Se quitó `currentEpoch` (sin uso). |
| `.github/workflows/*.yml` | `bash ./gradlew` para no depender del permiso de ejecución (subidas desde Windows). |
| `.gitignore`, `.gitattributes` (nuevo) | Ignora APK, AAB y temporales; finales de línea estables. |
| `README.md` | Reescrito: funciones, estructura, cómo subir a GitHub, compilar, publicar e instalar. |

---

# Revisión 4 (plazo tras reproducción estable)

| Archivo | Cambio |
|---|---|
| `core/player/FailoverController.kt` | `beginRecoveryIfNeeded()`: idempotente; inicia un episodio solo si no hay uno activo y no se abandonó. |
| `PlayerActivity.kt` | Al entrar en `STATE_BUFFERING` llama a `beginRecoveryIfNeeded()` y programa de inmediato el plazo absoluto. |
| `RecoveryTimersTest.kt` | 4 pruebas nuevas: estable → buffering → 8 fuentes → abandono a los 120 s; buffering repetido; recuperación estable que cancela el vencimiento; idempotencia y abandono. |
| `SECURITY.md` | Comportamiento y resultados actualizados. |

---

# Revisión 3 (pendientes)

| Archivo | Cambio |
|---|---|
| `core/storage/PlaylistStore.kt` | `StoredPlaylist.version` (SHA-256 del archivo) y `clearIfVersion()`: borra solo la versión leída, bajo el mismo monitor que las escrituras. |
| `core/load/PlaylistRepository.kt` | La limpieza tras un parseo fallido es condicional y se hace bajo el mutex de las escrituras. |
| `core/player/FailoverController.kt` | El episodio empieza al iniciar la carga; plazo absoluto (`deadline`, `onDeadline()`) que no reinician los cambios de fuente. |
| `core/player/RecoveryTimers.kt` (nuevo) | Watchdog y plazo programados con épocas: se cancelan al salir o cambiar de canal y descartan callbacks viejos. |
| `PlayerActivity.kt` | Usa `RecoveryTimers` con reloj `uptimeMillis` (el de `Handler`); rearma el plazo tras cada acción y comprueba que el controlador sea el vigente. |
| `HlsPolicyIntegrationTest.kt` | La prueba de la clave carga un manifiesto permitido y bloquea la clave en un destino prohibido (servidor en 127.0.0.2 que registra si alguien llega). Política de laboratorio solo en la prueba. |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionSha256Sum` oficial. |
| Pruebas | `LoadingTest` (+2), `RecoveryTimersTest` (5 nuevas). |
| `SECURITY.md`, `README.md` | Comportamiento y pendientes actualizados. |

---

# Revisión 2 de la versión 1.1 (corrección de pendientes)

| Archivo | Cambio |
|---|---|
| `core/storage/PlaylistStore.kt` (nuevo; reemplaza `PlaylistCache.kt`) | URL y contenido en **un solo archivo** con reemplazo atómico (temporal + fsync + rename) y suma SHA-256. Gancho de prueba para simular un corte antes del rename. |
| `core/load/PlaylistRepository.kt` (nuevo; reemplaza `PlaylistRefresher.kt`) | `committed` (StateFlow) = lo que hay en disco. Turnos para arranque, cambio y recarga. Confirmación no cancelable bajo mutex. `LoadError.STORAGE` si falla la escritura. |
| `core/player/FailoverController.kt` (nuevo) | Presupuesto de reintentos por episodio (12 intentos / 120 s). Solo se restablece tras 30 s de reproducción con avance real. |
| `core/Limits.kt` | Límites del episodio de fallo. |
| `core/playlist/Models.kt`, `M3uParser.kt`, `XspfParser.kt`, `Catalog.kt` | Nombres neutrales «Canal N» (nunca la URL); los generados no se fusionan. |
| `core/net/NetGuards.kt` | `HttpIdentity.USER_AGENT` (constante única). |
| `net/SafeHttp.kt` | `UserAgentInterceptor`: mismo User-Agent en listas, video y redirecciones. |
| `player/SafeMediaSources.kt` | Usa `HttpIdentity`. Observador opcional de bloqueos (diagnóstico y pruebas HLS). |
| `MainActivity.kt` | Una sola puerta (`startOperation`). La pantalla observa `committed`. El arranque usa el mismo control de turnos. |
| `PlayerActivity.kt` | Usa `FailoverController`. Al agotarse, detiene la carga y muestra «Reintentar» (enfocable; OK en pantalla completa). Recupera la lista con `loadStored()`. |
| `Store.kt` | Sin `commitPlaylistUrl`; solo lectura de la URL de la 1.0 para migrar. |
| `AppState.kt`, `res/values/strings.xml`, `res/layout/activity_player.xml` | Repositorio, error de almacenamiento, botón «Reintentar». |
| `gradle/network-security.gradle.kts` (nuevo) | Generación del NSC separada y verificable sola. |
| `app/build.gradle.kts`, `gradle.properties` | `brau.cleartextHosts=108.175.13.7`. Pruebas con Robolectric 4.16 y Media3 test-utils; sin MockWebServer. |
| `.github/workflows/build.yml` | Verifica el NSC dentro del APK. Publicación solo manual y tras el `gate` (wrapper con suma y verificación de dependencias). |
| `app/src/test/**` | Pruebas nuevas: persistencia, arranque, reintentos, nombres sin tokens, User-Agent, TLS real, HTTPS→HTTP e integración HLS. Certificado TLS solo de prueba en `src/test/resources/tls/`. |
| `SECURITY.md`, `README.md` | Garantías reales, pruebas y pendientes. |

---

# Cambios de la versión endurecida 1.1 (por archivo)

## Nuevos — núcleo puro (sin Android, probado localmente)
| Archivo | Qué hace |
|---|---|
| `core/Limits.kt` | Todos los límites centralizados (listas, XML, red y reproducción por separado). |
| `core/net/AddressPolicy.kt` | Qué IPs son públicas (IPv4/IPv6, incluidas las mapeadas). |
| `core/net/UrlPolicy.kt` | Política única de URLs: esquemas, credenciales, hosts, IP literal, HTTP permitido y `redact()`. |
| `core/net/NetGuards.kt` | Redirecciones validadas, DNS validado y socket que verifica la IP real. Excepciones con mensaje fijo. |
| `core/io/LimitedRead.kt` | Lectura que cuenta bytes reales (corta bombas gzip) y UTF-8 tolerante. |
| `core/playlist/Models.kt` | Modelos, errores controlados (`LoadError`) y saneo de texto. |
| `core/playlist/M3uParser.kt` | M3U con límites; ignora directivas y cabeceras de la lista. |
| `core/playlist/XspfParser.kt` | XSPF con parser XML real; rechaza DTD/entidades; límites de profundidad, elementos y texto. |
| `core/playlist/Catalog.kt` | Clasificación (misma lógica) con expresiones precompiladas y política aplicada a cada fuente. |
| `core/playlist/LogoNames.kt` | Logos LOCALES por canal. |
| `core/playlist/PlaylistDecoder.kt` | Bytes → catálogo; filtros compartidos con el reproductor. |
| `core/storage/PlaylistCache.kt` | Caché atómica, con límite y ligada a la URL. |
| `core/load/PlaylistRefresher.kt` | Una sola actualización vigente, cancelación real, tiempo total, Default/IO y conserva la última lista válida. |

## Nuevos — Android
| Archivo | Qué hace |
|---|---|
| `net/SafeHttp.kt` | Único OkHttpClient: interceptores de política, redirecciones manuales, DNS y socket protegidos, sin proxy. |
| `net/PlaylistFetcher.kt` | Descarga cancelable, con límite de bytes y tiempo total; errores controlados. |
| `net/NetInfo.kt` | Política y cliente compartidos; hosts HTTP desde `BuildConfig`. |
| `player/SafeMediaSources.kt` | Media3 sin `DefaultDataSource`; fábrica HLS que valida manifiestos, segmentos y claves; límites de bytes. |
| `AppState.kt` | Estado compartido y textos fijos para cada error. |
| `LogoResources.kt` | Nombre → drawable local. |
| `res/drawable-nodpi/logo_*.png` | 35 logos locales (máx. 240×120). |

## Modificados
| Archivo | Cambio |
|---|---|
| `MainActivity.kt` | Caché y parseo fuera del hilo principal; una actualización activa; errores controlados; la URL nueva solo se guarda si la lista es válida; el diálogo ya no muestra la URL completa. |
| `PlayerActivity.kt` | Una sola instancia de ExoPlayer; liberación completa; reconstrucción tras muerte del proceso; LoadControl con límites; reintentos acotados. |
| `Adapters.kt` | Logos locales; se quitó Coil. |
| `Store.kt` | `commitPlaylistUrl()` síncrono; misma estructura de claves (no se pierden favoritos). |
| `AndroidManifest.xml` | Sin `usesCleartextTraffic="true"`; `networkSecurityConfig` generado. |
| `res/values/strings.xml` | Mensajes de error fijos y textos movidos desde el código. |
| `build.gradle.kts`, `app/build.gradle.kts` | AGP 8.13.2, Kotlin 2.3.21, Media3 1.11.1, OkHttp 5.5.1; NSC generado; firma estricta con verificación de huella; lint reactivado; pruebas. |
| `gradle.properties` | `brau.expectedCertSha256` y `brau.cleartextHosts`. |
| `.github/workflows/build.yml` | Jobs separados con permisos mínimos; acciones fijadas por SHA; verificación de firma y SHA-256 antes de publicar. |
| `README.md` | Compilación, verificación e instalación actualizadas. |

## Eliminados
`Playlist.kt` y `Logos.kt` (red sin control y descargas de logos), dependencia de Coil y `app/brau.keystore`.

## Agregados
Gradle Wrapper 8.14.3, `.github/workflows/dependency-verification.yml`, `SECURITY.md`,
`THIRD_PARTY_NOTICES.md` y las pruebas en `app/src/test/`.
