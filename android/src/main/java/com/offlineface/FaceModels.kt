package com.offlineface

import com.facebook.react.bridge.ReadableMap

internal const val MODE_REGISTER = "register"
internal const val MODE_VERIFY = "verify"

internal const val EXTRA_MODE = "com.offlineface.MODE"
internal const val EXTRA_TIMEOUT = "com.offlineface.TIMEOUT"
internal const val EXTRA_REQUIRE_LIVENESS = "com.offlineface.REQUIRE_LIVENESS"
internal const val EXTRA_THRESHOLD = "com.offlineface.THRESHOLD"
internal const val EXTRA_ALLOW_GLASSES = "com.offlineface.ALLOW_GLASSES"
internal const val EXTRA_GLASSES_SENSITIVITY = "com.offlineface.GLASSES_SENSITIVITY"
internal const val EXTRA_RETURN_PHOTO = "com.offlineface.RETURN_PHOTO"
internal const val EXTRA_PHOTO = "com.offlineface.PHOTO"
internal const val EXTRA_SUCCESS = "com.offlineface.SUCCESS"
internal const val EXTRA_TEMPLATE_ID = "com.offlineface.TEMPLATE_ID"
internal const val EXTRA_SCORE = "com.offlineface.SCORE"
internal const val EXTRA_LIVENESS = "com.offlineface.LIVENESS"
internal const val EXTRA_ERROR = "com.offlineface.ERROR"

internal const val DEFAULT_TIMEOUT_MS = 45_000L
internal const val MIN_TIMEOUT_MS = 5_000L
internal const val MAX_TIMEOUT_MS = 120_000L
internal const val DEFAULT_THRESHOLD = 0.68

internal data class FaceCaptureOptions(
  val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  val requireLiveness: Boolean = true,
  val threshold: Double = DEFAULT_THRESHOLD,
  val allowGlasses: Boolean = true,
  val glassesSensitivity: String = "medium",
  val returnPhoto: Boolean = false,
) {
  companion object {
    fun fromMap(map: ReadableMap?): FaceCaptureOptions? {
      if (map == null) {
        return FaceCaptureOptions()
      }
      val timeout =
        if (map.hasKey("timeout")) map.getDouble("timeout").toLong() else DEFAULT_TIMEOUT_MS
      val requireLiveness =
        if (map.hasKey("requireLiveness")) map.getBoolean("requireLiveness") else true
      val threshold =
        if (map.hasKey("threshold")) map.getDouble("threshold") else DEFAULT_THRESHOLD
      val allowGlasses =
        if (map.hasKey("allowGlasses")) map.getBoolean("allowGlasses") else true
      val glassesSensitivity =
        if (map.hasKey("glassesSensitivity")) {
          map.getString("glassesSensitivity") ?: "medium"
        } else {
          "medium"
        }

      if (glassesSensitivity !in setOf("low", "medium", "high")) {
        return null
      }
      if (timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
        return null
      }
      if (threshold < 0.0 || threshold > 1.0 || threshold.isNaN()) {
        return null
      }
      val returnPhoto = if (map.hasKey("returnPhoto")) map.getBoolean("returnPhoto") else false
      return FaceCaptureOptions(timeout, requireLiveness, threshold, allowGlasses, glassesSensitivity, returnPhoto)
    }
  }
}

internal enum class FaceDescriptorFormat(val version: Int) {
  LEGACY(1),
  EYE_ALIGNED(2),
  LIGHTING_NORMALIZED(3),
}

internal data class FaceTemplateRecord(
  val templateId: String,
  val format: FaceDescriptorFormat,
  val descriptors: List<FloatArray>,
  val createdAt: Long,
)

internal data class CaptureOutcome(
  val success: Boolean,
  val templateId: String? = null,
  val photoBase64: String? = null,
  val score: Double? = null,
  val livenessPassed: Boolean? = null,
  val error: String? = null,
) {
  companion object {
    fun failure(error: String) = CaptureOutcome(success = false, error = error)
  }
}
