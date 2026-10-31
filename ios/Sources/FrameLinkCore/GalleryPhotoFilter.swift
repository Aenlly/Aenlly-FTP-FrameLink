import Foundation

/// Shared by the actual grid filter and its detail browsing input; order stays with the source list.
public enum GalleryPhotoFilter: Int, Sendable {
  case all, selected, favorites, unimported, raw
  public func matches(_ photo: Photo, selection: Set<String>) -> Bool {
    switch self {
    case .all: return true
    case .selected: return selection.contains(photo.id)
    case .favorites: return photo.favorite
    case .unimported: return photo.hasUnimportedFiles
    case .raw: return photo.files.contains { $0.isRaw }
    }
  }
}
