import Foundation

public enum PhotoSource: String, Codable, Sendable { case camera, imported, ftp, capture }

public struct CameraFile: Codable, Hashable, Sendable {
  public var fileId: Int
  public var name: String
  public var size: Int64
  public var localPath: String?
  public var cameraPath: String?
  public var cameraDay: String?
  public init(
    fileId: Int = 0, name: String, size: Int64 = 0, localPath: String? = nil,
    cameraPath: String? = nil, cameraDay: String? = nil
  ) {
    self.fileId = fileId
    self.name = name
    self.size = size
    self.localPath = localPath
    self.cameraPath = cameraPath
    self.cameraDay = cameraDay
  }
  public var isRaw: Bool {
    ["ARW", "DNG", "NEF", "CR2", "CR3", "RAF"].contains(
      (name as NSString).pathExtension.uppercased())
  }
  public var isPhoto: Bool {
    isRaw
      || ["JPG", "JPEG", "PNG", "HIF", "HEIF", "HEIC"].contains(
        (name as NSString).pathExtension.uppercased())
  }
}

public struct Photo: Codable, Identifiable, Hashable, Sendable {
  public var id: String
  public var name: String
  public var source: PhotoSource
  public var capturedAt: Date
  public var files: [CameraFile]
  public var previewPath: String?
  public var favorite: Bool
  public var hidden: Bool
  public var slot: Int
  public var contentId: Int64
  public init(
    id: String, name: String, source: PhotoSource, capturedAt: Date = Date(), files: [CameraFile],
    previewPath: String? = nil, favorite: Bool = false, hidden: Bool = false, slot: Int = 1,
    contentId: Int64 = 0
  ) {
    self.id = id
    self.name = name
    self.source = source
    self.capturedAt = capturedAt
    self.files = files
    self.previewPath = previewPath
    self.favorite = favorite
    self.hidden = hidden
    self.slot = slot
    self.contentId = contentId
  }
  public var imported: Bool { files.contains { $0.localPath != nil } }
  public var inLibrary: Bool { !hidden && (imported || source == .capture) }
}

public struct SelectionBatch: Codable, Identifiable, Sendable {
  public var id: String
  public var title: String
  public var photos: [Photo]
  public var createdAt: Date
  public var updatedAt: Date
  public init(title: String, photos: [Photo]) {
    id = UUID().uuidString
    self.title = title
    self.photos = photos
    createdAt = Date()
    updatedAt = Date()
  }
}

public struct CustomerChoice: Codable, Sendable {
  public var selected = false
  public var comment = ""
  public init() {}
}
public struct CustomerReview: Codable, Identifiable, Sendable {
  public var id: String
  public var title: String
  public var photos: [Photo]
  public var choices: [String: CustomerChoice]
  public var locked: Bool
  public var createdAt: Date
  public var updatedAt: Date
  public init(title: String, photos: [Photo]) {
    id = UUID().uuidString
    self.title = title
    self.photos = photos
    choices = [:]
    locked = false
    createdAt = Date()
    updatedAt = Date()
  }
}

public enum TransferState: String, Codable, Sendable {
  case queued, running, complete, failed, interrupted, cancelled
}
public struct TransferTask: Codable, Identifiable, Sendable {
  public var id = UUID().uuidString
  public var title: String
  public var kind: String
  public var photoIds: [String]
  public var payload: [String: String]
  public var state: TransferState = .queued
  public var done: Int64 = 0
  public var total: Int64 = 0
  public var error = ""
  public var createdAt = Date()
  public init(title: String, kind: String, photoIds: [String] = [], payload: [String: String] = [:])
  {
    self.title = title
    self.kind = kind
    self.photoIds = photoIds
    self.payload = payload
  }
}

public struct CameraInfo: Codable, Sendable {
  public let id: String
  public let model: String
  public let firmware: String
  public let canBrowse: Bool
  public let canDownload: Bool
  public let canPreview: Bool
  public let canCapture: Bool
  public let canLiveView: Bool
}
public struct RemotePhoto: Codable, Sendable {
  public let id: String
  public let contentId: Int64
  public let slot: Int
  public let capturedAt: Int64
  public let files: [CameraFile]
  public func photo() -> Photo {
    Photo(
      id: id, name: files.first?.name ?? "照片", source: .camera,
      capturedAt: Date(timeIntervalSince1970: Double(capturedAt) / 1000), files: files, slot: slot,
      contentId: contentId)
  }
}
public struct CameraPage: Codable, Sendable {
  public let items: [RemotePhoto]
  public let nextCursor: Int64
}
public struct CameraProperty: Codable, Identifiable, Sendable {
  public var id: Int { code }
  public let code: Int
  public let name: String
  public let value: Int64
  public let label: String
  public let writable: Bool
  public let choices: [PropertyChoice]
}
public struct PropertyChoice: Codable, Hashable, Sendable {
  public let value: Int64
  public let label: String
}
public struct CameraIdentity: Codable, Sendable {
  public let host: String
  public let port: Int
  public let fingerprint: String
  public let algorithm: String
}
public struct CameraConfig: Codable, Sendable {
  public var host = ""
  public var port = 15740
  public var user = ""
  public var password = ""
  public var fingerprint = ""
  public var guid = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
  public init() {}
}
public struct FTPProfile: Codable, Sendable {
  public var host = ""
  public var port = 21
  public var user = ""
  public var password = ""
  public var tls = false
  public init() {}
}
public struct FTPEntry: Codable, Identifiable, Sendable {
  public var id: String { path }
  public let name: String
  public let path: String
  public let directory: Bool
  public let size: Int64
}
public struct FileReceipt: Codable, Sendable {
  public let path: String
  public let size: Int64
  public let sha256: String
}
public struct ReceiveReceipt: Codable, Sendable {
  public let id: String
  public let name: String
  public let path: String
  public let size: Int64
  public let sha256: String
  public let receivedAt: Int64
}

public struct SelectionRange: Sendable {
  public let groups: [[String]]
  public let original: Set<String>
  public let anchor: Int
  public let selecting: Bool
  public init(groups: [[String]], original: Set<String>, anchor: Int) {
    precondition(groups.indices.contains(anchor) && groups.allSatisfy { !$0.isEmpty })
    self.groups = groups
    self.original = original
    self.anchor = anchor
    selecting = groups[anchor].contains { !original.contains($0) }
  }
  public func selection(at index: Int) -> Set<String> {
    precondition(groups.indices.contains(index))
    let affected = Set(groups[min(anchor, index)...max(anchor, index)].flatMap { $0 })
    return selecting ? original.union(affected) : original.subtracting(affected)
  }
}
