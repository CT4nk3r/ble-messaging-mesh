package com.offline.btmesh;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class QrDisplayActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            MeshRepository repository = MeshRepository.get(this);
            String payload = repository.exportIdentityQrPayload();

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(dp(18), dp(18), dp(18), dp(18));
            root.setBackgroundColor(Color.rgb(247, 250, 248));

            TextView title = text("Show this QR to your contact", 22, Color.rgb(22, 52, 44));
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(title);

            QrCodeView qrCodeView = new QrCodeView(this);
            qrCodeView.setContent(payload);
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
            );
            qrParams.setMargins(0, dp(14), 0, dp(14));
            root.addView(qrCodeView, qrParams);

            TextView fingerprint = text("Fingerprint: " + groupedFingerprint(repository.localFingerprint()), 15, Color.rgb(26, 36, 33));
            fingerprint.setGravity(Gravity.CENTER_HORIZONTAL);
            fingerprint.setTextIsSelectable(true);
            root.addView(fingerprint);

            TextView fallback = text("If scanning fails, use the identity text on the main screen.", 13, Color.rgb(80, 92, 88));
            fallback.setGravity(Gravity.CENTER_HORIZONTAL);
            root.addView(fallback);

            Button close = new Button(this);
            close.setText("Close");
            close.setAllCaps(false);
            close.setOnClickListener(v -> finish());
            root.addView(close);

            setContentView(root);
        } catch (Throwable t) {
            TextView error = text("Could not show QR: " + safeMessage(t), 16, Color.rgb(90, 40, 40));
            error.setGravity(Gravity.CENTER);
            error.setPadding(dp(20), dp(20), dp(20), dp(20));
            setContentView(error);
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setPadding(0, dp(4), 0, dp(4));
        return text;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
