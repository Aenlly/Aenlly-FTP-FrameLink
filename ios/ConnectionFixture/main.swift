import Foundation
import FrameLinkCore

/// Synthetic credentials only: the Go host test consumes the exact same encoder as NativeTransport.
@main struct ConnectionFixture {
  @MainActor static func main() async {
    let values = Array(CommandLine.arguments.dropFirst())
    if values.count == 2, values[0] == "--verify-media" {
      do { try await verifyMedia(URL(fileURLWithPath: values[1])); print("{\"passed\":true,\"jpegCacheReused\":true,\"rawBytesPreserved\":true,\"captureTimeInferred\":false}") }
      catch { FileHandle.standardError.write(Data(("media contract rejected: " + error.localizedDescription + "\n").utf8)); exit(3) }
      return
    }
    guard values.count == 4, let port = Int(values[2]) else { exit(2) }
    var config = CameraConfig(); config.protocolID = values[0]; config.host = values[1]; config.port = port
    config.fingerprint = "synthetic-sony-fingerprint"
    if values[3] != "anonymous" { config.user = "fixture-user"; config.password = values[3] == "invalid" ? "wrong-secret" : "fixture-secret" }
    do { print(try CameraConnectionPolicy.encode(config)) }
    catch { FileHandle.standardError.write(Data("configuration rejected\n".utf8)); exit(2) }
  }
  @MainActor private static func verifyMedia(_ folder: URL) async throws {
    let info = try JSONDecoder().decode(CameraInfo.self, from: Data(contentsOf: folder.appendingPathComponent("connection-info.json")))
    var config = CameraConfig(); config.protocolID = CameraProtocol.canon; config.host = "127.0.0.1"; config.port = 8080
    try CameraConnectionPolicy.validate(info: info, config: config)
    let page = try JSONDecoder().decode(CameraPage.self, from: Data(contentsOf: folder.appendingPathComponent("page.json")))
    let library = LibraryStore(root: folder.appendingPathComponent("swift-library")); try library.mergeCamera(page)
    guard page.totalCount == 1, library.photos.count == 1, let photo = library.photos.first,
      let jpeg = photo.files.first(where: { $0.formatTitle == "JPEG" }), let raw = photo.files.first(where: { $0.isRaw }),
      photo.capturedAt.timeIntervalSince1970 == 0, photo.orderingDate.timeIntervalSince1970 > 0 else { throw LibraryError.invalidPath }
    let cache = try JPEGPreviewCache(root: library.root.appendingPathComponent("previews"))
    let resource = JPEGPreviewResource(photo: photo, file: jpeg); let ticket = try await cache.begin(resource)
    let jpegSource = folder.appendingPathComponent(jpeg.name); let rawSource = folder.appendingPathComponent(raw.name)
    try FileManager.default.copyItem(at: jpegSource, to: ticket.url)
    _ = try await cache.commit(ticket, bytes: jpeg.size, sha256: LibraryStore.digest(jpegSource))
    guard !library.photos[0].imported, library.tasks.isEmpty else { throw LibraryError.busy }
    var task = TransferTask(title: "Synthetic Canon import", kind: "camera-download", photoIds: [photo.id]); task.state = .running; task.total = photo.files.reduce(0) { $0 + $1.size }
    try library.upsertTask(task); let publisher = CameraImportPublication(library: library); let operation = publisher.begin(taskID: task.id)
    let first = try await publisher.transferAndPublish(operation, photo: photo, file: jpeg, copy: { try await cache.copyForImport(resource, to: $0)?.size }, download: { _ in throw LibraryError.missingPhoto })
    let second = try await publisher.transferAndPublish(operation, photo: first.photo, file: raw, copy: { _ in nil }, download: { target in try FileManager.default.copyItem(at: rawSource, to: target); return raw.size })
    task.done = first.bytes + second.bytes; try publisher.complete(operation, task: task)
    for file in second.photo.files {
      guard let path = file.localPath, try LibraryStore.digest(library.fileURL(path)) == LibraryStore.digest(folder.appendingPathComponent(file.name)) else { throw LibraryError.invalidPath }
    }
    guard library.tasks.first?.state == .complete else { throw LibraryError.busy }
  }
}
