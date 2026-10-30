import Foundation

/// A local source change must restart metadata work even when the photo identity remains the same.
public struct PhotoMetadataRefreshKey: Hashable, Sendable {
  private let photoID: String
  private let files: [CameraFile]
  private let previewPath: String?
  private let previewRevision: Int
  public init(photo: Photo, previewRevision: Int) {
    photoID = photo.id; files = photo.files; previewPath = photo.previewPath; self.previewRevision = previewRevision
  }
}
public struct PhotoMetadataFile: Sendable {
  public let url: URL
  public let description: String
}
@MainActor public enum PhotoMetadataSource {
  /// This resolver only reads existing files/cache; it has no camera transport or download callback.
  public static func available(photo: Photo, library: LibraryStore, cache: JPEGPreviewCache?) async -> PhotoMetadataFile? {
    for file in photo.files where !file.isRaw {
      if let path = file.localPath, let url = existing(path, library: library) { return PhotoMetadataFile(url: url, description: file.name + " · 本机原片") }
    }
    if let file = photo.files.first(where: { $0.formatTitle == "JPEG" }),
      let cached = try? await cache?.lookup(JPEGPreviewResource(photo: photo, file: file)) {
      return PhotoMetadataFile(url: cached.url, description: file.name + " · 临时JPEG原片")
    }
    for file in photo.files where file.isRaw {
      if let path = file.localPath, let url = existing(path, library: library) { return PhotoMetadataFile(url: url, description: file.name + " · 本机原片") }
    }
    if let path = photo.previewPath, let url = existing(path, library: library) { return PhotoMetadataFile(url: url, description: "相机预览 · 参数可能不全") }
    return nil
  }
  private static func existing(_ path: String, library: LibraryStore) -> URL? {
    guard let url = try? library.fileURL(path), (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else { return nil }; return url
  }
}
