# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
- Example app, privacy manifest, security and privacy documentation, and unit tests.
