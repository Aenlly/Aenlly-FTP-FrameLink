import Camera
import Foundation
import FrameLinkCore

final class NativeProgress: NSObject, FLBridgeProgressProtocol {
  let update: (Int64, Int64) -> Void
  init(_ update: @escaping (Int64, Int64) -> Void) { self.update = update }
  func onProgress(_ done: Int64, total: Int64) { update(done, total) }
}
final class NativeReceiveEvents: NSObject, FLBridgeReceiverEventsProtocol {
  let receive: (String) -> Void
  init(_ receive: @escaping (String) -> Void) { self.receive = receive }
  func onReceived(_ receipt: String?) { if let receipt { receive(receipt) } }
}

/// Blocking native commands are serialized away from the main actor; live frames have their own lane.
final class NativeTransport: @unchecked Sendable {
  let session = FLBridgeNewSession()!
  private let commands = DispatchQueue(label: "app.framelink.camera.commands", qos: .userInitiated)
  private let frames = DispatchQueue(label: "app.framelink.camera.frames", qos: .userInitiated)
  private let epochLock = NSLock()
  private var epoch = UUID()
  private func currentEpoch() -> UUID {
    epochLock.lock()
    defer { epochLock.unlock() }
    return epoch
  }
  static func json<T: Encodable>(_ value: T) throws -> String {
    String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
  }
  static func decode<T: Decodable>(_ raw: String, as type: T.Type) throws -> T {
    try JSONDecoder().decode(type, from: Data(raw.utf8))
  }
  static func result<T>(_ read: (AutoreleasingUnsafeMutablePointer<NSError?>?) -> T) throws -> T {
    var error: NSError?
    let value = read(&error)
    if let error { throw error }
    return value
  }
  private func perform<T>(_ lane: DispatchQueue? = nil, _ work: @escaping () throws -> T)
    async throws -> T
  {
    let token = currentEpoch()
    return try await withCheckedThrowingContinuation { continuation in
      (lane ?? commands).async {
        do {
          guard token == self.currentEpoch() else { throw CancellationError() }
          continuation.resume(returning: try work())
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }
  func identity(_ host: String) async throws -> CameraIdentity {
    try await perform {
      try Self.decode(
        Self.result { self.session.probeIdentity(host, error: $0) }, as: CameraIdentity.self)
    }
  }
  func connect(_ config: CameraConfig) async throws -> CameraInfo {
    try await perform {
      let raw = try Self.json(config)
      return try Self.decode(
        Self.result { self.session.connect(raw, error: $0) }, as: CameraInfo.self)
    }
  }
  func page(slot: Int = 1, day: String = "", cursor: Int64) async throws -> CameraPage {
    try await perform {
      try Self.decode(
        Self.result { self.session.listNewestPage(slot, day: day, offset: cursor, error: $0) }, as: CameraPage.self)
    }
  }
  func properties() async throws -> [CameraProperty] {
    try await perform {
      try Self.decode(Self.result { self.session.properties($0) }, as: [CameraProperty].self)
    }
  }
  func set(_ property: CameraProperty, value: Int64) async throws {
    try await perform { try self.session.setProperty(property.code, value: value) }
  }
  func preview(_ photo: Photo, file: CameraFile, target: URL, large: Bool) async throws {
    try await perform {
      try self.session.preview(
        photo.slot, contentID: Int(photo.contentId), fileID: file.fileId, target: target.path,
        large: large)
    }
  }
  func download(
    _ photo: Photo, file: CameraFile, target: URL, update: @escaping (Int64, Int64) -> Void
  ) async throws -> FileReceipt {
    struct Request: Encodable {
      let slot: Int
      let contentId: Int64
      let fileId: Int
      let size: Int64
    }
    return try await perform {
      let raw = try Self.json(
        Request(slot: photo.slot, contentId: photo.contentId, fileId: file.fileId, size: file.size))
      return try Self.decode(
        Self.result {
          self.session.download(
            raw, target: target.path, progress: NativeProgress(update), error: $0)
        }, as: FileReceipt.self)
    }
  }
  func startLive() async throws { try await perform { try self.session.startLiveView() } }
  func nextFrame() async throws -> Data {
    try await perform(frames) {
      try self.session.nextFrame()
    }
  }
  func capture() async throws { try await perform { try self.session.capture(true) } }
  func stopLive() { session.stopLiveView() }
  func cancelTransfer() { session.cancelTransfer() }
  func close() {
    epochLock.lock()
    epoch = UUID()
    epochLock.unlock()
    session.close()
  }
  func connected() -> Bool { session.connected() }
}
