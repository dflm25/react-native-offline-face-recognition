# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] - 2026-11-24

### Fixed

- Corrected the package name in installation commands, imports and workspace tooling after the
  rename to `react-native-offline-face-recognition`.

## [0.1.1] - 2026-11-24

### Changed

- Renamed the npm package to `react-native-offline-face-recognition`.
- Added the blink-or-nod fallback and more tolerant eye closure detection.
- Added `allowGlasses` and `glassesSensitivity` capture options.

## [0.1.0] - 2026-11-10

### Added

- Initial `react-native-offline-face` native module for React Native 0.79+ (New Architecture,
  Hermes).
- `registerFace`, `verifyFace`, `hasFaceTemplate`, `deleteFaceTemplate`, and
  `getFaceCapabilities` APIs with TypeScript types and stable error codes.
- On-device face capture and matching on iOS (Vision/AVFoundation/Keychain) and Android (CameraX/
  bundled ML Kit/Keystore).
- Active head-turn + blink liveness challenge.
- Encrypted, device-bound template storage with no network code and no image persistence.
- Best-effort on-device glasses hint with a non-blocking "remove your glasses" prompt.
- More reliable eye action: improved blink thresholds plus a head-nod fallback.
- Example app, privacy manifest, security and privacy documentation, and unit tests.
