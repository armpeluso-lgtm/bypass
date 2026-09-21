# Bypass Power

Aplicación Android mínima para Samsung Galaxy que controla exclusivamente el ajuste del sistema `Settings.System["pass_through"]`.

- `0`: carga normal.
- `1`: bypass / Pause USB Power Delivery.

La aplicación no usa root, Shizuku, ADB desde el dispositivo, Internet ni almacenamiento externo. Tanto la pantalla principal como el mosaico de Ajustes rápidos vuelven a leer siempre el valor real de `Settings.System`.

Además, guarda únicamente la decisión manual del usuario: al pulsar **Activar**, recuerda que el bypass debe mantenerse activo hasta pulsar **Desactivar**. Esa preferencia no sustituye al valor real mostrado; sirve para restaurarlo cuando Samsung o Game Booster lo reinician.

## Requisitos

- Android Studio con Android SDK Platform 36 instalado.
- JDK 17 o posterior compatible con la versión de Gradle incluida.
- Un Samsung Galaxy compatible con el ajuste interno `pass_through`.

El proyecto usa intencionadamente:

```kotlin
compileSdk = 36
targetSdk = 22
```

No aumentes `targetSdk`: el valor 22 es deliberado para reproducir el comportamiento probado con SetEdit. `minSdk` es 24 porque `TileService` apareció en Android 7.0.

## Compilar

En Android Studio, abre la carpeta raíz del proyecto, deja que Gradle sincronice y selecciona **Build → Build APK(s)**.

Desde una terminal:

```powershell
.\gradlew.bat assembleDebug
```

En macOS o Linux:

```bash
./gradlew assembleDebug
```

La APK de depuración queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalar en Android 16

Android 16 bloquea normalmente la instalación de aplicaciones con un `targetSdk` tan antiguo. La instalación manual debe realizarse mediante una herramienta que permita la opción:

```text
--bypass-low-target-sdk-block
```

Por ejemplo, puede usarse **Install with Options** y activar la opción equivalente. Si se instala desde un equipo con Android SDK Platform Tools, el comando es:

```bash
adb install --bypass-low-target-sdk-block app/build/outputs/apk/debug/app-debug.apk
```

ADB solo se menciona aquí como método de instalación desde el equipo; la aplicación no ejecuta ADB ni depende de él.

## Primer uso

1. Abre **Bypass Power**.
2. Pulsa **Conceder permiso**.
3. Activa **Permitir modificar ajustes del sistema** para la aplicación.
4. Abre el panel rápido de One UI, entra en **Editar** y añade **Bypass**.

El mosaico lee el estado al abrir el panel rápido y después de cada toque. La pantalla principal observa cambios de ajustes mientras está visible, por lo que también refleja cambios realizados por Samsung Game Booster.

## Mantenimiento automático

Cuando el usuario deja el bypass activado:

1. Android inicia un servicio de observación al activar el bypass.
2. El servicio observa toda `Settings.System.CONTENT_URI` y comprueba `pass_through` cuando el proveedor notifica un cambio.
3. También comprueba el valor ante cambios de batería, conexión o desconexión del cargador, encendido y desbloqueo de pantalla, apertura de la app y apertura del panel rápido.
4. Al conectar el cargador con el mantenimiento habilitado y batería superior al 20%, escribe `1` inmediatamente y vuelve a confirmar el estado durante los primeros 12 segundos para cubrir la negociación USB-PD/carga súper rápida de Samsung. Son comprobaciones finitas disparadas únicamente por el evento de conexión.
5. No existe polling periódico: fuera de esa breve secuencia de conexión, el servicio permanece dormido entre eventos.
6. La protección continúa tanto con el cargador conectado como desconectado.
7. Al pulsar **Desactivar** en la app o el mosaico, se borra la preferencia, se escribe `0` y se detiene el servicio cuando no está protegiendo el límite de batería baja.

### Protección de batería al 20%

- El bypass solo puede activarse cuando el nivel de batería es superior al 20%.
- Mientras el mantenimiento está activo, el servicio escucha los cambios normales de batería enviados por Android.
- Al alcanzar el 20%, primero borra la preferencia de mantenimiento y escribe `pass_through = 0`.
- Mientras el nivel permanezca en 20% o menos, conserva un modo de protección dirigido por eventos que vuelve a forzar `0` si Samsung o Game Booster intenta activar el bypass, incluso al conectar el cargador.
- Cuando la batería vuelve a superar el 20%, el modo de protección se retira, pero no reactiva el bypass.
- Permanecerá apagado aunque la batería vuelva a subir. El usuario deberá activarlo manualmente otra vez cuando el nivel sea superior al 20%.

El servicio permanece activo mientras la preferencia está habilitada o mientras protege el límite de batería baja. No mantiene un wakelock, no realiza actividad de red ni ejecuta comprobaciones periódicas. Android exige una notificación de baja prioridad mientras el servicio está activo.

Después de reiniciar el teléfono, la protección vuelve a evaluar el nivel y la preferencia si el permiso para modificar ajustes continúa concedido.

## Medición de batería

La pantalla principal muestra, mientras está abierta:

- Nivel, estado y fuente de alimentación.
- Corriente neta instantánea y promedio reportada por `BatteryManager`.
- Potencia neta estimada en vatios, calculada con la corriente y el voltaje de la batería.
- Promedio móvil de corriente y potencia de los últimos 30 segundos.
- Voltaje y temperatura.
- Energía neta acumulada y potencia promedio desde que se abrió la pantalla.
- Diagnóstico automático después de al menos 10 segundos: muestra **Bypass probablemente funcionando** cuando el promedio móvil permanece a ±250 mA de cero con el cargador conectado y el ajuste activo.

El muestreo de estas estadísticas se realiza cada segundo únicamente mientras la Activity está visible; se cancela al cerrarla. Las estadísticas se reinician cuando el bypass cambia entre activo e inactivo para no mezclar carga normal con bypass. No añade polling al servicio en segundo plano.

Un valor positivo indica energía neta entrando a la batería y uno negativo indica descarga. Con el cargador conectado y el bypass activo, una potencia cercana a cero es una señal útil de que la batería recibe poca o ninguna carga neta. No representa la potencia total que el cargador entrega al teléfono: parte de esa energía puede alimentar directamente el sistema, la pantalla, CPU y GPU. La precisión y frecuencia del sensor dependen del hardware de Samsung.

## Limitaciones

`pass_through` es un ajuste interno de Samsung, no una API pública de Android. Su disponibilidad y efecto dependen del modelo, la versión de One UI, el cargador y las condiciones que Samsung imponga a Pause USB Power Delivery. Si Samsung elimina o restringe el ajuste en una actualización, la app no puede sustituirlo sin cambiar de enfoque técnico.

Si Game Booster modifica `pass_through` sin notificar al proveedor de ajustes y sin coincidir con otro de los eventos observados, la app no puede detectar ese cambio hasta el siguiente evento. Esta versión prioriza el funcionamiento dirigido por eventos y no realiza polling de respaldo.

Si el usuario aplica **Forzar detención** a la aplicación desde Ajustes, Android bloquea temporalmente sus receptores y servicios hasta que la app se abra de nuevo. Cerrar la pantalla normalmente o retirarla de aplicaciones recientes no desactiva la protección.
