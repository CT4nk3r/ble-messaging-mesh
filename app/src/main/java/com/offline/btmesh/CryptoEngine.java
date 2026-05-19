package com.offline.btmesh;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyAgreement;

final class CryptoEngine {
    private static final int VERSION = 1;

    private final IdentityStore identityStore;
    private final ContactStore contactStore;

    CryptoEngine(IdentityStore identityStore, ContactStore contactStore) {
        this.identityStore = identityStore;
        this.contactStore = contactStore;
    }

    byte[] encryptForTrustedContacts(String plaintext) throws Exception {
        IdentityStore.Identity identity = identityStore.getOrCreate();
        String id = CryptoSupport.hex(CryptoSupport.randomBytes(16));
        long timestamp = System.currentTimeMillis();
        byte[] contentKey = CryptoSupport.randomBytes(32);
        byte[] bodyNonce = CryptoSupport.randomBytes(12);
        byte[] bodyAad = bodyAad(id, identity.fingerprint, timestamp);
        byte[] bodyCiphertext = CryptoSupport.aesGcmEncrypt(
                contentKey,
                bodyNonce,
                CryptoSupport.utf8(plaintext),
                bodyAad
        );

        List<Recipient> recipients = recipients(identity);
        JSONArray recipientArray = new JSONArray();
        for (Recipient recipient : recipients) {
            byte[] wrapNonce = CryptoSupport.randomBytes(12);
            byte[] wrapKey = deriveWrapKey(
                    identity.encryptionPrivate,
                    recipient.encryptionPublic,
                    identity.fingerprint,
                    recipient.fingerprint,
                    id
            );
            byte[] wrappedKey = CryptoSupport.aesGcmEncrypt(
                    wrapKey,
                    wrapNonce,
                    contentKey,
                    wrapAad(id, identity.fingerprint, recipient.fingerprint)
            );

            JSONObject object = new JSONObject();
            object.put("rid", recipient.fingerprint);
            object.put("nonce", CryptoSupport.b64(wrapNonce));
            object.put("key", CryptoSupport.b64(wrappedKey));
            recipientArray.put(object);
        }

        JSONObject envelope = new JSONObject();
        envelope.put("v", VERSION);
        envelope.put("id", id);
        envelope.put("ts", timestamp);
        envelope.put("sender", identity.fingerprint);
        envelope.put("recipients", recipientArray);
        envelope.put("bodyNonce", CryptoSupport.b64(bodyNonce));
        envelope.put("body", CryptoSupport.b64(bodyCiphertext));

        byte[] signature = sign(identity.signingPrivate, canonical(envelope));
        envelope.put("sig", CryptoSupport.b64(signature));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    Decoded decrypt(byte[] envelopeBytes) {
        try {
            IdentityStore.Identity identity = identityStore.getOrCreate();
            JSONObject envelope = new JSONObject(CryptoSupport.text(envelopeBytes));
            String id = envelope.getString("id");
            String sender = envelope.getString("sender");
            long timestamp = envelope.getLong("ts");

            PublicKey senderSigningKey;
            String senderEncryptionKey;
            String senderLabel;
            if (sender.equals(identity.fingerprint)) {
                senderSigningKey = identity.signingPublic;
                senderEncryptionKey = identity.encryptionPublicB64;
                senderLabel = "me";
            } else {
                ContactStore.Contact contact = contactStore.find(sender);
                if (contact == null) {
                    return Decoded.locked(id, sender.substring(0, Math.min(8, sender.length())), timestamp, "sender is not a trusted contact");
                }
                senderSigningKey = CryptoSupport.decodeEcPublic(contact.signingPublicB64);
                senderEncryptionKey = contact.encryptionPublicB64;
                senderLabel = contact.name;
            }

            if (!verify(senderSigningKey, canonical(envelope), CryptoSupport.b64d(envelope.getString("sig")))) {
                return Decoded.locked(id, senderLabel, timestamp, "bad signature");
            }

            JSONObject recipient = findRecipient(envelope.getJSONArray("recipients"), identity.fingerprint);
            if (recipient == null) {
                return Decoded.locked(id, senderLabel, timestamp, "this device is not a recipient");
            }

            byte[] wrapKey = deriveWrapKey(
                    identity.encryptionPrivate,
                    CryptoSupport.decodeEcPublic(senderEncryptionKey),
                    sender,
                    identity.fingerprint,
                    id
            );
            byte[] contentKey = CryptoSupport.aesGcmDecrypt(
                    wrapKey,
                    CryptoSupport.b64d(recipient.getString("nonce")),
                    CryptoSupport.b64d(recipient.getString("key")),
                    wrapAad(id, sender, identity.fingerprint)
            );
            byte[] body = CryptoSupport.aesGcmDecrypt(
                    contentKey,
                    CryptoSupport.b64d(envelope.getString("bodyNonce")),
                    CryptoSupport.b64d(envelope.getString("body")),
                    bodyAad(id, sender, timestamp)
            );
            return Decoded.open(id, senderLabel, timestamp, CryptoSupport.text(body));
        } catch (Exception e) {
            String id = extractId(envelopeBytes);
            return Decoded.locked(id, "unknown", System.currentTimeMillis(), "could not decrypt");
        }
    }

    String extractId(byte[] envelopeBytes) {
        try {
            return new JSONObject(CryptoSupport.text(envelopeBytes)).getString("id");
        } catch (Exception e) {
            try {
                return CryptoSupport.hex(CryptoSupport.sha256(envelopeBytes)).substring(0, 32);
            } catch (Exception ignored) {
                return Long.toHexString(System.currentTimeMillis());
            }
        }
    }

    private List<Recipient> recipients(IdentityStore.Identity identity) throws Exception {
        Map<String, Recipient> unique = new LinkedHashMap<>();
        unique.put(identity.fingerprint, new Recipient(identity.fingerprint, identity.encryptionPublic));
        for (ContactStore.Contact contact : contactStore.list()) {
            unique.put(contact.fingerprint, new Recipient(contact.fingerprint, CryptoSupport.decodeEcPublic(contact.encryptionPublicB64)));
        }
        return new ArrayList<>(unique.values());
    }

    private static JSONObject findRecipient(JSONArray recipients, String fingerprint) throws Exception {
        for (int i = 0; i < recipients.length(); i++) {
            JSONObject object = recipients.getJSONObject(i);
            if (fingerprint.equals(object.getString("rid"))) {
                return object;
            }
        }
        return null;
    }

    private static byte[] deriveWrapKey(PrivateKey privateKey, PublicKey publicKey, String sender, String recipient, String id) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        byte[] info = CryptoSupport.utf8("btmesh-wrap-v1|" + sender + "|" + recipient + "|" + id);
        return CryptoSupport.hkdfSha256(sharedSecret, null, info, 32);
    }

    private static byte[] bodyAad(String id, String sender, long timestamp) {
        return CryptoSupport.utf8("btmesh-body-v1|" + id + "|" + sender + "|" + timestamp);
    }

    private static byte[] wrapAad(String id, String sender, String recipient) {
        return CryptoSupport.utf8("btmesh-keywrap-v1|" + id + "|" + sender + "|" + recipient);
    }

    private static String canonical(JSONObject envelope) throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append(envelope.getInt("v")).append('\n');
        builder.append(envelope.getString("id")).append('\n');
        builder.append(envelope.getLong("ts")).append('\n');
        builder.append(envelope.getString("sender")).append('\n');
        builder.append(envelope.getJSONArray("recipients").toString()).append('\n');
        builder.append(envelope.getString("bodyNonce")).append('\n');
        builder.append(envelope.getString("body"));
        return builder.toString();
    }

    private static byte[] sign(PrivateKey privateKey, String canonical) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey, CryptoSupport.RNG);
        signature.update(CryptoSupport.utf8(canonical));
        return signature.sign();
    }

    private static boolean verify(PublicKey publicKey, String canonical, byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(publicKey);
        signature.update(CryptoSupport.utf8(canonical));
        return signature.verify(signatureBytes);
    }

    private static final class Recipient {
        final String fingerprint;
        final PublicKey encryptionPublic;

        Recipient(String fingerprint, PublicKey encryptionPublic) {
            this.fingerprint = fingerprint;
            this.encryptionPublic = encryptionPublic;
        }
    }

    static final class Decoded {
        final String id;
        final String senderLabel;
        final long timestamp;
        final String body;
        final boolean open;

        private Decoded(String id, String senderLabel, long timestamp, String body, boolean open) {
            this.id = id;
            this.senderLabel = senderLabel;
            this.timestamp = timestamp;
            this.body = body;
            this.open = open;
        }

        static Decoded open(String id, String senderLabel, long timestamp, String body) {
            return new Decoded(id, senderLabel, timestamp, body, true);
        }

        static Decoded locked(String id, String senderLabel, long timestamp, String reason) {
            return new Decoded(id, senderLabel, timestamp, "Locked ciphertext: " + reason, false);
        }
    }
}
