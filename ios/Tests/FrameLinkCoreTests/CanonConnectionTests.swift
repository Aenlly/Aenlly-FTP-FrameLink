import CryptoKit
import XCTest
@testable import FrameLinkCore

@MainActor final class CanonConnectionTests: XCTestCase {
  private func config(_ protocolID: String) -> CameraConfig {
    var config = CameraConfig(); config.protocolID = protocolID; config.host = "127.0.0.1"; config.port = 8080; config.user = "user"; config.password = "secret"; config.fingerprint = "sony-fingerprint"; return config
  }
  private func info(id: String = "stable-device-id") throws -> CameraInfo {
    try JSONDecoder().decode(CameraInfo.self, from: JSONSerialization.data(withJSONObject: ["id": id, "model": "EOS Synthetic", "manufacturer": "Canon", "dateSource": "FILE_MODIFIED", "firmware": "fixture", "canBrowse": true, "canDownload": true, "canPreview": true, "canCapture": true, "canLiveView": true]))
  }
  func testLegacyMissingProtocolIsSonyAndUnknownProtocolIsNotReinterpreted() async throws {
    let legacy = try JSONDecoder().decode(CameraConfig.self, from: Data("{\"host\":\"127.0.0.1\",\"port\":15740,\"user\":\"old-user\",\"password\":\"old-secret\",\"fingerprint\":\"fp\"}".utf8))
    XCTAssertEqual(legacy.protocolID, CameraProtocol.sony); XCTAssertTrue(CameraConnectionPolicy.requiresSSHIdentity(legacy))
    let unknown = try JSONDecoder().decode(CameraConfig.self, from: Data("{\"protocol\":\"unsupported-camera\",\"host\":\"127.0.0.1\"}".utf8))
    XCTAssertEqual(unknown.protocolID, "unsupported-camera"); XCTAssertThrowsError(try CameraConnectionPolicy.encode(unknown))
    XCTAssertThrowsError(try JSONDecoder().decode(CameraConfig.self, from: Data("{\"protocol\":null}".utf8)))
  }
  func testCanonWireUsesActualProtocolFieldAndNeverSendsSonyIdentity() async throws {
    let config = config(CameraProtocol.canon); let raw = try CameraConnectionPolicy.encode(config)
    let object = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
    XCTAssertEqual(object["protocol"] as? String, "canon-ccapi"); XCTAssertNil(object["protocolID"])
    XCTAssertEqual(object["fingerprint"] as? String, ""); XCTAssertEqual(object["guid"] as? String, "")
    XCTAssertEqual(object["user"] as? String, config.user); XCTAssertEqual(object["password"] as? String, config.password)
    XCTAssertFalse(CameraConnectionPolicy.requiresSSHIdentity(config)); XCTAssertTrue(CameraConnectionPolicy.canReconnect(config))
    var sony = config; sony.protocolID = CameraProtocol.sony; sony.fingerprint = ""
    XCTAssertFalse(CameraConnectionPolicy.canReconnect(sony))
  }
  func testProtocolSwitchClearsCredentialsAndDoesNotModifyOtherStoredConfiguration() async throws {
    let sony = config(CameraProtocol.sony)
    let canon = ConnectionFormEditing.cameraProtocol(sony, protocolID: CameraProtocol.canon)
    XCTAssertEqual(canon.port, 8080); XCTAssertEqual(canon.user, ""); XCTAssertEqual(canon.password, ""); XCTAssertEqual(canon.fingerprint, ""); XCTAssertEqual(canon.guid, "")
    let back = ConnectionFormEditing.cameraProtocol(canon, protocolID: CameraProtocol.sony)
    XCTAssertEqual(back.port, 15740); XCTAssertFalse(back.guid.isEmpty); XCTAssertEqual(sony.password, "secret")
    let same = ConnectionFormEditing.cameraProtocol(sony, protocolID: CameraProtocol.sony); XCTAssertEqual(same.password, sony.password)
  }
  func testSonyAndCanonSavedConnectionsWithSameEndpointRemainIndependent() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    let sony = config(CameraProtocol.sony); var canon = config(CameraProtocol.canon); canon.fingerprint = ""; canon.guid = ""
    try store.completeCamera(sony, identity: "same-id", remember: true); try store.completeCamera(canon, identity: "same-id", remember: true)
    XCTAssertEqual(try store.cameras().count, 2); XCTAssertTrue(try store.isSaved(sony)); XCTAssertTrue(try store.isSaved(canon))
    let canonID = try store.cameras().first { $0.config.protocolID == CameraProtocol.canon }!.id
    try store.forgetCamera(canonID)
    XCTAssertEqual(try store.cameras().map { $0.config.protocolID }, [CameraProtocol.sony])
    XCTAssertFalse(try store.isSaved(canon)); XCTAssertTrue(try store.isSaved(sony))
  }
  func testUnsavedCanonCredentialsDoNotPersistOrEraseSonyProfile() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    let sony = config(CameraProtocol.sony); var canon = config(CameraProtocol.canon); canon.password = "temporary-http-secret"
    try store.completeCamera(sony, identity: "sony", remember: true)
    try store.completeCamera(canon, identity: "canon", remember: false)
    XCTAssertEqual(try store.cameras().map { $0.config.protocolID }, [CameraProtocol.sony])
    let last = try store.lastCamera(); XCTAssertEqual(last.protocolID, CameraProtocol.canon)
    XCTAssertEqual(last.user, ""); XCTAssertEqual(last.password, ""); XCTAssertEqual(last.fingerprint, ""); XCTAssertTrue(try store.cameraRequiresCredentials())
    XCTAssertFalse(String(decoding: vault.values["connection-profiles-v1"]!, as: UTF8.self).contains(canon.password))
  }
  func testLegacySonyKeyMigrationPreservesTheExistingKeyNamespace() async throws {
    let vault = MemoryCredentials()
    vault.values["camera"] = Data("{\"host\":\"127.0.0.1\",\"port\":15740,\"user\":\"old\",\"password\":\"secret\",\"fingerprint\":\"fp\"}".utf8)
    let store = ConnectionProfiles(vault: vault); let records = try store.cameras()
    let expected = SHA256.hash(data: try JSONEncoder().encode(["sony", "127.0.0.1", "15740", "fp"])).map { String(format: "%02x", $0) }.joined()
    XCTAssertEqual(records.first?.id, expected); XCTAssertEqual(records.first?.config.protocolID, CameraProtocol.sony)
    XCTAssertEqual(try store.lastCamera().password, "secret"); XCTAssertNil(vault.values["camera"])
  }
  func testReportedCapabilitiesAreRetainedButCanonControlsRemainUnavailable() async throws {
    let value = try info(); let canon = config(CameraProtocol.canon)
    try CameraConnectionPolicy.validate(info: value, config: canon)
    XCTAssertTrue(value.canLiveView); XCTAssertTrue(value.canCapture)
    let capabilities = CameraConnectionPolicy.capabilities(info: value, protocolID: canon.protocolID)
    XCTAssertTrue(capabilities.canBrowse); XCTAssertTrue(capabilities.canPreview); XCTAssertTrue(capabilities.canDownload)
    XCTAssertFalse(capabilities.canLiveView); XCTAssertFalse(capabilities.canCapture); XCTAssertFalse(capabilities.canSetProperties)
    XCTAssertTrue(CameraConnectionPolicy.capabilities(info: value, protocolID: CameraProtocol.sony).canLiveView)
    XCTAssertFalse(CameraConnectionPolicy.capabilities(info: value, protocolID: "unknown").canBrowse)
    XCTAssertThrowsError(try CameraConnectionPolicy.validate(info: info(id: ""), config: canon))
  }
  func testFileModificationTimeIsNotConvertedIntoCaptureTime() async throws {
    let json = "{\"id\":\"stable:ccapi:resource\",\"contentId\":1,\"slot\":1,\"capturedAt\":0,\"files\":[{\"fileId\":1,\"name\":\"IMG.JPG\",\"size\":200,\"fileModifiedAt\":1800000000000,\"fileDay\":\"2027-01-15\"}]}"
    let remote = try JSONDecoder().decode(RemotePhoto.self, from: Data(json.utf8)); let photo = remote.photo()
    XCTAssertEqual(photo.capturedAt.timeIntervalSince1970, 0); XCTAssertEqual(photo.orderingDate.timeIntervalSince1970, 1800000000)
    var state = CameraIndexState(); state.scope = CameraIndexScope(deviceID: "stable", dateSource: "FILE_MODIFIED")
    XCTAssertTrue(state.rangeLabel.contains("文件修改日期")); XCTAssertEqual(photo.files[0].fileDay, "2027-01-15")
  }
}
