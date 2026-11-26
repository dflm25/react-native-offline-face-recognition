import CoreImage
import CoreML

enum FaceApiEmbedding {
  private enum ModelError: Error {
    case resourceMissing
    case invalidInput
    case invalidOutput
  }

  private final class BundleMarker {}

  private static let model = Result {
    let containers = [Bundle(for: BundleMarker.self), Bundle.main]
    let bundles = containers.compactMap {
      $0.url(forResource: "OfflineFaceModels", withExtension: "bundle").flatMap(Bundle.init(url:))
    }
    let config = MLModelConfiguration()
    config.computeUnits = .cpuOnly
    for bundle in bundles {
      if let compiled = bundle.url(forResource: "FaceApiRecognition", withExtension: "mlmodelc") {
        return try MLModel(contentsOf: compiled, configuration: config)
      }
      if let source = bundle.url(forResource: "FaceApiRecognition", withExtension: "mlmodel") {
        return try MLModel(contentsOf: MLModel.compileModel(at: source), configuration: config)
      }
    }
    throw ModelError.resourceMissing
  }

  /// The incoming color patch is aligned in the same coordinates as the 64 px texture patch.
  static func extract(from patch: CIImage, context: CIContext) throws -> [Float] {
    let inputSize = 150
    let scale = CGFloat(inputSize) / 64
    var buffer: CVPixelBuffer?
    let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
    guard CVPixelBufferCreate(kCFAllocatorDefault, inputSize, inputSize,
                              kCVPixelFormatType_32BGRA, attributes, &buffer) == kCVReturnSuccess,
      let buffer, let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)
    else { throw ModelError.invalidInput }
    let bounds = CGRect(x: 0, y: 0, width: inputSize, height: inputSize)
    let background = CIImage(color: .black).cropped(to: bounds)
    let image = patch.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
      .composited(over: background)
    context.render(image, to: buffer, bounds: bounds, colorSpace: colorSpace)
    let features = try MLDictionaryFeatureProvider(dictionary: ["face": MLFeatureValue(pixelBuffer: buffer)])
    let prediction = try model.get().prediction(from: features)
    guard let output = prediction.featureValue(for: "embedding")?.multiArrayValue,
      output.count == 128 else { throw ModelError.invalidOutput }
    let embedding = (0..<output.count).map { output[$0].floatValue }
    guard embedding.allSatisfy({ $0.isFinite }) else { throw ModelError.invalidOutput }
    return embedding
  }
}
