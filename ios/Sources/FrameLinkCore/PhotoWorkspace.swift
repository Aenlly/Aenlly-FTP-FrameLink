import Foundation
import Combine

public struct PhotoWindow: Equatable, Sendable {
  public var width: Double
  public var height: Double
  public init(width: Double, height: Double) { self.width = width.isFinite ? max(1, width) : 1; self.height = height.isFinite ? max(1, height) : 1 }
}
public struct PhotoWorkspaceLayout: Equatable, Sendable {
  public let split: Bool
  public let gridWidth: Double
  public let detailWidth: Double
  public let horizontalComparison: Bool
  public let showsFilmstrip: Bool
  public let hideNavigation: Bool
  public init(window: PhotoWindow, detailOpen: Bool, textScale: Double = 1) {
    let type = min(2.5, max(1, textScale))
    let gridMinimum = 280 * type, detailMinimum = 340 * type
    hideNavigation = detailOpen && window.width < gridMinimum + detailMinimum + 16
    split = detailOpen && window.width >= gridMinimum + detailMinimum + 16 && window.height >= 320 * min(type, 1.4)
    gridWidth = split ? min(max(gridMinimum, window.width * 0.4), window.width - detailMinimum - 12) : window.width
    detailWidth = split ? window.width - gridWidth - 12 : window.width
    horizontalComparison = detailWidth >= 560 * min(type, 1.4) && detailWidth >= window.height
    showsFilmstrip = window.height >= 460 * min(type, 1.4)
  }
  public static func gridColumns(width: Double, target: Double, spacing: Double, padding: Double) -> Int {
    max(1, Int(floor((max(1, width - padding * 2) + spacing) / (max(44, target) + spacing))))
  }
}

/// Logical identity excludes publication/cache paths: representations may change without changing the photo.
public struct PhotoResourceIdentity: Hashable, Sendable {
  public let scope: String
  public let photoID: String
  public let revision: String
  public init(scope: String, photo: Photo) {
    self.scope = scope; photoID = photo.id
    var files = photo.files
    for index in files.indices { files[index].localPath = nil }
    files.sort { left, right in left.fileId == right.fileId ? left.name < right.name : left.fileId < right.fileId }
    let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
    let data = (try? encoder.encode(files)) ?? Data()
    let encoded = String(decoding: data, as: UTF8.self)
    revision = "\(photo.source.rawValue):\(photo.slot):\(photo.contentId):\(encoded)"
  }
}
public enum PhotoRepresentationRevision {
  public static func key(photo: Photo, cacheEpoch: Int) -> String {
    let paths = photo.files.map { "\($0.fileId):\($0.name):\($0.localPath ?? "")" }.sorted().joined(separator: "|")
    return "\(photo.id):\(photo.previewPath ?? ""):\(paths):\(cacheEpoch)"
  }
}
public struct PhotoViewport: Equatable, Sendable {
  public var scale: Double
  public var centerX: Double
  public var centerY: Double
  public init(scale: Double = 1, centerX: Double = 0.5, centerY: Double = 0.5) {
    self.scale = min(6, max(1, scale.isFinite ? scale : 1))
    self.centerX = min(1, max(0, centerX.isFinite ? centerX : 0.5))
    self.centerY = min(1, max(0, centerY.isFinite ? centerY : 0.5))
  }
  public func presentation(image: PhotoWindow, viewport: PhotoWindow) -> PhotoViewportPresentation {
    let fit = min(viewport.width / image.width, viewport.height / image.height)
    let width = image.width * fit * scale, height = image.height * fit * scale
    let insetX = max(0, (viewport.width - width) / 2), insetY = max(0, (viewport.height - height) / 2)
    let x = min(max(-insetX, centerX * width - viewport.width / 2), max(-insetX, width - viewport.width + insetX))
    let y = min(max(-insetY, centerY * height - viewport.height / 2), max(-insetY, height - viewport.height + insetY))
    return PhotoViewportPresentation(baseWidth: image.width * fit, baseHeight: image.height * fit,
      contentWidth: width, contentHeight: height, insetX: insetX, insetY: insetY, offsetX: x, offsetY: y)
  }
}
public struct PhotoViewportPresentation: Equatable, Sendable {
  public let baseWidth, baseHeight, contentWidth, contentHeight, insetX, insetY, offsetX, offsetY: Double
  public func normalizedCenter(viewport: PhotoWindow, scale: Double) -> PhotoViewport {
    PhotoViewport(scale: scale, centerX: (offsetX + viewport.width / 2) / max(1, contentWidth), centerY: (offsetY + viewport.height / 2) / max(1, contentHeight))
  }
}

@MainActor public final class PhotoViewportRecord: ObservableObject {
  @Published public var value = PhotoViewport()
  public init() {}
}

@MainActor public final class PhotoWorkspace: ObservableObject {
  public let previews = PhotoPreviewResolver()
  @Published public private(set) var scope = ""
  @Published public private(set) var currentID: String?
  @Published public private(set) var isOpen = false
  @Published public private(set) var comparison: [String] = []
  @Published public private(set) var focusedSide = 0
  @Published public private(set) var browsingIDs: [String] = []
  @Published public var toolsVisible = true
  @Published public var filesInfo = false
  @Published public var gridAnchor: String?
  private var viewports: [PhotoResourceIdentity: PhotoViewportRecord] = [:]
  private var resourceByPhoto: [String: PhotoResourceIdentity] = [:]
  private var available = Set<String>()
  private var explicitBrowsing = false
  public init() {}
  public var visibleIDs: [String] { comparison.count == 2 ? comparison : currentID.map { [$0] } ?? [] }
  public var focusedID: String? { comparison.count == 2 ? comparison[min(1, max(0, focusedSide))] : currentID }
  /// A current photo that stops matching is temporarily pinned; no other unfiltered photo is added.
  public var detailIDs: [String] {
    if isOpen, let currentID, available.contains(currentID), !browsingIDs.contains(currentID) { return [currentID] + browsingIDs }
    return browsingIDs
  }
  public var currentOutsideFilter: Bool { currentID.map { !browsingIDs.contains($0) } ?? false }
  public func reconcile(scope: String, available: Set<String>, browsing: [String]? = nil) {
    self.available = available
    explicitBrowsing = browsing != nil
    var seen = Set<String>()
    let order = (browsing ?? available.sorted()).filter { available.contains($0) && seen.insert($0).inserted }
    if browsingIDs != order { browsingIDs = order }
    if self.scope != scope {
      self.scope = scope; currentID = nil; isOpen = false; comparison = []; focusedSide = 0
      filesInfo = false; toolsVisible = true; gridAnchor = nil; viewports = [:]; resourceByPhoto = [:]
    }
    if let id = currentID, !available.contains(id) { close(); currentID = nil }
    if comparison.contains(where: { !detailIDs.contains($0) }) { endComparison() }
    if let anchor = gridAnchor, !available.contains(anchor) { gridAnchor = nil }
    viewports = viewports.filter { available.contains($0.key.photoID) }
    resourceByPhoto = resourceByPhoto.filter { available.contains($0.key) }
  }
  public func open(_ id: String) { guard browsingIDs.contains(id) else { return }; currentID = id; isOpen = true; comparison = [] }
  public func close() { isOpen = false; comparison = []; filesInfo = false }
  public func startComparison(_ ids: [String]) {
    let allowed = Set(detailIDs)
    var seen = Set<String>(); let pair = ids.filter { allowed.contains($0) && seen.insert($0).inserted }
    guard pair.count >= 2 else { return }
    if !isOpen || currentID == nil { currentID = pair[0] }
    comparison = Array(pair.prefix(2)); focusedSide = 0; isOpen = true
  }
  public func endComparison() { comparison = []; focusedSide = 0 }
  public func focus(_ side: Int) { focusedSide = min(1, max(0, side)) }
  public func tapThumbnail(_ id: String) {
    guard detailIDs.contains(id) else { return }
    if comparison.count == 2 {
      let other = 1 - focusedSide
      if comparison[other] == id { comparison.swapAt(0, 1) }
      else { comparison[focusedSide] = id }
    } else { currentID = id }
  }
  public func step(_ delta: Int, orderedIDs: [String]) {
    let supplied = Set(orderedIDs)
    let order = explicitBrowsing ? detailIDs.filter { supplied.contains($0) } : orderedIDs.filter { available.contains($0) }
    guard comparison.isEmpty, let id = currentID, let index = order.firstIndex(of: id), !order.isEmpty else { return }
    currentID = order[min(order.count - 1, max(0, index + delta))]
  }
  public func viewport(for identity: PhotoResourceIdentity) -> PhotoViewport { viewports[identity]?.value ?? PhotoViewport() }
  public func record(for identity: PhotoResourceIdentity) -> PhotoViewportRecord {
    guard identity.scope == scope else { return PhotoViewportRecord() }
    if let record = viewports[identity] { return record }
    viewports = viewports.filter { $0.key.photoID != identity.photoID }
    resourceByPhoto[identity.photoID] = identity
    let record = PhotoViewportRecord(); viewports[identity] = record
    return record
  }
  public func setViewport(_ value: PhotoViewport, for identity: PhotoResourceIdentity) {
    guard identity.scope == scope else { return }
    if let current = resourceByPhoto[identity.photoID], current != identity { return }
    record(for: identity).value = PhotoViewport(scale: value.scale, centerX: value.centerX, centerY: value.centerY)
  }
}

/// At most two visible panes; image decoding is separately serialized by the App renderer.
public enum PhotoDecodePolicy {
  public static func maxPixel(comparing: Bool, source: PhotoDisplaySource = .preview) -> Int { comparing ? 2048 : source == .preview ? 4096 : 8192 }
  public static let concurrentDecodes = 1
  public static func thumbnailPixel(width: Double, height: Double, displayScale: Double, large: Bool) -> Int {
    let edge = max(width, height) * max(1, displayScale)
    let bounded = min(large ? 2048.0 : 720.0, max(64, edge.isFinite ? edge : 64))
    return min(large ? 2048 : 720, Int(ceil(bounded / 64)) * 64)
  }
}

/// Geometry and tool changes are not render revisions and never resolve a new photo resource.
public actor PhotoPreviewResolver {
  private struct Flight { let id: UUID; let task: Task<URL?, Never>; var waiters: Set<UUID> }
  private var epoch = UUID()
  private var values: [String: URL] = [:]
  private var flights: [String: Flight] = [:]
  public init() {}
  public func activeWaiters() -> Int { flights.values.reduce(0) { $0 + $1.waiters.count } }
  public func resolve(identity: PhotoResourceIdentity, representation: String, provider: @escaping @Sendable () async -> URL?) async -> URL? {
    let key = identity.scope + "\u{0}" + identity.photoID + "\u{0}" + identity.revision + "\u{0}" + representation
    if let url = values[key], FileManager.default.fileExists(atPath: url.path) { return url }
    let token = epoch; let waiter = UUID()
    var flight = flights[key] ?? Flight(id: UUID(), task: Task { await provider() }, waiters: [])
    flight.waiters.insert(waiter); flights[key] = flight
    let flightID = flight.id; let task = flight.task
    let result = await withTaskCancellationHandler(operation: { await task.value }, onCancel: {
      Task { await self.release(key: key, flightID: flightID, waiter: waiter) }
    })
    release(key: key, flightID: flightID, waiter: waiter)
    guard epoch == token, !Task.isCancelled else { return nil }
    if let result { if values.count >= 32 { values.removeAll() }; values[key] = result }
    return result
  }
  private func release(key: String, flightID: UUID, waiter: UUID) {
    guard var flight = flights[key], flight.id == flightID else { return }
    flight.waiters.remove(waiter)
    if flight.waiters.isEmpty { flight.task.cancel(); flights[key] = nil }
    else { flights[key] = flight }
  }
  public func invalidate() { epoch = UUID(); flights.values.forEach { $0.task.cancel() }; flights = [:]; values = [:] }
}
