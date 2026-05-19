package com.offline.btmesh;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

final class BleMeshManager {
    interface Listener {
        void onFrameReceived(String frameKey, int received, int total);

        void onEnvelopeReceived(byte[] envelope);

        void onStatusChanged();
    }

    private static final int BROADCAST_REPEATS = 10;
    private static final long ADVERTISE_MILLIS = 260L;
    private static final long IDLE_MILLIS = 700L;
    private static final int MAX_KNOWN_FRAMES = 1600;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MeshFrameCodec.Reassembler reassembler = new MeshFrameCodec.Reassembler();
    private final Queue<OutboundFrame> frameQueue = new ArrayDeque<>();
    private final Set<String> knownFrames = new HashSet<>();

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private boolean running;
    private boolean advertising;
    private long rawFramesSeen;
    private long envelopesSeen;
    private String lastStatus = "Idle";

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            ScanRecord record = result.getScanRecord();
            if (record == null) {
                return;
            }
            byte[] data = record.getManufacturerSpecificData(MeshFrameCodec.MANUFACTURER_ID);
            if (data == null) {
                return;
            }
            rawFramesSeen++;
            byte[] envelope = reassembler.accept(data);
            MeshFrameCodec.Progress progress = reassembler.lastProgress();
            if (progress.total > 0) {
                lastStatus = "Saw BLE chunk " + progress.received + "/" + progress.total
                        + " for " + progress.shortKey()
                        + " | raw chunks: " + rawFramesSeen;
                listener.onFrameReceived(progress.key, progress.received, progress.total);
            }
            reassembler.pruneOlderThan(120_000L);
            if (envelope != null) {
                envelopesSeen++;
                lastStatus = "Reassembled encrypted BLE envelope | raw chunks: " + rawFramesSeen
                        + " | envelopes: " + envelopesSeen;
                listener.onEnvelopeReceived(envelope);
            } else {
                listener.onStatusChanged();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            lastStatus = "BLE scan failed: " + errorCode;
            listener.onStatusChanged();
        }
    };

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            lastStatus = "Broadcasting BLE chunks";
            listener.onStatusChanged();
        }

        @Override
        public void onStartFailure(int errorCode) {
            advertising = false;
            lastStatus = "BLE advertise failed: " + errorCode;
            listener.onStatusChanged();
            handler.postDelayed(BleMeshManager.this::advertiseNext, IDLE_MILLIS);
        }
    };

    BleMeshManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized boolean isRunning() {
        return running;
    }

    synchronized int queuedFrames() {
        return frameQueue.size();
    }

    synchronized String statusText() {
        if (!hasRequiredPermissions()) {
            return "Missing Bluetooth permissions";
        }
        if (!bluetoothEnabled()) {
            return "Bluetooth is off";
        }
        return lastStatus
                + " | queued frames: " + frameQueue.size()
                + " | raw chunks seen: " + rawFramesSeen
                + " | envelopes: " + envelopesSeen;
    }

    @SuppressLint("MissingPermission")
    synchronized void start() {
        if (running) {
            return;
        }
        if (!hasRequiredPermissions()) {
            lastStatus = "Missing Bluetooth permissions";
            listener.onStatusChanged();
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            lastStatus = "Bluetooth is off or unavailable";
            listener.onStatusChanged();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (scanner == null || advertiser == null) {
            lastStatus = "BLE scan/advertise is unavailable on this device";
            listener.onStatusChanged();
            return;
        }
        running = true;
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(
                            MeshFrameCodec.MANUFACTURER_ID,
                            new byte[]{0x42, 0x4d, 0x01},
                            new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff}
                    )
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            lastStatus = "Scanning for offline Bluetooth messages";
        } catch (SecurityException e) {
            lastStatus = "Permission denied while starting BLE scan";
        }
        listener.onStatusChanged();
        handler.post(this::advertiseNext);
    }

    @SuppressLint("MissingPermission")
    synchronized void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (scanner != null) {
                scanner.stopScan(scanCallback);
            }
            if (advertiser != null && advertising) {
                advertiser.stopAdvertising(advertiseCallback);
            }
        } catch (SecurityException ignored) {
        }
        advertising = false;
        lastStatus = "Stopped";
        listener.onStatusChanged();
    }

    synchronized void broadcastEnvelope(byte[] envelope) throws Exception {
        List<byte[]> frames = MeshFrameCodec.encode(envelope);
        for (byte[] frame : frames) {
            String key = CryptoSupport.hex(CryptoSupport.sha256(frame));
            if (knownFrames.add(key)) {
                frameQueue.add(new OutboundFrame(frame, BROADCAST_REPEATS));
            }
        }
        if (knownFrames.size() > MAX_KNOWN_FRAMES) {
            knownFrames.clear();
        }
        lastStatus = "Queued " + frames.size() + " BLE chunks";
        listener.onStatusChanged();
        if (running && !advertising) {
            handler.post(this::advertiseNext);
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void advertiseNext() {
        if (!running || advertiser == null || advertising) {
            return;
        }
        OutboundFrame outbound = frameQueue.poll();
        if (outbound == null) {
            lastStatus = "Scanning; no pending broadcast chunks";
            listener.onStatusChanged();
            handler.postDelayed(this::advertiseNext, IDLE_MILLIS);
            return;
        }
        advertising = true;
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(MeshFrameCodec.MANUFACTURER_ID, outbound.frame)
                .build();
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback);
            handler.postDelayed(() -> stopCurrentAdvertisement(outbound), ADVERTISE_MILLIS);
        } catch (SecurityException e) {
            advertising = false;
            lastStatus = "Permission denied while advertising";
            listener.onStatusChanged();
        } catch (IllegalArgumentException e) {
            advertising = false;
            lastStatus = "BLE chunk too large for advertisement";
            listener.onStatusChanged();
        } catch (RuntimeException e) {
            advertising = false;
            lastStatus = "BLE advertise runtime error: " + e.getClass().getSimpleName();
            listener.onStatusChanged();
        }
    }

    @SuppressLint("MissingPermission")
    private synchronized void stopCurrentAdvertisement(OutboundFrame outbound) {
        if (!running || advertiser == null) {
            advertising = false;
            return;
        }
        try {
            advertiser.stopAdvertising(advertiseCallback);
        } catch (SecurityException ignored) {
        }
        advertising = false;
        outbound.repeatsLeft--;
        if (outbound.repeatsLeft > 0) {
            frameQueue.add(outbound);
        }
        handler.postDelayed(this::advertiseNext, 35L);
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return has(Manifest.permission.BLUETOOTH_SCAN)
                    && has(Manifest.permission.BLUETOOTH_ADVERTISE)
                    && has(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return has(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean bluetoothEnabled() {
        try {
            BluetoothAdapter adapter = adapter();
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean has(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private static final class OutboundFrame {
        final byte[] frame;
        int repeatsLeft;

        OutboundFrame(byte[] frame, int repeatsLeft) {
            this.frame = frame;
            this.repeatsLeft = repeatsLeft;
        }
    }
}
