import CryptoKit
import Foundation

public struct CameraImportOperation: Sendable {
  fileprivate let taskID: String
  fileprivate let generation: UUID
  fileprivate let id: UUID
}
public struct CameraImportResult: Sendable {
  public let photo: Photo
  public let bytes: Int64
}

/// The queue uses this boundary for both cache hits and native downloads, including final task publication.
@MainActor public final class CameraImportPublication {
  private let library: LibraryStore
  private var generation = UUID()
  private var active: [String: UUID] = [:]
  public init(library: LibraryStore) { self.library = library }
  public func begin(taskID: String) -> CameraImportOperation {
    let operation = CameraImportOperation(taskID: taskID, generation: generation, id: UUID())
    active[taskID] = operation.id; return operation
  }
  public func cancel() {
    let ids = Set(active.keys)
    generation = UUID(); active = [:]
    for var task in library.tasks where ids.contains(task.id) && task.state == .running {
      task.state = .cancelled; task.error = "传输已取消"; try? library.upsertTask(task)
    }
  }
  public func check(_ operation: CameraImportOperation) throws {
    try Task.checkCancellation()
    guard matches(operation), library.tasks.first(where: { $0.id == operation.taskID })?.state == .running else { throw CancellationError() }
  }
  public func complete(_ operation: CameraImportOperation, task: TransferTask) throws {
    try check(operation)
    guard task.id == operation.taskID, let current = library.tasks.first(where: { $0.id == operation.taskID }) else { throw CancellationError() }
    var completed = task; completed.state = .complete
    completed.total = max(task.total, task.done, current.total, current.done); completed.done = completed.total
    try library.upsertTask(completed); active.removeValue(forKey: operation.taskID)
  }
  public func advance(_ operation: CameraImportOperation, done: Int64, total: Int64) throws {
    try check(operation)
    guard var current = library.tasks.first(where: { $0.id == operation.taskID }) else { throw CancellationError() }
    current.done = max(current.done, done); current.total = max(current.total, total)
    try library.upsertTask(current)
  }
  public func fail(_ operation: CameraImportOperation, error: Error) {
    guard matches(operation) else { return }
    defer { active.removeValue(forKey: operation.taskID) }
    guard var current = library.tasks.first(where: { $0.id == operation.taskID }), current.state == .running else { return }
    current.state = Task.isCancelled || error is CancellationError ? .cancelled : .failed
    current.error = error.localizedDescription; try? library.upsertTask(current)
  }
  public func transferAndPublish(
    _ operation: CameraImportOperation, photo: Photo, file: CameraFile,
    copy: (URL) async throws -> Int64?, download: (URL) async throws -> Int64
  ) async throws -> CameraImportResult {
    try check(operation)
    let filename = (file.name as NSString).lastPathComponent
    guard !filename.isEmpty, filename != ".", filename != ".." else { throw LibraryError.invalidName }
    let photoKey = SHA256.hash(data: Data(photo.id.utf8)).map { String(format: "%02x", $0) }.joined()
    let relative = "media/camera/\(photoKey)/\(file.fileId)_\(filename)"
    let target = try library.fileURL(relative)
    let pending = try library.fileURL("media/.pending-camera/\(operation.id.uuidString)-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: pending, withIntermediateDirectories: true)
    let staging = pending.appendingPathComponent(filename)
    var createdTarget = false
    var published = false
    defer {
      try? FileManager.default.removeItem(at: pending)
      // Never remove a pre-existing target or a file already referenced by another successful publication.
      if createdTarget && !published && !library.photos.contains(where: { $0.files.contains(where: { $0.localPath == relative }) }) {
        try? FileManager.default.removeItem(at: target)
      }
    }
    let reused = try await copy(staging)
    try check(operation)
    let bytes: Int64
    if let reused { bytes = reused }
    else { bytes = try await download(staging) }
    try check(operation)
    guard bytes >= 0, file.size <= 0 || file.size == bytes,
      try staging.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init) == bytes else { throw CocoaError(.fileReadCorruptFile) }
    guard var latest = library.photos.first(where: { $0.id == photo.id }),
      let index = latest.files.firstIndex(where: { $0.fileId == file.fileId && $0.name == file.name && $0.size == file.size }), latest.files[index].localPath == nil
    else { throw LibraryError.busy }
    let previous = latest
    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
    if FileManager.default.fileExists(atPath: target.path) {
      guard try LibraryStore.digest(target) == LibraryStore.digest(staging) else { throw CocoaError(.fileWriteFileExists) }
    } else {
      try check(operation)
      try FileManager.default.moveItem(at: staging, to: target); createdTarget = true
    }
    try check(operation)
    latest.files[index].localPath = relative; latest.hidden = false
    do { try library.update(latest) }
    catch { try? library.update(previous); throw error }
    published = true
    return CameraImportResult(photo: latest, bytes: bytes)
  }
  private func matches(_ operation: CameraImportOperation) -> Bool {
    operation.generation == generation && active[operation.taskID] == operation.id
  }
}
