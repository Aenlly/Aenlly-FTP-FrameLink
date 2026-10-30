import Foundation

public struct CameraIndexScope: Equatable, Sendable {
  public let deviceID: String
  public let source: Int
  public let slot: Int
  public let day: String
  public let mediaScope: String?
  public init(deviceID: String, source: Int = 1, slot: Int = 1, day: String = "", mediaScope: String? = nil) {
    self.deviceID = deviceID; self.source = source; self.slot = slot; self.day = day; self.mediaScope = mediaScope
  }
}
public struct CameraIndexState: Sendable {
  public var scope: CameraIndexScope?
  public var ids: Set<String> = []
  public var totalCount: Int?
  public var mediaScope: String?
  public var loading = false
  public var hasMore = false
  public var complete = false
  public var error: String?
  fileprivate var cursor: Int64 = 0
  public init() {}
  public var rangeLabel: String {
    if mediaScope == "AUTOSAVE_PENDING_BATCH" { return "待传批次" }
    guard mediaScope == nil || mediaScope?.isEmpty == true else { return "当前相机索引范围" }
    guard let scope else { return "相机索引范围" }
    return "相机索引 · 卡\(scope.slot) · " + (scope.day.isEmpty ? "全部日期" : scope.day)
  }
  public func countText(matched: Int, filtered: Bool) -> (title: String, detail: String) {
    guard scope != nil else { return ("相机未连接", "连接后显示当前范围数量") }
    let total = totalCount ?? (complete ? ids.count : nil)
    let progress = error != nil ? "读取中断，可重试" : complete ? "索引已完整" : "正在补齐索引"
    if filtered {
      return (complete ? "\(matched) 张" : "已匹配 \(matched) 张", rangeLabel + " · " + (total.map { "范围共\($0)张 · " } ?? "范围总数未知 · ") + (complete ? "筛选结果" : progress))
    }
    return (total.map { "共 \($0) 张" } ?? "已读取 \(ids.count) 张", rangeLabel + " · " + (complete ? "索引已完整" : (total == nil ? "总数未知 · " : "") + "已读取\(ids.count)张 · " + progress))
  }
}
public enum CameraIndexError: LocalizedError {
  case noProgress, incomplete, stopped
  public var errorDescription: String? {
    switch self {
    case .noProgress: return "相机索引未前进，已停止重复读取，请刷新或重试"
    case .incomplete: return "相机列表数量已变化，请刷新索引"
    case .stopped: return "相机索引读取已中断，请重试"
    }
  }
}
public enum CameraIndexLoadIntent: Equatable, Sendable {
  case automatic, userRetry, refresh
}

/// Background filling, scrolling and select-all join the same metadata-only page flight.
@MainActor public final class CameraIndexLoader {
  public private(set) var state = CameraIndexState()
  private let library: LibraryStore
  private let fetch: (CameraIndexScope, Int64) async throws -> CameraPage
  private let changed: (CameraIndexState) -> Void
  private var generation = UUID()
  private var seenCursors: Set<Int64> = []
  private var flight: (id: UUID, task: Task<Void, Error>)?
  private var background: Task<Void, Never>?
  public init(library: LibraryStore, fetch: @escaping (CameraIndexScope, Int64) async throws -> CameraPage, changed: @escaping (CameraIndexState) -> Void = { _ in }) {
    self.library = library; self.fetch = fetch; self.changed = changed
  }
  public func reset(scope: CameraIndexScope) {
    invalidate(); state.scope = scope; state.mediaScope = scope.mediaScope; state.hasMore = true; publish()
  }
  public func invalidate() {
    generation = UUID(); background?.cancel(); background = nil; flight?.task.cancel(); flight = nil
    seenCursors = []; state = CameraIndexState(); publish()
  }
  /// Apply intent when execution reaches the loader, not when a UI callback schedules its Task.
  public func requestNext(intent: CameraIndexLoadIntent) async throws {
    try await loadNext(retry: intent != .automatic)
  }
  public func loadNext(retry: Bool = false) async throws {
    try Task.checkCancellation()
    if let flight { try await flight.task.value; try Task.checkCancellation(); return }
    guard let scope = state.scope else { throw CancellationError() }
    if state.error != nil && !retry { throw CameraIndexError.stopped }
    guard state.hasMore else { return }
    let epoch = generation; let cursor = state.cursor; let id = UUID()
    state.error = nil; state.loading = true; publish()
    let work = Task { @MainActor in
      defer {
        if generation == epoch, flight?.id == id { flight = nil; state.loading = false; publish() }
      }
      do {
        let page = try await fetch(scope, cursor)
        try Task.checkCancellation()
        guard generation == epoch, state.scope == scope else { throw CancellationError() }
        guard page.nextCursor >= 0 else { throw CameraIndexError.noProgress }
        let ids = Set(page.items.map(\.id)); let combined = state.ids.union(ids)
        if page.nextCursor != 0 && (page.nextCursor == cursor || seenCursors.contains(page.nextCursor) || combined.count == state.ids.count) { throw CameraIndexError.noProgress }
        if let total = page.totalCount, total < combined.count || (page.nextCursor == 0 && total != combined.count) { throw CameraIndexError.incomplete }
        try library.mergeCamera(page)
        state.ids = combined; state.totalCount = page.totalCount; state.cursor = page.nextCursor
        if let mediaScope = page.mediaScope { state.mediaScope = mediaScope }
        state.hasMore = page.nextCursor != 0; state.complete = !state.hasMore
        seenCursors.insert(cursor); publish()
      } catch {
        guard generation == epoch else { throw CancellationError() }
        state.totalCount = nil; state.complete = false; state.error = error.localizedDescription; publish()
        throw error
      }
    }
    flight = (id, work)
    try await work.value
    try Task.checkCancellation()
  }
  public func fillAll(retry: Bool = false) async throws {
    let epoch = generation
    while state.hasMore {
      try Task.checkCancellation()
      guard epoch == generation, state.scope != nil else { throw CancellationError() }
      try await loadNext(retry: retry)
      guard epoch == generation else { throw CancellationError() }
    }
    try Task.checkCancellation()
    guard epoch == generation, state.scope != nil else { throw CancellationError() }
  }
  @discardableResult public func startBackground() -> Task<Void, Never>? {
    guard background == nil, state.hasMore, state.error == nil else { return background }
    let epoch = generation
    let work = Task { @MainActor [weak self] in
      guard let self else { return }
      do { try await fillAll() } catch { }
      if generation == epoch { background = nil }
    }
    background = work; return work
  }
  private func publish() { changed(state) }
}
