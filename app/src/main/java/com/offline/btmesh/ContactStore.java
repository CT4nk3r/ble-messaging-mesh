package com.offline.btmesh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ContactStore {
    private static final String PREFS = "contacts";
    private static final String ITEMS = "items";

    private final SharedPreferences prefs;

    ContactStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized Contact addFromBundle(String fallbackName, String bundleJson) throws Exception {
        JSONObject bundle = new JSONObject(bundleJson.trim());
        String enc = bundle.getString("enc");
        String sig = bundle.getString("sig");
        String fingerprint = CryptoSupport.shortFingerprint(enc, sig);
        String name = fallbackName == null || fallbackName.trim().isEmpty()
                ? bundle.optString("name", fingerprint.substring(0, 8))
                : fallbackName.trim();

        JSONArray items = readArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject existing = items.getJSONObject(i);
            if (fingerprint.equals(existing.getString("fingerprint"))) {
                existing.put("name", name);
                existing.put("enc", enc);
                existing.put("sig", sig);
                existing.put("trustedAt", System.currentTimeMillis());
                saveArray(items);
                return Contact.fromJson(existing);
            }
        }

        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("fingerprint", fingerprint);
        object.put("enc", enc);
        object.put("sig", sig);
        object.put("trustedAt", System.currentTimeMillis());
        items.put(object);
        saveArray(items);
        return Contact.fromJson(object);
    }

    synchronized List<Contact> list() {
        try {
            JSONArray items = readArray();
            List<Contact> contacts = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                contacts.add(Contact.fromJson(items.getJSONObject(i)));
            }
            Collections.sort(contacts, (a, b) -> a.name.compareToIgnoreCase(b.name));
            return contacts;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    synchronized Contact find(String fingerprint) {
        for (Contact contact : list()) {
            if (contact.fingerprint.equals(fingerprint)) {
                return contact;
            }
        }
        return null;
    }

    private JSONArray readArray() throws Exception {
        return new JSONArray(prefs.getString(ITEMS, "[]"));
    }

    private void saveArray(JSONArray array) {
        prefs.edit().putString(ITEMS, array.toString()).apply();
    }

    static final class Contact {
        final String name;
        final String fingerprint;
        final String encryptionPublicB64;
        final String signingPublicB64;
        final long trustedAt;

        Contact(String name, String fingerprint, String encryptionPublicB64, String signingPublicB64, long trustedAt) {
            this.name = name;
            this.fingerprint = fingerprint;
            this.encryptionPublicB64 = encryptionPublicB64;
            this.signingPublicB64 = signingPublicB64;
            this.trustedAt = trustedAt;
        }

        static Contact fromJson(JSONObject object) throws Exception {
            return new Contact(
                    object.getString("name"),
                    object.getString("fingerprint"),
                    object.getString("enc"),
                    object.getString("sig"),
                    object.optLong("trustedAt", 0L)
            );
        }
    }
}
