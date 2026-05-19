package com.offline.btmesh;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class CryptoSupport {
    static final SecureRandom RNG = new SecureRandom();

    private CryptoSupport() {
    }

    static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RNG.nextBytes(out);
        return out;
    }

    static String b64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static byte[] b64d(String encoded) {
        return Base64.decode(encoded, Base64.NO_WRAP);
    }

    static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static byte[] sha256(byte[]... chunks) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] chunk : chunks) {
            digest.update(chunk);
        }
        return digest.digest();
    }

    static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    static String shortFingerprint(String encryptionPublicB64, String signingPublicB64) throws Exception {
        byte[] hash = sha256(
                utf8("btmesh-contact-fingerprint-v1"),
                b64d(encryptionPublicB64),
                b64d(signingPublicB64)
        );
        return hex(Arrays.copyOf(hash, 16));
    }

    static PublicKey decodeEcPublic(String encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(b64d(encoded)));
    }

    static PrivateKey decodeEcPrivate(String encoded) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(b64d(encoded)));
    }

    static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, nonce));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(plaintext);
    }

    static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey secretKey = new SecretKeySpec(key, "AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, nonce));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertext);
    }

    static byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, byte[] info, int length) throws Exception {
        byte[] realSalt = salt == null ? new byte[32] : salt;
        byte[] prk = hmac(realSalt, inputKeyMaterial);
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int copied = 0;
        int counter = 1;
        while (copied < length) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            if (info != null) {
                mac.update(info);
            }
            mac.update((byte) counter);
            previous = mac.doFinal();
            int toCopy = Math.min(previous.length, length - copied);
            System.arraycopy(previous, 0, result, copied, toCopy);
            copied += toCopy;
            counter++;
        }
        return result;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }
}
