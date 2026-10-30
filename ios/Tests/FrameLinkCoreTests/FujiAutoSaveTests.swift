import XCTest
@testable import FrameLinkCore

private actor FakeFujiReceiver: FujiReceiverInstance {
  var starts = 0; var waits = 0; var closes = 0
  let holdStart: Bool; let ignoreClose: Bool; let failWait: Bool
  var startGate: CheckedContinuation<Void, Never>?
  var invitationGate: CheckedContinuation<String, Error>?
  init(holdStart: Bool = false, ignoreClose: Bool = false, failWait: Bool = false) {
    self.holdStart = holdStart; self.ignoreClose = ignoreClose; self.failWait = failWait
  }
  func start(localIPv4: String, importerName: String) async throws {
    starts += 1
    if holdStart { await withCheckedContinuation { startGate = $0 } }
  }
  func nextInvitation(timeoutMilliseconds: Int) async throws -> String {
    waits += 1
    if failWait { throw CancellationError() }
    return try await withCheckedThrowingContinuation { invitationGate = $0 }
  }
  func close() async {
    closes += 1
    if !ignoreClose { invitationGate?.resume(throwing: CancellationError()); invitationGate = nil }
  }
  func releaseStart() { startGate?.resume(); startGate = nil }
  func deliver(_ raw: String) { invitationGate?.resume(returning: raw); invitationGate = nil }
  func counts() -> (Int, Int, Int) { (starts, waits, closes) }
}

@MainActor final class FujiAutoSaveTests: XCTestCase {
  private func config() -> CameraConfig {
    var c = CameraConfig(); c.protocolID = CameraProtocol.fuji; c.experimentalFuji = true
    c.host = "127.0.0.1"; c.port = 55740; c.fujiDeviceName = "SYNTHETIC-UNKNOWN-MODEL"
    c.user = "old-user"; c.password = "old-password"; c.fingerprint = "old-fingerprint"
    return c
  }
  private func invitation(_ config: CameraConfig? = nil) throws -> String {
    var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(config ?? self.config())) as! [String: Any]
    object["mediaScope"] = "AUTOSAVE_PENDING_BATCH"
    return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
  }
  private func until(_ predicate: () async -> Bool) async {
    for _ in 0..<200 { if await predicate() { return }; try? await Task.sleep(nanoseconds: 5_000_000) }
    XCTFail("receiver lifecycle did not reach expected state")
  }
  func testLegacyConfigDoesNotEnableFujiAndProtocolSwitchClearsSecrets() throws {
    let old = try JSONDecoder().decode(CameraConfig.self, from: Data(#"{"host":"camera"}"#.utf8))
    XCTAssertEqual(old.protocolID, CameraProtocol.sony); XCTAssertFalse(old.experimentalFuji)
    let changed = ConnectionFormEditing.cameraProtocol(config(), protocolID: CameraProtocol.sony)
    XCTAssertFalse(changed.experimentalFuji); XCTAssertEqual(changed.fujiDeviceName, "")
    XCTAssertEqual(changed.user, ""); XCTAssertEqual(changed.password, ""); XCTAssertEqual(changed.fingerprint, "")
  }
  func testExplicitOptInAndImportScopeRequiredAndCredentialsNeverEncoded() throws {
    var c = config(); c.experimentalFuji = false
    XCTAssertThrowsError(try FujiAutoSaveController.decodeInvitation(invitation(c)))
    c = config(); c.fujiDeviceName = ""
    XCTAssertThrowsError(try FujiAutoSaveController.decodeInvitation(invitation(c)))
    c = config(); c.protocolID = CameraProtocol.sony
    XCTAssertThrowsError(try FujiAutoSaveController.decodeInvitation(invitation(c)))
    XCTAssertThrowsError(try FujiAutoSaveController.decodeInvitation(invitation().replacingOccurrences(of: "AUTOSAVE_PENDING_BATCH", with: "REGISTER")))
    let safe = try FujiAutoSaveController.decodeInvitation(invitation())
    XCTAssertEqual(safe.user, ""); XCTAssertEqual(safe.password, ""); XCTAssertEqual(safe.fingerprint, ""); XCTAssertEqual(safe.guid, "")
    XCTAssertFalse(CameraConnectionPolicy.canReconnect(safe))
  }
  func testFujiCannotReplaceSonyRecentProfileOrPersistPassword() throws {
    let vault = MemoryCredentials(); let store = ConnectionProfiles(vault: vault)
    var sony = CameraConfig(); sony.host = "sony"; sony.user = "sony-user"; sony.password = "sony-secret"; sony.fingerprint = "sony-fp"
    try store.completeCamera(sony, identity: "sony-id", remember: true)
    let before = vault.values
    XCTAssertThrowsError(try store.completeCamera(config(), identity: "fuji-id", remember: true))
    XCTAssertEqual(vault.values, before); XCTAssertEqual(try store.lastCamera().host, "sony")
  }
  func testDefaultAndRejectedStartDoNotAllocateReceiver() async {
    var made = 0
    let controller = FujiAutoSaveController(make: { made += 1; return FakeFujiReceiver() })
    XCTAssertEqual(controller.state.phase, .disabled); XCTAssertEqual(made, 0)
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: false)
    await controller.waitUntilClosed()
    XCTAssertEqual(made, 0); XCTAssertEqual(controller.state.phase, .failed)
  }
  func testImportInvitationClosesListenerAndCanOnlyBeConsumedOnce() async throws {
    let receiver = FakeFujiReceiver()
    let controller = FujiAutoSaveController(make: { receiver })
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true)
    await until { await receiver.counts().1 == 1 }
    await receiver.deliver(try invitation())
    await until { controller.state.phase == .invited }
    let counts = await receiver.counts(); XCTAssertGreaterThan(counts.2, 0)
    XCTAssertEqual(controller.takeInvitation()?.fujiDeviceName, "SYNTHETIC-UNKNOWN-MODEL")
    XCTAssertNil(controller.takeInvitation())
  }
  func testStopDiscardsLateInvitationAndRestartUsesFreshInstance() async throws {
    let first = FakeFujiReceiver(ignoreClose: true); let second = FakeFujiReceiver()
    var made = 0
    let controller = FujiAutoSaveController(make: { made += 1; return made == 1 ? first : second })
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true)
    await until { await first.counts().1 == 1 }
    controller.stop(); await controller.waitUntilClosed()
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true)
    await until { await second.counts().1 == 1 }
    await first.deliver(try invitation())
    try? await Task.sleep(nanoseconds: 20_000_000)
    XCTAssertNil(controller.invitation); XCTAssertEqual(controller.state.phase, .listening); XCTAssertEqual(made, 2)
    controller.stop(); await controller.waitUntilClosed()
    XCTAssertEqual(controller.state.phase, .disabled)
  }
  func testExpiredInvitationCannotConnectToALaterBatch() async throws {
    let receiver = FakeFujiReceiver(); var time = Date(timeIntervalSince1970: 100)
    let controller = FujiAutoSaveController(make: { receiver }, now: { time })
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true)
    await until { await receiver.counts().1 == 1 }
    await receiver.deliver(try invitation()); await until { controller.state.phase == .invited }
    time = time.addingTimeInterval(31)
    XCTAssertNil(controller.takeInvitation()); XCTAssertNil(controller.invitation); XCTAssertEqual(controller.state.phase, .failed)
  }
  func testStopDuringNativeStartClosesAgainAfterLateStartCompletes() async {
    let receiver = FakeFujiReceiver(holdStart: true)
    let controller = FujiAutoSaveController(make: { receiver })
    controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true)
    await until { await receiver.counts().0 == 1 }
    controller.stop(); await controller.waitUntilClosed(); await receiver.releaseStart()
    await until { await receiver.counts().2 >= 2 }
    let counts = await receiver.counts(); XCTAssertEqual(counts.1, 0); XCTAssertNil(controller.invitation)
  }
  func testTimeoutAndInvalidInvitationCloseWithoutPublishingConfig() async throws {
    for fail in [true, false] {
      let receiver = FakeFujiReceiver(failWait: fail)
      let controller = FujiAutoSaveController(make: { receiver })
      controller.start(localIPv4: "127.0.0.1", importerName: "QA", experimental: true, timeoutMilliseconds: 1)
      if !fail { await until { await receiver.counts().1 == 1 }; await receiver.deliver("{}") }
      await until { controller.state.phase == .failed }
      XCTAssertNil(controller.invitation); let counts = await receiver.counts(); XCTAssertGreaterThan(counts.2, 0)
    }
  }
  func testFujiUnknownModelFirmwareAndNoPreviewHaveExplicitCapabilityBoundary() throws {
    let raw = #"{"id":"session-unique","model":"unknown","firmware":"","manufacturer":"Fujifilm","mediaScope":"AUTOSAVE_PENDING_BATCH","identityScope":"SESSION_BATCH","canBrowse":true,"canDownload":true,"canPreview":true,"canCapture":true,"canLiveView":true}"#
    let info = try JSONDecoder().decode(CameraInfo.self, from: Data(raw.utf8))
    try CameraConnectionPolicy.validate(info: info, config: config())
    let caps = CameraConnectionPolicy.capabilities(info: info, protocolID: CameraProtocol.fuji)
    XCTAssertTrue(caps.canDownload); XCTAssertFalse(caps.canPreview); XCTAssertFalse(caps.canCapture); XCTAssertFalse(caps.canLiveView); XCTAssertFalse(caps.canSetProperties)
    let missing = try JSONDecoder().decode(CameraInfo.self, from: Data(raw.replacingOccurrences(of: ",\"identityScope\":\"SESSION_BATCH\"", with: "").utf8))
    XCTAssertThrowsError(try CameraConnectionPolicy.validate(info: missing, config: config()))
  }
  func testNewSessionNamespaceDoesNotRestoreOldCacheImportTaskOrSelection() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    let saved = try library.fileURL("media/saved.jpg")
    try FileManager.default.createDirectory(at: saved.deletingLastPathComponent(), withIntermediateDirectories: true)
    try jpegFixture(at: saved)
    let bytes = try Data(contentsOf: saved)
    let remoteFile = CameraFile(fileId: 1, name: "DSCF0001.JPG", size: Int64(bytes.count))
    var old = Photo(id: "old-session:fuji:object", name: remoteFile.name, source: .camera, files: [remoteFile], contentId: 1)
    let cache = try JPEGPreviewCache(root: root.appendingPathComponent("previews"))
    let resource = JPEGPreviewResource(photo: old, file: remoteFile)
    let ticket = try await cache.begin(resource); try bytes.write(to: ticket.url)
    _ = try await cache.commit(ticket, bytes: Int64(bytes.count), sha256: LibraryStore.digest(saved))
    old.files[0].localPath = "media/saved.jpg"; old.favorite = true
    try library.update(old); library.selection = [old.id]
    let task = TransferTask(title: "Old pending batch", kind: "camera-download", photoIds: [old.id]); try library.upsertTask(task)
    let next = RemotePhoto(id: "new-session:fuji:object", contentId: 1, slot: 1, capturedAt: 0, files: [remoteFile])
    try library.mergeCamera(CameraPage(items: [next], nextCursor: 0, mediaScope: "AUTOSAVE_PENDING_BATCH"))
    let photo = try XCTUnwrap(library.photos.first { $0.id == next.id })
    XCTAssertFalse(photo.imported); XCTAssertFalse(photo.favorite); XCTAssertFalse(library.selection.contains(photo.id))
    let cached = try await cache.lookup(JPEGPreviewResource(photo: photo, file: remoteFile)); XCTAssertNil(cached)
    XCTAssertEqual(library.tasks.first?.photoIds, [old.id]); XCTAssertEqual(library.tasks.first?.id, task.id)
    XCTAssertEqual(try Data(contentsOf: saved), bytes); XCTAssertTrue(library.photos.contains { $0.id == old.id && $0.imported })
  }
}
