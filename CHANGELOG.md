# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add an opt-in FaceRecognitionNet pilot on iOS using Core ML and a separate template format.

### Changed

- Normalize illumination locally for new face templates and prompt for more even light if either
  side of the face is heavily clipped. Existing templates retain their original processing.

## [0.1.6] - 2026-11-25

### Changed

- Align new enrollments by eye position to normalize face position, tilt, and scale before matching.
- Use the original alignment for existing templates until the user successfully re-enrolls.

## [0.1.5] - 2026-11-25

### Added

- Opt-in `returnPhoto` registration option returns a JPEG face crop as base64 to the host app.
  The library does not save the photo; host apps control its handling and deletion.

## [0.1.4] - 2026-11-25

### Changed

- Require a frontal pose before capturing samples, including when liveness is disabled.
- Reject samples with an overwhelmingly clipped dark or bright central region and show a lighting hint.
- Require moving closer before opening and closing the mouth; guide the user when too close.
- Place capture instructions near the camera guide for easier reading.
- Allow glasses without a warning or glasses classification by default; the optional check remains
  available with `allowGlasses: false`.

### Fixed

- Apply the requested glasses sensitivity to the Android classifier.

- Use Vision's valid near-zero yaw directly instead of replacing it with an approximate landmark
  estimate, reducing inconsistent frontal-pose prompts on iOS.
- Reset capture stability when the face leaves the frame or moves out of position, and reset
  an interrupted liveness challenge even if no samples have been captured.

## [0.1.3] - 2026-11-24

### Changed

- Replaced the blink/nod step with an explicit open/close mouth challenge.
- Added immediate two-step feedback: "Open your mouth" then "Now close your mouth".

## [0.1.2] - 2026-11-24

### Fixed

- Corrected the package name in installation commands, imports and workspace tooling after the
  rename to `react-native-offline-face-recognition`.

## [0.1.1] - 2026-11-24

### Changed

- Renamed the npm package to `react-native-offline-face-recognition`.
- Added a reliable open/close mouth liveness action.
- Added `allowGlasses` and `glassesSensitivity` capture options.

## [0.1.0] - 2026-11-10

### Added

- Initial native module for React Native 0.79+ (New Architecture,
  Hermes).
- `registerFace`, `verifyFace`, `hasFaceTemplate`, `deleteFaceTemplate`, and
  `getFaceCapabilities` APIs with TypeScript types and stable error codes.
- On-device face capture and matching on iOS (Vision/AVFoundation/Keychain) and Android (CameraX/
  bundled ML Kit/Keystore).
- Active head-turn + blink liveness challenge.
- Encrypted, device-bound template storage with no network code and no image persistence.
- Best-effort on-device glasses hint with a non-blocking "remove your glasses" prompt.
- Example app, privacy manifest, security and privacy documentation, and unit tests.
