# StereoPano (Android Stereo Panorama & VR Viewer)

StereoPano is an Android application designed for capturing and viewing stereoscopic 3D panoramas and VR panoramic photos (`.vr.jpg`).

## Features

- **Stereo Panorama Capture (`CaptureActivity`)**:
  - Capture 360-degree panoramic shots with gyroscope tracking and real-time stitching (`StereoStitcher`, `YawTracker`).
  - Dual-lens/stereoscopic 3D support for immersive VR experiences.
- **VR & Panorama Viewer (`ViewerActivity`)**:
  - High-performance OpenGL ES panorama renderer (`PanoRenderer`).
  - Supports opening `.vr.jpg` files directly from file managers or gallery apps via Android `VIEW` intents.
  - Interactive touch look-around and VR headset mode.
- **Encrypted/Structured VR Metadata (`VrJpgReader` / `VrJpgWriter`)**:
  - Seamlessly embed and read VR stereoscopic projection metadata in standard JPEG files.

- **Settings (`SettingsActivity`, gear icon)**:
  - Gear button on the main screen, camera screen, gallery and viewer. Capture parameters (stereo angle, averaging window, pacing, resolution, JPEG quality, sensor latency, focal correction), viewer options (compass, gyro, lens correction, Half-SBS, SBS field of view) and a reset button. Values are stored in `SharedPreferences` (`Settings.java`).

- **UI conventions**:
  - All buttons are round, icon-only buttons (`Ui.runderKnopf`); toggles are highlighted blue when on. Long-press shows a tooltip.
  - Strings live in `res/values/strings.xml` (English default) with German (`values-de`) and Russian (`values-ru`) translations; the system language is used automatically.

## Project Structure

- The app starts directly in `CaptureActivity` (camera). The gallery opens via the thumbnail at the bottom left; settings via the gear.
- `CaptureActivity.java`: Handles camera capture, sensor tracking, and stitching workflow.
- `ViewerActivity.java`: Interactive OpenGL ES viewer for stereoscopic panoramas.
- `StereoStitcher.java`: Core stitching algorithm for panorama creation.
- `PanoRenderer.java`: OpenGL ES 2.0+ shader rendering engine for 360° spherical projection.
- `YawTracker.java`: Gyroscope and orientation sensor tracking.
- `VrJpgReader.java` / `VrJpgWriter.java`: Metadata encoding/decoding for stereo VR JPEGs.

## Requirements

- Android SDK 24+ (Android 7.0+)
- Camera hardware and Gyroscope sensor
- OpenGL ES 2.0 support

## License

This project is open source and available under the MIT License.
