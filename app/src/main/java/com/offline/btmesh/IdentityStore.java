package com.offline.btmesh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

final class IdentityStore {
    private static final String PREFS = "identity";
    private static final String ENC_PUBLIC = "enc_public";
    private static final String ENC_PRIVATE = "enc_private";
    private static final String SIGN_PUBLIC = "sign_public";
    private static final String SIGN_PRIVATE = "sign_private";

    private final SharedPreferences prefs;
    private Identity cached;

    IdentityStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Identity getOrCreate() throws Exception {
        if (cached != null) {
            return cached;
        }
        String encPublic = prefs.getString(ENC_PUBLIC, null);
        String encPrivate = prefs.getString(ENC_PRIVATE, null);
        String signPublic = prefs.getString(SIGN_PUBLIC, null);
        String signPrivate = prefs.getString(SIGN_PRIVATE, null);
        if (encPublic == null || encPrivate == null || signPublic == null || signPrivate == null) {
            KeyPair encryption = generateEcPair();
            KeyPair signing = generateEcPair();
            encPublic = CryptoSupport.b64(encryption.getPublic().getEncoded());
            encPrivate = CryptoSupport.b64(encryption.getPrivate().getEncoded());
            signPublic = CryptoSupport.b64(signing.getPublic().getEncoded());
            signPrivate = CryptoSupport.b64(signing.getPrivate().getEncoded());
            prefs.edit()
                    .putString(ENC_PUBLIC, encPublic)
                    .putString(ENC_PRIVATE, encPrivate)
                    .putString(SIGN_PUBLIC, signPublic)
                    .putString(SIGN_PRIVATE, signPrivate)
                    .apply();
        }
        cached = new Identity(encPublic, encPrivate, signPublic, signPrivate);
        return cached;
    }

    String exportBundle(String displayName) throws Exception {
        Identity identity = getOrCreate();
        JSONObject object = new JSONObject();
        object.put("v", 1);
        object.put("name", displayName == null ? "Android peer" : displayName);
        object.put("fingerprint", identity.fingerprint);
        object.put("enc", identity.encryptionPublicB64);
        object.put("sig", identity.signingPublicB64);
        return object.toString(2);
    }

    private static KeyPair generateEcPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), CryptoSupport.RNG);
        return generator.generateKeyPair();
    }

    static final class Identity {
        final String encryptionPublicB64;
        final String encryptionPrivateB64;
        final String signingPublicB64;
        final String signingPrivateB64;
        final String fingerprint;
        final PublicKey encryptionPublic;
        final PrivateKey encryptionPrivate;
        final PublicKey signingPublic;
        final PrivateKey signingPrivate;

        Identity(String encryptionPublicB64, String encryptionPrivateB64, String signingPublicB64, String signingPrivateB64) throws Exception {
            this.encryptionPublicB64 = encryptionPublicB64;
            this.encryptionPrivateB64 = encryptionPrivateB64;
            this.signingPublicB64 = signingPublicB64;
            this.signingPrivateB64 = signingPrivateB64;
            this.fingerprint = CryptoSupport.shortFingerprint(encryptionPublicB64, signingPublicB64);
            this.encryptionPublic = CryptoSupport.decodeEcPublic(encryptionPublicB64);
            this.encryptionPrivate = CryptoSupport.decodeEcPrivate(encryptionPrivateB64);
            this.signingPublic = CryptoSupport.decodeEcPublic(signingPublicB64);
            this.signingPrivate = CryptoSupport.decodeEcPrivate(signingPrivateB64);
        }
    }
}
