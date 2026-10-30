import XCTest
@testable import FrameLinkCore

final class TransferHistoryTests: XCTestCase {
  @MainActor func testClearKeepsFilesPhotosAndRetryableTasksAndRejectsLateUpdates() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    let source = root.appendingPathComponent("source.jpg")
    try Data("original-bytes".utf8).write(to: source)
    let photo = try library.importFile(source)
    var done = TransferTask(title: "done", kind: "camera-download", photoIds: [photo.id]); done.state = .complete
    var cancelled = TransferTask(title: "cancelled", kind: "ftp-download", payload: ["name": "retained.txt"]); cancelled.state = .cancelled
    let retained = try library.fileURL("media/ftp-client/\(cancelled.id)/retained.txt")
    try FileManager.default.createDirectory(at: retained.deletingLastPathComponent(), withIntermediateDirectories: true)
    try Data("retained-data".utf8).write(to: retained)
    let activeStates: [TransferState] = [.queued, .running, .failed, .interrupted]
    var active: [TransferTask] = []
    for state in activeStates { var task = TransferTask(title: state.rawValue, kind: "camera-download"); task.state = state; try library.upsertTask(task); active.append(task) }
    try library.upsertTask(done); try library.upsertTask(cancelled)
    let beforePhoto = library.photos
    let beforeHash = try LibraryStore.digest(try library.fileURL(photo.files[0].localPath!))
    XCTAssertEqual(try library.clearTaskHistory(Set(library.tasks.map(\.id))), 2)
    XCTAssertEqual(Set(library.tasks.map(\.id)), Set(active.map(\.id)))
    XCTAssertEqual(library.photos, beforePhoto)
    XCTAssertEqual(try LibraryStore.digest(try library.fileURL(photo.files[0].localPath!)), beforeHash)
    XCTAssertEqual(library.savedFiles.count, 2)
    var late = done; late.state = .running; try library.upsertTask(late)
    XCTAssertFalse(library.tasks.contains { $0.id == done.id })
    let reopened = LibraryStore(root: root)
    XCTAssertEqual(reopened.savedFiles.count, 2); XCTAssertFalse(reopened.tasks.contains { $0.id == done.id })
    XCTAssertTrue(FileManager.default.fileExists(atPath: retained.path))
  }

  @MainActor func testClearRechecksStateAndOnlyUsesTheConfirmedIDs() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    var first = TransferTask(title: "retry", kind: "camera-download"); first.state = .complete
    try library.upsertTask(first); let confirmation: Set<String> = [first.id]
    first.state = .queued; try library.upsertTask(first)
    var later = TransferTask(title: "completed-after-dialog", kind: "camera-download"); later.state = .complete; try library.upsertTask(later)
    XCTAssertEqual(try library.clearTaskHistory(confirmation), 0)
    XCTAssertEqual(library.tasks.count, 2)
  }

  @MainActor func testLegacyIndexWithoutHistoryFieldsIsReadWithoutLoss() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    let task = TransferTask(title: "legacy", kind: "camera-download"); try library.upsertTask(task)
    let index = root.appendingPathComponent("library.json")
    var json = try JSONSerialization.jsonObject(with: Data(contentsOf: index)) as! [String: Any]
    json.removeValue(forKey: "savedFiles"); json.removeValue(forKey: "clearedTaskIDs")
    try JSONSerialization.data(withJSONObject: json).write(to: index, options: .atomic)
    let migrated = LibraryStore(root: root)
    XCTAssertNil(migrated.persistenceError); XCTAssertEqual(migrated.tasks.first?.id, task.id); XCTAssertTrue(migrated.savedFiles.isEmpty)
  }
}
