package dev.zehan.phonebridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码加设备。电脑管理页那张二维码里带 #pair=<6位码>，
 * 扫一下 = 地址 + 配对码一起到手，不用手打。
 */
public class QrScanActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "PBScan";
    private SurfaceView view;
    private TextView hint;
    private Camera camera;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile boolean done;
    private int pw = 1280;
    private int ph = 720;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("把电脑管理页上的二维码框进画面");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(20, 40, 20, 20);
        root.addView(hint, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        Button cancel = new Button(this);
        cancel.setText("手动输入");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 60;
        root.addView(cancel, lp);
        return root;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 11);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 11 && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            hint.setText("没有相机权限。可以点下面「手动输入」自己填地址和配对码。");
        }
    }

    @Override
    protected void onPause() {
        release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        release();
    }

    private void open() {
        if (camera != null || done) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.w(TAG, "open failed", e);
            hint.setText("打不开相机：" + e.getMessage() + "\n可以点下面「手动输入」。");
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            Camera.Size size = pick(p.getSupportedPreviewSizes());
            if (size != null) {
                p.setPreviewSize(size.width, size.height);
                pw = size.width;
                ph = size.height;
            }
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(view.getHolder());
            camera.startPreview();

            final byte[] buf = new byte[pw * ph * 3 / 2];
            camera.addCallbackBuffer(buf);
            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                public void onPreviewFrame(final byte[] data, final Camera cam) {
                    if (done || busy) return; // 不还 buffer，画面就先停住，省得解码排队
                    busy = true;
                    worker.execute(new Runnable() {
                        public void run() {
                            String text = decodeY(data, pw, ph);
                            if (text != null) {
                                done = true;
                                returnText(text);
                                return;
                            }
                            busy = false;
                            try {
                                cam.addCallbackBuffer(data);
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }
            });
            hint.setText("把电脑管理页上的二维码框进画面");
        } catch (Exception e) {
            Log.w(TAG, "config failed", e);
            hint.setText("相机初始化失败：" + e.getMessage());
            release();
        }
    }

    private static Camera.Size pick(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long diff = Math.abs((long) s.width * s.height - 1280L * 720L);
            if (best == null || diff < bestDiff) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    private static String decodeY(byte[] yuv, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(yuv, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result r = new MultiFormatReader().decode(bmp, hints);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }

    private void returnText(final String text) {
        ui.post(new Runnable() {
            public void run() {
                Intent out = new Intent();
                out.putExtra("text", text);
                setResult(RESULT_OK, out);
                finish();
            }
        });
    }

    private void release() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }
}
