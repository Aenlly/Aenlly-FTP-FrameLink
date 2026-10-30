import CoreGraphics
import ImageIO
import XCTest
@testable import FrameLinkCore

func jpegFixture(at url: URL, metadata: [CFString: Any] = [:]) throws {
  let context = try XCTUnwrap(CGContext(data: nil, width: 8, height: 8, bitsPerComponent: 8, bytesPerRow: 32, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
  context.setFillColor(CGColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
  let image = try XCTUnwrap(context.makeImage())
  let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil))
  CGImageDestinationAddImage(destination, image, metadata as CFDictionary)
  XCTAssertTrue(CGImageDestinationFinalize(destination))
}
final class PhotoMetadataTests: XCTestCase {
  func testReadsJPEGSettingsAndLocationWithoutChangingOriginalBytes() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
    defer { try? FileManager.default.removeItem(at: url) }
    try jpegFixture(at: url, metadata: [kCGImagePropertyExifDictionary: [kCGImagePropertyExifISOSpeedRatings: [400], kCGImagePropertyExifExposureTime: 1.0 / 125, kCGImagePropertyExifFNumber: 2.8, kCGImagePropertyExifFocalLength: 50, kCGImagePropertyExifExposureBiasValue: -0.7, kCGImagePropertyExifDateTimeOriginal: "2026:10:31 10:11:12"], kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFModel: "QA Camera"], kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 31.2, kCGImagePropertyGPSLatitudeRef: "S", kCGImagePropertyGPSLongitude: 121.4, kCGImagePropertyGPSLongitudeRef: "W"]])
    let before = try LibraryStore.digest(url); let value = PhotoMetadataReader.read(url)
    XCTAssertEqual(value.iso, "400"); XCTAssertEqual(value.shutter, "1/125 秒"); XCTAssertEqual(value.aperture, "f/2.8")
    XCTAssertEqual(value.camera, "QA Camera"); XCTAssertEqual(value.focalLength, "50 mm"); XCTAssertEqual(value.exposureBias, "-0.7 EV")
    XCTAssertEqual(value.latitude!, -31.2, accuracy: 0.00001); XCTAssertEqual(value.longitude!, -121.4, accuracy: 0.00001)
    XCTAssertNotNil(value.mapURL); XCTAssertEqual(try LibraryStore.digest(url), before)
  }
  func testMissingAndInvalidPropertiesNeverProduceDefaultCameraSettingsOrLocation() {
    let empty = PhotoMetadataReader.parse([:]); XCTAssertNil(empty.iso); XCTAssertNil(empty.shutter); XCTAssertNil(empty.aperture); XCTAssertNil(empty.mapURL)
    let invalid = PhotoMetadataReader.parse([kCGImagePropertyGPSDictionary as String: [kCGImagePropertyGPSLatitude as String: 200, kCGImagePropertyGPSLatitudeRef as String: "N", kCGImagePropertyGPSLongitude as String: 30, kCGImagePropertyGPSLongitudeRef as String: "E"]])
    XCTAssertNil(invalid.latitude); XCTAssertNil(invalid.mapURL)
    let unknownDatum = PhotoMetadataReader.parse([kCGImagePropertyGPSDictionary as String: [kCGImagePropertyGPSLatitude as String: 31, kCGImagePropertyGPSLatitudeRef as String: "N", kCGImagePropertyGPSLongitude as String: 121, kCGImagePropertyGPSLongitudeRef as String: "E", kCGImagePropertyGPSMapDatum as String: "unknown-datum"]])
    XCTAssertEqual(unknownDatum.latitude, 31); XCTAssertEqual(unknownDatum.coordinateDatum, "unknown-datum"); XCTAssertNil(unknownDatum.mapURL)
  }
  func testUnreadableFileIsDistinctFromMissingMetadata() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try Data("not-an-image".utf8).write(to: url); defer { try? FileManager.default.removeItem(at: url) }
    XCTAssertFalse(PhotoMetadataReader.read(url).readable)
  }
  func testZeroExposureAndNonFiniteNumbersAreNotShownAsMeasurements() {
    let value = PhotoMetadataReader.parse([kCGImagePropertyExifDictionary as String: [kCGImagePropertyExifISOSpeedRatings as String: [Double.infinity], kCGImagePropertyExifExposureTime as String: 0, kCGImagePropertyExifFNumber as String: -1]])
    XCTAssertNil(value.iso); XCTAssertNil(value.shutter); XCTAssertNil(value.aperture)
  }
}
