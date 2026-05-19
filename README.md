# Offline Bluetooth Mesh Messenger

Android-first prototype for offline Bluetooth messaging.

## What works in this build

- Android native app, no internet permission.
- BLE advertisement scanning and broadcasting.
- Foreground mesh service for active Bluetooth operation.
- QR-code identity exchange for trusted contacts.
- End-to-end encrypted messages for trusted contacts.
- Locked/radio diagnostics for encrypted broadcasts that arrive before trust or before all BLE chunks are reassembled.

## Testing flow

1. Install the APK on two Android phones.
2. Grant Bluetooth permissions on both phones.
3. Tap `Start mesh` on both phones.
4. Tap `Show my QR` on phone A and `Scan contact QR` on phone B, then trust the contact.
5. Repeat in the other direction.
6. Send short messages while Wi-Fi and mobile data are off.

The status panel should show raw BLE chunks when radio packets are seen. If a peer is not trusted, received traffic stays locked instead of showing plaintext.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```
