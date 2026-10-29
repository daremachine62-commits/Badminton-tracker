# Badminton Tracker

Native Android app prototype for live-camera badminton sessions, manual
timestamped hit labels, and experimental video motion analysis.

## Current prototype

- Configure four player names.
- Preview the back camera and save a match video under
  `Movies/BadmintonTracker`.
- Manually tag a hit with the selected player and elapsed session time.
- Save completed live sessions locally and review recent durations and tagged
  hit totals. These activity totals do not infer skill improvement.
- Analyze an imported local video or a direct HTTPS video-file URL by sampling
  frames and highlighting coarse frame-to-frame motion candidates.
- Automatically estimate court corners from bright court-line candidates in
  uploaded videos and direct HTTPS video files; adjust the estimate or mark
  the corners manually when detection fails.
- Show best-effort live court-line estimates with tap-to-adjust controls and a
  coarse frame-change score during a camera session.

Court detection uses a lightweight bright-line geometry heuristic, not a
trained court model. Background lines can cause false estimates, so check and
adjust the highlighted corners. When some court edges are outside the frame,
the estimate extrapolates visible lines and assumes standard 13.4 m by 6.1 m
doubles-court dimensions; dimensions provide scale, not evidence that an
invisible corner was detected. Motion candidates can be caused by players,
background movement, or camera shake; calibrated speed is a motion-cluster
estimate, not shuttle speed. The app does not automatically identify hits,
track players, or calculate missed-shot distance. YouTube page URLs and other
webpage URLs are not direct video files; import a video file you are authorized
to analyze or provide a direct HTTPS link to a supported video file.

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
grayscale frames, and reports generic motion candidates. Court estimation
searches early video frames for bright line geometry and may need manual
correction. It assumes a standard 13.4 m by 6.1 m doubles court. Results are
experimental and are not validated badminton measurements. Accurate
court/shuttle/racket/player detection requires a separately selected and
validated model and representative test footage.

Single-camera measurements are estimates. Additional synchronized views can
improve position and speed estimates, but require a separate calibration and
device synchronization workflow.
