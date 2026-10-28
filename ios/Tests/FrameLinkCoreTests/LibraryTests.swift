import XCTest

@testable import FrameLinkCore

final class LibraryTests: XCTestCase {
  @MainActor func testReceiptRecoveryDoesNotReviveDeletedFilesAndRunningTasksBecomeInterrupted()
    async throws
  {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = LibraryStore(root: root)
    let file = try store.fileURL("media/ftp/received/QA.jpg")
    try FileManager.default.createDirectory(
      at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
    try Data([1, 2, 3]).write(to: file)
    let receipt = ReceiveReceipt(
      id: "qa", name: "QA.jpg", path: file.path, size: 3, sha256: try LibraryStore.digest(file),
      receivedAt: 1)
    try store.receive(receipt)
    let id = store.photos.first!.id
    try store.remove([id], deleteFiles: false)
    try store.receive(receipt, restoreHidden: false)
    XCTAssertTrue(store.importedPhotos.isEmpty)
    try store.receive(receipt)
    XCTAssertEqual(store.importedPhotos.count, 1)
    try store.remove([id], deleteFiles: true)
    XCTAssertThrowsError(try store.receive(receipt, restoreHidden: false))
    var task = TransferTask(title: "中断任务", kind: "camera-download")
    task.state = .running
    try store.upsertTask(task)
    let recovered = LibraryStore(root: root)
    XCTAssertEqual(recovered.tasks.first!.state, .interrupted)
    XCTAssertTrue(recovered.importedPhotos.isEmpty)
  }

  @MainActor func testImportRemoveAndRestoreNeverDeleteSource() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let source = FileManager.default.temporaryDirectory.appendingPathComponent(
      UUID().uuidString + ".jpg")
    defer {
      try? FileManager.default.removeItem(at: root)
      try? FileManager.default.removeItem(at: source)
    }
    try Data([0, 1, 2, 255, 3]).write(to: source)
    let store = LibraryStore(root: root)
    let photo = try store.importFile(source)
    let copied = try store.fileURL(photo.files.first!.localPath!)
    XCTAssertEqual(try Data(contentsOf: source), try Data(contentsOf: copied))
    store.toggle(photo.id)
    let batch = try store.saveBatch(title: "客户A初选")
    try store.remove([photo.id], deleteFiles: false)
    XCTAssertTrue(FileManager.default.fileExists(atPath: copied.path))
    XCTAssertTrue(store.importedPhotos.isEmpty)
    _ = try store.importFile(source)
    XCTAssertEqual(store.photos.count, 1)
    XCTAssertEqual(store.batches.first!.photos.first!.name, batch.photos.first!.name)
    try store.remove([photo.id], deleteFiles: true)
    XCTAssertFalse(FileManager.default.fileExists(atPath: copied.path))
    XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    _ = try store.importFile(source)
    XCTAssertEqual(store.importedPhotos.count, 1)
    let reloaded = LibraryStore(root: root)
    XCTAssertEqual(reloaded.photos.count, 1)
    XCTAssertEqual(reloaded.batches.count, 1)
  }

  @MainActor func testCustomerNamesAndSelectionStaySeparate() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let source = root.appendingPathComponent("source.jpg")
    defer { try? FileManager.default.removeItem(at: root) }
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try Data([1, 2, 3]).write(to: source)
    let store = LibraryStore(root: root)
    let photo = try store.importFile(source)
    store.toggle(photo.id)
    let review = try store.createReview(title: "客户A", ids: [photo.id])
    try store.choose(review: review.id, photo: photo.id, selected: true, comment: "保留")
    try store.renameReview(review.id, title: "客户A 13800000000")
    XCTAssertTrue(store.selection.contains(photo.id))
    XCTAssertFalse(store.photos.first!.favorite)
    try store.lockReview(review.id, locked: true)
    XCTAssertThrowsError(
      try store.choose(review: review.id, photo: photo.id, selected: false, comment: ""))
    try store.deleteReview(review.id)
    XCTAssertEqual(store.photos.count, 1)
    XCTAssertTrue(store.reviews.isEmpty)
  }

  @MainActor func testPathEscapeAndActiveTransfersAreRejected() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = LibraryStore(root: root)
    XCTAssertThrowsError(try store.fileURL("../outside"))
    let photo = Photo(
      id: "one", name: "A.jpg", source: .imported,
      files: [CameraFile(name: "A.jpg", localPath: "media/A.jpg")])
    try store.update(photo)
    try store.upsertTask(TransferTask(title: "上传", kind: "ftp-upload", photoIds: [photo.id]))
    XCTAssertThrowsError(try store.remove([photo.id], deleteFiles: true))
  }

  func testDragRangeCanReverseAndDeselectBurstGroups() {
    let groups = [["a"], ["b", "c"], ["d"]]
    let add = SelectionRange(groups: groups, original: ["outside"], anchor: 0)
    XCTAssertEqual(add.selection(at: 2), ["outside", "a", "b", "c", "d"])
    XCTAssertEqual(add.selection(at: 0), ["outside", "a"])
    let remove = SelectionRange(groups: groups, original: ["a", "b", "c", "d"], anchor: 1)
    XCTAssertEqual(remove.selection(at: 2), ["a"])
    XCTAssertEqual(remove.selection(at: 1), ["a", "d"])
  }
}
