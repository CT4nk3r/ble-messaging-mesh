package com.offline.btmesh;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public final class QrScanActivity extends Activity {
    static final String EXTRA_QR_CONTENT = "qr_content";

    private TextureView textureView;
    private TextView statusText;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Size previewSize;
    private final MultiFormatReader qrReader = new MultiFormatReader();
    private volatile boolean decoding;
    private volatile boolean completed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        qrReader.setHints(hints);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView guide = new TextView(this);
        guide.setText("Point camera at contact QR");
        guide.setTextColor(Color.WHITE);
        guide.setTextSize(18);
        guide.setGravity(Gravity.CENTER);
        guide.setBackgroundColor(Color.argb(130, 0, 0, 0));
        FrameLayout.LayoutParams guideParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.TOP
        );
        root.addView(guide, guideParams);

        statusText = new TextView(this);
        statusText.setText("Opening camera...");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        statusText.setBackgroundColor(Color.argb(150, 0, 0, 0));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48),
                Gravity.BOTTOM
        );
        root.addView(statusText, statusParams);

        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        closeParams.setMargins(0, dp(8), dp(8), 0);
        root.addView(close, closeParams);

        setContentView(root);
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private void startCameraThread() {
        cameraThread = new HandlerThread("qr-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void openCamera() {
        if (completed) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setStatus("Camera permission is missing");
            return;
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null) {
                setStatus("Camera service unavailable");
                return;
            }
            String cameraId = findBackCamera(manager);
            if (cameraId == null) {
                setStatus("No back camera found");
                return;
            }
            previewSize = choosePreviewSize(manager, cameraId);
            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(),
                    previewSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            manager.openCamera(cameraId, stateCallback, cameraHandler);
            setStatus("Opening camera...");
        } catch (SecurityException e) {
            setStatus("Camera permission denied");
        } catch (Throwable t) {
            setStatus("Could not open camera: " + safeMessage(t));
        }
    }

    private String findBackCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) {
                fallback = id;
            }
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return fallback;
    }

    private Size choosePreviewSize(CameraManager manager, String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        if (map != null) {
            for (Size size : map.getOutputSizes(ImageFormat.YUV_420_888)) {
                int pixels = size.getWidth() * size.getHeight();
                if (pixels <= 1280 * 720 && (best == null || pixels > best.getWidth() * best.getHeight())) {
                    best = size;
                }
            }
        }
        return best == null ? new Size(640, 480) : best;
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            setStatus("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            setStatus("Camera error: " + error);
        }
    };

    private void createPreviewSession() {
        try {
            if (cameraDevice == null || imageReader == null || textureView.getSurfaceTexture() == null) {
                return;
            }
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface decodeSurface = imageReader.getSurface();

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.addTarget(decodeSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, decodeSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                        setStatus("Scanning for contact QR...");
                    } catch (Throwable t) {
                        setStatus("Could not start preview: " + safeMessage(t));
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    setStatus("Camera preview failed");
                }
            }, cameraHandler);
        } catch (Throwable t) {
            setStatus("Could not create camera preview: " + safeMessage(t));
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (decoding || completed) {
            Image skipped = reader.acquireLatestImage();
            if (skipped != null) {
                skipped.close();
            }
            return;
        }
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        decoding = true;
        try {
            String qr = decodeImage(image);
            if (qr != null) {
                completed = true;
                Intent result = new Intent();
                result.putExtra(EXTRA_QR_CONTENT, qr);
                setResult(RESULT_OK, result);
                runOnUiThread(this::finish);
            }
        } finally {
            image.close();
            decoding = false;
        }
    }

    private String decodeImage(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int rowStride = plane.getRowStride();
            byte[] data = new byte[width * height];
            for (int y = 0; y < height; y++) {
                int sourceOffset = y * rowStride;
                buffer.position(sourceOffset);
                buffer.get(data, y * width, width);
            }
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
            );
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = qrReader.decodeWithState(bitmap);
            return result == null ? null : result.getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Throwable t) {
            setStatus("Decode error: " + safeMessage(t));
            return null;
        } finally {
            qrReader.reset();
        }
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private void setStatus(String value) {
        runOnUiThread(() -> statusText.setText(value));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }
}
