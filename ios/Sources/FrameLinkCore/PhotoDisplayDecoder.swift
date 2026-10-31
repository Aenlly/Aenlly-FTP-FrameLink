import Foundation
import ImageIO
import CoreGraphics

public struct PhotoDecodedImage: @unchecked Sendable {
  public let image: CGImage
  public var width: Int { image.width }
  public var height: Int { image.height }
}
public struct PhotoDecodeStatistics: Sendable {
  public var active = 0
  public var peakConcurrent = 0
  public var attempts = 0
  public var largestDecodedEdge = 0
}
private final class PhotoDecodeCancellation: @unchecked Sendable {
  private let lock = NSLock()
  private var stopped = false
  func cancel() { lock.lock(); stopped = true; lock.unlock() }
  var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
}

/// The actual App detail decoder: one ImageIO decode at a time, no full UIImage/file decode.
public final class PhotoDisplayDecoder: @unchecked Sendable {
  public static let shared = PhotoDisplayDecoder()
  public static let thumbnails = PhotoDisplayDecoder(limit: 720)
  private let lane: DispatchQueue
  private let lock = NSLock()
  private var stats = PhotoDecodeStatistics()
  private let limit: Int
  public init() { limit = 4096; lane = DispatchQueue(label: "app.framelink.photo-detail.decode", qos: .userInitiated) }
  private init(limit: Int) { self.limit = limit; lane = DispatchQueue(label: "app.framelink.photo-thumbnail.decode", qos: .utility) }
  public func statistics() -> PhotoDecodeStatistics { lock.lock(); defer { lock.unlock() }; return stats }
  public func decode(_ url: URL, maxPixel: Int) async -> PhotoDecodedImage? {
    let cancellation = PhotoDecodeCancellation()
    let limit = min(self.limit, max(1, maxPixel))
    return await withTaskCancellationHandler(operation: {
      await withCheckedContinuation { continuation in
        lane.async {
          guard !cancellation.cancelled else { continuation.resume(returning: nil); return }
          self.lock.lock(); self.stats.active += 1; self.stats.attempts += 1
          self.stats.peakConcurrent = max(self.stats.peakConcurrent, self.stats.active); self.lock.unlock()
          let image: CGImage? = autoreleasepool {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
            return CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
              kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceShouldCacheImmediately: true,
              kCGImageSourceThumbnailMaxPixelSize: limit] as CFDictionary)
          }
          self.lock.lock(); self.stats.active -= 1
          if let image { self.stats.largestDecodedEdge = max(self.stats.largestDecodedEdge, max(image.width, image.height)) }
          self.lock.unlock()
          continuation.resume(returning: cancellation.cancelled ? nil : image.map { PhotoDecodedImage(image: $0) })
        }
      }
    }, onCancel: { cancellation.cancel() })
  }
}
