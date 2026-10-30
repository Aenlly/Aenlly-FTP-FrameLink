import CryptoKit
import XCTest
@testable import FrameLinkCore

@MainActor private final class PausedImport {
  let started = XCTestExpectation(description: "transfer reached suspension")
  private var continuation: CheckedContinuation<Int64, Never>?
  let bytes = Data("operation-owned-file".utf8)
  func writeAndWait(_ url: URL) async throws -> Int64 {
    try bytes.write(to: url)
    return await withCheckedContinuation { continuation = $0; started.fulfill() }
  }
  func resume() { continuation?.resume(returning: Int64(bytes.count)); continuation = nil }
}

@MainActor final class CameraImportPublicationTests: XCTestCase {
  private struct Fixture {
    let root: URL; let library: LibraryStore; let publisher: CameraImportPublication; let photo: Photo; let task: TransferTask
  }
  private func fixture(twoFiles: Bool = false) throws -> Fixture {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("publication-" + UUID().uuidString)
    let library = LibraryStore(root: root)
    var files = [CameraFile(fileId: 1, name: "photo.jpg")]
    if twoFiles { files.append(CameraFile(fileId: 2, name: "photo.arw")) }
    let photo = Photo(id: "camera-identity:1:99:123", name: "photo.jpg", source: .camera, files: files)
    try library.update(photo)
    var task = TransferTask(title: "Import", kind: "camera-download", photoIds: [photo.id]); task.state = .running
    try library.upsertTask(task)
    return Fixture(root: root, library: library, publisher: CameraImportPublication(library: library), photo: photo, task: task)
  }
  private func hasPendingFiles(_ f: Fixture) -> Bool {
    let url = f.root.appendingPathComponent("media/.pending-camera")
    return (FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey])?.allObjects as? [URL] ?? []).contains { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
  }
  private func worker(_ f: Fixture, gate: PausedImport, cacheHit: Bool) -> Task<Void, Error> {
    let operation = f.publisher.begin(taskID: f.task.id)
    return Task {
      do {
        let result = try await f.publisher.transferAndPublish(operation, photo: f.photo, file: f.photo.files[0], copy: { url in
          cacheHit ? try await gate.writeAndWait(url) : nil
        }, download: { url in
          if cacheHit { XCTFail("cache hit downloaded again") }
          return try await gate.writeAndWait(url)
        })
        var done = f.task; done.total = result.bytes; done.done = result.bytes
        try f.publisher.complete(operation, task: done)
      } catch { f.publisher.fail(operation, error: error); throw error }
    }
  }
  func testCancellationBeforeCacheCopyReturnsDoesNotPublishPhotoOrCompletion() async throws {
    for mode in 0..<3 {
      let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
      let gate = PausedImport(); let task = worker(f, gate: gate, cacheHit: true)
      await fulfillment(of: [gate.started], timeout: 3)
      if mode != 0 { f.publisher.cancel() }
      if mode != 1 { task.cancel() }
      gate.resume()
      do { try await task.value; XCTFail("cancelled copy completed") } catch is CancellationError {}
      XCTAssertEqual(f.library.tasks.first!.state, .cancelled)
      XCTAssertEqual(f.library.photos, [f.photo]); XCTAssertFalse(hasPendingFiles(f))
      XCTAssertFalse(FileManager.default.fileExists(atPath: destination(f).path))
    }
  }
  func testCancellationBeforeNativeDownloadReturnsPreservesPreexistingTarget() async throws {
    let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
    let existing = destination(f); try FileManager.default.createDirectory(at: existing.deletingLastPathComponent(), withIntermediateDirectories: true)
    let original = Data("preexisting-file".utf8); try original.write(to: existing)
    let gate = PausedImport(); let task = worker(f, gate: gate, cacheHit: false)
    await fulfillment(of: [gate.started], timeout: 3)
    f.publisher.cancel(); task.cancel(); gate.resume()
    do { try await task.value; XCTFail("cancelled native download completed") } catch is CancellationError {}
    XCTAssertEqual(f.library.tasks.first!.state, .cancelled); XCTAssertEqual(f.library.photos, [f.photo])
    XCTAssertEqual(try Data(contentsOf: existing), original); XCTAssertFalse(hasPendingFiles(f))
  }
  func testEarlierSuccessfulFileRemainsWhenNextFileIsCancelled() async throws {
    let f = try fixture(twoFiles: true); defer { try? FileManager.default.removeItem(at: f.root) }
    let gate = PausedImport(); let operation = f.publisher.begin(taskID: f.task.id)
    let bytes = Data("first-success".utf8)
    let task = Task {
      do {
        let first = try await f.publisher.transferAndPublish(operation, photo: f.photo, file: f.photo.files[0], copy: { url in try bytes.write(to: url); return Int64(bytes.count) }, download: { _ in XCTFail("unexpected download"); return 0 })
        try f.publisher.advance(operation, done: first.bytes, total: first.bytes * 2)
        _ = try await f.publisher.transferAndPublish(operation, photo: first.photo, file: f.photo.files[1], copy: { _ in nil }, download: { try await gate.writeAndWait($0) })
        try f.publisher.complete(operation, task: f.task)
      } catch { f.publisher.fail(operation, error: error); throw error }
    }
    await fulfillment(of: [gate.started], timeout: 3); f.publisher.cancel(); task.cancel(); gate.resume()
    do { try await task.value; XCTFail("cancelled batch completed") } catch is CancellationError {}
    let published = try XCTUnwrap(f.library.photos.first!.files[0].localPath)
    XCTAssertEqual(try Data(contentsOf: f.library.fileURL(published)), bytes)
    XCTAssertNil(f.library.photos.first!.files[1].localPath); XCTAssertEqual(f.library.tasks.first!.state, .cancelled)
    XCTAssertEqual(f.library.tasks.first!.done, Int64(bytes.count)); XCTAssertFalse(hasPendingFiles(f))
  }
  func testStateChangeDuringAwaitDoesNotOverwriteQueuedRetry() async throws {
    let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
    let gate = PausedImport(); let task = worker(f, gate: gate, cacheHit: true)
    await fulfillment(of: [gate.started], timeout: 3)
    var retry = f.task; retry.state = .queued; try f.library.upsertTask(retry); gate.resume()
    do { try await task.value; XCTFail("old operation published over retry") } catch is CancellationError {}
    XCTAssertEqual(f.library.tasks.first!.state, .queued); XCTAssertEqual(f.library.photos, [f.photo]); XCTAssertFalse(hasPendingFiles(f))
  }
  func testSuccessfulCacheAndDownloadPublishFileThenComplete() async throws {
    for cacheHit in [true, false] {
      let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
      let gate = PausedImport(); let task = worker(f, gate: gate, cacheHit: cacheHit)
      await fulfillment(of: [gate.started], timeout: 3); gate.resume(); try await task.value
      XCTAssertEqual(f.library.tasks.first!.state, .complete)
      let path = try XCTUnwrap(f.library.photos.first!.files[0].localPath)
      XCTAssertEqual(try Data(contentsOf: f.library.fileURL(path)), gate.bytes); XCTAssertFalse(hasPendingFiles(f))
    }
  }
  func testOldCompletionCannotOverwriteAReplacedOperation() async throws {
    let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
    let gate = PausedImport(); let old = worker(f, gate: gate, cacheHit: true)
    await fulfillment(of: [gate.started], timeout: 3)
    f.publisher.cancel()
    var retry = f.task; retry.state = .running; try f.library.upsertTask(retry)
    let replacement = f.publisher.begin(taskID: retry.id); let bytes = Data("replacement-operation".utf8)
    let result = try await f.publisher.transferAndPublish(replacement, photo: f.photo, file: f.photo.files[0], copy: { url in try bytes.write(to: url); return Int64(bytes.count) }, download: { _ in 0 })
    retry.done = result.bytes; retry.total = result.bytes; try f.publisher.complete(replacement, task: retry)
    gate.resume()
    do { try await old.value; XCTFail("old completion accepted") } catch is CancellationError {}
    XCTAssertEqual(f.library.tasks.first!.state, .complete)
    XCTAssertEqual(try Data(contentsOf: f.library.fileURL(f.library.photos[0].files[0].localPath!)), bytes)
    XCTAssertFalse(hasPendingFiles(f))
  }
  func testFinalCompletionGateRejectsCancellationAndKeepsAlreadyPublishedFile() async throws {
    let f = try fixture(); defer { try? FileManager.default.removeItem(at: f.root) }
    let operation = f.publisher.begin(taskID: f.task.id); let bytes = Data("already-published".utf8)
    let result = try await f.publisher.transferAndPublish(operation, photo: f.photo, file: f.photo.files[0], copy: { url in try bytes.write(to: url); return Int64(bytes.count) }, download: { _ in 0 })
    f.publisher.cancel()
    XCTAssertThrowsError(try f.publisher.complete(operation, task: f.task))
    XCTAssertEqual(f.library.tasks.first!.state, .cancelled)
    XCTAssertEqual(try Data(contentsOf: f.library.fileURL(result.photo.files[0].localPath!)), bytes)
  }
  private func destination(_ f: Fixture) -> URL {
    let key = SHA256.hash(data: Data(f.photo.id.utf8)).map { String(format: "%02x", $0) }.joined()
    return f.root.appendingPathComponent("media/camera/\(key)/1_photo.jpg")
  }
}
