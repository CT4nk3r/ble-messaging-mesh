package com.offline.btmesh;

import org.json.JSONObject;

final class ContactPreview {
    private ContactPreview() {
    }

    static String name(String bundleJson) throws Exception {
        JSONObject object = new JSONObject(bundleJson.trim());
        return object.optString("name", "Android peer");
    }

    static String fingerprint(String bundleJson) throws Exception {
        JSONObject object = new JSONObject(bundleJson.trim());
        String enc = object.getString("enc");
        String sig = object.getString("sig");
        return CryptoSupport.shortFingerprint(enc, sig);
    }
}
