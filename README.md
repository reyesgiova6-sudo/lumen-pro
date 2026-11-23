# LUMEN Pro — cámara profesional para Android

Cámara nativa **Camera2** escrita en Java, sin dependencias de terceros ni publicidad, con
control manual real, RAW/DNG, HDR por ráfaga y apilado nocturno. Nada de "modos" que solo
cambian un filtro: aquí el sensor se controla de verdad y las tomas múltiples se alinean y se
fusionan en el dispositivo.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)
![Camera2](https://img.shields.io/badge/API-Camera2-FFD166)
![License](https://img.shields.io/badge/license-MIT-blue)

## Descargar

El APK firmado está en **[Releases](../../releases)**. Descárgalo en el teléfono y ábrelo
(Android pedirá permitir la instalación de apps de origen desconocido).

## Qué hace de verdad

| Modo | Qué ocurre |
|---|---|
| **AUTO** | Captura directa del HAL a máxima calidad, sin recompresión. |
| **PRO** | ISO, velocidad de obturación, enfoque manual y balance de blancos en Kelvin, con lectura en vivo. Botón **BLOQUEAR AE** para fijar la exposición medida. |
| **HDR** | Ráfaga de 3/5/7 exposiciones (−3…+3 EV) → alineado por *median threshold bitmap* → fusión ponderada por exposición → microcontraste y curva tonal. |
| **NOCHE** | 3–9 tomas apiladas en espacio lineal con rechazo de movimiento, reducción de ruido de croma y realce de detalle. |
| **VÍDEO** | 1080p/1440p/4K, HEVC o H.264, bitrate hasta 150 Mbps, estabilización EIS/OIS, audio AAC 48 kHz. |

Además:

- **RAW / DNG** real (cuando el fabricante lo expone por Camera2).
- Histograma RGB en vivo, horizonte, rejilla de tercios, zoom hasta 10× (pellizco o doble toque).
- Enfoque y medición por toque, temporizador 3/10 s, flash automático/manual/linterna.
- Visor con zoom, compartir y borrar.
- Guardado en `Pictures/LUMEN` y `Movies/LUMEN` vía MediaStore (sin permisos de almacenamiento en Android 10+).
- EXIF completo: ISO, tiempo de exposición, apertura, focal, software, número de tomas.

## Compilar

Requisitos: JDK 17+ y Android SDK (compileSdk 37).

```bash
./gradlew assembleDebug
```

APK en `app/build/outputs/apk/debug/app-debug.apk`.

Para una compilación firmada crea `keystore.properties` en la raíz (no se sube al repo):

```properties
storeFile=lumen-release.jks
storePassword=TU_PASSWORD
keyAlias=lumen
keyPassword=TU_PASSWORD
```

```bash
./gradlew assembleRelease
```

## Estructura

```
app/src/main/java/mx/lumen/camera/
├── MainActivity.java      interfaz, modos, disparo, guardado
├── CameraEngine.java      Camera2: sesiones, control manual, RAW, ráfagas, vídeo
├── ImageFusion.java       alineado MTB, fusión HDR, apilado nocturno, tone mapping
├── MediaStoreSaver.java   guardado en galería, EXIF, vídeo pendiente
├── OverlayView.java       rejilla, horizonte, histograma, foco, REC
├── SliderView.java        sliders de ISO/velocidad/enfoque/WB
├── ShutterButton.java     botón de disparo
├── IconView.java          iconos vectoriales dibujados a mano
├── SettingsDialog.java    ajustes
└── ViewerActivity.java    visor con zoom
```

## Límites honestos

- El procesado HDR/noche es Java puro, multihilo, con tope de fusión configurable
  (4–12 MP) para no agotar memoria. No es un ISP dedicado: es fotografía computacional real,
  pero no magia.
- La resolución máxima depende de lo que el HAL del teléfono exponga por Camera2. Muchos
  fabricantes solo publican 12 MP aunque el sensor tenga 50 MP.
- El nivel del horizonte usa el acelerómetro; en algunos móviles el eje puede requerir ajuste.
- No hay acceso a red: la app no envía nada a ningún servidor.

## Licencia

MIT — ver [LICENSE](LICENSE).
