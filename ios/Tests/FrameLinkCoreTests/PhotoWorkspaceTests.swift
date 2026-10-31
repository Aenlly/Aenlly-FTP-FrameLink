import XCTest
import CoreGraphics
import ImageIO
@testable import FrameLinkCore

private actor PreviewSourceCounts {
  var previewCalls = 0
  var originalCalls = 0
  var rawCalls = 0
  func preview(_ url: URL) -> URL { previewCalls += 1; return url }
  func snapshot() -> [Int] { [previewCalls, originalCalls, rawCalls] }
}
private actor SuspendedPhotoURL {
  private var continuation: CheckedContinuation<URL?, Never>?
  var waiting: Bool { continuation != nil }
  func read() async -> URL? { await withCheckedContinuation { continuation = $0 } }
  func finish(_ url: URL) { continuation?.resume(returning: url); continuation = nil }
}

@MainActor final class PhotoWorkspaceTests: XCTestCase {
  private let windows = [PhotoWindow(width: 1194, height: 834), PhotoWindow(width: 834, height: 1194),
    PhotoWindow(width: 1366, height: 1024), PhotoWindow(width: 1024, height: 1366), PhotoWindow(width: 1280, height: 800),
    PhotoWindow(width: 800, height: 1280), PhotoWindow(width: 1720, height: 1060), PhotoWindow(width: 860, height: 1060),
    PhotoWindow(width: 574, height: 1060), PhotoWindow(width: 390, height: 780), PhotoWindow(width: 320, height: 240)]
  private func photo(_ id: String, size: Int64 = 0) -> Photo {
    Photo(id: id, name: id + ".JPG", source: .camera, files: [CameraFile(fileId: 1, name: id + ".JPG", size: size), CameraFile(fileId: 2, name: id + ".RAF", size: 30000000)], slot: 1, contentId: id == "A" ? 1 : 2)
  }
  func testWindowMatrixPreservesCurrentSelectionFocusToolsAndPendingTask() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root); let a = photo("A"), b = photo("B")
    try library.update(a); try library.update(b); library.selection = [a.id, b.id]
    let task = TransferTask(title: "已有任务", kind: "camera-download", photoIds: [a.id]); try library.upsertTask(task)
    let snapshot = library.photos; let selection = library.selection
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; let tasks = try encoder.encode(library.tasks)
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "camera:A", available: ["A", "B"])
    workspace.open("A"); workspace.filesInfo = true; workspace.toolsVisible = false; workspace.gridAnchor = "B"
    let key = PhotoResourceIdentity(scope: workspace.scope, photo: a)
    let focus = PhotoViewport(scale: 3.1, centerX: 0.71, centerY: 0.29); workspace.setViewport(focus, for: key)
    for _ in 0..<3 { for scale in [1.0, 1.3, 1.8] { for window in windows {
      let layout = PhotoWorkspaceLayout(window: window, detailOpen: true, textScale: scale)
      XCTAssertGreaterThan(layout.detailWidth, 0); XCTAssertLessThanOrEqual(layout.detailWidth, window.width)
      if layout.split { XCTAssertLessThanOrEqual(layout.gridWidth + layout.detailWidth + 12, window.width + 0.01) }
      let rendered = workspace.viewport(for: key).presentation(image: PhotoWindow(width: 6000, height: 4000), viewport: PhotoWindow(width: layout.detailWidth, height: max(1, window.height - 180)))
      XCTAssertGreaterThanOrEqual(rendered.offsetX, -rendered.insetX - 0.001)
      XCTAssertGreaterThanOrEqual(rendered.offsetY, -rendered.insetY - 0.001)
      XCTAssertEqual(workspace.viewport(for: key), focus)
      workspace.reconcile(scope: "camera:A", available: ["A", "B"])
      XCTAssertEqual(workspace.currentID, "A"); XCTAssertEqual(workspace.gridAnchor, "B")
      XCTAssertTrue(workspace.filesInfo); XCTAssertFalse(workspace.toolsVisible)
    } } }
    XCTAssertEqual(library.photos, snapshot); XCTAssertEqual(library.selection, selection); XCTAssertEqual(try encoder.encode(library.tasks), tasks)
    XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("previews").path))
    let matrix: [String: Any] = ["kind": "core-production-state-inputs", "windows": windows.map { [$0.width, $0.height] }, "textScales": [1, 1.3, 1.8], "repetitions": 3, "uiExecuted": false]
    print("PHOTO_WINDOW_MATRIX=" + String(decoding: try JSONSerialization.data(withJSONObject: matrix, options: [.sortedKeys]), as: UTF8.self))
  }
  func testViewportCenterRoundTripsAndGeometryClampsWithoutDiscardingIntent() {
    let image = PhotoWindow(width: 6000, height: 4000)
    let value = PhotoViewport(scale: 4, centerX: 0.65, centerY: 0.38)
    for window in windows {
      let presentation = value.presentation(image: image, viewport: window)
      let restored = presentation.normalizedCenter(viewport: window, scale: value.scale)
      XCTAssertEqual(restored.centerX, value.centerX, accuracy: 0.0001)
      XCTAssertEqual(restored.centerY, value.centerY, accuracy: 0.0001)
    }
    let edge = PhotoViewport(scale: 1, centerX: 1, centerY: 0)
    let fit = edge.presentation(image: image, viewport: PhotoWindow(width: 300, height: 900))
    XCTAssertEqual(fit.offsetX, 0); XCTAssertEqual(fit.offsetY, -fit.insetY)
    XCTAssertEqual(edge.centerX, 1); XCTAssertEqual(edge.centerY, 0)
  }
  func testComparisonHasIndependentPhotoStateAndClosingKeepsOriginalCurrent() {
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "local", available: ["A", "B", "C"]); workspace.open("C")
    let a = PhotoResourceIdentity(scope: workspace.scope, photo: photo("A")), b = PhotoResourceIdentity(scope: workspace.scope, photo: photo("B"))
    workspace.setViewport(PhotoViewport(scale: 3, centerX: 0.7, centerY: 0.2), for: a)
    workspace.setViewport(PhotoViewport(scale: 2, centerX: 0.3, centerY: 0.8), for: b)
    workspace.startComparison(["A", "B"]); workspace.filesInfo = true; workspace.toolsVisible = false
    workspace.focus(1); workspace.tapThumbnail("C")
    XCTAssertEqual(workspace.comparison, ["A", "C"]); XCTAssertEqual(workspace.currentID, "C")
    workspace.step(1, orderedIDs: ["A", "B", "C"]); XCTAssertEqual(workspace.currentID, "C")
    workspace.focus(0); workspace.tapThumbnail("C"); XCTAssertEqual(workspace.comparison, ["C", "A"])
    XCTAssertEqual(workspace.viewport(for: a).scale, 3); XCTAssertEqual(workspace.viewport(for: b).scale, 2)
    workspace.endComparison(); XCTAssertEqual(workspace.currentID, "C"); XCTAssertTrue(workspace.filesInfo); XCTAssertFalse(workspace.toolsVisible)
    workspace.close(); XCTAssertEqual(workspace.currentID, "C"); workspace.open("C")
    XCTAssertEqual(workspace.viewport(for: a).centerX, 0.7)
  }
  func testSourceAndResourceReplacementDoNotReuseOldViewportWhilePublicationDoes() {
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "camera:one", available: ["A"]); let a = photo("A", size: 100)
    let old = PhotoResourceIdentity(scope: workspace.scope, photo: a)
    workspace.setViewport(PhotoViewport(scale: 5, centerX: 0.8, centerY: 0.2), for: old)
    var published = a; published.files[0].localPath = "media/published.JPG"; published.previewPath = "previews/new.jpg"
    let same = PhotoResourceIdentity(scope: workspace.scope, photo: published)
    XCTAssertEqual(old, same); XCTAssertTrue(workspace.record(for: old) === workspace.record(for: same))
    published.files[0].size = 101
    let changed = PhotoResourceIdentity(scope: workspace.scope, photo: published)
    XCTAssertNotEqual(old, changed); XCTAssertEqual(workspace.viewport(for: changed), PhotoViewport())
    _ = workspace.record(for: changed); workspace.setViewport(PhotoViewport(scale: 6), for: old)
    XCTAssertEqual(workspace.viewport(for: changed), PhotoViewport())
    workspace.open("A"); workspace.reconcile(scope: "camera:two", available: ["A"])
    XCTAssertNil(workspace.currentID); XCTAssertFalse(workspace.isOpen)
    workspace.open("stale-id"); workspace.startComparison(["stale-id", "A"]); XCTAssertFalse(workspace.isOpen)
    let next = PhotoResourceIdentity(scope: workspace.scope, photo: a); XCTAssertEqual(workspace.viewport(for: next), PhotoViewport())
    workspace.setViewport(PhotoViewport(scale: 6), for: old); XCTAssertEqual(workspace.viewport(for: next).scale, 1)
  }
  func testColumnTargetsAndNavigationDoNotDependOnDeviceOrHeightFeedback() {
    XCTAssertGreaterThan(PhotoWorkspaceLayout.gridColumns(width: 1200, target: 108, spacing: 8, padding: 12), 3)
    XCTAssertEqual(PhotoWorkspaceLayout.gridColumns(width: 390, target: 108, spacing: 8, padding: 12), 3)
    XCTAssertEqual(PhotoWorkspaceLayout.gridColumns(width: 390, target: 68, spacing: 2, padding: 2), 5)
    XCTAssertEqual(PhotoWorkspaceLayout.gridColumns(width: 390, target: 164, spacing: 8, padding: 12), 2)
    for width in [390.0, 800, 1280] {
      XCTAssertEqual(PhotoWorkspaceLayout(window: PhotoWindow(width: width, height: 300), detailOpen: true).hideNavigation,
        PhotoWorkspaceLayout(window: PhotoWindow(width: width, height: 344), detailOpen: true).hideNavigation)
    }
    XCTAssertTrue(PhotoWorkspaceLayout(window: PhotoWindow(width: 1200, height: 600), detailOpen: false).horizontalComparison)
    XCTAssertFalse(PhotoWorkspaceLayout(window: PhotoWindow(width: 390, height: 700), detailOpen: false).horizontalComparison)
  }
  func testWindowActionsAndComparisonReusePreviewURLWithoutOriginalOrRawRequests() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("synthetic.JPG"); try jpegFixture(at: url)
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "local", available: ["A", "B"]); workspace.open("A")
    let source = PreviewSourceCounts(); let key = PhotoResourceIdentity(scope: workspace.scope, photo: photo("A"))
    for window in windows {
      _ = PhotoWorkspaceLayout(window: window, detailOpen: true)
      workspace.toolsVisible.toggle(); workspace.filesInfo.toggle(); workspace.startComparison(["A", "B"]); workspace.endComparison()
      let resolved = await workspace.previews.resolve(identity: key, representation: "preview:1") { await source.preview(url) }
      XCTAssertEqual(resolved, url)
    }
    let counts = await source.snapshot(); XCTAssertEqual(counts, [1, 0, 0])
    XCTAssertEqual(workspace.currentID, "A")
  }
  func testFormalFilePublicationRefreshesRepresentationWithoutChangingViewportOrCreatingTasks() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root); let preview = root.appendingPathComponent("synthetic.JPG"); try jpegFixture(at: preview)
    let bytes = try Data(contentsOf: preview); let a = photo("A", size: Int64(bytes.count)); try library.update(a)
    var task = TransferTask(title: "已开始的导入", kind: "camera-download", photoIds: [a.id]); task.state = .running; try library.upsertTask(task)
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "camera", available: [a.id]); workspace.open(a.id); workspace.filesInfo = true
    let key = PhotoResourceIdentity(scope: workspace.scope, photo: a); workspace.setViewport(PhotoViewport(scale: 3, centerX: 0.7, centerY: 0.4), for: key)
    let counts = PreviewSourceCounts()
    let previousRepresentation = PhotoRepresentationRevision.key(photo: a, cacheEpoch: 1)
    _ = await workspace.previews.resolve(identity: key, representation: previousRepresentation) { await counts.preview(preview) }
    let publisher = CameraImportPublication(library: library); let operation = publisher.begin(taskID: task.id)
    let result = try await publisher.transferAndPublish(operation, photo: a, file: a.files[0], copy: { target in try bytes.write(to: target); return Int64(bytes.count) }, download: { _ in XCTFail("cached JPEG must not download"); return 0 })
    let path = try XCTUnwrap(result.photo.files[0].localPath); let formal = try library.fileURL(path)
    let fresh = PhotoResourceIdentity(scope: workspace.scope, photo: result.photo)
    let nextRepresentation = PhotoRepresentationRevision.key(photo: result.photo, cacheEpoch: 1)
    XCTAssertNotEqual(previousRepresentation, nextRepresentation)
    XCTAssertNotEqual(nextRepresentation, PhotoRepresentationRevision.key(photo: result.photo, cacheEpoch: 2))
    let resolved = await workspace.previews.resolve(identity: fresh, representation: nextRepresentation) { await counts.preview(formal) }
    XCTAssertEqual(resolved, formal); XCTAssertEqual(fresh, key); XCTAssertEqual(workspace.viewport(for: fresh).centerX, 0.7)
    XCTAssertEqual(library.tasks.count, 1); XCTAssertEqual(library.tasks[0].id, task.id); XCTAssertTrue(workspace.filesInfo)
    XCTAssertNil(result.photo.files[1].localPath); XCTAssertEqual(try Data(contentsOf: formal), bytes)
  }
  func testResolverInvalidationRejectsLateOldSourceURL() async throws {
    let resolver = PhotoPreviewResolver(); let gate = SuspendedPhotoURL()
    let key = PhotoResourceIdentity(scope: "camera:old", photo: photo("A"))
    let task = Task { await resolver.resolve(identity: key, representation: "old") { await gate.read() } }
    for _ in 0..<200 { if await gate.waiting { break }; try await Task.sleep(nanoseconds: 1_000_000) }
    await resolver.invalidate(); await gate.finish(URL(fileURLWithPath: "/synthetic/old.jpg"))
    let value = await task.value; XCTAssertNil(value)
  }
  func testActualImageIODecoderIsBoundedAndSerialForSingleAndComparisonImages() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("synthetic-large.JPG")
    let context = try XCTUnwrap(CGContext(data: nil, width: 4300, height: 2900, bitsPerComponent: 8, bytesPerRow: 4300 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
    context.setFillColor(CGColor(red: 0.1, green: 0.5, blue: 0.7, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 4300, height: 2900))
    let image = try XCTUnwrap(context.makeImage()); let output = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil))
    CGImageDestinationAddImage(output, image, nil); XCTAssertTrue(CGImageDestinationFinalize(output))
    let decoder = PhotoDisplayDecoder()
    let single = await decoder.decode(url, maxPixel: PhotoDecodePolicy.maxPixel(comparing: false)); XCTAssertEqual(single?.width, 4096)
    let widths = await withTaskGroup(of: Int.self, returning: [Int].self) { group in
      for _ in 0..<6 { group.addTask { await decoder.decode(url, maxPixel: PhotoDecodePolicy.maxPixel(comparing: true))?.width ?? 0 } }
      var values: [Int] = []; for await value in group { values.append(value) }; return values
    }
    XCTAssertEqual(widths, Array(repeating: 2048, count: 6)); XCTAssertEqual(decoder.statistics().peakConcurrent, 1)
    XCTAssertEqual(decoder.statistics().largestDecodedEdge, 4096); XCTAssertEqual(PhotoDecodePolicy.concurrentDecodes, 1)
    let thumbnail = await PhotoDisplayDecoder.thumbnails.decode(url, maxPixel: 8192); XCTAssertEqual(thumbnail?.width, 720)
    XCTAssertEqual(PhotoDecodePolicy.thumbnailPixel(width: 68, height: 68, displayScale: 2, large: false), 192)
    XCTAssertLessThan(PhotoDecodePolicy.thumbnailPixel(width: 68, height: 68, displayScale: 2, large: false), 720)
    let cancelled = Task { await decoder.decode(url, maxPixel: 8192) }; cancelled.cancel(); let result = await cancelled.value; XCTAssertNil(result)
    let metrics: [String: Any] = ["sourcePixels": [4300, 2900], "singlePixels": [single?.width ?? 0, single?.height ?? 0], "comparisonWidths": widths,
      "thumbnailPixels": [thumbnail?.width ?? 0, thumbnail?.height ?? 0], "detailPeakConcurrent": decoder.statistics().peakConcurrent,
      "thumbnailPeakConcurrent": PhotoDisplayDecoder.thumbnails.statistics().peakConcurrent, "uiExecuted": false]
    print("PHOTO_DECODE_METRICS=" + String(decoding: try JSONSerialization.data(withJSONObject: metrics, options: [.sortedKeys]), as: UTF8.self))
  }
  func testVisitedPhotoViewportsAreNotEvictedByBrowsingManyPhotos() {
    let workspace = PhotoWorkspace(); let ids = Set((0..<100).map { "p\($0)" }); workspace.reconcile(scope: "local", available: ids)
    let first = PhotoResourceIdentity(scope: workspace.scope, photo: photo("p0"))
    workspace.setViewport(PhotoViewport(scale: 3.5, centerX: 0.7, centerY: 0.3), for: first)
    for i in 1..<100 { workspace.setViewport(PhotoViewport(scale: 2), for: PhotoResourceIdentity(scope: workspace.scope, photo: photo("p\(i)"))) }
    XCTAssertEqual(workspace.viewport(for: first).scale, 3.5)
    workspace.reconcile(scope: "local", available: ["p0"])
    XCTAssertEqual(workspace.viewport(for: first).centerX, 0.7)
    XCTAssertEqual(workspace.viewport(for: PhotoResourceIdentity(scope: workspace.scope, photo: photo("p1"))).scale, 1)
  }
  func testCancellingOnePreviewWaiterDoesNotDiscardOtherVisiblePane() async throws {
    let resolver = PhotoPreviewResolver(); let gate = SuspendedPhotoURL()
    let key = PhotoResourceIdentity(scope: "local", photo: photo("A"))
    let first = Task { await resolver.resolve(identity: key, representation: "same") { await gate.read() } }
    for _ in 0..<200 { if await gate.waiting { break }; try await Task.sleep(nanoseconds: 1_000_000) }
    let second = Task { await resolver.resolve(identity: key, representation: "same") { XCTFail("duplicate preview read"); return nil } }
    for _ in 0..<200 { if await resolver.activeWaiters() == 2 { break }; try await Task.sleep(nanoseconds: 1_000_000) }
    let joined = await resolver.activeWaiters(); XCTAssertEqual(joined, 2)
    first.cancel(); let url = URL(fileURLWithPath: "/synthetic/current.jpg"); await gate.finish(url)
    let cancelled = await first.value; let current = await second.value
    XCTAssertNil(cancelled); XCTAssertEqual(current, url)
  }
}
