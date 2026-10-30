import Foundation

/// The production page owns consent and pending navigation until a connection is published.
@MainActor public final class FujiConnectionBoundary {
  private var owner: UUID?
  private var consent = false
  private var pending: UUID?
  private var ready: (() -> Void)?
  private let cancelConnection: () -> Void
  public init(cancelConnection: @escaping () -> Void) { self.cancelConnection = cancelConnection }
  @discardableResult public func open(_ page: UUID) -> Bool {
    guard owner != page else { return false }
    cancelPending(); owner = page; consent = false
    return true
  }
  public func owns(_ page: UUID) -> Bool { owner == page }
  public func permits(_ page: UUID) -> Bool { owns(page) && consent }
  public func setConsent(_ enabled: Bool, page: UUID) {
    guard owns(page) else { return }
    consent = enabled
    if !enabled { cancelPending() }
  }
  @discardableResult public func leave(_ page: UUID) -> Bool {
    guard owns(page) else { return false }
    owner = nil; consent = false; cancelPending()
    return true
  }
  public func stop() { consent = false; cancelPending() }
  public func begin(page: UUID, onReady: (() -> Void)?) -> UUID? {
    guard permits(page) else { return nil }
    cancelPending()
    let token = UUID(); pending = token; ready = onReady
    return token
  }
  /// Clears before cancellation invokes AppModel.disconnect, preventing recursive cancellation.
  private func cancelPending() {
    let existed = pending != nil
    sessionClosed()
    if existed { cancelConnection() }
  }
  /// Called by the actual session-close path; consent remains local to the open page.
  public func sessionClosed() { pending = nil; ready = nil }
  @discardableResult public func publish(_ token: UUID, _ body: () -> Void) -> Bool {
    guard pending == token, consent, owner != nil else { return false }
    let navigate = ready
    sessionClosed()
    // Publish first: the successful navigation's onDisappear must not cancel this session.
    body(); navigate?()
    return true
  }
  @discardableResult public func fail(_ token: UUID, _ body: () -> Void) -> Bool {
    guard pending == token, consent, owner != nil else { return false }
    sessionClosed(); body()
    return true
  }
}
