import Foundation

public enum CameraProtocol {
  public static let sony = "sony-ptpip"
  public static let canon = "canon-ccapi"
  public static func supported(_ value: String) -> Bool { value == sony || value == canon }
  public static func title(_ value: String) -> String {
    value == sony ? "Sony网络" : value == canon ? "Canon CCAPI（实验）" : "未识别协议"
  }
}
public enum CameraConnectionError: LocalizedError {
  case unsupportedProtocol, invalidEndpoint, incompleteIdentity
  public var errorDescription: String? {
    switch self {
    case .unsupportedProtocol: return "此连接协议尚未接入，请重新选择连接通道"
    case .invalidEndpoint: return "请填写机身IP地址和有效端口"
    case .incompleteIdentity: return "机身未返回可验证的设备身份或日期来源，未恢复照片索引"
    }
  }
}
public struct CameraClientCapabilities: Sendable {
  public let canBrowse: Bool
  public let canPreview: Bool
  public let canDownload: Bool
  public let canLiveView: Bool
  public let canCapture: Bool
  public let canSetProperties: Bool
}
public enum CameraConnectionPolicy {
  /// This exact encoder is also used by NativeTransport.connect and the Swift→Go fixture.
  public static func encode(_ config: CameraConfig) throws -> String {
    String(decoding: try JSONEncoder().encode(wireConfig(config)), as: UTF8.self)
  }
  public static func wireConfig(_ original: CameraConfig) throws -> CameraConfig {
    guard CameraProtocol.supported(original.protocolID) else { throw CameraConnectionError.unsupportedProtocol }
    guard !original.host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !original.host.contains("://"), (1...65535).contains(original.port) else { throw CameraConnectionError.invalidEndpoint }
    var config = original
    if config.protocolID == CameraProtocol.canon { config.fingerprint = ""; config.guid = "" }
    return config
  }
  public static func requiresSSHIdentity(_ config: CameraConfig) -> Bool { config.protocolID == CameraProtocol.sony && !config.user.isEmpty }
  public static func canReconnect(_ config: CameraConfig) -> Bool {
    guard (try? wireConfig(config)) != nil else { return false }
    if !config.user.isEmpty && config.password.isEmpty { return false }
    return !requiresSSHIdentity(config) || !config.fingerprint.isEmpty
  }
  public static func validate(info: CameraInfo, config: CameraConfig) throws {
    guard !info.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw CameraConnectionError.incompleteIdentity }
    if config.protocolID == CameraProtocol.canon {
      // Shared CCAPI backend rejects missing serial/GUID before producing this identity.
      guard info.manufacturer?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("canon") == true, info.dateSource == "FILE_MODIFIED" else { throw CameraConnectionError.incompleteIdentity }
    }
  }
  public static func capabilities(info: CameraInfo?, protocolID: String) -> CameraClientCapabilities {
    guard CameraProtocol.supported(protocolID) else { return CameraClientCapabilities(canBrowse: false, canPreview: false, canDownload: false, canLiveView: false, canCapture: false, canSetProperties: false) }
    let controls = protocolID == CameraProtocol.sony
    return CameraClientCapabilities(canBrowse: info?.canBrowse == true, canPreview: info?.canPreview == true, canDownload: info?.canDownload == true,
      canLiveView: controls && info?.canLiveView == true, canCapture: controls && info?.canCapture == true, canSetProperties: controls && info != nil)
  }
}
