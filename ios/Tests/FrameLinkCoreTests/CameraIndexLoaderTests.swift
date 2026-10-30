import XCTest
@testable import FrameLinkCore

@MainActor private final class MetadataPages {
  var calls: [(String, Int64)] = []
  var total: Int? = 250
  var pendingScope: String?
  var pausedCursor: Int64?
  var pausedDevice: String?
  var badCursor = false
  var failCursor: Int64?
  let started = XCTestExpectation(description: "metadata page suspended")
  private var continuation: CheckedContinuation<Void, Never>?
  private var hasPaused = false
  var pausedReply: CameraPage?
  func fetch(_ scope: CameraIndexScope, _ cursor: Int64) async throws -> CameraPage {
    calls.append((scope.deviceID, cursor))
    if !hasPaused && pausedCursor == cursor && (pausedDevice == nil || pausedDevice == scope.deviceID) {
      hasPaused = true
      await withCheckedContinuation { continuation = $0; started.fulfill() }
      if let pausedReply { return pausedReply }
    }
    if failCursor == cursor { throw URLError(.networkConnectionLost) }
    if badCursor && cursor == 100 { return page(0..<100, next: 100, scope: scope) }
    let start = Int(cursor); let end = min(start + 100, 250)
    return page(start..<end, next: end == 250 ? 0 : Int64(end), scope: scope)
  }
  func resume() { continuation?.resume(); continuation = nil }
  func page(_ range: Range<Int>, next: Int64, scope: CameraIndexScope) -> CameraPage {
    CameraPage(items: range.map { item in
      RemotePhoto(id: "\(scope.deviceID):\(scope.slot):\(item)", contentId: Int64(item), slot: scope.slot, capturedAt: Int64(1_800_000_000_000 - item * 1000), files: [CameraFile(fileId: 1, name: "IMG_\(item).JPG"), CameraFile(fileId: 2, name: "IMG_\(item).ARW")])
    }, nextCursor: next, totalCount: total, mediaScope: pendingScope)
  }
}

@MainActor final class CameraIndexLoaderTests: XCTestCase {
  @MainActor private final class QueuedAction {
    let queued = XCTestExpectation(description: "automatic action queued before failure")
    private var continuation: CheckedContinuation<Void, Never>?
    func wait() async { await withCheckedContinuation { continuation = $0; queued.fulfill() } }
    func release() { continuation?.resume(); continuation = nil }
  }
  private func library() -> LibraryStore { LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("index-" + UUID().uuidString)) }
  func testFirstHundredExposeTotalAndBackgroundFillsWithoutScrollingOrImageTransfers() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); let scope = CameraIndexScope(deviceID: "camera")
    let first = service.page(0..<1, next: 0, scope: scope).items[0].photo()
    var saved = first; saved.files[0].localPath = "media/already-imported.jpg"; saved.favorite = true; saved.previewPath = "previews/already.jpg"
    try library.update(saved); library.selection = [saved.id]
    let task = TransferTask(title: "existing task", kind: "camera-download"); try library.upsertTask(task)
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: scope); try await loader.loadNext()
    XCTAssertEqual(loader.state.ids.count, 100); XCTAssertEqual(loader.state.totalCount, 250)
    XCTAssertEqual(loader.state.countText(matched: 100, filtered: false).title, "共 250 张")
    await loader.startBackground()?.value
    XCTAssertTrue(loader.state.complete); XCTAssertEqual(loader.state.ids.count, 250)
    XCTAssertEqual(service.calls.map(\.1), [0, 100, 200]); XCTAssertEqual(library.photos.count, 250)
    XCTAssertEqual(library.photos.first { $0.id == saved.id }?.files[0].localPath, saved.files[0].localPath)
    XCTAssertEqual(library.photos.first { $0.id == saved.id }?.previewPath, saved.previewPath)
    XCTAssertTrue(library.photos.first { $0.id == saved.id }!.favorite)
    XCTAssertEqual(library.selection, [saved.id]); XCTAssertEqual(library.tasks.map(\.id), [task.id])
    XCTAssertFalse(FileManager.default.fileExists(atPath: library.root.appendingPathComponent("previews").path))
  }
  func testBackgroundSelectAllAndScrollingJoinOneCursorFlight() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.pausedCursor = 100
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "camera")); try await loader.loadNext()
    let background = loader.startBackground(); await fulfillment(of: [service.started], timeout: 3)
    let selectionStarted = XCTestExpectation(description: "select all joining")
    let scrollStarted = XCTestExpectation(description: "scroll joining")
    let selection = Task { selectionStarted.fulfill(); try await loader.fillAll() }
    let scroll = Task { scrollStarted.fulfill(); try await loader.loadNext() }
    await fulfillment(of: [selectionStarted, scrollStarted], timeout: 3); service.resume()
    try await selection.value; try await scroll.value; await background?.value
    XCTAssertEqual(service.calls.map(\.1), [0, 100, 200]); XCTAssertTrue(loader.state.complete)
  }
  func testLatePageFromOldDeviceOrSourceCannotRestoreOldIDsOrTotals() async throws {
    let initial = CameraIndexScope(deviceID: "camera")
    for replacement in [initial, CameraIndexScope(deviceID: "camera", source: 0), CameraIndexScope(deviceID: "camera", slot: 2), CameraIndexScope(deviceID: "camera", day: "2026-10-31"), CameraIndexScope(deviceID: "new")] {
      let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
      let service = MetadataPages(); service.pausedCursor = 0
      service.pausedReply = CameraPage(items: [RemotePhoto(id: "stale-old-snapshot", contentId: 999, slot: 1, capturedAt: 0, files: [])], nextCursor: 0, totalCount: 1)
      let loader = CameraIndexLoader(library: library, fetch: service.fetch); loader.reset(scope: initial)
      let old = Task { try await loader.loadNext() }; await fulfillment(of: [service.started], timeout: 3)
      loader.reset(scope: replacement); try await loader.loadNext(); service.resume()
      do { try await old.value; XCTFail("late page accepted") } catch is CancellationError {}
      XCTAssertEqual(loader.state.scope, replacement); XCTAssertEqual(loader.state.ids.count, 100); XCTAssertEqual(loader.state.totalCount, 250)
      XCTAssertFalse(library.photos.contains { $0.id == "stale-old-snapshot" })
    }
  }
  func testMissingTotalDoesNotTurnFirstPageCountIntoFullCount() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.total = nil
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "camera")); try await loader.loadNext()
    XCTAssertNil(loader.state.totalCount); XCTAssertFalse(loader.state.complete)
    XCTAssertEqual(loader.state.countText(matched: 100, filtered: false).title, "已读取 100 张")
    XCTAssertTrue(loader.state.countText(matched: 100, filtered: false).detail.contains("总数未知"))
    try await loader.fillAll(); XCTAssertEqual(loader.state.countText(matched: 250, filtered: false).title, "共 250 张")
  }
  func testNoProgressStopsBackgroundAndExplicitRetryResumesWithoutDuplicates() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.badCursor = true
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "camera")); await loader.startBackground()?.value
    XCTAssertEqual(service.calls.map(\.1), [0, 100]); XCTAssertNotNil(loader.state.error); XCTAssertNil(loader.state.totalCount)
    XCTAssertEqual(loader.state.ids.count, 100); XCTAssertNil(loader.startBackground())
    service.badCursor = false; try await loader.fillAll(retry: true)
    XCTAssertEqual(service.calls.map(\.1), [0, 100, 100, 200]); XCTAssertEqual(loader.state.ids.count, 250)
  }
  func testQueuedAutomaticAppIntentCannotRetryFailurePublishedBeforeItsExecution() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.pausedCursor = 100; service.failCursor = 100
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "camera")); try await loader.requestNext(intent: .automatic)
    let background = loader.startBackground(); await fulfillment(of: [service.started], timeout: 3)
    // The same typed entry is called by AppModel; this action was scheduled while error was still nil.
    let action = QueuedAction()
    let automatic = Task { await action.wait(); try await loader.requestNext(intent: .automatic) }
    await fulfillment(of: [action.queued], timeout: 3)
    XCTAssertNil(loader.state.error)
    service.resume(); await background?.value
    let failure = try XCTUnwrap(loader.state.error)
    XCTAssertEqual(service.calls.map(\.1), [0, 100])
    action.release()
    do { try await automatic.value; XCTFail("queued automatic action retried") } catch CameraIndexError.stopped {}
    XCTAssertEqual(service.calls.map(\.1), [0, 100]); XCTAssertEqual(loader.state.error, failure)
    XCTAssertNil(loader.state.totalCount); XCTAssertEqual(loader.state.ids.count, 100)
    service.failCursor = nil
    try await loader.requestNext(intent: .userRetry); await loader.startBackground()?.value
    XCTAssertEqual(service.calls.map(\.1), [0, 100, 100, 200]); XCTAssertTrue(loader.state.complete)
  }
  func testFailureInvalidatesOldCountAndDisconnectDropsAllIndexState() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.failCursor = 100
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "camera")); await loader.startBackground()?.value
    XCTAssertNil(loader.state.totalCount); XCTAssertNotNil(loader.state.error); XCTAssertEqual(service.calls.count, 2)
    loader.invalidate(); XCTAssertTrue(loader.state.ids.isEmpty); XCTAssertNil(loader.state.totalCount); XCTAssertNil(loader.state.scope); XCTAssertFalse(loader.state.hasMore)
    XCTAssertEqual(loader.state.countText(matched: 0, filtered: false).title, "相机未连接")
  }
  func testPendingBatchAndPartialLocalFiltersNeverClaimWholeCardCounts() async throws {
    let library = library(); defer { try? FileManager.default.removeItem(at: library.root) }
    let service = MetadataPages(); service.pendingScope = "AUTOSAVE_PENDING_BATCH"
    let loader = CameraIndexLoader(library: library, fetch: service.fetch)
    loader.reset(scope: CameraIndexScope(deviceID: "fuji", mediaScope: "AUTOSAVE_PENDING_BATCH"))
    XCTAssertEqual(loader.state.rangeLabel, "待传批次")
    try await loader.loadNext()
    let count = loader.state.countText(matched: 12, filtered: true)
    XCTAssertEqual(count.title, "已匹配 12 张"); XCTAssertTrue(count.detail.contains("待传批次")); XCTAssertTrue(count.detail.contains("范围共250张"))
    XCTAssertFalse(count.detail.contains("全卡")); XCTAssertFalse(loader.state.complete)
    try await loader.fillAll(); XCTAssertEqual(loader.state.countText(matched: 12, filtered: true).title, "12 张")
  }
  func testTotalDecoderIsCompatibleWithMissingNegativeAndInvalidCount() throws {
    for extra in ["", ",\"totalCount\":-1", ",\"totalCount\":\"unknown\""] {
      let data = Data(("{\"items\":[],\"nextCursor\":100" + extra + "}").utf8)
      XCTAssertNil(try JSONDecoder().decode(CameraPage.self, from: data).totalCount)
    }
    let page = try JSONDecoder().decode(CameraPage.self, from: Data("{\"items\":[],\"nextCursor\":0,\"totalCount\":0}".utf8))
    XCTAssertEqual(page.totalCount, 0)
  }
}
