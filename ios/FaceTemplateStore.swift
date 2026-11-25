import Foundation
import Security

enum FaceTemplateStoreError: Error {
  case encodingFailed
  case keychainFailure(OSStatus)
}

/// Stores one encrypted-by-the-OS Keychain item containing only histogram
/// descriptors. No image, pixel buffer, or plaintext biometric data is written
/// to disk or to UserDefaults.
final class FaceTemplateStore {
  private static let service = "com.reactnativeofflineface.template"
  private static let account = "primary"

  func hasTemplate() -> Bool {
    return load() != nil
  }

  func load() -> FaceTemplate? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: FaceTemplateStore.service,
      kSecAttrAccount as String: FaceTemplateStore.account,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]

    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
      let data = item as? Data
    else {
      return nil
    }

    return decode(data)
  }

  func save(descriptors: [[Float]]) throws -> FaceTemplate {
    guard !descriptors.isEmpty,
      descriptors.allSatisfy({ $0.count == FaceFeatureExtractor.descriptorSize })
    else {
      throw FaceTemplateStoreError.encodingFailed
    }

    let record = FaceTemplate(
      templateId: UUID().uuidString,
      format: .eyeAligned,
      descriptors: descriptors.map { $0 },
      createdAt: Date().timeIntervalSince1970
    )
    let payload = try encode(record)
    let baseQuery: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: FaceTemplateStore.service,
      kSecAttrAccount as String: FaceTemplateStore.account,
    ]
    let attributes = [kSecValueData as String: payload]
    var status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
    if status == errSecItemNotFound {
      var addQuery = baseQuery
      addQuery[kSecValueData as String] = payload
      addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
      addQuery[kSecAttrSynchronizable as String] = false
      status = SecItemAdd(addQuery as CFDictionary, nil)
    }
    guard status == errSecSuccess else {
      throw FaceTemplateStoreError.keychainFailure(status)
    }
    return record
  }

  func delete() {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: FaceTemplateStore.service,
      kSecAttrAccount as String: FaceTemplateStore.account,
    ]
    SecItemDelete(query as CFDictionary)
  }

  private func encode(_ template: FaceTemplate) throws -> Data {
    let encodedDescriptors = template.descriptors.map { descriptor -> String in
      encodeFloats(descriptor).base64EncodedString()
    }
    let object: [String: Any] = [
      "version": template.format.rawValue,
      "templateId": template.templateId,
      "createdAt": template.createdAt,
      "platform": "ios",
      "descriptors": encodedDescriptors,
    ]
    guard JSONSerialization.isValidJSONObject(object) else {
      throw FaceTemplateStoreError.encodingFailed
    }
    return try JSONSerialization.data(withJSONObject: object, options: [])
  }

  private func decode(_ data: Data) -> FaceTemplate? {
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let version = object["version"] as? Int,
      let format = FaceDescriptorFormat(rawValue: version),
      let templateId = object["templateId"] as? String,
      !templateId.isEmpty,
      let encoded = object["descriptors"] as? [String],
      !encoded.isEmpty
    else {
      return nil
    }

    var descriptors: [[Float]] = []
    for item in encoded {
      guard let bytes = Data(base64Encoded: item),
        bytes.count == FaceFeatureExtractor.descriptorSize * MemoryLayout<Float>.size,
        let descriptor = decodeFloats(bytes),
        descriptor.allSatisfy({ $0.isFinite })
      else {
        return nil
      }
      descriptors.append(descriptor)
    }

    return FaceTemplate(
      templateId: templateId,
      format: format,
      descriptors: descriptors,
      createdAt: (object["createdAt"] as? NSNumber)?.doubleValue ?? 0
    )
  }

  private func encodeFloats(_ values: [Float]) -> Data {
    var data = Data(capacity: values.count * MemoryLayout<Float>.size)
    for value in values {
      var bits = value.bitPattern.littleEndian
      withUnsafeBytes(of: &bits) { data.append(contentsOf: $0) }
    }
    return data
  }

  private func decodeFloats(_ data: Data) -> [Float]? {
    let stride = MemoryLayout<Float>.size
    guard data.count % stride == 0 else {
      return nil
    }
    var values: [Float] = []
    values.reserveCapacity(data.count / stride)
    var offset = 0
    while offset < data.count {
      let range = offset..<(offset + stride)
      let bits = data.subdata(in: range).withUnsafeBytes { raw -> UInt32 in
        var value: UInt32 = 0
        withUnsafeMutableBytes(of: &value) { destination in
          destination.copyBytes(from: raw)
        }
        return UInt32(littleEndian: value)
      }
      values.append(Float(bitPattern: bits))
      offset += stride
    }
    return values
  }
}
