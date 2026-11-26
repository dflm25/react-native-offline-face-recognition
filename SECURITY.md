# Security

## Scope and threat model

`react-native-offline-face-recognition` is a local attendance aid. It is intended to make casual impersonation
with a basic mouth movement check. It is **not** a certified
biometric authentication product and must not be the only control protecting high-value accounts,
money, or physical access.

### Assets

- The face template stored on the device (a biometric identifier).
- The integrity of the clock-in/clock-out decision.
- Camera frames, which must remain on-device and in memory.

### Adversary assumptions

An attacker may:

- hold a printed photo, a phone screen, or a pre-recorded video in front of the camera;
- know the public API and source code;
- retry the capture;
- attempt to read the app's data directory on a rooted/jailbroken device.

An attacker is assumed **not** to control the operating system, camera pipeline, or secure
hardware. Device compromise is out of scope.

## Mitigations implemented

| Risk | Mitigation |
| --- | --- |
| Presentation attacks | The fixed approach and mouth-open/close challenge is not validated against photos, screens, or replayed video and can be spoofed. Face size is not physical depth. |
| Screenshots / screen recording (Android) | `FLAG_SECURE` on the capture activity. |
| Template at rest | AES-256-GCM with a non-exportable Android Keystore key; iOS Keychain item with `WhenUnlockedThisDeviceOnly`. |
| Cloud backup of template | Android `noBackupFilesDir`; iOS Keychain item is not synchronizable. |
| Data exfiltration via library | No network permission in the library manifest, no HTTP client, no analytics, no image persistence. |
| Accidental frame persistence | Frames are processed in memory and released. Templates contain float histograms or, in the iOS pilot, embeddings. |
| Optional glasses check | On-device classification is skipped by default; when enabled with `allowGlasses: false`, it may block capture. The check is best-effort and no crop is persisted. |
| Concurrent captures | Native module rejects a second capture with `already_in_progress`. |
| Sensitive data in result | Results contain status, an opaque local ID and score. An opt-in registration result can also contain a JPEG face photo; the host app is responsible for protecting it. |

## Residual risks

The active challenge is not ISO/IEC 30107-3 certified. In particular:

- a 3D mask or high-quality deepfake may pass;
- an attacker with a prepared, interactive video feed may pass;
- twins and very close lookalikes may exceed the similarity threshold;
- the default LBP/HOG texture descriptor is sensitive to
  extreme pose, lighting, occlusion, and camera differences;
- rooted/jailbroken devices, hooking frameworks, or camera injection can bypass any check made in
  app code;
- a user who can enroll their own face on the device becomes the enrolled identity.

There is no server-side replay protection in this package. A clock-in backend should apply its own
rate limits, duplicate-event detection, device binding, and anomaly review. Never send a score and
treat it as proof of a real person.

## Reporting a vulnerability

Please do not open a public issue for a security vulnerability. Email the maintainer at
`dflm25@gmail.com` with a description, reproduction steps, affected versions, and any suggested
fix. You will receive an acknowledgement as soon as possible. Please allow time for a fix before
public disclosure.

## Recommended deployment checklist

- Obtain informed consent and publish a retention/deletion policy.
- Use HTTPS for your own clock-in event API; the face data itself never leaves the device.
- Add a fallback (PIN, badge, supervisor) for false rejects.
- Rate-limit verification attempts and alert on repeated `liveness_failed` / `face_mismatch`.
- Call `deleteFaceTemplate()` on account deletion or employee offboarding (and on shared devices
  before another user enrolls).
- Test on the actual low-end devices and lighting conditions your workforce uses.
- Re-evaluate the threshold and liveness flow after OS/camera updates.
- Do not repurpose this package for surveillance, identification of unknowing people, or
  access control without a documented risk assessment.
