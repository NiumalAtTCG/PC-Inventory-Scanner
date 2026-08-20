# PC Inventory Scanner — Setup Notes

## How to use these files
1. In Android Studio: **File → New → New Project → Empty Views Activity**, package name `com.pixel.pcinventory`, language **Java**, min SDK 26+.
2. Overwrite the generated files with the ones in this folder (same relative paths under `app/src/main/`).
3. Add the top-level `id 'com.android.application'` plugin block is already assumed present in your project's `settings.gradle` / root `build.gradle` — this is just the **module-level** `app/build.gradle`.
4. Sync Gradle. The first ML Kit barcode-scanning call will trigger a one-time download of the on-device detection model via Google Play services — test on a device with Play Services (a real Pixel 8, not a bare emulator image).
5. Run on your Pixel 8 (API 34). Grant the camera permission when prompted.

## How it works
- **CameraX**: `Preview` feeds the `PreviewView` (top half of screen); `ImageAnalysis` runs on a background executor and hands each frame to ML Kit. `ResolutionSelector` targets 4:3 for a tighter frame around barcodes, which helps close-range/macro scans on the Pixel 8's sensor.
- **ML Kit**: `BarcodeScanner` is configured for CODE_128, CODE_39, CODE_93, EAN, UPC, QR, and DATA_MATRIX — covering essentially every serial-number barcode style you'll encounter on CPU/monitor asset tags.
- **Scan lock**: once a barcode is detected, `scanningPaused` freezes further detection so the camera doesn't keep overwriting the field while you're reviewing/editing it. Tap **Rescan**, or **Save**, to resume.
- **Data model**: `HashMap<Integer, PcRecord>` keyed by PC number; each `PcRecord` holds `cpuSerial` and `monitorSerial`. Saving just updates whichever field matches the current toggle state.
- **Export**: writes a plain-text file to `cacheDir/exports/`, sorted ascending by PC number, one line per PC in the exact `PC 40 | CPU | MONITOR` format (blank segment if unscanned), then shares it via `FileProvider` + `ACTION_SEND` so the user can pick Drive, Gmail, Nearby Share, etc.

## Things you'll likely want to adjust
- **Starting PC number / increment step**: currently starts at 40 in the layout (`android:text="40"` on `etPcNumber`) — change to whatever your default batch starts at.
- **In-session persistence**: the `HashMap` is in-memory only and resets if the app process dies. If you need it to survive rotation/backgrounding, persist it to `SharedPreferences` (as JSON) or a small Room database — happy to add that if you want it.
- **Duplicate-serial protection**: not currently implemented; if two PCs get scanned with the same CPU serial, both are saved as-is.
- **App icon**: the manifest references `@mipmap/ic_launcher` / `ic_launcher_round` — Android Studio's New Project wizard generates placeholders for these automatically; add your own if you replaced them.
