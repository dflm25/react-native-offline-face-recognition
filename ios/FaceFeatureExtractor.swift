import CoreImage
import CoreVideo
import Foundation
import Vision

/// Extracts the same class of illumination-normalized LBP/HOG descriptor as the
/// Android implementation. Only a masked 64x64 grayscale patch is analyzed;
/// no image is persisted or handed back to JS.
final class FaceFeatureExtractor {
  static let descriptorSize = 1_520
  static let imageSize = 64

  private static let lbpGrid = 4
  private static let lbpBins = 59
  private static let lbpBlocks = lbpGrid * lbpGrid
  private static let hogGrid = 8
  private static let hogBins = 9
  private static let hogBlocks = hogGrid * hogGrid
  private static let lbpOffset = 0
  private static let hogOffset = lbpBlocks * lbpBins

  private let context = CIContext(options: [.cacheIntermediates: false])
  private let grayColorSpace = CGColorSpaceCreateDeviceGray()
  private let uniformLbpBins: [Int] = {
    var bins = [Int](repeating: 58, count: 256)
    var next = 0
    for code in 0..<256 {
      if isUniform(code) {
        bins[code] = next
        next += 1
      }
    }
    return bins
  }()

  func extract(pixelBuffer: CVPixelBuffer, face: VNFaceObservation) -> [Float]? {
    let source = CIImage(cvPixelBuffer: pixelBuffer)
    let extent = source.extent
    guard extent.width >= 48, extent.height >= 48 else {
      return nil
    }

    let bbox = face.boundingBox
    let rawRect = CGRect(
      x: bbox.minX * extent.width,
      y: bbox.minY * extent.height,
      width: bbox.width * extent.width,
      height: bbox.height * extent.height
    )
    guard rawRect.width >= 24, rawRect.height >= 24 else {
      return nil
    }

    var side = max(rawRect.width, rawRect.height) * 1.5
    side = min(side, min(extent.width, extent.height))
    guard side >= 48 else {
      return nil
    }

    let x = min(max(rawRect.midX - side / 2, extent.minX), extent.maxX - side)
    let y = min(max(rawRect.midY - side / 2, extent.minY), extent.maxY - side)
    let cropRect = CGRect(x: x, y: y, width: side, height: side)

    var image = source.cropped(to: cropRect)
    image = image.transformed(
      by: CGAffineTransform(translationX: -cropRect.minX, y: -cropRect.minY))

    let roll = faceRoll(face: face, bbox: bbox, extent: extent.size)
    let rotated = image.transformed(
      by: CGAffineTransform(translationX: side / 2, y: side / 2)
        .rotated(by: -roll)
        .translatedBy(x: -side / 2, y: -side / 2))

    let rotatedExtent = rotated.extent
    guard rotatedExtent.width > 0, rotatedExtent.height > 0 else {
      return nil
    }
    let scale = CGFloat(FaceFeatureExtractor.imageSize) / max(rotatedExtent.width, rotatedExtent.height)
    let scaled = rotated.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    let centered = scaled.transformed(
      by: CGAffineTransform(
        translationX: CGFloat(FaceFeatureExtractor.imageSize) / 2 - scaled.extent.midX,
        y: CGFloat(FaceFeatureExtractor.imageSize) / 2 - scaled.extent.midY))
    let background = CIImage(color: CIColor.black).cropped(
      to: CGRect(x: 0, y: 0, width: FaceFeatureExtractor.imageSize, height: FaceFeatureExtractor.imageSize))
    let finalImage = centered.composited(over: background)

    var pixels = [UInt8](repeating: 0, count: FaceFeatureExtractor.imageSize * FaceFeatureExtractor.imageSize)
    pixels.withUnsafeMutableBytes { bytes in
      guard let base = bytes.baseAddress else { return }
      context.render(
        finalImage,
        toBitmap: base,
        rowBytes: FaceFeatureExtractor.imageSize,
        bounds: CGRect(
          x: 0,
          y: 0,
          width: FaceFeatureExtractor.imageSize,
          height: FaceFeatureExtractor.imageSize),
        format: .L8,
        colorSpace: grayColorSpace
      )
    }

    guard FaceExposure.acceptable(pixels, size: FaceFeatureExtractor.imageSize) else {
      return nil
    }
    return descriptor(from: pixels)
  }

  private func faceRoll(face: VNFaceObservation, bbox: CGRect, extent: CGSize) -> CGFloat {
    if let landmarks = face.landmarks,
      let left = landmarks.leftEye?.normalizedPoints,
      let right = landmarks.rightEye?.normalizedPoints
    {
      let leftCenter = centroid(left)
      let rightCenter = centroid(right)
      let leftPoint = CGPoint(
        x: (bbox.minX + leftCenter.x * bbox.width) * extent.width,
        y: (bbox.minY + leftCenter.y * bbox.height) * extent.height
      )
      let rightPoint = CGPoint(
        x: (bbox.minX + rightCenter.x * bbox.width) * extent.width,
        y: (bbox.minY + rightCenter.y * bbox.height) * extent.height
      )
      let angle = atan2(rightPoint.y - leftPoint.y, rightPoint.x - leftPoint.x)
      if angle.isFinite, abs(angle) <= .pi / 4 {
        return angle
      }
    }

    if let roll = face.roll?.doubleValue, roll.isFinite, abs(roll) <= .pi / 4 {
      return CGFloat(roll)
    }
    return 0
  }

  private func centroid(_ points: [CGPoint]) -> CGPoint {
    guard !points.isEmpty else {
      return .zero
    }
    var x: CGFloat = 0
    var y: CGFloat = 0
    for point in points {
      x += point.x
      y += point.y
    }
    return CGPoint(x: x / CGFloat(points.count), y: y / CGFloat(points.count))
  }

  private func descriptor(from source: [UInt8]) -> [Float]? {
    let size = FaceFeatureExtractor.imageSize
    var gray = [Int](repeating: 0, count: source.count)
    var mask = [Bool](repeating: false, count: source.count)
    var inside = 0
    var minGray = 255
    var maxGray = 0

    for y in 0..<size {
      for x in 0..<size {
        let index = y * size + x
        gray[index] = Int(source[index])
        let nx = (CGFloat(x) + 0.5) / CGFloat(size)
        let ny = (CGFloat(y) + 0.5) / CGFloat(size)
        let dx = (nx - 0.5) / 0.46
        let dy = (ny - 0.51) / 0.50
        if dx * dx + dy * dy <= 1 {
          mask[index] = true
          inside += 1
          minGray = min(minGray, Int(source[index]))
          maxGray = max(maxGray, Int(source[index]))
        }
      }
    }
    guard inside >= size * size / 4, maxGray - minGray >= 12 else {
      return nil
    }

    equalize(&gray, mask: mask)
    var descriptor = [Float](repeating: 0, count: FaceFeatureExtractor.descriptorSize)
    computeLbp(gray, mask: mask, descriptor: &descriptor)
    computeHog(gray, mask: mask, descriptor: &descriptor)

    guard descriptor.allSatisfy({ $0.isFinite }) else {
      return nil
    }
    return descriptor
  }

  private func equalize(_ gray: inout [Int], mask: [Bool]) {
    var histogram = [Int](repeating: 0, count: 256)
    var count = 0
    for index in gray.indices where mask[index] {
      histogram[gray[index]] += 1
      count += 1
    }
    guard count > 0 else {
      return
    }

    var lookup = [Int](repeating: 0, count: 256)
    var cumulative = 0
    for value in 0..<256 {
      cumulative += histogram[value]
      lookup[value] = min(255, max(0, cumulative * 255 / count))
    }
    for index in gray.indices {
      gray[index] = mask[index] ? lookup[gray[index]] : 0
    }
  }

  private func computeLbp(_ gray: [Int], mask: [Bool], descriptor: inout [Float]) {
    let size = FaceFeatureExtractor.imageSize
    let lbpGrid = FaceFeatureExtractor.lbpGrid
    let lbpBins = FaceFeatureExtractor.lbpBins
    var counts = [Int](repeating: 0, count: FaceFeatureExtractor.lbpBlocks)

    for y in 1..<(size - 1) {
      for x in 1..<(size - 1) {
        let index = y * size + x
        if !mask[index] {
          continue
        }
        let center = gray[index]
        var code = 0
        if gray[index - size - 1] >= center { code |= 1 << 0 }
        if gray[index - size] >= center { code |= 1 << 1 }
        if gray[index - size + 1] >= center { code |= 1 << 2 }
        if gray[index + 1] >= center { code |= 1 << 3 }
        if gray[index + size + 1] >= center { code |= 1 << 4 }
        if gray[index + size] >= center { code |= 1 << 5 }
        if gray[index + size - 1] >= center { code |= 1 << 6 }
        if gray[index - 1] >= center { code |= 1 << 7 }

        let cellX = min(lbpGrid - 1, max(0, x * lbpGrid / size))
        let cellY = min(lbpGrid - 1, max(0, y * lbpGrid / size))
        let cell = cellY * lbpGrid + cellX
        let bin = uniformLbpBins[code]
        descriptor[FaceFeatureExtractor.lbpOffset + cell * lbpBins + bin] += 1
        counts[cell] += 1
      }
    }

    for cell in 0..<FaceFeatureExtractor.lbpBlocks {
      let count = counts[cell]
      guard count > 0 else { continue }
      let offset = FaceFeatureExtractor.lbpOffset + cell * lbpBins
      for bin in 0..<lbpBins {
        descriptor[offset + bin] /= Float(count)
      }
    }
  }

  private func computeHog(_ gray: [Int], mask: [Bool], descriptor: inout [Float]) {
    let size = FaceFeatureExtractor.imageSize
    let hogGrid = FaceFeatureExtractor.hogGrid
    let hogBins = FaceFeatureExtractor.hogBins
    var blockEnergy = [Float](repeating: 0, count: FaceFeatureExtractor.hogBlocks)

    for y in 1..<(size - 1) {
      for x in 1..<(size - 1) {
        let index = y * size + x
        if !mask[index] {
          continue
        }
        let gx = Float(gray[index + 1] - gray[index - 1])
        let gy = Float(gray[index + size] - gray[index - size])
        let magnitude = sqrt(gx * gx + gy * gy)
        if magnitude < 1e-3 {
          continue
        }

        var angle = atan2(gy, gx) * 180 / .pi
        if angle < 0 { angle += 180 }
        if angle >= 180 { angle -= 180 }
        let bin = min(hogBins - 1, max(0, Int(angle / 180 * Float(hogBins))))
        let cellX = min(hogGrid - 1, max(0, x * hogGrid / size))
        let cellY = min(hogGrid - 1, max(0, y * hogGrid / size))
        let cell = cellY * hogGrid + cellX
        descriptor[FaceFeatureExtractor.hogOffset + cell * hogBins + bin] += magnitude
        blockEnergy[cell] += magnitude
      }
    }

    for cell in 0..<FaceFeatureExtractor.hogBlocks {
      let energy = blockEnergy[cell]
      guard energy > 1e-6 else { continue }
      let offset = FaceFeatureExtractor.hogOffset + cell * hogBins
      for bin in 0..<hogBins {
        descriptor[offset + bin] /= energy
      }
    }
  }

  private static func isUniform(_ code: Int) -> Bool {
    var transitions = 0
    for bit in 0..<8 {
      let current = (code >> bit) & 1
      let next = (code >> ((bit + 1) & 7)) & 1
      if current != next {
        transitions += 1
      }
    }
    return transitions <= 2
  }
}
