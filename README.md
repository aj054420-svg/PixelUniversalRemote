# Pixel Universal Remote v2

Control remoto universal para TV por Wi-Fi, en Kotlin nativo para Android.
Sin anuncios, sin cuentas, todo queda en tu teléfono.

## Qué controla (por Wi-Fi, misma red que la TV)

| Marca | Protocolo | Notas |
|---|---|---|
| Samsung viejita (2014–2016, ej. UN40J5300AF 2015) | Clásico, puerto 55000, WebSocket sin cifrar | La app lo prueba primero |
| Samsung nueva (Tizen 2016+) | Puertos 8001 (ws) / 8002 (wss), `ms.channel.connect` | La primera vez la TV pide permitir en pantalla; el token se guarda y ya no vuelve a preguntar |
| Roku | ECP HTTP `http://IP:8060/keypress/<tecla>` | Activa "control de red" en la TV si no responde |
| LG webOS | WebSocket SSAP (`wss://IP:3001`, o `ws://IP:3000`) + registro con client-key guardada | La primera vez la TV pide permitir; los botones van por el socket de entrada (`getPointerInputSocket`) |

La app prueba el protocolo Samsung clásico primero y cae a Tizen si no responde.

## Funciones

- Lista de TVs guardadas (nombre, IP, marca/protocolo), con alta manual por IP.
- Descubrimiento por SSDP en la red local (Samsung, Roku, LG).
- Botones: Encendido, Inicio, flechas, OK, Atrás, Volumen +/-, Silencio,
  Canales +/-, Fuente/Entrada y teclado numérico 0-9.
- Los tokens de emparejamiento (Samsung Tizen, LG client-key) se guardan
  en el teléfono y se reutilizan sin volver a pedir permiso.

## Compilar

### Con GitHub Actions (gratis, sin instalar nada)

1. Sube este proyecto a un repositorio en GitHub (rama `main`).
2. Pestaña **Actions** → **Build Pixel Universal Remote APK** → **Run workflow**
   (también corre solo con cada push a `main`).
3. Cuando termine en verde, descarga el artifact
   **Pixel-Universal-Remote-debug-apk** y abre `app-debug.apk` en el teléfono.
4. Android pedirá autorizar la instalación desde el navegador/archivos una vez.

### En tu computadora

```bash
gradle assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

Requisitos: JDK 17, Android SDK con `platforms;android-34` y `build-tools;34.0.0`.

## Configurar la TV

1. Teléfono y TV en la **misma Wi-Fi** (no la red de invitados).
2. En la TV: Ajustes → Red → anota su IP local (ej. 192.168.1.40).
3. En la app: "Agregar TV manualmente" (nombre, IP, marca) o "Buscar TVs en Wi-Fi".
4. La primera vez, la TV muestra un aviso para permitir el control: acéptalo
   con el control físico de la TV.
5. Prueba primero Inicio, flechas y OK.

## Pendiente (no está en esta versión)

- **Android TV / Google TV por ADB**: requiere emparejamiento ADB por Wi-Fi
  (puerto 5555) y otro protocolo; no implementado en v2.
- **Infrarrojo (IR)**: el teléfono no tiene emisor IR, así que las teles LED
  sin Wi-Fi no se pueden controlar desde el teléfono con ninguna app.
- **Encendido por Wake-on-LAN**: algunas teles apagadas no responden por red;
  LG apagada no enciende por SSAP (solo apaga). Samsung y Roku sí suelen
  responder al botón de encendido si la TV quedó en espera con red activa.
- Sony, Philips, Hisense/VIDAA, Vizio, Fire TV: no implementados en v2.

## Seguridad

Los protocolos de red local pueden ir sin cifrar. Usa una Wi-Fi de confianza
y no abras el control a Internet.
