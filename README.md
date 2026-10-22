# 🎵 Liora Music

Reproductor de música local para Android, escrito en **Kotlin** con **Jetpack Compose** y **Media3 (ExoPlayer)**.

Liora lee tu biblioteca de audio directamente del dispositivo (sin servidores, sin cuentas, sin internet) y la reproduce con un servicio en segundo plano, de modo que la música sigue sonando con la pantalla apagada o al salir de la app.

---

## ✨ Características

- 📚 **Biblioteca local**: descubre tus canciones usando `MediaStore` (título, artista, álbum, duración).
- ▶️ **Reproducción en segundo plano** mediante `MediaSessionService` + `ExoPlayer`.
- 🔔 **Notificación de medios** con controles del sistema y soporte de auriculares Bluetooth.
- 🎨 **UI declarativa** en Jetpack Compose con Material 3.
- 🔐 **Permisos por versión**: usa `READ_MEDIA_AUDIO` en Android 13+ y `READ_EXTERNAL_STORAGE` en versiones anteriores.
- 📱 Compatible desde **Android 7.0 (API 24)**.

## 🔌 Extensiones

Liora admite **proveedores externos** que viven en un repositorio aparte
([Liora-Extensions](https://github.com/Slourzz/Liora-Extensions)): Ajustes →
Extensiones → *Añadir repositorio* → la dirección de su `registry.json`.

Una extensión es un paquete `.lioraext` (un ZIP) con un `manifest.json` y su
código en JavaScript. Se instala, se verifica con su **SHA-256** y se ejecuta
en un motor **Rhino** aislado, sin acceso a las clases de Java.

### Qué límites impone el runtime, de verdad

- **Tiempo**: cada llamada a un proveedor tiene un plazo (20 s por defecto) y
  Rhino cuenta instrucciones: al pasarse, la ejecución se aborta. Un bucle
  infinito no cuelga la app.
- **Hilos**: cada extensión corre en su propio hilo y no comparte estado con
  Liora. Un fallo se marca en esa extensión y no afecta a las demás.
- **Red**: solo los dominios que declara el manifiesto, siempre por https, y
  **se comprueba en cada salto de una redirección**. Una cabecera de
  autorización no viaja a otro dominio.
- **Ficheros**: la extensión solo escribe en su propia carpeta y en las que
  Liora le concede.
- **Memoria**: **Rhino no impone un límite de memoria** y esta es la mayor
  debilidad del aislamiento: un script que reserve mucha memoria puede
  agotar el heap de la app. El límite de instrucciones acota el tiempo, no la
  memoria. Por eso un repositorio de extensiones **no oficial** es código de
  terceros con la confianza que merezca: instálalo solo si te fías de quien
  lo publica. Un proceso separado lo aislaría del todo, a costa de un
  servicio con su propia IPC; queda como mejora pendiente.

## 🧱 Stack técnico

| Componente | Versión |
| --- | --- |
| Kotlin | 2.2.10 (Kotlin integrado de AGP) |
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.5.0 |
| Jetpack Compose BOM | 2026.02.01 |
| Jetpack Media3 | 1.10.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| JDK | 21 |

> **Nota:** desde AGP 9.0 el plugin `org.jetbrains.kotlin.android` ya no se aplica; el soporte de Kotlin viene integrado en el propio plugin de Android.

## 📁 Estructura del proyecto

```
app/src/main/java/com/liora/android/
├── LioraApplication.kt              # Application
├── MainActivity.kt                  # Punto de entrada + permisos
├── data/
│   ├── model/Song.kt                # Modelo de canción
│   └── repository/MediaRepository.kt# Lectura de MediaStore
├── service/PlaybackService.kt       # MediaSessionService + ExoPlayer
└── ui/
    ├── screens/HomeScreen.kt        # Lista de canciones + mini-reproductor
    └── viewmodel/MusicViewModel.kt  # Estado y conexión con MediaController
```

## 🚀 Compilar

Requisitos:

- JDK 17 o superior
- Android SDK con la plataforma **API 37** instalada
- Un archivo `local.properties` en la raíz con la ruta del SDK (no se versiona):
  ```properties
  sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
  ```

Compilar el APK de depuración:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

Instalar en un dispositivo conectado:

```powershell
.\gradlew.bat installDebug
# o
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 🗺️ Roadmap

- [x] Compilación del proyecto (AGP 9 + Kotlin integrado)
- [x] Lectura de la biblioteca local
- [x] Reproducción en segundo plano
- [ ] **Cola de reproducción**: siguiente / anterior y avance automático
- [ ] Metadatos (título, artista, carátula) en la notificación
- [ ] Carátulas de álbum en la lista y el mini-reproductor
- [ ] Barra de progreso con *seek*
- [ ] `ViewModel` obtenido con `viewModels()` para sobrevivir a la rotación
- [ ] Permiso `POST_NOTIFICATIONS` también en Android 13 (API 33)
- [ ] Pantallas de álbumes y artistas, búsqueda y favoritos
- [ ] Ecualizador y ajustes

## 📄 Licencia

Por definir.
