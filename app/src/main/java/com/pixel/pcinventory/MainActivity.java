package com.pixel.pcinventory;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PCInventoryScanner";

    // ---- Simple data holder for one PC's scanned components ----
    static class PcRecord {
        String cpuSerial = "";
        String monitorSerial = "";
    }

    private enum ComponentType { CPU, MONITOR }

    // ---- Views ----
    private PreviewView previewView;
    private TextInputEditText etPcNumber;
    private TextInputEditText etSerial;
    private MaterialButton btnMinus, btnPlus, btnSave, btnGenerateShare, btnRescan;
    private MaterialButtonToggleGroup toggleComponent;
    private TextView tvStatus;
    private TextView tvSavedCount;

    // ---- Camera / ML Kit ----
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private Camera camera;
    private volatile boolean scanningPaused = false;

    // ---- App state ----
    private final Map<Integer, PcRecord> inventoryMap = new HashMap<>();
    private ComponentType currentComponent = ComponentType.CPU;
    private Vibrator vibrator;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required to scan barcodes.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupVibrator();
        setupPcSelector();
        setupComponentToggle();
        setupScanControls();
        setupSaveAndExport();

        barcodeScanner = BarcodeScanning.getClient(
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_CODE_128,
                                Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_CODE_93,
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8,
                                Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E,
                                Barcode.FORMAT_QR_CODE,
                                Barcode.FORMAT_DATA_MATRIX)
                        .build());

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        etPcNumber = findViewById(R.id.etPcNumber);
        etSerial = findViewById(R.id.etSerial);
        btnMinus = findViewById(R.id.btnMinus);
        btnPlus = findViewById(R.id.btnPlus);
        btnSave = findViewById(R.id.btnSave);
        btnGenerateShare = findViewById(R.id.btnGenerateShare);
        btnRescan = findViewById(R.id.btnRescan);
        toggleComponent = findViewById(R.id.toggleComponent);
        tvStatus = findViewById(R.id.tvStatus);
        tvSavedCount = findViewById(R.id.tvSavedCount);
    }

    private void setupVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void vibrateOnScan() {
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(60);
        }
    }

    // ===================== PC SELECTOR =====================

    private void setupPcSelector() {
        btnMinus.setOnClickListener(v -> {
            int val = getPcNumber();
            if (val > 1) setPcNumber(val - 1);
        });
        btnPlus.setOnClickListener(v -> setPcNumber(getPcNumber() + 1));
    }

    private int getPcNumber() {
        try {
            return Integer.parseInt(String.valueOf(etPcNumber.getText()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setPcNumber(int value) {
        etPcNumber.setText(String.valueOf(value));
        etPcNumber.setSelection(String.valueOf(value).length());
    }

    // ===================== COMPONENT TOGGLE =====================

    private void setupComponentToggle() {
        toggleComponent.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnCpu) {
                currentComponent = ComponentType.CPU;
            } else if (checkedId == R.id.btnMonitor) {
                currentComponent = ComponentType.MONITOR;
            }
            updateStatusText();
        });
    }

    private void updateStatusText() {
        String label = currentComponent == ComponentType.CPU ? "CPU" : "Monitor";
        tvStatus.setText("Point camera at " + label + " serial barcode");
    }

    // ===================== SCAN CONTROLS =====================

    private void setupScanControls() {
        btnRescan.setOnClickListener(v -> resumeScanning());
    }

    private void resumeScanning() {
        scanningPaused = false;
        etSerial.setText("");
        updateStatusText();
    }

    // ===================== CAMERAX SETUP =====================

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider init failed", e);
                Toast.makeText(this, "Unable to start camera.", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(@NonNull ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        // Favor a 4:3 target for tighter, sharper barcode frames; Pixel 8 handles this well.
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();

        Preview preview = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis);

        // Continuous autofocus gives the Pixel 8 fast macro-level focus lock on close barcodes.
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            // Flash left off by default; user can rely on ambient light / screen glow.
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (scanningPaused || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (barcodes.isEmpty() || scanningPaused) return;
                    for (Barcode barcode : barcodes) {
                        String raw = barcode.getRawValue();
                        if (raw != null && !raw.trim().isEmpty()) {
                            onBarcodeDetected(raw.trim());
                            break;
                        }
                    }
                })
                .addOnFailureListener(e -> Log.e(TAG, "Barcode scan failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onBarcodeDetected(String value) {
        // Ensure UI updates happen on the main thread.
        runOnUiThread(() -> {
            if (scanningPaused) return; // avoid double-trigger race
            scanningPaused = true;
            vibrateOnScan();
            etSerial.setText(value);
            etSerial.setSelection(value.length());
            String label = currentComponent == ComponentType.CPU ? "CPU" : "Monitor";
            tvStatus.setText(label + " serial captured — edit if needed, then Save");
        });
    }

    // ===================== SAVE =====================

    private void setupSaveAndExport() {
        btnSave.setOnClickListener(v -> saveCurrentSerial());
        btnGenerateShare.setOnClickListener(v -> generateAndShareFile());
    }

    private void saveCurrentSerial() {
        int pcNumber = getPcNumber();
        if (pcNumber <= 0) {
            Toast.makeText(this, "Enter a valid PC number first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String serial = String.valueOf(etSerial.getText()).trim();
        if (serial.isEmpty()) {
            Toast.makeText(this, "Scan or type a serial number first.", Toast.LENGTH_SHORT).show();
            return;
        }

        PcRecord record = inventoryMap.get(pcNumber);
        if (record == null) {
            record = new PcRecord();
            inventoryMap.put(pcNumber, record);
        }

        if (currentComponent == ComponentType.CPU) {
            record.cpuSerial = serial;
        } else {
            record.monitorSerial = serial;
        }

        Toast.makeText(this,
                "Saved " + (currentComponent == ComponentType.CPU ? "CPU" : "Monitor")
                        + " serial for PC " + pcNumber, Toast.LENGTH_SHORT).show();

        updateSavedCount();

        // Reset for the next scan; nudge the user to the other component for convenience.
        etSerial.setText("");
        if (currentComponent == ComponentType.CPU) {
            toggleComponent.check(R.id.btnMonitor);
        } else {
            toggleComponent.check(R.id.btnCpu);
        }
        resumeScanning();
    }

    private void updateSavedCount() {
        tvSavedCount.setText(inventoryMap.size() + " PC record" + (inventoryMap.size() == 1 ? "" : "s") + " saved");
    }

    // ===================== EXPORT / SHARE =====================

    private void generateAndShareFile() {
        if (inventoryMap.isEmpty()) {
            Toast.makeText(this, "No saved records yet.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File exportDir = new File(getCacheDir(), "exports");
            if (!exportDir.exists() && !exportDir.mkdirs()) {
                throw new IOException("Could not create export directory");
            }

            String fileName = "PC_Inventory_" + System.currentTimeMillis() + ".txt";
            File outFile = new File(exportDir, fileName);

            String content = buildExportContent();
            try (FileWriter writer = new FileWriter(outFile)) {
                writer.write(content);
            }

            Uri fileUri = FileProvider.getUriForFile(
                    this, getApplicationContext().getPackageName() + ".fileprovider", outFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "PC Inventory Export");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Share inventory file via"));

        } catch (IOException e) {
            Log.e(TAG, "Failed to write export file", e);
            Toast.makeText(this, "Failed to generate file.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Builds the pipe-delimited export exactly as specified:
     * PC 40 | [CPU_SERIAL_NUMBER] | [MONITOR_SERIAL_NUMBER]
     * One PC per line, sorted ascending by PC number, blank between
     * pipes for any serial that hasn't been scanned yet.
     */
    private String buildExportContent() {
        List<Integer> pcNumbers = new ArrayList<>(inventoryMap.keySet());
        Collections.sort(pcNumbers);

        StringBuilder sb = new StringBuilder();
        for (int pcNumber : pcNumbers) {
            PcRecord record = inventoryMap.get(pcNumber);
            String cpu = record != null ? record.cpuSerial : "";
            String monitor = record != null ? record.monitorSerial : "";
            sb.append(String.format(Locale.US, "PC %d | %s | %s%n", pcNumber, cpu, monitor));
        }
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }
}
