package com.offline.btmesh;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MeshFrameCodec {
    static final int MANUFACTURER_ID = 0x02E5;
    private static final byte MAGIC_0 = 0x42;
    private static final byte MAGIC_1 = 0x4d;
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = 14;
    private static final int MAX_FRAME_BYTES = 23;
    private static final int PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_BYTES;

    private MeshFrameCodec() {
    }

    static List<byte[]> encode(byte[] envelope) throws Exception {
        int total = (int) Math.ceil(envelope.length / (double) PAYLOAD_BYTES);
        if (total < 1) {
            total = 1;
        }
        if (total > 255) {
            throw new IllegalArgumentException("Message is too large for BLE advertisement v1. Keep it under " + (255 * PAYLOAD_BYTES) + " bytes.");
        }

        byte[] messageKey = Arrays.copyOf(CryptoSupport.sha256(envelope), 8);
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            int start = i * PAYLOAD_BYTES;
            int end = Math.min(envelope.length, start + PAYLOAD_BYTES);
            byte[] payload = Arrays.copyOfRange(envelope, start, end);
            byte[] frame = new byte[HEADER_BYTES + payload.length];
            frame[0] = MAGIC_0;
            frame[1] = MAGIC_1;
            frame[2] = VERSION;
            System.arraycopy(messageKey, 0, frame, 3, 8);
            frame[11] = (byte) i;
            frame[12] = (byte) total;
            frame[13] = (byte) payload.length;
            System.arraycopy(payload, 0, frame, HEADER_BYTES, payload.length);
            frames.add(frame);
        }
        return frames;
    }

    static Frame decode(byte[] frame) {
        if (frame == null || frame.length < HEADER_BYTES) {
            return null;
        }
        if (frame[0] != MAGIC_0 || frame[1] != MAGIC_1 || frame[2] != VERSION) {
            return null;
        }
        int index = frame[11] & 0xff;
        int total = frame[12] & 0xff;
        int payloadLength = frame[13] & 0xff;
        if (total < 1 || index >= total || payloadLength < 0 || HEADER_BYTES + payloadLength > frame.length) {
            return null;
        }
        byte[] key = Arrays.copyOfRange(frame, 3, 11);
        byte[] payload = Arrays.copyOfRange(frame, HEADER_BYTES, HEADER_BYTES + payloadLength);
        return new Frame(CryptoSupport.hex(key), index, total, payload);
    }

    static final class Reassembler {
        private final Map<String, ChunkSet> chunks = new HashMap<>();
        private Progress lastProgress = Progress.empty();

        synchronized byte[] accept(byte[] frameBytes) {
            Frame frame = decode(frameBytes);
            if (frame == null) {
                return null;
            }
            ChunkSet set = chunks.get(frame.key);
            if (set == null || set.total != frame.total) {
                set = new ChunkSet(frame.total);
                chunks.put(frame.key, set);
            }
            set.put(frame);
            lastProgress = new Progress(frame.key, set.received, set.total);
            if (!set.complete()) {
                return null;
            }
            chunks.remove(frame.key);
            return set.join();
        }

        synchronized Progress lastProgress() {
            return lastProgress;
        }

        synchronized void pruneOlderThan(long maxAgeMillis) {
            long now = System.currentTimeMillis();
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, ChunkSet> entry : chunks.entrySet()) {
                if (now - entry.getValue().createdAt > maxAgeMillis) {
                    expired.add(entry.getKey());
                }
            }
            for (String key : expired) {
                chunks.remove(key);
            }
        }
    }

    static final class Progress {
        final String key;
        final int received;
        final int total;

        Progress(String key, int received, int total) {
            this.key = key;
            this.received = received;
            this.total = total;
        }

        static Progress empty() {
            return new Progress("", 0, 0);
        }

        String shortKey() {
            return key.substring(0, Math.min(8, key.length()));
        }
    }

    private static final class ChunkSet {
        final int total;
        final byte[][] parts;
        final long createdAt = System.currentTimeMillis();
        int received;

        ChunkSet(int total) {
            this.total = total;
            this.parts = new byte[total][];
        }

        void put(Frame frame) {
            if (parts[frame.index] == null) {
                received++;
            }
            parts[frame.index] = frame.payload;
        }

        boolean complete() {
            return received == total;
        }

        byte[] join() {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (byte[] part : parts) {
                    out.write(part);
                }
                return out.toByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }

    private static final class Frame {
        final String key;
        final int index;
        final int total;
        final byte[] payload;

        Frame(String key, int index, int total, byte[] payload) {
            this.key = key;
            this.index = index;
            this.total = total;
            this.payload = payload;
        }
    }
}
