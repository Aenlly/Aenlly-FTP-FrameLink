import Foundation
import Security

public protocol CredentialStorage {
  func read<T: Decodable>(_ name: String, as type: T.Type) throws -> T?
  func write<T: Encodable>(_ value: T, name: String) throws
  func erase(_ name: String) throws
}

public final class CredentialVault: CredentialStorage {
  private let service: String
  public init(service: String = "app.framelink.credentials") { self.service = service }
  public func read<T: Decodable>(_ name: String, as type: T.Type) throws -> T? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
      kSecAttrAccount as String: name, kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = result as? Data else {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
    return try JSONDecoder().decode(T.self, from: data)
  }
  public func write<T: Encodable>(_ value: T, name: String) throws {
    let data = try JSONEncoder().encode(value)
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
      kSecAttrAccount as String: name,
    ]
    let status = SecItemUpdate(
      query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
    if status == errSecItemNotFound {
      var item = query
      item[kSecValueData as String] = data
      item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
      let added = SecItemAdd(item as CFDictionary, nil)
      guard added == errSecSuccess else {
        throw NSError(domain: NSOSStatusErrorDomain, code: Int(added))
      }
    } else if status != errSecSuccess {
      throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
    }
  }
  public func remove(_ name: String) {
    try? erase(name)
  }
  public func erase(_ name: String) throws {
    let status = SecItemDelete(
      [
        kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
        kSecAttrAccount as String: name,
      ] as CFDictionary)
    guard status == errSecSuccess || status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
  }
}
