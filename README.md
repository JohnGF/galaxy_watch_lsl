# WatchStreamer

![Build Status](https://img.shields.io/badge/build-passing-brightgreen)
![Platform](https://img.shields.io/badge/platform-WearOS%20|%20Android-blue)
![Kotlin](https://img.shields.io/badge/kotlin-1.9.0-blueviolet)

WatchStreamer is an Android and Wear OS application designed to stream sensor data (IMU, Heart Rate, and Touch Events) over UDP to a local network, typically for integration with Lab Streaming Layer (LSL) or other client applications.

## Architecture / Streaming Pipeline

```mermaid
graph LR
    A[Galaxy Watch / WearOS] -->|UDP Stream| B[LSL Streamer / PC]
    C[Android Phone] -->|UDP Stream| B
    B -->|LSL Stream| D[Client Apps]
```

## Features
- **High-frequency sensor streaming:** Accelerometer and Gyroscope data.
- **Biometric streaming:** Heart rate tracking.
- **Touch Event tracking:** X, Y coordinates and touch states for interaction capture.
- **Dual Support:** Modules provided for both Wear OS (e.g., Galaxy Watch) and standard Android devices.
- **Keeps streaming with the screen off (watch):** Streaming runs in a health-type foreground service with a wake lock, a bound Wi-Fi network and a low-latency Wi-Fi lock, shown as an Ongoing Activity on the watch face. Lowering the wrist or covering the watch no longer stops the stream, and the socket is rebuilt when Wi-Fi comes back.

## Packet Format

Data is sent over UDP, with each packet containing a single line of CSV-formatted text. The format is as follows:

```csv
accX,accY,accZ,gyroX,gyroY,gyroZ,hr,touchX,touchY,isTouched,timestampNs
```

*   **`timestampNs`**: the acquisition time of the sample. A packet is sent on every accelerometer event, stamped with `SensorEvent.timestamp`; gyroscope and heart-rate values ride along as their latest readings. Touch packets use `SystemClock.elapsedRealtimeNanos()`. Both run on the same clock base.
*   **Heartbeat:** once a second the watch also sends `HB,sent,dropped,batteryPercent,timestampNs`. It has 5 fields, so the bridge's IMU parser (6+ fields) ignores it; newer bridges use it to tell a dropped device from a still one.
*   This payload string is constructed in `core/StreamState.toPayloadString()` and is verified by tests in `StreamHelperTest`.

## Setup & Installation

### WearOS / Android Installation
1. Enable **Developer Options** and **Wireless Debugging** on your Watch or Phone.
2. Connect your device to the same Wi-Fi network as your development PC.
3. Build the APK using `./gradlew assembleRelease` or by running `make build`.
4. Deploy the APK to your device using `adb install <path_to_apk>` or use the provided deployment script for watches: `./deploy_to_watch.ps1`.

### Network Configuration
1. Setup a Mobile Hotspot on your PC or ensure both the PC and Watch/Phone are on the same local network.
2. Find your PC's IP Address (e.g., `192.168.137.1`).
3. Open the WatchStreamer app on your device, enter the PC's IP address, and tap **"START STREAMING"**.

## Connecting to Nautilus BCI

This application is designed to integrate with [nautilus_bci](https://github.com/JohnGF/nautilus_bci).

To receive the streamed data on your PC, run the bridge script provided in the nautilus_bci repository from its root directory:

```bash
uv run python scripts/bridges/smartwatch_lsl_bridge.py --mode udp --port 5005
```

*(Note: The app streams to UDP port 5005 by default, so you must explicitly specify `--port 5005` as the bridge defaults to 8080).*

This bridge script receives the UDP packets and publishes them as Lab Streaming Layer (LSL) streams `Smartwatch_IMU` and `Smartwatch_PPG`. It maps the `timestampNs` field onto the local LSL time, preserving the original acquisition time of the sensor data rather than the network arrival time.

## Project Structure
- `:app` - Wear OS application module containing the UI and logic for smartwatches.
- `:mobile` - Standard Android application module containing the UI and logic for smartphones.

## Troubleshooting

If you are having trouble connecting the app to your PC, check the following:

1.  **Network**: Ensure both the Android device (watch or phone) and the receiving PC are connected to the **same Wi-Fi network**.
2.  **Target IP**: Ensure you have correctly entered the PC's IP address into the watch or phone app before pressing "Start Streaming".
3.  **Stream stops when the screen goes off (watch)**:
    *   Accept the **body sensors** permission and then **"Allow all the time"** for background body sensors; without the second one, heart rate can stop with the screen off. Over adb: `adb shell pm grant com.nautilus.watchstreamer android.permission.BODY_SENSORS_BACKGROUND`.
    *   Accept the battery optimization exemption the app asks for on first launch (`adb shell dumpsys deviceidle whitelist +com.nautilus.watchstreamer` does the same).
    *   On Galaxy watches, make sure the app is not in **Settings → Battery → Background usage limits → Sleeping apps**.
    *   The on-screen status line shows `Waiting for Wi-Fi` while the watch has no Wi-Fi; packets sent in that window are counted as dropped.
4.  **Firewall (App)**: Check that the firewall on the receiving PC allows incoming UDP packets on the target port (default: 5005).
5.  **Firewall (LSL)**: For the Lab Streaming Layer to function properly on the network, LSL requires specific ports to be open on your PC:
    *   **UDP**: 16571 - 16636
    *   **TCP**: 16572 - 16636
    *   For a detailed walkthrough, refer to the [nautilus_bci LSL network/firewall guide](https://github.com/JohnGF/nautilus_bci/blob/feature/gpype-pipeline/docs/guides/lsl_network_setup.md).
