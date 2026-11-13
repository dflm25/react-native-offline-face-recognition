import CoreImage
import CoreVideo
import Vision

/// Best-effort, fully on-device glasses hint.
///
/// Apple Vision's general image classifier is run on a padded face crop. It is
/// intentionally treated as a hint, not a hard gate: the result is `nil` when
/// the classifier is not confident enough, and callers should never block a
/// capture because of it.
final class GlassesDetector {
  private let context = CIContext(options: [.cacheIntermediates: false])
  private let glassesLabels: Set<String> = [
    "sunglasses",
    "eyeglasses",
    "goggles",
    "optical_equipment",
  ]

  func detect(
    pixelBuffer: CVPixelBuffer,
    face: VNFaceObservation,
    sensitivity: GlassesSensitivity
  ) -> Bool? {
    let image = CIImage(cvPixelBuffer: pixelBuffer)
    let extent = image.extent
    let bbox = face.boundingBox
    let rawRect = CGRect(
      x: bbox.minX * extent.width,
      y: bbox.minY * extent.height,
      width: bbox.width * extent.width,
      height: bbox.height * extent.height
    )
    let cropRect = rawRect
      .insetBy(dx: -rawRect.width * 0.15, dy: -rawRect.height * 0.15)
      .intersection(extent)
    guard cropRect.width >= 48, cropRect.height >= 48 else {
      return nil
    }

    let request = VNClassifyImageRequest()
    let handler = VNImageRequestHandler(ciImage: image.cropped(to: cropRect), options: [:])
    do {
      try handler.perform([request])
    } catch {
      return nil
    }

    let bestScore =
      request.results?
      .filter { glassesLabels.contains($0.identifier) }
      .map { Double($0.confidence) }
      .max() ?? 0

    let thresholds: (positive: Double, negative: Double) =
      switch sensitivity {
      case .low:
        (0.40, 0.15)
      case .medium:
        (0.24, 0.08)
      case .high:
        (0.14, 0.05)
      }

    if bestScore >= thresholds.positive {
      return true
    }
    if bestScore <= thresholds.negative {
      return false
    }
    return nil
  }
}
