import Combine
import CryptoKit
import Foundation

public enum LibraryError: LocalizedError {
  case invalidName, invalidPath, missingPhoto, busy, corruptedIndex
  public var errorDescription: String? {
    switch self {
    case .invalidName: return "请填写有效的名称"
    case .invalidPath: return "文件不在App管理目录内"
    case .missingPhoto: return "照片记录已变化"
    case .busy: return "照片仍有待处理传输，请先完成或取消"
    case .corruptedIndex: return "索引无法读取，已保留原文件"
    }
  }
}

@MainActor
public final class LibraryStore: ObservableObject {
  @Published public private(set) var photos: [Photo] = []
  @Published public private(set) var batches: [SelectionBatch] = []
  @Published public private(set) var reviews: [CustomerReview] = []
  @Published public private(set) var tasks: [TransferTask] = []
  @Published public private(set) var savedFiles: [SavedTransferFile] = []
  private var clearedTaskIDs: Set<String> = []
  @Published public var selection: Set<String> = []
  @Published public var persistenceError: String?
  public let root: URL
  private let index: URL
  private var canSave = true
  private struct Snapshot: Codable {
    var version = 1
    var photos: [Photo]
    var batches: [SelectionBatch]
    var reviews: [CustomerReview]
    var tasks: [TransferTask]
    var savedFiles: [SavedTransferFile]?
    var clearedTaskIDs: Set<String>?
  }

  public init(root: URL) {
    self.root = root
    index = root.appendingPathComponent("library.json")
    do {
      try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
      if FileManager.default.fileExists(atPath: index.path) {
        let value = try JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: index))
        guard value.version == 1 else { throw LibraryError.corruptedIndex }
        photos = value.photos
        batches = value.batches
        reviews = value.reviews
        savedFiles = value.savedFiles ?? []
        clearedTaskIDs = value.clearedTaskIDs ?? []
        tasks = value.tasks.map { task in
          var next = task
          if next.state == .running {
            next.state = .interrupted
            next.error = "上次运行中断，可重试"
          }
          return next
        }
      }
    } catch {
      canSave = false
      persistenceError = error.localizedDescription
    }
  }
  public var importedPhotos: [Photo] {
    photos.filter(\.inLibrary).sorted { $0.orderingDate > $1.orderingDate }
  }
  public func save() throws {
    guard canSave else { throw LibraryError.corruptedIndex }
    let data = try JSONEncoder().encode(
      Snapshot(photos: photos, batches: batches, reviews: reviews, tasks: tasks, savedFiles: savedFiles, clearedTaskIDs: clearedTaskIDs))
    try data.write(to: index, options: .atomic)
    #if os(iOS)
      try FileManager.default.setAttributes(
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
        ofItemAtPath: index.path)
    #endif
  }
  public func fileURL(_ path: String) throws -> URL {
    let value = root.appendingPathComponent(path).standardizedFileURL.resolvingSymlinksInPath()
    let prefix = root.standardizedFileURL.resolvingSymlinksInPath().path + "/"
    guard value.path.hasPrefix(prefix) else { throw LibraryError.invalidPath }
    return value
  }
  public func relativePath(_ url: URL) throws -> String {
    let value = url.standardizedFileURL.resolvingSymlinksInPath()
    let prefix = root.standardizedFileURL.resolvingSymlinksInPath().path + "/"
    guard value.path.hasPrefix(prefix) else { throw LibraryError.invalidPath }
    return String(value.path.dropFirst(prefix.count))
  }
  public nonisolated static func digest(_ url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var digest = SHA256()
    while let bytes = try handle.read(upToCount: 256 * 1024), !bytes.isEmpty {
      digest.update(data: bytes)
    }
    return digest.finalize().map { String(format: "%02x", $0) }.joined()
  }
  @discardableResult public func importFile(_ source: URL) throws -> Photo {
    try commitImport(Self.prepareImport(source, root: root))
  }

  /// Copies and hashes the original in bounded chunks; callers can run this off the UI actor.
  public nonisolated static func prepareImport(_ source: URL, root: URL) throws -> Photo {
    let name = source.lastPathComponent
    let info = try source.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
    guard !name.isEmpty && info.isRegularFile == true else { throw LibraryError.invalidPath }
    let relative = "media/import/\(UUID().uuidString)/\(name)"
    let destination = root.appendingPathComponent(relative)
    let directory = destination.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let part = destination.appendingPathExtension("part")
    guard FileManager.default.createFile(atPath: part.path, contents: nil) else {
      throw CocoaError(.fileWriteUnknown)
    }
    let input = try FileHandle(forReadingFrom: source)
    defer { try? input.close() }
    let output = try FileHandle(forWritingTo: part)
    defer {
      try? output.close()
      try? FileManager.default.removeItem(at: part)
    }
    var hash = SHA256()
    var count: Int64 = 0
    while let data = try input.read(upToCount: 256 * 1024), !data.isEmpty {
      try output.write(contentsOf: data)
      hash.update(data: data)
      count += Int64(data.count)
    }
    guard count == Int64(info.fileSize ?? -1) else { throw CocoaError(.fileReadCorruptFile) }
    try output.synchronize()
    try output.close()
    try FileManager.default.moveItem(at: part, to: destination)
    let id = "local:" + hash.finalize().map { String(format: "%02x", $0) }.joined()
    return Photo(
      id: id, name: name, source: .imported,
      files: [CameraFile(name: name, size: count, localPath: relative)])
  }

  @discardableResult public func commitImport(_ photo: Photo) throws -> Photo {
    guard canSave else { throw LibraryError.corruptedIndex }
    let existing = photos.firstIndex(where: { $0.id == photo.id })
    if let i = existing,
      photos[i].files.contains(where: { file in
        guard let path = file.localPath, let url = try? fileURL(path) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
      })
    {
      photos[i].hidden = false
      try save()
      for file in photo.files {
        if let path = file.localPath { try? FileManager.default.removeItem(at: fileURL(path)) }
      }
      return photos[i]
    }
    if let i = existing {
      var restored = photo
      restored.favorite = photos[i].favorite
      photos[i] = restored
    } else {
      photos.append(photo)
    }
    try save()
    return photos.first { $0.id == photo.id }!
  }
  public func mergeCamera(_ page: CameraPage) throws {
    for remote in page.items {
      var next = remote.photo()
      if let index = photos.firstIndex(where: { $0.id == next.id }) {
        let old = photos[index]
        next.files = next.files.map { incoming in
          var value = incoming
          if let saved = old.files.first(where: {
            $0.fileId == incoming.fileId && $0.name == incoming.name && $0.size == incoming.size
          }) {
            value.localPath = saved.localPath
          }
          return value
        }
        next.favorite = old.favorite
        next.hidden = old.hidden
        next.previewPath = old.previewPath
        photos[index] = next
      } else {
        photos.append(next)
      }
    }
    try save()
  }
  public func update(_ photo: Photo) throws {
    if let i = photos.firstIndex(where: { $0.id == photo.id }) {
      photos[i] = photo
    } else {
      photos.append(photo)
    }
    try save()
  }
  public func toggle(_ id: String) { if !selection.insert(id).inserted { selection.remove(id) } }
  public func favorite(_ id: String) throws {
    guard let i = photos.firstIndex(where: { $0.id == id }) else { throw LibraryError.missingPhoto }
    photos[i].favorite.toggle()
    try save()
  }
  @discardableResult public func saveBatch(title: String) throws -> SelectionBatch {
    let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty && name.count <= 160 && !selection.isEmpty else {
      throw LibraryError.invalidName
    }
    let selected = photos.filter { selection.contains($0.id) }
    guard selected.count == selection.count else { throw LibraryError.missingPhoto }
    let batch = SelectionBatch(title: name, photos: selected)
    batches.insert(batch, at: 0)
    try save()
    return batch
  }
  public func renameBatch(_ id: String, title: String) throws {
    let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty && name.count <= 160,
      let i = batches.firstIndex(where: { $0.id == id })
    else { throw LibraryError.invalidName }
    batches[i].title = name
    batches[i].updatedAt = Date()
    try save()
  }
  public func deleteBatch(_ id: String) throws {
    batches.removeAll { $0.id == id }
    try save()
  }
  @discardableResult public func createReview(title: String, ids: Set<String>) throws
    -> CustomerReview
  {
    let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty && name.count <= 160 && !ids.isEmpty else {
      throw LibraryError.invalidName
    }
    let selected = photos.filter { ids.contains($0.id) }
    guard selected.count == ids.count else { throw LibraryError.missingPhoto }
    let review = CustomerReview(title: name, photos: selected)
    reviews.insert(review, at: 0)
    try save()
    return review
  }
  public func renameReview(_ id: String, title: String) throws {
    let name = title.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !name.isEmpty && name.count <= 160, let i = reviews.firstIndex(where: { $0.id == id })
    else {
      throw LibraryError.invalidName
    }
    reviews[i].title = name
    reviews[i].updatedAt = Date()
    try save()
  }
  public func choose(review id: String, photo: String, selected: Bool, comment: String) throws {
    guard comment.count <= 2000, let i = reviews.firstIndex(where: { $0.id == id }),
      !reviews[i].locked, reviews[i].photos.contains(where: { $0.id == photo })
    else { throw LibraryError.missingPhoto }
    var choice = CustomerChoice()
    choice.selected = selected
    choice.comment = comment
    reviews[i].choices[photo] = choice
    reviews[i].updatedAt = Date()
    try save()
  }
  public func lockReview(_ id: String, locked: Bool) throws {
    guard let i = reviews.firstIndex(where: { $0.id == id }) else {
      throw LibraryError.missingPhoto
    }
    reviews[i].locked = locked
    try save()
  }
  public func deleteReview(_ id: String) throws {
    reviews.removeAll { $0.id == id }
    try save()
  }
  public func upsertTask(_ task: TransferTask) throws {
    guard !clearedTaskIDs.contains(task.id) else { return }
    if let i = tasks.firstIndex(where: { $0.id == task.id }) {
      tasks[i] = task
    } else {
      tasks.insert(task, at: 0)
    }
    try save()
  }
  /// Rechecks terminal states on the main actor; file indexes and receipt tombstones survive history removal.
  @discardableResult public func clearTaskHistory(_ ids: Set<String>) throws -> Int {
    guard canSave else { throw LibraryError.corruptedIndex }
    let ended = tasks.filter { ids.contains($0.id) && $0.state.canClearHistory }
    let previousTasks = tasks, previousFiles = savedFiles, previousCleared = clearedTaskIDs
    for task in ended {
      var paths = photos.filter { task.photoIds.contains($0.id) }.flatMap { $0.files.compactMap(\.localPath) }
      if task.kind == "ftp-download", let name = task.payload["name"] {
        paths.append("media/ftp-client/\(task.id)/\(name)")
      }
      for key in ["path", "source"] {
        if let value = task.payload[key] {
          if value.hasPrefix(root.path + "/"), let relative = try? relativePath(URL(fileURLWithPath: value)) { paths.append(relative) }
          else if value.hasPrefix("media/") { paths.append(value) }
        }
      }
      for path in Set(paths) {
        guard let url = try? fileURL(path), (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { continue }
        let id = SHA256.hash(data: Data(path.utf8)).map { String(format: "%02x", $0) }.joined()
        if !savedFiles.contains(where: { $0.id == id }) { savedFiles.append(SavedTransferFile(id: id, name: url.lastPathComponent, path: path, savedAt: task.createdAt)) }
      }
      clearedTaskIDs.insert(task.id)
    }
    tasks.removeAll { clearedTaskIDs.contains($0.id) }
    do { try save() } catch { tasks = previousTasks; savedFiles = previousFiles; clearedTaskIDs = previousCleared; throw error }
    return ended.count
  }
  public func remove(_ ids: Set<String>, deleteFiles: Bool) throws {
    guard canSave else { throw LibraryError.corruptedIndex }
    guard
      !tasks.contains(where: {
        !Set($0.photoIds).isDisjoint(with: ids)
          && [.queued, .running, .interrupted].contains($0.state)
      })
    else { throw LibraryError.busy }
    let otherPaths = Set(
      photos.filter { !ids.contains($0.id) }.flatMap {
        $0.files.compactMap(\.localPath) + [$0.previewPath].compactMap { $0 }
      })
    for i in photos.indices where ids.contains(photos[i].id) {
      if deleteFiles {
        let paths = Set(
          photos[i].files.compactMap(\.localPath) + [photos[i].previewPath].compactMap { $0 }
        ).subtracting(otherPaths)
        let urls = try paths.map { path -> URL in
          guard path.hasPrefix("media/") || path.hasPrefix("previews/") else {
            throw LibraryError.invalidPath
          }
          let url = try fileURL(path)
          if FileManager.default.fileExists(atPath: url.path),
            try url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true
          {
            throw LibraryError.invalidPath
          }
          return url
        }
        for url in urls {
          if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
          }
        }
        photos[i].files = photos[i].files.map {
          var file = $0
          file.localPath = nil
          return file
        }
        photos[i].previewPath = nil
      }
      photos[i].hidden = true
      selection.remove(photos[i].id)
      try save()
    }
  }
  public func receive(_ receipt: ReceiveReceipt, restoreHidden: Bool = true) throws {
    let file = URL(fileURLWithPath: receipt.path)
    let relative = try relativePath(file)
    guard relative.hasPrefix("media/"), FileManager.default.fileExists(atPath: file.path),
      try file.resourceValues(forKeys: [.fileSizeKey]).fileSize.map(Int64.init) == receipt.size
    else { throw LibraryError.missingPhoto }
    let id = "ftp:" + receipt.sha256
    if let i = photos.firstIndex(where: { $0.id == id }) {
      if !photos[i].files.contains(where: { part in
        guard let path = part.localPath, let url = try? fileURL(path) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
      }) {
        photos[i].files = [CameraFile(name: receipt.name, size: receipt.size, localPath: relative)]
      }
      if restoreHidden { photos[i].hidden = false }
    } else {
      photos.append(
        Photo(
          id: id, name: receipt.name, source: .ftp,
          capturedAt: Date(timeIntervalSince1970: Double(receipt.receivedAt) / 1000),
          files: [CameraFile(name: receipt.name, size: receipt.size, localPath: relative)]))
    }
    try save()
  }
}
