import CryptoKit
import Foundation
import ImageIO

/// Sony photoID already includes camera identity, card slot, content ID and capture time.
public struct JPEGPreviewResource: Codable, Equatable, Sendable {
  public let photoID: String
  public let slot: Int
  public let contentID: Int64
  public let file: CameraFile
  public init(photo: Photo, file: CameraFile) {
    photoID = photo.id; slot = photo.slot; contentID = photo.contentId
    var remote = file; remote.localPath = nil; self.file = remote
  }
  public var isJPEG: Bool { ["JPG", "JPEG"].contains((file.name as NSString).pathExtension.uppercased()) }
  fileprivate var key: String {
    let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
    return SHA256.hash(data: (try? encoder.encode(self)) ?? Data()).map { String(format: "%02x", $0) }.joined()
  }
}
public struct JPEGCacheFile: Sendable {
  public let url: URL
  public let size: Int64
  public let sha256: String
}
public struct JPEGCacheTicket: Sendable {
  public let url: URL
  fileprivate let epoch: UUID
  fileprivate let resource: JPEGPreviewResource
  fileprivate let photoRevision: Int
}
public enum JPEGCacheError: Error { case invalidResource, invalidReceipt, cleared, tooLarge }

/// Owns only disposable previews. Photo originals, import state and transfer tasks are outside this actor.
public actor JPEGPreviewCache {
  public static let defaultLimit: Int64 = 2 * 1024 * 1024 * 1024
  private let root: URL
  private let originals: URL
  private let limit: Int64
  private var epoch = UUID()
  private var photoRevisions: [String: Int] = [:]
  private struct Entry: Codable {
    let resource: JPEGPreviewResource
    let size: Int64
    let sha256: String
  }
  public init(root: URL, limit: Int64 = JPEGPreviewCache.defaultLimit) throws {
    self.root = root; self.originals = root.appendingPathComponent("jpeg-originals", isDirectory: true); self.limit = limit
    try FileManager.default.createDirectory(at: originals, withIntermediateDirectories: true)
    // Previous-process staging files are never valid cache entries.
    for url in try FileManager.default.contentsOfDirectory(at: originals, includingPropertiesForKeys: nil)
      where url.lastPathComponent.hasPrefix("staging-") || url.pathExtension == "part" { try? FileManager.default.removeItem(at: url) }
    var folder = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try folder.setResourceValues(values)
  }
  private func dataURL(_ resource: JPEGPreviewResource) -> URL { originals.appendingPathComponent(resource.key + ".jpg") }
  private func entryURL(_ resource: JPEGPreviewResource) -> URL { originals.appendingPathComponent(resource.key + ".json") }
  public func lookup(_ resource: JPEGPreviewResource) throws -> JPEGCacheFile? {
    guard resource.isJPEG else { return nil }
    let target = dataURL(resource); let metadata = entryURL(resource)
    guard FileManager.default.fileExists(atPath: target.path) else { return nil }
    guard let entry = try? JSONDecoder().decode(Entry.self, from: Data(contentsOf: metadata)), entry.resource == resource,
      entry.size == (try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init),
      resource.file.size == 0 || entry.size == resource.file.size,
      (try? LibraryStore.digest(target)) == entry.sha256 else {
      try? FileManager.default.removeItem(at: target); try? FileManager.default.removeItem(at: metadata); return nil
    }
    try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
    return JPEGCacheFile(url: target, size: entry.size, sha256: entry.sha256)
  }
  public func begin(_ resource: JPEGPreviewResource) throws -> JPEGCacheTicket {
    guard resource.isJPEG, resource.file.size >= 0 else { throw JPEGCacheError.invalidResource }
    guard resource.file.size <= limit else { throw JPEGCacheError.tooLarge }
    try FileManager.default.createDirectory(at: originals, withIntermediateDirectories: true)
    return JPEGCacheTicket(url: originals.appendingPathComponent("staging-" + UUID().uuidString + ".jpg"), epoch: epoch, resource: resource, photoRevision: photoRevisions[resource.photoID, default: 0])
  }
  public func discard(_ ticket: JPEGCacheTicket) {
    try? FileManager.default.removeItem(at: ticket.url)
    try? FileManager.default.removeItem(at: ticket.url.appendingPathExtension("part"))
  }
  public func commit(_ ticket: JPEGCacheTicket, bytes: Int64, sha256: String) throws -> JPEGCacheFile {
    defer { discard(ticket) }
    guard ticket.epoch == epoch, ticket.photoRevision == photoRevisions[ticket.resource.photoID, default: 0] else { throw JPEGCacheError.cleared }
    let resource = ticket.resource
    guard bytes > 0, bytes <= limit else { throw JPEGCacheError.tooLarge }
    guard resource.file.size == 0 || resource.file.size == bytes,
      (try ticket.url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) == bytes,
      try LibraryStore.digest(ticket.url) == sha256,
      let source = CGImageSourceCreateWithURL(ticket.url as CFURL, nil),
      CGImageSourceGetType(source) as String? == "public.jpeg" else { throw JPEGCacheError.invalidReceipt }
    let target = dataURL(resource); let metadata = entryURL(resource)
    try? FileManager.default.removeItem(at: target)
    try FileManager.default.moveItem(at: ticket.url, to: target)
    do {
      try JSONEncoder().encode(Entry(resource: resource, size: bytes, sha256: sha256)).write(to: metadata, options: .atomic)
      try trim(protecting: target)
    } catch { try? FileManager.default.removeItem(at: target); try? FileManager.default.removeItem(at: metadata); throw error }
    return JPEGCacheFile(url: target, size: bytes, sha256: sha256)
  }
  /// A verified cache hit is copied to the import destination; the caller alone updates import state.
  public func copyForImport(_ resource: JPEGPreviewResource, to target: URL) throws -> JPEGCacheFile? {
    guard let cached = try lookup(resource) else { return nil }
    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
    if FileManager.default.fileExists(atPath: target.path) {
      guard try LibraryStore.digest(target) == cached.sha256 else { throw CocoaError(.fileWriteFileExists) }
      return JPEGCacheFile(url: target, size: cached.size, sha256: cached.sha256)
    }
    let part = target.deletingLastPathComponent().appendingPathComponent(".cached-" + UUID().uuidString + ".part")
    defer { try? FileManager.default.removeItem(at: part) }
    try FileManager.default.copyItem(at: cached.url, to: part)
    guard try LibraryStore.digest(part) == cached.sha256 else { throw JPEGCacheError.invalidReceipt }
    guard !FileManager.default.fileExists(atPath: target.path) else { throw CocoaError(.fileWriteFileExists) }
    try FileManager.default.moveItem(at: part, to: target)
    return JPEGCacheFile(url: target, size: cached.size, sha256: cached.sha256)
  }
  public func usage() throws -> Int64 { try files().reduce(0) { $0 + $1.bytes } }
  public func clear() throws {
    epoch = UUID()
    for url in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) { try FileManager.default.removeItem(at: url) }
    try FileManager.default.createDirectory(at: originals, withIntermediateDirectories: true)
  }
  public func remove(photoIDs: Set<String>) throws {
    for id in photoIDs { photoRevisions[id, default: 0] += 1 }
    for url in try FileManager.default.contentsOfDirectory(at: originals, includingPropertiesForKeys: nil) where url.pathExtension == "json" {
      guard let entry = try? JSONDecoder().decode(Entry.self, from: Data(contentsOf: url)), photoIDs.contains(entry.resource.photoID) else { continue }
      let data = dataURL(entry.resource)
      if FileManager.default.fileExists(atPath: data.path) { try FileManager.default.removeItem(at: data) }
      try FileManager.default.removeItem(at: url)
    }
  }
  public func trim() throws { try trim(protecting: nil) }
  private func trim(protecting: URL?) throws {
    let items = try files(); var total = items.reduce(Int64(0)) { $0 + $1.bytes }
    for item in items.sorted(by: { $0.modified < $1.modified }) where total > limit {
      // Native transfers own their staging file until commit/discard.
      guard item.url != protecting, !item.url.lastPathComponent.hasPrefix("staging-"), item.url.pathExtension != "part" else { continue }
      try FileManager.default.removeItem(at: item.url); total -= item.bytes
      if item.url.deletingLastPathComponent() == originals, item.url.pathExtension == "jpg" {
        try? FileManager.default.removeItem(at: item.url.deletingPathExtension().appendingPathExtension("json"))
      }
    }
  }
  private struct DiskFile { let url: URL; let bytes: Int64; let modified: Date }
  private func files() throws -> [DiskFile] {
    guard let items = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]) else { return [] }
    return try items.compactMap { item in
      guard let url = item as? URL else { return nil }
      let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
      guard values.isRegularFile == true, url.pathExtension != "json" else { return nil }
      return DiskFile(url: url, bytes: Int64(values.fileSize ?? 0), modified: values.contentModificationDate ?? .distantPast)
    }
  }
}
