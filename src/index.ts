import { Platform } from 'react-native';

import NativeOfflineFace from './NativeOfflineFace';
import type {
  FaceCapabilities,
  FaceOptions,
  FaceRegistrationResult,
  FaceVerificationResult,
} from './types';

export type {
  FaceCapabilities,
  FaceErrorCode,
  FaceOptions,
  GlassesSensitivity,
  FaceRegistrationResult,
  FaceVerificationResult,
} from './types';

const DEFAULT_TIMEOUT_MS = 45_000;
const MIN_TIMEOUT_MS = 5_000;
const MAX_TIMEOUT_MS = 120_000;
const DEFAULT_THRESHOLD = 0.68;

type NormalizedOptions = {
  timeout: number;
  requireLiveness: boolean;
  threshold: number;
  allowGlasses: boolean;
  glassesSensitivity: 'low' | 'medium' | 'high';
};

type ValidationResult =
  { ok: true; value: NormalizedOptions } | { ok: false; error: string };

function validateOptions(options: FaceOptions | undefined): ValidationResult {
  if (
    options !== undefined &&
    (options === null || typeof options !== 'object')
  ) {
    return { ok: false, error: 'invalid_options' };
  }

  const timeout = options?.timeout ?? DEFAULT_TIMEOUT_MS;
  if (
    typeof timeout !== 'number' ||
    !Number.isFinite(timeout) ||
    timeout < MIN_TIMEOUT_MS ||
    timeout > MAX_TIMEOUT_MS
  ) {
    return { ok: false, error: 'invalid_options' };
  }

  const requireLiveness = options?.requireLiveness ?? true;
  if (typeof requireLiveness !== 'boolean') {
    return { ok: false, error: 'invalid_options' };
  }

  const threshold = options?.threshold ?? DEFAULT_THRESHOLD;
  if (
    typeof threshold !== 'number' ||
    !Number.isFinite(threshold) ||
    threshold < 0 ||
    threshold > 1
  ) {
    return { ok: false, error: 'invalid_options' };
  }

  const allowGlasses = options?.allowGlasses ?? true;
  if (typeof allowGlasses !== 'boolean') {
    return { ok: false, error: 'invalid_options' };
  }

  const glassesSensitivity = options?.glassesSensitivity ?? 'medium';
  if (
    glassesSensitivity !== 'low' &&
    glassesSensitivity !== 'medium' &&
    glassesSensitivity !== 'high'
  ) {
    return { ok: false, error: 'invalid_options' };
  }

  return {
    ok: true,
    value: {
      timeout: Math.round(timeout),
      requireLiveness,
      threshold,
      allowGlasses,
      glassesSensitivity,
    },
  };
}

function toErrorMessage(error: unknown): string {
  if (typeof error === 'string' && error.length > 0) {
    return error;
  }

  if (error !== null && typeof error === 'object') {
    const candidate = error as { code?: unknown; message?: unknown };
    if (typeof candidate.code === 'string' && candidate.code.length > 0) {
      return candidate.code;
    }
    if (typeof candidate.message === 'string' && candidate.message.length > 0) {
      // Native errors follow "<code>: <detail>". Keep only the stable code.
      return candidate.message.split(':', 1)[0] ?? candidate.message;
    }
  }

  return 'unknown_error';
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object'
    ? (value as Record<string, unknown>)
    : {};
}

function parseRegistrationResult(value: unknown): FaceRegistrationResult {
  const record = asRecord(value);
  if (record.success !== true) {
    return {
      success: false,
      error:
        typeof record.error === 'string' && record.error.length > 0
          ? record.error
          : 'unknown_error',
    };
  }

  return {
    success: true,
    ...(typeof record.templateId === 'string'
      ? { templateId: record.templateId }
      : {}),
  };
}

function parseVerificationResult(value: unknown): FaceVerificationResult {
  const record = asRecord(value);
  const score =
    typeof record.score === 'number' && Number.isFinite(record.score)
      ? Math.max(0, Math.min(1, record.score))
      : undefined;
  const livenessPassed =
    typeof record.livenessPassed === 'boolean'
      ? record.livenessPassed
      : undefined;

  if (record.success !== true) {
    return {
      success: false,
      ...(score !== undefined ? { score } : {}),
      ...(livenessPassed !== undefined ? { livenessPassed } : {}),
      error:
        typeof record.error === 'string' && record.error.length > 0
          ? record.error
          : 'unknown_error',
    };
  }

  return {
    success: true,
    ...(score !== undefined ? { score } : {}),
    ...(livenessPassed !== undefined ? { livenessPassed } : {}),
  };
}

/**
 * Opens the native front-camera flow, guides the user through an active
 * anti-spoofing challenge and stores a new encrypted face template on this
 * device. Replaces any existing template.
 *
 * The returned promise never sends image data anywhere; all processing is local.
 */
export async function registerFace(
  options?: FaceOptions
): Promise<FaceRegistrationResult> {
  const validated = validateOptions(options);
  if (!validated.ok) {
    return { success: false, error: validated.error };
  }

  if (NativeOfflineFace == null) {
    return { success: false, error: 'not_supported' };
  }

  try {
    const result = await NativeOfflineFace.registerFace(validated.value);
    return parseRegistrationResult(result);
  } catch (error) {
    return { success: false, error: toErrorMessage(error) };
  }
}

/**
 * Opens the native front-camera flow, performs the liveness challenge and
 * compares the captured face with the template stored on this device.
 */
export async function verifyFace(
  options?: FaceOptions
): Promise<FaceVerificationResult> {
  const validated = validateOptions(options);
  if (!validated.ok) {
    return { success: false, error: validated.error };
  }

  if (NativeOfflineFace == null) {
    return { success: false, error: 'not_supported' };
  }

  try {
    const result = await NativeOfflineFace.verifyFace(validated.value);
    return parseVerificationResult(result);
  } catch (error) {
    return { success: false, error: toErrorMessage(error) };
  }
}

/**
 * Returns `true` when an encrypted face template exists on this device.
 * This does not require camera permission and never opens the camera.
 */
export function hasFaceTemplate(): Promise<boolean> {
  if (NativeOfflineFace == null) {
    return Promise.reject(new Error('not_supported'));
  }
  return NativeOfflineFace.hasFaceTemplate();
}

/**
 * Irreversibly deletes the locally stored face template.
 * Resolves even when no template exists.
 */
export async function deleteFaceTemplate(): Promise<void> {
  if (NativeOfflineFace == null) {
    throw new Error('not_supported');
  }
  await NativeOfflineFace.deleteFaceTemplate();
}

/**
 * Reports hardware/module availability and the active-liveness capability.
 * Never prompts for camera permission.
 */
export async function getFaceCapabilities(): Promise<FaceCapabilities> {
  const fallback: FaceCapabilities = {
    available: false,
    platform: Platform.OS === 'ios' ? 'ios' : 'android',
    supportsLiveness: false,
  };

  if (NativeOfflineFace == null) {
    return fallback;
  }

  try {
    const record = asRecord(await NativeOfflineFace.getFaceCapabilities());
    return {
      available: record.available === true,
      platform: record.platform === 'ios' ? 'ios' : 'android',
      supportsLiveness: record.supportsLiveness === true,
    };
  } catch {
    return fallback;
  }
}
