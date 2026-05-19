package com.offline.btmesh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

final class MessageStore {
    private static final String PREFS = "messages";
    private static final String ITEMS = "items";
    private static final int MAX_MESSAGES = 250;

    private final SharedPreferences prefs;

    MessageStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean has(String id) {
        for (Message message : list()) {
            if (message.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    synchronized void append(Message message) {
        try {
            if (has(message.id)) {
                return;
            }
            JSONArray oldItems = readArray();
            JSONArray next = new JSONArray();
            next.put(message.toJson());
            int limit = Math.min(oldItems.length(), MAX_MESSAGES - 1);
            for (int i = 0; i < limit; i++) {
                next.put(oldItems.getJSONObject(i));
            }
            saveArray(next);
        } catch (Exception ignored) {
        }
    }

    synchronized List<Message> list() {
        try {
            JSONArray items = readArray();
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                messages.add(Message.fromJson(items.getJSONObject(i)));
            }
            return messages;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private JSONArray readArray() throws Exception {
        return new JSONArray(prefs.getString(ITEMS, "[]"));
    }

    private void saveArray(JSONArray array) {
        prefs.edit().putString(ITEMS, array.toString()).apply();
    }

    static final class Message {
        final String id;
        final String sender;
        final String body;
        final String state;
        final long timestamp;

        Message(String id, String sender, String body, String state, long timestamp) {
            this.id = id;
            this.sender = sender;
            this.body = body;
            this.state = state;
            this.timestamp = timestamp;
        }

        JSONObject toJson() throws Exception {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("sender", sender);
            object.put("body", body);
            object.put("state", state);
            object.put("timestamp", timestamp);
            return object;
        }

        static Message fromJson(JSONObject object) throws Exception {
            return new Message(
                    object.getString("id"),
                    object.optString("sender", "unknown"),
                    object.optString("body", ""),
                    object.optString("state", "locked"),
                    object.optLong("timestamp", System.currentTimeMillis())
            );
        }

        String displayLine() {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(timestamp));
            return "[" + time + "] " + state + " " + sender + ": " + body;
        }
    }
}
