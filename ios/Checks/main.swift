import Foundation
import FrameLinkCore

enum Failure: Error { case check(String) }
@main struct CoreChecks {
  @MainActor static func main() throws {
    var checks = 0
    func expect(_ value: @autoclosure () throws -> Bool, _ message: String) throws {
      guard try value() else { throw Failure.check(message) }
      checks += 1
    }
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let source = FileManager.default.temporaryDirectory.appendingPathComponent(
      UUID().uuidString + ".jpg")
    defer {
      try? FileManager.default.removeItem(at: root)
      try? FileManager.default.removeItem(at: source)
    }
    try Data((0..<800_000).map { UInt8($0 % 251) }).write(to: source)
    let store = LibraryStore(root: root)
    let photo = try store.importFile(source)
    let copied = try store.fileURL(photo.files[0].localPath!)
    try expect(try LibraryStore.digest(copied) == LibraryStore.digest(source), "original bytes")
    store.toggle(photo.id)
    let batch = try store.saveBatch(title: "客户A初选")
    let review = try store.createReview(title: "客户A", ids: [photo.id])
    try store.choose(review: review.id, photo: photo.id, selected: true, comment: "保留")
    try store.renameReview(review.id, title: "客户A 13800000000")
    try expect(store.reviews[0].choices[photo.id]!.selected, "customer selection")
    try expect(
      !store.photos[0].favorite && store.selection.contains(photo.id),
      "independent customer choices")
    try store.remove([photo.id], deleteFiles: false)
    try expect(FileManager.default.fileExists(atPath: copied.path), "list removal retains original")
    try expect(store.importedPhotos.isEmpty, "hidden from library")
    _ = try store.importFile(source)
    try expect(
      store.photos.count == 1 && store.importedPhotos.count == 1, "restore hidden original")
    try store.remove([photo.id], deleteFiles: true)
    try expect(!FileManager.default.fileExists(atPath: copied.path), "local deletion")
    try expect(FileManager.default.fileExists(atPath: source.path), "source retained")
    try expect(store.batches[0].photos[0].name == batch.photos[0].name, "immutable batch snapshot")
    _ = try store.importFile(source)
    let loaded = LibraryStore(root: root)
    try expect(loaded.photos.count == 1 && loaded.batches.count == 1, "persistence")
    do {
      _ = try loaded.fileURL("../outside")
      throw Failure.check("path escape")
    } catch LibraryError.invalidPath { checks += 1 }
    try loaded.upsertTask(TransferTask(title: "上传", kind: "ftp-upload", photoIds: [photo.id]))
    do {
      try loaded.remove([photo.id], deleteFiles: true)
      throw Failure.check("active transfer deletion")
    } catch LibraryError.busy { checks += 1 }
    let range = SelectionRange(
      groups: [["a"], ["b", "c"], ["d"]], original: ["outside"], anchor: 0)
    try expect(range.selection(at: 2) == ["a", "b", "c", "d", "outside"], "drag select")
    try expect(range.selection(at: 0) == ["a", "outside"], "drag reverse")
    print("FrameLinkCore: \(checks) checks passed")
  }
}
