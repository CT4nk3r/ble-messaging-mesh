package com.offline.btmesh;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements MeshRepository.Listener {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_SCAN_QR = 101;

    private MeshRepository repository;
    private TextView statusText;
    private TextView identityText;
    private TextView contactsText;
    private TextView messagesText;
    private EditText messageInput;
    private EditText contactNameInput;
    private EditText contactBundleInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = MeshRepository.get(this);
        repository.addListener(this);
        buildUi();
        refresh();
        requestMeshPermissions();
    }

    @Override
    protected void onDestroy() {
        repository.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN_QR) {
            if (resultCode == RESULT_OK && data != null) {
                String content = data.getStringExtra(QrScanActivity.EXTRA_QR_CONTENT);
                if (content != null && !content.trim().isEmpty()) {
                    confirmScannedContact(content);
                } else {
                    toast("QR scan did not return contact data");
                }
            } else {
                toast("QR scan cancelled");
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRepositoryChanged() {
        runOnUiThread(this::refresh);
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 250, 248));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Bluetooth Mesh Messenger", 24, Color.rgb(22, 52, 44));
        title.setGravity(Gravity.START);
        root.addView(title);

        statusText = panelText(14);
        root.addView(section("Status", statusText));

        LinearLayout controls = row();
        Button permissionButton = button("Permissions");
        permissionButton.setOnClickListener(v -> requestMeshPermissions());
        Button startButton = button("Start mesh");
        startButton.setOnClickListener(v -> startMeshService());
        Button stopButton = button("Stop");
        stopButton.setOnClickListener(v -> stopMeshService());
        controls.addView(permissionButton);
        controls.addView(startButton);
        controls.addView(stopButton);
        root.addView(controls);

        identityText = panelText(12);
        identityText.setTextIsSelectable(true);
        root.addView(section("Your offline identity bundle", identityText));

        LinearLayout qrControls = row();
        Button showQrButton = button("Show my QR");
        showQrButton.setOnClickListener(v -> showIdentityQr());
        Button scanQrButton = button("Scan contact QR");
        scanQrButton.setOnClickListener(v -> scanContactQr());
        qrControls.addView(showQrButton);
        qrControls.addView(scanQrButton);
        root.addView(qrControls);

        messageInput = edit("Message to broadcast", 3);
        root.addView(messageInput);
        Button sendButton = button("Encrypt and broadcast");
        sendButton.setOnClickListener(v -> sendMessage());
        root.addView(sendButton);

        contactsText = panelText(14);
        root.addView(section("Trusted contacts", contactsText));

        contactNameInput = edit("Contact name", 1);
        root.addView(contactNameInput);
        contactBundleInput = edit("Paste contact identity bundle JSON", 5);
        contactBundleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(contactBundleInput);
        Button addContactButton = button("Trust contact");
        addContactButton.setOnClickListener(v -> addContact());
        root.addView(addContactButton);

        messagesText = panelText(14);
        messagesText.setTextIsSelectable(true);
        root.addView(section("Messages", messagesText));

        setContentView(scrollView);
    }

    private void refresh() {
        if (statusText == null) {
            return;
        }
        statusText.setText(repository.statusText());
        identityText.setText(repository.exportIdentity());
        contactsText.setText(repository.contactsText());
        messagesText.setText(repository.messagesText());
    }

    private void startMeshService() {
        if (!hasAllMeshPermissions()) {
            requestMeshPermissions();
            toast("Grant Bluetooth permissions, then start mesh again.");
            return;
        }
        Intent intent = new Intent(this, MeshForegroundService.class);
        intent.setAction(MeshForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        repository.startMesh();
        refresh();
    }

    private void stopMeshService() {
        Intent intent = new Intent(this, MeshForegroundService.class);
        intent.setAction(MeshForegroundService.ACTION_STOP);
        startService(intent);
        repository.stopMesh();
        refresh();
    }

    private void sendMessage() {
        try {
            repository.sendMessage(messageInput.getText().toString());
            messageInput.setText("");
            refresh();
        } catch (Exception e) {
            toast(e.getMessage() == null ? "Could not send message" : e.getMessage());
        }
    }

    private void addContact() {
        try {
            repository.addContact(
                    contactNameInput.getText().toString(),
                    contactBundleInput.getText().toString()
            );
            contactNameInput.setText("");
            contactBundleInput.setText("");
            refresh();
        } catch (Exception e) {
            toast("Invalid contact bundle");
        }
    }

    private void showIdentityQr() {
        try {
            startActivity(new Intent(this, QrDisplayActivity.class));
        } catch (RuntimeException e) {
            toast("Could not open QR screen: " + safeMessage(e));
        }
    }

    private void scanContactQr() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
            toast("Grant camera permission, then scan again.");
            return;
        }
        try {
            startActivityForResult(new Intent(this, QrScanActivity.class), REQ_SCAN_QR);
        } catch (ActivityNotFoundException e) {
            toast("QR scanner activity is missing from this build");
        } catch (RuntimeException e) {
            toast("Could not start camera scanner: " + safeMessage(e));
        }
    }

    private void confirmScannedContact(String bundle) {
        try {
            String fingerprint = ContactPreview.fingerprint(bundle);
            EditText nameInput = edit("Contact name", 1);
            nameInput.setText(ContactPreview.name(bundle));
            TextView fingerprintText = panelText(14);
            fingerprintText.setText("Fingerprint:\n" + groupedFingerprint(fingerprint));

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(dp(12), dp(8), dp(12), dp(4));
            layout.addView(fingerprintText);
            layout.addView(nameInput);

            new AlertDialog.Builder(this)
                    .setTitle("Trust this QR contact?")
                    .setView(layout)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Trust contact", (dialog, which) -> {
                        try {
                            repository.addContact(nameInput.getText().toString(), bundle);
                            refresh();
                            toast("Trusted contact added");
                        } catch (Exception e) {
                            toast("Invalid contact QR");
                        }
                    })
                    .show();
        } catch (Exception e) {
            toast("That QR is not a valid contact bundle");
        }
    }

    private void requestMeshPermissions() {
        List<String> missing = missingMeshPermissions();
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private boolean hasAllMeshPermissions() {
        return missingMeshPermissions().isEmpty();
    }

    private List<String> missingMeshPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addIfMissing(permissions, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(permissions, Manifest.permission.BLUETOOTH_ADVERTISE);
            addIfMissing(permissions, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(permissions, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(permissions, Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions;
    }

    private void addIfMissing(List<String> permissions, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(permission);
        }
    }

    private LinearLayout section(String title, TextView content) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(16), 0, dp(8));
        TextView label = text(title, 16, Color.rgb(24, 67, 56));
        layout.addView(label);
        layout.addView(content);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setPadding(0, dp(6), 0, dp(6));
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setPadding(0, dp(4), 0, dp(4));
        return text;
    }

    private TextView panelText(int sp) {
        TextView text = text("", sp, Color.rgb(34, 45, 42));
        text.setPadding(dp(12), dp(10), dp(12), dp(10));
        text.setBackgroundColor(Color.WHITE);
        return text;
    }

    private EditText edit(String hint, int minLines) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setMinLines(minLines);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setSingleLine(false);
        editText.setTextColor(Color.rgb(26, 36, 33));
        editText.setHintTextColor(Color.rgb(111, 128, 123));
        editText.setBackgroundColor(Color.WHITE);
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return editText;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private String safeMessage(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String groupedFingerprint(String fingerprint) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fingerprint.length(); i += 4) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(fingerprint, i, Math.min(i + 4, fingerprint.length()));
        }
        return builder.toString();
    }
}
