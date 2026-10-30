import Foundation

/// User edits clear authentication independently of whether a saved profile is selected.
public enum ConnectionFormEditing {
  public static func cameraProtocol(_ original: CameraConfig, protocolID: String) -> CameraConfig {
    guard original.protocolID != protocolID else { return original }
    var value = original; value.protocolID = protocolID
    value.experimentalFuji = false; value.fujiDeviceName = ""
    value.port = protocolID == CameraProtocol.canon ? 8080 : 15740
    value.user = ""; value.password = ""; value.fingerprint = ""
    value.guid = protocolID == CameraProtocol.sony ? CameraConfig().guid : ""
    return value
  }
  public static func cameraEndpoint(_ original: CameraConfig, host: String, port: Int) -> CameraConfig {
    guard original.host != host || original.port != port else { return original }
    var value = original
    value.host = host; value.port = port
    value.user = ""; value.password = ""; value.fingerprint = ""
    return value
  }
  public static func cameraAccount(_ original: CameraConfig, user: String) -> CameraConfig {
    guard original.user != user else { return original }
    var value = original; value.user = user; value.password = ""
    return value
  }
  public static func ftpEndpoint(_ original: FTPProfile, host: String, port: Int, tls: Bool) -> FTPProfile {
    guard original.host != host || original.port != port || original.tls != tls else { return original }
    var value = original
    value.host = host; value.port = port; value.tls = tls
    value.user = ""; value.password = ""
    return value
  }
  public static func ftpAccount(_ original: FTPProfile, user: String) -> FTPProfile {
    guard original.user != user else { return original }
    var value = original; value.user = user; value.password = ""
    return value
  }
}
