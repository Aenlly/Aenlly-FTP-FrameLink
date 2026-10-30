import CryptoKit
import Foundation

public struct SavedConnection<Value: Codable & Sendable>: Codable, Identifiable, Sendable {
  public let id: String
  public let config: Value
  public let identity: String
}

public struct ReceiverSettings: Codable, Sendable {
  public var port = 2121
  public var user = "camera"
  public var password = ""
  public init(port: Int = 2121, user: String = "camera", password: String = "") {
    self.port = port; self.user = user; self.password = password
  }
}

public extension FTPProfile {
  var connectionID: String { connectionKey(["ftp", host.lowercased(), String(port), user, String(tls)]) }
}
public extension ReceiverSettings {
  var connectionID: String { connectionKey(["receiver", String(port), user]) }
}
private func connectionKey(_ parts: [String]) -> String {
  SHA256.hash(data: (try? JSONEncoder().encode(parts)) ?? Data()).map { String(format: "%02x", $0) }.joined()
}
public enum ConnectionProfileError: LocalizedError {
  case missingReference, missingCredentials
  public var errorDescription: String? {
    switch self {
    case .missingReference: return "旧任务缺少服务器标识，请从原服务器重新创建任务"
    case .missingCredentials: return "此连接未保存或已忘记，请重新连接原服务器后重试"
    }
  }
}

/// Only explicitly saved records contain authentication data. Last endpoints are sanitized in the same Keychain snapshot.
@MainActor public final class ConnectionProfiles {
  private let vault: any CredentialStorage
  private let key = "connection-profiles-v1"
  private struct Snapshot: Codable {
    var cameras: [SavedConnection<CameraConfig>] = []
    var ftp: [SavedConnection<FTPProfile>] = []
    var receivers: [SavedConnection<ReceiverSettings>] = []
    var lastCamera = CameraConfig()
    var cameraID = ""
    var cameraAuthRequired = false
    var lastFTP = FTPProfile()
    var ftpID = ""
    var lastReceiver = ReceiverSettings()
    var receiverID = ""
  }
  public init(vault: any CredentialStorage) { self.vault = vault }
  private func clearLegacy() throws { for name in ["camera", "ftp", "receiver"] { try vault.erase(name) } }
  private func read() throws -> Snapshot {
    if let value = try vault.read(key, as: Snapshot.self) { try clearLegacy(); return value }
    var value = Snapshot()
    if let camera = try vault.read("camera", as: CameraConfig.self) {
      value.lastCamera = camera; value.lastCamera.user = ""; value.lastCamera.password = ""
      value.cameraAuthRequired = !camera.user.isEmpty
      if !camera.user.isEmpty || !camera.password.isEmpty {
        let id = cameraKey(camera, identity: camera.fingerprint)
        value.cameras = [SavedConnection(id: id, config: camera, identity: camera.fingerprint)]; value.cameraID = id
      }
    }
    if let ftp = try vault.read("ftp", as: FTPProfile.self) {
      value.ftp = [SavedConnection(id: ftp.connectionID, config: ftp, identity: "")]; value.ftpID = ftp.connectionID
      value.lastFTP = ftp; value.lastFTP.user = ""; value.lastFTP.password = ""
    }
    if let receiver = try vault.read("receiver", as: ReceiverSettings.self) {
      value.receivers = [SavedConnection(id: receiver.connectionID, config: receiver, identity: "local")]; value.receiverID = receiver.connectionID
      value.lastReceiver = receiver; value.lastReceiver.user = ""; value.lastReceiver.password = ""
    }
    try write(value); try clearLegacy(); return value
  }
  private func write(_ value: Snapshot) throws { try vault.write(value, name: key) }
  private func cameraKey(_ config: CameraConfig, identity: String) -> String { connectionKey(["sony", config.host.lowercased(), String(config.port), identity]) }
  public func cameras() throws -> [SavedConnection<CameraConfig>] { try read().cameras }
  public func ftpProfiles() throws -> [SavedConnection<FTPProfile>] { try read().ftp }
  public func receiverProfiles() throws -> [SavedConnection<ReceiverSettings>] { try read().receivers }
  public func resolveFTP(_ id: String?, session: [String: FTPProfile]) throws -> FTPProfile {
    guard let id else { throw ConnectionProfileError.missingReference }
    if let value = session[id], value.connectionID == id { return value }
    if let value = try read().ftp.first(where: { $0.id == id && $0.config.connectionID == id }) { return value.config }
    throw ConnectionProfileError.missingCredentials
  }
  public func lastCamera() throws -> CameraConfig { let value = try read(); return value.cameras.first { $0.id == value.cameraID }?.config ?? value.lastCamera }
  public func cameraRequiresCredentials() throws -> Bool { let value = try read(); return value.cameraAuthRequired && !value.cameras.contains { $0.id == value.cameraID } }
  public func lastFTP() throws -> FTPProfile { let value = try read(); return value.ftp.first { $0.id == value.ftpID }?.config ?? value.lastFTP }
  public func lastReceiver() throws -> ReceiverSettings { let value = try read(); return value.receivers.first { $0.id == value.receiverID }?.config ?? value.lastReceiver }
  public func isSaved(_ camera: CameraConfig) throws -> Bool { try read().cameras.contains { $0.config.host == camera.host && $0.config.port == camera.port && $0.config.fingerprint == camera.fingerprint && $0.config.user == camera.user && $0.config.password == camera.password } }
  public func completeCamera(_ config: CameraConfig, identity: String, remember: Bool) throws {
    var value = try read(); let id = cameraKey(config, identity: identity)
    value.cameras.removeAll { $0.identity.isEmpty && $0.config.host == config.host && $0.config.port == config.port && $0.config.user == config.user && $0.config.password == config.password }
    if remember { value.cameras.removeAll { $0.id == id }; value.cameras.insert(SavedConnection(id: id, config: config, identity: identity), at: 0) }
    else { value.cameras.removeAll { $0.id == id } }
    value.lastCamera = config; value.lastCamera.user = ""; value.lastCamera.password = ""
    value.cameraAuthRequired = !config.user.isEmpty; value.cameraID = remember ? id : ""; try write(value)
  }
  public func completeFTP(_ config: FTPProfile, remember: Bool) throws {
    var value = try read(); let id = config.connectionID
    if remember { value.ftp.removeAll { $0.id == id }; value.ftp.insert(SavedConnection(id: id, config: config, identity: ""), at: 0) }
    else { value.ftp.removeAll { $0.id == id } }
    value.lastFTP = config; value.lastFTP.user = ""; value.lastFTP.password = ""; value.ftpID = remember ? id : ""; try write(value)
  }
  public func completeReceiver(_ config: ReceiverSettings, remember: Bool) throws {
    var value = try read(); let id = config.connectionID
    if remember { value.receivers.removeAll { $0.id == id }; value.receivers.insert(SavedConnection(id: id, config: config, identity: "local"), at: 0) }
    else { value.receivers.removeAll { $0.id == id } }
    value.lastReceiver = config; value.lastReceiver.user = ""; value.lastReceiver.password = ""; value.receiverID = remember ? id : ""; try write(value)
  }
  public func forgetCamera(_ id: String) throws { var value = try read(); value.cameras.removeAll { $0.id == id }; if value.cameraID == id { value.cameraID = "" }; try write(value) }
  public func forgetFTP(_ id: String) throws { var value = try read(); value.ftp.removeAll { $0.id == id }; if value.ftpID == id { value.ftpID = "" }; try write(value) }
  public func forgetReceiver(_ id: String) throws { var value = try read(); value.receivers.removeAll { $0.id == id }; if value.receiverID == id { value.receiverID = "" }; try write(value) }
}
