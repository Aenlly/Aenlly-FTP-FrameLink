import XCTest
@testable import FrameLinkCore

private final class MemoryCredentials: CredentialStorage {
  var values: [String: Data] = [:]
  func read<T: Decodable>(_ name: String, as type: T.Type) throws -> T? { try values[name].map { try JSONDecoder().decode(type, from: $0) } }
  func write<T: Encodable>(_ value: T, name: String) throws { values[name] = try JSONEncoder().encode(value) }
  func erase(_ name: String) throws { values.removeValue(forKey: name) }
}

@MainActor final class ConnectionProfilesTests: XCTestCase {
  private func camera(_ host: String, _ fingerprint: String) -> CameraConfig {
    var value = CameraConfig(); value.host = host; value.fingerprint = fingerprint; value.user = "private-user-" + host; value.password = "private-password-" + host; return value
  }
  func testUnsavedCameraAndFTPCredentialsAreNotPersisted() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault); let config = camera("camera-a", "fp-a")
    try store.completeCamera(config, identity: "fp-a", remember: false)
    var ftp = FTPProfile(); ftp.host = "ftp-a"; ftp.user = "private-ftp-user"; ftp.password = "private-ftp-password"
    try store.completeFTP(ftp, remember: false)
    let restarted = ConnectionProfiles(vault: vault)
    XCTAssertTrue(try restarted.cameras().isEmpty); XCTAssertEqual(try restarted.lastCamera().password, ""); XCTAssertTrue(try restarted.cameraRequiresCredentials())
    XCTAssertEqual(try restarted.lastFTP().user, ""); XCTAssertEqual(try restarted.lastFTP().password, "")
    let snapshot = String(decoding: vault.values["connection-profiles-v1"]!, as: UTF8.self)
    for secret in [config.user, config.password, ftp.user, ftp.password] { XCTAssertFalse(snapshot.contains(secret)) }
  }
  func testMultipleIdentitiesUpdateAndForgetIndependently() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    let a = camera("same-endpoint", "fp-a"), b = camera("same-endpoint", "fp-b")
    try store.completeCamera(a, identity: "fp-a", remember: true); try store.completeCamera(b, identity: "fp-b", remember: true)
    let aID = try store.cameras().first { $0.identity == "fp-a" }!.id
    try store.forgetCamera(aID); XCTAssertEqual(try store.cameras().map(\.identity), ["fp-b"])
    var next = b; next.password = "updated"
    try store.completeCamera(next, identity: "fp-b", remember: true)
    XCTAssertEqual(try store.cameras().count, 1); XCTAssertEqual(try store.lastCamera().password, "updated")
    try store.completeCamera(next, identity: "fp-b", remember: false); XCTAssertTrue(try store.cameras().isEmpty)
  }
  func testLegacyMigrationAndUnboundIdentityUpgrade() async throws {
    let vault = MemoryCredentials(); let old = camera("legacy", "")
    try vault.write(old, name: "camera")
    var ftp = FTPProfile(); ftp.host = "legacy-ftp"; ftp.user = "legacy-user"; ftp.password = "legacy-secret"
    try vault.write(ftp, name: "ftp")
    let store = ConnectionProfiles(vault: vault)
    XCTAssertEqual(try store.lastCamera().password, old.password); XCTAssertNil(vault.values["camera"]); XCTAssertNil(vault.values["ftp"])
    var confirmed = old; confirmed.fingerprint = "confirmed"
    try store.completeCamera(confirmed, identity: "confirmed", remember: true)
    XCTAssertEqual(try store.cameras().count, 1); XCTAssertEqual(try store.ftpProfiles().count, 1)
  }
  func testReceiverProfilesDoNotPersistUnselectedCredentials() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    let a = ReceiverSettings(port: 2121, user: "receiver-a", password: "receiver-secret-a")
    let b = ReceiverSettings(port: 2122, user: "receiver-b", password: "receiver-secret-b")
    try store.completeReceiver(a, remember: true); try store.completeReceiver(b, remember: true)
    try store.forgetReceiver(a.connectionID)
    XCTAssertEqual(try store.receiverProfiles().map(\.id), [b.connectionID])
    try store.completeReceiver(b, remember: false)
    XCTAssertTrue(try store.receiverProfiles().isEmpty); XCTAssertEqual(try store.lastReceiver().password, "")
  }
  func testFTPTaskResolutionNeverFallsBackToAnotherAccount() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    var a = FTPProfile(); a.host = "server-a"; a.user = "user-a"; a.password = "secret-a"
    var b = FTPProfile(); b.host = "server-b"; b.user = "user-b"; b.password = "secret-b"
    try store.completeFTP(a, remember: true); try store.completeFTP(b, remember: true)
    XCTAssertEqual(try store.resolveFTP(a.connectionID, session: [b.connectionID: b]).host, a.host)
    try store.forgetFTP(a.connectionID)
    XCTAssertThrowsError(try store.resolveFTP(a.connectionID, session: [b.connectionID: b]))
    XCTAssertThrowsError(try store.resolveFTP(nil, session: [b.connectionID: b]))
    XCTAssertEqual(try store.resolveFTP(b.connectionID, session: [b.connectionID: b]).user, b.user)
  }
}
