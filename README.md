# StereoPano (Android Stereo Panorama & VR Viewer)

StereoPano is a native Android application designed for capturing and viewing stereoscopic 3D panoramas and VR panoramic photos (`.vr.jpg`).

## Key Features

- **Instant Camera Capture (`CaptureActivity`)**:
  - The app launches directly into the camera capture screen.
  - Capture 360° panoramic shots with gyroscope tracking and real-time stitching (`StereoStitcher`, `YawTracker`).
  - Unified round action buttons with active/inactive state highlights, short-tap notifications, and long-press accessibility tooltips.
  - Centered shutter button with visual states (red circle for start, red square for stop/save).
  - Bottom panel hide/restore gesture (swiping down hides panel, tapping the arrow-up button restores it).
- **VR & Panorama Viewer (`ViewerActivity`)**:
  - High-performance OpenGL ES 2.0 spherical renderer (`PanoRenderer`).
  - Dynamic round buttons for viewing modes: 2D, Cardboard VR, and SBS Glasses.
  - Contextual buttons: Lens correction (Cardboard mode), Half-SBS toggle (SBS mode), Gyroscope, Compass orientation tracking, and View Recenter.
  - Smooth back navigation that returns to the gallery or caller without duplicating screens in the activity stack.
- **Gallery & Exporter (`GalleryActivity` / `Exporter`)**:
  - Manage and view saved panoramas.
  - Export panoramas to flat 2D JPEG, 3D Side-by-Side (SBS), or Anaglyph 3D (Red/Cyan).
  - Direct sharing via system share sheet.
- **Multilingual Support (i18n)**:
  - English (default), German (`values-de`), and Russian (`values-ru`) localizations.
  - Automatically matches the device's system language, falling back to English.

## Requirements

- Android SDK 24+ (Android 7.0+)
- Camera hardware and Gyroscope sensor
- OpenGL ES 2.0 support

## License

This project is open source and available under the MIT License.
