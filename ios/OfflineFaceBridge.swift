import AVFoundation
import UIKit

/// Objective-C compatible façade used by the TurboModule implementation.
/// All methods return a result dictionary; the JS wrapper turns native
/// exceptions into stable error codes as an additional safety net.
@objc(OfflineFaceBridge)
public final class OfflineFaceBridge: NSObject {
  private static var activeCapture: FaceCaptureViewController?

  @objc public static func registerFace(
    options: [String: Any],
    presenter: UIViewController,
    completion: @escaping ([String: Any]) -> Void
  ) {
    start(mode: "register", options: options, presenter: presenter, completion: completion)
  }

  @objc public static func verifyFace(
    options: [String: Any],
    presenter: UIViewController,
    completion: @escaping ([String: Any]) -> Void
  ) {
    start(mode: "verify", options: options, presenter: presenter, completion: completion)
  }

  @objc public static func hasFaceTemplate(completion: @escaping (Bool) -> Void) {
    DispatchQueue.main.async {
      completion(FaceTemplateStore().hasTemplate())
    }
  }

  @objc public static func deleteFaceTemplate(completion: @escaping () -> Void) {
    DispatchQueue.main.async {
      FaceTemplateStore().delete()
      completion()
    }
  }

  @objc public static func capabilities(completion: @escaping ([String: Any]) -> Void) {
    DispatchQueue.main.async {
      let available = frontCamera() != nil
      completion([
        "available": available,
        "platform": "ios",
        "supportsLiveness": available,
      ])
    }
  }

  private static func start(
    mode: String,
    options: [String: Any],
    presenter: UIViewController,
    completion: @escaping ([String: Any]) -> Void
  ) {
    DispatchQueue.main.async {
      guard activeCapture == nil else {
        completion(FaceCaptureError.alreadyInProgress.result)
        return
      }
      guard let parsed = FaceCaptureOptions(dictionary: options) else {
        completion(FaceCaptureError.invalidOptions.result)
        return
      }
      if mode == "verify", !FaceTemplateStore().hasTemplate() {
        completion(FaceCaptureError.noTemplate.result)
        return
      }
      guard frontCamera() != nil else {
        completion(FaceCaptureError.cameraUnavailable.result)
        return
      }
      guard Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") != nil else {
        completion(FaceCaptureError.cameraUnavailable.result)
        return
      }

      authorizeCamera { granted in
        DispatchQueue.main.async {
          guard granted else {
            completion(FaceCaptureError.cameraPermissionDenied.result)
            return
          }

          let controller = FaceCaptureViewController(
            mode: mode,
            options: parsed
          ) { result in
            activeCapture = nil
            completion(result)
          }
          activeCapture = controller
          presenter.present(controller, animated: false)
        }
      }
    }
  }

  private static func frontCamera() -> AVCaptureDevice? {
    return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front)
      ?? AVCaptureDevice.default(.builtInTrueDepthCamera, for: .video, position: .front)
  }

  private static func authorizeCamera(completion: @escaping (Bool) -> Void) {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized:
      completion(true)
    case .notDetermined:
      AVCaptureDevice.requestAccess(for: .video) { granted in
        completion(granted)
      }
    case .denied, .restricted:
      completion(false)
    @unknown default:
      completion(false)
    }
  }
}
