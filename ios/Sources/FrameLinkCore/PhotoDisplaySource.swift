import Foundation

public enum PhotoDisplaySource: String, Sendable {
  case preview, localOriginal, temporaryJPEGOriginal
  public static func classify(photo: Photo, resolved: URL, fileURL: (String) -> URL?, verifiedTemporaryJPEG: URL?) -> PhotoDisplaySource {
    let originals = photo.files.filter { !$0.isRaw }.compactMap(\.localPath).compactMap(fileURL)
    let temporary = photo.files.contains { $0.formatTitle == "JPEG" } ? verifiedTemporaryJPEG : nil
    return classify(resolved: resolved, originalFiles: originals, verifiedTemporaryJPEG: temporary)
  }
  /// Classification uses the resolved file, not `photo.imported` or a filename suffix.
  public static func classify(resolved: URL, originalFiles: [URL], verifiedTemporaryJPEG: URL?) -> PhotoDisplaySource {
    guard resolved.isFileURL else { return .preview }
    let file = resolved.standardizedFileURL
    if originalFiles.contains(where: { $0.isFileURL && $0.standardizedFileURL == file }) { return .localOriginal }
    if let verifiedTemporaryJPEG, verifiedTemporaryJPEG.isFileURL, verifiedTemporaryJPEG.standardizedFileURL == file { return .temporaryJPEGOriginal }
    return .preview
  }
}
