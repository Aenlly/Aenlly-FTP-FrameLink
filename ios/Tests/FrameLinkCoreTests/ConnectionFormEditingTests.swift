import XCTest
@testable import FrameLinkCore

final class ConnectionFormEditingTests: XCTestCase {
  private func camera() -> CameraConfig {
    var value = CameraConfig(); value.host = "camera-a"; value.user = "user-a"; value.password = "secret-a"; value.fingerprint = "fingerprint-a"; return value
  }
  private func ftp() -> FTPProfile {
    var value = FTPProfile(); value.host = "server-a"; value.user = "user-a"; value.password = "secret-a"; return value
  }
  func testCameraEndpointEditsClearSessionCredentialsAndIdentity() {
    let original = camera()
    for edited in [ConnectionFormEditing.cameraEndpoint(original, host: "camera-b", port: original.port), ConnectionFormEditing.cameraEndpoint(original, host: original.host, port: original.port + 1)] {
      XCTAssertEqual(edited.user, ""); XCTAssertEqual(edited.password, ""); XCTAssertEqual(edited.fingerprint, "")
    }
    XCTAssertEqual(original.password, "secret-a")
  }
  func testFTPEndpointEditsClearSessionCredentialsIncludingTLSChanges() {
    let original = ftp()
    for edited in [ConnectionFormEditing.ftpEndpoint(original, host: "server-b", port: original.port, tls: original.tls), ConnectionFormEditing.ftpEndpoint(original, host: original.host, port: original.port + 1, tls: original.tls), ConnectionFormEditing.ftpEndpoint(original, host: original.host, port: original.port, tls: !original.tls)] {
      XCTAssertEqual(edited.user, ""); XCTAssertEqual(edited.password, "")
    }
  }
  func testAccountEditsClearPasswordEvenWithoutSelectedSavedProfile() {
    let camera = ConnectionFormEditing.cameraAccount(camera(), user: "new-camera-user")
    let ftp = ConnectionFormEditing.ftpAccount(ftp(), user: "new-ftp-user")
    XCTAssertEqual(camera.user, "new-camera-user"); XCTAssertEqual(camera.password, "")
    XCTAssertEqual(ftp.user, "new-ftp-user"); XCTAssertEqual(ftp.password, "")
  }
  func testUnchangedFieldDoesNotClearExplicitlySelectedProfile() {
    let camera = camera(); let ftp = ftp()
    let unchanged = ConnectionFormEditing.cameraEndpoint(camera, host: camera.host, port: camera.port)
    XCTAssertEqual(unchanged.password, camera.password); XCTAssertEqual(unchanged.fingerprint, camera.fingerprint)
    XCTAssertEqual(ConnectionFormEditing.cameraAccount(camera, user: camera.user).password, camera.password)
    XCTAssertEqual(ConnectionFormEditing.ftpEndpoint(ftp, host: ftp.host, port: ftp.port, tls: ftp.tls).password, ftp.password)
    XCTAssertEqual(ConnectionFormEditing.ftpAccount(ftp, user: ftp.user).password, ftp.password)
  }
  @MainActor func testUncheckingSavedAThenEditingDoesNotReuseCredentialsOrAffectB() async throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    let a = camera(); var b = a; b.host = "camera-b"; b.user = "user-b"; b.password = "secret-b"; b.fingerprint = "fingerprint-b"
    try store.completeCamera(a, identity: a.fingerprint, remember: true); try store.completeCamera(b, identity: b.fingerprint, remember: true)
    try store.forgetCamera(try store.cameras().first { $0.config.host == a.host }!.id)
    let edited = ConnectionFormEditing.cameraEndpoint(a, host: b.host, port: b.port)
    XCTAssertEqual(edited.password, ""); XCTAssertEqual(edited.user, ""); XCTAssertEqual(edited.fingerprint, "")
    let selectedB = try store.cameras().first { $0.config.host == b.host }!.config
    XCTAssertEqual(selectedB.password, b.password); XCTAssertEqual(selectedB.user, b.user); XCTAssertEqual(selectedB.fingerprint, b.fingerprint)
    var ftpA = ftp(); var ftpB = ftpA; ftpB.host = "server-b"; ftpB.user = "user-b"; ftpB.password = "secret-b"
    try store.completeFTP(ftpA, remember: true); try store.completeFTP(ftpB, remember: true); try store.forgetFTP(ftpA.connectionID)
    ftpA = ConnectionFormEditing.ftpAccount(ftpA, user: "changed")
    XCTAssertEqual(ftpA.password, ""); XCTAssertEqual(try store.ftpProfiles().first!.config.password, ftpB.password)
  }
}
