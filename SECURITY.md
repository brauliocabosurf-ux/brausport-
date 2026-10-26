# BRAU SPORTS+ — Seguridad y estabilidad (versión endurecida 1.1, revisión 4)

Este documento describe qué protege la app, **dónde** se aplica cada control, qué se probó y qué
queda pendiente. No es una garantía: la app **no es "100 % segura"**.

---

## 1. Política única de red

Todas las peticiones (lista, canales, manifiestos HLS maestros y de medios, segmentos, claves AES,
subtítulos y cada redirección) pasan por **un solo cliente OkHttp** (`net/SafeHttp.kt`) y por la
**misma política** (`core/net/UrlPolicy.kt`).

### Dónde se valida cada petición efectiva

| # | Punto | Archivo | Qué valida |
|---|---|---|---|
| 1 | Al leer la lista | `core/playlist/Catalog.kt` | Cada fuente pasa por `UrlPolicy.check`. Las rechazadas se descartan y se cuentan. |
| 2 | Antes de reproducir | `player/SafeMediaSources.kt` → `create()` | URL del canal. |
| 3 | Cada apertura de Media3 | `PolicyDataSource.open()` | Toda URL que Media3 abre por su cuenta: manifiestos (`DATA_TYPE_MANIFEST`), segmentos y subtítulos (`MEDIA`) y claves (`DRM`). Así, una referencia interna del HLS a `file:`, `data:` o a una IP privada falla aunque el canal fuera válido. |
| 0 | Interceptor de aplicación | `UserAgentInterceptor` | Fija un único User-Agent (`HttpIdentity.USER_AGENT`) en toda petición: listas, manifiestos, segmentos, claves y redirecciones. Reemplaza cualquier valor previo. Las listas nunca aportan cabeceras. |
| 4 | Interceptor de aplicación | `PolicyInterceptor` | Petición inicial y **cada redirección, hecha a mano**: OkHttp tiene `followRedirects(false)` y `followSslRedirects(false)`. Máximo 5 saltos. Sin HTTPS → HTTP. |
| 5 | Interceptor de red | `NetworkRecheckInterceptor` | Repite la validación en la petición que realmente sale y comprueba la IP de la conexión usada (también si es reutilizada). |
| 6 | DNS | `GuardedDns` + `ResolvingGuard` | Cada respuesta DNS. Si **alguna** IP no es pública, se rechaza el nombre entero. |
| 7 | Socket | `GuardedSocketFactory` / `GuardedSocket.connect()` | La **IP real** de cada socket, justo antes de conectar. |

### Qué rechaza `UrlPolicy`
- URLs de más de 4,096 caracteres (se rechazan, nunca se truncan).
- Caracteres de control, espacios y cualquier carácter no ASCII. Los dominios internacionales deben venir en punycode.
- URLs mal formadas (las analiza `java.net.URI`) y URLs relativas.
- Esquemas distintos de `http` y `https`: `file`, `content`, `asset`, `data`, `rtmp`, `udp`, etc.
- `usuario:contraseña@host` y cualquier `@` en la autoridad.
- Puertos fuera de 1–65535.
- Hosts numéricos ambiguos (`2130706433`, `127.1`, `0x7f.0.0.1`, `0177.0.0.1`) y zonas IPv6 (`%eth0`).
- Nombres locales: sin punto, `localhost`, `.local`, `.lan`, `.internal`, `.home.arpa`, etc.
- IPs no públicas (`core/net/AddressPolicy.kt`):
  - **IPv4:** 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.31.196/24, 192.52.193/24, 192.88.99/24, 192.168/16, 192.175.48/24, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4 y 240/4.
  - **IPv6:** solo se permite 2000::/3, menos 2001::/23, 2001:db8::/32, 2002::/16 y 3fff::/20. Las IPv4 mapeadas (`::ffff:a.b.c.d`) se juzgan por la IPv4 que llevan dentro.
- `http://` hacia un host que no esté en la lista de excepciones (ver §2).

Los tokens en ruta o query **se conservan intactos** para reproducir.
`UrlPolicy.redact()` deja solo `esquema://host/…`. Los errores usan códigos fijos (`UrlRejection`, `LoadError`), sin URL ni `exception.message`. La app no escribe logs con URLs.

### Cómo se evita el DNS rebinding

1. **El DNS y la conexión usan la misma resolución.** OkHttp conecta exactamente a las IPs que devuelve `Dns.lookup()`, sin volver a resolver. Lo verifiqué en el código fuente de OkHttp 5.5.1 (`RouteSelector`).
2. **Las IPs literales también se cubren.** En ese caso OkHttp **no** llama a `Dns` (mismo archivo, línea 174), así que una protección solo de DNS dejaría un hueco. Por eso existe el guardia de socket.
3. **El socket comprueba la IP final, en cada conexión.** Toda conexión directa termina en `socket.connect(address, timeout)` (`Platform.connectSocket`), y ahí `GuardedSocket` revisa la IP. Si el DNS cambia entre la validación y la conexión, lo que se compara es la IP a la que de verdad se conecta. Esto cubre conexiones nuevas tras redirecciones, reintentos y Happy Eyeballs.
4. **Sin proxy** (`Proxy.NO_PROXY`): un proxy del sistema no puede saltarse estas barreras.
5. **Respuestas mixtas:** si el DNS devuelve una IP pública y una privada, se rechaza todo el nombre. Así una IP interna no puede quedar escondida como "segunda opción".

**Límites que conserva la solución:**
- **IPv6 global de tu propia red.** Una IP pública puede llevar a tu red interna, por ejemplo la IP pública de tu router con su panel de administración expuesto, o equipos de casa con IPv6 global (2000::/3). Por rango no se distinguen de Internet, y la política no puede saberlo.
- **El servidor puede reenviar.** Un servidor público puede hacer peticiones por su cuenta (proxy o SSRF en su lado). Eso queda fuera del control de la app.
- **El resolvedor de Android** (Private DNS, cachés) no se controla. Lo que se valida es su respuesta y la IP final.
- **TLS** confía en las autoridades de certificación del sistema Android de la tele.

TLS y hostname usan la verificación estándar de Android. No hay TrustManager ni HostnameVerifier propios, y los certificados instalados por el usuario no se aceptan (`base-config` usa solo `system`).

---

## 2. HTTP en claro: excepción limitada a 108.175.13.7

- `usesCleartextTraffic` ya no es `true`. El manifiesto apunta a `@xml/network_security_config`, que **se genera al compilar** desde `gradle/network-security.gradle.kts`. Por defecto todo es solo HTTPS y solo con certificados del sistema.
- **Excepción configurada** por decisión del dueño (25-oct-2026): `brau.cleartextHosts=108.175.13.7` en `gradle.properties`.
  - Es un host exacto, sin subdominios. La misma lista alimenta `BuildConfig.CLEARTEXT_HOSTS`, que usa `UrlPolicy`.
  - Es fija en cada APK y no se cambia desde la app.
- **La excepción NO autoriza:**
  - HTTP hacia cualquier otro host.
  - Degradaciones HTTPS → HTTP, ni siquiera hacia ese host.
  - Destinos privados.
  - Desactivar TLS en el resto.
- **Verificado aquí con Gradle 8.14.3 real:**
  - El XML generado tiene `base-config cleartextTrafficPermitted="false"` con anclas `system`, y un único `domain-config` con `<domain includeSubdomains="false">108.175.13.7</domain>`.
  - Un host inválido (`*.evil.com`) hace fallar el build.
- **En CI** se vuelve a comprobar el XML **dentro del APK firmado** (`aapt2 dump xmltree`). Si no coincide, no hay artefacto.
- **IPs literales en NSC:** en AOSP (`XmlConfigSource.parseDomain` y `ApplicationConfig.getConfigForHostname`) el texto de `<domain>` solo se pasa a minúsculas y se compara como cadena, y OkHttp consulta `NetworkSecurityPolicy.isCleartextTrafficPermitted(host)`. Que funcione en la Hisense **falta probarlo** (§6).

**Otros destinos HTTP necesarios:**
- Las 166 entradas de la lista apuntan a `http://108.175.13.7`. No hace falta ningún otro destino para descargar la lista ni para abrir los manifiestos.
- **Desconocido:** si los manifiestos HLS de ese servidor remiten segmentos o claves a **otro** host por HTTP. Esas peticiones se bloquearán (fallo controlado, se ofrece "Reintentar") y aparecerán como canal que no reproduce.
- Por diseño no se autoriza ningún otro host automáticamente. Si ocurre, se identifica en la prueba en la tele y se decide caso por caso.

**Riesgo aceptado:** el tráfico hacia `108.175.13.7` viaja sin cifrar y puede verse o modificarse en la red. La IP queda visible en el repositorio y en el APK.

---

## 3. Media3 restringido

- **Ya no se usa `DefaultDataSource`.** La única fuente es `OkHttpDataSource` sobre el cliente seguro, envuelta en `PolicyDataSource`. `DefaultDataSource` maneja `file:`, `content:`, `asset:`, `data:`, `rawresource:`, `udp:` y `rtmp:`.
- **HLS usa `SafeHlsDataSourceFactory`.** En el código de Media3 1.11.1 se confirmó que manifiestos, segmentos y claves se piden a esa fábrica (`DefaultHlsPlaylistTracker` → `DATA_TYPE_MANIFEST`; `HlsChunkSource` → `DATA_TYPE_MEDIA` y `DATA_TYPE_DRM`).
- **Límites de bytes:** manifiestos 4 MiB y claves 64 KiB. Los segmentos no tienen límite porque son video en vivo.
- **Límites de reproducción:**
  - Buffer de 15 a 50 s, con 48 MiB como objetivo.
  - 3 reintentos de Media3 por carga.
- **Presupuesto por episodio** (`core/player/FailoverController.kt` y `RecoveryTimers.kt`):
  - **Plazo absoluto de 120 s** con reloj monotónico (`SystemClock.uptimeMillis`, el mismo de `Handler`). Empieza al iniciar la carga del canal (o el reintento manual). Si ya hubo una reproducción estable y el episodio se cerró, el siguiente empieza **en cuanto el reproductor vuelve a cargar** (`STATE_BUFFERING` → `beginRecoveryIfNeeded()`, que se programa de inmediato) o en el primer fallo, lo que ocurra antes. Si ya hay un episodio activo, volver a cargar no amplía su fecha límite.
  - El vencimiento se **programa con un temporizador** y no depende de que llegue otro error. Los cambios automáticos de fuente **no reinician** el plazo. Prueba en tiempo virtual: con 8 fuentes en buffering continuo se abandona a los 120 s (más el retraso del temporizador), no a ~160 s.
  - Además hay un máximo de 12 intentos automáticos. Cada fuente se prueba una vez; HLS una vez por fuente; "detrás de la ventana en vivo" máximo 3 veces; watchdog de 20 s.
  - El presupuesto **solo se restablece tras 30 s de reproducción continua con avance real**. Eso termina el episodio y cancela su plazo.
  - Al agotarse: `player.stop()` (se detiene la carga), mensaje fijo y **«Reintentar»** enfocado. El reintento manual da plazo y presupuesto completos.
  - **Temporizadores:** se cancelan al salir, al cambiar de canal o fuente a mano y al reintentar. Cada uno de esos eventos abre una "época" nueva: un callback viejo que ya estuviera en cola se descarta, y además se comprueba que pertenezca al mismo controlador. Nunca detiene el canal nuevo.
- **Importar archivos locales:** no se agregó. Si se agrega, debe leerse aparte y nunca pasar por la ruta de reproducción remota.

---

## 4. Parsers y límites (`core/Limits.kt`)

| Límite | Valor |
|---|---|
| Lista (bytes **descomprimidos**, contados al leer) | 2 MiB |
| Entradas | 2,000 |
| Nombre o categoría | 256 caracteres |
| URL | 4,096 (se rechaza, nunca se trunca) |
| Fuentes por canal | 8 |
| Línea M3U | 8,192 caracteres |
| XML: profundidad / elementos / texto | 16 / 40,000 / 8,192 caracteres |
| Descarga de lista: conexión / lectura / **total** | 10 s / 15 s / 45 s |

- **M3U:** solo se usan `#EXTINF` (nombre y `group-title`) y `#EXTGRP`. Se ignoran `#EXTVLCOPT`, `#KODIPROP`, user-agent, referer y cabeceras: la lista no puede cambiar cómo se conecta la app.
- **Logos de la lista:** `tvg-logo` se ignora.
- **XSPF:** se lee con un parser XML real (`XmlPullParser`) con namespaces.
  - Cualquier `<!DOCTYPE` o `<!ENTITY` hace que el documento se **rechace antes de parsear**. Así no hay DTD, entidades externas ni expansión de entidades, sin depender de las opciones de cada versión de Android.
  - Solo se aceptan las entidades estándar (`&amp;`, `&lt;`, etc.). Una entidad desconocida es un error.

---

## 5. Concurrencia, persistencia y ciclo de vida

- **Hilos:** disco en `Dispatchers.IO`; parseo y clasificación en `Dispatchers.Default`; en el hilo principal solo la interfaz.
- **Persistencia transaccional** (`core/storage/PlaylistStore.kt`):
  - **URL y contenido van en UN solo archivo** (`store/playlist.dat`), con suma SHA-256.
  - Se escribe un temporal completo, se sincroniza (`fsync`) y se renombra encima (`rename` atómico).
  - Si el proceso muere antes del rename, queda la versión anterior completa. Si muere después, la nueva completa. Nunca hay URL de una versión con contenido de otra.
  - Un archivo truncado, alterado o demasiado grande se descarta.
  - Ya no existe un `commit()` de preferencias aparte: la URL vigente es la del archivo.
- **Una sola fuente de verdad** (`core/load/PlaylistRepository.kt`):
  - La interfaz solo muestra `committed`, que es exactamente lo que hay en disco.
  - Cada operación (arranque, cambio de lista o recarga) toma un **turno**. La anterior se cancela y queda obsoleta.
  - La confirmación ocurre bajo un mutex: se comprueba que el turno sigue vigente y luego, **sin poder cancelarse a la mitad**, se escribe el archivo y se publica. O pasa todo, o nada.
  - Si la escritura falla (disco lleno, por ejemplo), no se publica y queda la versión anterior (`LoadError.STORAGE`).
  - **Limpieza condicional:** si la lista guardada resulta inválida al leerla, solo se borra **esa versión**. Se identifica por la suma SHA-256 del archivo, se compara con el archivo actual antes de borrar, bajo el mismo mutex que las escrituras y con el almacén sincronizado. Si mientras tanto se guardó otra lista (B), la limpieza de la anterior (A) no la toca. Prueba determinista: lectura de A → se guarda B → falla el parseo de A → B sigue en disco y publicada.
- **Arranque bajo el mismo control:**
  - La carga inicial toma su turno antes de leer el disco.
  - Si el usuario elige otra lista mientras tanto, el arranque queda obsoleto: no muestra la lista guardada, no la actualiza y no cancela la operación nueva.
- **Cancelación y errores:**
  - Cancelar la coroutine cancela la llamada de OkHttp, también durante la lectura del cuerpo.
  - `CancellationException` nunca se trata como error.
  - Si una descarga o lista falla, se conserva la última lista válida, en pantalla y en disco.
- **Migración:** los archivos de versiones anteriores se descartan una vez. La URL de la 1.0 (preferencias) solo se usa para la primera descarga.
- **Muerte del proceso:** el reproductor reconstruye su lista desde el disco (`loadStored()` sin turno). Nunca reemplaza una lista más reciente.
- **ExoPlayer:**
  - Una sola instancia: se crea en `onStart` y se libera en `onStop`.
  - Al liberarla se desvincula de `PlayerView`, se quita el listener y se cancelan todos los callbacks (watchdog y progreso).
- **Nombres:** si la lista no trae título, el canal se llama «Canal 1», «Canal 2»…, nunca un trozo de la URL. Esos canales no se fusionan entre sí ni con otro canal llamado igual. La URL se conserva intacta solo para reproducir.
- **Logos:** sin descargas ni conexiones a terceros. Hay 35 logos locales en el APK.

---

## 6. Pruebas

### Ejecutadas de verdad en el entorno de desarrollo

Herramientas: Kotlin 2.0.21 y JUnit 4.13.2. OkHttp 5.5.1 y Okio 3.18.1 se compilaron desde sus fuentes oficiales; Okio necesitó un ajuste local de una línea en un alias de CRC32, solo para esa compilación de prueba.

| Suite | Pruebas | Resultado |
|---|---|---|
| `core/net/UrlPolicyTest` + `NetGuardsTest` | 27 | ✅ |
| `core/playlist/ParsersTest` | 23 | ✅ |
| `core/LoadingTest` (persistencia, repositorio, **limpieza condicional A/B**) | 21 | ✅ |
| `core/player/FailoverControllerTest` | 10 | ✅ |
| `core/player/RecoveryTimersTest` (**8 fuentes → 120 s**; tras 30 s estables, el buffering inicia el plazo de inmediato; buffering repetido no lo amplía; una recuperación estable cancela el vencimiento; callbacks viejos) | 9 | ✅ |
| `net/SafeHttpTest` (OkHttp real; servidores HTTP y HTTPS locales) | 17 | ✅ |
| **Total** | **107** | **107 aprobadas** |

**Pruebas de mutación** (se reintroduce el fallo en una copia y la prueba debe fallar):
- Borrado incondicional en `loadStored()`: falla `staleCleanupNeverDeletesANewerValidList`.
- Sin plazo absoluto: fallan 4 de las 5 pruebas de `RecoveryTimersTest` (revisión 3).
- `beginRecoveryIfNeeded()` sin efecto: fallan las 2 pruebas del buffering tras una reproducción estable (revisión 4).
- Sin guardia de socket ni User-Agent: fallan las 3 pruebas correspondientes.

**Wrapper:** la distribución Gradle 9.3.1 usa `distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`, publicado en https://gradle.org/release-checksums/. El bootstrap `gradle-wrapper.jar` conserva la versión oficial 8.14.3 y su suma `7d3a4ac4…df296172`; descarga y verifica la distribución indicada.

### Validación en GitHub Actions (26-oct-2026)

La ejecución [40362428630](https://github.com/brauliocabosurf-ux/brausport-/actions/runs/40362428630), commit `7933e7f97e66e03fe58c962161d86d83e234298f`, terminó correctamente:

- **112 pruebas Android/JVM aprobadas**, incluidas las cinco HLS con Robolectric y las dependencias reales de Maven; ninguna omitida.
- **6 pruebas del resolver NSC aprobadas**: rutas originales y abreviadas, referencia del manifiesto, rechazo de referencias ambiguas, recursos ausentes y variantes inesperadas.
- `lintDebug` aprobado; conserva advertencias no bloqueantes y `abortOnError = true`.
- `assembleRelease` aprobado, con `lintVitalRelease` y certificado de firma esperado.
- `apksigner` verificó el APK firmado y CI comparó su huella con `brau.expectedCertSha256`.
- El NSC dentro del APK prohíbe HTTP por defecto, confía en certificados del sistema y permite HTTP únicamente a `108.175.13.7`, sin subdominios.
- Se generaron el artefacto `apk` (`BrauSportsPlus.apk`) y su archivo SHA-256. No se publicó una Release.

Las pruebas HLS usan `FakeClock(true)` de Media3 para que avancen los reintentos en Robolectric; desactivan el diagnóstico de plataforma solo en las pruebas. La política de producción permanece intacta.

El empaquetador abrevia rutas de recursos (en esta compilación, el NSC está en `res/8G.xml`). `scripts/dump-network-security.py` resuelve el archivo mediante el ID referenciado por el manifiesto y la tabla de recursos, sin asumir su nombre físico. Las comprobaciones se realizan sobre ese XML del APK firmado.

### Comprobaciones pendientes

| Comprobación | Cómo ejecutarla |
|---|---|
| `gradle/verification-metadata.xml` | Workflow manual **"Generar verificación de dependencias"**. Revisar el artefacto (que las versiones sean las de §7) y subirlo a `gradle/`. Local: `./gradlew --write-verification-metadata sha256 help testDebugUnitTest lintDebug assembleDebug` |

**Publicación:** solo manual (`publicar = true`). El job `gate` exige `distributionSha256Sum` (ya está) y `verification-metadata.xml` (**pendiente**), además de pruebas, lint, firma y NSC. Mientras falte cualquiera, no se publica.

### Pruebas que requieren la televisión (pendientes, **no realizadas**)
1. **Excepción HTTP:** que la lista de `108.175.13.7` descargue y los canales reproduzcan. Anotar si alguno falla con "Fuente bloqueada" (§2).
2. **Mando:** navegación, búsqueda y favoritos; vista dividida y pantalla completa; ◀▶ y CH±; **«Reintentar»**.
3. **Cambio repetido de canales:** 50 o más cambios rápidos.
4. **Cierre y reapertura:** Inicio, Atrás, forzar detención y "No conservar actividades".
5. **Sesión larga:** 2 horas o más, vigilando memoria (`dumpsys meminfo`) y temperatura.
6. **Red inestable:** desconectar el Wi-Fi reproduciendo. Debe aparecer «Reintentar» **a los 120 s, como máximo, desde que empezó a cargar** (aunque antes se viera bien) (más el retraso normal del temporizador).

---

## 7. Compilación, firma y CI

- **Versiones:** AGP 9.1.1, distribución Gradle 9.3.1, Kotlin 2.3.21, Media3 1.11.1, OkHttp 5.5.1, AndroidX (core 1.17.0, appcompat 1.7.1, recyclerview 1.4.0, lifecycle 2.9.2), coroutines 1.10.2. compileSdk 37, targetSdk 36, minSdk 24. Se conserva Kotlin externo con `android.builtInKotlin=false` y `android.newDsl=false`, opciones de compatibilidad documentadas por Android para AGP 9.
- **Pruebas en CI:** Robolectric 4.16, androidx.test core 1.5.0 y ext-junit 1.1.5. Son las mismas versiones que usa Media3 1.11.1 en sus propias pruebas.
- **Release:**
  - Sin respaldo de depuración. Falla si falta la llave o si su certificado no coincide con `brau.expectedCertSha256`.
  - Se **conserva el certificado existente**: no se generó otra clave.
- **Lint** está reactivado (`abortOnError = true`). R8 sigue desactivado.
- **GitHub Actions:**
  - Permisos mínimos; acciones fijadas por SHA verificado; wrapper validado.
  - La llave temporal se crea en `$RUNNER_TEMP` y se borra siempre.
  - Se verifican `apksigner` y la huella del certificado, el **NSC dentro del APK** y el SHA-256.
  - Publicar es **solo manual** y requiere el job `gate`.

**Para habilitar la publicación (una vez):**
1. `distributionSha256Sum` ya está en `gradle/wrapper/gradle-wrapper.properties`.
2. Ejecutar el workflow "Generar verificación de dependencias", revisar `verification-metadata.xml` (que las versiones sean las de §7) y subirlo a `gradle/`.
3. Ejecutar "Compilar y publicar APK" a mano con `publicar = true`.

---

## 8. Riesgos residuales

- **Firmware y decodificadores de la tele.** El video lo decodifican MediaCodec y los códecs del fabricante (Hisense/MediaTek u otro), además de los extractores de Media3. Un stream malicioso podría explotar un fallo ahí, y la app no puede impedirlo. Mitigación: mantener actualizado el sistema de la tele.
- **Contenido de terceros.** Las fuentes son de terceros y no están verificadas: pueden cambiar, desaparecer o servir contenido malicioso dentro de los formatos permitidos.
- **HTTP sin cifrar.** Si se habilita la excepción (§2), el tráfico hacia ese host puede verse y modificarse en la red.
- **IPs públicas que llevan a tu red.** Ver los límites de §1.
- **Llave de firma.** Quien tenga la llave (Secrets de GitHub y el archivo que guardaste) puede publicar actualizaciones que la tele aceptará.
- **Nada se cifra en la tele.** La URL de la lista (puede llevar tokens) va en `store/playlist.dat`; favoritos y último canal en SharedPreferences. `allowBackup="false"` evita que salgan en respaldos.
- **El sideload exige "Fuentes desconocidas".** Desactívalo después de instalar.
