import { beforeEach, describe, expect, it, jest } from '@jest/globals';

import NativeOfflineFace, { type Spec } from '../NativeOfflineFace';
import {
  deleteFaceTemplate,
  getFaceCapabilities,
  hasFaceTemplate,
  registerFace,
  verifyFace,
} from '../index';

jest.mock('../NativeOfflineFace', () => ({
  __esModule: true,
  default: {
    registerFace: jest.fn(),
    verifyFace: jest.fn(),
    hasFaceTemplate: jest.fn(),
    deleteFaceTemplate: jest.fn(),
    getFaceCapabilities: jest.fn(),
  },
}));

const native = NativeOfflineFace as jest.Mocked<Spec>;

describe('react-native-offline-face-recognition', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('passes safe defaults to registerFace', async () => {
    native.registerFace.mockResolvedValue({
      success: true,
      templateId: 'local-template-id',
    });

    await expect(registerFace()).resolves.toEqual({
      success: true,
      templateId: 'local-template-id',
    });
    expect(native.registerFace).toHaveBeenCalledWith({
      timeout: 45000,
      requireLiveness: true,
      threshold: 0.68,
      allowGlasses: true,
      glassesSensitivity: 'medium',
    });
  });

  it('validates options before touching the native module', async () => {
    await expect(registerFace({ threshold: 1.5 })).resolves.toEqual({
      success: false,
      error: 'invalid_options',
    });
    await expect(verifyFace({ timeout: 1000 })).resolves.toEqual({
      success: false,
      error: 'invalid_options',
    });
    await expect(
      verifyFace({ glassesSensitivity: 'extreme' as 'low' })
    ).resolves.toEqual({
      success: false,
      error: 'invalid_options',
    });
    expect(native.registerFace).not.toHaveBeenCalled();
    expect(native.verifyFace).not.toHaveBeenCalled();
  });

  it('normalizes verification scores and liveness state', async () => {
    native.verifyFace.mockResolvedValue({
      success: true,
      score: 1.8,
      livenessPassed: true,
    });

    await expect(verifyFace({ threshold: 0.7 })).resolves.toEqual({
      success: true,
      score: 1,
      livenessPassed: true,
    });
  });

  it('turns native failures into stable error codes', async () => {
    native.registerFace.mockRejectedValue({
      code: 'camera_permission_denied',
      message: 'camera_permission_denied: denied by user',
    });
    native.verifyFace.mockRejectedValue(
      new Error('liveness_failed: challenge not completed')
    );

    await expect(registerFace()).resolves.toEqual({
      success: false,
      error: 'camera_permission_denied',
    });
    await expect(verifyFace()).resolves.toEqual({
      success: false,
      error: 'liveness_failed',
    });
  });

  it('passes no-template results through unchanged', async () => {
    native.verifyFace.mockResolvedValue({
      success: false,
      error: 'no_template',
      livenessPassed: true,
    });

    await expect(verifyFace()).resolves.toEqual({
      success: false,
      error: 'no_template',
      livenessPassed: true,
    });
  });

  it('supports template lifecycle and capability queries', async () => {
    native.hasFaceTemplate.mockResolvedValue(true);
    native.deleteFaceTemplate.mockResolvedValue(undefined);
    native.getFaceCapabilities.mockResolvedValue({
      available: true,
      platform: 'ios',
      supportsLiveness: true,
    });

    await expect(hasFaceTemplate()).resolves.toBe(true);
    await expect(deleteFaceTemplate()).resolves.toBeUndefined();
    await expect(getFaceCapabilities()).resolves.toEqual({
      available: true,
      platform: 'ios',
      supportsLiveness: true,
    });
  });

  it('reports unavailable capabilities when the native call fails', async () => {
    native.getFaceCapabilities.mockRejectedValue(new Error('boom'));

    await expect(getFaceCapabilities()).resolves.toEqual({
      available: false,
      platform: 'ios',
      supportsLiveness: false,
    });
  });
});
