import Foundation

enum GlassesSensitivity: String {
  case low
  case medium
  case high
}

struct FaceCaptureOptions {
  let timeoutMs: Int
  let requireLiveness: Bool
  let threshold: Double
  let allowGlasses: Bool
  let glassesSensitivity: GlassesSensitivity

  init?(dictionary: [String: Any]) {
    let timeout =
      (dictionary["timeout"] as? NSNumber)?.intValue ?? FaceCaptureOptions.defaultTimeoutMs
    let requireLiveness = (dictionary["requireLiveness"] as? NSNumber)?.boolValue ?? true
    let threshold =
      (dictionary["threshold"] as? NSNumber)?.doubleValue ?? FaceCaptureOptions.defaultThreshold
    let allowGlasses = (dictionary["allowGlasses"] as? NSNumber)?.boolValue ?? true
    let glassesSensitivity =
      GlassesSensitivity(
        rawValue: dictionary["glassesSensitivity"] as? String ?? "medium"
      ) ?? .medium

    guard timeout >= FaceCaptureOptions.minTimeoutMs,
      timeout <= FaceCaptureOptions.maxTimeoutMs,
      threshold >= 0.0,
      threshold <= 1.0
    else {
      return nil
    }

    self.timeoutMs = timeout
    self.requireLiveness = requireLiveness
    self.threshold = threshold
    self.allowGlasses = allowGlasses
    self.glassesSensitivity = glassesSensitivity
  }

  static let defaultTimeoutMs = 45_000
  static let minTimeoutMs = 5_000
  static let maxTimeoutMs = 120_000
  static let defaultThreshold = 0.68
}

struct FaceTemplate {
  let templateId: String
  let descriptors: [[Float]]
  let createdAt: TimeInterval
}

enum FaceCaptureError: String {
  case invalidOptions = "invalid_options"
  case cameraUnavailable = "camera_unavailable"
  case cameraPermissionDenied = "camera_permission_denied"
  case alreadyInProgress = "already_in_progress"
  case cancelled = "cancelled"
  case timeout = "timeout"
  case noFace = "no_face"
  case multipleFaces = "multiple_faces"
  case faceTooSmall = "face_too_small"
  case livenessFailed = "liveness_failed"
  case faceMismatch = "face_mismatch"
  case noTemplate = "no_template"
  case storageError = "storage_error"
  case notSupported = "not_supported"
  case unknownError = "unknown_error"

  var result: [String: Any] {
    ["success": false, "error": rawValue]
  }
}

func faceResult(
  success: Bool,
  templateId: String? = nil,
  score: Double? = nil,
  livenessPassed: Bool? = nil,
  error: FaceCaptureError? = nil
) -> [String: Any] {
  var result: [String: Any] = ["success": success]
  if let templateId {
    result["templateId"] = templateId
  }
  if let score {
    result["score"] = score
  }
  if let livenessPassed {
    result["livenessPassed"] = livenessPassed
  }
  if let error {
    result["error"] = error.rawValue
  }
  return result
}
