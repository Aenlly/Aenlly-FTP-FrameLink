import XCTest
import CoreGraphics
import ImageIO
@testable import FrameLinkCore

private actor PrecisionPreviewCalls {
  var count = 0
  func read(_ url: URL) -> URL { count += 1; return url }
}

@MainActor final class PhotoBrowsePrecisionTests: XCTestCase {
  private func photo(_ id: String, favorite: Bool = false) -> Photo {
    var value = Photo(id: id, name: id + ".JPG", source: .camera, files: [CameraFile(fileId: 1, name: id + ".JPG")])
    value.favorite = favorite; return value
  }
  private func filtered(_ input: [Photo], _ filter: GalleryPhotoFilter, selection: Set<String> = []) -> [Photo] {
    input.filter { filter.matches($0, selection: selection) }
  }
  func testFavoriteGridInputKeepsPagingThumbnailAndCompareInsideFilterAcrossWindows() {
    let candidates = [photo("A", favorite: true), photo("B"), photo("C", favorite: true)]
    let photos = filtered(candidates, .favorites)
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "gallery", available: Set(candidates.map(\.id)), browsing: photos.map(\.id)); workspace.open("A")
    for window in [PhotoWindow(width: 1194, height: 834), PhotoWindow(width: 390, height: 780), PhotoWindow(width: 574, height: 1060)] {
      _ = PhotoWorkspaceLayout(window: window, detailOpen: workspace.isOpen)
      XCTAssertEqual(workspace.detailIDs, ["A", "C"])
      workspace.step(1, orderedIDs: candidates.map(\.id)); XCTAssertEqual(workspace.currentID, "C")
      workspace.step(-1, orderedIDs: photos.map(\.id)); XCTAssertEqual(workspace.currentID, "A")
      workspace.tapThumbnail("B"); XCTAssertEqual(workspace.currentID, "A")
      workspace.startComparison(["A", "B"]); XCTAssertTrue(workspace.comparison.isEmpty)
      workspace.startComparison(photos.map(\.id)); XCTAssertEqual(workspace.comparison, ["A", "C"])
      workspace.tapThumbnail("B"); XCTAssertEqual(workspace.comparison, ["A", "C"])
      workspace.endComparison(); XCTAssertEqual(workspace.currentID, "A")
    }
  }
  func testCurrentThatStopsMatchingIsPinnedAloneUntilNavigation() {
    var candidates = [photo("A", favorite: true), photo("B"), photo("C", favorite: true)]
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "gallery", available: Set(candidates.map(\.id)), browsing: filtered(candidates, .favorites).map(\.id)); workspace.open("A")
    candidates[0].favorite = false
    workspace.reconcile(scope: "gallery", available: Set(candidates.map(\.id)), browsing: filtered(candidates, .favorites).map(\.id))
    XCTAssertTrue(workspace.currentOutsideFilter); XCTAssertEqual(workspace.detailIDs, ["A", "C"])
    workspace.startComparison(["A", "B"]); XCTAssertTrue(workspace.comparison.isEmpty)
    workspace.step(1, orderedIDs: workspace.detailIDs); XCTAssertEqual(workspace.currentID, "C")
    XCTAssertFalse(workspace.currentOutsideFilter); XCTAssertEqual(workspace.detailIDs, ["C"])
    workspace.tapThumbnail("A"); XCTAssertEqual(workspace.currentID, "C")
  }
  func testOtherProductionFiltersKeepTheOriginalRangeAndOrder() {
    var a = photo("A"), b = photo("B"), c = photo("C")
    a.files[0].localPath = "media/A.JPG"
    b.files = [CameraFile(fileId: 2, name: "B.RAF")]
    c.files.append(CameraFile(fileId: 2, name: "C.RAF", localPath: "media/C.RAF"))
    let input = [c, a, b]
    XCTAssertEqual(filtered(input, .selected, selection: ["A", "C"]).map(\.id), ["C", "A"])
    XCTAssertEqual(filtered(input, .raw).map(\.id), ["C", "B"])
    XCTAssertEqual(filtered(input, .unimported).map(\.id), ["C", "B"])
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "record", available: Set(input.map(\.id)), browsing: input.map(\.id)); workspace.open("C")
    workspace.step(1, orderedIDs: input.map(\.id)); XCTAssertEqual(workspace.currentID, "A")
    let legacy = PhotoWorkspace(); legacy.reconcile(scope: "legacy", available: Set(input.map(\.id))); legacy.open("C")
    legacy.step(1, orderedIDs: input.map(\.id)); XCTAssertEqual(legacy.currentID, "A")
  }
  func testActualDisplaySourceClassifierRequiresResolvedOriginalNotRawImportedFlagOrName() {
    let root = URL(fileURLWithPath: "/synthetic/library")
    let preview = root.appendingPathComponent("looks-like-original.JPG"), original = root.appendingPathComponent("media/A.JPG"), temporary = root.appendingPathComponent("cache/A.JPG")
    var value = photo("A"); value.files = [CameraFile(fileId: 2, name: "A.RAF", localPath: "media/A.RAF")]
    let fileURL: (String) -> URL? = { root.appendingPathComponent($0) }
    XCTAssertTrue(value.imported)
    XCTAssertEqual(PhotoDisplaySource.classify(photo: value, resolved: preview, fileURL: fileURL, verifiedTemporaryJPEG: temporary), .preview)
    XCTAssertEqual(PhotoDisplaySource.classify(photo: value, resolved: temporary, fileURL: fileURL, verifiedTemporaryJPEG: temporary), .preview)
    value.files.append(CameraFile(fileId: 1, name: "A.JPG", localPath: "media/A.JPG"))
    XCTAssertEqual(PhotoDisplaySource.classify(photo: value, resolved: original, fileURL: fileURL, verifiedTemporaryJPEG: temporary), .localOriginal)
    XCTAssertEqual(PhotoDisplaySource.classify(photo: value, resolved: temporary, fileURL: fileURL, verifiedTemporaryJPEG: temporary), .temporaryJPEGOriginal)
    XCTAssertEqual(PhotoDisplaySource.classify(photo: value, resolved: preview, fileURL: fileURL, verifiedTemporaryJPEG: temporary), .preview)
    XCTAssertEqual(PhotoDecodePolicy.maxPixel(comparing: false, source: .preview), 4096)
    XCTAssertEqual(PhotoDecodePolicy.maxPixel(comparing: false, source: .localOriginal), 8192)
    XCTAssertEqual(PhotoDecodePolicy.maxPixel(comparing: true, source: .localOriginal), 2048)
    XCTAssertEqual(PhotoDecodePolicy.maxPixel(comparing: true, source: .temporaryJPEGOriginal), 2048)
  }
  func testRealImageIOKeepsMoreThan4096ForExplicitOriginalWhileComparisonStaysBounded() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: root) }
    let original = root.appendingPathComponent("original.JPG")
    try autoreleasepool {
      let context = try XCTUnwrap(CGContext(data: nil, width: 5000, height: 3200, bitsPerComponent: 8, bytesPerRow: 5000 * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
      context.setFillColor(CGColor(gray: 0, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 5000, height: 3200))
      context.setFillColor(CGColor(gray: 1, alpha: 1))
      for x in stride(from: 0, to: 5000, by: 4) { context.fill(CGRect(x: x, y: 0, width: 2, height: 3200)) }
      let image = try XCTUnwrap(context.makeImage()); let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(original as CFURL, "public.jpeg" as CFString, 1, nil))
      CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.98] as CFDictionary); XCTAssertTrue(CGImageDestinationFinalize(destination))
    }
    let temporary = root.appendingPathComponent("temporary.JPG"); try FileManager.default.copyItem(at: original, to: temporary)
    var value = photo("A"); value.files[0].localPath = "original.JPG"
    let fileURL: (String) -> URL? = { root.appendingPathComponent($0) }
    let localKind = PhotoDisplaySource.classify(photo: value, resolved: original, fileURL: fileURL, verifiedTemporaryJPEG: temporary)
    let temporaryKind = PhotoDisplaySource.classify(photo: value, resolved: temporary, fileURL: fileURL, verifiedTemporaryJPEG: temporary)
    let decoder = PhotoDisplayDecoder()
    let previewWidth = await decoder.decode(original, maxPixel: PhotoDecodePolicy.maxPixel(comparing: false, source: .preview))?.width
    let localWidth = await decoder.decode(original, maxPixel: PhotoDecodePolicy.maxPixel(comparing: false, source: localKind))?.width
    let temporaryWidth = await decoder.decode(temporary, maxPixel: PhotoDecodePolicy.maxPixel(comparing: false, source: temporaryKind))?.width
    XCTAssertEqual(previewWidth, 4096); XCTAssertEqual(localWidth, 5000); XCTAssertEqual(temporaryWidth, 5000)
    let comparison = await withTaskGroup(of: Int.self, returning: [Int].self) { group in
      for kind in [localKind, temporaryKind] { group.addTask { await decoder.decode(original, maxPixel: PhotoDecodePolicy.maxPixel(comparing: true, source: kind))?.width ?? 0 } }
      var values: [Int] = []; for await value in group { values.append(value) }; return values
    }
    XCTAssertEqual(comparison, [2048, 2048]); XCTAssertEqual(decoder.statistics().peakConcurrent, 1)
    let metrics: [String: Any] = ["sourcePixels": [5000, 3200], "ordinaryPreviewWidth": previewWidth ?? 0, "localOriginalWidth": localWidth ?? 0,
      "temporaryJPEGOriginalWidth": temporaryWidth ?? 0, "singleOriginalLimit": 8192, "comparisonWidths": comparison,
      "detailPeakConcurrent": decoder.statistics().peakConcurrent, "uiExecuted": false, "peakMemoryMeasured": false]
    print("PHOTO_PRECISION_METRICS=" + String(decoding: try JSONSerialization.data(withJSONObject: metrics, options: [.sortedKeys]), as: UTF8.self))
  }
  func testPrecisionModeSwitchReusesLocalFileAndDoesNotChangeTaskSelectionOrViewport() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString); let library = LibraryStore(root: root)
    defer { try? FileManager.default.removeItem(at: root) }
    let url = root.appendingPathComponent("cached.JPG"); try jpegFixture(at: url)
    var value = photo("A"); value.files[0].localPath = "cached.JPG"; try library.update(value); library.selection = [value.id]
    let workspace = PhotoWorkspace(); workspace.reconcile(scope: "local", available: [value.id], browsing: [value.id]); workspace.open(value.id)
    let key = PhotoResourceIdentity(scope: workspace.scope, photo: value); let viewport = PhotoViewport(scale: 3.5, centerX: 0.7, centerY: 0.4); workspace.setViewport(viewport, for: key)
    let calls = PrecisionPreviewCalls(); let decoder = PhotoDisplayDecoder()
    for comparing in [false, true, false] {
      let local = await workspace.previews.resolve(identity: key, representation: PhotoRepresentationRevision.key(photo: value, cacheEpoch: 1)) { await calls.read(url) }
      let kind = PhotoDisplaySource.classify(photo: value, resolved: try XCTUnwrap(local), fileURL: { root.appendingPathComponent($0) }, verifiedTemporaryJPEG: nil)
      _ = await decoder.decode(url, maxPixel: PhotoDecodePolicy.maxPixel(comparing: comparing, source: kind))
      XCTAssertEqual(workspace.viewport(for: key), viewport)
    }
    let count = await calls.count; XCTAssertEqual(count, 1); XCTAssertTrue(library.tasks.isEmpty); XCTAssertEqual(library.selection, [value.id]); XCTAssertEqual(library.photos, [value])
  }
}
