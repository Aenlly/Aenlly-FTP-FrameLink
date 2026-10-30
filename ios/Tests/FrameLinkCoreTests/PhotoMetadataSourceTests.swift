import ImageIO
import XCTest
@testable import FrameLinkCore

@MainActor final class PhotoMetadataSourceTests: XCTestCase {
  func testSamePhotoRefreshesFromExistingPreviewToPublishedOriginal() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    for folder in ["previews", "media"] { try FileManager.default.createDirectory(at: root.appendingPathComponent(folder), withIntermediateDirectories: true) }
    let preview = try library.fileURL("previews/photo.jpg"); let original = try library.fileURL("media/photo.jpg")
    try jpegFixture(at: preview, metadata: [kCGImagePropertyExifDictionary: [kCGImagePropertyExifISOSpeedRatings: [100]]])
    try jpegFixture(at: original, metadata: [kCGImagePropertyExifDictionary: [kCGImagePropertyExifISOSpeedRatings: [400]]])
    var photo = Photo(id: "same-photo", name: "photo.jpg", source: .camera, files: [CameraFile(fileId: 1, name: "photo.jpg")], previewPath: "previews/photo.jpg")
    try library.update(photo)
    let beforeKey = PhotoMetadataRefreshKey(photo: photo, previewRevision: 0)
    let before = await PhotoMetadataSource.available(photo: photo, library: library, cache: nil)
    XCTAssertEqual(before?.url, preview); XCTAssertEqual(PhotoMetadataReader.read(before!.url).iso, "100")
    photo.files[0].localPath = "media/photo.jpg"; try library.update(photo)
    let afterKey = PhotoMetadataRefreshKey(photo: library.photos[0], previewRevision: 0)
    let after = await PhotoMetadataSource.available(photo: library.photos[0], library: library, cache: nil)
    XCTAssertNotEqual(beforeKey, afterKey); XCTAssertEqual(after?.url, original)
    XCTAssertEqual(PhotoMetadataReader.read(after!.url).iso, "400"); XCTAssertTrue(after!.description.contains("本机原片"))
    XCTAssertTrue(library.tasks.isEmpty)
  }
  func testMissingSourcesDoNotCreateFilesOrDownloadTasks() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let library = LibraryStore(root: root)
    let photo = Photo(id: "unavailable", name: "missing.jpg", source: .camera, files: [CameraFile(name: "missing.jpg", localPath: "media/missing.jpg")], previewPath: "previews/missing.jpg")
    try library.update(photo)
    let before = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?.allObjects as? [URL]
    let result = await PhotoMetadataSource.available(photo: photo, library: library, cache: nil)
    XCTAssertNil(result); XCTAssertTrue(library.tasks.isEmpty)
    XCTAssertEqual(Set(before ?? []), Set(FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?.allObjects as? [URL] ?? []))
  }
}
