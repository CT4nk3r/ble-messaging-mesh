package com.offline.btmesh;

import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

final class MeshRepository implements BleMeshManager.Listener {
    interface Listener {
        void onRepositoryChanged();
    }

    private static MeshRepository instance;

    static synchronized MeshRepository get(Context context) {
        if (instance == null) {
            instance = new MeshRepository(context.getApplicationContext());
        }
        return instance;
    }

    private final IdentityStore identityStore;
    private final ContactStore contactStore;
    private final MessageStore messageStore;
    private final CryptoEngine cryptoEngine;
    private final BleMeshManager bleMeshManager;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> partialKeys = new HashSet<>();

    private String lastError = "";

    private MeshRepository(Context context) {
        identityStore = new IdentityStore(context);
        contactStore = new ContactStore(context);
        messageStore = new MessageStore(context);
        cryptoEngine = new CryptoEngine(identityStore, contactStore);
        bleMeshManager = new BleMeshManager(context, this);
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void startMesh() {
        bleMeshManager.start();
        notifyListeners();
    }

    void stopMesh() {
        bleMeshManager.stop();
        notifyListeners();
    }

    boolean isMeshRunning() {
        return bleMeshManager.isRunning();
    }

    String exportIdentity() {
        try {
            return identityStore.exportBundle("Android peer");
        } catch (Exception e) {
            lastError = e.getMessage();
            return "";
        }
    }

    String localFingerprint() {
        try {
            return identityStore.getOrCreate().fingerprint;
        } catch (Exception e) {
            lastError = e.getMessage();
            return "unavailable";
        }
    }

    void addContact(String name, String bundle) throws Exception {
        ContactStore.Contact contact = contactStore.addFromBundle(name, bundle);
        lastError = "Trusted contact added: " + contact.name + " (" + contact.fingerprint.substring(0, 8) + ")";
        notifyListeners();
    }

    void sendMessage(String body) throws Exception {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        byte[] envelope = cryptoEngine.encryptForTrustedContacts(trimmed);
        String id = cryptoEngine.extractId(envelope);
        messageStore.append(new MessageStore.Message(id, "me", trimmed, "sent", System.currentTimeMillis()));
        bleMeshManager.broadcastEnvelope(envelope);
        lastError = "Message encrypted and queued for Bluetooth broadcast (" + envelope.length + " bytes)";
        notifyListeners();
    }

    String statusText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Identity: ").append(shortId(localFingerprint())).append('\n');
        builder.append("Mesh: ").append(isMeshRunning() ? "running" : "stopped").append('\n');
        builder.append("Bluetooth: ").append(bleMeshManager.statusText()).append('\n');
        builder.append("Trusted contacts: ").append(contactStore.list().size()).append('\n');
        if (!lastError.isEmpty()) {
            builder.append("Last event: ").append(lastError).append('\n');
        }
        return builder.toString();
    }

    String contactsText() {
        List<ContactStore.Contact> contacts = contactStore.list();
        if (contacts.isEmpty()) {
            return "No trusted contacts yet. Paste a peer identity bundle below to let them decrypt future broadcasts.";
        }
        StringBuilder builder = new StringBuilder();
        for (ContactStore.Contact contact : contacts) {
            builder.append(contact.name)
                    .append("  ")
                    .append(shortId(contact.fingerprint))
                    .append('\n');
        }
        return builder.toString();
    }

    String messagesText() {
        List<MessageStore.Message> messages = messageStore.list();
        if (messages.isEmpty()) {
            return "No messages yet.";
        }
        StringBuilder builder = new StringBuilder();
        for (MessageStore.Message message : messages) {
            builder.append(message.displayLine()).append('\n');
        }
        return builder.toString();
    }

    @Override
    public void onFrameReceived(String frameKey, int received, int total) {
        String shortKey = shortId(frameKey);
        lastError = "Radio saw encrypted BLE chunks for " + shortKey + " (" + received + "/" + total + ")";
        if (partialKeys.add(frameKey) && !messageStore.has("partial-" + frameKey)) {
            messageStore.append(new MessageStore.Message(
                    "partial-" + frameKey,
                    "nearby",
                    "Encrypted Bluetooth broadcast detected. Waiting for full envelope chunks (" + received + "/" + total + ").",
                    "radio",
                    System.currentTimeMillis()
            ));
        }
        notifyListeners();
    }

    @Override
    public void onEnvelopeReceived(byte[] envelope) {
        String id = cryptoEngine.extractId(envelope);
        boolean isNew = !messageStore.has(id);
        if (isNew) {
            CryptoEngine.Decoded decoded = cryptoEngine.decrypt(envelope);
            messageStore.append(new MessageStore.Message(
                    decoded.id,
                    decoded.senderLabel,
                    decoded.body,
                    decoded.open ? "received" : "locked",
                    decoded.timestamp
            ));
            lastError = decoded.open ? "Decrypted Bluetooth message" : "Received encrypted Bluetooth message";
            try {
                bleMeshManager.broadcastEnvelope(envelope);
            } catch (Exception e) {
                lastError = "Relay failed: " + e.getMessage();
            }
        }
        notifyListeners();
    }

    @Override
    public void onStatusChanged() {
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onRepositoryChanged();
        }
    }

    private static String shortId(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.substring(0, Math.min(8, value.length()));
    }
}
