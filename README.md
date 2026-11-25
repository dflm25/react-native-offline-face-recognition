# react-native-offline-face-recognition

[![npm version](https://img.shields.io/npm/v/react-native-offline-face-recognition.svg)](https://www.npmjs.com/package/react-native-offline-face-recognition)
[![license](https://img.shields.io/npm/l/react-native-offline-face-recognition.svg)](LICENSE)

Privacy-first, fully on-device face enrollment and verification for React Native clock-in and
clock-out flows. Built for the New Architecture and Hermes, with native iOS and Android capture
screens.

- **Offline by design.** No images, embeddings, templates, or scores leave the device. The library
  itself does not request network permission.
- **Local template only.** One face template is encrypted at rest with Keychain (iOS) or
  Android Keystore-backed AES-256-GCM storage.
- **Active liveness challenge.** The user must look straight, turn their head to both sides, then
  **open and close their mouth**. The mouth action is easier to detect than a fast blink, especially
  with glasses or in low light. Static photos and naive replay clips do not complete the sequence.
- **Simple promise API.** Five functions, stable error codes, no camera component to wire up.
- **Glasses hint.** On-device image classification detects eyeglasses/sunglasses and asks the user
  to remove them for a cleaner template. It is a non-blocking, best-effort hint.
- **No third-party SaaS.** ML Kit face detection is bundled in the Android binary and Apple Vision
  is used on iOS, so detection also works without downloads.

> **Important:** this package is a privacy-preserving attendance aid, not a certified biometric
> system. It uses a compact geometric/texture template instead of a deep face-recognition model.
> Accuracy is lower than Face ID or a trained embedding model, and the liveness check is not
> certified presentation-attack detection (ISO/IEC 30107-3). Read [SECURITY.md](SECURITY.md)
> before using it for high-value access control.

## Requirements

| Platform | Requirement |
| --- | --- |
| React Native | `>= 0.79` with the New Architecture enabled |
| JS engine | Hermes (tested); JSC is not the supported target |
| iOS | Deployment target inherited from React Native (`min_ios_version_supported`, currently 15.1+) |
| Android | `minSdkVersion 24`+, CameraX, bundled ML Kit face detection |
| Device | Front-facing camera; a physical device for real testing |
| Platforms | iOS and Android only. There is no web implementation. |

## Installation

```sh
npm install react-native-offline-face-recognition
# or
yarn add react-native-offline-face-recognition
```

### iOS

```sh
cd ios && pod install
```

Add a camera usage description to the **host app** `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>Use the front camera to enroll and verify your face for clock-in and clock-out. Images are processed on this device and are never uploaded.</string>
```

The pod includes its own privacy manifest. On iOS 15.1+, no additional permission is required.

Expo Go is not supported because this is a native module. Use an Expo development build or a bare
React Native app.

### Android

The library manifest contributes the `CAMERA` permission and a non-exported capture activity. The
host app still needs to be built normally; the module requests camera permission when the capture
screen opens. No network permission is added by this package. ML Kit face detection is bundled in
the AAR, so there is no runtime model download and no Google Play Services dependency; expect an
increase of a few MB in the APK/AAB.

Rebuild the native app after installing (Metro reload is not enough for a new native module):

```sh
npm run android
npm run ios
```

## Usage

```tsx
import {
  registerFace,
  verifyFace,
  hasFaceTemplate,
  deleteFaceTemplate,
  getFaceCapabilities,
} from 'react-native-offline-face-recognition';

const capabilities = await getFaceCapabilities();
if (!capabilities.available) {
  throw new Error('Face capture is unavailable on this device');
}

// One-time enrollment. Replaces any existing template on success.
const enrollment = await registerFace({
  timeout: 45_000,
  requireLiveness: true,
  threshold: 0.68,
});
if (!enrollment.success) {
  console.warn(enrollment.error);
}

// Clock-in / clock-out.
const verification = await verifyFace({
  requireLiveness: true,
  threshold: 0.68,
});
if (verification.success) {
  // Local decision only: enqueue your own clock-in event.
  console.log('match score', verification.score, 'liveness', verification.livenessPassed);
}

// Template lifecycle.
const enrolled = await hasFaceTemplate();
await deleteFaceTemplate();
```

### API

```ts
type FaceOptions = {
  /** Capture window in ms. 5,000–120,000. Default: 45,000. */
  timeout?: number;
  /** Require the active challenge. Default: true. */
  requireLiveness?: boolean;
  /** Similarity threshold in [0, 1]. Default: 0.68. */
  threshold?: number;
  /** Show a hint when glasses are detected. Default: true. */
  allowGlasses?: boolean;
  /** Glasses detector sensitivity: 'low' | 'medium' | 'high'. Default: 'medium'. */
  glassesSensitivity?: 'low' | 'medium' | 'high';
};

type FaceRegistrationResult = {
  success: boolean;
  templateId?: string;
  error?: string;
};

type FaceVerificationResult = {
  success: boolean;
  score?: number;
  livenessPassed?: boolean;
  error?: string;
};

type FaceCapabilities = {
  available: boolean;
  platform: 'ios' | 'android';
  supportsLiveness: boolean;
};

function registerFace(options?: FaceOptions): Promise<FaceRegistrationResult>;
function verifyFace(options?: FaceOptions): Promise<FaceVerificationResult>;
function hasFaceTemplate(): Promise<boolean>;
function deleteFaceTemplate(): Promise<void>;
function getFaceCapabilities(): Promise<FaceCapabilities>;
```

`registerFace` and `verifyFace` always present the native camera UI. `hasFaceTemplate`,
`deleteFaceTemplate`, and `getFaceCapabilities` never open the camera or prompt for permission.

### Glasses options

- `allowGlasses: true` (default) shows a non-blocking "remove your glasses" hint.
- `allowGlasses: false` pauses the liveness challenge while glasses are detected and resumes after
  they are removed. If the detector has a false positive, the user can cancel and retry.
- `glassesSensitivity` tunes the detector: `low` is stricter (fewer prompts), `high` catches more
  glasses (more prompts). It is a best-effort classifier, not a hard medical/accessory test.

```ts
await registerFace({
  allowGlasses: false,
  glassesSensitivity: 'high',
});
```

#### Error codes

| Code | Meaning |
| --- | --- |
| `invalid_options` | Invalid timeout, threshold, or liveness option. |
| `camera_unavailable` | No usable front camera (for example, a simulator). |
| `camera_permission_denied` | The user denied camera access. |
| `already_in_progress` | Another capture UI is already open. |
| `cancelled` | The user cancelled the capture. |
| `timeout` | Capture did not finish within `timeout`. |
| `no_face` / `multiple_faces` | No single face was available. |
| `face_too_small` | The face was too far from the camera. |
| `liveness_failed` | The active challenge did not complete. |
| `face_mismatch` | The score was below `threshold`. |
| `no_template` | No face has been enrolled on this device. |
| `storage_error` | The encrypted template could not be read or written. |
| `not_supported` | Unsupported platform or build. |
| `unknown_error` | Unexpected native failure. |

Native methods are wrapped so rejected promises become `{ success: false, error }` results for the
two capture functions. `hasFaceTemplate` and `deleteFaceTemplate` may reject with an `Error` only
for an unrecoverable bridge failure.

## How it works

1. The native module opens a full-screen front-camera activity/view controller.
2. Apple Vision (iOS) or bundled ML Kit (Android) detects a single face, its landmarks, and head
   pose. No image is written to disk.
3. While the face is centered, a lightweight on-device image classifier checks the face crop for
   eyeglasses/sunglasses and shows a "remove your glasses" hint. With `allowGlasses: false` the
   challenge pauses until the glasses are removed; otherwise the hint is non-blocking.
4. The user completes an active challenge: center, turn one way, return, turn the other way,
   then open and close the mouth. This is easier to detect than a blink and gives immediate visual
   feedback ("Open your mouth" / "Now close your mouth"). A static photo or a simple looping clip
   fails the coherent sequence.
5. On success, six frames are captured. Each face is aligned, converted to a 64×64 masked and
   equalized grayscale patch, and reduced to a 1,520-value LBP/HOG histogram descriptor.
6. Enrollment stores six descriptors in encrypted local storage. Verification compares six new
   descriptors against the enrolled set using block-wise histogram intersection.
7. Only a boolean, score, and template ID cross the native bridge. Frames are released immediately.

There is no HTTP client in the native module, no analytics, no cloud SDK, and no upload code path.
The iOS privacy manifest declares no collected data types and no tracking.

## Matching and thresholds

The score is a histogram-intersection similarity in `[0, 1]`:

- `1.0` identical descriptors
- `>= 0.68` default acceptance
- `< 0.68` mismatch

The default was chosen for conservative clock-in use. Tune it against your own users, lighting and
devices:

- **False accepts** (wrong person accepted): raise `threshold` (for example `0.70`).
- **False rejects** (right person denied): lower `threshold` (for example `0.55`) and improve
  lighting.

Do not tune against a single person. Evaluate at least 20–30 users and multiple sessions, and
include glasses, facial hair, and different lighting. There is no cross-device template portability:
templates are stored locally and are bound to that installation/device.

## Privacy and compliance

- Enrollment data is biometric data under GDPR, BIPA, and similar laws. Obtain informed consent,
  publish a retention policy, and provide deletion (for example call `deleteFaceTemplate` when an
  employee leaves).
- The template is local. If your clock-in flow needs an audit trail, send only an event with a
  timestamp and a non-reversible local template ID — never send images, descriptors, or scores
  that can be replayed.
- `deleteFaceTemplate` removes the Keychain item / encrypted file and the Android Keystore key.
- iOS Keychain items can survive app reinstallation; call `deleteFaceTemplate` during account
  cleanup.
- Android stores the encrypted file in `noBackupFilesDir`; it is excluded from auto-backup.
- The Android capture screen sets `FLAG_SECURE` to block screenshots and screen recording.

See [PRIVACY.md](PRIVACY.md) for a data-flow summary.

## Security limitations

This library is designed to deter casual photo, video, and screen replay. It is **not** designed
to stop:

- a custom 3D mask, deepfake, or a replay timed precisely to the challenge,
- an attacker with control of the device OS, camera injection, or a rooted/jailbroken device,
- identical twins or very close lookalikes,
- a determined attacker who can replace the enrolled template.

The glasses hint is intentionally best-effort: it can miss clear frames or produce a false positive
depending on lighting, reflections and glasses style. Ask the user to remove glasses when prompted,
but do not make it a hard gate.

For high-value access control, combine it with a second factor (device biometrics, badge, PIN),
device attestation, rate limiting, and liveness reviewed by a specialized vendor. See
[SECURITY.md](SECURITY.md).

## Development

```sh
yarn
yarn prepare
yarn typecheck
yarn test
yarn lint

yarn example ios
yarn example android
```

The example app registers and verifies a face, shows capabilities, and deletes the local template.

## License

MIT © Daniel Lucumi
