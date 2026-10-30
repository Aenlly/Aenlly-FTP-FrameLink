import Foundation

/// Implementations allocate without sockets; only an explicit Start may listen/register.
public protocol FujiReceiverInstance: AnyObject, Sendable {
  func start(localIPv4: String, importerName: String) async throws
  func nextInvitation(timeoutMilliseconds: Int) async throws -> String
  func close() async
}
public enum FujiReceiverPhase: Sendable, Equatable { case disabled, starting, listening, invited, failed }
public struct FujiReceiverState: Sendable {
  public var phase: FujiReceiverPhase = .disabled
  public var message = "未开启"
  public init() {}
}

/// One pending batch requires a fresh IMPORT invitation; a saved address never starts discovery.
@MainActor public final class FujiAutoSaveController {
  // A conservative local confirmation window, not a guarantee of camera queue retention.
  private static let invitationLifetime: TimeInterval = 30
  public private(set) var state = FujiReceiverState()
  public private(set) var invitation: CameraConfig?
  private let make: () -> any FujiReceiverInstance
  private let changed: (FujiReceiverState) -> Void
  private let now: () -> Date
  private var invitationExpires: Date?
  private var generation = UUID()
  private var receiver: (any FujiReceiverInstance)?
  private var work: Task<Void, Never>?
  private var closing: Task<Void, Never>?
  public init(make: @escaping () -> any FujiReceiverInstance, now: @escaping () -> Date = Date.init, changed: @escaping (FujiReceiverState) -> Void = { _ in }) {
    self.make = make; self.now = now; self.changed = changed
  }
  public static func decodeInvitation(_ raw: String) throws -> CameraConfig {
    struct Scope: Decodable { let mediaScope: String }
    let data = Data(raw.utf8)
    let input = try JSONDecoder().decode(CameraConfig.self, from: data)
    guard input.protocolID == CameraProtocol.fuji,
      try JSONDecoder().decode(Scope.self, from: data).mediaScope == "AUTOSAVE_PENDING_BATCH" else { throw CameraConnectionError.invitationRequired }
    return try CameraConnectionPolicy.wireConfig(input)
  }
  public func start(localIPv4: String, importerName: String, experimental: Bool, timeoutMilliseconds: Int = 60000) {
    stop()
    guard experimental, (1...60000).contains(timeoutMilliseconds) else {
      publish(.failed, "实验接收默认关闭，请主动确认后开启"); return
    }
    let token = generation
    let previousClose = closing
    let instance = make()
    receiver = instance
    publish(.starting, "正在开启实验接收")
    work = Task {
      await previousClose?.value
      guard token == generation else { await instance.close(); return }
      do {
        try await instance.start(localIPv4: localIPv4, importerName: importerName)
        guard token == generation else { await instance.close(); return }
        publish(.listening, "请在机身回放菜单启动PC AutoSave；正在等待传输邀请")
        let raw = try await instance.nextInvitation(timeoutMilliseconds: timeoutMilliseconds)
        await instance.close()
        guard token == generation else { return }
        let config = try Self.decodeInvitation(raw)
        receiver = nil; work = nil; invitation = config
        invitationExpires = now().addingTimeInterval(Self.invitationLifetime)
        publish(.invited, "收到 \(config.fujiDeviceName) 的待传批次；监听已关闭，请在30秒内确认")
      } catch {
        await instance.close()
        guard token == generation else { return }
        receiver = nil; work = nil; invitation = nil
        publish(.failed, "接收已停止：\(error.localizedDescription)。请核对局域网权限、广播签名授权和机身AutoSave模式")
      }
    }
  }
  public func takeInvitation() -> CameraConfig? {
    guard state.phase == .invited else { return nil }
    guard let expiry = invitationExpires, now() < expiry else {
      invitation = nil; invitationExpires = nil
      publish(.failed, "传输邀请已过期，请重新开启接收并在机身启动PC AutoSave")
      return nil
    }
    let config = invitation
    invitation = nil; invitationExpires = nil
    publish(.disabled, "接收监听已关闭")
    return config
  }
  public func stop() {
    generation = UUID(); work?.cancel(); work = nil; invitation = nil; invitationExpires = nil
    let instance = receiver; receiver = nil
    let previousClose = closing
    closing = Task { await previousClose?.value; await instance?.close() }
    publish(.disabled, "接收监听已关闭")
  }
  public func waitUntilClosed() async { await closing?.value }
  private func publish(_ phase: FujiReceiverPhase, _ message: String) {
    state.phase = phase; state.message = message; changed(state)
  }
}
