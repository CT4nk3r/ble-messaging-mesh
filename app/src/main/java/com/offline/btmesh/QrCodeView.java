package com.offline.btmesh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

final class QrCodeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BitMatrix matrix;
    private String errorText;

    QrCodeView(Context context) {
        super(context);
        setBackgroundColor(Color.WHITE);
    }

    void setContent(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, 2);
            matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints);
            errorText = null;
        } catch (Throwable t) {
            matrix = null;
            errorText = "Could not render QR: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        int size = Math.max(240, available);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
        if (matrix == null) {
            paint.setColor(Color.rgb(80, 40, 40));
            paint.setTextSize(32f);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(errorText == null ? "QR unavailable" : errorText, getWidth() / 2f, getHeight() / 2f, paint);
            return;
        }
        int qrWidth = matrix.getWidth();
        int qrHeight = matrix.getHeight();
        float scale = Math.min(getWidth() / (float) qrWidth, getHeight() / (float) qrHeight);
        float left = (getWidth() - qrWidth * scale) / 2f;
        float top = (getHeight() - qrHeight * scale) / 2f;
        paint.setColor(Color.BLACK);
        for (int y = 0; y < qrHeight; y++) {
            for (int x = 0; x < qrWidth; x++) {
                if (matrix.get(x, y)) {
                    canvas.drawRect(
                            left + x * scale,
                            top + y * scale,
                            left + (x + 1) * scale,
                            top + (y + 1) * scale,
                            paint
                    );
                }
            }
        }
    }
}
