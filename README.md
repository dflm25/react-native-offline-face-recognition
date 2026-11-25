# react-native-offline-face-recognition

[![npm version](https://img.shields.io/npm/v/react-native-offline-face-recognition.svg)](https://www.npmjs.com/package/react-native-offline-face-recognition)
[![license](https://img.shields.io/npm/l/react-native-offline-face-recognition.svg)](LICENSE)

Privacy-first, fully on-device face enrollment and verification for React Native clock-in and
clock-out flows. Built for the New Architecture and Hermes, with native iOS and Android capture
screens.

- **Offline by design.** The library processes data on the device and does not upload it. The host
  app can explicitly request a registration photo and is responsible for its handling. The library
  itself does not request network permission.
- **Local template only.** One face template is encrypted at rest with Keychain (iOS) or
  Android Keystore-backed AES-256-GCM storage.
- **Basic movement challenge.** The user moves closer, looks straight and opens and closes their mouth. This is
  not certified liveness detection and a photograph or video may still be used in an attack.
- **Simple promise API.** Five functions, stable error codes, no camera component to wire up.
- **Optional glasses check.** Glasses are allowed by default. An optional on-device classifier can
  pause capture when it detects glasses.
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
  returnPhoto: true, // Optional: return a JPEG face crop as base64.
});
if (!enrollment.success) {
  console.warn(enrollment.error);
}

// The host app decides whether and how to save the optional photo securely.
if (enrollment.success && enrollment.photoBase64) {
  // Handle the sensitive photo according to your app's retention policy.
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
  /** Allow glasses without a warning. Default: true. */
  allowGlasses?: boolean;
  /** Glasses detector sensitivity: 'low' | 'medium' | 'high'. Default: 'medium'. */
  glassesSensitivity?: 'low' | 'medium' | 'high';
  /** Return a JPEG face crop as base64 on registration. Default: false. */
  returnPhoto?: boolean;
};

type FaceRegistrationResult = {
  success: boolean;
  templateId?: string;
  photoBase64?: string;
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

- `allowGlasses: true` (default) allows glasses without a warning or glasses check.
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
| `photo_capture_failed` | The requested registration photo could not be encoded. |
| `not_supported` | Unsupported platform or build. |
| `unknown_error` | Unexpected native failure. |

Native methods are wrapped so rejected promises become `{ success: false, error }` results for the
two capture functions. `hasFaceTemplate` and `deleteFaceTemplate` may reject with an `Error` only
for an unrecoverable bridge failure.

## How it works

1. The native module opens a full-screen front-camera activity/view controller.
2. Apple Vision (iOS) or bundled ML Kit (Android) detects a single face, its landmarks, and head
   pose. The library does not write camera images to disk.
3. If `allowGlasses: false`, an on-device image classifier checks for eyeglasses/sunglasses and
   pauses the challenge while they are detected. By default, this check is skipped.
4. The user moves closer, then looks straight with their mouth closed and opens and closes it. The screen gives
   immediate feedback. This short, fixed challenge has not been validated against presentation attacks.
5. On success, six frames are captured. Each face is aligned, converted to a 64×64 masked and
   equalized grayscale patch, and reduced to a 1,520-value LBP/HOG histogram descriptor.
6. Enrollment stores six descriptors in encrypted local storage. Verification compares six new
   descriptors against the enrolled set using block-wise histogram intersection.
7. A boolean, score, and template ID cross the native bridge. If explicitly requested at registration,
   a 256×256 JPEG face crop is also returned as base64; the host app controls its storage and deletion.

There is no HTTP client in the native module, no analytics, no cloud SDK, and no upload code path.
The host app can store or transmit a requested photo, so its own privacy disclosures must reflect that.
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
- The template is local. The optional returned photo is sensitive biometric data; the host app is
  responsible for consent, access control, storage, retention, and deletion as applicable.
- `deleteFaceTemplate` removes the Keychain item / encrypted file and the Android Keystore key.
- iOS Keychain items can survive app reinstallation; call `deleteFaceTemplate` during account
  cleanup.
- Android stores the encrypted file in `noBackupFilesDir`; it is excluded from auto-backup.
- The Android capture screen sets `FLAG_SECURE` to block screenshots and screen recording.

See [PRIVACY.md](PRIVACY.md) for a data-flow summary.

## Security limitations

This library has not been validated against photo, video, or screen replay. It is **not** designed
to stop:

- a custom 3D mask, deepfake, or a replay timed precisely to the challenge,
- an attacker with control of the device OS, camera injection, or a rooted/jailbroken device,
- identical twins or very close lookalikes,
- a determined attacker who can replace the enrolled template.

The optional glasses check is best-effort: it can miss glasses or produce a false positive
depending on lighting, reflections and glasses style. It can block capture if explicitly enabled.

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
