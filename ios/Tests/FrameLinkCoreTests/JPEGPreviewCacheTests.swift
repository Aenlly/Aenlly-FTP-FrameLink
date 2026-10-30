import XCTest
@testable import FrameLinkCore

final class JPEGPreviewCacheTests: XCTestCase {
  private func root() throws -> URL {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); return root
  }
  private func resource(_ id: String, size: Int64 = 0, name: String = "photo.jpg") -> JPEGPreviewResource {
    JPEGPreviewResource(photo: Photo(id: id, name: name, source: .camera, files: [], slot: 1, contentId: 99), file: CameraFile(fileId: 3, name: name, size: size))
  }
  private func store(_ resource: JPEGPreviewResource, in cache: JPEGPreviewCache) async throws -> JPEGCacheFile {
    let ticket = try await cache.begin(resource); try jpegFixture(at: ticket.url)
    let bytes = Int64(try ticket.url.resourceValues(forKeys: [.fileSizeKey]).fileSize!)
    return try await cache.commit(ticket, bytes: bytes, sha256: LibraryStore.digest(ticket.url))
  }
  @MainActor func testPreviewDoesNotChangeImportStateAndCanBeCopiedForFormalImport() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root.appendingPathComponent("library"))
    let photo = Photo(id: "camera-identity:1:99:123", name: "photo.jpg", source: .camera, files: [CameraFile(fileId: 3, name: "photo.jpg")], contentId: 99)
    try library.update(photo); let snapshot = library.photos
    let cache = try JPEGPreviewCache(root: library.root.appendingPathComponent("previews"))
    let resource = JPEGPreviewResource(photo: photo, file: photo.files[0]); let cached = try await store(resource, in: cache)
    XCTAssertEqual(library.photos, snapshot); XCTAssertTrue(library.tasks.isEmpty); XCTAssertFalse(library.photos[0].imported)
    let target = try library.fileURL("media/imported.jpg"); let copied = try await cache.copyForImport(resource, to: target)
    XCTAssertEqual(copied?.sha256, cached.sha256); XCTAssertEqual(try LibraryStore.digest(target), cached.sha256)
    let existing = try await cache.copyForImport(resource, to: target); XCTAssertNotNil(existing)
    try Data("unrelated-existing-file".utf8).write(to: target)
    do { _ = try await cache.copyForImport(resource, to: target); XCTFail("unrelated destination replaced") } catch {}
    XCTAssertEqual(try Data(contentsOf: target), Data("unrelated-existing-file".utf8))
    try await cache.clear(); XCTAssertTrue(FileManager.default.fileExists(atPath: target.path)); XCTAssertEqual(library.photos, snapshot)
  }
  func testIdentityAndFormatsRemainIndependent() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root); _ = try await store(resource("camera-a:1:99:123"), in: cache)
    let other = try await cache.lookup(resource("camera-b:1:99:123")); XCTAssertNil(other)
    let changed = try await cache.lookup(resource("camera-a:1:99:124")); XCTAssertNil(changed)
    let raw = try await cache.lookup(resource("camera-a:1:99:123", name: "photo.arw")); XCTAssertNil(raw)
  }
  func testCorruptCachedBytesAreRejected() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root); let item = resource("camera-a")
    let cached = try await store(item, in: cache); try Data(repeating: 0, count: Int(cached.size)).write(to: cached.url)
    let result = try await cache.lookup(item); XCTAssertNil(result); XCTAssertFalse(FileManager.default.fileExists(atPath: cached.url.path))
  }
  func testClearRejectsLateNativeCompletion() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root); let ticket = try await cache.begin(resource("camera-a"))
    try await cache.clear(); try jpegFixture(at: ticket.url)
    do { _ = try await cache.commit(ticket, bytes: 1, sha256: "unused"); XCTFail("late completion accepted") } catch JPEGCacheError.cleared {}
    XCTAssertFalse(FileManager.default.fileExists(atPath: ticket.url.path))
  }
  func testBudgetEvictsOldPreviewsAndPreservesLatestJPEG() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root, limit: 1600)
    let old = root.appendingPathComponent("old-thumbnail.jpg"); try Data(repeating: 1, count: 1100).write(to: old)
    try FileManager.default.setAttributes([.modificationDate: Date.distantPast], ofItemAtPath: old.path)
    let cached = try await store(resource("camera-a"), in: cache)
    XCTAssertFalse(FileManager.default.fileExists(atPath: old.path)); XCTAssertTrue(FileManager.default.fileExists(atPath: cached.url.path))
    let usage = try await cache.usage(); XCTAssertLessThanOrEqual(usage, 1600)
  }
  func testIncompleteReceiptAndOversizedResourceAreRejected() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root, limit: 1600)
    do { _ = try await cache.begin(resource("too-large", size: 2000)); XCTFail("oversized resource accepted") } catch JPEGCacheError.tooLarge {}
    let ticket = try await cache.begin(resource("camera-a", size: 1000)); try jpegFixture(at: ticket.url)
    do { _ = try await cache.commit(ticket, bytes: 1000, sha256: "incorrect"); XCTFail("invalid receipt accepted") } catch JPEGCacheError.invalidReceipt {}
    XCTAssertFalse(FileManager.default.fileExists(atPath: ticket.url.path))
  }
  func testDeletingOnePhotosCacheKeepsOtherPhotoAndRejectsItsLateWrite() async throws {
    let root = try root(); defer { try? FileManager.default.removeItem(at: root) }
    let cache = try JPEGPreviewCache(root: root)
    let a = resource("camera-a"); let b = resource("camera-b")
    _ = try await store(a, in: cache); let other = try await store(b, in: cache)
    let pending = try await cache.begin(a)
    try await cache.remove(photoIDs: [a.photoID])
    let missing = try await cache.lookup(a); XCTAssertNil(missing)
    XCTAssertTrue(FileManager.default.fileExists(atPath: other.url.path))
    try jpegFixture(at: pending.url)
    do { _ = try await cache.commit(pending, bytes: 1, sha256: "unused"); XCTFail("deleted preview returned") } catch JPEGCacheError.cleared {}
  }
}
