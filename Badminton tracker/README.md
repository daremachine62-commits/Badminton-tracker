# Badminton Tracker

Native Android app prototype for live-camera badminton sessions, manual
timestamped hit labels, and experimental video motion analysis.

## Current prototype

- Configure four player names.
- Preview the back camera and save a match video under
  `Movies/BadmintonTracker`.
- Manually tag a hit with the selected player and elapsed session time.
- Analyze an imported local video or a direct HTTPS video-file URL by sampling
  frames and highlighting coarse frame-to-frame motion candidates.
- Mark the four court corners in an analyzed frame for an approximate
  perspective transform and motion-cluster speed estimate.
- Show a coarse live camera frame-change score during a camera session.

This prototype does **not** include a trained shuttle, racket, or player
detection model. Motion candidates can be caused by players, background
movement, or camera shake; the calibrated speed is a motion-cluster estimate,
not shuttle speed. It does not automatically identify hits, track players, or
calculate missed-shot distance. YouTube page URLs and other webpage URLs are
not direct video files; import a video file you are authorized to analyze or
provide a direct HTTPS link to a supported video file.

## Build

The project uses JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, and Android
SDK 35, with Android 10 or newer as its minimum. Install Android SDK Platform
35 and Build-Tools 35.0.0, then build from this directory:

```powershell
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Android
Studio can also open this directory and sync using the Gradle wrapper.

## Analysis limitations

The current analysis samples up to 300 frames from a video, compares downscaled
grayscale frames, and reports generic motion candidates. Court calibration
assumes a standard 13.4 m by 6.1 m doubles court and four manually marked
corners. Results are experimental and are not validated badminton measurements.
Accurate shuttle/racket/player detection requires a separately selected and
validated model and representative test footage.

Single-camera measurements are estimates. Additional synchronized views can
improve position and speed estimates, but require a separate calibration and
device synchronization workflow.
