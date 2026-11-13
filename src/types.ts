/**
 * Public types for react-native-offline-face.
 *
 * Nothing in this API ever sends biometric data off the device. The native
 * layer keeps capture frames in memory and persists only an encrypted,
 * device-bound template in Keychain (iOS) or Android Keystore-backed storage.
 */

export type FaceOptions = {
  /**
   * Maximum time, in milliseconds, that the capture UI may stay open.
   * Defaults to 45 seconds. Allowed range: 5,000–120,000 ms.
   */
  timeout?: number;
  /**
   * Require the active anti-spoofing challenge before capturing a template.
   * Defaults to `true`. Disabling it is not recommended for clock-in flows.
   */
  requireLiveness?: boolean;
  /**
   * Similarity threshold in the range 0–1 used by `verifyFace`.
   * Defaults to `0.68`. Larger values reduce false accepts; smaller values
   * reduce false rejects. Tune it against your own population and lighting.
   */
  threshold?: number;
  /**
   * Whether eyeglasses are allowed during capture. Defaults to `true`, which
   * shows a non-blocking hint. Set to `false` to pause the challenge until the
   * detected glasses are removed.
   */
  allowGlasses?: boolean;
  /**
   * Controls how eagerly eyeglasses are reported. `low` prefers fewer false
   * positives, `high` prefers catching more glasses. Defaults to `medium`.
   */
  glassesSensitivity?: GlassesSensitivity;
};

export type GlassesSensitivity = 'low' | 'medium' | 'high';

export type FaceRegistrationResult = {
  success: boolean;
  /** Opaque local identifier for the stored template. Never an identity. */
  templateId?: string;
  /** Stable machine-readable error code when `success` is false. */
  error?: string;
};

export type FaceVerificationResult = {
  success: boolean;
  /** Similarity score in the range 0–1. Present when a comparison ran. */
  score?: number;
  /** Whether the active liveness challenge was completed. */
  livenessPassed?: boolean;
  /** Stable machine-readable error code when `success` is false. */
  error?: string;
};

export type FaceCapabilities = {
  /** `true` when a front-facing camera and the native module are available. */
  available: boolean;
  platform: 'ios' | 'android';
  /** Active challenge support. `false` only on unsupported hardware. */
  supportsLiveness: boolean;
};

/**
 * Stable error codes returned in `result.error`.
 *
 * - `invalid_options`          Invalid timeout/threshold/liveness option.
 * - `camera_unavailable`       No usable front camera (for example, simulator).
 * - `camera_permission_denied` The user denied camera access.
 * - `already_in_progress`      Another capture is already open.
 * - `cancelled`                The user closed the capture UI.
 * - `timeout`                  The capture did not finish before `timeout`.
 * - `no_face`                  No face was found in the capture window.
 * - `multiple_faces`           More than one face was visible.
 * - `face_too_small`           The face was too far from the camera.
 * - `liveness_failed`          The anti-spoofing challenge did not pass.
 * - `face_mismatch`            The captured face did not match the stored template.
 * - `no_template`              No face has been registered on this device.
 * - `storage_error`            The encrypted local template could not be read/written.
 * - `not_supported`            The platform or build is not supported.
 * - `unknown_error`            An unexpected native failure.
 */
export type FaceErrorCode =
  | 'invalid_options'
  | 'camera_unavailable'
  | 'camera_permission_denied'
  | 'already_in_progress'
  | 'cancelled'
  | 'timeout'
  | 'no_face'
  | 'multiple_faces'
  | 'face_too_small'
  | 'liveness_failed'
  | 'face_mismatch'
  | 'no_template'
  | 'storage_error'
  | 'not_supported'
  | 'unknown_error';
