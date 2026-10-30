import Foundation
import ImageIO

public struct PhotoMetadata: Sendable {
  public var iso: String?
  public var shutter: String?
  public var aperture: String?
  public var camera: String?
  public var lens: String?
  public var capturedAt: String?
  public var focalLength: String?
  public var equivalentFocalLength: String?
  public var exposureBias: String?
  public var pixelSize: String?
  public var latitude: Double?
  public var longitude: Double?
  public var altitude: String?
  public var coordinateDatum: String?
  public var readable = false
  public var mapURL: URL? {
    guard let latitude, let longitude else { return nil }
    if let coordinateDatum, coordinateDatum.uppercased().filter({ $0.isLetter || $0.isNumber }) != "WGS84" { return nil }
    return URL(string: "https://maps.apple.com/?ll=\(latitude),\(longitude)")
  }
  public init() {}
}

/// Reads only file metadata; missing values are never filled from live camera or phone location.
public enum PhotoMetadataReader {
  public static func read(_ url: URL) -> PhotoMetadata {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, [kCGImageSourceShouldCache: false] as CFDictionary) as? [String: Any]
    else { return PhotoMetadata() }
    return parse(properties)
  }
  static func parse(_ properties: [String: Any]) -> PhotoMetadata {
    let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
    let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
    let aux = properties[kCGImagePropertyExifAuxDictionary as String] as? [String: Any] ?? [:]
    let gps = properties[kCGImagePropertyGPSDictionary as String] as? [String: Any] ?? [:]
    var result = PhotoMetadata(); result.readable = true
    result.coordinateDatum = text(gps, kCGImagePropertyGPSMapDatum)
    let iso = (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.doubleValue
      ?? number(exif, kCGImagePropertyExifISOSpeed)
    if let iso, iso.isFinite, iso > 0 { result.iso = decimal(iso) }
    if let seconds = number(exif, kCGImagePropertyExifExposureTime), seconds > 0 {
      result.shutter = ExposureTimeText.seconds(seconds)
    }
    if let aperture = number(exif, kCGImagePropertyExifFNumber), aperture > 0 { result.aperture = "f/" + decimal(aperture) }
    result.camera = text(tiff, kCGImagePropertyTIFFModel)
    result.lens = text(exif, kCGImagePropertyExifLensModel) ?? text(aux, kCGImagePropertyExifAuxLensModel)
    result.capturedAt = text(exif, kCGImagePropertyExifDateTimeOriginal)
    if let offset = exif["OffsetTimeOriginal"] as? String, !offset.isEmpty, result.capturedAt != nil { result.capturedAt! += " " + offset }
    if let focal = number(exif, kCGImagePropertyExifFocalLength), focal > 0 { result.focalLength = decimal(focal) + " mm" }
    if let focal = number(exif, kCGImagePropertyExifFocalLenIn35mmFilm), focal > 0 { result.equivalentFocalLength = decimal(focal) + " mm" }
    if let bias = number(exif, kCGImagePropertyExifExposureBiasValue) { result.exposureBias = (bias > 0 ? "+" : "") + decimal(bias) + " EV" }
    if let width = number(properties, kCGImagePropertyPixelWidth), let height = number(properties, kCGImagePropertyPixelHeight), width > 0, height > 0, width < Double(Int.max), height < Double(Int.max) {
      result.pixelSize = "\(Int(width)) × \(Int(height))"
    }
    if let latitude = coordinate(gps, value: kCGImagePropertyGPSLatitude, reference: kCGImagePropertyGPSLatitudeRef, positive: "N", negative: "S", limit: 90),
      let longitude = coordinate(gps, value: kCGImagePropertyGPSLongitude, reference: kCGImagePropertyGPSLongitudeRef, positive: "E", negative: "W", limit: 180) {
      result.latitude = latitude; result.longitude = longitude
    }
    if let altitude = number(gps, kCGImagePropertyGPSAltitude), altitude >= 0,
      let reference = number(gps, kCGImagePropertyGPSAltitudeRef), reference == 0 || reference == 1 {
      result.altitude = (reference == 1 ? "−" : "") + decimal(altitude) + " m"
    }
    return result
  }
  private static func number(_ values: [String: Any], _ key: CFString) -> Double? {
    guard let value = (values[key as String] as? NSNumber)?.doubleValue, value.isFinite else { return nil }; return value
  }
  private static func text(_ values: [String: Any], _ key: CFString) -> String? {
    guard let value = values[key as String] as? String else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines); return trimmed.isEmpty ? nil : trimmed
  }
  private static func coordinate(_ gps: [String: Any], value: CFString, reference: CFString, positive: String, negative: String, limit: Double) -> Double? {
    guard let amount = number(gps, value), amount >= 0, amount <= limit,
      let ref = text(gps, reference)?.uppercased(), ref == positive || ref == negative else { return nil }
    return ref == negative ? -amount : amount
  }
  private static func decimal(_ value: Double) -> String {
    String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value).replacingOccurrences(of: #"\.?0+$"#, with: "", options: .regularExpression)
  }
}
