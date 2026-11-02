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

## Project Structure

- `MainActivity.java`: Main menu providing options to **Capture** (`CaptureActivity`) or **View** (`ViewerActivity`).
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
